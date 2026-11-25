from datetime import datetime

from fastapi import APIRouter

from adapters.vllm_client import VllmClientError, client
from core.config import settings
from core.request_budget import ACTIVE_CONTEXT_TOKENS, CAPABILITIES, public_capabilities

router = APIRouter()


@router.get("/healthz")
def healthz():
    """与 search-service 平级的健康检查端点。"""
    return {"status": "ok", "service": settings.app_name}


@router.get("/health")
def health_check():
    """检查 QA 服务、vLLM 推理引擎和已加载的模型配置。"""
    vllm_status = "ok"
    vllm_error = None
    model_loaded = False
    try:
        client.check_engine_health()
        models = client.list_models()
        model_loaded = any(m.get("id") == settings.model_name and m.get("max_model_len") == ACTIVE_CONTEXT_TOKENS for m in models)
    except VllmClientError as e:
        vllm_status = "unreachable"
        vllm_error = str(e)
    return {
        "status": "ok",
        "service": settings.app_name,
        "vllm": {
            "status": vllm_status,
            "baseUrl": settings.vllm_base_url,
            "error": vllm_error,
        },
        "model": {
            "name": settings.model_name,
            "loaded": model_loaded,
            "quantization": CAPABILITIES["quantization"],
            "revision": CAPABILITIES["modelRevision"],
        },
        "capabilities": public_capabilities(),
        "timestamp": datetime.now().isoformat(),
    }


@router.get("/model")
def model_info():
    """查看 vLLM 实际加载的模型列表。"""
    try:
        models = client.list_models()
        return {"configuredModel": settings.model_name, "models": models}
    except VllmClientError as e:
        return {"configuredModel": settings.model_name, "models": [], "error": str(e)}
