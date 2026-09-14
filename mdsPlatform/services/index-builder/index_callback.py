"""Publish a completed index only after the job bootstrap uploaded every file."""

from __future__ import annotations

import json
import os
import urllib.request
from pathlib import Path


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
    internal = os.environ.get("MM_INTERNAL_TOKEN", "")
    if bearer:
        headers["Authorization"] = f"Bearer {bearer}"
    if internal:
        headers["X-MM-Internal-Token"] = internal
    request = urllib.request.Request(
        base_url + ready_path,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status < 200 or response.status >= 300:
            raise RuntimeError(f"READY callback failed: HTTP {response.status}")
    print("[index-builder] publication callback accepted after complete upload", flush=True)


if __name__ == "__main__":
    main()
