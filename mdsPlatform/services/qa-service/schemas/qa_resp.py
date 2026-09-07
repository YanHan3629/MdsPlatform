from datetime import datetime
from typing import List, Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class SourceItem(BaseModel):
    """回答引用的来源（图片 / 文本片段 / 检索结果 / bundle 文本）。"""

    source_id: str
    source_type: str  # image / text_chunk / context / search_result / bundle_text
    relevance_score: float = 1.0
    content_preview: str = ""
    asset_id: Optional[UUID] = None
    logical_path: Optional[str] = None


class QAMeta(BaseModel):
    """调用元信息（模型名、置信度来源、token 用量、请求 ID）。"""

    model_config = ConfigDict(protected_namespaces=())

    model_name: str = ""
    confidence_source: str = "unavailable"
    image_count: int = 0
    text_count: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    request_id: Optional[str] = None
    retrieval_type: str = "none"
    intent_analysis_used: bool = False
    intent_confidence: float = 1.0
    intent_reason: str = ""


class RetrievedItem(BaseModel):
    """检索命中的内容项（供用户自行查看检索内容）。"""

    asset_id: Optional[UUID] = None
    logical_path: Optional[str] = None
    score: float = 0.0
    preview_url: Optional[str] = None
    source_type: str = "search_result"


class QAResponse(BaseModel):
    answer: str
    confidence: float
    sources: List[SourceItem]
    processing_time_ms: int
    timestamp: str = Field(default_factory=lambda: datetime.now().isoformat())
    model: str = ""
    meta: QAMeta = Field(default_factory=QAMeta)
    task_type: str = "general_qa"
    retrieval_mode: str = "none"
    backend: str = "vllm"
    notice: Optional[str] = None
    retrieved: List[RetrievedItem] = Field(default_factory=list)
