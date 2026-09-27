"""Run a counting prototype against the benchmark and report how far off it is.

A prototype is a Python file that defines
`quantify(image_path: Path, exemplars: Sequence[Box], text: str) -> int`. Exemplars are a few
example instances of the object to count, as a user would mark them; text names the object, as
a user would type it. Prototypes use whichever prompt they support.

Usage: uv run run.py prototypes/prototype-0.py [--exemplars 0|1|2|3] [--photos]
"""

import argparse
import csv
import dataclasses
import json
import logging
import time
from collections.abc import Sequence
from pathlib import Path

from dataset import BENCHMARK_DIR, EXEMPLARS, Sample, configure_logging, load_samples
from metrics import Result, Summary, summarize
from photos import PhotoStore
from prototype import Quantify, load_prototype

logger = logging.getLogger(__name__)

RESULTS_DIR = BENCHMARK_DIR / "results"


def evaluate(quantify: Quantify, samples: Sequence[Sample]) -> list[Result]:
    results = []
    for index, sample in enumerate(samples, start=1):
        start = time.perf_counter()
        try:
            predicted = quantify(sample.image_path, sample.exemplars, sample.category)
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


def result_name(prototype: Path, exemplars: int, photos: bool) -> str:
    """Name of a run's result files: the prototype, suffixed for photos and fewer exemplars."""
    name = prototype.stem
    if photos:
        name += "-photos"
    if exemplars != EXEMPLARS:
        name += f"-{exemplars}-exemplar"
    return name


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
    parser.add_argument(
        "--exemplars",
        type=int,
        choices=range(EXEMPLARS + 1),
        default=EXEMPLARS,
        help=f"exemplar boxes per image, 0 for text only; fewer than {EXEMPLARS} adds an "
        "-N-exemplar suffix",
    )
    parser.add_argument(
        "--photos",
        action="store_true",
        help="run on our own completely labelled photos instead of FSC-147; adds a -photos suffix",
    )
    args = parser.parse_args()

    name = result_name(args.prototype, args.exemplars, args.photos)
    quantify = load_prototype(args.prototype)
    if args.photos:
        samples = PhotoStore().samples(exemplars=args.exemplars)
        if not samples:
            raise SystemExit("No completely labelled photos yet, label them with `uv run label.py`")
    else:
        samples = load_samples(exemplars=args.exemplars)
    results = evaluate(quantify, samples)
    summary = summarize(results)
    write_results(name, results, summary)
    print(format_summary(name, summary))


if __name__ == "__main__":
    configure_logging()
    main()
