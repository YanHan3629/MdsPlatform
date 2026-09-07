import json
import re
from dataclasses import dataclass
from typing import Literal

from adapters.vllm_client import VllmClient


RetrievalType = Literal["none", "text_to_image", "image_to_text", "dual"]


@dataclass(frozen=True)
class RetrievalIntent:
    retrieval_type: RetrievalType
    text_query: str
    use_uploaded_images: bool
    reason: str
    confidence: float


class IntentAnalyzer:
    """使用与问答相同的 vLLM 模型处理无法由规则确定的检索意图。"""

    _SYSTEM_PROMPT = """你是多模态检索路由器，只判断路由，不回答问题。
retrieval_type 只能是 none、text_to_image、image_to_text、dual。
输入中的 image_count 是可信事实，大于 0 表示已上传图片。
只输出单行 JSON，且仅含 retrieval_type、text_query、confidence 三个字段；
text_query 最多 40 字，confidence 为 0 到 1。"""

    def __init__(self, client: VllmClient):
        self.client = client

    @staticmethod
    def _parse_json(content: str) -> dict:
        text = (content or "").strip()
        fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S | re.I)
        if fenced:
            text = fenced.group(1)
        else:
            start, end = text.find("{"), text.rfind("}")
            if start < 0 or end <= start:
                raise ValueError("意图模型未返回 JSON")
            text = text[start : end + 1]
        data = json.loads(text)
        if not isinstance(data, dict):
            raise ValueError("意图模型返回值不是 JSON 对象")
        return data

    @staticmethod
    def _as_bool(value, default: bool) -> bool:
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            normalized = value.strip().lower()
            if normalized in {"true", "1", "yes"}:
                return True
            if normalized in {"false", "0", "no"}:
                return False
        return default

    def analyze(
        self,
        *,
        question: str,
        input_mode: str,
        image_count: int,
        text_count: int,
        has_context: bool,
    ) -> RetrievalIntent:
        payload = {
            "question": question,
            "input_mode": input_mode,
            "image_count": image_count,
            "text_count": text_count,
            "has_context": has_context,
            "available_retrieval_types": ["none", "text_to_image", "image_to_text", "dual"],
        }
        result = self.client.chat_completions(
            messages=[
                {"role": "system", "content": self._SYSTEM_PROMPT},
                {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
            ],
            max_tokens=128,
            temperature=0.0,
            top_p=1.0,
            top_k=1,
            presence_penalty=0.0,
            logprobs=False,
            response_format={"type": "json_object"},
        )
        choices = result.get("choices") or []
        content = choices[0].get("message", {}).get("content", "") if choices else ""
        data = self._parse_json(content)
        retrieval_type = str(data.get("retrieval_type") or "").strip().lower()
        if retrieval_type not in {"none", "text_to_image", "image_to_text", "dual"}:
            raise ValueError(f"未知检索类型: {retrieval_type}")
        try:
            confidence = max(0.0, min(1.0, float(data.get("confidence", 0.0))))
        except (TypeError, ValueError):
            confidence = 0.0
        return RetrievalIntent(
            retrieval_type=retrieval_type,
            text_query=str(data.get("text_query") or question).strip() or question,
            use_uploaded_images=self._as_bool(
                data.get("use_uploaded_images"), image_count > 0
            ),
            reason="模型意图分析",
            confidence=confidence,
        )
