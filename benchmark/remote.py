"""Train a prototype on a cloud GPU via Modal and download its weights.

A trainable prototype defines `train(image_dir: Path) -> None`, which trains on the FSC-147 train
split in image_dir and saves its weights next to the prototype file with a `.pt` suffix. The
train images are downloaded once into a persistent Modal volume.

Usage: uv run modal run remote.py --prototype prototypes/prototype-2.py
"""

import logging
from pathlib import Path

import modal

from dataset import BENCHMARK_DIR, DATA_DIR, ensure_split
from run import load_module

logger = logging.getLogger(__name__)

REMOTE_DIR = Path("/root")
GPU = "L4"

app = modal.App("quantify-benchmark")
volume = modal.Volume.from_name("quantify-data", create_if_missing=True)
image = (
    modal.Image.debian_slim(python_version="3.12")
    .uv_pip_install("numpy==2.5.3", "pillow==12.3.0", "torch==2.14.0", "transformers==5.17.0")
    .env({"HF_HOME": str(REMOTE_DIR / "data" / "huggingface")})
    .add_local_dir(
        BENCHMARK_DIR,
        str(REMOTE_DIR),
        ignore=[".venv", "data", "results", "**/__pycache__", "**/*.pt", ".*_cache"],
    )
)


@app.function(image=image, gpu=GPU, volumes={str(DATA_DIR): volume}, timeout=3600)
def train(prototype: str) -> bytes:
    """Train the prototype at the given benchmark-relative path and return its weights."""
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    image_dir = DATA_DIR / "train"
    try:
        ensure_split("train", image_dir)
    finally:
        # Keep the images downloaded so far, so a failed download resumes where it stopped.
        volume.commit()
    path = REMOTE_DIR / prototype
    load_module(path).train(image_dir)
    volume.commit()
    return path.with_suffix(".pt").read_bytes()


@app.local_entrypoint()
def main(prototype: str) -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    relative = (Path.cwd() / prototype).resolve().relative_to(BENCHMARK_DIR)
    weights = train.remote(str(relative))
    target = BENCHMARK_DIR / relative.with_suffix(".pt")
    target.write_bytes(weights)
    logger.info("Saved weights to %s", target)
