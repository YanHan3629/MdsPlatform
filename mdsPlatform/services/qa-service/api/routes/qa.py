import json
from typing import List

from fastapi import APIRouter, HTTPException, Request
from starlette.datastructures import UploadFile
from starlette.concurrency import run_in_threadpool

from adapters.vllm_client import VllmClientError
from core.config import settings
from schemas.qa_req import QaRequest
from schemas.qa_resp import QAResponse
from services.qa_service import QaService, service

router = APIRouter()


def _handle_errors(exc: Exception) -> HTTPException:
    import requests
    from services.evidence_trace import TraceStorageError
    if isinstance(exc, TraceStorageError):
        return HTTPException(status_code=503, detail="EVIDENCE_TRACE_UNAVAILABLE: 本次证据记录未能保存，暂不能交付回答，请稍后重试")
    if isinstance(exc, requests.RequestException):
        status = exc.response.status_code if exc.response is not None else 503
        if status in {401,403}:
            return HTTPException(status_code=status, detail="账号或数据范围授权失效")
        if status == 409:
            return HTTPException(status_code=409, detail="INDEX_NOT_READY: 当前索引尚未具备所需能力")
        if status == 404:
            return HTTPException(status_code=404, detail="SOURCE_OR_EVIDENCE_NOT_FOUND: 冻结来源或所选字段不存在")
        if status == 413:
            return HTTPException(status_code=413, detail="EVIDENCE_READ_BUDGET_EXCEEDED: 原生读取内容超限，请缩小行列或子树")
        if status == 422:
            return HTTPException(status_code=422, detail="EVIDENCE_OPERATION_UNSUPPORTED: 所选读取或源值状态不支持当前操作")
        if status == 400:
            return HTTPException(status_code=400, detail="EVIDENCE_SELECTOR_INVALID: 读取范围或字段参数无效")
        return HTTPException(status_code=503, detail="RETRIEVAL_SERVICE_UNAVAILABLE: 检索服务异常，请稍后重试")
    if isinstance(exc, ValueError):
        return HTTPException(status_code=400, detail=str(exc))
    if isinstance(exc, VllmClientError):
        public={
            "unavailable":(503,"MODEL_SERVICE_UNAVAILABLE: 模型服务暂不可用，请稍后重试。"),
            "timeout":(504,"MODEL_SERVICE_TIMEOUT: 模型处理超时，请稍后重试。"),
            "busy":(503,"MODEL_SERVICE_BUSY: 模型服务繁忙，请稍后重试。"),
            "configuration":(503,"MODEL_SERVICE_CONFIGURATION_ERROR: 模型服务配置暂不可用，请联系管理员。"),
            "request_rejected":(502,"MODEL_REQUEST_REJECTED: 模型未接受本次请求，请调整资料范围后重试。"),
            "invalid_response":(502,"MODEL_RESPONSE_INVALID: 模型服务返回了无效响应，请稍后重试。"),
        }
        status,detail=public.get(getattr(exc,"category",None),public["unavailable"])
        return HTTPException(status_code=status,detail=detail)
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
                query_plan=req.query_plan.model_dump(by_alias=True) if req.query_plan else None,
                top_k=req.top_k,
                max_tokens=req.max_tokens,
                temperature=req.temperature,
            )

        form = await request.form()
        question = str(form.get("question") or "").strip()
        images = form.getlist("images") if "images" in form else []
        image_payloads = []
        try:
            if not question:
                raise ValueError("question 不能为空")
            image_limit = min(settings.max_upload_images, settings.max_prompt_images)
            if len(images) > image_limit:
                raise HTTPException(status_code=400, detail=f"单次最多上传 {image_limit} 张图片")
            if any(not isinstance(img, UploadFile) for img in images):
                raise HTTPException(status_code=400, detail="USER_IMAGE_FILE_REQUIRED: images 必须是图片文件")
            for img in images:
                # Reject a missing attachment before retrieval or generation;
                # a silent omission would answer a different question.
                content = await img.read(10 * 1024 * 1024 + 1)
                if not content:
                    raise HTTPException(status_code=400, detail="USER_IMAGE_EMPTY: 图片附件为空，请重新选择有效图片")
                if len(content) > 10 * 1024 * 1024:
                    raise HTTPException(status_code=413, detail="USER_IMAGE_BYTE_LIMIT: 每张图片不得超过10 MB")
                image_payloads.append((img.filename or "upload.jpg", content))
        finally:
            await form.close()

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
            "queryPlan": json.loads(str(form.get("queryPlan") or form.get("query_plan") or "null")),
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
            query_plan=req.query_plan.model_dump(by_alias=True) if req.query_plan else None,
            top_k=req.top_k,
            max_tokens=req.max_tokens,
            temperature=req.temperature,
        )
    except HTTPException:
        raise
    except Exception as exc:
        raise _handle_errors(exc) from exc
