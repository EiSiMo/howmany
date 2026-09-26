from pathlib import Path

import pytest

from dataset import Sample
from run import evaluate, load_prototype


def write_prototype(tmp_path: Path, body: str) -> Path:
    path = tmp_path / "prototype-test.py"
    path.write_text(f"def quantify(image_path):\n    {body}\n")
    return path


def test_evaluates_prototype_against_samples(tmp_path: Path) -> None:
    quantify = load_prototype(write_prototype(tmp_path, "return 7"))
    samples = [Sample(tmp_path / "a.jpg", "coins", 7), Sample(tmp_path / "b.jpg", "coins", 10)]

    results = evaluate(quantify, samples)

    assert [(r.image, r.predicted_count, r.error) for r in results] == [
        ("a.jpg", 7, 0),
        ("b.jpg", 7, -3),
    ]


def test_rejects_non_integer_counts(tmp_path: Path) -> None:
    quantify = load_prototype(write_prototype(tmp_path, "return 7.5"))

    with pytest.raises(TypeError):
        evaluate(quantify, [Sample(tmp_path / "a.jpg", "coins", 7)])
