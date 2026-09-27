from pathlib import Path

import pytest

from dataset import Sample
from prototype import load_prototype
from run import evaluate

EXEMPLARS = ((10.0, 20.0, 30.0, 40.0), (50.0, 60.0, 70.0, 80.0))


def write_prototype(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "prototype-test.py"
    path.write_text(f"def quantify(image_path, exemplars, text):\n    {body}\n")
    return path


def test_evaluates_prototype_against_samples(tmp_path: Path) -> None:
    quantify = load_prototype(write_prototype(tmp_path, "return 7"))
    samples = [
        Sample(tmp_path / "a.jpg", "coins", 7, EXEMPLARS),
        Sample(tmp_path / "b.jpg", "coins", 10, EXEMPLARS),
    ]

    results = evaluate(quantify, samples)

    assert [(r.image, r.predicted_count, r.error) for r in results] == [
        ("a.jpg", 7, 0),
        ("b.jpg", 7, -3),
    ]


def test_rejects_non_integer_counts(tmp_path: Path) -> None:
    quantify = load_prototype(write_prototype(tmp_path, "return 7.5"))

    with pytest.raises(TypeError):
        evaluate(quantify, [Sample(tmp_path / "a.jpg", "coins", 7, EXEMPLARS)])


def test_passes_exemplars_to_prototype(tmp_path: Path) -> None:
    quantify = load_prototype(write_prototype(tmp_path, "return len(exemplars)"))

    results = evaluate(quantify, [Sample(tmp_path / "a.jpg", "coins", 2, EXEMPLARS)])

    assert results[0].predicted_count == 2


def test_passes_category_as_text_prompt(tmp_path: Path) -> None:
    quantify = load_prototype(write_prototype(tmp_path, "return len(text)"))

    results = evaluate(quantify, [Sample(tmp_path / "a.jpg", "coins", 5, EXEMPLARS)])

    assert results[0].predicted_count == len("coins")
