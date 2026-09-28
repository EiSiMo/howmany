"""Our own photos as a second benchmark: real phone photos next to the FSC-147 sample.

A photo is labelled like FSC-147: a category naming the objects to count (the text prompt), three
exemplar boxes, and one point per object, which gives the true count. Labels live in
`photos/<name>.json` and are committed; the photos themselves stay local in `data/photos/`.

Usage:
    uv run photos.py ingest ~/Downloads/quantify-training-*.zip   # add new photos, idempotent
    uv run photos.py status                                      # what is left to label
"""

import argparse
import hashlib
import io
import json
import logging
import re
import zipfile
from collections import Counter
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any

from PIL import Image, ImageOps

from dataset import DATA_DIR, EXEMPLARS, MODEL_DIR, Box, Sample, configure_logging, to_box

logger = logging.getLogger(__name__)

LABEL_DIR = MODEL_DIR / "photos"
IMAGE_DIR = DATA_DIR / "photos"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png"}
EXIF_ORIENTATION = 0x0112
TRANSLITERATION = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss"})

# Image pixel coordinates: (x, y).
Point = tuple[float, float]


class Status(StrEnum):
    """Where a photo stands, named after what has to happen next."""

    MISSING_IMAGE = "missing image"
    NEEDS_CATEGORY = "needs category"
    NEEDS_EXEMPLARS = "needs exemplars"
    NEEDS_POINTS = "needs points"
    COMPLETE = "complete"
    EXCLUDED = "excluded"


@dataclass(frozen=True)
class Label:
    # Hash of the photo as it was ingested, so ingesting it again is recognized.
    sha256: str
    category: str = ""
    exemplars: tuple[Box, ...] = ()
    points: tuple[Point, ...] = ()
    # The labeller confirmed that every object has a point.
    complete: bool = False
    # Unusable photo, kept so ingesting it again does not bring it back.
    excluded: bool = False

    def to_json(self) -> str:
        """Serialize with one point per line, so label diffs stay readable."""
        lines = ",\n".join(f"    {json.dumps(list(point))}" for point in self.points)
        points = f"[\n{lines}\n  ]" if self.points else "[]"
        return (
            "{\n"
            f'  "sha256": {json.dumps(self.sha256)},\n'
            f'  "category": {json.dumps(self.category)},\n'
            f'  "complete": {json.dumps(self.complete)},\n'
            f'  "excluded": {json.dumps(self.excluded)},\n'
            f'  "exemplars": {json.dumps([list(box) for box in self.exemplars])},\n'
            f'  "points": {points}\n'
            "}\n"
        )

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "Label":
        return cls(
            sha256=str(data["sha256"]),
            category=str(data.get("category", "")).strip(),
            exemplars=tuple(to_box(box) for box in data.get("exemplars", [])),
            points=tuple((float(x), float(y)) for x, y in data.get("points", [])),
            complete=bool(data.get("complete", False)),
            excluded=bool(data.get("excluded", False)),
        )


def _slug(filename: str) -> str:
    stem = Path(filename).stem.lower().translate(TRANSLITERATION)
    slug = re.sub(r"[^a-z0-9]+", "-", stem).strip("-")
    if not slug:
        raise ValueError(f"Cannot derive a name from {filename!r}")
    return slug


def _iter_photos(sources: Iterable[Path]) -> Iterator[tuple[str, bytes]]:
    """Yield (filename, content) of every photo in the given files, directories and zips."""
    for source in sources:
        if source.is_dir():
            yield from _iter_photos(sorted(path for path in source.rglob("*") if path.is_file()))
        elif source.suffix.lower() == ".zip":
            with zipfile.ZipFile(source) as archive:
                for entry in archive.infolist():
                    if not entry.is_dir() and Path(entry.filename).suffix.lower() in IMAGE_SUFFIXES:
                        yield Path(entry.filename).name, archive.read(entry)
        elif source.suffix.lower() in IMAGE_SUFFIXES:
            yield source.name, source.read_bytes()
        elif not source.exists():
            raise FileNotFoundError(source)
        else:
            logger.warning("Skipping %s, not a photo or zip", source)


def _upright_jpeg(content: bytes) -> bytes:
    """Return the photo as JPEG with any EXIF rotation applied to the pixels.

    Labels are in pixel coordinates, so every reader must see the same pixels, whether it
    honors EXIF orientation (browsers, OpenCV) or not (Pillow).
    """
    with Image.open(io.BytesIO(content)) as image:
        if image.format == "JPEG" and image.getexif().get(EXIF_ORIENTATION, 1) == 1:
            return content
        upright = ImageOps.exif_transpose(image).convert("RGB")
    buffer = io.BytesIO()
    upright.save(buffer, "JPEG", quality=95)
    return buffer.getvalue()


class PhotoStore:
    """Our own photos and their labels: ingesting, labelling progress and benchmark samples."""

    def __init__(self, label_dir: Path = LABEL_DIR, image_dir: Path = IMAGE_DIR) -> None:
        self.label_dir = label_dir
        self.image_dir = image_dir

    def names(self) -> list[str]:
        return sorted(path.stem for path in self.label_dir.glob("*.json"))

    def image_path(self, name: str) -> Path:
        return self.image_dir / f"{name}.jpg"

    def label(self, name: str) -> Label:
        path = self.label_dir / f"{name}.json"
        try:
            return Label.from_dict(json.loads(path.read_text()))
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(f"Invalid label {path}") from error

    def save(self, name: str, label: Label) -> None:
        if name not in self.names():
            raise KeyError(f"Unknown photo {name!r}")
        if len(label.exemplars) > EXEMPLARS:
            raise ValueError(f"At most {EXEMPLARS} exemplars, got {len(label.exemplars)}")
        if any(x1 >= x2 or y1 >= y2 for x1, y1, x2, y2 in label.exemplars):
            raise ValueError("Exemplar boxes need x1 < x2 and y1 < y2")
        if label.complete and not (
            label.category and len(label.exemplars) == EXEMPLARS and label.points
        ):
            raise ValueError(f"A complete label needs a category, {EXEMPLARS} exemplars and points")
        (self.label_dir / f"{name}.json").write_text(label.to_json())

    def status(self, name: str) -> Status:
        label = self.label(name)
        if label.excluded:
            return Status.EXCLUDED
        if not self.image_path(name).exists():
            return Status.MISSING_IMAGE
        if not label.category:
            return Status.NEEDS_CATEGORY
        if len(label.exemplars) < EXEMPLARS:
            return Status.NEEDS_EXEMPLARS
        if not label.complete:
            return Status.NEEDS_POINTS
        return Status.COMPLETE

    def ingest(self, sources: Iterable[Path]) -> list[str]:
        """Add every photo not ingested before and return the names of the new ones."""
        self.label_dir.mkdir(parents=True, exist_ok=True)
        self.image_dir.mkdir(parents=True, exist_ok=True)
        known = {self.label(name).sha256 for name in self.names()}
        added = []
        for filename, content in _iter_photos(sources):
            sha256 = hashlib.sha256(content).hexdigest()
            if sha256 in known:
                continue
            name = base = _slug(filename)
            suffix = 1
            while (self.label_dir / f"{name}.json").exists():
                suffix += 1
                name = f"{base}-{suffix}"
            self.image_path(name).write_bytes(_upright_jpeg(content))
            (self.label_dir / f"{name}.json").write_text(Label(sha256).to_json())
            known.add(sha256)
            added.append(name)
            logger.info("Ingested %s as %s", filename, name)
        return added

    def samples(self, exemplars: int | None = None) -> list[Sample]:
        """Completely labelled photos as benchmark samples, keeping at most `exemplars` boxes."""
        samples = []
        for name in self.names():
            status = self.status(name)
            if status == Status.MISSING_IMAGE and self.label(name).complete:
                raise FileNotFoundError(
                    f"{self.image_path(name)} missing, ingest the original photos again"
                )
            if status == Status.COMPLETE:
                label = self.label(name)
                samples.append(
                    Sample(
                        self.image_path(name),
                        label.category,
                        len(label.points),
                        label.exemplars[:exemplars],
                    )
                )
        return samples


def format_status(store: PhotoStore) -> str:
    statuses = {name: store.status(name) for name in store.names()}
    counts = Counter(statuses.values())
    lines = [f"{len(statuses)} photos"]
    lines += [f"  {status}: {counts[status]}" for status in Status if counts[status]]
    open_names = [
        f"  {name}: {status}"
        for name, status in statuses.items()
        if status not in (Status.COMPLETE, Status.EXCLUDED)
    ]
    if open_names:
        lines += ["", "To do (label them with `uv run label.py`):", *open_names]
    if counts[Status.MISSING_IMAGE]:
        lines += ["", "Missing images: ingest the original photos again."]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawTextHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)
    ingest = commands.add_parser("ingest", help="add new photos from files, directories or zips")
    ingest.add_argument("sources", type=Path, nargs="+")
    commands.add_parser("status", help="show what is left to label")
    args = parser.parse_args()

    store = PhotoStore()
    if args.command == "ingest":
        added = store.ingest(args.sources)
        logger.info("%d new photos", len(added))
    print(format_status(store))


if __name__ == "__main__":
    configure_logging()
    main()
