import dataclasses
import zipfile
from pathlib import Path
from typing import Any

import pytest
from PIL import Image

from photos import Label, PhotoStore, Status

BOXES = ((10.0, 10.0, 20.0, 20.0), (30.0, 10.0, 40.0, 20.0), (50.0, 10.0, 60.0, 20.0))
POINTS = ((15.0, 15.0), (35.0, 15.0), (55.0, 15.0), (75.0, 15.0))


def write_jpeg(path: Path, color: str = "red", orientation: int = 1) -> Path:
    exif = Image.Exif()
    exif[0x0112] = orientation
    Image.new("RGB", (40, 20), color).save(path, exif=exif)
    return path


def labelled(store: PhotoStore, name: str, **changes: Any) -> Label:
    label = dataclasses.replace(
        store.label(name), category="coins", exemplars=BOXES, points=POINTS, complete=True
    )
    return dataclasses.replace(label, **changes)


@pytest.fixture
def store(tmp_path: Path) -> PhotoStore:
    return PhotoStore(tmp_path / "labels", tmp_path / "images")


def test_ingests_photos_from_zip_with_ascii_names(tmp_path: Path, store: PhotoStore) -> None:
    archive = tmp_path / "export.zip"
    with zipfile.ZipFile(archive, "w") as file:
        file.write(write_jpeg(tmp_path / "a.jpg", "red"), "howmany-training/Wäscheklammern.jpg")
        file.write(write_jpeg(tmp_path / "b.jpg", "blue"), "howmany-training/Pflastersteine_.JPG")

    added = store.ingest([archive])

    assert added == ["waescheklammern", "pflastersteine"]
    assert store.names() == ["pflastersteine", "waescheklammern"]
    assert store.image_path("waescheklammern").exists()
    assert store.status("waescheklammern") == Status.NEEDS_CATEGORY


def test_ingesting_the_same_photo_again_adds_nothing(tmp_path: Path, store: PhotoStore) -> None:
    photo = write_jpeg(tmp_path / "coins.jpg")
    store.ingest([photo])
    store.save("coins", labelled(store, "coins"))

    assert store.ingest([tmp_path]) == []
    assert store.status("coins") == Status.COMPLETE


def test_keeps_different_photos_with_the_same_name(tmp_path: Path, store: PhotoStore) -> None:
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    write_jpeg(tmp_path / "a" / "coins.jpg", "red")
    write_jpeg(tmp_path / "b" / "coins.jpg", "blue")

    assert store.ingest([tmp_path / "a", tmp_path / "b"]) == ["coins", "coins-2"]


def test_bakes_exif_rotation_into_the_image(tmp_path: Path, store: PhotoStore) -> None:
    store.ingest([write_jpeg(tmp_path / "coins.jpg", orientation=6)])

    with Image.open(store.image_path("coins")) as image:
        assert image.size == (20, 40)
        assert image.getexif().get(0x0112, 1) == 1


def test_status_tells_what_is_missing_next(tmp_path: Path, store: PhotoStore) -> None:
    store.ingest([write_jpeg(tmp_path / "coins.jpg")])
    label = store.label("coins")

    steps = [
        (label, Status.NEEDS_CATEGORY),
        (dataclasses.replace(label, category="coins", exemplars=BOXES[:2]), Status.NEEDS_EXEMPLARS),
        (dataclasses.replace(label, category="coins", exemplars=BOXES), Status.NEEDS_POINTS),
        (labelled(store, "coins"), Status.COMPLETE),
        (dataclasses.replace(label, excluded=True), Status.EXCLUDED),
    ]
    for step, status in steps:
        store.save("coins", step)
        assert store.status("coins") == status


def test_reports_labelled_photos_whose_image_is_gone(tmp_path: Path, store: PhotoStore) -> None:
    store.ingest([write_jpeg(tmp_path / "coins.jpg")])
    store.image_path("coins").unlink()

    assert store.status("coins") == Status.MISSING_IMAGE


def test_refuses_to_mark_an_unfinished_label_complete(tmp_path: Path, store: PhotoStore) -> None:
    store.ingest([write_jpeg(tmp_path / "coins.jpg")])

    with pytest.raises(ValueError, match="exemplars"):
        store.save("coins", labelled(store, "coins", exemplars=BOXES[:2]))


def test_complete_photos_become_benchmark_samples(tmp_path: Path, store: PhotoStore) -> None:
    store.ingest(
        [write_jpeg(tmp_path / "coins.jpg", "red"), write_jpeg(tmp_path / "pills.jpg", "green")]
    )
    store.ingest([write_jpeg(tmp_path / "nuts.jpg", "blue")])
    store.save("coins", labelled(store, "coins"))
    store.save("nuts", labelled(store, "nuts", excluded=True))

    samples = store.samples(exemplars=1)

    assert [(s.image_path.name, s.category, s.true_count, s.exemplars) for s in samples] == [
        ("coins.jpg", "coins", 4, BOXES[:1])
    ]


def test_labels_survive_a_round_trip_through_disk(tmp_path: Path, store: PhotoStore) -> None:
    store.ingest([write_jpeg(tmp_path / "coins.jpg")])
    label = labelled(store, "coins")
    store.save("coins", label)

    assert PhotoStore(tmp_path / "labels", tmp_path / "images").label("coins") == label
