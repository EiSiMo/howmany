"""Benchmark samples: images with a known object count, fetched on demand."""

import csv
import logging
import urllib.request
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


@dataclass(frozen=True)
class Sample:
    image_path: Path
    category: str
    true_count: int


def download(url: str, target: Path) -> None:
    """Download url to target atomically, so an interrupted run leaves no partial file."""
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    logger.info("Downloading %s", url)
    try:
        urllib.request.urlretrieve(url, partial)
    except OSError as error:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"Failed to download {url}") from error
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
