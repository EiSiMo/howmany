"""Benchmark samples: images with a known object count, fetched on demand."""

import csv
import json
import logging
import time
import urllib.error
import urllib.request
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

MANIFEST_FIELDS = ("image", "category", "count")
DOWNLOAD_ATTEMPTS = 6
RETRYABLE_STATUS = {429, 500, 502, 503, 504}
ANNOTATION_FILES = (
    "annotation_FSC147_384.json",
    "Train_Test_Val_FSC_147.json",
    "ImageClasses_FSC147.txt",
)


@dataclass(frozen=True)
class Sample:
    image_path: Path
    category: str
    true_count: int


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


def load_samples(manifest_path: Path = MANIFEST_PATH, image_dir: Path = IMAGE_DIR) -> list[Sample]:
    """Read the manifest and make sure every listed image is available locally."""
    with manifest_path.open(newline="") as file:
        rows = list(csv.DictReader(file))

    samples = []
    for row in rows:
        image_path = image_dir / row["image"]
        if not image_path.exists():
            download(f"{FSC147_BASE_URL}/{FSC147_IMAGE_DIR}/{row['image']}", image_path)
        samples.append(Sample(image_path, row["category"], int(row["count"])))
    return samples


def ensure_annotations(data_dir: Path = DATA_DIR) -> None:
    """Make sure the FSC-147 annotation, split and class files are available locally."""
    for name in ANNOTATION_FILES:
        if not (data_dir / name).exists():
            download(f"{FSC147_BASE_URL}/{name}", data_dir / name)


def ensure_split(split: str, image_dir: Path, data_dir: Path = DATA_DIR) -> None:
    """Make sure every image of an FSC-147 split is available locally, downloading in parallel."""
    ensure_annotations(data_dir)
    names = json.loads((data_dir / "Train_Test_Val_FSC_147.json").read_text())[split]
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
