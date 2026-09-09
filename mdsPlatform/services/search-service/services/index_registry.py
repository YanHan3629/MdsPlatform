from dataclasses import dataclass
from typing import Dict, Optional

from services.manifest_loader import ManifestLoader


@dataclass
class LoadedIndex:
    dataset_id: str
    version_id: str
    index_version_id: str
    bundle: dict
    manifest: dict
    image_index: object
    text_index: object
    image_metadata: object
    text_metadata: object
    unified_index: object = None
    unified_metadata: object = None


class IndexRegistry:
    def __init__(self):
        self._cache: Dict[str, LoadedIndex] = {}
        self._loader = ManifestLoader()

    def build_key(self, dataset_id: str, version_id: str, index_version_id: str) -> str:
        return f"{dataset_id}:{version_id}:{index_version_id}"

    def get(self, dataset_id: str, version_id: str, index_version_id: str) -> Optional[LoadedIndex]:
        return self._cache.get(self.build_key(dataset_id, version_id, index_version_id))

    def ensure_loaded(self, dataset_id: str, version_id: str, index_version_id: str) -> LoadedIndex:
        key = self.build_key(dataset_id, version_id, index_version_id)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        loaded = self._loader.load(dataset_id, version_id, index_version_id)
        wrapped = LoadedIndex(
            dataset_id=dataset_id,
            version_id=version_id,
            index_version_id=index_version_id,
            bundle=loaded["bundle"],
            manifest=loaded["manifest"],
            image_index=loaded["image_index"],
            text_index=loaded["text_index"],
            image_metadata=loaded["image_metadata"],
            text_metadata=loaded["text_metadata"],
            unified_index=loaded.get("unified_index"),
            unified_metadata=loaded.get("unified_metadata"),
        )
        self._cache[key] = wrapped
        return wrapped


registry = IndexRegistry()
