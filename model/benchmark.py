"""Count the benchmark's images with the counter and report how far off it is.

The benchmark is a fixed random sample of 100 FSC-147 test images (manifest.csv), or with --photos
our own completely labelled phone photos. Each image comes with exemplar boxes around instances of
the object to count; by default the counter gets one, as the user marks one in the app.

Usage: uv run benchmark.py [--exemplars 1|2|3] [--photos]
"""

import argparse
import csv
import dataclasses
import json
import logging
import time
from collections.abc import Callable, Sequence
from pathlib import Path

import counter
from dataset import EXEMPLARS, MODEL_DIR, Box, Sample, configure_logging, load_samples
from metrics import Result, Summary, summarize
from photos import PhotoStore

logger = logging.getLogger(__name__)

RESULTS_DIR = MODEL_DIR / "results"
# The app lets the user mark one to three exemplars.
DEFAULT_EXEMPLARS = 1

# count(image_path, exemplars) -> the number of objects, as counter.count.
Count = Callable[[Path, Sequence[Box]], int]


def evaluate(count: Count, samples: Sequence[Sample]) -> list[Result]:
    results = []
    for index, sample in enumerate(samples, start=1):
        start = time.perf_counter()
        try:
            predicted = count(sample.image_path, sample.exemplars)
        except Exception as error:
            raise RuntimeError(f"Counting failed on {sample.image_path.name}") from error
        seconds = time.perf_counter() - start
        if not isinstance(predicted, int):
            raise TypeError(
                f"Counting returned {type(predicted).__name__} for "
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


def result_name(exemplars: int, photos: bool) -> str:
    """Name of a run's result files: the images counted and the exemplars per image."""
    return f"{'photos' if photos else 'fsc147'}-{exemplars}-exemplar"


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
            f"Run:                  {name}",
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
    parser.add_argument(
        "--exemplars",
        type=int,
        choices=range(1, EXEMPLARS + 1),
        default=DEFAULT_EXEMPLARS,
        help=f"exemplar boxes per image (default: {DEFAULT_EXEMPLARS}, as in the app)",
    )
    parser.add_argument(
        "--photos",
        action="store_true",
        help="count our own completely labelled photos instead of FSC-147",
    )
    args = parser.parse_args()

    name = result_name(args.exemplars, args.photos)
    if args.photos:
        samples = PhotoStore().samples(exemplars=args.exemplars)
        if not samples:
            raise SystemExit("No completely labelled photos yet, label them with `uv run label.py`")
    else:
        samples = load_samples(exemplars=args.exemplars)
    results = evaluate(counter.count, samples)
    summary = summarize(results)
    write_results(name, results, summary)
    print(format_summary(name, summary))


if __name__ == "__main__":
    configure_logging()
    main()
