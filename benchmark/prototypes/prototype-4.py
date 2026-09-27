"""GeCo2 exported to ONNX, running locally on the CPU with ONNX Runtime as it would on a phone.

The ONNX model is GeCo2's dense network (see _dense_network in export_geco2.py): it predicts an
objectness map and box offsets. Picking one box per objectness peak and suppressing duplicates
happens here in NumPy, as it would in the app. Unlike prototype 3 it skips GeCo2's SAM2 box
refinement, which only tightens box edges; which objects are found stays the same.

The model is dynamically quantized to int8 weights, the usual first step for phones. Export it
once (runs on Modal, writes data/geco2-fp32.onnx and data/geco2-int8.onnx):

    uv run modal run export_geco2.py
"""

from __future__ import annotations

from collections.abc import Sequence
from functools import cache
from pathlib import Path
from typing import TYPE_CHECKING

import cv2
import numpy as np
import onnxruntime
from cv2.typing import MatLike
from numpy.typing import NDArray

if TYPE_CHECKING:
    from dataset import Box

MODEL_PATH = Path(__file__).parent.parent / "data" / "geco2-int8.onnx"
# A phone runs inference on its few performance cores; match that instead of using every core.
THREADS = 4
# GeCo2 was trained on square images of this many pixels, the scaled image top-left.
INPUT_SIZE = 1024
# The model takes that image with the padding cut off, down to the next multiple of this.
SIZE_MULTIPLE = 32
# But never smaller than this per side: with less padding around very small images (few, large
# objects) GeCo2 miscounts, e.g. 48 instead of 32 planks.
MIN_INPUT_SIDE = 512
# GeCo2 scales images so exemplars are at most this many pixels on average.
EXEMPLAR_SIZE = 80
IMAGENET_MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
# Post-processing as in GeCo2: objectness peaks above 1/8 of the maximum become candidate boxes,
# candidates scoring above 11% of the best are kept, and overlapping duplicates are suppressed.
PEAK_RATIO = 1 / 8
SCORE_RATIO = 0.11
NMS_IOU = 0.5

Boxes = NDArray[np.float32]


@cache
def _session() -> onnxruntime.InferenceSession:
    if not MODEL_PATH.exists():
        raise FileNotFoundError(f"{MODEL_PATH} missing, export it with export_geco2.py")
    options = onnxruntime.SessionOptions()
    options.intra_op_num_threads = THREADS
    return onnxruntime.InferenceSession(
        str(MODEL_PATH), options, providers=["CPUExecutionProvider"]
    )


def _prepare(image: MatLike, exemplars: Boxes) -> tuple[NDArray[np.float32], float]:
    """Scale the image so exemplars are at most ~80 px, pad it (see above) and normalize."""
    height, width = image.shape[:2]
    scale = INPUT_SIZE / max(height, width)
    sizes = (exemplars[:, 2:] - exemplars[:, :2]) * scale
    scale *= min(1.0, EXEMPLAR_SIZE / float(sizes.mean(axis=0).mean()))
    resized = cv2.resize(
        image, (int(width * scale), int(height * scale)), interpolation=cv2.INTER_LINEAR
    )
    input_height, input_width = (
        max(-(-side // SIZE_MULTIPLE) * SIZE_MULTIPLE, MIN_INPUT_SIDE) for side in resized.shape[:2]
    )
    padded = np.zeros((input_height, input_width, 3), dtype=np.float32)
    padded[: resized.shape[0], : resized.shape[1]] = resized / 255
    normalized = (padded - IMAGENET_MEAN) / IMAGENET_STD
    return normalized.transpose(2, 0, 1)[None], scale


def _peaks(objectness: NDArray[np.float32]) -> tuple[NDArray[np.intp], NDArray[np.intp]]:
    """Rows and columns of 3 x 3 local maxima above the peak threshold."""
    padded = np.pad(objectness, 1, constant_values=-np.inf)
    neighbourhood = np.lib.stride_tricks.sliding_window_view(padded, (3, 3)).max(axis=(2, 3))
    peaks = (neighbourhood == objectness) & (objectness > objectness.max() * PEAK_RATIO)
    rows, columns = np.nonzero(peaks)
    return rows, columns


def _suppress_duplicates(boxes: Boxes, scores: NDArray[np.float32]) -> Boxes:
    """Greedy non-maximum suppression, keeping the best-scoring box of each overlapping group."""
    areas = (boxes[:, 2] - boxes[:, 0]) * (boxes[:, 3] - boxes[:, 1])
    remaining = np.argsort(-scores)
    kept = []
    while len(remaining):
        best, others = remaining[0], remaining[1:]
        kept.append(best)
        top_left = np.maximum(boxes[best, :2], boxes[others, :2])
        bottom_right = np.minimum(boxes[best, 2:], boxes[others, 2:])
        intersection = np.prod(np.clip(bottom_right - top_left, 0, None), axis=1)
        iou = intersection / (areas[best] + areas[others] - intersection)
        remaining = others[iou <= NMS_IOU]
    return boxes[kept]


def detect(image_path: Path, exemplars: Sequence[Box]) -> Boxes:
    """Return one (x1, y1, x2, y2) box in image pixels per detected object."""
    image = cv2.imread(str(image_path))
    if image is None:
        raise ValueError(f"Cannot read image {image_path}")
    image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    exemplar_boxes = np.array(exemplars, dtype=np.float32)
    pixels, scale = _prepare(image, exemplar_boxes)
    outputs = _session().run(
        ["objectness", "offsets"],
        {"image": pixels, "exemplars": (exemplar_boxes * scale)[None]},
    )
    # ONNX Runtime is untyped; the model's outputs are float32.
    objectness = np.asarray(outputs[0][0], dtype=np.float32)
    offsets = np.asarray(outputs[1][0], dtype=np.float32)

    rows, columns = _peaks(objectness)
    input_height, input_width = pixels.shape[2:]
    cell_height, cell_width = input_height / objectness.shape[0], input_width / objectness.shape[1]
    centers = np.stack([columns * cell_width, rows * cell_height], axis=1).astype(np.float32)
    left_top, right_bottom = offsets[rows, columns, :2], offsets[rows, columns, 2:]
    boxes = np.concatenate([centers - left_top, centers + right_bottom], axis=1)
    scores = objectness[rows, columns]
    if len(scores) == 0:
        return np.zeros((0, 4), dtype=np.float32)
    keep = scores > scores.max() * SCORE_RATIO
    boxes = np.clip(
        _suppress_duplicates(boxes[keep], scores[keep]), 0, [input_width, input_height] * 2
    )

    # Drop boxes centred in the padding, then map back to original image pixels.
    box_centers = (boxes[:, :2] + boxes[:, 2:]) / 2
    height, width = image.shape[:2]
    inside = (box_centers[:, 0] < width * scale) & (box_centers[:, 1] < height * scale)
    detected: Boxes = (boxes[inside] / scale).astype(np.float32)
    return detected


def quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int:
    return len(detect(image_path, exemplars))
