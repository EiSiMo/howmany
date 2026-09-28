from collections.abc import Sequence
from pathlib import Path

import pytest

from benchmark import evaluate, result_name
from dataset import Box, Sample

EXEMPLARS = ((10.0, 20.0, 30.0, 40.0), (50.0, 60.0, 70.0, 80.0))


def test_evaluates_counts_against_samples(tmp_path: Path) -> None:
    samples = [
        Sample(tmp_path / "a.jpg", "coins", 7, EXEMPLARS),
        Sample(tmp_path / "b.jpg", "coins", 10, EXEMPLARS),
    ]

    results = evaluate(lambda image_path, exemplars: 7, samples)

    assert [(r.image, r.predicted_count, r.error) for r in results] == [
        ("a.jpg", 7, 0),
        ("b.jpg", 7, -3),
    ]


def test_passes_exemplars_to_counter(tmp_path: Path) -> None:
    def count(image_path: Path, exemplars: Sequence[Box]) -> int:
        return len(exemplars)

    results = evaluate(count, [Sample(tmp_path / "a.jpg", "coins", 2, EXEMPLARS)])

    assert results[0].predicted_count == 2


def test_rejects_non_integer_counts(tmp_path: Path) -> None:
    def count(image_path: Path, exemplars: Sequence[Box]) -> int:
        return 7.5  # type: ignore[return-value]

    with pytest.raises(TypeError):
        evaluate(count, [Sample(tmp_path / "a.jpg", "coins", 7, EXEMPLARS)])


@pytest.mark.parametrize(
    ("exemplars", "photos", "name"),
    [(1, False, "fsc147-1-exemplar"), (3, True, "photos-3-exemplar")],
)
def test_names_results_after_images_and_exemplars(exemplars: int, photos: bool, name: str) -> None:
    assert result_name(exemplars, photos) == name
