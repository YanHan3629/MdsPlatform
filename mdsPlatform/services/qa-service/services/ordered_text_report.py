"""Generate bounded, verifiable segments of an explicitly ordered text report."""
import hashlib
import re

from services.business_answer_guard import CITATION


PURPOSE = 'ordered_text_report'
_MIN_ROWS = 241
_TARGET_SEGMENT_ROWS = 180
_MAX_CALLS = 10
_ID = r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}'
_INPUT_ROW = re.compile(rf'^({_ID})[ ]*(\||\t)[ ]*\S.*$')
_OUTPUT_ROW = re.compile(rf'^({_ID})[ ]*([:：|\t])[ ]*(\S.*)$')
_REPORT_INTENT = (
    r'每条.{0,12}都|全部.{0,10}(?:记录|条目)|不(?:要|得|能).{0,14}(?:省略|遗漏)|every (?:row|record)|omit no (?:row|record)',
    r'原顺序|原有顺序|原来的顺序|保持顺序|原始顺序|original order|same order',
    r'保留.{0,12}(?:编号|标识|主键|\bID\b)|keep.{0,20}\bIDs?\b|preserve.{0,20}\bIDs?\b',
    r'一行|单行|每行|one line|single line|per line',
)
_SUBSET_INTENT = re.compile(
    r'仅(?:处理|输出|保留)|只(?:处理|输出|保留)|筛选|过滤|跳过|排除|'
    r'\b(?:only (?:process|output|keep)|filter|skip|exclude)\b', re.I)


def row_ids(question, source):
    if source.source_type != 'user_text' or not source.model_context:
        return ()
    if _SUBSET_INTENT.search(question) or not all(re.search(pattern, question, re.I) for pattern in _REPORT_INTENT):
        return ()
    lines = [line.strip() for line in source.model_context.splitlines() if line.strip()]
    if len(lines) < _MIN_ROWS:
        return ()
    rows = [_INPUT_ROW.fullmatch(line) for line in lines]
    if not all(rows) or len({row[2] for row in rows}) != 1:
        return ()
    ids = tuple(row[1] for row in rows)
    return ids if len(set(ids)) == len(ids) else ()


def _end(ids, offset):
    segments = min(_MAX_CALLS - 2, (len(ids) + _TARGET_SEGMENT_ROWS - 1) // _TARGET_SEGMENT_ROWS)
    size = (len(ids) + segments - 1) // segments
    return min(len(ids), (offset // size + 1) * size)


def _instruction(ids, offset, end):
    return (
        f'本次只交付原问题所要求报告的第 {offset + 1} 至 {end} 条，'
        f'从编号 {ids[offset]} 开始，到编号 {ids[end - 1]}（含）结束。'
        f'严格按资料顺序输出这 {end - offset} 行。每行保留原编号、用户要求的内容和 [来源1] 引用。'
        '不要加标题、编号前缀、代码块或说明。'
    )


def _prefix(choice, ids):
    """Resume only after a proven prefix. The only discardable line is an unfinished tail."""
    raw = choice.get('message', {}).get('content')
    if (choice.get('finish_reason') not in {'stop', 'length'}
            or choice.get('stop_reason') == 'repetition_detected' or not isinstance(raw, str)):
        return []
    lines = raw.strip().splitlines()
    if len(lines) > len(ids):
        return []
    for index, line in enumerate(lines):
        row = _OUTPUT_ROW.fullmatch(line.strip())
        if not row or row[1] != ids[index]:
            if index == len(lines) - 1 and choice['finish_reason'] == 'length' and ids[index].startswith(line.strip()):
                return lines[:index]
            return []
        if (CITATION.findall(row[3]) != ['1'] or not row[3].endswith('[来源1]')
                or not CITATION.sub('', row[3]).strip()):
            return lines[:index] if index == len(lines) - 1 else []
    return lines


def _tokens(response, limit):
    count = (response.get('usage') or {}).get('completion_tokens')
    return count if type(count) is int and 0 < count <= limit else 0


def generate(client, messages, ids, budget, output_tokens, options, trace):
    offset = used = 0
    segments = []
    result = {}
    reason = 'model_call_limit'
    for _ in range(_MAX_CALLS):
        if not output_tokens - used:
            reason = 'output_budget_exhausted'
            break
        if budget.remaining() < 5:
            reason = 'insufficient_time'
            break
        end = _end(ids, offset)
        user = messages[-1]
        directive = {'type': 'text', 'text': _instruction(ids, offset, end)}
        prompt = [*messages[:-1], {**user, 'content': [*user['content'], directive]}]
        tokens = client.tokenize_messages(prompt)
        context_limit = min(budget.profile['contextTokens'], int(tokens.get('max_model_len') or budget.profile['contextTokens']))
        limit = min(output_tokens - used, context_limit - int(tokens['count']))
        if limit < 1:
            reason = 'context_budget_exhausted'
            break
        result = client.chat_completions(messages=prompt, max_tokens=limit, purpose=PURPOSE, **options)
        choices = result.get('choices') or []
        count = _tokens(result, limit)
        if not count:
            reason = 'unverified_output_usage'
            break
        used += count
        prefix = _prefix(choices[0], ids[offset:end]) if len(choices) == 1 else []
        if not prefix:
            reason = 'ordered_prefix_not_verified'
            break
        segments.extend(prefix)
        offset += len(prefix)
        if offset == len(ids):
            reason = 'complete' if choices[0]['finish_reason'] == 'stop' else 'final_segment_truncated'
            break
    state = {'expectedRows': len(ids), 'verifiedRows': offset, 'status': reason}
    trace.event('orderedTextReport', state)
    return result, '\n'.join(segments) if reason == 'complete' else '', state


def verify(ids, trace):
    """Reconstruct the ordered reply solely from recorded requests and responses."""
    def require(condition):
        if not condition:
            raise ValueError('ORDERED_TEXT_REPORT_MODEL_LINEAGE_UNCONFIRMED')

    def sha(text):
        return hashlib.sha256(text.encode()).hexdigest()

    budgets = [entry['value']['outputReserved'] for entry in trace.data['events'] if entry['kind'] == 'requestBudget']
    calls = trace.data['modelCalls']
    require(ids and len(budgets) == 1 and 1 <= len(calls) <= _MAX_CALLS)
    remaining, = budgets
    offset = 0
    text = []
    segments = []
    base_messages = None
    for call in calls:
        require(offset < len(ids) and call.get('purpose') == PURPOSE and call.get('status') == 'response_received')
        request = call['request']
        response = call['response']
        messages = request['messages']
        require(len(messages) == 2 and messages[0]['role'] == 'system' and messages[1]['role'] == 'user')
        parts = messages[1]['content']
        end = _end(ids, offset)
        require(len(parts) >= 3 and parts[-2]['textSha256'] == trace.data['questionSha256']
                and parts[-1]['textSha256'] == sha(_instruction(ids, offset, end)))
        unchanged = ({key: value for key, value in request.items() if key not in {'messages', 'max_tokens'}},
                     messages[0], parts[:-1])
        require(base_messages is None or unchanged == base_messages)
        base_messages = unchanged
        limit = request['max_tokens']
        count = _tokens(response, limit)
        choices = response['choices']
        prefix = _prefix(choices[0], ids[offset:end]) if len(choices) == 1 else []
        require(request['model'] == response.get('model') and response.get('id')
                and 0 < count <= limit <= remaining and prefix)
        segments.append({'modelCallId': call['callId'], 'modelRequestBodySha256': call['requestBodySha256'],
                         'modelResponseTextSha256': sha(choices[0]['message']['content']),
                         'rowStart': offset + 1, 'rowEnd': offset + len(prefix)})
        text.extend(prefix)
        offset += len(prefix)
        remaining -= count
    require(offset == len(ids) and calls[-1]['response']['choices'][0]['finish_reason'] == 'stop')
    return '\n'.join(text), segments
