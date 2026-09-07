import logging
import re
from dataclasses import dataclass

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


class RetrievalPlanner:
    """先使用确定性规则，仅在 hybrid 图片请求存在歧义时调用意图模型。"""

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
    def _plan(provider, retrieval_type, question, use_images, reason, confidence=1.0, used=False):
        return RetrievalPlan(provider, retrieval_type, question, use_images, reason, confidence, used)

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

        # 新增的 retrievalType 是最明确的路由选择，永远不调用意图模型。
        if retrieval_type != "auto":
            selected = retrieval_type
            if selected in {"image_to_text", "dual"} and not has_images:
                selected = "text_to_image"
                return self._plan(provider, selected, question, False, "未上传图片，降级为文搜图")
            return self._plan(provider, selected, question, has_images, "请求明确指定检索类型")

        # 输入形态已经能唯一确定检索类型时走快路径。
        if input_mode == "question_only" or not has_images:
            return self._plan(provider, "text_to_image", question, False, "仅有文字查询，直接文搜图")

        # 用户自然语言明确表达时也不需要额外模型调用。
        if self._NO_RETRIEVAL.search(question):
            return self._plan("none", "none", question, False, "用户明确要求不检索")
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
            )
        except Exception as exc:
            logger.warning("retrieval intent analysis failed: %s", exc)
            # 意图模型失败时保持旧版行为，优先保证问答可用。
            return self._plan(provider, "text_to_image", question, False, "意图分析失败，回退为文搜图", 0.0, True)
