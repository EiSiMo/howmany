import pytest

from metrics import Result, summarize


def result(true_count: int, predicted_count: int) -> Result:
    return Result("x.jpg", "pills", true_count, predicted_count, seconds=0.5)


def test_summary_measures_deviation_from_true_counts() -> None:
    summary = summarize([result(100, 100), result(100, 104), result(50, 40)])

    assert summary.samples == 3
    assert summary.mae == pytest.approx(14 / 3)
    assert summary.rmse == pytest.approx((116 / 3) ** 0.5)
    assert summary.mean_relative_error == pytest.approx((0 + 0.04 + 0.2) / 3)
    assert summary.exact_rate == pytest.approx(1 / 3)
    assert summary.within_5_percent_rate == pytest.approx(2 / 3)
    assert summary.within_10_percent_rate == pytest.approx(2 / 3)
    assert summary.mean_seconds == pytest.approx(0.5)


def test_summary_rejects_empty_run() -> None:
    with pytest.raises(ValueError):
        summarize([])
