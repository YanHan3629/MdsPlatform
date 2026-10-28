import logging
import json
import re
from dataclasses import dataclass
from typing import Optional

from services.intent_analyzer import IntentAnalyzer


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class RetrievalPlan:
    provider_mode: str
    retrieval_type: str
    text_query: str
    use_uploaded_images: bool
    reason: str
    confidence: float
    intent_analysis_used: bool
    professional_plan: Optional[dict] = None


class RetrievalPlanner:
    """Only complete deterministic plans bypass the intent model; never drop constraints."""

    _NO_RETRIEVAL = re.compile(r"(?:不要|无需|不需要|禁止)(?:进行)?检索|仅(?:分析|描述|总结)(?:这|该)?(?:张)?图")
    _IMAGE_TO_TEXT = re.compile(
        r"图搜文|以图搜文|根据(?:这|该)?(?:张)?图(?:片)?(?:查找|检索|搜索)"
        r"|与(?:这|该)?(?:张)?图(?:片)?(?:相关|相似)"
        r"|(?:查找|找到|检索|搜索).{0,8}(?:相关|关联|相似)?资料"
    )
    _TEXT_TO_IMAGE = re.compile(r"文搜图|以文搜图|(?:查找|检索|搜索).{0,16}(?:图片|图像|照片)")
    _DUAL = re.compile(r"(?:结合|同时使用|综合).{0,12}(?:图片|图像).{0,12}(?:文字|文本|问题|条件)|(?:图片|图像).{0,12}(?:和|与).{0,6}(?:文字|文本).{0,8}(?:检索|查找)")

    def __init__(self, analyzer: IntentAnalyzer):
        self.analyzer = analyzer

    @staticmethod
    def _plan(provider, retrieval_type, question, use_images, reason, confidence=1.0, used=False, professional_plan=None):
        return RetrievalPlan(provider, retrieval_type, question, use_images, reason, confidence, used, professional_plan)

    @staticmethod
    def _professional_rule(question: str, top_k: int = 8) -> dict | None:
        """Deterministic rules for unambiguous specialist questions."""
        temporal = re.search(r"温度|振动|压力|电流|电压|时序|趋势|持续多久|时间段|最大值|最小值|周期|曲线", question)
        geometry = re.search(r"几何|形状相似|尺寸|包围盒|表面积|体积|网格|拓扑|STEP|OBJ|零件", question, re.I)
        structured = re.search(r"筛选|过滤|字段|数量|计数|总和|平均值|排序|升序|降序|大于|小于|等于", question)
        if not (temporal or geometry or structured):
            return None
        if temporal and geometry:
            return None  # Dependency planning is not a single-keyword routing decision.
        operators = {"等于": "eq", "大于等于": "gte", "小于等于": "lte", "大于": "gt", "小于": "lt", "超过": "gt", "高于": "gt"}
        condition_pattern = r"([A-Za-z_][A-Za-z0-9_.]*|体积|表面积)\s*(大于等于|小于等于|等于|大于|小于|超过|高于)\s*([\"']?[A-Za-z_][A-Za-z0-9_.-]*[\"']?|-?\d+(?:\.\d+)?)(?:\s*(mm3|cm3|m3|mm2|cm2|m2|mm|cm|m|℃|°C))?"
        conditions = []
        for match in re.finditer(condition_pattern, question):
            field, op, raw, unit = match.groups()
            value = raw.strip("\"'")
            if re.fullmatch(r"-?\d+(?:\.\d+)?", value): value = float(value)
            condition = {"field": {"体积": "volume", "表面积": "surfaceArea"}.get(field, field), "op": operators[op], "value": value}
            if unit: condition["unit"] = unit
            conditions.append(condition)
        condition_words = re.findall(r"大于等于|小于等于|等于|大于|小于|超过|高于", question)
        if not temporal and (len(conditions) != len(condition_words) or re.search(r"或者|或|不等于|小于等于以外", question)):
            return None
        if temporal:
            retrievers = ["timeseries"]
            operation = "search"
            if "持续多久" in question or "持续时间" in question: operation = "duration_above"
            elif "最大值" in question: operation = "max"
            elif "最小值" in question: operation = "min"
            elif "时间加权平均" in question: operation = "time_weighted_average"
            elif "平均" in question: operation = "average"
            elif "回归趋势" in question: operation = "trend_regression"
            elif "单调" in question: operation = "trend_monotonic"
            elif "趋势" in question: operation = "trend_start_end"
            elif "相似" in question or "曲线" in question: operation = "similar_shape"
            metric = "temperature" if "温度" in question else ("vibration" if "振动" in question else None)
            plan = {"taskIntent": "timeseries_analysis", "retrievers": retrievers, "queryText": question,
                    "metric": metric, "operation": operation, "topK": top_k}
            entity = re.search(r"(?:设备|测点)\s*([A-Za-z0-9_-]+)", question)
            if entity: plan["targetEntity"] = entity.group(1)
            threshold = re.search(r"(?:超过|高于|大于)\s*(-?\d+(?:\.\d+)?)", question)
            if threshold: plan["threshold"] = float(threshold.group(1))
            dates = re.findall(r"\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?)?", question)
            if len(dates) >= 2: plan["timeRange"] = {"start": dates[0], "end": dates[1], "boundary": "[start,end]"}
            from schemas.qa_req import ProfessionalQueryPlan
            try:
                ProfessionalQueryPlan.model_validate(plan)
            except ValueError:
                raise ValueError("NEEDS_CLARIFICATION: 时序计算需要明确指标、带时区的时间范围及必要查询样本；请使用专业查询表单")
            return plan
        if geometry:
            if re.search(r"相似|孔|接口|拓扑|尺寸|包围盒", question) or not conditions:
                return None
            return {"taskIntent": "geometry_search", "retrievers": ["geometry"], "queryText": question,
                    "operation": "filter", "hardConditions": conditions, "topK": top_k}
        if re.search(r"排序|升序|降序", question):
            match = re.search(r"(?:按|根据)\s*([A-Za-z_][A-Za-z0-9_.]*)\s*(?:字段)?\s*(?:进行)?\s*(?:升序|降序|排序|排列)", question)
            if not match:
                raise ValueError("NEEDS_CLARIFICATION: 结构化排序需要明确原始字段名")
            return {"taskIntent": "structured_sort", "retrievers": ["structured"], "queryText": question,
                    "operation": "sort", "sortField": match.group(1),
                    "sortDescending": "降序" in question, "topK": top_k}
        aggregate = re.search(r"(?:字段\s*)?([A-Za-z_][A-Za-z0-9_.]*)\s*(?:的)?(平均值|平均|总和|求和|最大值|最小值)", question)
        if aggregate:
            operation = {"平均值": "average", "平均": "average", "总和": "sum", "求和": "sum", "最大值": "max", "最小值": "min"}[aggregate.group(2)]
            return {"taskIntent": "structured_aggregate", "retrievers": ["structured"], "queryText": question,
                    "operation": operation, "aggregateField": aggregate.group(1), "hardConditions": conditions, "topK": top_k}
        if not conditions:
            return None
        return {"taskIntent": "structured_query", "retrievers": ["structured"], "queryText": question,
                "operation": "count" if re.search(r"数量|计数", question) else "filter", "hardConditions": conditions, "topK": top_k}

    def plan(
        self,
        *,
        question: str,
        input_mode: str,
        retrieval_mode: str,
        retrieval_type: str,
        use_search_service: bool,
        has_scope: bool,
        image_count: int,
        text_count: int,
        has_context: bool,
        allow_model_analysis: bool = True,
        query_plan: dict | None = None,
        top_k: int = 8,
    ) -> RetrievalPlan:
        has_images = image_count > 0

        if input_mode == "user_data_only":
            return self._plan("none", "none", question, False, "user_data_only 禁止检索")
        if not has_scope:
            return self._plan("none", "none", question, False, "缺少数据集或索引标识")
        if retrieval_mode == "none" or retrieval_type == "none":
            return self._plan("none", "none", question, False, "请求明确关闭检索")

        provider = retrieval_mode
        if provider == "auto":
            provider = "search_service" if use_search_service else "bundle"
        if provider == "bundle":
            return self._plan("bundle", "text_to_image", question, False, "请求明确使用 bundle")

        if query_plan:
            return self._plan(provider, "professional", question, False, "请求提供了完整 QueryPlan", professional_plan=query_plan)

        # 新增的 retrievalType 是最明确的路由选择，永远不调用意图模型。
        if retrieval_type != "auto":
            selected = retrieval_type
            if selected == "professional":
                inferred = self._professional_rule(question, top_k)
                if not inferred:
                    if self._needs_semantic_planning(question):
                        raise ValueError("NEEDS_CLARIFICATION: 专业检索模式尚未明确完整条件，请在问题中补充字段、对象及范围")
                    inferred = {"taskIntent": "text_search", "retrievers": ["text"], "queryText": question,
                                "operation": "search", "topK": top_k}
                return self._plan(provider, selected, question, False, "请求明确指定专业检索", professional_plan=inferred)
            if selected in {"image_to_text", "dual"} and not has_images:
                selected = "text_to_image"
                return self._plan(provider, selected, question, False, "未上传图片，降级为文搜图")
            return self._plan(provider, selected, question, has_images, "请求明确指定检索类型")

        if self._NO_RETRIEVAL.search(question):
            return self._plan("none", "none", question, False, "用户明确要求不检索")

        if self._TEXT_TO_IMAGE.search(question) and not self._needs_semantic_planning(question):
            return self._plan(provider, "text_to_image", question, False, "用户明确要求文搜图")

        specialist = self._professional_rule(question, top_k)
        if specialist:
            return self._plan(provider, "professional", question, False, "确定性专业检索规则", professional_plan=specialist)

        # 输入形态已经能唯一确定检索类型时走快路径。
        if (input_mode == "question_only" or not has_images) and not self._needs_semantic_planning(question):
            text_plan = {"taskIntent": "text_search", "retrievers": ["text"], "queryText": question,
                         "operation": "search", "topK": top_k}
            return self._plan(provider, "professional", question, False, "仅有文字查询，使用专业文本检索", professional_plan=text_plan)

        # 用户自然语言明确表达时也不需要额外模型调用。
        if self._DUAL.search(question):
            return self._plan(provider, "dual", question, True, "用户明确要求图文联合检索")
        image_match = self._IMAGE_TO_TEXT.search(question)
        text_match = self._TEXT_TO_IMAGE.search(question)
        if image_match and text_match:
            return self._plan(provider, "dual", question, True, "问题同时明确要求图搜文和文搜图")
        if image_match:
            return self._plan(provider, "image_to_text", question, True, "问题明确要求图搜文")
        if text_match:
            return self._plan(provider, "text_to_image", question, False, "问题明确要求文搜图")

        if not allow_model_analysis:
            if self._needs_semantic_planning(question):
                raise ValueError("NEEDS_CLARIFICATION: 无法确定完整专业查询计划")
            return self._plan(
                provider, "text_to_image", question, False,
                "当前后端不支持意图分析，回退为文搜图", 0.0,
            )

        try:
            intent = self.analyzer.analyze(
                question=question,
                input_mode=input_mode,
                image_count=image_count,
                text_count=text_count,
                has_context=has_context,
            )
            selected = intent.retrieval_type
            if self._needs_semantic_planning(question):
                if selected != "professional" or not intent.query_plan:
                    raise ValueError("NEEDS_CLARIFICATION: 意图模型未生成完整专业查询计划")
                from schemas.qa_req import ProfessionalQueryPlan
                ProfessionalQueryPlan.model_validate(intent.query_plan)
                if re.search(r"先.+再|对应设备|关联设备", question) and not (intent.query_plan.get("dependsOn") or intent.query_plan.get("depends_on")):
                    raise ValueError("NEEDS_CLARIFICATION: 跨检索器问题缺少可执行依赖关系")
            if selected == "professional" and not intent.query_plan:
                inferred = self._professional_rule(question, top_k)
                if not inferred:
                    raise ValueError("NEEDS_CLARIFICATION: 专业检索缺少可验证的 QueryPlan")
                intent = type(intent)(intent.retrieval_type, intent.text_query, intent.use_uploaded_images,
                                      intent.reason, intent.confidence, inferred)
            if selected == "professional" and intent.query_plan:
                conditions = intent.query_plan.get("hardConditions", intent.query_plan.get("hard_conditions", []))
                comparisons = re.findall(r"大于等于|小于等于|不等于|等于|大于|小于|超过|高于", question)
                if comparisons and "timeseries" not in intent.query_plan.get("retrievers", []) and len(conditions) < len(comparisons):
                    raise ValueError("NEEDS_CLARIFICATION: 查询计划遗漏显式筛选条件")
                if "不等于" in question:
                    raise ValueError("NEEDS_CLARIFICATION: 当前执行器不支持不等于条件")
            if selected in {"image_to_text", "dual"} and not has_images:
                selected = "text_to_image"
            selected_provider = "none" if selected == "none" else provider
            return RetrievalPlan(
                provider_mode=selected_provider,
                retrieval_type=selected,
                text_query=intent.text_query,
                use_uploaded_images=selected in {"image_to_text", "dual"} and has_images,
                reason=f"模型意图分析选择 {selected}",
                confidence=intent.confidence,
                intent_analysis_used=True,
                professional_plan=intent.query_plan,
            )
        except Exception as exc:
            logger.warning("retrieval intent analysis failed: %s", exc)
            if self._needs_semantic_planning(question):
                raise ValueError("NEEDS_CLARIFICATION: 无法可靠保留专业查询条件，请补充字段、实体、时间或样本") from exc
            # 意图模型失败时保持旧版行为，优先保证问答可用。
            return self._plan(provider, "text_to_image", question, False, "意图分析失败，回退为文搜图", 0.0, True)

    @staticmethod
    def _needs_semantic_planning(question: str) -> bool:
        return bool(re.search(r"筛选|过滤|统计|数量|计数|总和|求和|平均|排序|升序|降序|大于|小于|等于|超过|高于|趋势|持续|时序|曲线|几何|形状|尺寸|包围盒|表面积|体积|拓扑|零件|对应设备|关联|先.+再", question))
