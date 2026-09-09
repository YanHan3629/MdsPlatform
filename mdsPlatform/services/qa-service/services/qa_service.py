import base64
import logging
import math
import re
import time
from datetime import datetime, timezone
from typing import List, Optional, Tuple
from uuid import UUID

from adapters.vllm_client import VllmClient, VllmClientError, client as vllm_default_client
from core.config import settings
from schemas.qa_resp import QAMeta, QAResponse, RetrievedItem, SourceItem
from services.intent_analyzer import IntentAnalyzer
from services.prompt_builder import build_system_prompt
from services.retrieval_planner import RetrievalPlanner

logger = logging.getLogger(__name__)

TASK_TYPES = {"general_qa", "chain_risk_analysis", "demand_evaluation", "data_summary"}


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


class QaService:
    """多模态问答核心：收集依据 -> 可选检索 -> 组装消息 -> 调用 vLLM -> 统一响应。"""

    def __init__(self, client: Optional[VllmClient] = None, bearer_token=None):
        self.client = client or vllm_default_client
        self.bearer_token = settings.backend_bearer_token if bearer_token is None else bearer_token
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
    ):
        """按检索计划执行文搜图、图搜文、双路检索或 bundle 回退。"""
        sources: List[SourceItem] = []
        retrieved_images: List[Tuple[str, bytes, float]] = []
        retrieved_meta: List[dict] = []

        if mode == "search_service" and dataset_id and version_id and index_version_id:
            client = self._get_search_client()
            ranked_records = []
            downloaded_by_key = {}

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
                return sources, "search_service", retrieved_images, retrieved_meta

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
                    return sources, "bundle", [], retrieved_meta
            except Exception as exc:
                logger.warning("bundle retrieval failed: %s", exc)

        return sources, "none", retrieved_images, retrieved_meta

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
        top_k: int = 8,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
    ) -> QAResponse:
        question = (question or "").strip()
        if not question:
            raise ValueError("question 不能为空")

        images = images or []
        texts = [t for t in (texts or []) if t and t.strip()]
        input_mode = (input_mode or "hybrid").lower()
        if input_mode not in ("question_only", "hybrid", "user_data_only"):
            input_mode = "hybrid"
        if input_mode == "question_only":
            # 只输入问题：禁止用户附带文本/上下文/图片
            if texts or (context and context.strip()) or images:
                raise ValueError(
                    "question_only 模式仅允许输入问题，不能附带 texts/context/images；"
                    "请使用 hybrid 或 user_data_only 模式输入数据"
                )
            texts = []
            context = None
            images = []
        image_limit = min(settings.max_upload_images, settings.max_prompt_images)
        if len(images) > image_limit:
            raise ValueError(f"单次最多上传 {image_limit} 张图片")
        task_type = task_type if task_type in TASK_TYPES else "general_qa"
        retrieval_mode = (retrieval_mode or "auto").lower()
        if retrieval_mode not in ("auto", "search_service", "bundle", "none"):
            retrieval_mode = "auto"
        retrieval_type = (retrieval_type or "auto").lower()
        if retrieval_type not in ("auto", "none", "text_to_image", "image_to_text", "dual"):
            retrieval_type = "auto"

        start = time.time()
        sources: List[SourceItem] = []
        for i, t in enumerate(texts, start=1):
            sources.append(
                SourceItem(
                    source_id=f"txt_{i}",
                    source_type="text_chunk",
                    relevance_score=0.9,
                    content_preview=_truncate(t, settings.max_text_chars),
                )
            )
        if context:
            sources.append(
                SourceItem(
                    source_id="ctx_1",
                    source_type="context",
                    relevance_score=0.9,
                    content_preview=_truncate(context, settings.max_text_chars),
                )
            )
        for i, (name, _) in enumerate(images, start=1):
            sources.append(
                SourceItem(
                    source_id=f"img_{i}",
                    source_type="image",
                    relevance_score=0.8,
                    content_preview=f"[图片] {name}",
                )
            )

        plan = self._retrieval_planner.plan(
            question=question,
            input_mode=input_mode,
            retrieval_mode=retrieval_mode,
            retrieval_type=retrieval_type,
            use_search_service=use_search_service,
            has_scope=bool(dataset_id and version_id and index_version_id),
            image_count=len(images),
            text_count=len(texts),
            has_context=bool(context and context.strip()),
            allow_model_analysis=settings.llm_backend.lower() != "mock",
        )
        mode = plan.provider_mode
        query_images = images if plan.use_uploaded_images else []
        retrieved, resolved, retrieved_images, retrieved_meta = self._retrieve(
            plan.text_query,
            dataset_id,
            version_id,
            index_version_id,
            mode,
            plan.retrieval_type,
            query_images,
            top_k,
        )
        sources.extend(retrieved)
        if resolved != "none" and (retrieved or retrieved_images):
            mode = resolved
        else:
            mode = "none"

        # 检索提示：无匹配 / 未提供数据集标识时给前端可见提示
        notice: Optional[str] = None
        if input_mode in ("question_only", "hybrid"):
            if not (dataset_id and version_id and index_version_id):
                notice = (
                    "未提供数据集标识（datasetId/versionId/indexVersionId），"
                    "无法检索数据空间；本次回答未使用数据空间内容。"
                )
            elif plan.retrieval_type == "none":
                notice = "根据输入模式或用户意图，本次未执行数据空间检索。"
            elif not (retrieved or retrieved_images):
                notice = (
                    "数据空间检索未匹配到相关内容；"
                    "本次回答基于用户输入与模型自身知识生成。"
                )
        # 检索命中的内容 ID（RAG：附带返回给用户查看）
        retrieved_items: List[RetrievedItem] = []
        seen = set()
        for item in retrieved_meta:
            aid = self._as_uuid(item.get("asset_id"))
            key = str(aid) if aid else str(item.get("logical_path") or "")
            if not key or key in seen:
                continue
            seen.add(key)
            retrieved_items.append(
                RetrievedItem(
                    asset_id=aid,
                    logical_path=item.get("logical_path"),
                    score=float(item.get("score", 0.0)),
                    preview_url=item.get("preview_url"),
                    source_type=item.get("source_type", "search_result"),
                )
            )

        # 组装送入模型的图片：优先用户图片，空位用检索召回图片（按相关度）补足
        # 用户图片也必须遵守 vLLM 的多模态上限，避免请求在推理端因显存限制失败。
        prompt_images: List[Tuple[str, bytes]] = list(images[: settings.max_prompt_images])
        for name, data, score in sorted(retrieved_images, key=lambda x: x[2], reverse=True):
            if len(prompt_images) >= settings.max_prompt_images:
                break
            prompt_images.append((name, data))
            sources.append(
                SourceItem(
                    source_id=f"retrieved_img_{len(sources)}",
                    source_type="image",
                    relevance_score=score,
                    content_preview=f"[检索图片] {name}",
                )
            )

        # 组装消息：系统提示（任务类型）+ 参考依据 + 图片 + 问题
        user_content: list = []
        if sources:
            evidence = "\n".join(
                f"[来源{i + 1}] ({s.source_type}, 相关度{s.relevance_score:.2f}) "
                f"{_truncate(s.content_preview, settings.max_text_chars)}"
                for i, s in enumerate(sources)
            )
            user_content.append({"type": "text", "text": f"参考信息：\n{evidence}"})
        for filename, content in prompt_images:
            user_content.append(
                {"type": "image_url", "image_url": {"url": _image_to_data_url(filename, content)}}
            )
        user_content.append({"type": "text", "text": question})
        messages = [
            {
                "role": "system",
                "content": build_system_prompt(task_type, mode, has_sources=bool(sources)),
            },
            {"role": "user", "content": user_content},
        ]

        backend = settings.llm_backend.lower()
        if backend == "mock":
            answer = self._mock_answer(question, sources)
            confidence = self._confidence_heuristic(answer, sources)
            elapsed_ms = int((time.time() - start) * 1000)
            backend_name = "mock"
            confidence_source = "mock"
            usage = {}
            request_id = None
            model_name = settings.model_name
        else:
            try:
                result = self.client.chat_completions(
                    messages=messages,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    logprobs=True,
                )
            except VllmClientError as exc:
                logger.error("vLLM generation failed: %s", exc)
                raise

            elapsed_ms = int((time.time() - start) * 1000)
            choices = result.get("choices", [])
            answer = choices[0]["message"]["content"] if choices else ""
            confidence, confidence_source = _compute_confidence(choices)
            if confidence_source == "unavailable":
                confidence = self._confidence_heuristic(answer, sources)
            usage = result.get("usage", {})
            request_id = result.get("id")
            model_name = result.get("model") or settings.model_name
            backend_name = "vllm"

        # 小参数模型偶尔会在没有依据时虚构“来源”标记；响应层保证引用与 sources 一致。
        if not sources:
            answer = re.sub(r"\s*\[来源\s*\d+\]", "", answer).strip()

        # 回答附带检索内容 ID（RAG：用户可据此自行查看检索内容）
        if retrieved_items:
            ids = ", ".join(str(r.asset_id) for r in retrieved_items if r.asset_id)
            if ids:
                answer = f"{answer}\n\n---\n检索内容ID：{ids}"

        return QAResponse(
            answer=answer,
            confidence=confidence,
            sources=sources,
            processing_time_ms=elapsed_ms,
            timestamp=datetime.now(timezone.utc).isoformat(),
            model=model_name,
            meta=QAMeta(
                model_name=model_name,
                confidence_source=confidence_source,
                image_count=len(prompt_images),
                text_count=len(texts),
                prompt_tokens=usage.get("prompt_tokens", 0),
                completion_tokens=usage.get("completion_tokens", 0),
                total_tokens=usage.get("total_tokens", 0),
                request_id=request_id,
                retrieval_type=plan.retrieval_type,
                intent_analysis_used=plan.intent_analysis_used,
                intent_confidence=plan.confidence,
                intent_reason=plan.reason,
            ),
            task_type=task_type,
            retrieval_mode=mode,
            backend=backend_name,
            notice=notice,
            retrieved=retrieved_items,
        )


service = QaService()
