import json
import threading
import urllib.error
import urllib.request
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest
from PIL import Image

from label import make_server
from photos import PhotoStore

BOXES = [[10, 10, 20, 20], [30, 10, 40, 20], [50, 10, 60, 20]]


@pytest.fixture
def base_url(tmp_path: Path) -> Iterator[str]:
    Image.new("RGB", (80, 40), "red").save(tmp_path / "coins.jpg")
    store = PhotoStore(tmp_path / "labels", tmp_path / "images")
    store.ingest([tmp_path / "coins.jpg"])
    server = make_server(store, 0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_port}"
    server.shutdown()
    server.server_close()


def request(url: str, method: str = "GET", body: Any = None) -> Any:
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data, method=method)) as response:
        return json.loads(response.read())


def test_saves_labels_and_reports_progress(base_url: str) -> None:
    label = {"category": "coins", "exemplars": BOXES, "points": [[15, 15], [35, 15]]}

    saved = request(f"{base_url}/api/photos/coins", "PUT", {**label, "complete": True})

    assert saved["status"] == "complete"
    assert request(f"{base_url}/api/photos") == [
        {"name": "coins", "status": "complete", "count": 2}
    ]


def test_rejects_invalid_labels_without_saving(base_url: str) -> None:
    with pytest.raises(urllib.error.HTTPError) as error:
        request(f"{base_url}/api/photos/coins", "PUT", {"category": "coins", "complete": True})

    assert error.value.code == 400
    assert request(f"{base_url}/api/photos/coins")["status"] == "needs category"


def test_serves_only_known_photos(base_url: str) -> None:
    with pytest.raises(urllib.error.HTTPError) as error:
        request(f"{base_url}/images/..%2F..%2Fsecret.jpg")

    assert error.value.code == 404
