import json
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

from core.config import settings
from core.request_budget import CAPABILITIES, BudgetExceeded, max_image_pixels, timeout_seconds


class VllmClientError(RuntimeError):
    """Typed upstream fault; public routes expose a bounded message only."""
    def __init__(self,message,*,category="unavailable",upstream_status=None):
        super().__init__(message)
        self.category=category
        self.upstream_status=upstream_status


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
        self, method: str, path: str, payload: Optional[dict] = None, timeout: Optional[int] = None, *,
        purpose="answer", expect_json: bool = True
    ) -> dict:
        from core.worker_authority import current_guard,AuthorityError
        protected=payload is not None and path in {"/tokenize","/v1/chat/completions"}
        if protected:current_guard().revalidate(force=True)
        url = self.base_url + path
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        from services.evidence_trace import current,TraceStorageError
        trace=current();observed=trace.begin(path,payload,data,purpose=purpose) if trace and payload is not None and path in {'/tokenize','/v1/chat/completions'} else None
        if observed:headers['X-Request-ID']=observed['callId']
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            limit = timeout_seconds(timeout or self.timeout)
            if protected:
                from adapters.model_http import post_json
                result = post_json(url, data, headers, limit)
                current_guard().revalidate(force=True)
            else:
                with urllib.request.urlopen(req, timeout=limit) as response:
                    if expect_json:
                        result = json.loads(response.read().decode("utf-8"))
                    else:
                        if response.status != 200:
                            raise ValueError(f"Unexpected vLLM health status {response.status}")
                        result = {}
            if observed:trace.end(observed,result)
            return result
        except urllib.error.HTTPError as e:
            if observed:trace.end(observed,error=e)
            detail = e.read().decode("utf-8", errors="replace")
            category=("configuration" if e.code in {401,403,404} else "request_rejected" if e.code in {400,413,422}
                else "busy" if e.code==429 else "timeout" if e.code==504 else "unavailable")
            raise VllmClientError(f"vLLM HTTP {e.code}: {detail}",category=category,upstream_status=e.code) from e
        except urllib.error.URLError as e:
            if observed:trace.end(observed,error=e)
            category="timeout" if isinstance(e.reason,TimeoutError) else "unavailable"
            raise VllmClientError(f"无法连接 vLLM({url}): {e.reason}",category=category) from e
        except (AuthorityError, BudgetExceeded) as error:
            if observed:trace.end(observed,error=error)
            raise
        except TraceStorageError:
            raise
        except Exception as e:
            if observed:trace.end(observed,error=e)
            category="invalid_response" if isinstance(e,(json.JSONDecodeError,UnicodeDecodeError)) else "timeout" if isinstance(e,TimeoutError) else "unavailable"
            raise VllmClientError(f"调用 vLLM 失败: {e}",category=category) from e

    def check_engine_health(self) -> None:
        """Check the inference engine independently of the model catalogue."""
        self._request("GET", "/health", timeout=3, expect_json=False)

    def list_models(self) -> List[dict]:
        """查询 vLLM 已加载的模型列表。"""
        data = self._request("GET", "/v1/models", timeout=10)
        return data.get("data", [])

    def tokenize_text(self, text):
        return self._request("POST", "/tokenize", {"model": settings.model_name, "prompt": text,
            "add_special_tokens": False})

    def tokenize_messages(self, messages):
        return self._request("POST", "/tokenize", {"model": settings.model_name, "messages": messages,
            "add_generation_prompt": True, "chat_template_kwargs": {"enable_thinking": False},
            "mm_processor_kwargs": {"min_pixels": CAPABILITIES["minImagePixels"], "max_pixels": max_image_pixels()}})

    def chat_completions(
        self,
        messages: List[dict],
        model: Optional[str] = None,
        max_tokens: Optional[int] = None,
        temperature: Optional[float] = None,
        top_p: Optional[float] = None,
        top_k: Optional[int] = None,
        presence_penalty: Optional[float] = None,
        frequency_penalty: Optional[float] = None,
        logprobs: bool = True,
        response_format: Optional[dict] = None,
        structured_outputs: Optional[dict] = None,
        purpose: str = "answer",
        repetition_detection: Optional[dict] = None,
        stop: Optional[List[str]] = None,
        include_stop_str_in_output: bool = False,
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
            "chat_template_kwargs": {"enable_thinking": False},
        }
        if response_format is not None:
            payload["response_format"] = response_format
        if structured_outputs is not None:
            if response_format is not None:raise ValueError('Provide one structured output mechanism per request')
            payload['structured_outputs']=structured_outputs
        if frequency_penalty is not None:
            payload['frequency_penalty']=frequency_penalty
        if repetition_detection is not None:
            payload['repetition_detection'] = repetition_detection
        if stop is not None:
            payload['stop'] = stop
            payload['include_stop_str_in_output'] = include_stop_str_in_output
        if any(isinstance(message.get("content"), list) and any(
            part.get("type") == "image_url" for part in message["content"]
        ) for message in messages):
            payload["mm_processor_kwargs"] = {
                "min_pixels": CAPABILITIES["minImagePixels"],
                "max_pixels": max_image_pixels(),
            }
        return self._request("POST", "/v1/chat/completions", payload, purpose=purpose)


client = VllmClient()
