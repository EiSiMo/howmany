"""GeCo2: an off-the-shelf few-shot counter that detects every instance similar to the exemplars.

GeCo2 (Pelhan et al., AAAI 2026, MIT license, https://github.com/jerpelhan/GECO2) matches the
exemplars against a SAM2 Hiera-B+ feature map at several scales and predicts one box per object;
the count is the number of boxes. We use the authors' weights trained on FSC-147's train split,
which is disjoint from the benchmark's test images.

GeCo2 needs a CUDA GPU and its own environment, so the model runs as a Modal app and quantify()
sends each image to it. Deploy once before running the benchmark:

    uv run modal deploy prototypes/prototype-3.py

The same environment exports GeCo2 to ONNX for on-device inference (see prototype 4):

    uv run modal run prototypes/prototype-3.py
"""

from __future__ import annotations

import io
import logging
import os
import sys
import types
from collections.abc import Sequence
from functools import cache
from pathlib import Path
from typing import TYPE_CHECKING, Any

import modal

if TYPE_CHECKING:
    from dataset import Box


APP_NAME = "quantify-prototype-3"
GECO2_DIR = "/geco2"
GECO2_COMMIT = "b7086c1db5d9bf2a1718b77eb76715c5f5b963cb"
ASSETS_URL = (
    "https://huggingface.co/datasets/jerpelhan/geco2-assets/resolve/"
    "ed3c8ff3753e731fd7074862c0ea49d908785335"
)
SAM2_URL = "https://dl.fbaipublicfiles.com/segment_anything_2/072824/sam2_hiera_base_plus.pt"
TORCH_HUB_CHECKPOINTS = "/root/.cache/torch/hub/checkpoints"
# Post-processing as in GeCo2's FSC-147 evaluation: keep boxes scoring above 11% of the best
# score, then suppress duplicates.
SCORE_RATIO = 0.11
NMS_IOU = 0.5
PRETRAINED_MODULES = ("backbone.", "sam_mask.")
INPUT_SIZE = 1024
ONNX_DIR = Path(__file__).parent.parent / "data"

app = modal.App(APP_NAME)
image = (
    modal.Image.debian_slim(python_version="3.10")
    .apt_install("git", "curl")
    .pip_install(
        "torch==2.7.1",
        "torchvision==0.22.1",
        index_url="https://download.pytorch.org/whl/cu126",
    )
    .pip_install(
        "numpy<2",
        "hydra-core==1.3.2",
        "scikit-image==0.25.2",
        "pycocotools==2.0.8",
        "einops==0.8.1",
        "opencv-python-headless==4.11.0.86",
        "pillow==10.4.0",
        "tqdm==4.67.1",
        "onnx==1.18.0",
        "onnxruntime==1.22.0",
    )
    .run_commands(
        f"git clone https://github.com/jerpelhan/GECO2 {GECO2_DIR}",
        f"git -C {GECO2_DIR} checkout {GECO2_COMMIT}",
        f"cp -r {GECO2_DIR}/Deformable-DETR/models/ops {GECO2_DIR}/models/ops",
        f"curl -fL -o {GECO2_DIR}/GECO2FSCD.pth {ASSETS_URL}/weights/GECO2FSCD.pth",
        f"mkdir -p {TORCH_HUB_CHECKPOINTS}",
        f"curl -fL -o {TORCH_HUB_CHECKPOINTS}/sam2_hiera_base_plus.pt {SAM2_URL}",
        # Fail at build time, not in a GPU container crash loop, if a dependency is missing.
        f"cd {GECO2_DIR} && python -c 'import sys, types; "
        'sys.modules["MultiScaleDeformableAttention"] = types.ModuleType("MSDA"); '
        "import models.counter_infer, utils.data'",
    )
)


def _ms_deform_attn_forward(
    value: Any,
    spatial_shapes: Any,
    level_start_index: Any,
    sampling_locations: Any,
    attention_weights: Any,
    im2col_step: int,
) -> Any:
    from models.ops.functions.ms_deform_attn_func import (  # type: ignore[import-not-found]
        ms_deform_attn_core_pytorch,
    )

    return ms_deform_attn_core_pytorch(value, spatial_shapes, sampling_locations, attention_weights)


def _load_model(device: str) -> Any:
    """Build GeCo2 with the authors' FSC-147 weights; only works inside the container image."""
    import torch

    # GeCo2's modules only exist inside the container image, hence the type: ignores below.
    os.chdir(GECO2_DIR)
    sys.path.insert(0, GECO2_DIR)
    # The prebuilt CUDA kernel for deformable attention does not match current torch builds;
    # the pure PyTorch reference computes the same thing and is what a mobile export needs.
    kernel = types.ModuleType("MultiScaleDeformableAttention")
    kernel.ms_deform_attn_forward = _ms_deform_attn_forward  # type: ignore[attr-defined]
    sys.modules[kernel.__name__] = kernel
    from models.counter_infer import build_model  # type: ignore[import-not-found]
    from utils.arg_parser import get_argparser  # type: ignore[import-not-found]

    model = build_model(get_argparser().parse_args([])).to(device).eval()
    checkpoint = torch.load("GECO2FSCD.pth", map_location="cpu", weights_only=True)
    state = {key.removeprefix("module."): value for key, value in checkpoint["model"].items()}
    result = model.load_state_dict(state, strict=False)
    # The checkpoint also holds training-only heads, and the backbone and mask decoder load
    # their pretrained SAM2 weights themselves; any other missing weight is a real error.
    missing = [key for key in result.missing_keys if not key.startswith(PRETRAINED_MODULES)]
    if missing:
        raise RuntimeError(f"GeCo2 checkpoint lacks weights: {missing[:5]}")
    return model


@app.cls(image=image, gpu="L4", scaledown_window=120)
class GeCo2:
    @modal.enter()
    def load(self) -> None:
        self.model = _load_model("cuda")

    @modal.method()
    def detect(self, image_bytes: bytes, exemplars: list[list[float]]) -> list[list[float]]:
        """Return one (x1, y1, x2, y2) box in image pixels per detected object."""
        import torch
        from PIL import Image
        from torchvision import ops  # type: ignore[import-not-found]
        from torchvision import transforms as T
        from utils.data import resize_and_pad  # type: ignore[import-not-found]

        pixels = T.ToTensor()(Image.open(io.BytesIO(image_bytes)).convert("RGB"))
        boxes = torch.tensor(exemplars, dtype=torch.float32)
        # Scales the image so that exemplars are at most ~80 px, then pads to 1024 x 1024.
        padded, boxes, scale = resize_and_pad(pixels, boxes, size=1024.0)
        padded = T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])(padded)
        with torch.no_grad():
            outputs: list[dict[str, Any]] = self.model(
                padded.unsqueeze(0).cuda(), boxes.unsqueeze(0).cuda()
            )[0]

        predicted = outputs[0]["pred_boxes"].reshape(-1, 4)
        scores = outputs[0]["box_v"].reshape(-1)
        if len(scores) == 0:
            return []
        keep = scores > scores.max() * SCORE_RATIO
        predicted, scores = predicted[keep], scores[keep]
        predicted = predicted[ops.nms(predicted, scores, NMS_IOU)].clamp(0, 1) * 1024
        # Drop boxes centred in the padding, then map back to original image pixels.
        centers = (predicted[:, :2] + predicted[:, 2:]) / 2
        height, width = pixels.shape[1:]
        inside = (centers[:, 0] < width * scale) & (centers[:, 1] < height * scale)
        return (predicted[inside] / scale).tolist()  # type: ignore[no-any-return]


def _dense_network(model: Any) -> Any:
    """Wrap GeCo2 up to its dense outputs, leaving out SAM2 box refinement and post-processing.

    Inputs: a normalized, padded (1, 3, 1024, 1024) image and (1, K, 4) exemplar boxes in its
    pixels. Outputs: objectness (1, 64, 64) and box offsets (1, 64, 64, 4) as fractions of the
    image side (left, top, right, bottom). Mirrors GeCo2's CNT.forward for a batch of one.
    """
    import torch
    from torchvision.ops import roi_align  # type: ignore[import-not-found]

    class DenseGeCo2(torch.nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.model = model

        def forward(self, image: Any, exemplars: Any) -> tuple[Any, Any]:
            m = self.model
            features = m.backbone(image)
            coarse = features["vision_features"]
            reduction = INPUT_SIZE / coarse.shape[-1]
            count = exemplars.shape[1]
            rois = torch.cat([torch.zeros_like(exemplars[0, :, :1]), exemplars[0]], dim=1)

            def pool(level: Any, scale: float) -> Any:
                pooled = roi_align(level, rois, output_size=1, spatial_scale=scale, aligned=True)
                return pooled.reshape(1, count, m.emb_dim)

            sizes = torch.stack(
                [exemplars[..., 2] - exemplars[..., 0], exemplars[..., 3] - exemplars[..., 1]], -1
            )
            shape = m.shape_or_objectness(sizes).reshape(1, -1, m.emb_dim)
            fine, middle = features["backbone_fpn"][0], features["backbone_fpn"][1]
            adapted, _ = m.adapt_features(
                image_embeddings=coarse,
                image_pe=m.sam_prompt_encoder.get_dense_pe(),
                prototype_embeddings=torch.cat([pool(coarse, 1 / reduction), shape], dim=1),
                hq_features=features["backbone_fpn"],
                hq_prototypes=[
                    torch.cat([pool(fine, 4 / reduction), shape], dim=1),
                    torch.cat([pool(middle, 2 / reduction), shape], dim=1),
                ],
                hq_pos=features["vision_pos_enc"],
            )
            _, _, rows, columns = adapted.shape
            flat = adapted.view(1, m.emb_dim, -1).permute(0, 2, 1)
            objectness = m.class_embed(flat).view(1, rows, columns)
            offsets = m.bbox_embed(flat).sigmoid().view(1, rows, columns, 4)
            return objectness, offsets

    return DenseGeCo2().eval()


@app.function(image=image, cpu=8, memory=32768, timeout=3600)
def export_onnx() -> dict[str, bytes]:
    """Export the dense network to ONNX in float32 and int8, checked against PyTorch."""
    import numpy as np
    import onnxruntime
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic

    model = _load_model("cpu")
    # GeCo2 only builds these lazily when its inputs move to another device, which never
    # happens on the CPU.
    adapter = model.adapt_features
    for suffix in ("", "1", "2"):
        shapes = getattr(adapter, f"spatial_shapes{suffix}")
        ratios = getattr(adapter, f"valid_ratios{suffix}")
        points = adapter.get_reference_points(shapes, ratios, device="cpu")
        setattr(adapter, f"reference_points{suffix}", points)
    network = _dense_network(model)
    image = torch.randn(1, 3, INPUT_SIZE, INPUT_SIZE)
    exemplars = torch.tensor([[[100.0, 120.0, 160.0, 170.0], [400.0, 380.0, 470.0, 440.0]]])
    with torch.no_grad():
        torch.onnx.export(
            network,
            (image, exemplars),
            "/tmp/fp32.onnx",
            input_names=["image", "exemplars"],
            output_names=["objectness", "offsets"],
            dynamic_axes={"exemplars": {1: "count"}},
            opset_version=17,
            dynamo=False,
        )
    quantize_dynamic("/tmp/fp32.onnx", "/tmp/int8.onnx", weight_type=QuantType.QUInt8)

    session = onnxruntime.InferenceSession("/tmp/fp32.onnx", providers=["CPUExecutionProvider"])
    # Check both a single exemplar and three, since the exemplar count is a dynamic axis.
    for boxes in (exemplars[:, :1], torch.cat([exemplars, exemplars[:, :1] + 50], dim=1)):
        with torch.no_grad():
            expected = network(image, boxes)[0].numpy()
        actual = session.run(["objectness"], {"image": image.numpy(), "exemplars": boxes.numpy()})
        difference = float(np.abs(actual[0] - expected).max())
        if difference > 1e-3 * float(np.abs(expected).max()):
            raise RuntimeError(f"ONNX output deviates by {difference} for {boxes.shape[1]} boxes")
    return {name: Path(f"/tmp/{name}.onnx").read_bytes() for name in ("fp32", "int8")}


@app.local_entrypoint()
def export() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    for name, model in export_onnx.remote().items():
        target = ONNX_DIR / f"geco2-{name}.onnx"
        target.write_bytes(model)
        logging.info("Saved %s (%.0f MB)", target, len(model) / 1e6)


@cache
def _geco2() -> Any:
    return modal.Cls.from_name(APP_NAME, "GeCo2")()


def quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int:
    boxes = _geco2().detect.remote(image_path.read_bytes(), [list(box) for box in exemplars])
    return len(boxes)
