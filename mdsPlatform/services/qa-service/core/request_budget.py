"""Limits shared by the gateway, QA worker and vLLM launcher."""
from contextlib import contextmanager
from contextvars import ContextVar
from hashlib import sha256
import json
import os
from pathlib import Path
from threading import Event
import time


_raw = (Path(__file__).resolve().parents[1] / 'qa-capabilities.json').read_bytes()
CAPABILITIES = json.loads(_raw)
CAPABILITIES_SHA256 = sha256(_raw).hexdigest()
MAX_DATA_SCOPES = CAPABILITIES['maxDataScopes']
if type(MAX_DATA_SCOPES) is not int or not 1 <= MAX_DATA_SCOPES <= 8:
    raise RuntimeError('Invalid QA scope capacity')
ACTIVE_PROFILE = os.environ.get('MDS_QA_RUNTIME_PROFILE', 'standard')
PROFILES = CAPABILITIES['profiles']
if ACTIVE_PROFILE not in PROFILES:
    raise RuntimeError('Unknown QA runtime profile')
ACTIVE_CONTEXT_TOKENS = PROFILES[ACTIVE_PROFILE]['contextTokens']
ENABLED_PROFILES = {name: profile for name, profile in PROFILES.items()
                    if profile['contextTokens'] <= ACTIVE_CONTEXT_TOKENS}


class BudgetExceeded(RuntimeError):
    def __init__(self, code, message, status=504):
        super().__init__(message)
        self.code = code
        self.status = status


class RequestBudget:
    def __init__(self, name, upstream_deadline_ms=None):
        if name not in ENABLED_PROFILES:
            raise BudgetExceeded('QA_PROFILE_UNAVAILABLE', '当前部署未启用所选问答档位。', 422)
        self.name = name
        self.profile = ENABLED_PROFILES[name]
        self.started = time.monotonic()
        seconds = self.profile['deadlineSeconds']
        if upstream_deadline_ms is not None:
            seconds = min(seconds, upstream_deadline_ms / 1000 - time.time())
        self.deadline = self.started + seconds
        self.cancelled = Event()
        self.stage = 'queued'

    def remaining(self, cap=None):
        if self.cancelled.is_set():
            raise BudgetExceeded('QA_REQUEST_CANCELLED', '问答请求已取消。', 499)
        seconds = self.deadline - time.monotonic()
        if seconds <= 0:
            raise BudgetExceeded('QA_DEADLINE_EXCEEDED', '问答已达到本次总处理时限。')
        return seconds if cap is None else min(seconds, cap)

    def output_tokens(self, requested):
        if requested is None:
            return self.profile['defaultOutputTokens']
        if not 1 <= requested <= self.profile['maxOutputTokens']:
            raise BudgetExceeded('QA_OUTPUT_LIMIT', '回答预算超过当前档位的上限。', 422)
        return requested


_budget = ContextVar('qa_request_budget', default=None)


@contextmanager
def use_budget(budget):
    token = _budget.set(budget)
    try:
        yield
    finally:
        _budget.reset(token)


def current_budget():
    budget = _budget.get()
    if budget is None:
        raise RuntimeError('A QA request budget is required')
    return budget


def timeout_seconds(cap):
    budget = _budget.get()
    return cap if budget is None else budget.remaining(cap)


def max_image_pixels():
    return current_budget().profile['maxImagePixels']


def public_capabilities():
    return {**CAPABILITIES, 'profiles': ENABLED_PROFILES, 'runtimeProfile': ACTIVE_PROFILE,
            'sha256': CAPABILITIES_SHA256}
