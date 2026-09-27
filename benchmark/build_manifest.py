"""Draw a reproducible random sample of FSC-147 images and write it to manifest.csv.

Usage: uv run build_manifest.py [--size 100] [--seed 0] [--split test] [--output manifest.csv]
"""

import argparse
import csv
import json
import logging
import random
from pathlib import Path

from dataset import (
    ANNOTATIONS_FILE,
    CATEGORIES_FILE,
    DATA_DIR,
    EXEMPLARS,
    MANIFEST_FIELDS,
    MANIFEST_PATH,
    SPLITS_FILE,
    configure_logging,
    ensure_annotations,
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--size", type=int, default=100)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--split", default="test", choices=("train", "val", "test"))
    parser.add_argument("--output", type=Path, default=MANIFEST_PATH)
    args = parser.parse_args()

    ensure_annotations()

    annotations = json.loads((DATA_DIR / ANNOTATIONS_FILE).read_text())
    splits = json.loads((DATA_DIR / SPLITS_FILE).read_text())
    categories = dict(
        line.split("\t", 1)
        for line in (DATA_DIR / CATEGORIES_FILE).read_text().splitlines()
        if line
    )

    images = random.Random(args.seed).sample(sorted(splits[args.split]), args.size)

    with args.output.open("w", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(MANIFEST_FIELDS)
        for image in images:
            annotation = annotations[image]
            exemplars = [
                [*corners[0], *corners[2]]
                for corners in annotation["box_examples_coordinates"][:EXEMPLARS]
            ]
            writer.writerow(
                (image, categories[image], len(annotation["points"]), json.dumps(exemplars))
            )
    logging.info("Wrote %d samples to %s", len(images), args.output)


if __name__ == "__main__":
    configure_logging()
    main()
