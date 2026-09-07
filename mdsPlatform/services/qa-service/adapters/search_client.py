import requests

from core.config import settings


class SearchServiceClient:
    """检索客户端：
    1) backend_multimodal_search - 调用数据空间后端多模态搜索接口（返回图片预览 + 描述）；
    2) text_to_image - 调用平级 search-service 内部文搜图接口（仅文本，接口格式一致）。
    """

    def __init__(self):
        self.backend_base_url = settings.backend_base_url.rstrip("/")
        self.backend_headers = {}
        if settings.backend_bearer_token:
            self.backend_headers["Authorization"] = f"Bearer {settings.backend_bearer_token}"
        self.base_url = settings.search_service_base_url.rstrip("/")
        self.timeout = settings.request_timeout_seconds

    def backend_multimodal_search(self, dataset_id, version_id, index_version_id, query: str, top_k: int = 8) -> list:
        """调用后端 /api/mm/search/text-to-image（多模态：返回 previewUrl 图片 + captions/tags/categories）。

        需配置 BACKEND_BEARER_TOKEN；返回条目结构与 MmTextToImageResp 一致。
        """
        url = f"{self.backend_base_url}/api/mm/search/text-to-image"
        payload = {
            "datasetId": str(dataset_id),
            "versionId": str(version_id),
            "query": query,
            "topK": top_k,
        }
        resp = requests.post(url, json=payload, headers=self.backend_headers or None, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json().get("items", []) or []

    def download_preview(self, url: str) -> bytes:
        resp = requests.get(url, timeout=30)
        resp.raise_for_status()
        return resp.content

    def text_to_image(self, dataset_id, version_id, index_version_id, query: str, top_k: int = 8) -> list:
        url = f"{self.base_url}/internal/v1/search/text-to-image"
        payload = {
            "datasetId": str(dataset_id),
            "versionId": str(version_id),
            "indexVersionId": str(index_version_id),
            "query": query,
            "topK": top_k,
        }
        resp = requests.post(url, json=payload, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json().get("items", []) or []

    def image_to_text(self, dataset_id, version_id, index_version_id, image_bytes, top_k: int = 8) -> list:
        url = f"{self.base_url}/internal/v1/search/image-to-text"
        files = {"file": ("query.jpg", image_bytes, "image/jpeg")}
        data = {
            "datasetId": str(dataset_id),
            "versionId": str(version_id),
            "indexVersionId": str(index_version_id),
            "topK": top_k,
        }
        resp = requests.post(url, data=data, files=files, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json().get("items", []) or []
