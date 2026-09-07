from typing import List, Optional
from uuid import UUID

from pydantic import AliasChoices, BaseModel, Field


class QaRequest(BaseModel):
    """统一问答请求（JSON 请求体；multipart 表单字段同名，texts 为 JSON 数组字符串）。

    同时兼容 camelCase 与 snake_case 字段名，业务通道统一为 POST /api/v1/qa。
    """

    question: str = Field(min_length=1, max_length=8000)
    input_mode: str = Field(
        default="hybrid",
        validation_alias=AliasChoices("input_mode", "inputMode"),
        pattern="^(question_only|hybrid|user_data_only)$",
        description="question_only=只输入问题其余由数据空间检索；hybrid=输入数据+检索并用；user_data_only=仅用输入数据",
    )
    task_type: str = Field(
        default="general_qa",
        validation_alias=AliasChoices("task_type", "taskType"),
        pattern="^(general_qa|chain_risk_analysis|demand_evaluation|data_summary)$",
    )
    dataset_id: Optional[UUID] = Field(
        default=None, validation_alias=AliasChoices("dataset_id", "datasetId")
    )
    version_id: Optional[UUID] = Field(
        default=None, validation_alias=AliasChoices("version_id", "versionId")
    )
    index_version_id: Optional[UUID] = Field(
        default=None, validation_alias=AliasChoices("index_version_id", "indexVersionId")
    )
    texts: Optional[List[str]] = None
    context: Optional[str] = None
    use_search_service: bool = Field(
        default=True,
        validation_alias=AliasChoices("use_search_service", "useSearchService"),
    )
    retrieval_mode: str = Field(
        default="auto",
        validation_alias=AliasChoices("retrieval_mode", "retrievalMode"),
        pattern="^(auto|search_service|bundle|none)$",
    )
    retrieval_type: str = Field(
        default="auto",
        validation_alias=AliasChoices("retrieval_type", "retrievalType"),
        pattern="^(auto|none|text_to_image|image_to_text|dual)$",
        description="检索类型；auto 时仅在规则无法判断的图片请求中调用意图模型",
    )
    top_k: int = Field(
        default=8, ge=1, le=50, validation_alias=AliasChoices("top_k", "topK")
    )
    max_tokens: Optional[int] = Field(
        default=None, ge=1, le=32768, validation_alias=AliasChoices("max_tokens", "maxTokens")
    )
    temperature: Optional[float] = Field(default=None, ge=0.0, le=2.0)
