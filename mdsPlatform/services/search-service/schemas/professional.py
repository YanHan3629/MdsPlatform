from __future__ import annotations

from datetime import datetime
from typing import Any, Literal, Optional
from uuid import UUID

from pydantic import AliasChoices, BaseModel, ConfigDict, Field, model_validator


RetrieverName = Literal["text", "visual", "structured", "timeseries", "geometry"]
OperationName = Literal[
    "search", "filter", "sort", "count", "sum", "min", "max", "average", "time_weighted_average",
    "duration_above", "compare_periods", "trend_start_end", "trend_regression", "trend_monotonic", "similar_shape",
]


class HardCondition(BaseModel):
    model_config = ConfigDict(extra="forbid")
    field: str = Field(min_length=1, max_length=128)
    op: Literal["eq", "in", "gt", "gte", "lt", "lte", "between"]
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


class QueryPlan(BaseModel):
    model_config = ConfigDict(extra="forbid")
    task_intent: str = Field(validation_alias=AliasChoices("task_intent", "taskIntent"), min_length=1, max_length=128)
    retrievers: list[RetrieverName] = Field(min_length=1, max_length=5)
    query_text: str = Field(default="", validation_alias=AliasChoices("query_text", "queryText"), max_length=8000)
    target_entity: Optional[str] = Field(default=None, validation_alias=AliasChoices("target_entity", "targetEntity"))
    hard_conditions: list[HardCondition] = Field(default_factory=list, validation_alias=AliasChoices("hard_conditions", "hardConditions"), max_length=32)
    time_range: Optional[TimeRange] = Field(default=None, validation_alias=AliasChoices("time_range", "timeRange"))
    compare_time_range: Optional[TimeRange] = Field(default=None, validation_alias=AliasChoices("compare_time_range", "compareTimeRange"))
    metric: Optional[str] = None
    unit: Optional[str] = None
    aggregate_field: Optional[str] = Field(default=None, validation_alias=AliasChoices("aggregate_field", "aggregateField"), max_length=128)
    sort_field: Optional[str] = Field(default=None, validation_alias=AliasChoices("sort_field", "sortField"), max_length=128)
    sort_descending: bool = Field(default=False, validation_alias=AliasChoices("sort_descending", "sortDescending"))
    operation: OperationName = "search"
    retriever_operations: dict[RetrieverName, OperationName] = Field(
        default_factory=dict, validation_alias=AliasChoices("retriever_operations", "retrieverOperations"))
    threshold: Optional[float] = None
    query_series: Optional[list[float]] = Field(default=None, validation_alias=AliasChoices("query_series", "querySeries"), max_length=4096)
    query_asset_id: Optional[str] = Field(default=None, validation_alias=AliasChoices("query_asset_id", "queryAssetId"))
    scale_invariant: bool = Field(default=False, validation_alias=AliasChoices("scale_invariant", "scaleInvariant"))
    rotation_invariant: bool = Field(default=False, validation_alias=AliasChoices("rotation_invariant", "rotationInvariant"))
    tolerance: float = Field(default=0.01, ge=0, le=1)
    top_k: int = Field(default=8, validation_alias=AliasChoices("top_k", "topK"), ge=1, le=50)
    depends_on: dict[str, list[str]] = Field(default_factory=dict, validation_alias=AliasChoices("depends_on", "dependsOn"))

    @model_validator(mode="after")
    def validate_semantics(self):
        timeseries_operation = self.retriever_operations.get("timeseries", self.operation)
        geometry_operation = self.retriever_operations.get("geometry", self.operation)
        if "timeseries" in self.retrievers and timeseries_operation not in {"search", "similar_shape"}:
            if not self.metric:
                raise ValueError("timeseries exact operation requires metric")
            if not self.time_range:
                raise ValueError("timeseries exact operation requires timeRange")
        if timeseries_operation == "duration_above" and self.threshold is None:
            raise ValueError("duration_above requires threshold")
        if timeseries_operation == "compare_periods" and self.compare_time_range is None:
            raise ValueError("compare_periods requires compareTimeRange")
        if timeseries_operation == "similar_shape" and "timeseries" in self.retrievers and not self.query_series:
            raise ValueError("similar_shape requires querySeries")
        if "geometry" in self.retrievers and geometry_operation == "similar_shape" and not self.query_asset_id:
            raise ValueError("geometry similar_shape requires queryAssetId")
        structured_operation = self.retriever_operations.get("structured", self.operation)
        if "structured" in self.retrievers and structured_operation == "sort" and not self.sort_field:
            raise ValueError("structured sort requires sortField")
        for target, sources in self.depends_on.items():
            if target not in self.retrievers or any(source not in self.retrievers for source in sources):
                raise ValueError("dependsOn may only reference requested retrievers")
            if any(self.retrievers.index(source) >= self.retrievers.index(target) for source in sources):
                raise ValueError("dependsOn sources must precede their dependent retriever")
        if self.target_entity and "visual" in self.retrievers:
            raise ValueError("visual retriever cannot verify targetEntity")
        if self.target_entity and "structured" in self.retrievers and not any(
                condition.op == "eq" and str(condition.value) == self.target_entity
                for condition in self.hard_conditions):
            raise ValueError("structured targetEntity requires an explicit equality field")
        if self.hard_conditions:
            names = set(self.retrievers)
            verified = names in ({"structured"}, {"geometry"}) or (
                names == {"geometry", "timeseries"} and self.depends_on.get("timeseries") == ["geometry"])
            if not verified:
                raise ValueError("hardConditions require a scoped structured or geometry validator")
        return self


class ProfessionalSearchRequest(BaseModel):
    dataset_id: UUID = Field(validation_alias=AliasChoices("dataset_id", "datasetId"))
    version_id: UUID = Field(validation_alias=AliasChoices("version_id", "versionId"))
    index_version_id: UUID = Field(validation_alias=AliasChoices("index_version_id", "indexVersionId"))
    plan: QueryPlan


class Evidence(BaseModel):
    assetId: Optional[str] = None
    dataVersion: str
    evidenceId: str
    semanticSpace: str
    indexVersion: str
    locator: dict[str, Any] = Field(default_factory=dict)
    entityId: Optional[str] = None
    timeRange: Optional[dict[str, Any]] = None
    rank: int = 0
    rawScore: Optional[float] = None
    scoreType: str
    facts: dict[str, Any] = Field(default_factory=dict)
    units: dict[str, Any] = Field(default_factory=dict)
    calculationPolicy: dict[str, Any] = Field(default_factory=dict)
    provenance: dict[str, Any] = Field(default_factory=dict)
    qualityFlags: list[str] = Field(default_factory=list)
    preview: Optional[dict[str, Any]] = None
    evidenceType: Literal["retrieval", "calculation", "constraint"] = "retrieval"


class EvidencePack(BaseModel):
    snapshot: dict[str, str]
    plan: QueryPlan
    evidence: list[Evidence]
    coverage: dict[str, Any]
    conflicts: list[dict[str, Any]] = Field(default_factory=list)
    limitations: list[str] = Field(default_factory=list)
    timingsMs: dict[str, float] = Field(default_factory=dict)
