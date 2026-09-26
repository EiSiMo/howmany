"""Accuracy and speed metrics for a benchmark run."""

import math
from collections.abc import Sequence
from dataclasses import dataclass


@dataclass(frozen=True)
class Result:
    image: str
    category: str
    true_count: int
    predicted_count: int
    seconds: float

    @property
    def error(self) -> int:
        return self.predicted_count - self.true_count

    @property
    def relative_error(self) -> float:
        return abs(self.error) / self.true_count


@dataclass(frozen=True)
class Summary:
    samples: int
    mae: float
    rmse: float
    mean_relative_error: float
    exact_rate: float
    within_5_percent_rate: float
    within_10_percent_rate: float
    mean_seconds: float


def summarize(results: Sequence[Result]) -> Summary:
    if not results:
        raise ValueError("Cannot summarize an empty benchmark run")
    n = len(results)

    def rate(condition: Sequence[bool]) -> float:
        return sum(condition) / n

    return Summary(
        samples=n,
        mae=sum(abs(r.error) for r in results) / n,
        rmse=math.sqrt(sum(r.error**2 for r in results) / n),
        mean_relative_error=sum(r.relative_error for r in results) / n,
        exact_rate=rate([r.error == 0 for r in results]),
        within_5_percent_rate=rate([r.relative_error <= 0.05 for r in results]),
        within_10_percent_rate=rate([r.relative_error <= 0.10 for r in results]),
        mean_seconds=sum(r.seconds for r in results) / n,
    )
