"""Draw a reproducible random sample of FSC-147 images and write it to manifest.csv.

Usage: uv run build_manifest.py [--size 100] [--seed 0] [--split test]
"""

import argparse
import csv
import json
import logging
import random

from dataset import DATA_DIR, FSC147_BASE_URL, MANIFEST_FIELDS, MANIFEST_PATH, download

ANNOTATION_FILES = (
    "annotation_FSC147_384.json",
    "Train_Test_Val_FSC_147.json",
    "ImageClasses_FSC147.txt",
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", type=int, default=100)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--split", default="test", choices=("train", "val", "test"))
    args = parser.parse_args()

    for name in ANNOTATION_FILES:
        if not (DATA_DIR / name).exists():
            download(f"{FSC147_BASE_URL}/{name}", DATA_DIR / name)

    annotations = json.loads((DATA_DIR / "annotation_FSC147_384.json").read_text())
    splits = json.loads((DATA_DIR / "Train_Test_Val_FSC_147.json").read_text())
    categories = dict(
        line.split("\t", 1)
        for line in (DATA_DIR / "ImageClasses_FSC147.txt").read_text().splitlines()
        if line
    )

    images = random.Random(args.seed).sample(sorted(splits[args.split]), args.size)

    with MANIFEST_PATH.open("w", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(MANIFEST_FIELDS)
        for image in images:
            writer.writerow((image, categories[image], len(annotations[image]["points"])))
    logging.info("Wrote %d samples to %s", len(images), MANIFEST_PATH)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    main()
