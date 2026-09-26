"""Run a counting prototype against the benchmark and report how far off it is.

A prototype is a Python file that defines `quantify(image_path: Path) -> int`.

Usage: uv run run.py prototypes/prototype-0.py
"""

import argparse
import csv
import dataclasses
import importlib.util
import json
import logging
import time
from collections.abc import Callable, Sequence
from pathlib import Path
from types import ModuleType

from dataset import BENCHMARK_DIR, Sample, load_samples
from metrics import Result, Summary, summarize

logger = logging.getLogger(__name__)

RESULTS_DIR = BENCHMARK_DIR / "results"

Quantify = Callable[[Path], int]


def load_module(path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(path.stem.replace("-", "_"), path)
    if spec is None or spec.loader is None:
        raise ValueError(f"Cannot load prototype from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_prototype(path: Path) -> Quantify:
    module = load_module(path)
    quantify = getattr(module, "quantify", None)
    if not callable(quantify):
        raise ValueError(f"{path} does not define a quantify(image_path) function")
    return quantify  # type: ignore[no-any-return]


def evaluate(quantify: Quantify, samples: Sequence[Sample]) -> list[Result]:
    results = []
    for index, sample in enumerate(samples, start=1):
        start = time.perf_counter()
        try:
            predicted = quantify(sample.image_path)
        except Exception as error:
            raise RuntimeError(f"quantify failed on {sample.image_path.name}") from error
        seconds = time.perf_counter() - start
        if not isinstance(predicted, int):
            raise TypeError(
                f"quantify returned {type(predicted).__name__} for "
                f"{sample.image_path.name}, expected int"
            )
        logger.info(
            "[%d/%d] %s: predicted %d, true %d",
            index,
            len(samples),
            sample.image_path.name,
            predicted,
            sample.true_count,
        )
        results.append(
            Result(sample.image_path.name, sample.category, sample.true_count, predicted, seconds)
        )
    return results


def write_results(name: str, results: Sequence[Result], summary: Summary) -> None:
    RESULTS_DIR.mkdir(exist_ok=True)
    with (RESULTS_DIR / f"{name}.csv").open("w", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(("image", "category", "true_count", "predicted_count", "error", "seconds"))
        for r in results:
            writer.writerow(
                (r.image, r.category, r.true_count, r.predicted_count, r.error, f"{r.seconds:.4f}")
            )
    (RESULTS_DIR / f"{name}.summary.json").write_text(
        json.dumps(dataclasses.asdict(summary), indent=2) + "\n"
    )


def format_summary(name: str, summary: Summary) -> str:
    return "\n".join(
        (
            f"Prototype:            {name}",
            f"Samples:              {summary.samples}",
            f"MAE:                  {summary.mae:.2f}",
            f"RMSE:                 {summary.rmse:.2f}",
            f"Mean relative error:  {summary.mean_relative_error:.1%}",
            f"Exact:                {summary.exact_rate:.1%}",
            f"Within 5%:            {summary.within_5_percent_rate:.1%}",
            f"Within 10%:           {summary.within_10_percent_rate:.1%}",
            f"Mean time per image:  {summary.mean_seconds:.3f} s",
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("prototype", type=Path, help="path to a prototype file")
    args = parser.parse_args()

    quantify = load_prototype(args.prototype)
    results = evaluate(quantify, load_samples())
    summary = summarize(results)
    write_results(args.prototype.stem, results, summary)
    print(format_summary(args.prototype.stem, summary))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    main()
