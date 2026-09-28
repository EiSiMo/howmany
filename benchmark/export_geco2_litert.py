"""Export GeCo2's dense network at a fixed 1024 x 1024 input to LiteRT and to ONNX, to compare them.

A time-boxed spike: would LiteRT on the CPU (XNNPACK) run GeCo2 faster or smaller than ONNX Runtime?
litert-torch cannot export dynamic input sizes, so both exports take a 1024 x 1024 image and one
exemplar. Both quantize their weights dynamically to int8 with float32 activations. The network is
export_geco2.py's. For LiteRT, a few modules are replaced by equivalents that convert to builtin
ops within reasonable memory: RoiAlign, deformable attention and linear layers (see
_roi_align and _use_litert_friendly_modules), each checked against the original. Runs on Modal and
writes data/geco2-int8-1024.tflite and data/geco2-int8-1024.onnx.

Usage: uv run modal run export_geco2_litert.py
"""

import logging
import math
import tempfile
import types
from pathlib import Path
from typing import Any

import modal

import export_geco2
from dataset import DATA_DIR, IMAGE_DIR, configure_logging

logger = logging.getLogger(__name__)

INPUT_SIZE = export_geco2.INPUT_SIZE
# Largest relative deviation tolerated between the RoiAlign replacement and torchvision's, and
# between an export in float32 and the network in PyTorch.
ROI_ALIGN_TOLERANCE = 1e-5
EXPORT_TOLERANCE = 1e-3

app = modal.App("quantify-export-geco2-litert")
# GeCo2's image, with the newer torch that litert-torch needs; the export runs on the CPU.
container_image = (
    export_geco2.geco2.container_image.pip_install(
        "torch==2.12.1",
        "torchvision==0.27.1",
        index_url="https://download.pytorch.org/whl/cpu",
    )
    .pip_install("litert-torch==0.9.4", "ai-edge-quantizer==0.9.0", "ai-edge-litert==2.2.0")
    # Fail at build time, not after loading GeCo2, if the converter does not import.
    .run_commands(
        "python -c 'import torch, torchvision, litert_torch, ai_edge_quantizer, ai_edge_litert; "
        "print(torch.__version__, torchvision.__version__)'"
    )
    .add_local_python_source("dataset", "prototype", "export_geco2")
    .add_local_file(export_geco2.GECO2_PROTOTYPE, export_geco2.remote_prototype)
)


def _roi_align(level: Any, rois: Any, output_size: int, spatial_scale: float, aligned: bool) -> Any:
    """torchvision's roi_align with one output cell and adaptive sampling, from dense tensor ops.

    RoiAlign averages bilinear samples on a grid of ceil(height) x ceil(width) points per box.
    Bilinear weights factor into a row and a column part, so the average is a weighted sum over
    the level's rows and columns, which needs only element-wise ops and matrix products. Covers
    what the dense network uses: one image, one output cell, aligned boxes.
    """
    import torch

    if output_size != 1 or not aligned:
        raise ValueError("Only aligned RoiAlign with one output cell is supported")
    _, _, height, width = level.shape

    def side_weights(start: Any, end: Any, size: int) -> Any:
        """Each box's weight per cell along one side, (K, size), averaged over its samples."""
        start, end = start * spatial_scale - 0.5, end * spatial_scale - 0.5
        samples = torch.ceil(end - start)[:, None]
        # A box within the level has at most size samples per side, plus one for rounding.
        steps = torch.arange(size + 1, dtype=level.dtype)
        positions = start[:, None] + (steps + 0.5) * (end - start)[:, None] / samples.clamp(min=1)
        used = (steps < samples) & (positions >= -1) & (positions <= size)
        cells = torch.arange(size, dtype=level.dtype)
        distance = (positions.clamp(0, size - 1)[..., None] - cells).abs()
        return ((1 - distance).clamp(min=0) * used[..., None]).sum(1) / samples.clamp(min=1)

    rows = side_weights(rois[:, 2], rois[:, 4], height)
    columns = side_weights(rois[:, 1], rois[:, 3], width)
    by_row = level[0] @ columns.T  # (C, H, K)
    pooled = (by_row * rows.T[None]).sum(1)  # (C, K)
    return pooled.T[:, :, None, None]


def _deformable_attention(
    self: Any,
    query: Any,
    reference_points: Any,
    input_flatten: Any,
    input_spatial_shapes: Any,
    input_level_start_index: Any,
    input_padding_mask: Any = None,
) -> Any:
    """Deformable DETR's MSDeformAttn.forward with its level's size as Python numbers.

    The original checks and reshapes by a tensor of level sizes, which torch.export cannot read
    while tracing. At the fixed 1024 x 1024 input every level GeCo2 attends within is square, so
    its side follows from the number of cells. Covers GeCo2's use: one level, no padding mask.

    Sampling is bilinear like grid_sample (zero padding, align_corners=False), but gathers whole
    cells, one index per sampling point and corner. torch's grid_sample decomposition, which
    litert-torch uses, gathers every channel separately, with index tensors of 2 GB each.
    """
    import torch
    from torch.nn import functional as F

    batch, queries, _ = query.shape
    _, length, _ = input_flatten.shape
    side = math.isqrt(length)
    if side * side != length or self.n_levels != 1 or input_padding_mask is not None:
        raise ValueError("Only a single square level without padding mask is supported")
    heads, points, channels = self.n_heads, self.n_points, self.d_model // self.n_heads

    value = self.value_proj(input_flatten).view(batch, length, heads, channels)
    offsets = self.sampling_offsets(query).view(batch, queries, heads, points, 2)
    weights = F.softmax(self.attention_weights(query).view(batch, queries, heads, points), -1)
    locations = reference_points[:, :, None, :, :] + offsets / side
    # Sampling points in cell coordinates, where cell centres lie at whole numbers.
    x, y = locations[..., 0] * side - 0.5, locations[..., 1] * side - 0.5
    left, top = torch.floor(x), torch.floor(y)
    # Every head's cells as rows of one table.
    cells = value.transpose(1, 2).reshape(batch * heads * length, channels)
    first_cell = (torch.arange(batch * heads) * length).view(batch, 1, heads, 1)
    output = torch.zeros(batch * queries * heads, 1, channels)
    for column, row in ((left, top), (left + 1, top), (left, top + 1), (left + 1, top + 1)):
        inside = (column >= 0) & (column < side) & (row >= 0) & (row < side)
        corner = (1 - (x - column).abs()) * (1 - (y - row).abs()) * weights * inside
        cell = row.clamp(0, side - 1) * side + column.clamp(0, side - 1)
        sampled = cells[(first_cell + cell.long()).flatten()]
        output = output + torch.bmm(corner.view(-1, 1, points), sampled.view(-1, points, channels))
    return self.output_proj(output.view(batch, queries, heads * channels))


def _flat_linear(self: Any, features: Any) -> Any:
    """nn.Linear.forward on a 2D view of the input, see _use_litert_friendly_modules."""
    from torch.nn import functional as F

    flat = F.linear(features.reshape(-1, self.in_features), self.weight, self.bias)
    return flat.reshape(*features.shape[:-1], self.out_features)


def _use_litert_friendly_modules(model: Any) -> None:
    """Replace the forward of the model's deformable attention and linear layers with equivalents.

    Deformable attention: see _deformable_attention. Linear layers: litert-torch lowers them on
    inputs of more than three dimensions to a batch matrix product with the weights broadcast per
    batch, gigabytes large and not quantized; on a 2D view they become fully connected layers.
    """
    import torch

    for module in model.modules():
        if type(module).__name__ == "MSDeformAttn":
            module.forward = types.MethodType(_deformable_attention, module)
        elif isinstance(module, torch.nn.Linear):
            module.forward = types.MethodType(_flat_linear, module)  # type: ignore[method-assign]


def _check_roi_align(padded: Any, boxes: Any) -> None:
    """The replacement must match torchvision on feature-sized levels and exemplar-like boxes."""
    import torch
    from torchvision.ops import roi_align  # type: ignore[import-not-found]

    generator = torch.Generator().manual_seed(0)
    corners = torch.rand(64, 2, generator=generator) * (INPUT_SIZE - 100)
    sizes = torch.rand(64, 2, generator=generator) * 90 + 0.5
    random_boxes = torch.cat([corners, corners + sizes], dim=1)
    all_boxes = torch.cat([boxes[0], random_boxes, torch.tensor([[0.0, 0.0, 1024.0, 1024.0]])])
    rois = torch.cat([torch.zeros_like(all_boxes[:, :1]), all_boxes], dim=1)
    for stride in (4, 8, 16):
        level = torch.randn(1, 256, INPUT_SIZE // stride, INPUT_SIZE // stride, generator=generator)
        expected = roi_align(level, rois, output_size=1, spatial_scale=1 / stride, aligned=True)
        actual = _roi_align(level, rois, output_size=1, spatial_scale=1 / stride, aligned=True)
        error = export_geco2._deviation(actual.numpy(), expected.numpy())
        logger.info("RoiAlign replacement deviates by %.1e at stride %d", error, stride)
        if error > ROI_ALIGN_TOLERANCE:
            raise RuntimeError(f"RoiAlign replacement deviates by {error:.1e} at stride {stride}")


def _check(name: str, outputs: Any, expected: Any) -> None:
    """Each output must match the network's within the export tolerance."""
    import numpy as np

    for output_name, value, reference in zip(export_geco2.OUTPUTS, outputs, expected, strict=True):
        error = export_geco2._deviation(np.asarray(value).reshape(reference.shape), reference)
        logger.info("%s %s deviates by %.1e", name, output_name, error)
        if error > EXPORT_TOLERANCE:
            raise RuntimeError(f"{name} {output_name} deviates by {error:.1e}")


def _export_onnx(network: Any, image: Any, exemplars: Any, directory: Path) -> bytes:
    """Export to ONNX at the example's fixed input shapes and quantize the weights to int8."""
    import onnxruntime
    import torch

    fp32_path, int8_path = directory / "fp32.onnx", directory / "int8.onnx"
    with torch.no_grad():
        torch.onnx.export(
            network,
            (image, exemplars),
            str(fp32_path),
            input_names=["image", "exemplars"],
            output_names=list(export_geco2.OUTPUTS),
            opset_version=17,
            dynamo=False,
        )
    session = onnxruntime.InferenceSession(str(fp32_path), providers=["CPUExecutionProvider"])
    outputs = session.run(None, {"image": image.numpy(), "exemplars": exemplars.numpy()})
    _check(
        "ONNX", [output[0] for output in outputs], export_geco2._dense(network, image, exemplars)
    )
    export_geco2._quantize(fp32_path, int8_path)
    return int8_path.read_bytes()


def _export_litert(network: Any, image: Any, exemplars: Any) -> bytes:
    """Convert to LiteRT with builtin ops only and quantize the weights dynamically to int8."""
    import litert_torch  # type: ignore[import-not-found]
    from ai_edge_quantizer import quantizer, recipe  # type: ignore[import-not-found]

    edge_model = litert_torch.convert(network, (image, exemplars))
    outputs = edge_model(image.numpy(), exemplars.numpy())
    _check(
        "LiteRT", [output[0] for output in outputs], export_geco2._dense(network, image, exemplars)
    )
    int8 = quantizer.Quantizer(edge_model.model_content(), recipe.dynamic_wi8_afp32()).quantize()
    return bytes(int8.quantized_model)


@app.function(image=container_image, cpu=8, memory=65536, timeout=3600)
def export_models(image_bytes: bytes, exemplars: list[list[float]]) -> dict[str, bytes]:
    """Export GeCo2 at 1024 x 1024 with one exemplar to LiteRT and ONNX, both int8.

    Returns the models' bytes by file suffix, "tflite" and "onnx".
    """
    configure_logging()
    model = export_geco2.geco2.load_model("cpu")
    padded, _, boxes = export_geco2._check_inputs(image_bytes, exemplars)
    one_exemplar = boxes[:, :1]
    _check_roi_align(padded, boxes)

    network = export_geco2._dense_network(model)
    reference = export_geco2._dense(network, padded, one_exemplar)
    with tempfile.TemporaryDirectory() as directory:
        onnx = _export_onnx(network, padded, one_exemplar, Path(directory))

    # From here on the model runs the replacements LiteRT needs.
    _use_litert_friendly_modules(model)
    litert_network = export_geco2._dense_network(model, roi_align=_roi_align, fixed_size=True)
    replaced = export_geco2._dense(litert_network, padded, one_exemplar)
    _check("Network with replacements", replaced, reference)
    tflite = _export_litert(litert_network, padded, one_exemplar)
    return {"tflite": tflite, "onnx": onnx}


@app.local_entrypoint()
def export() -> None:
    configure_logging()
    image_bytes = (IMAGE_DIR / export_geco2.CHECK_IMAGE).read_bytes()
    for suffix, model in export_models.remote(image_bytes, export_geco2.CHECK_EXEMPLARS).items():
        target = DATA_DIR / f"geco2-int8-{INPUT_SIZE}.{suffix}"
        target.write_bytes(model)
        logger.info("Saved %s (%.0f MB)", target, len(model) / 1e6)
