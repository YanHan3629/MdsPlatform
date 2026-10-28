import base64
import json
import logging
import math
import re
import time
from datetime import datetime, timezone
from typing import List, Optional, Tuple
from uuid import UUID

from adapters.vllm_client import VllmClient, VllmClientError, client as vllm_default_client
from core.config import settings
from schemas.qa_req import ProfessionalQueryPlan
from schemas.qa_resp import QAMeta, QAResponse, RetrievedItem, SourceItem
from services.intent_analyzer import IntentAnalyzer
from services.prompt_builder import build_system_prompt
from services.retrieval_planner import RetrievalPlanner
from services.calculation_context import compact_decimal
from services.timeseries_lineage import scope_details as timeseries_scope_details

logger = logging.getLogger(__name__)

TASK_TYPES = {"general_qa", "chain_risk_analysis", "demand_evaluation", "data_summary"}
NUMBER_PATTERN = re.compile(r"(?<![A-Za-z_])[-+]?\d+(?:\.\d+)?")
SOURCE_PATTERN = re.compile(r"\[来源\s*(\d+)\]")
BARE_CITATION_PATTERN = re.compile(r"\[(\d{1,2})\]")
MEASUREMENT_PATTERN = re.compile(
    r"(?<![A-Za-z0-9_.])(-?\d+(?:\.\d+)?)\s*(mm3|cm3|m3|mm2|cm2|m2|mm|cm|m|毫米|厘米|米|℃|°C|C|K|摄氏度|kWh)(?![A-Za-z])",
    re.IGNORECASE,
)


def _image_to_data_url(filename: str, content: bytes) -> str:
    """将图片字节转为 OpenAI 兼容的 base64 data URL。"""
    ext = ""
    if filename and "." in filename:
        ext = filename.rsplit(".", 1)[-1].lower()
    mime = {
        "jpg": "image/jpeg",
        "jpeg": "image/jpeg",
        "png": "image/png",
        "webp": "image/webp",
        "bmp": "image/bmp",
        "gif": "image/gif",
    }.get(ext, "image/jpeg")
    b64 = base64.b64encode(content).decode("ascii")
    return f"data:{mime};base64,{b64}"


def _truncate(text: str, limit: int) -> str:
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    return text[:limit] + "..."


def _compute_confidence(choices: list) -> Tuple[float, str]:
    """优先用生成 token 的平均对数概率作为置信度（vLLM logprobs）。"""
    try:
        logprobs = choices[0].get("logprobs")
        if logprobs and logprobs.get("content"):
            probs = [
                math.exp(item["logprob"])
                for item in logprobs["content"]
                if item.get("logprob") is not None
            ]
            if probs:
                return round(sum(probs) / len(probs), 4), "token_logprob_mean"
    except Exception:
        pass
    return 1.0, "unavailable"


def _verified_answer(answer: str, question: str, sources: List[SourceItem]) -> str:
    """Reject claims that contradict exact or ranked professional evidence."""
    answer = BARE_CITATION_PATTERN.sub(
        lambda match: f"[来源{match.group(1)}]" if 1 <= int(match.group(1)) <= len(sources) else "",
        answer or "",
    )
    answer = SOURCE_PATTERN.sub(
        lambda match: match.group(0) if 1 <= int(match.group(1)) <= len(sources) else "",
        answer or "",
    ).strip()
    exact_sources = [source for source in sources if source.evidence_type in {"calculation", "constraint"}]
    shape_sources = [source for source in sources if source.evidence_type == "retrieval" and
                     source.semantic_space in {"timeseries.shape", "geometry.shape"}]
    contradicts_shape_hits = bool(shape_sources) and bool(re.search(
        r"(?:(?:无法(?:直接)?确定|不能确定|无法判断).{0,80}(?:相似|接近|匹配)|未找到.{0,80}(?:相似|接近)|"
        r"不存在.{0,80}(?:相似|接近)|没有(?:找到|检索到|匹配到).{0,80}(?:相似|接近))",
        answer,
    ))
    if contradicts_shape_hits:
        verified = []
        for source in shape_sources:
            index = sources.index(source) + 1
            fact = json.dumps({"facts": source.facts, "units": source.units,
                               "timeRange": source.time_range, "scoreType": source.score_type,
                               "rawScore": source.raw_score}, ensure_ascii=False, default=str)
            verified.append(f"- [来源{index}] {_truncate(fact, 700)}")
        return ("程序核验后保留以下近似检索结果（按检索排名）：\n" + "\n".join(verified) +
                "\n\n模型草稿否认了已命中的形态候选，已用索引证据替换。"
                "这些结果只表示近似形态匹配，不构成尺寸、拓扑、工程可替换性或因果结论。")
    if shape_sources and "近似" not in answer:
        answer += ("\n\n说明：上述候选来自专业索引的近似形态检索；它不构成尺寸、拓扑、"
                   "工程可替换性或因果结论。")
    if not exact_sources:
        source_measurements = {
            (float(value), unit.lower())
            for source in sources for value, unit in MEASUREMENT_PATTERN.findall(source.content_preview or "")
        }
        unsupported_measurements = [
            (value, unit) for value, unit in MEASUREMENT_PATTERN.findall(answer)
            if (float(value), unit.lower()) not in source_measurements
        ]
        if unsupported_measurements:
            cited = [f"- [来源{index}] {_truncate(source.content_preview, 180)}"
                     for index, source in enumerate(sources, 1)
                     if source.source_type not in {"limitation"}]
            return ("已检索到相关资料，但模型草稿包含未获文字或结构化证据核验的具体测量值，"
                    "已省略这些数值。\n" + "\n".join(cited[:5]) +
                    "\n视觉相似或图像推断不能单独证明精确尺寸、单位或工程可替换性。")
        return answer
    guarded_statuses = {
        "NEEDS_TIMEZONE_MAPPING": "来源时区尚未映射",
        "NEEDS_UNIT_MAPPING": "来源单位尚未映射或不可可靠换算",
        "TARGET_ENTITY_REQUIRED": "目标实体尚未明确",
        "NO_DATA": "指定范围内没有可计算的原始事实",
    }
    guarded = [source for source in exact_sources
               if source.facts.get("value") is None and source.facts.get("status") in guarded_statuses]
    if guarded and not any(source.facts.get("value") is not None for source in exact_sources):
        explanations = []
        for source in guarded:
            index = sources.index(source) + 1
            facts = source.facts
            explanations.append(
                f"- [来源{index}] 资产 {source.asset_id or '未指定'}；"
                f"指标/字段 {facts.get('metric') or facts.get('field') or '未指定'}；"
                f"状态 {facts['status']}：{guarded_statuses[facts['status']]}；"
                f"样本数 {facts.get('sampleCount', facts.get('matchedRowCount', '未知'))}。"
            )
        return ("程序核验后，当前不能给出精确数值；原因如下：\n" + "\n".join(explanations) +
                "\n请补全相应映射或查询条件后重新计算，不能把相似检索结果当作精确统计。")
    calculations = [source for source in exact_sources if source.evidence_type == "calculation" and "operation" in source.facts]
    if calculations:
        # Free prose cannot be reliably checked by membership in a bag of numbers.
        # Bind every numeric result to its operation, field, unit, scope and citation.
        labels = {"average": "样本平均值", "sum": "总和", "count": "计数", "min": "最小值", "max": "最大值",
                  "time_weighted_average": "时间加权估计平均值", "duration_above": "严格超过阈值估计时长",
                  "trend_start_end": "起止变化量", "trend_regression": "每秒回归斜率", "trend_monotonic": "逐点非递减",
                  "compare_periods": "两时段比较"}
        lines = []
        for source in calculations:
            facts = source.facts
            operation = facts.get("operation")
            value = facts.get("value")
            unit = facts.get("unit") or source.units.get("value") or ""
            if operation == 'count':unit='点' if facts.get('countPolicy')=='unique-calculable-time-samples' else '条'
            if operation == 'duration_above' and unit == 's':unit='秒'
            if operation == "trend_regression" and unit: unit += "/s"
            if operation == "trend_monotonic": unit = ""
            if facts.get('exactValue') is not None:
                display=str(facts['exactValue'])
                if len(display)>128:
                    expression=compact_decimal(display)
                    if expression!=display:display=expression+'（精确表达式）'
            elif facts.get('exactRatio') and facts.get('decimalRounded'):
                ratio=facts['exactRatio'];numerator=compact_decimal(ratio['numerator']);denominator=compact_decimal(ratio['denominator'])
                display=f"({numerator})/({denominator})（精确比值；十进制近似 {facts.get('decimalValue')}）"
            elif facts.get('decimalValue') is not None and facts.get('decimalRounded') is False:display=str(facts['decimalValue'])
            else:display = json.dumps(value, ensure_ascii=False) if value is not None else "不可计算"
            if operation == "compare_periods" and "difference" in facts:
                display = json.dumps({key: facts.get(key) for key in ("firstPeriodAverage", "secondPeriodAverage", "difference")}, ensure_ascii=False)
            label=('物理记录数' if facts.get('countMode')=='records' else '去重后有效数值样本数') if facts.get('countMode') else labels.get(operation,operation)
            field='记录' if facts.get('countMode')=='records' else facts.get('metric') or facts.get('field') or '记录'
            result_line=f"- [来源{sources.index(source) + 1}] {field}的{label}：{display}{(' '+unit) if unit else ''}。"
            lines.append(result_line)
            if facts.get('arithmetic')=='exact-rational-source-lexemes-v1':
                details=[]
                if source.entity_id:details.append('对象 '+source.entity_id)
                if facts.get('matchedRowCount') is not None:details.append(f"匹配 {facts['matchedRowCount']} 条记录")
                if facts.get('completeFilteredSet'):details.append('覆盖完整筛选范围')
                if operation!='count':
                    details.append(f"数值输入 {facts.get('numericInputCount',0)} 项")
                    if facts.get('excludedNullOrNonNumericCount'):details.append(f"{facts['excludedNullOrNonNumericCount']} 项空值或非数值未参与计算")
                if facts.get('legacyDecodedNumberCount'):details.append(f"其中 {facts['legacyDecodedNumberCount']} 项使用了解码数值，原始数字表达式未全量保留")
                if source.time_range:details.append('时间范围 '+json.dumps(source.time_range,ensure_ascii=False))
                if (source.calculation_lineage or {}).get('version','').startswith('native-timeseries-calculation-'):
                    details=timeseries_scope_details(source.calculation_lineage)
                else:
                    if source.calculation_policy:details.append('计算口径 '+json.dumps(source.calculation_policy,ensure_ascii=False))
                    if source.quality_flags:details.append('数据说明 '+ '；'.join(source.quality_flags))
                scope_line='  '+'；'.join(details)+'。' if details else ''
                if scope_line:lines.append(scope_line)
                if source.calculation_lineage:
                    source.provenance['verifiedCalculationPresentation']={'result':result_line,'scope':scope_line}
                continue
            scope = {"entity": source.entity_id, "timeRange": source.time_range, "status": facts.get("status"),
                     "sampleCount": facts.get("sampleCount", facts.get("matchedRowCount")),
                     "policy": source.calculation_policy, "qualityFlags": source.quality_flags}
            lines.append("  范围与口径：" + json.dumps(scope, ensure_ascii=False, default=str))
        return "计算结果：\n" + "\n".join(lines)
    evidence_text = json.dumps([{
        "facts": source.facts,
        "units": source.units,
        "timeRange": source.time_range,
        "policy": source.calculation_policy,
    } for source in exact_sources], ensure_ascii=False, default=str)
    allowed = [float(value) for source in exact_sources for key, value in source.facts.items()
               if isinstance(value, (int, float)) and not isinstance(value, bool)
               and key not in {"sampleCount", "matchedRowCount", "sourceSeriesCount"}]
    answer_without_citations = SOURCE_PATTERN.sub("", answer)
    claimed = [float(value) for value in NUMBER_PATTERN.findall(answer_without_citations)]
    unsupported = [value for value in claimed if not any(
        abs(value - permitted) <= 1e-6 * max(1.0, abs(value), abs(permitted)) for permitted in allowed
    )]
    contradicts_constraint_hits = any(source.evidence_type == "constraint" for source in exact_sources) and bool(
        re.search(r"(?:不存在|未找到|没有(?:找到|检索到|匹配到)|无匹配).{0,80}(?:记录|结果|数据|证据)", answer)
    )
    unit_alias = {"°c": "c", "℃": "c", "摄氏度": "c"}
    measurements = {(float(value), unit_alias.get(str(source.units.get("value", "")).lower(), str(source.units.get("value", "")).lower()))
                    for source in exact_sources for value in source.facts.values()
                    if isinstance(value, (int, float)) and not isinstance(value, bool)}
    unsupported_units = any((float(value), unit_alias.get(unit.lower(), unit.lower())) not in measurements
                            for value, unit in MEASUREMENT_PATTERN.findall(answer_without_citations))
    if not unsupported and not unsupported_units and not contradicts_constraint_hits:
        return answer
    verified = []
    for source in exact_sources:
        index = sources.index(source) + 1
        fact = json.dumps({"facts": source.facts, "units": source.units,
                           "timeRange": source.time_range, "policy": source.calculation_policy},
                          ensure_ascii=False, default=str)
        verified.append(f"- [来源{index}] {_truncate(fact, 700)}")
    reason = ("模型草稿与已命中的硬条件记录矛盾，已用程序验证事实替换。"
              if contradicts_constraint_hits else "模型草稿含未获证据支持的数值，已从结论中省略。")
    return "程序核验后仅保留以下已计算或已验证事实：\n" + "\n".join(verified) + "\n\n" + reason


class QaService:
    """多模态问答核心：收集依据 -> 可选检索 -> 组装消息 -> 调用 vLLM -> 统一响应。"""

    def __init__(self, client: Optional[VllmClient] = None, bearer_token=None):
        self.client = client or vllm_default_client
        self.bearer_token = bearer_token or ""
        self._search_client = None
        self._bundle_retriever = None
        self._retrieval_planner = RetrievalPlanner(IntentAnalyzer(self.client))

    # ------------------------------------------------------------- retrieval

    def _get_search_client(self):
        if self._search_client is None:
            from adapters.search_client import SearchServiceClient

            self._search_client = SearchServiceClient(self.bearer_token)
        return self._search_client

    def _get_bundle_retriever(self):
        if self._bundle_retriever is None:
            from services.bundle_retriever import BundleRetriever

            self._bundle_retriever = BundleRetriever()
        return self._bundle_retriever

    @staticmethod
    def _as_uuid(value) -> Optional[UUID]:
        if value is None:
            return None
        try:
            return UUID(str(value))
        except Exception:
            return None

    def _retrieve(
        self, question, dataset_id, version_id, index_version_id, mode: str,
        retrieval_type: str, query_images: List[Tuple[str, bytes]], top_k: int,
        professional_plan: dict | None = None,
    ):
        """按检索计划执行文搜图、图搜文、双路检索或 bundle 回退。"""
        sources: List[SourceItem] = []
        retrieved_images: List[Tuple[str, bytes, float]] = []
        retrieved_meta: List[dict] = []

        if mode == "search_service" and dataset_id and version_id and index_version_id:
            client = self._get_search_client()
            ranked_records = []
            downloaded_by_key = {}

            if retrieval_type == "professional":
                if not professional_plan:
                    raise ValueError("professional retrieval requires QueryPlan")
                try:
                    professional_plan = ProfessionalQueryPlan.model_validate(professional_plan).model_dump(
                        by_alias=True, exclude_none=True)
                except ValueError as exc:
                    error = str(exc)
                    reason = ("起止时间需明确时区" if "explicit timezone" in error else
                              "结束时间必须晚于开始时间" if "later than start" in error else
                              "查询计划包含不支持的字段" if "extra_forbidden" in error else
                              "硬条件缺少可验证的作用范围" if "scoped structured or geometry validator" in error else
                              "视觉检索不能核验精确实体约束" if "cannot verify targetEntity" in error else
                              "依赖步骤顺序错误" if "must precede" in error else
                              "查询计划缺少必需字段或不满足执行约束")
                    raise ValueError("NEEDS_CLARIFICATION: " + reason) from exc
                operation = (professional_plan.get("retrieverOperations") or professional_plan.get("retriever_operations") or {}).get(
                    "timeseries", professional_plan.get("operation", "search"))
                time_range = professional_plan.get("timeRange") or professional_plan.get("time_range")
                if "timeseries" in professional_plan.get("retrievers", []) and operation not in {"search", "similar_shape"} and not time_range:
                    raise ValueError("NEEDS_CLARIFICATION: 精确时序计算需要明确 timeRange")
                pack = client.professional_search(dataset_id, version_id, index_version_id, professional_plan)
                if pack.get("limitations") and not pack.get("evidence"):
                    codes = pack["limitations"]
                    if any("FIELD_NOT_FOUND" in code or "FIELD_REQUIRED" in code for code in codes):
                        raise ValueError("NEEDS_CLARIFICATION: " + ", ".join(codes))
                    if any("RELATION_NOT_FOUND" in code for code in codes):
                        raise ValueError("RELATION_NOT_FOUND: 当前数据版本没有可追溯的实体关系")
                    raise ValueError("INDEX_NOT_READY: " + ", ".join(codes))
                for item in pack.get("evidence", []):
                    facts = item.get("facts", {})
                    preview = item.get("preview") or {}
                    score = item.get("rawScore")
                    relevance = 1.0 if item.get("evidenceType") in {"calculation", "constraint"} else float(score or 0.0)
                    if str(item.get("scoreType", "")).endswith("lower_is_better"):
                        relevance = 1.0 / (1.0 + max(0.0, relevance))
                    path = preview.get("logicalPath")
                    sources.append(SourceItem(
                        source_id=item.get("evidenceId", f"evidence_{len(sources)}"),
                        source_type=item.get("evidenceType", "retrieval"),
                        relevance_score=max(0.0, min(1.0, relevance)),
                        content_preview=json.dumps({
                            "assetId": item.get("assetId"), "entityId": item.get("entityId"),
                            "status": facts.get("status"), "timeRange": item.get("timeRange"),
                            "facts": facts, "units": item.get("units", {}), "locator": item.get("locator", {}),
                            "qualityFlags": item.get("qualityFlags", []),
                            "calculationPolicy": item.get("calculationPolicy", {}),
                        }, ensure_ascii=False),
                        asset_id=self._as_uuid(item.get("assetId")), logical_path=path,
                        data_version=item.get("dataVersion"), index_version=item.get("indexVersion"),
                        evidence_id=item.get("evidenceId"), semantic_space=item.get("semanticSpace"),
                        locator=item.get("locator", {}), entity_id=item.get("entityId"),
                        time_range=item.get("timeRange"), raw_score=score,
                        score_type=item.get("scoreType"), facts=facts, units=item.get("units", {}),
                        calculation_policy=item.get("calculationPolicy", {}),
                        provenance=item.get("provenance", {}), quality_flags=item.get("qualityFlags", []),
                        evidence_type=item.get("evidenceType", "retrieval"),
                    ))
                    retrieved_meta.append({
                        "asset_id": item.get("assetId"), "logical_path": path,
                        "score": relevance, "preview_url": preview.get("url"),
                        "source_type": item.get("semanticSpace", "professional"),
                    })
                    if (item.get("semanticSpace") == "visual.clip-cn-v1" and item.get("assetId")
                            and len(retrieved_images) < settings.max_retrieved_images and self.bearer_token):
                        try:
                            image_data = client.download_asset_image(dataset_id, version_id, item["assetId"], preview)
                            if image_data:
                                retrieved_images.append((f"retrieved_{item['assetId']}.png", image_data, relevance))
                        except Exception as exc:
                            logger.warning("professional image unavailable: %s", type(exc).__name__)
                            sources[-1].quality_flags.append("VISUAL_PREVIEW_UNAVAILABLE")
                            pack.setdefault("limitations", []).append("VISUAL_PREVIEW_UNAVAILABLE: 图片未送入模型，仅可依据已返回的文字描述回答")
                for limitation in pack.get("limitations", []):
                    sources.append(SourceItem(source_id=f"limit_{len(sources)}", source_type="limitation",
                                              relevance_score=1.0, content_preview=limitation))
                for conflict in pack.get("conflicts", []):
                    sources.append(SourceItem(source_id=f"conflict_{len(sources)}", source_type="limitation",
                                              relevance_score=1.0,
                                              content_preview="证据冲突：" + json.dumps(conflict, ensure_ascii=False)))
                return sources, "search_service", retrieved_images, retrieved_meta, {
                    "professionalTimingsMs": pack.get("timingsMs", {}),
                    "coverage": pack.get("coverage", {}),
                    "limitations": pack.get("limitations", []),
                    "conflicts": pack.get("conflicts", []),
                }

            def append_record(item, channel: str, rank: int, preview: str):
                aid, path = item.get("assetId"), item.get("logicalPath")
                key = str(aid or path or f"{channel}:{rank}")
                ranked_records.append(
                    {
                        "key": key, "asset_id": aid, "logical_path": path,
                        "score": float(item.get("score", 0.0)),
                        "preview_url": item.get("previewUrl"),
                        "source_type": channel, "preview": preview[:500],
                        "rrf": 1.0 / (60 + rank),
                    }
                )
                return key

            if retrieval_type in ("text_to_image", "dual"):
                text_items, from_backend = [], False
                if self.bearer_token:
                    try:
                        text_items = client.backend_multimodal_search(
                            dataset_id, version_id, index_version_id, question, top_k
                        )
                        from_backend = bool(text_items)
                    except Exception as exc:
                        logger.warning("backend multimodal search failed: %s", exc)
                if not text_items:
                    try:
                        text_items = client.text_to_image(
                            dataset_id, version_id, index_version_id, question, top_k
                        )
                    except Exception as exc:
                        logger.warning("search-service text-to-image failed: %s", exc)
                for rank, item in enumerate(text_items, start=1):
                    evidence = item.get("captions") or item.get("texts") or []
                    preview = " | ".join(str(value)[:120] for value in evidence[:2])
                    preview = preview or str(item.get("logicalPath") or "")
                    key = append_record(item, "search_text_to_image", rank, preview)
                    url = item.get("previewUrl") if from_backend else None
                    content_type = item.get("contentType") or ""
                    is_image = content_type.startswith("image/") if content_type else str(item.get("logicalPath") or "").lower().endswith((".jpg", ".jpeg", ".png", ".webp"))
                    if url and is_image and len(downloaded_by_key) < settings.max_retrieved_images:
                        try:
                            data = client.download_preview(url)
                            if data:
                                downloaded_by_key[key] = (
                                    f"retrieved_{item.get('assetId') or rank}.jpg", data,
                                    float(item.get("score", 0.0)),
                                )
                        except Exception as exc:
                            logger.warning("preview download failed: %s", exc)

            if retrieval_type in ("image_to_text", "dual"):
                for _, image_bytes in query_images:
                    try:
                        items = client.image_to_text(
                            dataset_id, version_id, index_version_id, image_bytes, top_k
                        )
                        for rank, item in enumerate(items, start=1):
                            preview = str(item.get("text") or item.get("logicalPath") or "")
                            append_record(
                                item, "search_image_to_text",
                                rank, preview,
                            )
                    except Exception as exc:
                        logger.warning("search-service image-to-text failed: %s", exc)

            merged = {}
            for record in ranked_records:
                key = record["key"]
                if key not in merged:
                    merged[key] = {**record, "channels": {record["source_type"]}}
                    continue
                current = merged[key]
                current["rrf"] += record["rrf"]
                current["channels"].add(record["source_type"])
                if record["score"] > current["score"]:
                    for field in ("score", "preview", "preview_url", "asset_id", "logical_path"):
                        current[field] = record[field]

            selected = sorted(
                merged.values(), key=lambda item: (item["rrf"], item["score"]), reverse=True
            )[:top_k]
            for item in selected:
                source_type = (
                    "search_dual_match" if len(item["channels"]) > 1
                    else next(iter(item["channels"]))
                )
                retrieved_meta.append(
                    {
                        "asset_id": item["asset_id"], "logical_path": item["logical_path"],
                        "score": item["score"], "preview_url": item["preview_url"],
                        "source_type": source_type,
                    }
                )
                sources.append(
                    SourceItem(
                        source_id=f"{source_type}_{item['asset_id'] or len(sources)}",
                        source_type=source_type, relevance_score=item["score"],
                        content_preview=item["preview"],
                        asset_id=self._as_uuid(item["asset_id"]),
                        logical_path=item["logical_path"],
                    )
                )
                if item["key"] in downloaded_by_key:
                    retrieved_images.append(downloaded_by_key[item["key"]])
            if sources or retrieved_images:
                return sources, "search_service", retrieved_images, retrieved_meta, {}

        if mode == "bundle" and dataset_id and version_id and index_version_id:
            try:
                items = self._get_bundle_retriever().retrieve(
                    dataset_id, version_id, index_version_id, question, top_k
                )
                for item in items:
                    retrieved_meta.append(
                        {
                            "asset_id": item.get("asset_id"),
                            "logical_path": item.get("logical_path"),
                            "score": float(item.get("score", 0.0)),
                            "preview_url": None, "source_type": "bundle_text",
                        }
                    )
                    sources.append(
                        SourceItem(
                            source_id=f"bundle_{item.get('asset_id') or len(sources)}",
                            source_type="bundle_text",
                            relevance_score=float(item.get("score", 0.0)),
                            content_preview=str(item.get("text") or "")[:500],
                            asset_id=self._as_uuid(item.get("asset_id")),
                            logical_path=item.get("logical_path"),
                        )
                    )
                if sources:
                    return sources, "bundle", [], retrieved_meta, {}
            except Exception as exc:
                logger.warning("bundle retrieval failed: %s", exc)

        return sources, "none", retrieved_images, retrieved_meta, {}

    # ------------------------------------------------------------- generation

    @staticmethod
    def _confidence_heuristic(answer: str, sources: List[SourceItem]) -> float:
        if sources:
            avg = sum(s.relevance_score for s in sources) / len(sources)
            return round(max(0.3, min(0.99, 0.45 + 0.5 * avg)), 4)
        return 0.5

    @staticmethod
    def _mock_answer(question: str, sources: List[SourceItem]) -> str:
        lines = [f"【模拟回答】针对“{question}”："]
        top = sorted(sources, key=lambda s: s.relevance_score, reverse=True)[:5]
        if not top:
            lines.append("当前没有可用的数据依据，请补充数据或启用检索服务。")
        else:
            lines.append(f"共使用 {len(sources)} 条数据依据，其中最相关的 {len(top)} 条：")
            for i, s in enumerate(top, start=1):
                lines.append(
                    f"- [来源{i}] ({s.source_type}, 相关度{s.relevance_score:.2f}) {s.content_preview[:80]}"
                )
            lines.append("（当前为 mock 后端输出；配置 vLLM 后可生成真正的分析内容。）")
        return "\n".join(lines)

    # ------------------------------------------------------------- entry

    def answer(
        self,
        *,
        question: str,
        input_mode: str = "hybrid",
        task_type: str = "general_qa",
        dataset_id=None,
        version_id=None,
        index_version_id=None,
        texts: Optional[List[str]] = None,
        context: Optional[str] = None,
        images: Optional[List[Tuple[str, bytes]]] = None,
        use_search_service: bool = True,
        retrieval_mode: str = "auto",
        retrieval_type: str = "auto",
        query_plan: Optional[dict] = None,
        top_k: int = 8,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
    ) -> QAResponse:
        question = (question or "").strip()
        if not question:
            raise ValueError("question 不能为空")
        from services.curated_qa import answer_curated
        return answer_curated(self, question=question, input_mode=input_mode, task_type=task_type,
            dataset_id=dataset_id, version_id=version_id, index_version_id=index_version_id,
            texts=texts, context=context, images=images, use_search_service=use_search_service,
            retrieval_mode=retrieval_mode, retrieval_type=retrieval_type, query_plan=query_plan,
            top_k=top_k, max_tokens=max_tokens, temperature=temperature)


service = QaService()
