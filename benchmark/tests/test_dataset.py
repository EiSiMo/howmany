import time
import urllib.error
import urllib.request
from email.message import Message
from pathlib import Path

import pytest

import dataset


def http_error(code: int, retry_after: str | None = None) -> urllib.error.HTTPError:
    headers = Message()
    if retry_after is not None:
        headers["Retry-After"] = retry_after
    return urllib.error.HTTPError("https://example.org/a.jpg", code, "error", headers, None)


def test_download_retries_when_rate_limited(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    responses: list[urllib.error.HTTPError | None] = [http_error(429, "7"), None]
    sleeps: list[float] = []

    def fake_urlretrieve(url: str, target: Path) -> None:
        error = responses.pop(0)
        if error is not None:
            raise error
        target.write_bytes(b"image")

    monkeypatch.setattr(urllib.request, "urlretrieve", fake_urlretrieve)
    monkeypatch.setattr(time, "sleep", sleeps.append)

    dataset.download("https://example.org/a.jpg", tmp_path / "a.jpg")

    assert (tmp_path / "a.jpg").read_bytes() == b"image"
    assert sleeps == [7.0]


def test_download_fails_loudly_on_missing_file(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def fake_urlretrieve(url: str, target: Path) -> None:
        raise http_error(404)

    monkeypatch.setattr(urllib.request, "urlretrieve", fake_urlretrieve)

    with pytest.raises(RuntimeError, match="a.jpg"):
        dataset.download("https://example.org/a.jpg", tmp_path / "a.jpg")
    assert not list(tmp_path.iterdir())


def test_download_retries_when_connection_drops(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    attempts: list[int] = []

    def fake_urlretrieve(url: str, target: Path) -> None:
        attempts.append(1)
        if len(attempts) == 1:
            raise urllib.error.URLError(ConnectionResetError())
        target.write_bytes(b"image")

    monkeypatch.setattr(urllib.request, "urlretrieve", fake_urlretrieve)
    monkeypatch.setattr(time, "sleep", lambda seconds: None)

    dataset.download("https://example.org/a.jpg", tmp_path / "a.jpg")

    assert (tmp_path / "a.jpg").read_bytes() == b"image"


def test_load_samples_reads_exemplars_from_manifest(tmp_path: Path) -> None:
    (tmp_path / "a.jpg").write_bytes(b"image")
    manifest = tmp_path / "manifest.csv"
    manifest.write_text(
        'image,category,count,exemplars\na.jpg,coins,7,"[[10, 20, 30, 40], [50, 60, 70, 80]]"\n'
    )

    samples = dataset.load_samples(manifest, tmp_path)

    assert samples == [
        dataset.Sample(
            tmp_path / "a.jpg", "coins", 7, ((10.0, 20.0, 30.0, 40.0), (50.0, 60.0, 70.0, 80.0))
        )
    ]
