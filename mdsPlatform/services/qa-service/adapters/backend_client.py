from core.worker_authority import current_guard, require_scope, download_atomic
from pathlib import Path

import requests

from core.config import settings


class BackendClient:
    """数据空间后端内部接口客户端（与 search-service 使用完全相同的 bundle 格式）。"""

    def __init__(self):
        self.base_url = settings.backend_base_url.rstrip("/")
        self.session = requests.Session()
        self.timeout = settings.request_timeout_seconds

    @property
    def backend_headers(self):return current_guard().headers

    def resolve_index_bundle(self, dataset_id: str, version_id: str, index_version_id: str) -> dict:
        require_scope(dataset_id,version_id,index_version_id)
        url = (
            f"{self.base_url}/api/mm/internal/datasets/{dataset_id}"
            f"/versions/{version_id}/indexes/{index_version_id}/bundle"
        )
        resp = self.session.get(url, timeout=self.timeout, headers=self.backend_headers or None)
        resp.raise_for_status()
        return resp.json()

    def download_to(self, presigned_url: str, target_path: Path) -> Path:
        return download_atomic(presigned_url,target_path,self.timeout)
