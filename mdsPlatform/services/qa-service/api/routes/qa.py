"""QA request parsing, bounded queue and deadline coordination."""
import asyncio
import json
from time import monotonic
from uuid import UUID

from fastapi import APIRouter, HTTPException, Request
from starlette.datastructures import UploadFile
from starlette.concurrency import run_in_threadpool

from adapters.vllm_client import VllmClientError
from core.request_budget import ACTIVE_PROFILE, CAPABILITIES, PROFILES, BudgetExceeded, RequestBudget, use_budget
from core.worker_authority import AuthorityError, ScopeSet, current_guard, using_guard
from schemas.qa_req import QaRequest
from schemas.qa_resp import QAResponse
from services.evidence_trace import TraceStorageError
from services.qa_service import QaService, service


router = APIRouter()
runtime = PROFILES[ACTIVE_PROFILE]
slots = asyncio.Semaphore(runtime['maxConcurrentRequests'])
maximum_inflight = runtime['maxConcurrentRequests'] + runtime['maxQueuedRequests']
inflight = 0
requests_by_id = {}


def request_key(request_id):
    scope = current_guard().scope
    return scope['userId'], scope['orgId'], scope['sessionId'], request_id


def public_error(exc):
    import requests

    if isinstance(exc, BudgetExceeded):
        return HTTPException(status_code=exc.status, detail=exc.code + ': ' + str(exc))
    if isinstance(exc, AuthorityError):
        return HTTPException(status_code=exc.status, detail=exc.code)
    if isinstance(exc, TraceStorageError):
        return HTTPException(status_code=503, detail='EVIDENCE_TRACE_UNAVAILABLE: 本次证据记录未能保存。')
    if isinstance(exc, VllmClientError):
        failures = {
            'unavailable': (503, '模型服务暂不可用，请稍后重试。'),
            'timeout': (504, '模型处理超时，请稍后重试。'),
            'busy': (503, '模型服务繁忙，请稍后重试。'),
            'configuration': (503, '模型服务配置异常，请联系管理员。'),
            'request_rejected': (502, '模型未接受本次请求，请调整资料范围后重试。'),
            'invalid_response': (502, '模型服务返回了无效响应，请稍后重试。'),
        }
        status, message = failures.get(exc.category, failures['unavailable'])
        return HTTPException(status_code=status, detail=message)
    if isinstance(exc, requests.RequestException):
        if isinstance(exc, requests.Timeout):
            return HTTPException(status_code=504, detail='证据读取超时。')
        status = exc.response.status_code if exc.response is not None else 503
        errors = {
            400: '读取范围或字段参数无效。',
            401: '账号或数据范围授权失效。',
            403: '账号或数据范围授权失效。',
            404: '冻结来源或所选字段不存在。',
            409: '当前索引尚未具备所需能力。',
            413: '原生读取内容超限，请缩小行列或子树。',
            422: '所选读取或源值状态不支持当前操作。',
        }
        return HTTPException(status_code=status if status in errors else 503,
                             detail=errors.get(status, '检索服务暂不可用，请稍后重试。'))
    if isinstance(exc, ValueError):
        from pydantic import ValidationError
        if isinstance(exc, ValidationError):
            fields = ', '.join('.'.join(str(value) for value in item['loc'])
                               for item in exc.errors(include_input=False)[:6])
            return HTTPException(status_code=422, detail='问答请求字段不符合要求。' + fields)
        return HTTPException(status_code=400, detail=str(exc))
    return HTTPException(status_code=500, detail='本次问答未完成。')


def parse_texts(raw):
    if not raw:
        return []
    try:
        decoded = json.loads(str(raw))
    except ValueError:
        return [str(raw)]
    return [str(item) for item in decoded] if isinstance(decoded, list) else [str(decoded)]


async def parse_request(request):
    if 'application/json' in request.headers.get('content-type', '').lower():
        body = await request.json()
        if not isinstance(body,dict):
            raise HTTPException(status_code=400,detail='问答请求必须是对象。')
        credentials = body.pop('scope_credentials',None)
        return QaRequest.model_validate(body), [], credentials

    form = await request.form(max_files=CAPABILITIES['maxImageCount'], max_fields=64)
    try:
        parts = form.getlist('images')
        if any(not isinstance(part, UploadFile) for part in parts):
            raise HTTPException(status_code=400, detail='images 必须是有效的图片文件。')
        images = []
        total = 0
        for part in parts:
            limit = min(CAPABILITIES['maxImageBytes'], CAPABILITIES['maxAttachmentBytes'] - total)
            content = await part.read(limit + 1)
            if not content:
                raise HTTPException(status_code=400, detail='图片附件为空。')
            if len(content) > limit:
                raise HTTPException(status_code=413, detail='图片或全部附件超过当前字节上限。')
            total += len(content)
            images.append((part.filename or 'upload.png', content))
        fields = dict(form)
        fields.pop('images', None)
        fields['texts'] = parse_texts(fields.get('texts'))
        for key in ('priorQuestions', 'prior_questions'):
            if key in fields:
                fields[key] = json.loads(fields[key] or '[]')
        for key in ('queryPlan', 'query_plan'):
            if key in fields:
                fields[key] = json.loads(fields[key] or 'null')
        for key in ('scopes','scope_credentials'):
            if len(form.getlist(key))>1:
                raise HTTPException(status_code=400,detail='数据范围字段重复。')
            if key in fields:
                fields[key] = json.loads(fields[key])
        credentials = fields.pop('scope_credentials',None)
        return QaRequest.model_validate({key: value for key, value in fields.items() if value != ''}), images, credentials
    finally:
        await form.close()


def response_or_error(task):
    if not task.cancelled():
        task.exception()


async def execute(request, req, images, budget, credentials):
    global inflight
    if inflight >= maximum_inflight:
        raise BudgetExceeded('QA_QUEUE_FULL', '当前问答队列已满，请稍后重试。', 429)
    identifier = request.headers.get('X-MDS-QA-Request-Id')
    key = request_key(UUID(identifier)) if identifier else None
    if key is not None:
        if key in requests_by_id:
            raise BudgetExceeded('QA_REQUEST_ALREADY_RUNNING', '该问答请求正在处理。', 409)
        requests_by_id[key] = budget
    inflight += 1
    queued = monotonic()

    async def process():
        global inflight
        acquired = False
        try:
            while not acquired:
                try:
                    await asyncio.wait_for(slots.acquire(), timeout=min(0.25, budget.remaining()))
                    acquired = True
                except asyncio.TimeoutError:
                    pass
            budget.remaining()
            budget.stage = 'validating'
            queued_ms = round((monotonic() - queued) * 1000, 3)
            authorization = request.headers.get('authorization', '')
            params = req.model_dump(exclude={'profile', 'query_plan','scopes'})
            params['query_plan'] = req.query_plan.model_dump(by_alias=True) if req.query_plan else None
            params['max_tokens'] = budget.output_tokens(req.max_tokens)
            scopes = [scope.coordinates() for scope in (req.scopes or [])]
            guard = current_guard()
            if len(scopes)>1:
                guard = await run_in_threadpool(ScopeSet,guard,scopes,credentials)
                params['scope_set'] = guard
            elif credentials is not None:
                raise AuthorityError(403,'UNEXPECTED_MULTI_SCOPE_CREDENTIALS')
            elif scopes:
                params.update(zip(('dataset_id','version_id','index_version_id'),scopes[0]))
            request_service = QaService(client=service.client, bearer_token=None if len(scopes)>1 else authorization[7:].strip())
            with use_budget(budget):
                with using_guard(guard):
                    answer_started = monotonic()
                    result = await run_in_threadpool(request_service.answer, images=images, **params)
                    if len(scopes)>1:
                        # Verify every frozen credential at the delivery
                        # boundary, after both generation and trace persistence.
                        await run_in_threadpool(guard.revalidate,True)
                        result.processing_time_ms = int((monotonic() - answer_started) * 1000)
                budget.remaining()
            result.meta.profile = budget.name
            result.meta.queue_ms = queued_ms
            return result
        finally:
            if acquired:
                slots.release()
            inflight -= 1
            if key is not None:
                requests_by_id.pop(key, None)

    task = asyncio.create_task(process())
    task.add_done_callback(response_or_error)
    try:
        while not task.done():
            await asyncio.wait({task}, timeout=min(0.25, budget.remaining()))
            if not task.done() and await request.is_disconnected():
                budget.cancelled.set()
                budget.remaining()
        return task.result()
    finally:
        if not task.done():
            budget.cancelled.set()


@router.post('/qa', response_model=QAResponse)
async def ask_question(request: Request):
    try:
        req, images, credentials = await parse_request(request)
        deadline = request.headers.get('X-MDS-QA-Deadline-Ms')
        budget = RequestBudget(req.profile, int(deadline) if deadline is not None else None)
        budget.output_tokens(req.max_tokens)
        return await execute(request, req, images, budget, credentials)
    except HTTPException:
        raise
    except Exception as exc:
        raise public_error(exc) from exc


@router.get('/qa/requests/{request_id}')
async def request_progress(request_id: UUID):
    budget = requests_by_id.get(request_key(request_id))
    if budget is None:
        raise HTTPException(status_code=404, detail='问答请求不存在或已经结束。')
    return {'stage': budget.stage, 'cancelled': budget.cancelled.is_set()}


@router.delete('/qa/requests/{request_id}')
async def cancel_request(request_id: UUID):
    budget = requests_by_id.get(request_key(request_id))
    if budget is None:
        raise HTTPException(status_code=404, detail='问答请求不存在或已经结束。')
    budget.cancelled.set()
    return {'cancelRequested': True}
