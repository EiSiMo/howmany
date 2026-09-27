"""EfficientSAM3: SAM 3 distilled into a phone-sized model that finds every instance of a concept.

EfficientSAM3 (Zeng et al., 2025, Apache 2.0, https://github.com/SimonZeng7108/efficientsam3)
distills Meta's SAM 3 (848M parameters) into ~90M: an EfficientViT image encoder and a
MobileCLIP text encoder in front of SAM 3's detector. We use the EV-M variant, the smallest,
with the authors' fine-tuned weights. It was not trained on FSC-147.

Prompts: the exemplar boxes if there are any (a tap), otherwise the text. Every detection above
a confidence threshold counts as one object. Like SAM 3, it detects at most 200 objects.

The distilled model's confidences run lower than SAM 3's, so the authors' default threshold of
0.5 finds almost nothing. The thresholds below are calibrated on 100 FSC-147 validation images
(manifest-val.csv), which are disjoint from the benchmark's test images.

It runs as a Modal app on a GPU. Deploy once, then calibrate if the model changes:

    uv run modal deploy prototypes/prototype-5.py
    uv run modal run prototypes/prototype-5.py
"""

from __future__ import annotations

import io
import json
import logging
import sys
from collections.abc import Sequence
from functools import cache
from pathlib import Path
from typing import TYPE_CHECKING, Any

import modal
import numpy as np

if TYPE_CHECKING:
    from dataset import Box

logger = logging.getLogger(__name__)

APP_NAME = "quantify-prototype-5"
REPO_DIR = "/efficientsam3"
REPO_COMMIT = "bd0936c788fed8d51fa799437f05abd97b401b06"
WEIGHTS_URL = (
    "https://huggingface.co/Simon7108528/EfficientSAM3/resolve/"
    "85b05896928f974e308f889d7ccb2eefc069de98/efficientsam3_ft/efficientsam3_efficientvit.pt"
)
WEIGHTS_PATH = f"{REPO_DIR}/efficientsam3_efficientvit.pt"
# Confidence thresholds minimising the count MAE on the validation manifest, for a text prompt
# and for a single exemplar (a tap).
THRESHOLDS = {"text": 0.16, "exemplars": 0.21}
# The EV-M configuration, as in the authors' ONNX export script.
MODEL_CONFIG = {
    "backbone_type": "efficientvit",
    "model_name": "b1",
    "text_encoder_type": "MobileCLIP-S0",
    "text_encoder_context_length": 16,
}

app = modal.App(APP_NAME)
container_image = (
    modal.Image.debian_slim(python_version="3.12")
    .apt_install("git", "curl")
    .pip_install(
        "torch==2.7.1",
        "torchvision==0.22.1",
        index_url="https://download.pytorch.org/whl/cu126",
    )
    .run_commands(
        f"git clone https://github.com/SimonZeng7108/efficientsam3 {REPO_DIR}",
        f"git -C {REPO_DIR} checkout {REPO_COMMIT}",
        # Undeclared runtime dependencies; OpenCV is the video backend the package requires
        # even for images.
        f"pip install -e {REPO_DIR}/sam3 einops==0.8.1 pillow==11.3.0 "
        "opencv-python-headless==4.11.0.86 pycocotools==2.0.8 psutil==7.0.0 omegaconf==2.3.0",
        f"curl -fL -o {WEIGHTS_PATH} {WEIGHTS_URL}",
        # Build the model once on the CPU, so a missing dependency or a mismatched checkpoint
        # fails the image build instead of crash-looping GPU containers.
        f"python -c 'from sam3.model_builder import build_efficientsam3_image_model as b; "
        f'b(device="cpu", checkpoint_path="{WEIGHTS_PATH}", **{json.dumps(MODEL_CONFIG)})\'',
    )
)


@app.cls(image=container_image, gpu="L4", scaledown_window=120)
class EfficientSam3:
    @modal.enter()
    def load(self) -> None:
        # These modules only exist inside the container image.
        from sam3.model.sam3_image_processor import (  # type: ignore[import-not-found]
            Sam3Processor,
        )
        from sam3.model_builder import (  # type: ignore[import-not-found]
            build_efficientsam3_image_model,
        )

        model = build_efficientsam3_image_model(
            device="cuda", checkpoint_path=WEIGHTS_PATH, **MODEL_CONFIG
        )
        # Keep every detection; quantify() applies the calibrated threshold.
        self.processor = Sam3Processor(model, device="cuda", confidence_threshold=0.0)

    @modal.method()
    def detect(
        self, image_bytes: bytes, exemplars: list[list[float]], text: str
    ) -> list[list[float]]:
        """Return (x1, y1, x2, y2, confidence) in image pixels for every candidate object."""
        import torch
        from PIL import Image

        image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        state = self.processor.set_image(image)
        if exemplars:
            width, height = image.size
            for x1, y1, x2, y2 in exemplars:
                # SAM 3 takes boxes as normalized (center x, center y, width, height).
                box = [(x1 + x2) / 2 / width, (y1 + y2) / 2 / height]
                box += [(x2 - x1) / width, (y2 - y1) / height]
                state = self.processor.add_geometric_prompt(box, True, state)
        else:
            state = self.processor.set_text_prompt(text, state)
        detections = torch.cat([state["boxes"], state["scores"][:, None]], dim=1)
        return detections.tolist()


@cache
def _model() -> Any:
    return modal.Cls.from_name(APP_NAME, "EfficientSam3")()


def _request(
    image_path: Path, exemplars: Sequence[Box], text: str
) -> tuple[bytes, list[list[float]], str]:
    return image_path.read_bytes(), [list(box) for box in exemplars], text


def quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int:
    detections = _model().detect.remote(*_request(image_path, exemplars, text))
    threshold = THRESHOLDS["exemplars" if exemplars else "text"]
    return sum(detection[4] > threshold for detection in detections)


@app.local_entrypoint()
def calibrate() -> None:
    """Find the thresholds minimising the count MAE on the validation manifest."""
    benchmark_dir = Path(__file__).parent.parent
    sys.path.insert(0, str(benchmark_dir))
    from dataset import configure_logging, load_samples

    configure_logging()
    samples = load_samples(benchmark_dir / "manifest-val.csv", exemplars=1)
    counts = np.array([sample.true_count for sample in samples])
    candidates = np.arange(0.05, 0.96, 0.01)
    for mode in ("text", "exemplars"):
        requests = [
            _request(s.image_path, s.exemplars if mode == "exemplars" else (), s.category)
            for s in samples
        ]
        scores = [np.array([d[4] for d in ds]) for ds in EfficientSam3().detect.starmap(requests)]
        errors = [
            np.mean([abs((s > t).sum() - c) for s, c in zip(scores, counts, strict=True)])
            for t in candidates
        ]
        best = int(np.argmin(errors))
        logger.info("%s: threshold %.2f, val MAE %.2f", mode, candidates[best], errors[best])
