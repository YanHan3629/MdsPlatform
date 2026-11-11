"""Publish a completed index only after the job bootstrap uploaded every file."""

from __future__ import annotations

import http.client
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path


CALLBACK_BUDGET_SECONDS = 540
CALLBACK_ATTEMPT_SECONDS = 300
CALLBACK_RETRY_SECONDS = 3
RETRYABLE_CALLBACK_HTTP = {408, 429, 502, 503, 504}


def wait_for_ready_callback(request: urllib.request.Request) -> None:
    deadline = time.monotonic() + CALLBACK_BUDGET_SECONDS
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        try:
            with urllib.request.urlopen(request, timeout=min(CALLBACK_ATTEMPT_SECONDS, remaining)) as response:
                if not 200 <= response.status < 300:
                    raise RuntimeError(f"READY callback failed: HTTP {response.status}")
                body = response.read(1048577)
        except urllib.error.HTTPError as error:
            if error.code not in RETRYABLE_CALLBACK_HTTP:
                raise
            error.close()
        except urllib.error.URLError as error:
            if not isinstance(error.reason, (TimeoutError, ConnectionError)):
                raise
        except (TimeoutError, ConnectionError, http.client.IncompleteRead):
            pass
        else:
            if not body:
                return
            if len(body) > 1048576:
                raise RuntimeError("READY callback response is too large")
            data = json.loads(body)
            state = data.get("indexStatus") if isinstance(data, dict) else None
            if state in (None, "READY", "DEPRECATED"):
                return
            if state != "PREWARMING":
                raise RuntimeError("READY callback reported an unexpected index state")
        # Retry the same bound output coordinates and payload. The server's
        # durable callback receipt deduplicates accepted requests.
        time.sleep(max(0, min(CALLBACK_RETRY_SECONDS, deadline - time.monotonic())))
    raise TimeoutError("READY callback did not settle within its time budget")


def main() -> None:
    output_dir = Path(os.environ.get("OUTPUT_DIR", "/tmp/out"))
    manifest = json.loads((output_dir / "indexes" / "manifest.json").read_text(encoding="utf-8"))
    base_url = os.environ.get("BACKEND_BASE_URL", "").rstrip("/")
    ready_path = os.environ.get("BACKEND_CALLBACK_READY") or os.environ.get("backendCallbackReady")
    if not base_url or not ready_path:
        raise RuntimeError("backend READY callback URL is not configured")
    body = {
        "indexCommitId": os.environ.get("OUTPUT_COMMIT_ID"),
        "imageIndexPath": manifest["imageIndexPath"],
        "textIndexPath": manifest["textIndexPath"],
        "imageMetadataPath": manifest["imageMetadataPath"],
        "textMetadataPath": manifest["textMetadataPath"],
        "unifiedIndexPath": manifest["unifiedIndexPath"],
        "unifiedMetadataPath": manifest["unifiedMetadataPath"],
        "representationManifestPath": manifest["representationManifestPath"],
        "manifestPath": "/indexes/manifest.json",
        "embeddingDim": manifest["embeddingDim"],
        "imageCount": manifest["imageCount"],
        "textCount": manifest["textCount"],
        "unifiedCount": manifest["unifiedCount"],
        "indexes": manifest.get("indexes", []),
        "professionalManifestPath": manifest.get("professionalManifestPath"),
    }
    headers = {"Content-Type": "application/json"}
    bearer = os.environ.get("BACKEND_BEARER_TOKEN", "")
    if not bearer:
        raise RuntimeError("parent-bound index worker credential is required")
    headers["Authorization"] = f"Bearer {bearer}"
    request = urllib.request.Request(
        base_url + ready_path,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    wait_for_ready_callback(request)
    print("[index-builder] publication callback accepted after complete upload", flush=True)


if __name__ == "__main__":
    main()
