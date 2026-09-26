"""Baseline: guess a random count. Any real approach has to beat this."""

import random
from pathlib import Path


def quantify(image_path: Path) -> int:
    return random.randint(1, 200)
