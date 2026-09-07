import json
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

from core.config import settings


class VllmClientError(RuntimeError):
    """vLLM 调用异常。"""


class VllmClient:
    """基于标准库的 vLLM OpenAI 兼容客户端（参考 Multimodel-DataSpace-Platform 实现）。"""

    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        timeout: Optional[int] = None,
    ):
        self.base_url = (base_url or settings.vllm_base_url).rstrip("/")
        self.api_key = api_key if api_key is not None else settings.api_key
        self.timeout = timeout if timeout is not None else settings.request_timeout_seconds

    def _request(
        self, method: str, path: str, payload: Optional[dict] = None, timeout: Optional[int] = None
    ) -> dict:
        url = self.base_url + path
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", errors="replace")
            raise VllmClientError(f"vLLM HTTP {e.code}: {detail}") from e
        except urllib.error.URLError as e:
            raise VllmClientError(f"无法连接 vLLM({url}): {e.reason}") from e
        except Exception as e:
            raise VllmClientError(f"调用 vLLM 失败: {e}") from e

    def list_models(self) -> List[dict]:
        """查询 vLLM 已加载的模型列表。"""
        data = self._request("GET", "/v1/models", timeout=10)
        return data.get("data", [])

    def chat_completions(
        self,
        messages: List[dict],
        model: Optional[str] = None,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
        top_p: Optional[float] = None,
        top_k: Optional[int] = None,
        presence_penalty: Optional[float] = None,
        logprobs: bool = True,
        response_format: Optional[dict] = None,
    ) -> dict:
        """调用 vLLM 的 /v1/chat/completions（OpenAI 兼容格式）。"""
        payload: Dict[str, Any] = {
            "model": model or settings.model_name,
            "messages": messages,
            "max_tokens": max_tokens if max_tokens is not None else settings.default_max_tokens,
            "temperature": temperature if temperature is not None else settings.default_temperature,
            "top_p": top_p if top_p is not None else settings.default_top_p,
            "presence_penalty": (
                presence_penalty if presence_penalty is not None else settings.default_presence_penalty
            ),
            "top_k": top_k if top_k is not None else settings.default_top_k,
            "logprobs": logprobs,
        }
        if response_format is not None:
            payload["response_format"] = response_format
        return self._request("POST", "/v1/chat/completions", payload)


client = VllmClient()
