import hashlib
import json
from pathlib import Path

import faiss
import pandas as pd

from adapters.backend_client import BackendClient
from core.config import settings
from services.professional_retrievers import ProfessionalRetrieverSuite


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
        professional_files = {}
        signed = {item.get("path"): item.get("url") for item in bundle.get("indexFiles", [])}
        for registration in manifest.get("indexes", []):
            entries = registration.get("files", [])
            registered_checksum = registration.get("contentChecksum")
            if registered_checksum and hashlib.sha256("".join(item.get("sha256", "") for item in entries).encode()).hexdigest() != registered_checksum:
                raise ValueError(f"index registration checksum mismatch: {registration.get('semanticSpace')}")
            for item in entries:
                logical_path = item.get("path")
                if not logical_path or logical_path in professional_files:
                    continue
                target = self._target_path(base_dir, logical_path)
                if target.exists():
                    try:
                        self._verify_file(target, item.get("sha256"))
                        professional_files[logical_path] = target
                        continue
                    except ValueError:
                        target.unlink()
                url = signed.get(logical_path)
                if not url:
                    raise ValueError(f"registered index file has no signed download URL: {logical_path}")
                self.backend.download_to(url, target)
                self._verify_file(target, item.get("sha256"))
                professional_files[logical_path] = target
        # Preload parsed professional indexes as part of the publication gate.
        # The owning LoadedIndex releases this cache when LRU eviction removes
        # the snapshot, so professional objects obey the same memory budget.
        professional_cache = {}
        ProfessionalRetrieverSuite(
            manifest.get("indexes", []), professional_files, cache=professional_cache
        ).preload()

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
