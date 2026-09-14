from dataclasses import dataclass
from collections import OrderedDict
from threading import Lock
from typing import Dict, Optional

from core.config import settings
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
    professional_files: dict = None
    professional_cache: dict = None


class IndexRegistry:
    def __init__(self):
        self._cache: OrderedDict[str, LoadedIndex] = OrderedDict()
        self._locks: Dict[str, Lock] = {}
        self._guard = Lock()
        self._loader = ManifestLoader()

    def build_key(self, dataset_id: str, version_id: str, index_version_id: str) -> str:
        return f"{dataset_id}:{version_id}:{index_version_id}"

    def get(self, dataset_id: str, version_id: str, index_version_id: str) -> Optional[LoadedIndex]:
        key = self.build_key(dataset_id, version_id, index_version_id)
        with self._guard:
            value = self._cache.get(key)
            if value is not None:
                self._cache.move_to_end(key)
            return value

    def ensure_loaded(self, dataset_id: str, version_id: str, index_version_id: str) -> LoadedIndex:
        key = self.build_key(dataset_id, version_id, index_version_id)
        cached = self.get(dataset_id, version_id, index_version_id)
        if cached is not None:
            return cached
        with self._guard:
            key_lock = self._locks.setdefault(key, Lock())
        with key_lock:
            cached = self.get(dataset_id, version_id, index_version_id)
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
                professional_files=loaded.get("professional_files", {}),
                professional_cache=loaded.get("professional_cache", {}),
            )
            with self._guard:
                self._cache[key] = wrapped
                self._cache.move_to_end(key)
                while len(self._cache) > settings.max_loaded_indexes:
                    self._cache.popitem(last=False)
                self._locks.pop(key, None)
            return wrapped


registry = IndexRegistry()
