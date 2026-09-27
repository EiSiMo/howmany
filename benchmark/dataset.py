"""Benchmark samples: images with a known object count, fetched on demand."""

import csv
import json
import logging
import time
import urllib.error
import urllib.request
from collections.abc import Iterable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger(__name__)

# FSC-147 mirror pinned to a fixed commit so the benchmark stays reproducible.
FSC147_BASE_URL = (
    "https://huggingface.co/datasets/isentropic/FSC147/resolve/"
    "3e420cb6537e803dd6d4516623ce82a79c0317b8"
)
FSC147_IMAGE_DIR = "images_384_VarV2"

BENCHMARK_DIR = Path(__file__).parent
MANIFEST_PATH = BENCHMARK_DIR / "manifest.csv"
DATA_DIR = BENCHMARK_DIR / "data"
IMAGE_DIR = DATA_DIR / "images"

MANIFEST_FIELDS = ("image", "category", "count", "exemplars")
DOWNLOAD_ATTEMPTS = 6
RETRYABLE_STATUS = {429, 500, 502, 503, 504}
# FSC-147's annotations: exemplar boxes and points per image, the image names per split, and the
# category per image.
ANNOTATIONS_FILE = "annotation_FSC147_384.json"
SPLITS_FILE = "Train_Test_Val_FSC_147.json"
CATEGORIES_FILE = "ImageClasses_FSC147.txt"
ANNOTATION_FILES = (ANNOTATIONS_FILE, SPLITS_FILE, CATEGORIES_FILE)
# Exemplar boxes per image in FSC-147's standard few-shot setting, and per labelled photo.
EXEMPLARS = 3


# Axis-aligned box in image pixels: (x1, y1, x2, y2).
Box = tuple[float, float, float, float]


@dataclass(frozen=True)
class Sample:
    image_path: Path
    category: str
    true_count: int
    # A few example instances of the object to count, as a user would mark them.
    exemplars: tuple[Box, ...]


def _retry_delay(error: OSError, attempt: int) -> float | None:
    """Seconds to wait before retrying a failed download, or None if retrying is pointless."""
    retry_after = ""
    if isinstance(error, urllib.error.HTTPError):
        if error.code not in RETRYABLE_STATUS:
            return None
        retry_after = error.headers.get("Retry-After", "")
    return float(retry_after) if retry_after.isdigit() else 30.0 * attempt


def download(url: str, target: Path) -> None:
    """Download url to target atomically, so an interrupted run leaves no partial file.

    Network errors, rate limits and server errors are retried, honoring Retry-After.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    logger.info("Downloading %s", url)
    for attempt in range(1, DOWNLOAD_ATTEMPTS + 1):
        try:
            urllib.request.urlretrieve(url, partial)
            break
        except OSError as error:
            partial.unlink(missing_ok=True)
            delay = _retry_delay(error, attempt)
            if delay is None or attempt == DOWNLOAD_ATTEMPTS:
                raise RuntimeError(f"Failed to download {url}") from error
            logger.warning("%s for %s, retrying in %.0f s", error, url, delay)
            time.sleep(delay)
    partial.replace(target)


def configure_logging() -> None:
    """Log INFO and above to stderr, for the benchmark's scripts and remote functions."""
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")


def to_box(values: Iterable[float]) -> Box:
    x1, y1, x2, y2 = (float(value) for value in values)
    return (x1, y1, x2, y2)


def load_samples(
    manifest_path: Path = MANIFEST_PATH, image_dir: Path = IMAGE_DIR, exemplars: int | None = None
) -> list[Sample]:
    """Read the manifest and make sure every listed image is available locally.

    exemplars limits how many exemplar boxes each sample keeps, e.g. 1 for a single tap.
    """
    with manifest_path.open(newline="") as file:
        rows = list(csv.DictReader(file))

    samples = []
    for row in rows:
        image_path = image_dir / row["image"]
        if not image_path.exists():
            download(f"{FSC147_BASE_URL}/{FSC147_IMAGE_DIR}/{row['image']}", image_path)
        boxes = tuple(to_box(box) for box in json.loads(row["exemplars"]))[:exemplars]
        samples.append(Sample(image_path, row["category"], int(row["count"]), boxes))
    return samples


def ensure_annotations(data_dir: Path = DATA_DIR) -> None:
    """Make sure the FSC-147 annotation, split and class files are available locally."""
    for name in ANNOTATION_FILES:
        if not (data_dir / name).exists():
            download(f"{FSC147_BASE_URL}/{name}", data_dir / name)


def ensure_split(split: str, image_dir: Path, data_dir: Path = DATA_DIR) -> None:
    """Make sure every image of an FSC-147 split is available locally, downloading in parallel."""
    ensure_annotations(data_dir)
    names = json.loads((data_dir / SPLITS_FILE).read_text())[split]
    missing = [name for name in names if not (image_dir / name).exists()]
    logger.info("%d of %d %s images missing", len(missing), len(names), split)
    with ThreadPoolExecutor(max_workers=16) as pool:
        # list() re-raises the first download error instead of dropping it.
        list(
            pool.map(
                lambda name: download(
                    f"{FSC147_BASE_URL}/{FSC147_IMAGE_DIR}/{name}", image_dir / name
                ),
                missing,
            )
        )
