import json
from typing import List

from fastapi import APIRouter, HTTPException, Request, UploadFile
from starlette.concurrency import run_in_threadpool

from adapters.vllm_client import VllmClientError
from core.config import settings
from schemas.qa_req import QaRequest
from schemas.qa_resp import QAResponse
from services.qa_service import QaService, service

router = APIRouter()


def _handle_errors(exc: Exception) -> HTTPException:
    if isinstance(exc, ValueError):
        return HTTPException(status_code=400, detail=str(exc))
    if isinstance(exc, VllmClientError):
        return HTTPException(status_code=502, detail=str(exc))
    return HTTPException(status_code=500, detail=str(exc))


def _parse_texts(raw) -> List[str]:
    """兼容 multipart 的 JSON 数组字符串与 JSON 的 list。"""
    if raw is None:
        return []
    if isinstance(raw, list):
        return [str(x) for x in raw]
    text = str(raw).strip()
    if not text:
        return []
    try:
        parsed = json.loads(text)
        if isinstance(parsed, list):
            return [str(x) for x in parsed]
        return [str(parsed)]
    except Exception:
        return [text]


@router.post("/qa", response_model=QAResponse)
async def ask_question(request: Request):
    """统一问答入口（业务通道唯一）：JSON 或 multipart（含图片）。

    JSON 请求体与 multipart 表单字段一致，camelCase / snake_case 均可：
    question、input_mode(inputMode)、task_type(taskType)、
    dataset_id(datasetId)、version_id(versionId)、index_version_id(indexVersionId)、
    texts（JSON 数组字符串）、context、use_search_service(useSearchService)、
    retrieval_mode(retrievalMode)、retrieval_type(retrievalType)、
    top_k(topK)、max_tokens(maxTokens)、temperature、
    images（仅 multipart）。
    """
    content_type = request.headers.get("content-type", "").lower()
    authorization = request.headers.get("authorization", "")
    # Keep credentials local to the request; concurrent users must never share a token.
    token = authorization[7:].strip() if authorization.lower().startswith("bearer ") else None
    request_service = QaService(client=service.client, bearer_token=token)
    try:
        if "application/json" in content_type:
            req = QaRequest.model_validate(await request.json())
            return await run_in_threadpool(
                request_service.answer,
                question=req.question,
                input_mode=req.input_mode,
                task_type=req.task_type,
                dataset_id=req.dataset_id,
                version_id=req.version_id,
                index_version_id=req.index_version_id,
                texts=req.texts,
                context=req.context,
                images=[],
                use_search_service=req.use_search_service,
                retrieval_mode=req.retrieval_mode,
                retrieval_type=req.retrieval_type,
                top_k=req.top_k,
                max_tokens=req.max_tokens,
                temperature=req.temperature,
            )

        form = await request.form()
        question = str(form.get("question") or "").strip()
        if not question:
            raise ValueError("question 不能为空")
        images: List[UploadFile] = form.getlist("images") if "images" in form else []
        image_limit = min(settings.max_upload_images, settings.max_prompt_images)
        if len(images) > image_limit:
            for img in images:
                await img.close()
            raise HTTPException(status_code=400, detail=f"单次最多上传 {image_limit} 张图片")
        image_payloads = []
        for img in images:
            content = await img.read()
            if content:
                image_payloads.append((img.filename or "upload.jpg", content))

        payload = {
            "question": question,
            "inputMode": form.get("inputMode") or form.get("input_mode") or "hybrid",
            "taskType": form.get("taskType") or form.get("task_type") or "general_qa",
            "datasetId": form.get("datasetId") or form.get("dataset_id") or None,
            "versionId": form.get("versionId") or form.get("version_id") or None,
            "indexVersionId": form.get("indexVersionId") or form.get("index_version_id") or None,
            "texts": _parse_texts(form.get("texts")),
            "context": form.get("context") or None,
            "useSearchService": form.get("useSearchService") or form.get("use_search_service") or True,
            "retrievalMode": form.get("retrievalMode") or form.get("retrieval_mode") or "auto",
            "retrievalType": form.get("retrievalType") or form.get("retrieval_type") or "auto",
            "topK": form.get("topK") or form.get("top_k") or 8,
            "maxTokens": form.get("maxTokens") or form.get("max_tokens") or None,
            "temperature": form.get("temperature") or None,
        }
        req = QaRequest.model_validate(payload)
        return await run_in_threadpool(
            request_service.answer,
            question=req.question,
            input_mode=req.input_mode,
            task_type=req.task_type,
            dataset_id=req.dataset_id,
            version_id=req.version_id,
            index_version_id=req.index_version_id,
            texts=req.texts,
            context=req.context,
            images=image_payloads,
            use_search_service=req.use_search_service,
            retrieval_mode=req.retrieval_mode,
            retrieval_type=req.retrieval_type,
            top_k=req.top_k,
            max_tokens=req.max_tokens,
            temperature=req.temperature,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise _handle_errors(exc) from exc
