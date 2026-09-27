"""Export GeCo2 (prototype 3) to ONNX for on-device inference, as prototype 4 and the app run it.

The export covers GeCo2's dense network, up to its objectness map and box offsets; picking boxes
from them is left to the caller. It runs on Modal in prototype 3's container image, checks the
network against GeCo2 and the export against the network on a benchmark image, and writes
data/geco2-fp32.onnx and its dynamically quantized data/geco2-int8.onnx.

Usage: uv run modal run export_geco2.py
"""

import io
import itertools
import logging
import tempfile
from collections.abc import Sequence
from pathlib import Path, PurePosixPath
from typing import Any

import modal

from dataset import BENCHMARK_DIR, DATA_DIR, IMAGE_DIR, configure_logging
from prototype import load_module

logger = logging.getLogger(__name__)

GECO2_PROTOTYPE = BENCHMARK_DIR / "prototypes" / "prototype-3.py"
REMOTE_DIR = PurePosixPath("/root")
# The exported network takes GeCo2's input with the padding cut off, down to the next multiple of
# 32 (the backbone's coarsest stride, where its feature pyramid levels must line up).
SIZE_MULTIPLE = 32
# The stride of the backbone's coarsest feature level that GeCo2 uses.
BACKBONE_STRIDE = 16
# A benchmark image and its exemplars (manifest.csv) that the ONNX export is checked on.
CHECK_IMAGE = "2147.jpg"
CHECK_EXEMPLARS = [
    [122.0, 133.0, 222.0, 231.0],
    [38.0, 2.0, 148.0, 109.0],
    [234.0, 93.0, 324.0, 177.0],
]
# The largest relative deviation tolerated between the network and GeCo2, and between the export
# and the network, and the median one tolerated in box offsets once the padding is cut off.
NETWORK_TOLERANCE = 1e-4
EXPORT_TOLERANCE = 1e-3
CONTENT_OFFSET_TOLERANCE = 0.2
# Export sizes checked besides the check image's: with and without window padding in Hiera.
EXPORT_CHECK_SIZES = ((768, 1024), (1024, 448), (224, 1024))
OUTPUTS = ("objectness", "offsets")

# GeCo2's environment, model and input size are prototype 3's; its file is added to the
# container so this module imports there too.
geco2 = load_module(GECO2_PROTOTYPE)
INPUT_SIZE: int = geco2.INPUT_SIZE

app = modal.App("quantify-export-geco2")
remote_prototype = str(REMOTE_DIR / GECO2_PROTOTYPE.relative_to(BENCHMARK_DIR))
container_image = geco2.container_image.add_local_python_source(
    "dataset", "prototype"
).add_local_file(GECO2_PROTOTYPE, remote_prototype)


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
    from torchvision import transforms as T  # type: ignore[import-not-found]
    from utils.data import resize_and_pad  # type: ignore[import-not-found]

    pixels = T.ToTensor()(Image.open(io.BytesIO(image_bytes)).convert("RGB"))
    padded, boxes, scale = resize_and_pad(pixels, torch.tensor(exemplars), size=float(INPUT_SIZE))
    padded = T.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225])(padded)[None]
    height, width = (
        -(-int(side * scale) // SIZE_MULTIPLE) * SIZE_MULTIPLE for side in pixels.shape[1:]
    )
    return padded, padded[..., :height, :width], boxes[None]


def _dense(network: Any, image: Any, exemplars: Any) -> tuple[Any, Any]:
    """The network's objectness map and box offsets for one image, as NumPy arrays."""
    import torch

    with torch.no_grad():
        objectness, offsets = network(image, exemplars)
    return objectness[0].numpy(), offsets[0].numpy()


def _deviation(actual: Any, expected: Any) -> float:
    """The largest absolute difference, relative to the largest expected magnitude."""
    import numpy as np

    return float(np.abs(actual - expected).max() / np.abs(expected).max())


def _check_network(model: Any, network: Any, padded: Any, exemplar_sets: Sequence[Any]) -> None:
    """On the padded input, cropping the positional encodings must change nothing."""
    import torch

    for boxes in exemplar_sets:
        # GeCo2 only builds its 1024 x 1024 attention grids when its inputs move to another
        # device, which never happens on the CPU; the dense network replaces them with its own.
        adapter = model.adapt_features
        for suffix, side in (("", 64), ("1", 256), ("2", 128)):
            shapes, ratios = torch.tensor([[side, side]]), torch.tensor([[1.0, 1.0]])
            setattr(adapter, f"spatial_shapes{suffix}", shapes)
            points = adapter.get_reference_points(shapes, ratios, device="cpu")
            setattr(adapter, f"reference_points{suffix}", points)
        with torch.no_grad():
            _, _, centerness, coordinates, _ = model(padded, boxes)
        objectness, offsets = _dense(network, padded, boxes)
        expected_offsets = coordinates[0].permute(1, 2, 0).numpy() * INPUT_SIZE
        for name, error in (
            ("objectness", _deviation(objectness, centerness[0, 0].numpy())),
            ("offsets", _deviation(offsets, expected_offsets)),
        ):
            if error > NETWORK_TOLERANCE:
                raise RuntimeError(f"Network deviates from GeCo2 by {error:.1e} in {name}")


def _check_content_input(
    model: Any, network: Any, padded: Any, content: Any, exemplar_sets: Sequence[Any]
) -> None:
    """Compare the content-sized input with the padded one and fail if the box offsets differ.

    Cutting off the padding changes the features from the backbone's first global attention on,
    which no longer sees the padding; the benchmark measures what that does to the counts. The
    box offsets must stay fractions of 1024 px, not become fractions of the input's sides.
    """
    import numpy as np
    import torch

    with torch.no_grad():
        for stage, (full, cut) in enumerate(
            zip(model.backbone.trunk(padded), model.backbone.trunk(content), strict=True)
        ):
            full = full[..., : cut.shape[2], : cut.shape[3]]
            logger.info(
                "Trunk stage %d deviates by %.3f (mean) and %.3f (max) of its mean magnitude",
                stage,
                float((cut - full).abs().mean() / full.abs().mean()),
                float((cut - full).abs().max() / full.abs().mean()),
            )
    for boxes in exemplar_sets:
        padded_objectness, padded_offsets = _dense(network, padded, boxes)
        objectness, offsets = _dense(network, content, boxes)
        rows, columns = objectness.shape
        padded_found = _peaks(padded_objectness[:rows, :columns])
        found = _peaks(objectness)
        # Peaks found by both, allowing them to move to a neighbouring cell.
        near = np.lib.stride_tricks.sliding_window_view(np.pad(found, 1), (3, 3)).any(axis=(2, 3))
        expected_offsets = padded_offsets[:rows, :columns][padded_found]
        offset_error = np.abs(offsets[padded_found] - expected_offsets) / expected_offsets
        logger.info(
            "%s input vs padded, %d exemplars: %d vs %d peaks (%d within a cell), "
            "offsets at the padded peaks deviate by %.1f%% (median)",
            tuple(content.shape[2:]),
            boxes.shape[1],
            found.sum(),
            padded_found.sum(),
            (padded_found & near).sum(),
            100 * np.median(offset_error),
        )
        if np.median(offset_error) > CONTENT_OFFSET_TOLERANCE:
            raise RuntimeError("Box offsets on the content-sized input differ from the padded one")


def _export(network: Any, padded: Any, boxes: Any, path: Path) -> None:
    """Export the network to ONNX with dynamic input size and exemplar count."""
    import torch

    # Trace at a size where Hiera pads its attention windows, so the padding stays in the graph.
    with torch.no_grad():
        torch.onnx.export(
            network,
            (padded[..., :640, :960], boxes),
            str(path),
            input_names=["image", "exemplars"],
            output_names=list(OUTPUTS),
            dynamic_axes={
                "image": {2: "height", 3: "width"},
                "exemplars": {1: "count"},
                "objectness": {1: "rows", 2: "columns"},
                "offsets": {1: "rows", 2: "columns"},
            },
            opset_version=17,
            dynamo=False,
        )


def _quantize(fp32_path: Path, int8_path: Path) -> None:
    """Quantize the exported model's weights dynamically to int8."""
    from onnxruntime.quantization import QuantType, quantize_dynamic

    quantize_dynamic(str(fp32_path), str(int8_path), weight_type=QuantType.QUInt8)


def _check_export(
    path: Path, network: Any, padded: Any, content: Any, exemplar_sets: Sequence[Any]
) -> None:
    """The export must match the network at several non-square input sizes and exemplar counts."""
    import onnxruntime

    session = onnxruntime.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    sizes = [tuple(content.shape[2:]), *EXPORT_CHECK_SIZES]
    for (height, width), boxes in itertools.product(sizes, exemplar_sets):
        image = padded[..., :height, :width]
        expected = _dense(network, image, boxes)
        actual = session.run(list(OUTPUTS), {"image": image.numpy(), "exemplars": boxes.numpy()})
        for name, value, reference in zip(OUTPUTS, actual, expected, strict=True):
            error = _deviation(value[0], reference)
            if error > EXPORT_TOLERANCE:
                raise RuntimeError(
                    f"ONNX {name} deviates by {error:.1e} at {height} x {width} with "
                    f"{boxes.shape[1]} exemplars"
                )
        logger.info("ONNX matches PyTorch at %d x %d, %d exemplars", height, width, boxes.shape[1])


@app.function(image=container_image, cpu=8, memory=32768, timeout=3600)
def export_onnx(image_bytes: bytes, exemplars: list[list[float]]) -> dict[str, bytes]:
    """Export the dense network to ONNX in float32 and int8, checked against PyTorch on an image.

    The network must match GeCo2 on the padded 1024 x 1024 input, find the same boxes on the
    content-sized input, and the export must match the network at several non-square input sizes
    and exemplar counts. Returns the models' bytes by precision, "fp32" and "int8".
    """
    configure_logging()
    model = geco2.load_model("cpu")
    padded, content, boxes = _check_inputs(image_bytes, exemplars)
    network = _dense_network(model)
    # One exemplar (a tap) and all of them.
    exemplar_sets = (boxes[:, :1], boxes)

    _check_network(model, network, padded, exemplar_sets)
    _check_content_input(model, network, padded, content, exemplar_sets)
    with tempfile.TemporaryDirectory() as directory:
        fp32_path, int8_path = Path(directory) / "fp32.onnx", Path(directory) / "int8.onnx"
        _export(network, padded, boxes, fp32_path)
        _quantize(fp32_path, int8_path)
        _check_export(fp32_path, network, padded, content, exemplar_sets)
        return {"fp32": fp32_path.read_bytes(), "int8": int8_path.read_bytes()}


@app.local_entrypoint()
def export() -> None:
    configure_logging()
    image_bytes = (IMAGE_DIR / CHECK_IMAGE).read_bytes()
    for name, model in export_onnx.remote(image_bytes, CHECK_EXEMPLARS).items():
        target = DATA_DIR / f"geco2-{name}.onnx"
        target.write_bytes(model)
        logger.info("Saved %s (%.0f MB)", target, len(model) / 1e6)
