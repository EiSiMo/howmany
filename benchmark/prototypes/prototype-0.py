"""Baseline: guess a random count. Any real approach has to beat this."""

import random
from collections.abc import Sequence
from pathlib import Path

from dataset import Box


def quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int:
    return random.randint(1, 200)
