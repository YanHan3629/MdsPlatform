"""Present a verified deadline and preserve the limits of an order amount review."""
from hashlib import sha256
from decimal import Decimal, InvalidOperation
import json
import re
from uuid import NAMESPACE_URL, uuid5

from services.answer_lineage import canonical, require
from services.business_answer_guard import CITATION, permits_cited_blocks, validate
from services.business_records import (
    _number_input, _order_line_amount_unit_scope, _order_line_deadline_calculations,
)
from services.read_receipt import verify

VERSION = 'order-line-reconciliation-1'
SCHEMA = {
    'type': 'object',
    'properties': {
        'deadline_relation': {'type': 'string', 'enum': ['before', 'at', 'after']},
        'deadline_explanation': {'type': 'string'},
        'evidence_needed': {'type': 'string'},
    },
    'required': ['deadline_relation', 'deadline_explanation', 'evidence_needed'],
    'additionalProperties': False,
}
INSTRUCTION = (
    '\n填写 deadline_relation、deadline_explanation 和 evidence_needed。'
    'deadline_relation 根据同一订单行的末次签收和截止时间选择 before、at 或 after。'
    'deadline_explanation 说明比较依据。系统会列出两个时刻和核验结果，解释中可省略这些数值。'
    'evidence_needed 说明应核对哪份记录及哪个计量条件。当前金额是原表登记值，'
    '存在 order_line_amount_check 时，说明本行数量、单位和单价的核对依据，'
    '不存在时说明尚缺的核对条件。字段单位以“由记录”开头时是取值规则，本行单位列提供实际单位。'
    '引用原生订单行，不推断付款状态。'
)


def _basis(source):
    bundle = source.evidence_bundle
    receipt = verify(bundle)
    rows = bundle.get('content', {}).get('records', [])
    operation = receipt['operation']
    if (operation.get('operation') != 'read_table' or len(rows) != 1
            or operation.get('rowStart') != rows[0]['rawRow'] or operation.get('rowEnd') != rows[0]['rawRow']):
        return None
    row = rows[0]
    context = json.loads(source.model_context or '{}')
    amounts = _number_input('amount_cny', row, [bundle])
    limit = _order_line_amount_unit_scope(row, bundle)
    calculations = _order_line_deadline_calculations(row, bundle)
    timing = [value for value in calculations if value['operation'] == 'deadline_time_difference']
    products = [value for value in calculations if value['operation'] == 'order_line_amount_check']
    if (not isinstance(context, dict) or not amounts or amounts[2] not in {None, 'CNY', '元', '人民币'}
            or context.get('orderAmountRecalculationLimit') != limit
            or len(timing) != 1 or len(products) > 1 or (not limit and not products)
            or calculations != context.get('verifiedCalculations')):
        return None
    time, = timing
    product = products[0] if products else None
    expected = {i['factId'] for value in calculations for i in value['inputs']} | {amounts[1]['factId']}
    if product:
        unit_facts = [fact for fact in bundle['facts'] if fact['field'] == 'unit'
                      and fact['value'] == row['values'].get('unit')
                      and fact.get('locator', {}).get('rawRow') == row['rawRow']]
        if len(unit_facts) != 1:
            return None
        expected.add(unit_facts[0]['factId'])
    if limit:
        expected.update((limit['quantityFactId'], limit['rowUnitFactId']))
    if not expected <= set(source.fact_ids):
        return None
    facts = {fact['factId']: fact for fact in bundle['facts'] if fact['factId'] in expected}
    if len(facts) != len(expected):
        return None
    units = ((facts[limit['quantityFactId']]['unit'], facts[limit['rowUnitFactId']]['value']) if limit
             else (row['values'].get('unit'),))
    if any(not isinstance(unit, str) or not unit.strip() or re.search(r'[\r\n\[\]]', unit) for unit in units):
        return None
    projected = [fact for fact in context.get('facts', []) if isinstance(fact, dict) and fact.get('factId') in expected]
    if len(projected) != len(expected) or {fact['factId'] for fact in projected} != expected:
        return None
    projected_by_id = {fact['factId']: fact for fact in projected}
    for fact_id, fact in facts.items():
        projected_fact = projected_by_id[fact_id]
        value = projected_fact.get('value')
        number = _number_input(fact['field'], row, [bundle])
        if isinstance(value, dict):
            value = value.get('sourceNumber')
        try:
            matches = Decimal(str(value)) == number[0] if number else value == fact['value']
        except InvalidOperation:
            return None
        if not matches or projected_fact.get('unit') != fact.get('unit'):
            return None
    return amounts[1], time, limit, facts, product


def review_basis(question, sources, image_parts, status, prior_questions):
    if (len(sources) != 1 or status != 'OK' or image_parts or prior_questions
            or not sources[0].provenance.get('orderLineAmountDeadlineReview')
            or not sources[0].provenance.get('verifiedBusinessRecord') or not permits_cited_blocks(question)):
        return None
    try:
        return _basis(sources[0])
    except (KeyError, TypeError, ValueError):
        return None


def _model_fields(raw, reason):
    if reason != 'stop' or not raw.lstrip().startswith('{'):
        return {}
    try:
        pairs = json.loads(raw, object_pairs_hook=list)
        if not isinstance(pairs, list) or len(pairs) != len(SCHEMA['required']):
            return {}
        fields = dict(pairs)
    except (TypeError, ValueError):
        return {}
    if fields.keys() != set(SCHEMA['required']) or not all(isinstance(value, str) for value in fields.values()):
        return {}
    return fields


def _explanation(value, source, kind, direction=None):
    value = value.strip()
    if not value:
        return None
    if kind == 'deadline':
        if not re.search(r'签收|截止|交期', value) or re.search(r'金额|付款|应付|乘积', value):
            return None
        positive = re.sub(r'(?:未|没有|不算|不属于)(?:发生)?(?:逾期|超期)', '', value)
        if ((direction != 'after' and re.search(r'晚于|迟于|逾期|超期', positive))
                or (direction == 'after' and re.search(r'提前|早于|按时|如期', positive))
                or (direction == 'at' and re.search(r'提前|早于', positive))):
            return None
    elif (not re.search(r'单位|口径', value) or not re.search(r'核对|复核|确认|核实|补充|提供|查阅', value)
          or re.search(r'已付|已支付|已到账', value)):
        return None
    rendered = value if CITATION.search(value) else f'{value} [来源1]'
    return rendered if validate(rendered, [source])['accepted'] else None


def finish(question, result, source, basis, trace):
    from services.prose_answer_lineage import _call, _model_slots, _native_link

    call = _call(result, trace, allow_truncated=True)
    question_sha256 = sha256(question.encode()).hexdigest()
    user = call['request']['messages'][-1]
    require(trace.data.get('questionSha256') == question_sha256 and user.get('role') == 'user'
            and user['content'][-1].get('textSha256') == question_sha256, 'ORDER_REVIEW_QUESTION_CHANGED')
    require(call['request'].get('structured_outputs') == {'json': SCHEMA}, 'ORDER_REVIEW_SCHEMA_CHANGED')
    require(_model_slots([source], call) == ({1: 1}, {1: 1}), 'ORDER_REVIEW_SOURCE_ORDER_CHANGED')
    link = _native_link(source, trace, call)
    require(_basis(source) == basis, 'ORDER_REVIEW_SOURCE_CHANGED')
    amount, timing, limit, facts, product = basis
    timestamps = {entry['field']: entry['value'] for entry in timing['inputs']}
    delta = Decimal(timing['exactValue'])
    direction = 'before' if delta > 0 else 'after' if delta < 0 else 'at'
    deadline = {
        'before': f'提前 {timing["displayValue"]}，按时。',
        'after': f'超过截止 {timing["displayValue"]}，逾期。',
        'at': '恰在截止时刻签收，按时。',
    }[direction]
    raw = result['choices'][0]['message']['content']
    fields = _model_fields(raw, result['choices'][0]['finish_reason'])
    deadline_reason = (_explanation(fields['deadline_explanation'], source, 'deadline', direction)
                       if fields.get('deadline_relation') == direction else None)
    needed = _explanation(fields.get('evidence_needed', ''), source, 'amount')
    deadline_text = f'末次签收 {timestamps["last_signed_at"]}，截止 {timestamps["due_at"]}。{deadline} [来源1]'
    amount_text = f'订单行登记金额 {amount["value"]} CNY。 [来源1]'
    parts = [
        ('registered_operation', deadline_text, [i['factId'] for i in timing['inputs']], timing),
        ('source_value', amount_text, [amount['factId']], None),
    ]
    if product:
        inputs = {item['field']: item for item in product['inputs']}
        unit = source.evidence_bundle['content']['records'][0]['values']['unit']
        comparison = '与登记行金额一致' if product['matchesRecordedAmount'] else '与登记行金额不一致'
        text = (f'按原表本行单位「{unit}」核算：{inputs["quantity"]["value"]} × '
                f'{inputs["unit_price_cny"]["value"]} = {product["exactValue"]} CNY，{comparison}。 [来源1]')
        fact_ids = sorted({item['factId'] for item in product['inputs']}
                          | {fact_id for fact_id, fact in facts.items() if fact['field'] == 'unit'})
        parts.append(('registered_operation', text, fact_ids, product))
    elif limit:
        unit_field = facts[limit['quantityFactId']]['unit']
        row_unit = facts[limit['rowUnitFactId']]['value']
        unit_text = f'数量的字段登记单位为「{unit_field}」，原表行单位为「{row_unit}」。核对计量口径后才能复算。 [来源1]'
        parts.append(('source_comparison', unit_text, [limit['quantityFactId'], limit['rowUnitFactId']], None))
    if deadline_reason:
        parts.append(('source_interpretation', deadline_reason, list(source.fact_ids), None))
    if needed:
        parts.append(('source_interpretation', needed, list(source.fact_ids), None))
    answer, claims = '', []
    for kind, text, fact_ids, operation in parts:
        if answer:
            answer += '\n\n'
        start = len(answer)
        answer += text
        anchor = dict(link, sourceIndex=1, modelSourceIndex=1, factIds=fact_ids)
        claim = dict(claimId=str(uuid5(NAMESPACE_URL, canonical([VERSION, start, text, anchor]))), kind=kind,
                     text=text, answerStart=start, answerEnd=len(answer), offsetUnit='unicode-code-points',
                     sourceIndices=[1], evidenceIds=[source.evidence_id], factIds=fact_ids,
                     readIds=link['readIds'], inputLinks=[anchor])
        if operation:
            claim['operation'] = operation
        if kind == 'source_interpretation':
            claim['modelResponseTextSha256'] = sha256(raw.encode()).hexdigest()
        claims.append(claim)
    complete = bool(deadline_reason and needed)
    presentation = dict(version=VERSION, answer=answer, claims=claims,
                        generationStatus='source_interpretation_unverified' if complete else 'partial',
                        modelInterpretationVerified=False,
                        modelDeadlineExplanationDelivered=bool(deadline_reason), modelEvidenceNeededDelivered=bool(needed))
    trace.event('orderLineReviewPresentation', presentation)
    return presentation
