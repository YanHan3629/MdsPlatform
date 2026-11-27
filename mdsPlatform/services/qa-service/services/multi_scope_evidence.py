"""Coordinate bounded evidence discovery under independently scoped credentials."""
import re
import json
import time
from concurrent.futures import ThreadPoolExecutor
from contextvars import copy_context
from datetime import datetime
from decimal import Decimal, InvalidOperation

from core.request_budget import CAPABILITIES, current_budget
from services.evidence_policy import admit, record_locator, select_evidence
from services.evidence_trace import current
from services.native_evidence_read import read_selected

IDENTIFIER = re.compile(r"(?<![A-Za-z0-9_-])[A-Za-z]{2,12}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_-])")
LINK_FIELDS = {
    'order_line_id','delivery_line_id','sku_id','qc_id','supplier_batch_id',
    'service_contract_id','shipment_id','finished_batch_id','bom_id','manufacturing_batch_id',
}
DIMENSION_FIELDS = {'width_mm':'宽度','depth_mm':'深度','height_mm':'高度'}
PACKAGE_BOX_FIELDS = ('package_id','paired_package_id','delivery_line_id','finished_batch_id','sku_id','component_type','box_count')
SHIPMENT_EVENT_STAGES = {'装车清点完成':'count', '发车':'depart', '到达':'arrive', '客户签收':'sign', '签收':'sign'}
NAMED_RECORD_SOURCE = re.compile(r'\.(?:csv|xlsx|jsonl?|pdf|txt|png|jpe?g|webp|svg|obj|stp|step)\b', re.I)

SINGLE_REVIEW_ROWS = {
    '放行终检': ('FG', (
        ('object_id', {'qc_id', 'measured_at', 'item', 'actual_value', 'quantity_checked'}),
        ('finished_batch_id', {'manufacturing_batch_id', 'qc_id', 'released_at', 'final_good', 'unit'}),
    )),
    '工单合同': ('SVC', (('service_id', {'serial_id', 'customer_id', 'inspection'}),)),
    '包装签收': ('DL', (
        ('delivery_line_id', {'waybill_line_id', 'signed_quantity', 'signed_at', 'unit', 'box_count'}),
        ('delivery_line_id', {'package_id', 'paired_package_id', 'component_type', 'box_count'}),
    )),
    '仓储运输': ('SHIP', (
        ('source_document_id', {'movement_id', 'movement_type', 'quantity_change', 'unit', 'occurred_at'}),
        ('shipment_id', {'waybill_line_id', 'signed_at'}),
    )),
}

REVIEW_FIELDS = (
    (r'签收|交期|收齐|履约', 'order_line_id', '订单承诺', (
        ('order_line_id', '订单行'), ('due_at', '承诺交期'), ('quantity', '承诺数量'),
        ('unit', '承诺单位'), ('sku_id', '型号'))),
    (r'签收|发运|内外机|到齐|收齐|履约', 'waybill_line_id', '运单签收', (
        ('waybill_line_id', '运单行'), ('delivery_line_id', '交付行'), ('order_line_id', '关联订单行'),
        ('signed_quantity', '实签数量'), ('unit', '实签单位'), ('box_count', '箱数'), ('signed_at', '签收时刻'))),
    (r'领料|来料|部件|原材料', 'consumption_id', '领料', (
        ('consumption_id', '领料号'), ('manufacturing_batch_id', '制造批'), ('part_id', '料号'),
        ('supplier_batch_id', '来料批'), ('quantity', '领料量'), ('unit', '单位'), ('consumed_at', '领料时刻'))),
    (r'来料|接收|供货批', 'supplier_batch_id', '来料接收', (
        ('supplier_batch_id', '来料批'), ('part_id', '料号'), ('qc_id', '检验号'),
        ('received_at', '接收时刻'), ('final_state', '最终状态'))),
    (r'放行|终检', 'finished_batch_id', '制造放行', (
        ('finished_batch_id', '成品批'), ('manufacturing_batch_id', '制造批'), ('qc_id', '检验号'),
        ('released_at', '放行时刻'), ('final_good', '放行量'), ('unit', '放行量单位'))),
    (r'检验|质检|终检|测量|限值|合格', 'qc_id', '检验', (
        ('qc_id', '检验号'), ('object_id', '受检对象'), ('measured_at', '原始检验时刻'), ('item', '测项'),
        ('actual_value', '实测值'), ('lower_limit', '下限'), ('upper_limit', '上限'), ('unit', '测量单位'),
        ('quantity_checked', '检查量'), ('quantity_checked_unit', '检查量单位'), ('result', '检验结果'))),
    (r'BOM|用料|部件', 'bom_line_id', '关键物料', (
        ('sku_id', '型号'), ('part_id', '料号'), ('revision', '版本'), ('quantity_per_product', '每台或每套用量'),
        ('coverage_scope', '资料覆盖范围'))),
    (r'箱|内机|外机|配件|包装', 'package_id', '包装组', (
        ('package_id', '包装组号'), ('paired_package_id', '互配组号'), ('set_component_role', '组别'),
        ('box_count', '记录箱数'), ('accessory_part_id', '配件料号'), ('accessory_quantity', '配件数量'))),
    (r'出库|扣减|签收节点|仓储|发车', 'movement_id', '仓储变动', (
        ('movement_id', '变动单号'), ('source_document_id', '关联凭证'), ('movement_type', '变动类型'),
        ('quantity_change', '变动量'), ('unit', '单位'), ('occurred_at', '变动时刻'))),
    (r'出库|扣减|签收节点|仓储|发车', 'event_id', '运输事件', (
        ('event_id', '事件编号'), ('shipment_id', '运输编号'), ('event_type', '事件'), ('timestamp', '事件时刻'))),
    (r'售后|服务|现场|退款', 'service_id', '服务现场', (
        ('service_id', '工单号'), ('service_contract_id', '对应合同'), ('sku_id', '型号'), ('serial_id', '现场设备序列号'),
        ('customer_id', '现场客户'), ('inspection', '现场检查'), ('action', '现场处置'), ('retest', '复测'))),
    (r'售后|服务|现场|退款', 'contract_id', '合同主体', (
        ('contract_id', '合同号'), ('buyer_id', '买方'), ('seller_ids', '签约供方'), ('settlement', '结算约定'))),
)


def _compares_dimensions(question):
    return bool(re.search(r'外形|机身|整机|产品',question) and re.search(r'宽|深|高|尺寸',question)
                and re.search(r'比较|对比|差|哪个|哪台',question) and not re.search(r'包装|箱组|内外机',question))


def _source_row(source, *, allow_single=False):
    bundle = source.evidence_bundle or {}
    if not allow_single and not source.provenance.get('multiScopeSource'):
        return {}, {}
    content = bundle.get('content', {})
    records = content.get('records', [])
    entries = {fact['field']:fact for fact in bundle.get('facts', [])
               if fact['factId'] in source.fact_ids and fact.get('evidenceNature')=='source_record'}
    facts = {field:fact['factId'] for field,fact in entries.items()}
    if len(records)==1:
        values = {}
        for field,value in records[0]['values'].items():
            if field not in entries:
                continue
            fact = entries[field]
            number = fact.get('numberSourceExpression')
            if fact.get('calculationEligible') is False and (number is not None or type(value) in (int,float)):
                continue
            values[field] = value if number is None else number
        return values, facts
    value = content.get('value')
    if isinstance(value,dict) and set(facts)=={'value'}:
        return value, dict.fromkeys(value,facts['value'])
    return {}, {}


def _signed_at(value):
    if not isinstance(value, str):
        raise ValueError('Missing source timestamp')
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.utcoffset() is None:
        raise ValueError('Missing source timezone')
    return result


def _count(value):
    if isinstance(value, bool) or value is None:
        raise ValueError('Missing source quantity')
    amount = Decimal(str(value))
    if not amount.is_finite() or amount < 0:
        raise ValueError('Invalid source quantity')
    return amount


def whole_count(value):
    try:
        amount = _count(value)
    except (InvalidOperation, ValueError):
        return None
    return amount if amount==amount.to_integral_value() else None


def dimension_values(source, row):
    """Read positive millimetre dimensions from one canonical product row."""
    values = {}
    for field in DIMENSION_FIELDS:
        unit = source.units.get(field)
        if unit is not None and (not isinstance(unit, str) or unit.strip().casefold() not in {'mm', '毫米'}):
            return None
        try:
            value = _count(row.get(field))
        except (ValueError, InvalidOperation):
            return None
        if not value:
            return None
        values[field] = value
    return values


def measurement_quantities(source, *, allow_single=False):
    """Bind a recorded measurement and its limits to their own column unit."""
    row, _ = _source_row(source, allow_single=allow_single)
    if not row.get('qc_id') or not row.get('item'):
        return []
    pairs = []
    for field, unit_field in (('actual_value','unit'), ('lower_limit','unit'), ('upper_limit','unit'),
                              ('quantity_checked','quantity_checked_unit'),
                              ('qualified_count','quantity_checked_unit'), ('rejected_count','quantity_checked_unit')):
        unit = row.get(unit_field)
        if isinstance(unit,str) and unit.strip():
            try:
                value = Decimal(str(row.get(field)))
                if value.is_finite():
                    pairs.append((field, str(value), unit))
            except InvalidOperation:
                continue
    return pairs


def _single_review_topic(question):
    if NAMED_RECORD_SOURCE.search(question) or re.search(r'只看|仅看|只用|仅用|只依据|只按|仅按|限于|仅限', question):
        return None
    prefixes = {match.group().split('-', 1)[0].upper() for match in IDENTIFIER.finditer(question)}
    if 'SL' in prefixes and all(bit in question for bit in ('承诺','签收','交期')):
        return '订单签收'
    if ('SL' in prefixes and '成品批' in question and '分配' in question
            and re.search(r'合计|总计|一共|总量', question)):
        return '批次分配'
    if ('FG' in prefixes and '放行' in question and '终检' in question
            and re.search(r'长期|外推|所有设备|全部设备',question)):
        return '放行终检'
    if 'SVC' in prefixes and all(bit in question for bit in ('现场','合同','退款')):
        return '工单合同'
    if 'DL' in prefixes and '签收' in question and re.search(r'包装|内外机|内机|外机|配件|几箱',question):
        return '包装签收'
    if ('SHIP' in prefixes and re.search(r'仓储|扣减|出库',question)
            and re.search(r'发车|运输|到达|签收',question) and re.search(r'区分|分开|经过|多久',question)):
        return '仓储运输'
    if 'SHIP' in prefixes and '清点' in question and re.search(r'哪一?条|哪个节点|在哪个节点', question):
        return '运输清点'
    return None


def record_review_prompt(question, sources, *, single_scope=False):
    """Surface related, already-read row fields next to their actual source labels."""
    topic = _single_review_topic(question) if single_scope else None
    if single_scope and not topic:
        return None
    applicable = [entry for entry in REVIEW_FIELDS if re.search(entry[0], question, re.I)]
    lines, roles = [], set()
    for index, source in enumerate(sources, 1):
        row, _ = _source_row(source,allow_single=single_scope)
        for _, identity_field, name, fields in applicable:
            # The first two identifying cells distinguish related tables which
            # share, for example, a supplier batch number or a QC reference.
            if not row.get(identity_field) or not all(row.get(field) for field, _ in fields[:2]):
                continue
            cells = {label:row[field] for field,label in fields if row.get(field) not in (None,'',[])}
            if len(cells) >= 3:
                lines.append(f'[来源{index}] {name}。' + json.dumps(cells,ensure_ascii=False,separators=(',',':')))
                roles.add(name)
    if not lines:
        return None
    distinctions = [
        text for required, text in (
            ({'订单承诺', '运单签收'}, '同一订单按实际交付行分别列出签收量和时刻。承诺量、交期和计算各引用对应原生凭证。'),
            ({'来料接收', '检验'}, '核对同一来料批的接收时刻、最终状态与检验时刻，引用各自记录。'),
            ({'制造放行', '检验'}, '分别报告制造放行时刻与终检实测时刻，保留批次、检查数、测项及原单位。'),
            ({'服务现场', '合同主体'}, '分别说明现场观察的设备与客户、实际检查与处置，以及合同买方与服务方。结算条款不能证明退款。'),
        ) if required <= roles
    ]
    if topic == '订单签收' and '运单签收' in roles:
        distinctions.append('逐笔说明已读签收的日期、时刻和数量。没有订单承诺或交期凭证时，明确列为待核对。')
    if topic == '放行终检' and '检验' in roles and '制造放行' not in roles:
        distinctions.append('保留已读实测时刻、测量项目、实测值、原单位与检查量。制造放行与长期运行结论需有对应资料。')
    if topic == '工单合同' and '服务现场' in roles and '合同主体' not in roles:
        distinctions.append('分别写明原记录确认的现场设备与客户。合同主体及退款另需对应合同和支付凭证。')
    return ('以下字段按本次实际读取的原始记录列出。先直接回答原问。所问同类记录有多条时，分别写清业务编号、关键值、'
            '原单位和时点。报告核对过的凭证及每条对应来源。涉及多类记录时分别引用支持各部分的来源。'
            '缺少单位时保留原数并说明；对不相关字段不展开。' + ''.join(distinctions) + '\n'+'\n'.join(lines))


def row_quantity_literals(source):
    """Resolve native row quantities with a companion unit or explicit column unit."""
    row, _ = _source_row(source)
    unit = row.get('unit')
    if isinstance(unit,str) and unit.strip():
        fields = dict.fromkeys(('quantity','signed_quantity','loaded_quantity','difference_quantity','quantity_change'),unit)
    else:
        fields = {}
    if row.get('sku_id') and row.get('manufacturer_id'):
        fields.update(dict.fromkeys(DIMENSION_FIELDS,'mm'))
    literals = []
    for field,unit in fields.items():
        if field not in row:
            continue
        try:
            value = Decimal(str(row[field]))
        except InvalidOperation:
            continue
        if value.is_finite():
            literals.append((str(value),unit))
    return literals


def partial_dimension_values(source):
    """Bind the three millimetre columns to the same verified native product row."""
    sku = source.provenance.get('scopedPartialDimensionSku')
    if not isinstance(sku, str) or not IDENTIFIER.fullmatch(sku) or not sku.startswith('SKU-'):
        return None
    row, facts = _source_row(source, allow_single=True)
    if str(row.get('sku_id', '')).upper() != sku or not {'sku_id', *DIMENSION_FIELDS} <= facts.keys():
        return None
    values = dimension_values(source, row)
    return (sku, {field:str(value) for field, value in values.items()}) if values else None


def partial_dimension_prompt(sources):
    lines = []
    for index, source in enumerate(sources, 1):
        record = partial_dimension_values(source)
        if record:
            sku, values = record
            axes = '、'.join(f'{label} {values[field]} 毫米' for field, label in DIMENSION_FIELDS.items())
            lines.append(f'[来源{index}] {sku} 的已读外形尺寸为{axes}。')
    return '\n'.join(lines)


def fulfillment(question, sources):
    """Reconcile explicitly requested order lines and independently read deliveries."""
    if not re.search(r'签收|收齐|到货', question):
        return []
    wanted = {match.group().casefold() for match in IDENTIFIER.finditer(question)}
    named_counts = {int(value) if value.isdecimal() else {'一':1,'二':2,'两':2,'三':3,'四':4,'五':5,
                    '六':6,'七':7,'八':8,'九':9,'十':10}[value]
                    for value in re.findall(r'(?:按|共|根据|基于)\s*([一二两三四五六七八九十]|[0-9]{1,2})\s*(?:次|笔|条)(?:实际)?签收', question)}
    orders, deliveries = {}, {}
    for index, source in enumerate(sources, 1):
        row, facts = _source_row(source)
        order_line = row.get('order_line_id')
        if not isinstance(order_line, str) or order_line.casefold() not in wanted:
            continue
        if {'customer_id', 'quantity', 'unit', 'due_at', 'sku_id'} <= row.keys():
            orders.setdefault(order_line, []).append((index, row, facts))
        if {'waybill_line_id', 'delivery_line_id', 'signed_at', 'signed_quantity', 'unit', 'sku_id'} <= row.keys():
            deliveries.setdefault(order_line, []).append((index, row, facts))
    results = []
    for key, anchors in orders.items():
        if len(anchors) != 1 or not deliveries.get(key):
            continue
        index, order, order_facts = anchors[0]
        unit = order['unit']
        if not isinstance(unit, str) or not unit.strip() or not order['sku_id']:
            continue
        try:
            due = _signed_at(order['due_at'])
            ordered = _count(order['quantity'])
            by_delivery = {}
            for receipt_index, row, facts in deliveries[key]:
                if (not row['delivery_line_id'] or row['unit'] != unit or row['sku_id'] != order['sku_id']
                        or row.get('customer_id') not in (None, '', order['customer_id'])):
                    raise ValueError('Delivery scope, product or unit conflict')
                received = (_signed_at(row['signed_at']), _count(row['signed_quantity']))
                previous = by_delivery.setdefault(row['delivery_line_id'], (received, receipt_index, facts))
                if received != previous[0]:
                    raise ValueError('Conflicting evidence for a delivery line')
        except (ValueError, InvalidOperation):
            continue
        if named_counts and named_counts != {len(by_delivery)}:
            continue
        entries = sorted(by_delivery.values(), key=lambda entry: entry[0][0])
        inclusive = bool(re.search(r'截至交期|不晚于(?:合同)?交期', question))
        before = sum((amount for (moment, amount), _, _ in entries
                      if moment < due or inclusive and moment == due), Decimal(0))
        receipt_fields = ('order_line_id', 'delivery_line_id', 'sku_id', 'unit', 'signed_quantity', 'signed_at')
        fact_ids = [order_facts[field] for field in ('order_line_id', 'sku_id', 'unit', 'quantity', 'due_at')]
        fact_ids.extend(facts[field] for _, _, facts in entries for field in receipt_fields)
        indices = sorted({index, *(source_index for _,source_index,_ in entries)})
        results.append({'operation':'deadline_receipt_sum', 'orderLineId':key, 'unit':unit,
                        'exactValue':str(before), 'orderFulfilledAtDeadline':before >= ordered,
                        'sourceIndices':indices, 'factIds':fact_ids})
        remaining = ordered
        for (moment, amount), _, _ in entries:
            remaining -= amount
            if remaining <= 0:
                break
        if ordered > 0 and remaining <= 0:
            interval = due - moment
            seconds = (interval.days*86400 + interval.seconds)*1000000 + interval.microseconds
            requested = re.search(r'多少\s*(小时|分钟|秒)', question)
            units = ((requested[1],) if requested else ('小时','分钟','秒'))
            for duration_unit in units:
                divisor = {'小时':3600000000, '分钟':60000000, '秒':1000000}[duration_unit]
                if seconds % divisor == 0:
                    results.append({'operation':'deadline_time_difference', 'orderLineId':key, 'unit':duration_unit,
                                    'exactValue':str(abs(seconds//divisor)), 'early':seconds >= 0,
                                    'sourceIndices':indices, 'factIds':fact_ids})
                    break
    return results


def _dimension_differences(question, sources):
    if not _compares_dimensions(question):
        return []
    named = {match.group().casefold() for match in IDENTIFIER.finditer(question)}
    if len(named) != 2 or not all(target.startswith('sku-') for target in named):
        return []
    products = {}
    for index, source in enumerate(sources,1):
        row, facts = _source_row(source)
        sku = row.get('sku_id')
        maker = row.get('manufacturer_id')
        if not isinstance(sku, str) or sku.casefold() not in named or not isinstance(maker, str) or not maker.strip():
            continue
        if DIMENSION_FIELDS.keys() <= row.keys():
            products.setdefault(sku.casefold(),[]).append((index,row,facts))
    if len(products)!=2 or any(len(rows)!=1 for rows in products.values()):
        return []
    (i,a,fa),(j,b,fb) = (matches[0] for matches in products.values())
    if sources[i-1].dataset_id==sources[j-1].dataset_id:
        return []
    left, right = dimension_values(sources[i-1], a), dimension_values(sources[j-1], b)
    if not left or not right:
        return []
    result = []
    for field,label in DIMENSION_FIELDS.items():
        difference = left[field]-right[field]
        result.append({'operation':'dimension_difference','field':label,'left':a['sku_id'],'right':b['sku_id'],
                       'comparison':(difference>0)-(difference<0),'unit':'mm','exactValue':str(abs(difference)),
                       'sourceIndices':[i,j],'factIds':[fa['sku_id'],fb['sku_id'],fa['manufacturer_id'],
                                                      fb['manufacturer_id'],fa[field],fb[field]]})
    return result


def _paired_boxes(question, sources, *, allow_single=False):
    if not re.search(r'箱|包装',question):
        return []
    named = {match.group().casefold() for match in IDENTIFIER.finditer(question)}
    groups = {}
    for index, source in enumerate(sources,1):
        row, facts = _source_row(source, allow_single=allow_single)
        delivery = row.get('delivery_line_id')
        if isinstance(delivery,str) and delivery.casefold() in named and set(PACKAGE_BOX_FIELDS)<=row.keys():
            if row.get('package_status') in (None,'','有效') and row['package_id'] and row['paired_package_id']:
                groups.setdefault(delivery,[]).append((index,row,facts))
    totals = []
    for delivery, entries in groups.items():
        if len(entries)!=2:
            continue
        (i,a,fa),(j,b,fb) = entries
        frozen = {(sources[index-1].dataset_id, sources[index-1].data_version, sources[index-1].index_version) for index in (i,j)}
        if (a['paired_package_id']!=b['package_id'] or b['paired_package_id']!=a['package_id']
                or {a['component_type'],b['component_type']}!={'室内机','室外机'}
                or not a['finished_batch_id'] or a['finished_batch_id']!=b['finished_batch_id']
                or not a['sku_id'] or a['sku_id']!=b['sku_id'] or len(frozen)!=1):
            continue
        counts = [whole_count(row['box_count']) for row in (a,b)]
        if any(count is None for count in counts):
            continue
        totals.append({'operation':'paired_package_box_count','delivery':delivery,'unit':'箱',
                       'exactValue':str(sum(counts)),'sourceIndices':[i,j],
                       'factIds':[facts[field] for facts in (fa,fb) for field in PACKAGE_BOX_FIELDS]})
    return totals


def shipment_stage(row):
    if row.get('movement_type') == '运输交接出库':
        fields = ('source_document_id', 'occurred_at', 'movement_type')
        if row.get('quantity_change') not in (None, ''):
            try:
                change = Decimal(str(row['quantity_change']))
            except InvalidOperation:
                return None, fields
            if not change.is_finite() or change>=0:
                return None, fields
        return 'release', fields
    if row.get('waybill_line_id') and row.get('signed_at') and not row.get('event_type'):
        return 'sign', ('shipment_id', 'signed_at', 'waybill_line_id')
    return SHIPMENT_EVENT_STAGES.get(row.get('event_type')), ('shipment_id', 'timestamp', 'event_type')


def _shipment_durations(question, sources, *, allow_single=False):
    if not re.search(r'耗时|相隔多久|相隔多长|间隔|用时|多长时间',question):
        return []
    names = {match.group().casefold() for match in IDENTIFIER.finditer(question)}
    stages = {}
    for index, source in enumerate(sources,1):
        row, facts = _source_row(source,allow_single=allow_single)
        key, fields = shipment_stage(row)
        reference, time_field, _ = fields
        shipment = row.get(reference)
        if not isinstance(shipment,str) or shipment.casefold() not in names:
            continue
        if not key or not row.get(time_field):
            continue
        try:
            instant = _signed_at(row[time_field])
        except ValueError:
            continue
        if not set(fields) <= facts.keys():
            continue
        stages.setdefault(shipment,{}).setdefault(key,{}).setdefault(instant,(index,[facts[field] for field in fields]))
    results = []
    for shipment, moments in stages.items():
        for start,end,label in (('release','sign','出库扣减至客户签收'),('depart','arrive','发车至到达')):
            if len(moments.get(start,{}))!=1 or len(moments.get(end,{}))!=1:
                continue
            (first,(i,first_facts)), = moments[start].items()
            (last,(j,last_facts)), = moments[end].items()
            if last<first:
                continue
            duration = last-first
            seconds = duration.days*86400+duration.seconds
            if duration.microseconds:
                continue
            value,unit = (seconds//60,'分钟') if seconds%60==0 else (seconds,'秒')
            results.append({'operation':'shipment_duration','shipment':shipment,'field':label,'unit':unit,'exactValue':str(value),
                            'sourceIndices':sorted({i,j}),'factIds':sorted(set(first_facts+last_facts))})
    return results


def verified_computations(question, sources):
    return [*fulfillment(question,sources),*_dimension_differences(question,sources),
            *_shipment_durations(question,sources),*_paired_boxes(question,sources)]


def computation_prompt(question, sources):
    statements = []
    for fact in verified_computations(question,sources):
        citations = ''.join(f'[来源{index}]' for index in fact['sourceIndices'])
        value = fact["exactValue"] + ' ' + fact["unit"]
        if fact['operation'] == 'deadline_receipt_sum':
            state = '已达到承诺数量' if fact['orderFulfilledAtDeadline'] else '当前凭证不足以确认收齐'
            statement = f'{fact["orderLineId"]} 按交期核对，已读签收合计 {value}，{state}。'
        elif fact['operation'] == 'deadline_time_difference':
            direction = '提前' if fact['early'] else '迟于交期'
            statement = f'{fact["orderLineId"]} 签收累计达到承诺数量的时刻相对交期 {direction} {value}。'
        elif fact['operation'] == 'dimension_difference':
            direction = {1:'大',0:'一致',-1:'小'}[fact['comparison']]
            statement = f'{fact["left"]} 的{fact["field"]}相对 {fact["right"]} 为{direction}，绝对差 {value}。'
        elif fact['operation'] == 'paired_package_box_count':
            statement = f'{fact["delivery"]} 两个互配的内外机包装组合计 {value}，运输记录中的同一批箱数不再相加。'
        else:
            statement = f'{fact["shipment"]} {fact["field"]}相隔 {value}。'
        statements.append(f'{statement}{citations}')
    return ('以下计算由实际读取的原始记录复核。引用须包含列出的全部来源。\n'
            + '\n'.join(statements)) if statements else ''


def _local_question(question, filters):
    entities = {entity['entity_id'].casefold() for entity in filters.get('entities',[])}
    entities.update(str(entity.get('values',{}).get('model','')).casefold() for entity in filters.get('entities',[]))
    # Search's single-snapshot unknown-entity guard remains intact. A reference
    # outside this child can only enter through another selected child.
    return IDENTIFIER.sub(lambda match: match.group() if match.group().casefold() in entities else '',question)


def _bound_candidates(raw, scope, known, limit):
    snapshot = raw.get('snapshot',{})
    if snapshot.get('dataVersion') != scope[1] or snapshot.get('indexVersion') != scope[2]:
        raise ValueError('检索返回了不同的冻结范围，请重试。')
    if raw.get('status','OK') not in {'OK','UNKNOWN_ENTITY','NO_RELEVANT_EVIDENCE'}:
        raise ValueError('所选范围未能完成检索，请重试。')
    values = []
    for item in raw.get('evidence',[]):
        if (item.get('dataVersion'),item.get('indexVersion')) != scope[1:]:
            raise ValueError('候选来源与冻结范围不一致。')
        evidence_id = scope[0] + ':' + item['evidenceId']
        if evidence_id in known:
            continue
        known.add(evidence_id)
        values.append({**item,'evidenceId':evidence_id,
                       'frozenScope':dict(zip(('datasetId','versionId','indexVersionId'),scope))})
        if len(values) >= limit:
            break
    return values


def _reference_candidates(client, scope, filters, identifiers, known, limit, asset_ids=()):
    identifiers = [value for value in identifiers if len(value)<=93][:4]
    if not identifiers or limit<=0 or not any('structured' in asset.get('contentTypes',[]) and asset.get('scope')=='corpus'
                           for asset in filters.get('assets',[])):
        return []
    current_budget().remaining()
    plan = {'taskIntent':'structured_search','retrievers':['structured'],'contentType':'structured',
            'operation':'search','corpusScope':'corpus','topK':limit,
            'all_tables_search':True,'reference_terms':identifiers}
    if asset_ids:
        plan['assetIds'] = list(asset_ids)
    raw = client.professional_search(*scope,plan)
    values = _bound_candidates(raw,scope,known,limit)
    for item in values:
        matches = _record_references(item.get('facts',{}),identifiers)
        if not matches:
            raise ValueError('检索返回的原始记录与命中的业务编号不一致。')
        item['relationQuestion'] = matches[0]
        item.setdefault('provenance',{}).update(explicitEntities=matches,crossScopeReference=matches[0])
    return values


def _record_references(record,identifiers):
    values = json.dumps(record,ensure_ascii=False)
    return [identifier for identifier in identifiers if re.search(
        r'(?<![A-Za-z0-9_-])'+re.escape(identifier)+r'(?![A-Za-z0-9_-])',values,re.I)]


def read_table_batch(client, scope_set, candidates, question, filters_by_scope, caches):
    """Read up to three independent native rows in their own frozen scopes."""
    targets = [item for item in candidates if not item.get('nativeBundle')
               and any(item.get('locator', {}).get(key) is not None for key in ('rawRow', 'jsonlLine'))]
    if not targets:
        return {}, []

    def read_row(item):
        scope = tuple(item['frozenScope'][key] for key in ('datasetId', 'versionId', 'indexVersionId'))
        with scope_set.for_scope(scope):
            source, receipt = read_selected(client, scope, item, question, filters_by_scope[scope],
                                            dict(caches[scope]), full_record=True)
        return item['evidenceId'], source, receipt

    prepared, receipts = {}, []
    with ThreadPoolExecutor(max_workers=min(len(targets), 3)) as pool:
        tasks = [pool.submit(copy_context().run, read_row, item) for item in targets]
        try:
            for task in tasks:
                evidence_id, source, receipt = task.result()
                prepared[evidence_id] = source
                if receipt:
                    receipts.append(receipt)
        except Exception:
            for task in tasks:
                task.cancel()
            raise
    return prepared, receipts


def _dimension_assets(question, filters, intent):
    if not _compares_dimensions(question) or re.search(r'BOM|用料|部件',question,re.I):
        return []
    registered = {table['assetId'] for table in filters.get('tables',[])
                  if {'sku_id','manufacturer_id',*DIMENSION_FIELDS} <= {field['name'] for field in table.get('fields',[])}}
    return [asset['assetId'] for asset in filters.get('assets',[])
            if asset['assetId'] in registered and asset.get('scope')=='corpus' and
            (not intent.allowed or asset.get('originalFormat') in intent.allowed)][:8]


def _native_cells(item):
    content = item.get('nativeBundle', {}).get('content', {})
    records = content.get('records', [])
    return records[0].get('values', {}) if len(records) == 1 else content.get('value') or {}


def _exact_native_rows(client, scope, question, filters, intent, key, targets, required_fields, *, qualifiers=None):
    """Find exact rows in registered tables, then verify the requested cells by native read."""
    scope = tuple(map(str, scope))
    native_assets = {asset['assetId'] for asset in filters.get('assets', [])
                     if asset.get('scope') == 'corpus' and asset.get('nativeEvidence')
                     and (not intent.allowed or asset.get('originalFormat') in intent.allowed)}
    qualifiers = qualifiers or {}
    fields = {key, *required_fields, *qualifiers}
    tables = [table for table in filters.get('tables', []) if table.get('scope') == 'corpus'
              and table['assetId'] in native_assets and fields <= {field['name'] for field in table['fields']}][:8]
    known, cache, rows, reads = set(), {}, {}, []
    for table in tables:
        for target in targets:
            # Each exact key has its own recall limit, so repeated rows for
            # one key cannot hide the other side of a comparison.
            selectors = {key: target, **qualifiers}
            plan = {'taskIntent': 'structured_search', 'contentType': 'structured', 'retrievers': ['structured'],
                    'operation': 'search', 'tableId': table['tableId'], 'topK': 4, 'corpusScope': 'corpus',
                    'hardConditions': [{'field': field, 'op': 'eq', 'value': value} for field, value in selectors.items()]}
            raw = client.professional_search(*scope, plan)
            for candidate in _bound_candidates(raw, scope, known, plan['topK']):
                if any(str(candidate['facts'].get(field, '')).casefold() != value.casefold()
                       for field, value in selectors.items()):
                    raise ValueError('检索返回了不匹配的业务编号，请重试。')
                candidate.setdefault('provenance', {})['explicitEntities'] = [target]
                source, receipt = read_selected(client, scope, candidate, question, filters, cache, full_record=True)
                bundle = source.get('nativeBundle', {})
                if not receipt or not bundle.get('readId') or receipt.get('readId') != bundle['readId']:
                    continue
                cells = _native_cells(source)
                if (not isinstance(cells, dict) or any(str(cells.get(field, '')).casefold() != value.casefold()
                                                       for field, value in selectors.items())
                        or any(cells.get(field) in (None, '') for field in required_fields)):
                    continue
                rows.setdefault(target, []).append(source)
                reads.append(receipt)
    return rows, reads


def partial_business_review(client, scope, question, filters, intent):
    """Keep partial record evidence when a single scope holds one side of a relation."""
    if re.search(r'排除|剔除|不含|不引用|不用|不要用', question):
        return None
    topic = _single_review_topic(question)
    role = SINGLE_REVIEW_ROWS.get(topic)
    if role is None:
        return None
    prefix, plans = role
    targets = list(dict.fromkeys(match.group().upper() for match in IDENTIFIER.finditer(question)
                                if match.group().upper().startswith(prefix + '-')))
    if len(targets) != 1:
        return None
    evidence, reads = [], []
    for key, required_fields in plans:
        rows, receipts = _exact_native_rows(client, scope, question, filters, intent, key, targets, required_fields)
        evidence.extend(rows.get(targets[0], ()))
        reads.extend(receipts)
    if prefix=='SHIP':
        for event_type in SHIPMENT_EVENT_STAGES:
            rows, receipts = _exact_native_rows(client, scope, question, filters, intent, 'shipment_id', targets,
                                                {'event_id', 'timestamp'}, qualifiers={'event_type': event_type})
            evidence.extend(rows.get(targets[0], ()))
            reads.extend(receipts)
    if not evidence:
        return None
    return {'evidence': evidence, 'nativeReads': reads, 'status': 'OK', 'businessReviewTopic': topic}


def _logistics_lineage(scope_set, client, question, intent, filters_by_scope):
    """Keep each named delivery or transport milestone in its own scope."""
    topic = _single_review_topic(question)
    prefixes = {'包装签收': 'DL-', '仓储运输': 'SHIP-'}
    targets = list(dict.fromkeys(match.group().upper() for match in IDENTIFIER.finditer(question)
                                if match.group().upper().startswith(prefixes[topic])))
    if len(targets) != 1 or re.search(r'排除|剔除|不含|不引用|不用|不要用',question):
        return {}, [], {}
    retained, receipts, timings = {}, [], {}
    for scope, filters in filters_by_scope.items():
        started = time.perf_counter()
        with scope_set.for_scope(scope):
            direct = partial_business_review(client, scope, question, filters, intent)
            sources = list(direct['evidence']) if direct else []
            reads = list(direct['nativeReads']) if direct else []
        if sources:
            for item in sources:
                item['provenance']['scopedLogisticsSource'] = True
            retained[scope] = {item['evidenceId']:item for item in sources}
        frozen = dict(zip(('datasetId','versionId','indexVersionId'),scope))
        receipts.extend({'scope':frozen, **read} for read in reads)
        timings[scope] = (time.perf_counter()-started)*1000
    return retained, receipts, timings


def partial_dimension_comparison(client, scope, question, filters, intent):
    """Keep the exact size row when only one compared SKU is in this scope."""
    targets = list(dict.fromkeys(match.group().upper() for match in IDENTIFIER.finditer(question)))
    if len(targets) != 2 or not all(target.startswith('SKU-') for target in targets):
        return None
    if (not all(axis in question for axis in ('宽','深','高'))
            or NAMED_RECORD_SOURCE.search(question)):
        return None
    if not _compares_dimensions(question) or re.search(r'BOM|用料|部件',question,re.I):
        return None
    identified, reads = _exact_native_rows(client, scope, question, filters, intent, 'sku_id', targets, DIMENSION_FIELDS)
    if len(identified) != 1:
        return None
    available, result = next(iter(identified.items()))
    for item in result:
        item['provenance']['scopedPartialDimensionSku'] = available
    return {'evidence': result, 'nativeReads': reads, 'status': 'OK',
            'unavailableDimensionTarget': next(target for target in targets if target != available)}


def _material_lineage(scope_set, client, question, intent, filters_by_scope):
    """Follow a native consumption row's supplier batch into selected intake and QC tables."""
    batches = []
    parts = []
    if (not re.search(r'领料|领用', question) or not re.search(r'来料|供应批', question)
            or not re.search(r'质检|检验', question) or NAMED_RECORD_SOURCE.search(question)
            or re.search(r'仅|只看|排除|剔除|不含|不引用', question)):
        return {}, [], {}
    for value in dict.fromkeys(match.group().upper() for match in IDENTIFIER.finditer(question)):
        if value.startswith('MB-'):
            batches.append(value)
        elif value.startswith('PART-'):
            parts.append(value)
    if not 1 <= len(batches) <= 3 or len(parts) != 1:
        return {}, [], {}

    from services.business_records import MATERIAL_CONSUMPTION

    collected, native_reads, timings = {}, [], {}

    def read(role, key, values, fields, qualifiers=None):
        for scope, filters in filters_by_scope.items():
            started = time.perf_counter()
            with scope_set.for_scope(scope):
                native, receipts = _exact_native_rows(client, scope, question, filters, intent, key, values,
                                                      fields - {key}, qualifiers=qualifiers)
            timings[scope] = timings.get(scope, 0.0) + (time.perf_counter()-started)*1000
            for items in native.values():
                for item in items:
                    item['provenance']['scopedMaterialTraceRole'] = role
                    collected.setdefault(scope, {})[item['evidenceId']] = item
            frozen_scope = dict(zip(('datasetId', 'versionId', 'indexVersionId'), scope))
            native_reads.extend({'scope': frozen_scope, **receipt} for receipt in receipts)

    read('consumption', 'manufacturing_batch_id', batches, MATERIAL_CONSUMPTION, {'part_id': parts[0]})
    linked = list(dict.fromkeys(_native_cells(item).get('supplier_batch_id')
                                for items in collected.values() for item in items.values()))
    linked = [value for value in linked if isinstance(value, str) and IDENTIFIER.fullmatch(value)]
    if not linked or len(linked) > 4:
        return collected, native_reads, timings
    read('receipt', 'supplier_batch_id', linked, {'received_at', 'final_state'}, {'part_id': parts[0]})
    inspection_fields = {'qc_id', 'measured_at', 'result'}
    read('inspection', 'object_id', linked, inspection_fields)
    return collected, native_reads, timings


def _order_fulfillment_rows(scope_set, client, question, intent, filters_by_scope):
    """Read the named order and each matching receipt under its own selected scope."""
    identifiers = list(dict.fromkeys(match.group().upper() for match in IDENTIFIER.finditer(question)
                                    if match.group().upper().startswith('SL-')))
    if len(identifiers) != 1 or re.search(r'排除|剔除|不含|不引用|不用|不要用', question):
        return {}, [], {}
    roles = {
        'commitment': {'customer_id', 'sku_id', 'quantity', 'unit', 'due_at'},
        'receipt': {'waybill_line_id', 'delivery_line_id', 'sku_id', 'signed_quantity', 'unit', 'signed_at'},
    }
    collected, native_reads, timings = {}, [], {}
    for scope, filters in filters_by_scope.items():
        started = time.perf_counter()
        frozen_scope = dict(zip(('datasetId', 'versionId', 'indexVersionId'), scope))
        with scope_set.for_scope(scope):
            for role, fields in roles.items():
                native, receipts = _exact_native_rows(client, scope, question, filters, intent, 'order_line_id',
                                                      identifiers, fields)
                for items in native.values():
                    for item in items:
                        item['provenance']['scopedFulfillmentRole'] = role
                        collected.setdefault(scope, {})[item['evidenceId']] = item
                native_reads.extend({'scope': frozen_scope, **receipt} for receipt in receipts)
        timings[scope] = (time.perf_counter()-started)*1000
    return collected, native_reads, timings


def discover(scope_set, client, question, intent, query_plan=None):
    started = time.perf_counter()
    scopes = list(scope_set.children)
    share = max(3,CAPABILITIES['maxSources']*3//len(scopes))
    packs, filters_by_scope, caches, reports, native_reads = {}, {}, {}, [], []
    known, links, directly_supported = set(), {}, set()
    asked_identifiers = list(dict.fromkeys(match.group() for match in IDENTIFIER.finditer(question)))
    specific = [identifier for identifier in asked_identifiers if identifier.split('-',1)[0].upper() not in {'SKU','PART'}]
    requested_anchors = specific or asked_identifiers
    product_context = not specific or re.search(r'什么产品|型号|用料|规格|BOM|外形|尺寸|制冷能力|部件参数', question, re.I)
    wide_scope = len(scopes) >= 4
    anchor_read_limit = max(1, min(len(requested_anchors), 2)) if wide_scope else 2
    def read_child(scope):
        current_budget().remaining()
        tick = time.perf_counter()
        child_known, cache, reads, references = set(), {}, [], set()
        supported = False
        with scope_set.for_scope(scope):
            filters = client.capabilities(*scope, discovery=True)['filters']
            local_question = _local_question(question,filters)
            dimension_assets = _dimension_assets(question,filters,intent) if not query_plan else []
            if dimension_assets and asked_identifiers:
                candidates = _reference_candidates(client,scope,filters,asked_identifiers,child_known,
                                                   min(4,share),dimension_assets)
            elif query_plan:
                raw = client.professional_search(*scope,query_plan)
                candidates = _bound_candidates(raw,scope,child_known,share)
            else:
                if IDENTIFIER.search(question) and not IDENTIFIER.search(local_question):
                    candidates = []
                else:
                    raw = client.candidates(*scope,local_question,intent.allowed,per_format=min(8,share),multi_scope=True)
                    candidates = _bound_candidates(raw,scope,child_known,share//2)
                local_identifiers = {match.group().casefold() for match in IDENTIFIER.finditer(local_question)}
                foreign = [identifier for identifier in asked_identifiers if identifier.casefold() not in local_identifiers]
                candidates.extend(_reference_candidates(client,scope,filters,foreign,child_known,min(4,share-len(candidates))))
            # Foreign keys used for a second lookup must come from actual native
            # reads and an admitted anchor. Free-text snippets do not expand scope.
            eligible = [item for item in candidates if any(item.get('locator',{}).get(key) is not None
                        for key in ('rawRow','jsonlLine'))
                        and admit(item.get('relationQuestion',local_question),item,scoped_filters=filters)[0] is not None]
            eligible.sort(key=lambda item:-len(_record_references(item.get('facts',{}),asked_identifiers)))
            # An exact dimension comparison needs the underlying product
            # rows for the final answer, without following their foreign keys.
            follow_links = not query_plan and not (wide_scope and dimension_assets and not specific)
            for item in eligible[:anchor_read_limit] if follow_links else []:
                source, receipt = read_selected(client,scope,item,local_question,filters,cache,full_record=True)
                if not source.get('nativeBundle'):
                    continue
                if receipt:
                    reads.append({'scope':item['frozenScope'],**receipt})
                candidates[candidates.index(item)] = source
                if _record_references(source['nativeBundle'].get('content',{}),requested_anchors):
                    supported = True
                bundle = source['nativeBundle']
                raw_record = bundle.get('content',{}).get('value')
                if not isinstance(raw_record,dict):
                    raw_record = {fact.get('field'):fact.get('value') for fact in bundle.get('facts',[])}
                references.update(value for name,value in raw_record.items()
                                  if name in LINK_FIELDS and isinstance(value,str) and IDENTIFIER.fullmatch(value)
                                  and (product_context or value.split('-',1)[0].upper() not in {'SKU','PART'}))
        report = {'scope':dict(zip(('datasetId','versionId','indexVersionId'),scope)),
                  'status':'COMPLETED','candidateCount':len(candidates),
                  'elapsedMs':round((time.perf_counter()-tick)*1000,3)}
        return candidates, filters, cache, child_known, reads, supported, references, report

    with ThreadPoolExecutor(max_workers=min(len(scopes), 3)) as executor:
        pending = {scope:executor.submit(copy_context().run,read_child,scope) for scope in scopes}
        try:
            for scope, task in pending.items():
                pack, filters, cache, child_known, reads, supported, references, report = task.result()
                packs[scope], filters_by_scope[scope], caches[scope] = pack, filters, cache
                known.update(child_known)
                native_reads.extend(reads)
                reports.append(report)
                if supported:
                    directly_supported.add(scope)
                for reference in sorted(references):
                    links.setdefault(reference,set()).add(scope)
        except Exception:
            for task in pending.values():
                task.cancel()
            raise

    if links and not query_plan:
        named = {identifier.casefold() for identifier in asked_identifiers}

        def read_related(scope):
            related = [identifier for identifier, origins in links.items()
                       if any(origin != scope for origin in origins)
                       and identifier.casefold() not in named][:4]
            if not related:
                return [], 0.0
            tick = time.perf_counter()
            with scope_set.for_scope(scope):
                available = share-len(packs[scope])
                rows = _reference_candidates(client, scope, filters_by_scope[scope], related, set(known), min(6,available))
            return rows, round((time.perf_counter()-tick)*1000,3)

        other_scopes = [scope for scope in scopes if scope not in directly_supported]
        with ThreadPoolExecutor(max_workers=3) as executor:
            pending = {scope:executor.submit(copy_context().run,read_related,scope) for scope in other_scopes}
            try:
                for scope, task in pending.items():
                    rows, elapsed = task.result()
                    packs[scope].extend(rows)
                    report = next(report for report in reports if report['scope']['datasetId']==scope[0])
                    report['candidateCount'] = len(packs[scope])
                    report['elapsedMs'] += elapsed
            except Exception:
                for task in pending.values():
                    task.cancel()
                raise
    if not query_plan and not wide_scope:
        read_related_rows = {'订单签收':_order_fulfillment_rows, '包装签收':_logistics_lineage,
                             '仓储运输':_logistics_lineage}.get(_single_review_topic(question),_material_lineage)
        relation_sources, relation_reads, relation_timings = read_related_rows(scope_set, client, question, intent, filters_by_scope)
        for scope, records in relation_sources.items():
            original_candidates = {record['provenance']['candidateEvidenceId'] for record in records.values()}
            packs[scope] = list(records.values()) + [item for item in packs[scope]
                            if item['evidenceId'] not in original_candidates and item['evidenceId'] not in records]
        for scope, elapsed in relation_timings.items():
            report = next(report for report in reports if report['scope']['datasetId']==scope[0])
            report['candidateCount'] = len(packs[scope])
            report['elapsedMs'] += round(elapsed, 3)
        native_reads.extend(relation_reads)
    candidates, eligible, rejections = [], [], []
    for scope, pack in packs.items():
        filters = filters_by_scope[scope]
        direct = _local_question(question,filters)
        groups = {}
        for item in pack:
            groups.setdefault(item.get('relationQuestion',direct),[]).append(item)
        for prompt, group in groups.items():
            admitted, diagnostics = select_evidence(prompt,group,intent,
                limit=CAPABILITIES['maxSources'],scoped_filters=filters)
            eligible.extend(admitted)
            rejections.extend(diagnostics['rejections'])
        candidates.extend(pack)

    def relation_witness(item):
        provenance = item['provenance']
        return bool(provenance.get('scopedMaterialTraceRole') or provenance.get('scopedFulfillmentRole')
                    or provenance.get('scopedLogisticsSource'))

    eligible.sort(key=lambda item:(not relation_witness(item),-item['relevance'],item['evidenceId']))
    result, seen = [], {}
    # Additional scope and modality only influence ties among admitted hits.
    while eligible and len(result)<CAPABILITIES['maxSources']:
        included = {item['frozenScope']['datasetId'] for item in result}
        high = eligible[0]['relevance']
        choice = next((item for item in eligible if relation_witness(item) == relation_witness(eligible[0])
                       and high - item['relevance'] <= .03
                       and item['frozenScope']['datasetId'] not in included),eligible[0])
        eligible.remove(choice)
        original = choice.get('nativeBundle',{}).get('identity',{})
        digest = original.get('sourceSha256')
        locator = record_locator(choice) or choice.get('locator',{})
        snap = (original.get('rawCommitId'),digest,json.dumps(locator,sort_keys=True,separators=(',',':')))
        if digest and snap in seen:
            seen[snap].setdefault('provenance',{}).setdefault('alternateFrozenSources',[]).append(choice['frozenScope'])
            continue
        seen[snap] = choice
        result.append(choice)
    if current():
        current().candidate_admission(candidates,result,rejections)
        current().event('multiScopeExecution',reports)
    diagnostics = {'candidateCount':len(candidates),'rejections':rejections,'scopeResults':reports,
                   'multiScopeElapsedMs':round((time.perf_counter()-started)*1000,3)}
    return result,diagnostics,filters_by_scope,caches,native_reads
