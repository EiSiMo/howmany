"""Classical computer vision: segment foreground blobs, then split merged blobs by size.

No learned model. The image is thresholded (Otsu on a blurred grayscale and on saturation,
whichever separates better), touching objects are split with a distance-transform watershed,
and the count is the sum of every blob's area divided by the typical blob area, where
the typical area is the area-weighted median and much smaller blobs are dropped as noise.
"""

from pathlib import Path

import cv2
import numpy as np
from cv2.typing import MatLike
from numpy.typing import NDArray

MIN_AREA_FRACTION = 0.00005
MAX_AREA_FRACTION = 0.5
PEAK_THRESHOLD = 0.5
NOISE_FRACTION = 0.3


def quantify(image_path: Path) -> int:
    image = cv2.imread(str(image_path))
    if image is None:
        raise ValueError(f"Cannot read image {image_path}")
    mask = _foreground_mask(image)
    areas = _blob_areas(mask)
    if areas.size == 0:
        return 0
    typical = _area_weighted_median(areas)
    objects = areas[areas >= NOISE_FRACTION * typical]
    return int(round(float(np.sum(np.maximum(1.0, np.round(objects / typical))))))


def _area_weighted_median(areas: NDArray[np.float64]) -> float:
    """Blob size that splits the total foreground area in half, robust to many noise specks."""
    ordered = np.sort(areas)
    cumulative = np.cumsum(ordered)
    return float(ordered[np.searchsorted(cumulative, cumulative[-1] / 2)])


def _foreground_mask(image: MatLike) -> MatLike:
    gray = cv2.GaussianBlur(cv2.cvtColor(image, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    saturation = cv2.GaussianBlur(cv2.cvtColor(image, cv2.COLOR_BGR2HSV)[:, :, 1], (5, 5), 0)
    best: MatLike | None = None
    best_score = -1.0
    for channel in (gray, saturation):
        threshold, mask = cv2.threshold(channel, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        score = _separation(channel, threshold)
        # Objects are usually the minority class; flip the mask so they are white.
        if np.count_nonzero(mask) > mask.size / 2:
            mask = cv2.bitwise_not(mask)
        if score > best_score:
            best, best_score = mask, score
    assert best is not None
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (3, 3))
    return cv2.morphologyEx(best, cv2.MORPH_OPEN, kernel)


def _separation(channel: MatLike, threshold: float) -> float:
    """Between-class variance normalized by total variance (Otsu's effectiveness measure)."""
    total = float(channel.var())
    if total == 0:
        return 0.0
    low = channel[channel <= threshold]
    high = channel[channel > threshold]
    if low.size == 0 or high.size == 0:
        return 0.0
    w_low = low.size / channel.size
    w_high = 1.0 - w_low
    return w_low * w_high * (float(low.mean()) - float(high.mean())) ** 2 / total


def _blob_areas(mask: MatLike) -> NDArray[np.float64]:
    distance = cv2.distanceTransform(mask, cv2.DIST_L2, 5)
    peaks = np.zeros_like(mask)
    count, labels = cv2.connectedComponents(mask)
    for label in range(1, count):
        region = labels == label
        local_max = float(distance[region].max())
        peaks[region & (distance > PEAK_THRESHOLD * local_max)] = 255
    count, labels, stats, _ = cv2.connectedComponentsWithStats(peaks)
    areas = stats[1:, cv2.CC_STAT_AREA].astype(np.float64)
    image_area = mask.size
    keep = (areas > MIN_AREA_FRACTION * image_area) & (areas < MAX_AREA_FRACTION * image_area)
    return areas[keep]
