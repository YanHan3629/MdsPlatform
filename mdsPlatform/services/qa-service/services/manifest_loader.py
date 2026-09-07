import json
from pathlib import Path

import faiss
import pandas as pd

from adapters.backend_client import BackendClient
from core.config import settings


class ManifestLoader:
    """读取与 search-service 完全相同的索引 bundle（manifest.json + faiss + parquet）。"""

    def __init__(self):
        self.backend = BackendClient()

    @staticmethod
    def _target_path(base_dir: Path, logical_path: str) -> Path:
        rel = Path(logical_path.lstrip("/"))
        target = base_dir / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        return target

    def load(self, dataset_id: str, version_id: str, index_version_id: str):
        base_dir = Path(settings.local_cache_dir) / dataset_id / version_id / index_version_id
        base_dir.mkdir(parents=True, exist_ok=True)

        bundle = self.backend.resolve_index_bundle(dataset_id, version_id, index_version_id)
        manifest_path = self.backend.download_to(
            bundle["manifestUrl"], self._target_path(base_dir, bundle["manifestPath"])
        )
        image_index_file = self.backend.download_to(
            bundle["imageIndexUrl"], self._target_path(base_dir, bundle["imageIndexPath"])
        )
        text_index_file = self.backend.download_to(
            bundle["textIndexUrl"], self._target_path(base_dir, bundle["textIndexPath"])
        )
        image_meta_file = self.backend.download_to(
            bundle["imageMetadataUrl"], self._target_path(base_dir, bundle["imageMetadataPath"])
        )
        text_meta_file = self.backend.download_to(
            bundle["textMetadataUrl"], self._target_path(base_dir, bundle["textMetadataPath"])
        )
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        return {
            "bundle": bundle,
            "manifest": manifest,
            "image_index": faiss.read_index(str(image_index_file)),
            "text_index": faiss.read_index(str(text_index_file)),
            "image_metadata": pd.read_parquet(image_meta_file),
            "text_metadata": pd.read_parquet(text_meta_file),
        }
