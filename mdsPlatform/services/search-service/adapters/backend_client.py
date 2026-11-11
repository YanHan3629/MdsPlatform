from core.worker_authority import current_guard, require_scope, download_atomic, AuthorityError
from pathlib import Path
import requests

from core.config import settings


class BackendClient:
    def __init__(self):
        self.base_url = settings.backend_base_url.rstrip("/")
        self.session = requests.Session()
        self.timeout = settings.request_timeout_seconds

    @property
    def backend_headers(self):return current_guard().headers

    def resolve_index_bundle(self, dataset_id: str, version_id: str, index_version_id: str) -> dict:
        require_scope(dataset_id,version_id,index_version_id)
        url = self.base_url + current_guard().bundle_path(dataset_id,version_id,index_version_id)
        resp = self.session.get(url, timeout=self.timeout, headers=self.backend_headers or None, allow_redirects=False)
        if resp.status_code!=200:raise AuthorityError(resp.status_code if resp.status_code in {401,403,404,409,503} else 502,"WORKER_FROZEN_BUNDLE_UNAVAILABLE")
        return resp.json()

    def download_to(self, presigned_url: str, target_path: Path) -> Path:
        return download_atomic(presigned_url,target_path,self.timeout)
