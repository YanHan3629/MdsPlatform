import requests

from core.config import settings


class SearchServiceClient:
    """检索客户端：
    1) backend_multimodal_search - 调用数据空间后端多模态搜索接口（返回图片预览 + 描述）；
    2) text_to_image - 调用平级 search-service 内部文搜图接口（仅文本，接口格式一致）。
    """

    def __init__(self, bearer_token=None):
        self.backend_base_url = settings.backend_base_url.rstrip("/")
        self.backend_headers = {}
        token = bearer_token or ""
        if token:
            self.backend_headers["Authorization"] = f"Bearer {token}"
        self.base_url = settings.search_service_base_url.rstrip("/")
        self.search_headers = {"X-MM-Internal-Token": settings.mm_internal_token}
        self.timeout = settings.request_timeout_seconds

    def backend_multimodal_search(self, dataset_id, version_id, index_version_id, query: str, top_k: int = 8) -> list:
        """调用后端 /api/mm/search/text-to-image（多模态：返回 previewUrl 图片 + captions/tags/categories）。

        需配置 BACKEND_BEARER_TOKEN；返回条目结构与 MmTextToImageResp 一致。
        """
        url = f"{self.backend_base_url}{settings.backend_search_path}"
        payload = {
            "datasetId": str(dataset_id),
            "versionId": str(version_id),
            "indexVersionId": str(index_version_id),
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

    def download_asset_image(self, dataset_id, version_id, asset_id, preview=None, *, index_version_id=None) -> bytes | None:
        """Use the scoped authenticated gateway, never arbitrary evidence URLs."""
        from io import BytesIO
        from PIL import Image
        from urllib.parse import quote
        url = f"{self.backend_base_url}/api/data-space/qa/assets/{dataset_id}/{version_id}/{asset_id}"
        params = None
        if preview and (preview.get("derived") or preview.get("artifactPath")):
            representation=preview.get('representationId')
            if not index_version_id or not isinstance(representation,str) or not representation or len(representation)>200 or '/' in representation or '\\' in representation:
                raise ValueError('EVIDENCE_ID_MISSING: derived image requires a frozen index and representation identity')
            url=(f"{self.backend_base_url}/api/data-space/qa/evidence/{dataset_id}/{version_id}/"
                 f"{index_version_id}/{asset_id}/{quote(representation,safe='')}")
        with requests.get(url, params=params, headers=self.backend_headers, timeout=30, stream=True) as response:
            response.raise_for_status()
            content = bytearray()
            for block in response.iter_content(65536):
                content.extend(block)
                if len(content) > 10 * 1024 * 1024:
                    raise ValueError("preview exceeds 10 MiB")
        with Image.open(BytesIO(content)) as image:
            image.thumbnail((512, 512))
            output = BytesIO()
            image.convert("RGB").save(output, format="PNG")
            return output.getvalue()

    def text_to_image(self, dataset_id, version_id, index_version_id, query: str, top_k: int = 8) -> list:
        url = f"{self.base_url}/internal/v1/search/text-to-image"
        payload = {
            "datasetId": str(dataset_id),
            "versionId": str(version_id),
            "indexVersionId": str(index_version_id),
            "query": query,
            "topK": top_k,
        }
        resp = requests.post(url, json=payload, headers=self.search_headers, timeout=self.timeout)
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
        resp = requests.post(url, data=data, files=files, headers=self.search_headers, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json().get("items", []) or []

    def professional_search(self, dataset_id, version_id, index_version_id, plan: dict) -> dict:
        url = f"{self.base_url}/internal/v1/search/execute"
        payload = {
            "datasetId": str(dataset_id),
            "versionId": str(version_id),
            "indexVersionId": str(index_version_id),
            "plan": plan,
        }
        resp = requests.post(url, json=payload, headers=self.search_headers, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def capabilities(self, dataset_id, version_id, index_version_id):
        # The public QA port must also enforce organisation scope before using
        # an internal service credential. No cross-user capability cache.
        response = requests.get(f"{self.backend_base_url}/api/data-space/qa/capabilities/{dataset_id}/{version_id}/{index_version_id}",
            headers=self.backend_headers, timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def check_scope(self,dataset_id,version_id,index_version_id):
        response=requests.get(f"{self.backend_base_url}/api/data-space/qa/access/{dataset_id}/{version_id}/{index_version_id}",
            headers=self.backend_headers,timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def evidence_metadata(self,dataset_id,version_id,index_version_id,asset_id,*,timeout=None):
        response=requests.get(f"{self.backend_base_url}/api/data-space/qa/evidence/{dataset_id}/{version_id}/{index_version_id}/{asset_id}",
            headers=self.backend_headers,timeout=min(self.timeout,timeout) if timeout is not None else self.timeout)
        response.raise_for_status()
        return response.json()

    def read_evidence(self,dataset_id,version_id,index_version_id,asset_id,operation,*,timeout=None):
        from services.read_receipt import attest
        response=requests.post(f"{self.backend_base_url}/api/data-space/qa/evidence/{dataset_id}/{version_id}/{index_version_id}/{asset_id}/read",
            headers=self.backend_headers,json=operation,timeout=min(self.timeout,timeout) if timeout is not None else self.timeout)
        response.raise_for_status()
        return attest(response.json(),response.content,(dataset_id,version_id,index_version_id),asset_id,operation)

    def download_registered_image(self,dataset_id,version_id,index_version_id,asset_id,view,metadata):
        """Read unchanged registered render bytes; preprocessing is traced later."""
        import hashlib
        from urllib.parse import quote
        from io import BytesIO
        from PIL import Image
        expected={'datasetId':dataset_id,'versionId':version_id,'indexVersionId':index_version_id,'assetId':asset_id}
        if any(str(metadata.get(key))!=str(value) for key,value in expected.items()):raise ValueError('EVIDENCE_IMAGE_SCOPE_MISMATCH')
        identifier=view.get('legacyRepresentationId','')
        descriptors=[row for row in metadata['representations'] if row['representationId']==identifier]
        if len(descriptors)!=1 or descriptors[0].get('outputSha256')!=view.get('outputSha256') or descriptors[0].get('sizeBytes')!=view.get('outputBytes'):
            raise ValueError('EVIDENCE_REGISTERED_IMAGE_MISMATCH')
        if not identifier or len(identifier)>200 or '/' in identifier or '\\' in identifier:raise ValueError('EVIDENCE_IMAGE_ID_INVALID')
        url=f'{self.backend_base_url}/api/data-space/qa/evidence/{dataset_id}/{version_id}/{index_version_id}/{asset_id}/{quote(identifier,safe="")}'
        with requests.get(url,headers=self.backend_headers,timeout=45,stream=True) as response:
            response.raise_for_status();raw=bytearray()
            for block in response.iter_content(65536):
                raw.extend(block)
                if len(raw)>16*1024*1024:raise ValueError('EVIDENCE_IMAGE_BYTE_LIMIT')
        raw=bytes(raw)
        if len(raw)!=view['outputBytes'] or hashlib.sha256(raw).hexdigest()!=view['outputSha256']:raise ValueError('EVIDENCE_IMAGE_HASH_MISMATCH')
        with Image.open(BytesIO(raw)) as image:
            if image.format!='PNG' or image.size!=(view['width'],view['height']):raise ValueError('EVIDENCE_IMAGE_DIMENSION_MISMATCH')
        return raw

    def render_asset_region(self,dataset_id,version_id,index_version_id,asset_id,parameters,*,source_sha256,_operation='render_region_image'):
        import base64,hashlib,json
        from io import BytesIO
        from PIL import Image
        url=f"{self.backend_base_url}/api/data-space/qa/evidence/{dataset_id}/{version_id}/{index_version_id}/{asset_id}/render"
        if _operation not in {'render_region_image','render_engineering_image'}:raise ValueError('EVIDENCE_RENDER_OPERATION_UNSUPPORTED')
        with requests.post(url,headers=self.backend_headers,json={'operation':_operation,**parameters},timeout=100,stream=True) as response:
            response.raise_for_status();header=response.headers.get('X-Evidence-Read','')
            if not header or len(header)>32768:raise ValueError('EVIDENCE_RENDER_METADATA_INVALID')
            try:metadata=json.loads(base64.b64decode(header,validate=True))
            except (ValueError,TypeError):raise ValueError('EVIDENCE_RENDER_METADATA_INVALID')
            identity=metadata.get('identity',{})
            for key,value in [('datasetId',dataset_id),('versionId',version_id),('indexVersionId',index_version_id),('assetId',asset_id)]:
                if identity.get(key)!=str(value):raise ValueError('EVIDENCE_RENDER_SNAPSHOT_MISMATCH')
            if identity.get('sourceSha256')!=source_sha256:raise ValueError('EVIDENCE_RENDER_SOURCE_MISMATCH')
            raw=bytearray()
            for block in response.iter_content(65536):
                raw.extend(block)
                if len(raw)>16*1024*1024:raise ValueError('EVIDENCE_RENDER_BYTE_LIMIT')
        raw=bytes(raw)
        if hashlib.sha256(raw).hexdigest()!=metadata.get('outputSha256') or len(raw)!=metadata.get('outputBytes'):
            raise ValueError('EVIDENCE_RENDER_HASH_MISMATCH')
        with Image.open(BytesIO(raw)) as image:
            if image.format!='PNG' or [image.width,image.height]!=[metadata.get('width'),metadata.get('height')]:raise ValueError('EVIDENCE_RENDER_DIMENSION_MISMATCH')
        from services.evidence_trace import current
        if current():current().event('regionRender',metadata)
        return raw,metadata

    def render_engineering_region(self,dataset_id,version_id,index_version_id,asset_id,parameters,*,source_sha256):
        return self.render_asset_region(dataset_id,version_id,index_version_id,asset_id,parameters,
            source_sha256=source_sha256,_operation='render_engineering_image')

    def candidates(self, dataset_id, version_id, index_version_id, question, formats=()):
        response = requests.post(f"{self.base_url}/internal/v1/search/candidates", headers=self.search_headers,
            json={"datasetId":str(dataset_id),"versionId":str(version_id),"indexVersionId":str(index_version_id),
                  "question":question,"formats":list(formats),"per_format":8}, timeout=self.timeout)
        response.raise_for_status()
        return response.json()

    def image_candidates(self,dataset_id,version_id,index_version_id,image,formats=()):
        import json
        plan={"taskIntent":"qa_image_to_text","retrievers":["text"],"contentType":"text","operation":"search","formats":list(formats),"topK":16}
        response=requests.post(f"{self.base_url}/internal/v1/search/image-candidates",headers=self.search_headers,
            data={"datasetId":str(dataset_id),"versionId":str(version_id),"indexVersionId":str(index_version_id),"plan":json.dumps(plan)},
            files={"file":("query.png",image,"image/png")},timeout=self.timeout)
        response.raise_for_status()
        return response.json()
