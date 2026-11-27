import hashlib
import json
from concurrent.futures import ThreadPoolExecutor
from contextvars import copy_context
from pathlib import Path
from tempfile import NamedTemporaryFile

import faiss
import pandas as pd

from adapters.backend_client import BackendClient
from core.config import settings


class ManifestLoader:
    def __init__(self):
        self.backend = BackendClient()

    @staticmethod
    def _target_path(base_dir: Path, logical_path: str) -> Path:
        rel = Path(logical_path.lstrip("/"))
        target = (base_dir / rel).resolve()
        if base_dir.resolve() not in target.parents:
            raise ValueError(f"bundle path escapes cache directory: {logical_path}")
        target.parent.mkdir(parents=True, exist_ok=True)
        return target

    @staticmethod
    def _verify_file(path: Path, expected_sha256: str | None) -> None:
        if not expected_sha256:
            return
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != expected_sha256:
            raise ValueError(f"bundle checksum mismatch: {path.name}")

    def _cache_registered_file(self, spec: tuple[Path, str | None, str | None]) -> None:
        target, expected_sha256, url = spec
        if target.is_file():
            try:
                self._verify_file(target, expected_sha256)
                return
            except ValueError:
                pass
        if not url:
            raise ValueError(f"registered index file has no signed download URL: {target.name}")
        with NamedTemporaryFile(dir=target.parent, prefix=".index-download-", delete=False) as stream:
            temporary = Path(stream.name)
        try:
            self.backend.download_to(url, temporary)
            self._verify_file(temporary, expected_sha256)
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)

    @staticmethod
    def _capability_registration(manifest: dict) -> tuple[dict, dict]:
        registrations = [item for item in manifest.get('indexes', [])
                         if item.get('semanticSpace') == 'snapshot.facets' and item.get('status') == 'READY']
        if len(registrations) != 1:
            raise ValueError('exactly one ready snapshot capability registration is required')
        registration = registrations[0]
        entries = registration.get('files', [])
        checksum = hashlib.sha256(''.join(item.get('sha256', '') for item in entries).encode()).hexdigest()
        if registration.get('contentChecksum') and checksum != registration['contentChecksum']:
            raise ValueError('snapshot capability registration checksum mismatch')
        facets = [item for item in entries if item.get('path', '').endswith('/facets.json')]
        if len(facets) != 1 or len(facets[0].get('sha256', '')) != 64:
            raise ValueError('one checksummed snapshot capability file is required')
        return registration, facets[0]

    def load_capabilities(self, dataset_id: str, version_id: str, index_version_id: str):
        """Materialize only the frozen capability descriptor, not search indexes.

        Explicit preload retains the publication gate. Merely describing a
        second snapshot must not construct another set of all retrievers.
        """
        base_dir = Path(settings.local_cache_dir) / dataset_id / version_id / index_version_id
        bundle = self.backend.resolve_index_bundle(dataset_id, version_id, index_version_id)
        manifest_path = self.backend.download_to(bundle['manifestUrl'], self._target_path(base_dir, bundle['manifestPath']))
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        _, entry = self._capability_registration(manifest)
        logical_path = entry['path']; target = self._target_path(base_dir, logical_path)
        valid = False
        if target.is_file():
            try:
                self._verify_file(target, entry['sha256']); valid = True
            except ValueError:
                pass
        if not valid:
            url = next((item.get('url') for item in bundle.get('indexFiles', []) if item.get('path') == logical_path), None)
            if not url:
                raise ValueError('registered capability file has no signed download URL')
            self.backend.download_to(url, target)
            self._verify_file(target, entry['sha256'])
        return {'manifest': manifest, 'professional_files': {logical_path: target}}

    def load_professional_subset(self, dataset_id: str, version_id: str, index_version_id: str, spaces: set[str],
                                 *, reference_only: bool = False):
        """Load only the checksummed registrations required by this retrieval."""
        base_dir = Path(settings.local_cache_dir) / dataset_id / version_id / index_version_id
        bundle = self.backend.resolve_index_bundle(dataset_id, version_id, index_version_id)
        manifest_path = self.backend.download_to(bundle['manifestUrl'],self._target_path(base_dir,bundle['manifestPath']))
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        files = self._registered_files(base_dir,bundle,manifest,spaces=spaces | {'snapshot.facets'},
                                       reference_only=reference_only)
        return manifest, files, bundle

    def load_candidate_images(self, dataset_id: str, version_id: str, index_version_id: str, bundle: dict):
        """Load the image index used by the same QA candidate ranking."""
        base_dir = Path(settings.local_cache_dir) / dataset_id / version_id / index_version_id
        index_path = self.backend.download_to(bundle["imageIndexUrl"], self._target_path(base_dir, bundle["imageIndexPath"]))
        metadata_path = self.backend.download_to(bundle["imageMetadataUrl"], self._target_path(base_dir, bundle["imageMetadataPath"]))
        return faiss.read_index(str(index_path)), pd.read_parquet(metadata_path)

    def _registered_files(self, base_dir, bundle, manifest, *, spaces=None, reference_only=False):
        professional_files = {}
        transfers = {}
        signed = {item.get("path"): item.get("url") for item in bundle.get("indexFiles", [])}
        for registration in manifest.get("indexes", []):
            semantic_space = registration.get("semanticSpace")
            if spaces is not None and semantic_space not in spaces:
                continue
            entries = registration.get("files", [])
            registered_checksum = registration.get("contentChecksum")
            if registered_checksum and hashlib.sha256("".join(item.get("sha256", "") for item in entries).encode()).hexdigest() != registered_checksum:
                raise ValueError(f"index registration checksum mismatch: {semantic_space}")
            for item in entries:
                logical_path = item.get("path")
                if not logical_path or logical_path in professional_files:
                    continue
                if reference_only and semantic_space == 'structured.typed' and logical_path.rsplit('/', 1)[-1] != 'records.jsonl':
                    continue
                target = self._target_path(base_dir, logical_path)
                checksum = item.get("sha256")
                existing = transfers.get(target)
                if existing and (not checksum or existing[1] != checksum):
                    raise ValueError(f"conflicting registered index files share a cache target: {logical_path}")
                transfers.setdefault(target, (target, checksum, signed.get(logical_path)))
                professional_files[logical_path] = target
        with ThreadPoolExecutor(max_workers=4) as pool:
            pending = [pool.submit(copy_context().run, self._cache_registered_file, spec)
                       for spec in transfers.values()]
            for transfer in pending:
                transfer.result()
        return professional_files

    def load(self, dataset_id: str, version_id: str, index_version_id: str):
        base_dir = Path(settings.local_cache_dir) / dataset_id / version_id / index_version_id
        base_dir.mkdir(parents=True, exist_ok=True)

        bundle = self.backend.resolve_index_bundle(dataset_id, version_id, index_version_id)
        manifest_path = self.backend.download_to(
            bundle["manifestUrl"], self._target_path(base_dir, bundle["manifestPath"])
        )
        # Prefer bundle URLs and keep original relative paths to avoid filename collisions.
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
        unified_index = None
        unified_metadata = None
        if bundle.get("unifiedIndexUrl") and bundle.get("unifiedMetadataUrl"):
            unified_index_file = self.backend.download_to(
                bundle["unifiedIndexUrl"], self._target_path(base_dir, bundle["unifiedIndexPath"])
            )
            unified_meta_file = self.backend.download_to(
                bundle["unifiedMetadataUrl"], self._target_path(base_dir, bundle["unifiedMetadataPath"])
            )
            unified_index = faiss.read_index(str(unified_index_file))
            unified_metadata = pd.read_parquet(unified_meta_file)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        professional_files = self._registered_files(base_dir,bundle,manifest)
        # Ordinary queries prepare only their requested semantic spaces.
        # The explicit publication endpoint still prepares all spaces through
        # IndexRegistry.ensure_preloaded, including on an existing cache hit.
        professional_cache = {}

        return {
            "bundle": bundle,
            "manifest": manifest,
            "image_index": faiss.read_index(str(image_index_file)),
            "text_index": faiss.read_index(str(text_index_file)),
            "image_metadata": pd.read_parquet(image_meta_file),
            "text_metadata": pd.read_parquet(text_meta_file),
            "unified_index": unified_index,
            "unified_metadata": unified_metadata,
            "professional_files": professional_files,
            "professional_cache": professional_cache,
        }
