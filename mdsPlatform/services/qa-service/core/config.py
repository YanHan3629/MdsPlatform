import os
from dataclasses import dataclass


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return float(raw)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    """多模态问答服务配置（参考 Multimodel-DataSpace-Platform 的 vLLM 部署方式）。"""

    app_name: str = os.environ.get("APP_NAME", "multimodal-qa-service")

    # vLLM OpenAI 兼容服务地址（vllm serve 默认 http://localhost:8000）
    vllm_base_url: str = os.environ.get("VLLM_BASE_URL", "http://localhost:8000")
    # 请求 vLLM 时使用的模型名，需与 `vllm serve --served-model-name` 保持一致
    model_name: str = os.environ.get("MODEL_NAME", "Qwen/Qwen3.5-0.8B")
    api_key: str = os.environ.get("VLLM_API_KEY", "EMPTY")

    request_timeout_seconds: int = _env_int("REQUEST_TIMEOUT_SECONDS", 300)

    # 默认采样参数（参考 Qwen3.5 非思考模式 VL 任务推荐值）
    default_max_tokens: int = _env_int("DEFAULT_MAX_TOKENS", 2048)
    default_temperature: float = _env_float("DEFAULT_TEMPERATURE", 0.7)
    default_top_p: float = _env_float("DEFAULT_TOP_P", 0.8)
    default_top_k: int = _env_int("DEFAULT_TOP_K", 20)
    default_presence_penalty: float = _env_float("DEFAULT_PRESENCE_PENALTY", 1.5)

    # 单次请求限制
    # RTX 3050 4GB 的保守默认值：单次最多两张图，每条文本依据最多 500 字符。
    max_upload_images: int = _env_int("MAX_UPLOAD_IMAGES", 2)
    max_text_chars: int = _env_int("MAX_TEXT_CHARS", 500)
    # 检索回图片时，最多下载/入提示的图片数量
    max_retrieved_images: int = _env_int("MAX_RETRIEVED_IMAGES", 3)
    max_prompt_images: int = _env_int("MAX_PROMPT_IMAGES", 2)
    max_image_pixels: int = _env_int("MAX_IMAGE_PIXELS", 262144)

    # LLM 后端：vllm（默认）| mock（降级，用于无 vLLM 时的连通性测试）
    llm_backend: str = os.environ.get("LLM_BACKEND", "vllm")

    # 数据空间后端（索引 bundle 来源，格式与 search-service 一致）
    backend_base_url: str = os.environ.get("BACKEND_BASE_URL", "http://localhost:8888")
    backend_bearer_token: str = os.environ.get("BACKEND_BEARER_TOKEN", "")
    backend_search_path: str = os.environ.get("BACKEND_SEARCH_PATH", "/api/mm/search/text-to-image")
    search_service_base_url: str = os.environ.get("SEARCH_SERVICE_BASE_URL", "http://localhost:18080")
    local_cache_dir: str = os.environ.get("LOCAL_CACHE_DIR", "/tmp/qa-cache")


settings = Settings()
