from typing import Any, List, Literal, Optional
from datetime import datetime
from decimal import Decimal
from uuid import UUID

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, model_validator


class HardCondition(BaseModel):
    model_config = ConfigDict(extra="forbid")
    field: str = Field(min_length=1, max_length=128)
    op: Literal["eq", "in", "gt", "gte", "lt", "lte", "between", "is_null", "not_null", "contains"]
    value: Any
    unit: Optional[str] = None

    @model_validator(mode="after")
    def validate_value(self):
        if self.op == "in" and (not isinstance(self.value, list) or not 1 <= len(self.value) <= 100):
            raise ValueError("in requires a non-empty bounded list")
        if self.op == "between" and (not isinstance(self.value, list) or len(self.value) != 2):
            raise ValueError("between requires exactly two bounds")
        return self


class TimeRange(BaseModel):
    model_config = ConfigDict(extra="forbid")
    start: str
    end: str
    boundary: Literal["[start,end)", "[start,end]"] = "[start,end)"

    @model_validator(mode="after")
    def validate_boundaries(self):
        try:
            start = datetime.fromisoformat(self.start.strip().replace("Z", "+00:00"))
            end = datetime.fromisoformat(self.end.strip().replace("Z", "+00:00"))
        except (TypeError, ValueError) as exc:
            raise ValueError("timeRange boundaries must be ISO-8601 timestamps") from exc
        if start.tzinfo is None or end.tzinfo is None or start.utcoffset() is None or end.utcoffset() is None:
            raise ValueError("timeRange boundaries require an explicit timezone")
        if end <= start:
            raise ValueError("timeRange end must be later than start")
        return self


class ProfessionalQueryPlan(BaseModel):
    model_config = ConfigDict(extra="forbid")
    task_intent: str = Field(validation_alias=AliasChoices("task_intent", "taskIntent"), min_length=1, max_length=128)
    retrievers: list[Literal["text", "visual", "structured", "timeseries", "geometry"]] = Field(min_length=1, max_length=5)
    query_text: str = Field(default="", validation_alias=AliasChoices("query_text", "queryText"), max_length=8000)
    content_type: Optional[Literal["all", "text", "structured", "visual", "timeseries", "geometry"]] = Field(default=None, validation_alias=AliasChoices("content_type", "contentType"))
    formats: list[str] = Field(default_factory=list, max_length=20)
    asset_ids: list[str] = Field(default_factory=list, validation_alias=AliasChoices("asset_ids", "assetIds"), max_length=500)
    table_id: Optional[str] = Field(default=None, validation_alias=AliasChoices("table_id", "tableId"))
    corpus_scope: Literal["corpus", "catalog"] = Field(default="corpus", validation_alias=AliasChoices("corpus_scope", "corpusScope"))
    object_conditions: list[HardCondition] = Field(default_factory=list, validation_alias=AliasChoices("object_conditions", "objectConditions"), max_length=16)
    target_entity: Optional[str] = Field(default=None, validation_alias=AliasChoices("target_entity", "targetEntity"))
    hard_conditions: list[HardCondition] = Field(default_factory=list, validation_alias=AliasChoices("hard_conditions", "hardConditions"), max_length=32)
    time_range: Optional[TimeRange] = Field(default=None, validation_alias=AliasChoices("time_range", "timeRange"))
    compare_time_range: Optional[TimeRange] = Field(default=None, validation_alias=AliasChoices("compare_time_range", "compareTimeRange"))
    metric: Optional[str] = None
    count_mode: Optional[Literal['records','numeric_samples']] = Field(default=None, validation_alias=AliasChoices('count_mode','countMode'))
    unit: Optional[str] = None
    aggregate_field: Optional[str] = Field(default=None, validation_alias=AliasChoices("aggregate_field", "aggregateField"), max_length=128)
    sort_field: Optional[str] = Field(default=None, validation_alias=AliasChoices("sort_field", "sortField"), max_length=128)
    sort_descending: bool = Field(default=False, validation_alias=AliasChoices("sort_descending", "sortDescending"))
    operation: Literal[
        "search", "filter", "sort", "count", "sum", "min", "max", "average", "time_weighted_average",
        "duration_above", "compare_periods", "trend_start_end", "trend_regression", "trend_monotonic", "similar_shape"
    ] = "search"
    retriever_operations: dict[str, Literal[
        "search", "filter", "sort", "count", "sum", "min", "max", "average", "time_weighted_average",
        "duration_above", "compare_periods", "trend_start_end", "trend_regression", "trend_monotonic", "similar_shape"
    ]] = Field(default_factory=dict, validation_alias=AliasChoices("retriever_operations", "retrieverOperations"))
    threshold: Optional[float] = Field(default=None, allow_inf_nan=False)
    threshold_expression: Optional[str] = Field(default=None, validation_alias=AliasChoices("threshold_expression", "thresholdExpression"),
        max_length=4096, pattern=r"^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")
    query_series: Optional[list[float]] = Field(default=None, validation_alias=AliasChoices("query_series", "querySeries"), max_length=4096)
    query_asset_id: Optional[str] = Field(default=None, validation_alias=AliasChoices("query_asset_id", "queryAssetId"))
    scale_invariant: bool = Field(default=False, validation_alias=AliasChoices("scale_invariant", "scaleInvariant"))
    rotation_invariant: bool = Field(default=False, validation_alias=AliasChoices("rotation_invariant", "rotationInvariant"))
    tolerance: float = Field(default=0.01, ge=0, le=1)
    top_k: int = Field(default=8, validation_alias=AliasChoices("top_k", "topK"), ge=1, le=50)
    depends_on: dict[str, list[str]] = Field(default_factory=dict, validation_alias=AliasChoices("depends_on", "dependsOn"))

    @model_validator(mode="after")
    def validate_dependencies(self):
        if self.threshold_expression is not None:
            number=Decimal(self.threshold_expression)
            if not number.is_finite() or len(number.as_tuple().digits)>4096 or abs(number.as_tuple().exponent)>4096:
                raise ValueError("thresholdExpression exceeds supported decimal precision")
            if self.threshold is not None and Decimal(str(self.threshold))!=number:
                raise ValueError("threshold and thresholdExpression disagree")
        if "timeseries" not in self.retrievers and any([self.time_range, self.compare_time_range, self.metric,
                self.threshold is not None, self.threshold_expression is not None, self.query_series]):
            raise ValueError("timeRange/metric/threshold/querySeries only apply to timeseries")
        if "geometry" not in self.retrievers and any([self.query_asset_id, self.scale_invariant, self.rotation_invariant]):
            raise ValueError("geometry sample/invariance only apply to geometry")
        if self.compare_time_range and self.retriever_operations.get("timeseries", self.operation) != "compare_periods":
            raise ValueError("compareTimeRange only applies to compare_periods")
        if (self.threshold is not None or self.threshold_expression is not None) and self.retriever_operations.get("timeseries", self.operation) != "duration_above":
            raise ValueError("threshold only applies to duration_above")
        if self.count_mode is not None and (self.retrievers != ['timeseries'] or self.operation != 'count'):
            raise ValueError('countMode requires a single timeseries count')
        if self.count_mode == 'records' and (not self.table_id or self.unit):
            raise ValueError('record count requires tableId and has no measurement unit')
        if self.table_id and self.retrievers != ['structured'] and not (self.retrievers == ['timeseries'] and self.operation == 'count'):
            raise ValueError('tableId requires structured retrieval or a single timeseries count')
        allowed = {"text": {"search", "filter"}, "visual": {"search", "filter"},
                   "structured": {"search", "filter", "sort", "count", "sum", "min", "max", "average"},
                   "geometry": {"search", "filter", "similar_shape"},
                   "timeseries": {"search", "count", "sum", "min", "max", "average", "time_weighted_average",
                       "duration_above", "compare_periods", "trend_start_end", "trend_regression", "trend_monotonic", "similar_shape"}}
        for name in self.retrievers:
            if self.retriever_operations.get(name, self.operation) not in allowed[name]:
                raise ValueError(f"unsupported operation for {name}")
        timeseries_operation = self.retriever_operations.get("timeseries", self.operation)
        geometry_operation = self.retriever_operations.get("geometry", self.operation)
        if "timeseries" in self.retrievers and timeseries_operation not in {"search", "similar_shape"}:
            if (not self.metric and self.count_mode != 'records') or not self.time_range:
                raise ValueError("timeseries exact operation requires metric and timeRange")
        if timeseries_operation == "duration_above" and self.threshold is None and self.threshold_expression is None:
            raise ValueError("duration_above requires threshold")
        if timeseries_operation == "compare_periods" and self.compare_time_range is None:
            raise ValueError("compare_periods requires compareTimeRange")
        if timeseries_operation == "similar_shape" and "timeseries" in self.retrievers and not self.query_series:
            raise ValueError("timeseries similar_shape requires querySeries")
        if geometry_operation == "similar_shape" and "geometry" in self.retrievers and not self.query_asset_id:
            raise ValueError("geometry similar_shape requires queryAssetId")
        structured_operation = self.retriever_operations.get("structured", self.operation)
        if "structured" in self.retrievers and structured_operation == "sort" and not self.sort_field:
            raise ValueError("structured sort requires sortField")
        if any(target not in self.retrievers or any(source not in self.retrievers for source in sources)
               for target, sources in self.depends_on.items()):
            raise ValueError("dependsOn may only reference requested retrievers")
        if any(self.retrievers.index(source) >= self.retrievers.index(target)
               for target, sources in self.depends_on.items() for source in sources):
            raise ValueError("dependsOn sources must precede their dependent retriever")
        if self.target_entity and "visual" in self.retrievers:
            raise ValueError("visual retriever cannot verify targetEntity")
        if self.target_entity and "structured" in self.retrievers and not any(
                condition.op == "eq" and str(condition.value) == self.target_entity
                for condition in self.hard_conditions):
            raise ValueError("structured targetEntity requires an explicit equality field")
        if self.hard_conditions:
            names = set(self.retrievers)
            verified = names in ({"structured"}, {"geometry"}) or (names == {"timeseries"} and self.content_type == "timeseries") or (
                names == {"geometry", "timeseries"} and self.depends_on.get("timeseries") == ["geometry"])
            if not verified:
                raise ValueError("hardConditions require a scoped structured or geometry validator")
        return self


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
        pattern="^(auto|none|text_to_image|image_to_text|dual|professional)$",
        description="检索类型；auto 时仅在规则无法判断的图片请求中调用意图模型",
    )
    query_plan: Optional[ProfessionalQueryPlan] = Field(
        default=None, validation_alias=AliasChoices("query_plan", "queryPlan")
    )
    top_k: int = Field(
        default=8, ge=1, le=50, validation_alias=AliasChoices("top_k", "topK")
    )
    max_tokens: Optional[int] = Field(
        default=None, ge=1, le=32768, validation_alias=AliasChoices("max_tokens", "maxTokens")
    )
    temperature: Optional[float] = Field(default=None, ge=0.0, le=2.0)
