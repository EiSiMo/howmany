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
import itertools
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
# GeCo2 was trained on 1024 x 1024 inputs: the image scaled so exemplars are at most ~80 px,
# top-left, padded with black.
INPUT_SIZE = 1024
# The exported network takes that input with the padding cut off, down to the next multiple of
# 32 (the backbone's coarsest stride, where its feature pyramid levels must line up).
SIZE_MULTIPLE = 32
# The stride of the backbone's coarsest feature level that GeCo2 uses.
BACKBONE_STRIDE = 16
ONNX_DIR = Path(__file__).parent.parent / "data"
# A benchmark image and its exemplars (manifest.csv) that the ONNX export is checked on.
CHECK_IMAGE = "2147.jpg"
CHECK_EXEMPLARS = [
    [122.0, 133.0, 222.0, 231.0],
    [38.0, 2.0, 148.0, 109.0],
    [234.0, 93.0, 324.0, 177.0],
]

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
    """Deformable attention in pure PyTorch, for GeCo2's single-level use.

    Deformable DETR's reference implementation reads the level sizes as Python ints, which an ONNX
    export freezes; this one keeps them as tensors, so the exported input size stays dynamic.
    """
    from torch.nn import functional as F

    batch, _, heads, channels = value.shape
    _, queries, _, levels, points, _ = sampling_locations.shape
    if levels != 1:
        raise ValueError(f"Expected one feature level, got {levels}")
    height, width = spatial_shapes[0, 0], spatial_shapes[0, 1]
    value = value.flatten(2).transpose(1, 2).reshape(batch * heads, channels, height, width)
    grid = (2 * sampling_locations[:, :, :, 0] - 1).transpose(1, 2).flatten(0, 1)
    sampled = F.grid_sample(value, grid, mode="bilinear", padding_mode="zeros", align_corners=False)
    weights = attention_weights.transpose(1, 2).reshape(batch * heads, 1, queries, points)
    output = (sampled * weights).sum(-1).view(batch, heads * channels, queries)
    return output.transpose(1, 2).contiguous()


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

    Inputs: a normalized (1, 3, H, W) image and (1, K, 4) exemplar boxes in its pixels. The image
    is GeCo2's 1024 x 1024 input with the padding cut off: H and W are multiples of 32 up to 1024.
    Positional encodings are those of the 1024 x 1024 input, cropped to H x W, so every pixel is
    encoded as before. Outputs: objectness (1, H/2, W/2) and box offsets (1, H/2, W/2, 4), the
    distances in input pixels from each cell to the left, top, right and bottom box edges.
    Mirrors GeCo2's CNT.forward for a batch of one.
    """
    import torch
    from torch.nn import functional as F
    from torchvision.ops import roi_align  # type: ignore[import-not-found]

    trunk = model.backbone.trunk
    sine = model.backbone.neck.position_encoding
    adapter = model.adapt_features

    def trunk_position(size: Any) -> Any:
        """Hiera's position embedding for a 1024 x 1024 input, cropped to the given token grid."""
        full = INPUT_SIZE // 4
        window = trunk.pos_embed_window
        position = F.interpolate(trunk.pos_embed, size=(full, full), mode="bicubic")
        # repeat, unlike tile, exports to ONNX without a branch on the repeat count.
        position = position + window.repeat(1, 1, full // window.shape[2], full // window.shape[3])
        return position[:, :, : size[0], : size[1]].permute(0, 2, 3, 1)

    trunk._get_pos_embed = trunk_position

    def cells(level: Any) -> tuple[Any, Any]:
        """Column and row numbers (1, 2, ...) of a feature level's cells, taken from its shape."""
        ones = torch.ones_like(level[0, 0])
        return ones.cumsum(1), ones.cumsum(0)

    def sine_position(level: Any, stride: int) -> Any:
        """The backbone's sine position encoding over a 1024 x 1024 input, cropped to the level."""
        columns, rows = cells(level)
        full = INPUT_SIZE // stride + 1e-6
        encoded_x, encoded_y = sine._encode_xy(columns.flatten() / full, rows.flatten() / full)
        encoded = torch.cat([encoded_y, encoded_x], dim=1)
        return encoded.reshape(level.shape[2], level.shape[3], -1).permute(2, 0, 1)[None]

    def dense_position(level: Any) -> Any:
        """SAM2's prompt encoding of a 1024 x 1024 input's coarse grid, cropped to the level."""
        columns, rows = cells(level)
        full = INPUT_SIZE // BACKBONE_STRIDE
        points = torch.stack([(columns - 0.5) / full, (rows - 0.5) / full], dim=-1)
        return model.sam_prompt_encoder.pe_layer._pe_encoding(points).permute(2, 0, 1)[None]

    def attend_within(level: Any, suffix: str) -> None:
        """Size the adapter's deformable attention over a level to the level's actual shape.

        Its sampling points are relative to the level, so they reach the same cells as before.
        """
        columns, rows = cells(level)
        x = (columns - 0.5) / columns[:, -1:]
        y = (rows - 0.5) / rows[-1:]
        setattr(adapter, f"spatial_shapes{suffix}", torch._shape_as_tensor(level)[2:][None])
        setattr(adapter, f"reference_points{suffix}", torch.stack([x, y], -1).reshape(1, -1, 1, 2))

    class DenseGeCo2(torch.nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.model = model

        def forward(self, image: Any, exemplars: Any) -> tuple[Any, Any]:
            m = self.model
            features = m.backbone(image)
            coarse = features["vision_features"]
            fine, middle = features["backbone_fpn"][0], features["backbone_fpn"][1]
            fine_stride, middle_stride = BACKBONE_STRIDE // 4, BACKBONE_STRIDE // 2
            count = exemplars.shape[1]
            rois = torch.cat([torch.zeros_like(exemplars[0, :, :1]), exemplars[0]], dim=1)

            def pool(level: Any, stride: int) -> Any:
                pooled = roi_align(
                    level, rois, output_size=1, spatial_scale=1 / stride, aligned=True
                )
                return pooled.reshape(1, count, m.emb_dim)

            sizes = torch.stack(
                [exemplars[..., 2] - exemplars[..., 0], exemplars[..., 3] - exemplars[..., 1]], -1
            )
            shape = m.shape_or_objectness(sizes).reshape(1, -1, m.emb_dim)
            for level, suffix in ((coarse, ""), (fine, "1"), (middle, "2")):
                attend_within(level, suffix)
            adapted, _ = m.adapt_features(
                image_embeddings=coarse,
                image_pe=dense_position(coarse),
                prototype_embeddings=torch.cat([pool(coarse, BACKBONE_STRIDE), shape], dim=1),
                hq_features=features["backbone_fpn"],
                hq_prototypes=[
                    torch.cat([pool(fine, fine_stride), shape], dim=1),
                    torch.cat([pool(middle, middle_stride), shape], dim=1),
                ],
                hq_pos=[sine_position(fine, fine_stride), sine_position(middle, middle_stride)],
            )
            _, _, rows, columns = adapted.shape
            flat = adapted.view(1, m.emb_dim, -1).permute(0, 2, 1)
            objectness = m.class_embed(flat).view(1, rows, columns)
            # GeCo2 predicts offsets as fractions of the 1024 x 1024 input it was trained on.
            offsets = m.bbox_embed(flat).sigmoid().view(1, rows, columns, 4) * INPUT_SIZE
            return objectness, offsets

    return DenseGeCo2().eval()


def _peaks(objectness: Any) -> Any:
    """A mask of the objectness map's 3 x 3 local maxima above 1/8 of its maximum, as in GeCo2."""
    import numpy as np

    padded = np.pad(objectness, 1, constant_values=-np.inf)
    neighbourhood = np.lib.stride_tricks.sliding_window_view(padded, (3, 3)).max(axis=(2, 3))
    return (neighbourhood == objectness) & (objectness > objectness.max() / 8)


def _check_inputs(image_bytes: bytes, exemplars: list[list[float]]) -> tuple[Any, Any, Any]:
    """GeCo2's own 1024 x 1024 input for an image, that input with the padding cut off to a
    multiple of 32, and the exemplars in input pixels."""
    import torch
    from PIL import Image
    from torchvision import transforms as T
    from utils.data import resize_and_pad

    pixels = T.ToTensor()(Image.open(io.BytesIO(image_bytes)).convert("RGB"))
    padded, boxes, scale = resize_and_pad(pixels, torch.tensor(exemplars), size=float(INPUT_SIZE))
    padded = T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])(padded)[None]
    height, width = (
        -(-int(side * scale) // SIZE_MULTIPLE) * SIZE_MULTIPLE for side in pixels.shape[1:]
    )
    return padded, padded[..., :height, :width], boxes[None]


@app.function(image=image, cpu=8, memory=32768, timeout=3600)
def export_onnx(image_bytes: bytes, exemplars: list[list[float]]) -> dict[str, bytes]:
    """Export the dense network to ONNX in float32 and int8, checked against PyTorch on an image.

    The network must match GeCo2 on the padded 1024 x 1024 input, find the same boxes on the
    content-sized input, and the export must match the network at several non-square input sizes
    and exemplar counts.
    """
    import numpy as np
    import onnxruntime
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    log = logging.getLogger(__name__)
    model = _load_model("cpu")
    padded, content, boxes = _check_inputs(image_bytes, exemplars)
    network = _dense_network(model)
    exemplar_counts = (boxes[:, :1], boxes)

    def dense(image: Any, exemplars: Any) -> tuple[Any, Any]:
        with torch.no_grad():
            objectness, offsets = network(image, exemplars)
        return objectness[0].numpy(), offsets[0].numpy()

    def deviation(actual: Any, expected: Any) -> float:
        return float(np.abs(actual - expected).max() / np.abs(expected).max())

    # On the padded input, cropping the positional encodings must change nothing.
    for boxes_ in exemplar_counts:
        # GeCo2 only builds its 1024 x 1024 attention grids when its inputs move to another
        # device, which never happens on the CPU; the dense network replaces them with its own.
        adapter = model.adapt_features
        for suffix, side in (("", 64), ("1", 256), ("2", 128)):
            shapes, ratios = torch.tensor([[side, side]]), torch.tensor([[1.0, 1.0]])
            setattr(adapter, f"spatial_shapes{suffix}", shapes)
            points = adapter.get_reference_points(shapes, ratios, device="cpu")
            setattr(adapter, f"reference_points{suffix}", points)
        with torch.no_grad():
            _, _, centerness, coordinates, _ = model(padded, boxes_)
        objectness, offsets = dense(padded, boxes_)
        expected_offsets = coordinates[0].permute(1, 2, 0).numpy() * INPUT_SIZE
        for name, error in (
            ("objectness", deviation(objectness, centerness[0, 0].numpy())),
            ("offsets", deviation(offsets, expected_offsets)),
        ):
            if error > 1e-4:
                raise RuntimeError(f"Network deviates from GeCo2 by {error:.1e} in {name}")

    # Cutting off the padding changes the features from the backbone's first global attention on,
    # which no longer sees the padding; the benchmark measures what that does to the counts. The
    # box offsets must stay fractions of 1024 px, not become fractions of the input's sides.
    with torch.no_grad():
        for stage, (full, cut) in enumerate(
            zip(model.backbone.trunk(padded), model.backbone.trunk(content), strict=True)
        ):
            full = full[..., : cut.shape[2], : cut.shape[3]]
            log.info(
                "Trunk stage %d deviates by %.3f (mean) and %.3f (max) of its mean magnitude",
                stage,
                float((cut - full).abs().mean() / full.abs().mean()),
                float((cut - full).abs().max() / full.abs().mean()),
            )
    for boxes_ in exemplar_counts:
        padded_objectness, padded_offsets = dense(padded, boxes_)
        objectness, offsets = dense(content, boxes_)
        rows, columns = objectness.shape
        padded_found = _peaks(padded_objectness[:rows, :columns])
        found = _peaks(objectness)
        # Peaks found by both, allowing them to move to a neighbouring cell.
        near = np.lib.stride_tricks.sliding_window_view(np.pad(found, 1), (3, 3)).any(axis=(2, 3))
        expected_offsets = padded_offsets[:rows, :columns][padded_found]
        offset_error = np.abs(offsets[padded_found] - expected_offsets) / expected_offsets
        log.info(
            "%s input vs padded, %d exemplars: %d vs %d peaks (%d within a cell), "
            "offsets at the padded peaks deviate by %.1f%% (median)",
            tuple(content.shape[2:]),
            boxes_.shape[1],
            found.sum(),
            padded_found.sum(),
            (padded_found & near).sum(),
            100 * np.median(offset_error),
        )
        if np.median(offset_error) > 0.2:
            raise RuntimeError("Box offsets on the content-sized input differ from the padded one")

    # Trace at a size where Hiera pads its attention windows, so the padding stays in the graph.
    with torch.no_grad():
        torch.onnx.export(
            network,
            (padded[..., :640, :960], boxes),
            "/tmp/fp32.onnx",
            input_names=["image", "exemplars"],
            output_names=["objectness", "offsets"],
            dynamic_axes={
                "image": {2: "height", 3: "width"},
                "exemplars": {1: "count"},
                "objectness": {1: "rows", 2: "columns"},
                "offsets": {1: "rows", 2: "columns"},
            },
            opset_version=17,
            dynamo=False,
        )
    quantize_dynamic("/tmp/fp32.onnx", "/tmp/int8.onnx", weight_type=QuantType.QUInt8)

    session = onnxruntime.InferenceSession("/tmp/fp32.onnx", providers=["CPUExecutionProvider"])
    # Sizes with and without window padding in Hiera, each with one and three exemplars.
    sizes = [tuple(content.shape[2:]), (768, 1024), (1024, 448), (224, 1024)]
    for (height, width), boxes_ in itertools.product(sizes, exemplar_counts):
        image_ = padded[..., :height, :width]
        expected = dense(image_, boxes_)
        actual = session.run(
            ["objectness", "offsets"], {"image": image_.numpy(), "exemplars": boxes_.numpy()}
        )
        for name, value, reference in zip(("objectness", "offsets"), actual, expected, strict=True):
            error = deviation(value[0], reference)
            if error > 1e-3:
                raise RuntimeError(
                    f"ONNX {name} deviates by {error:.1e} at {height} x {width} with "
                    f"{boxes_.shape[1]} exemplars"
                )
        log.info("ONNX matches PyTorch at %d x %d, %d exemplars", height, width, boxes_.shape[1])
    return {name: Path(f"/tmp/{name}.onnx").read_bytes() for name in ("fp32", "int8")}


@app.local_entrypoint()
def export() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    image_bytes = (ONNX_DIR / "images" / CHECK_IMAGE).read_bytes()
    for name, model in export_onnx.remote(image_bytes, CHECK_EXEMPLARS).items():
        target = ONNX_DIR / f"geco2-{name}.onnx"
        target.write_bytes(model)
        logging.info("Saved %s (%.0f MB)", target, len(model) / 1e6)


@cache
def _geco2() -> Any:
    return modal.Cls.from_name(APP_NAME, "GeCo2")()


def quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int:
    boxes = _geco2().detect.remote(image_path.read_bytes(), [list(box) for box in exemplars])
    return len(boxes)
