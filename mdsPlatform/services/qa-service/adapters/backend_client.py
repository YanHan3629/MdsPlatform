from pathlib import Path

import requests

from core.config import settings


class BackendClient:
    """数据空间后端内部接口客户端（与 search-service 使用完全相同的 bundle 格式）。"""

    def __init__(self):
        self.base_url = settings.backend_base_url.rstrip("/")
        self.session = requests.Session()
        self.backend_headers = {}
        if settings.backend_bearer_token:
            self.backend_headers["Authorization"] = f"Bearer {settings.backend_bearer_token}"
        self.timeout = settings.request_timeout_seconds

    def resolve_index_bundle(self, dataset_id: str, version_id: str, index_version_id: str) -> dict:
        url = (
            f"{self.base_url}/api/mm/internal/datasets/{dataset_id}"
            f"/versions/{version_id}/indexes/{index_version_id}/bundle"
        )
        resp = self.session.get(url, timeout=self.timeout, headers=self.backend_headers or None)
        resp.raise_for_status()
        return resp.json()

    def download_to(self, presigned_url: str, target_path: Path) -> Path:
        target_path.parent.mkdir(parents=True, exist_ok=True)
        # 预签名 URL 不应携带后端 Authorization 头
        with requests.get(presigned_url, timeout=self.timeout, stream=True) as resp:
            resp.raise_for_status()
            with open(target_path, "wb") as f:
                for chunk in resp.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        f.write(chunk)
        return target_path
