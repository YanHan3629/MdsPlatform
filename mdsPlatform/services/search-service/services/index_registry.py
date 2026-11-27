from core.worker_authority import require_scope
from dataclasses import dataclass
from collections import OrderedDict
from threading import BoundedSemaphore, Lock
from typing import Dict, Optional
from weakref import WeakValueDictionary
from contextlib import contextmanager
from functools import wraps
from threading import RLock

from core.config import settings
from services.manifest_loader import ManifestLoader
from services.professional_retrievers import ProfessionalRetrieverSuite
from services.snapshot_scope import SnapshotScope


CAPABILITY_SOURCE_CACHE_BYTES = 32 * 1024 * 1024


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
    preloaded: bool = False


@dataclass
class LoadedCapabilities:
    manifest: dict
    scope: SnapshotScope
    source_bytes: int


@dataclass
class CandidateImages:
    image_index: object
    image_metadata: object


class IndexRegistry:
    def __init__(self):
        self._cache: OrderedDict[str, LoadedIndex] = OrderedDict()
        # Strong references in active callers keep a per-key lock alive until
        # all waiters finish. Dropping a dict entry before those waiters finish
        # would let a third caller start a concurrent load of the same scope.
        self._locks = WeakValueDictionary()
        self._capabilities_cache: OrderedDict[str, LoadedCapabilities] = OrderedDict()
        self._capability_cache_bytes = 0
        self._guard = Lock()
        self._operation_guard = RLock()
        self._reference_guard = BoundedSemaphore(3)
        self._loader = ManifestLoader()

    @contextmanager
    def operation(self):
        # Keep a full snapshot lease through retrieval, not merely through
        # loading. Otherwise another request can evict and reload it while
        # the first still holds the large decoded records in local variables.
        acquired = self._operation_guard.acquire(timeout=settings.request_timeout_seconds)
        if not acquired:
            from fastapi import HTTPException
            raise HTTPException(status_code=503, detail='INDEX_MEMORY_QUEUE_TIMEOUT: search is waiting for its bounded memory lease')
        try:
            yield
        finally:
            self._operation_guard.release()

    def serialized(self, function):
        @wraps(function)
        def invoke(*args, **kwargs):
            with self.operation():
                return function(*args, **kwargs)
        return invoke

    def build_key(self, dataset_id: str, version_id: str, index_version_id: str) -> str:
        return f"{dataset_id}:{version_id}:{index_version_id}"

    def get(self, dataset_id: str, version_id: str, index_version_id: str) -> Optional[LoadedIndex]:
        require_scope(dataset_id,version_id,index_version_id)
        key = self.build_key(dataset_id, version_id, index_version_id)
        with self._guard:
            value = self._cache.get(key)
            if value is not None:
                self._cache.move_to_end(key)
            return value

    def ensure_loaded(self, dataset_id: str, version_id: str, index_version_id: str) -> LoadedIndex:
        require_scope(dataset_id,version_id,index_version_id)
        with self.operation():
            return self._ensure_loaded(dataset_id, version_id, index_version_id)

    def _ensure_loaded(self, dataset_id: str, version_id: str, index_version_id: str) -> LoadedIndex:
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
            # Release the old cache before decoding its replacement. Evicting
            # only after loading would still require both full snapshots at peak.
            with self._guard:
                while len(self._cache) >= settings.max_loaded_indexes:
                    self._cache.popitem(last=False)
            loaded = self._loader.load(dataset_id, version_id, index_version_id)
            with self._guard:
                capabilities = self._capabilities_cache.get(key)
            if capabilities is not None:
                loaded['professional_cache']['snapshot.scope'] = capabilities.scope
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
            return wrapped

    def ensure_preloaded(self, dataset_id: str, version_id: str, index_version_id: str) -> LoadedIndex:
        require_scope(dataset_id,version_id,index_version_id)
        """Complete the publication gate even if a query already loaded metadata."""
        with self.operation():
            return self._ensure_preloaded(dataset_id, version_id, index_version_id)

    def _ensure_preloaded(self, dataset_id: str, version_id: str, index_version_id: str) -> LoadedIndex:
        loaded = self.ensure_loaded(dataset_id, version_id, index_version_id)
        key = self.build_key(dataset_id, version_id, index_version_id)
        with self._guard:
            key_lock = self._locks.setdefault(key, Lock())
        with key_lock:
            if not loaded.preloaded:
                ProfessionalRetrieverSuite(loaded.manifest.get('indexes', []),
                    loaded.professional_files or {}, cache=loaded.professional_cache).preload()
                loaded.preloaded = True
        return loaded

    @contextmanager
    def reference_query_suite(self, dataset_id: str, version_id: str, index_version_id: str):
        # Exact reference scans only keep a bounded top K in memory. Their
        # short independent lease does not wait for a full dense index load.
        acquired = self._reference_guard.acquire(timeout=settings.request_timeout_seconds)
        if not acquired:
            from fastapi import HTTPException
            raise HTTPException(status_code=503, detail='业务编号检索正在排队，请稍后重试。')
        try:
            capabilities = self.ensure_capabilities(dataset_id, version_id, index_version_id)
            manifest, files, _ = self._loader.load_professional_subset(
                dataset_id, version_id, index_version_id, {'structured.typed'}, reference_only=True)
            yield ProfessionalRetrieverSuite(manifest.get('indexes', []), files,
                                             cache={'snapshot.scope': capabilities.scope})
        finally:
            self._reference_guard.release()

    def professional_query_suite(self, dataset_id: str, version_id: str, index_version_id: str, *, retrievers: set[str]):
        suite, _ = self._professional_query(dataset_id, version_id, index_version_id, retrievers)
        return suite

    def candidate_query(self, dataset_id: str, version_id: str, index_version_id: str, *, text: bool, visual: bool):
        return self._professional_query(dataset_id, version_id, index_version_id,
                                        {'text'} if text else set(), visual=visual)

    def _professional_query(self, dataset_id: str, version_id: str, index_version_id: str, retrievers: set[str],
                            *, visual: bool = False):
        spaces = set()
        if 'structured' in retrievers:
            spaces.add('structured.typed')
        if 'text' in retrievers:
            spaces.update({'text.lexical.zh', 'text.semantic.zh'})
        with self.operation():
            scope_key = self.build_key(dataset_id,version_id,index_version_id)
            active = self.get(dataset_id,version_id,index_version_id)
            if active is not None:
                suite = ProfessionalRetrieverSuite(active.manifest.get('indexes',[]),active.professional_files,
                                                   cache=active.professional_cache)
                return suite, active if visual else None
            with self._guard:
                # Preserve the full-index memory lease while an independent subset is active.
                self._cache.clear()
                capabilities = self._capabilities_cache.get(scope_key)
            manifest, files, bundle = self._loader.load_professional_subset(dataset_id,version_id,index_version_id,spaces)
            cache = {'snapshot.scope':capabilities.scope} if capabilities else {}
            suite = ProfessionalRetrieverSuite(manifest.get('indexes',[]),files,cache=cache)
            images = CandidateImages(*self._loader.load_candidate_images(dataset_id, version_id, index_version_id,
                                                                         bundle)) if visual else None
            return suite, images

    def ensure_capabilities(self, dataset_id: str, version_id: str, index_version_id: str) -> LoadedCapabilities:
        require_scope(dataset_id,version_id,index_version_id)
        key = self.build_key(dataset_id, version_id, index_version_id)
        with self._guard:
            cached = self._capabilities_cache.get(key)
            if cached is not None:
                self._capabilities_cache.move_to_end(key)
                return cached
            key_lock = self._locks.setdefault(key, Lock())
        with key_lock:
            with self._guard:
                cached = self._capabilities_cache.get(key)
                if cached is not None:
                    self._capabilities_cache.move_to_end(key)
                    return cached
            loaded = self.get(dataset_id, version_id, index_version_id)
            if loaded is None:
                data = self._loader.load_capabilities(dataset_id, version_id, index_version_id)
                manifest, files, cache = data['manifest'], data['professional_files'], {}
            else:
                manifest, files, cache = loaded.manifest, loaded.professional_files, loaded.professional_cache
            suite = ProfessionalRetrieverSuite(manifest.get('indexes', []), files, cache=cache)
            scope = SnapshotScope.from_suite(suite)
            source_bytes = suite._path('snapshot.facets', 'facets.json').stat().st_size
            capabilities = LoadedCapabilities(manifest, scope, source_bytes)
            slots = settings.max_loaded_capabilities or settings.max_loaded_indexes
            with self._guard:
                self._capabilities_cache[key] = capabilities
                self._capability_cache_bytes += source_bytes
                while len(self._capabilities_cache) > 1 and (
                        len(self._capabilities_cache) > slots or
                        self._capability_cache_bytes > CAPABILITY_SOURCE_CACHE_BYTES):
                    _, evicted = self._capabilities_cache.popitem(last=False)
                    self._capability_cache_bytes -= evicted.source_bytes
            return capabilities


registry = IndexRegistry()
