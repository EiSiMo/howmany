"""Label our own photos in the browser: category, three exemplar boxes and a point per object.

Runs a local web server on top of PhotoStore and opens the labelling page. Every change is saved
to `photos/<name>.json` right away.

Usage: uv run label.py [--port 8765]
"""

import argparse
import json
import logging
import threading
import webbrowser
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import unquote

from photos import Label, PhotoStore

logger = logging.getLogger(__name__)

PAGE_PATH = Path(__file__).with_suffix(".html")
MAX_BODY_BYTES = 10_000_000


def _photo_summary(store: PhotoStore, name: str) -> dict[str, Any]:
    label = store.label(name)
    return {"name": name, "status": store.status(name), "count": len(label.points)}


def _photo_details(store: PhotoStore, name: str) -> dict[str, Any]:
    label = store.label(name)
    return {
        **_photo_summary(store, name),
        "category": label.category,
        "exemplars": label.exemplars,
        "points": label.points,
        "complete": label.complete,
        "excluded": label.excluded,
    }


def make_server(store: PhotoStore, port: int) -> ThreadingHTTPServer:
    """Serve the labelling page and a small JSON API for the photos in store on localhost."""

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: Any) -> None:
            logger.debug(format, *args)

        def _send(self, status: HTTPStatus, body: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _send_json(self, data: Any, status: HTTPStatus = HTTPStatus.OK) -> None:
            self._send(status, json.dumps(data).encode(), "application/json")

        def _photo_name(self, prefix: str, suffix: str = "") -> str | None:
            name = unquote(self.path[len(prefix) :].removesuffix(suffix))
            return name if name in store.names() else None

        def do_GET(self) -> None:
            if self.path == "/":
                self._send(HTTPStatus.OK, PAGE_PATH.read_bytes(), "text/html; charset=utf-8")
            elif self.path == "/api/photos":
                self._send_json([_photo_summary(store, name) for name in store.names()])
            elif self.path.startswith("/api/photos/") and (
                name := self._photo_name("/api/photos/")
            ):
                self._send_json(_photo_details(store, name))
            elif self.path.startswith("/images/") and (
                name := self._photo_name("/images/", ".jpg")
            ):
                self._send(HTTPStatus.OK, store.image_path(name).read_bytes(), "image/jpeg")
            else:
                self._send_json({"error": "not found"}, HTTPStatus.NOT_FOUND)

        def do_PUT(self) -> None:
            name = (
                self._photo_name("/api/photos/") if self.path.startswith("/api/photos/") else None
            )
            if name is None:
                self._send_json({"error": "not found"}, HTTPStatus.NOT_FOUND)
                return
            length = int(self.headers.get("Content-Length", 0))
            if length > MAX_BODY_BYTES:
                self._send_json({"error": "label too large"}, HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
                return
            try:
                data = json.loads(self.rfile.read(length))
                data["sha256"] = store.label(name).sha256
                store.save(name, Label.from_dict(data))
            except (KeyError, TypeError, ValueError) as error:
                logger.warning("Rejected label for %s: %s", name, error)
                self._send_json({"error": str(error)}, HTTPStatus.BAD_REQUEST)
                return
            self._send_json(_photo_details(store, name))

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    store = PhotoStore()
    if not store.names():
        raise SystemExit("No photos yet, add some with `uv run photos.py ingest <zip>`")
    server = make_server(store, args.port)
    url = f"http://127.0.0.1:{server.server_port}/"
    logger.info("Labelling %d photos at %s (Ctrl+C to stop)", len(store.names()), url)
    threading.Timer(0.5, webbrowser.open, (url,)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("Stopped")
    finally:
        server.server_close()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    main()
