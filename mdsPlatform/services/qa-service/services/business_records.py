"""Find native records for exact business identifiers before semantic ranking."""
from collections import Counter
from datetime import datetime
from decimal import Decimal, InvalidOperation, localcontext
from fractions import Fraction
import json
import re

from services.native_evidence_read import source_from_bundle
from services.record_evidence_plan import (
    Reader, canonical, field_requested, identifier, key_candidates, literal, same_key, scan_table,
)


FORMATS = {'CSV', 'JSON', 'JSONL', 'XLSX'}
MAX_BUSINESS_ROWS = 256
FIELD_TERMS = {
    'width_mm': r'尺寸|外形|宽度|更宽|宽多少',
    'depth_mm': r'尺寸|外形|深度',
    'height_mm': r'尺寸|外形|高度|更高|高多少',
    'outdoor_width_mm': r'内外机.{0,6}(?:尺寸|外形)|(?:室外机|外机).{0,6}(?:尺寸|外形|宽度)',
    'outdoor_depth_mm': r'内外机.{0,6}(?:尺寸|外形)|(?:室外机|外机).{0,6}(?:尺寸|外形|深度)',
    'outdoor_height_mm': r'内外机.{0,6}(?:尺寸|外形)|(?:室外机|外机).{0,6}(?:尺寸|外形|高度)',
    'quantity': r'数量|订量|订了|实收|收齐|多少(?:个|件|台|套|箱)|计量',
    'quantity_change': r'数量|转移|扣减|净变动|多入|移入|移出',
    'signed_quantity': r'签收数量|签收[了量]|实签|收齐|(?:交付|签收)[^，。？；\n]{0,16}数量差异',
    'box_count': r'箱数|几箱|多少箱|清点',
    'quantity_unit': r'单位|多少(?:个|件|台|套|箱)|计量',
    'unit': r'单位|多少(?:个|件|台|套|箱)|计量',
    'amount_cny': r'金额|货值|含税',
    'line_amount_cny': r'金额|货值|含税',
    'tax_basis': r'含税|未税|不含税|税口径',
    'tax_included': r'含税|未税|不含税|税口径',
    'unit_price_cny': r'单价|货值',
    'part_id': r'料号|零件|什么料',
    'sku_id': r'型号|什么产品',
    'finished_batch_id': r'成品批|哪批|批号',
    'batch_id': r'批号|成品批|出库',
    'object_id': r'批号|批次|终检',
    'manufacturing_batch_id': r'生产批|制造批|生产单|追溯|领用',
    'supplier_batch_id': r'来料批|供货批|物料批|来源|来自|追溯|领用',
    'delivery_id': r'交付单|交付记录',
    'delivery_line_id': r'交付单|交付行|分批|每次签收',
    'difference_quantity': r'数量差异|差异数量|差了多少|短收',
    'exception': r'为什么|合理|分批|异常',
    'movement_id': r'拣货|转移|移入|移出|流水',
    'paired_movement_id': r'拣货|转移|配对',
    'movement_type': r'拣货|转移|移入|移出|出库|入库',
    'source_document_id': r'发运单|发运凭证|出库凭证',
    'inventory_state': r'拣货|待发|库存状态',
    'slot_id': r'库位|拣货|转移',
    'node_id': r'库位|拣货|转移|地点|节点',
    'occurred_at': r'什么时候|何时|时间|时刻|拣货|出库|\d{1,2}:\d{2}',
    'closed': r'状态|关闭|闭环|处置',
    'case_id': r'关联|CASE|异常|处置',
    'component_type': r'内机|外机|室内|室外|配套|配对',
    'products_per_box': r'整机|多少台|几台|台数|有效箱数',
    'product_unit': r'整机|多少台|几台|台数|有效箱数|计量',
    'package_status': r'包装|换箱|有效箱数',
    'replaces_package_id': r'包装|换箱|替换|有效箱数',
    'inspection': r'检查|观察|核查|原因',
    'symptom': r'检查|观察|核查|原因|症状|故障',
    'diagnosis': r'原因|判断|诊断',
    'action': r'措施|处置|行动|换件',
    'retest': r'复测|复核|复验',
    'observation_start_at': r'观察时间|观察起止|观察窗口',
    'observation_end_at': r'观察时间|观察起止|观察窗口',
    'tested_at': r'测试|试验完成',
    'released_at': r'放行',
    'service_id': r'工单|服务',
    'service_contract_id': r'服务合同|售后合同',
    'serial_id': r'序列号|序列标识',
    'shipment_id': r'运输|发运|到达|卸货|签收',
    'arrived_at': r'到达|抵达|运抵',
    'unloading_completed_at': r'卸货',
    'signed_at': r'签收|交付',
    'destination_node_id': r'目的地|地点|卸货|签收',
    'event_id': r'哪一条事件|哪条事件|事件编号|事件号',
    'event_type': r'事件|清点|到达|抵达|卸货|签收|终检|放行|转为|移交',
    'timestamp': r'时间|时刻|多久|用时|间隔|耗时|时长|什么时候|何时|同一天|移交|清点',
    'waybill_line_ids': r'运单行|清点',
    'from_state': r'状态|转为|移交|放行',
    'to_state': r'状态|转为|移交|放行',
    'reference_id': r'参考号|凭证|终检|放行|交接',
    'note': r'交到哪里|移交到|交接|终检',
    'reason': r'原因|换箱|返修|为什么',
    'message_type': r'换箱|返修|异常登记|处置',
    'old_package_id': r'换箱|原包装',
    'new_package_id': r'换箱|新包装',
    'qualified_quantity': r'合格数量|合格(?:件|个|台|套)数|入库(?:的)?依据|实收和合格',
    'final_good': r'合格(?:件|个|台|套)数|多少(?:件|个|台|套)合格',
    'final_state': r'入库依据|可入库|可以入库|终态|可用',
    'inspected_at': r'检验|验货',
    'received_at': r'收货|入库|实收',
    'last_signed_at': r'最后签收|末次签收|截止',
    'due_at': r'截止|交期|承诺时间|到期',
    'gross_weight_kg': r'毛重|载重|配载|箱重|多重|每箱.{0,8}重量',
    'vehicle_capacity_kg': r'载重|配载|重量上限',
    'vehicle_volume_m3': r'容积|体积|配载',
    'loaded_volume_m3': r'容积|体积|配载',
    'loaded_floor_m2': r'地板|配载',
    'qc_id': r'终检|质检单|检验凭证|入库(?:的)?依据',
    'qc_type': r'终检|首检|复检',
    'quantity_checked': r'统计口径|检验数量|检查数量|合格套数',
    'quantity_checked_unit': r'统计口径|合格套数|检验单位',
    'qualified_count': r'合格数量|合格套数|多少套通过',
    'aggregation_method': r'统计口径|均值|平均',
    'item': r'统计口径|检验项目|上下限',
    'lower_limit': r'下限|上下限',
    'upper_limit': r'上限|上下限',
    'actual_value': r'统计口径|实测|均值|平均',
    'min_value': r'实测范围|统计口径|最小值',
    'max_value': r'实测范围|统计口径|最大值',
}
NUMERIC = {'number', 'mixed'}
NUMERIC_TYPES = {int, float}
ID = re.compile(r'(?<![A-Za-z0-9_.-])[A-Za-z]{2,16}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_.-])')
MODEL_ALIAS = re.compile(r'(?=.*[a-z])(?=.*\d)[a-z0-9_-]{2,64}')
SHIPMENT_DURATION = re.compile(r'(?:从|自)?(?:实际)?(?:到达|抵达)(?:时刻)?(?:到|至)(?:最终)?签收|签收(?:距|离|在)(?:实际)?(?:到达|抵达)')
PRODUCTION_DURATION = re.compile(r'(?:从|自)?(?:测试|试验)(?:完成)?(?:到|至)(?:正式)?放行')
DURATION_WORDS = re.compile(r'多久|多长|用时|耗时|间隔|时长|多少(?:分钟|小时|秒)')
LOCAL_CLOCK = re.compile(r'(?<!\d)(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*日\s*(\d{1,2}):([0-5]\d)(?![:\d])')
LOCAL_DATE = re.compile(r'(?<!\d)(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*日')
TIME_RANGE = re.compile(r'此前|此后|之前|之后|期间|起止|直到|至\s*\d|到\s*\d{1,2}:')
SHIPMENT_SUMMARY = {'arrived_at', 'signed_at'}
SHIPMENT_EVENT_CROSS_CHECK = re.compile(r'事件流|事件表|事件号|扫描记录|对照|对比|结合|交叉核对|\bevent (?:log|stream|table)\b', re.I)
PACKAGING_PAIR_FIELDS = {'delivery_line_id', 'sku_id', 'package_id', 'paired_package_id', 'set_component_role',
                         'box_count', 'set_quantity', 'accessory_part_id', 'accessory_quantity'}
PACKAGING_KEYS = {'sku_id', 'delivery_line_id', 'package_id'}
PACKAGING_LEDGER = {'package_id', 'delivery_line_id', 'finished_batch_id', 'sku_id', 'box_count'}
PACKAGING_CHANGE = {'message_id', 'message_type', 'finished_batch_id', 'sku_id', 'old_package_id', 'new_package_id', 'reason'}
PACKAGING_IDENTITY = {'package_id', 'delivery_line_id', 'finished_batch_id', 'sku_id', 'message_id',
                      'old_package_id', 'new_package_id'}
SHIPMENT_EVENTS = {'event_type', 'timestamp', 'node_id'}
SHIPMENT_CLOCK_TIMES = ('planned_arrival_at', 'arrived_at', 'signed_at')
SHIPMENT_CLOCK_LEDGER = {'shipment_id', 'destination_node_id', *SHIPMENT_CLOCK_TIMES}
SHIPMENT_CLOCK_EVENTS = {'shipment_id', 'event_id', *SHIPMENT_EVENTS}
ARRIVAL_EVENT_TYPES = {'到达', '抵达', '运抵', '已到达', 'arrival', 'arrived'}
SIGNOFF_EVENT_TYPES = {'签收', '客户签收', '完成签收', '交付签收', 'signoff', 'signed', 'signed_off'}
MAX_SHIPMENT_SUMMARY_TABLES = 3
ROAD_TRANSPORT_TIMES = ('loading_started_at', 'loading_completed_at', 'warehouse_outbound_at',
                        'departed_at', 'arrived_at', 'signed_at')
FOLLOW_UP = re.compile(r'这(?:一)?批|那(?:一)?批|该批|同一批|这票|那票|该票|这(?:笔|条|单)|那(?:笔|条|单|复测)|它(?:配套|的)|'
                       r'(?:刚才|前一轮|上一轮|上次)(?:的)?(?:那|这|同一)?(?:批|票|笔|条|单|记录|工单|订单|产品|型号|对象)|'
                       r'(?:刚才|前一轮|上一轮|上次)(?:查|问|看的|的)?(?:那|这)?(?:一)?(?:项|行|个|份|结果)')
NEW_SUBJECT = re.compile(r'换成|改成|另问|更正|换一|新会话|切换|忽略|另一家|不要原批|不看原批')
NEGATED_TIME = re.compile(r'(?:不要|不用|不能|无需|不应|请勿|而非|而不是|不是|并非)[^，。；;！？?\n]*')
PURCHASE_ORDER = {'purchase_line_id', 'quantity', 'ordered_at', 'due_at'}
PURCHASE_LINE_QUANTITIES = {'purchase_id', 'purchase_line_id', 'quantity', 'unit'}
PURCHASE_RECEIPT = {'purchase_line_id', 'supplier_batch_id', 'quantity', 'qualified_quantity', 'received_at'}
ORDER_AMOUNT = {'order_line_id', 'sku_id', 'amount_cny', 'ordered_at'}
ORDER_LINE_DEADLINE_REVIEW = {'order_line_id', 'amount_cny', 'last_signed_at', 'due_at'}
SUPPLY_COST_REVIEW = {'supplier_batch_id', 'quantity', 'unit', 'unit_price_cny', 'line_amount_cny', 'manufacturing_scope'}
FINAL_INSPECTION_HANDOFF = {'qc_id', 'qc_type', 'aggregation_method', 'actual_value'}
FULFILLMENT_FIELDS = {'last_signed_at', 'delivery_line_id', 'delivery_line_ids_json'}
ORDER_ALLOCATION = {'allocation_id', 'order_line_id', 'finished_batch_id', 'quantity'}
MATERIAL_CONSUMPTION = {'consumption_id', 'part_id', 'manufacturing_batch_id', 'supplier_batch_id', 'quantity', 'unit'}
MATERIAL_PRODUCTION = {'manufacturing_batch_id', 'finished_batch_id', 'sku_id'}
BATCH_DELIVERY = {'delivery_id', 'delivery_line_id', 'order_line_id', 'finished_batch_id', 'sku_id',
                  'signed_at', 'signed_quantity', 'unit'}
PRODUCTION_RELEASE = {'finished_batch_id', 'sku_id', 'qc_id', 'tested_at', 'released_at', 'final_good', 'unit'}
PRODUCTION_DURATION_FIELDS = {'tested_at', 'released_at'}
PRODUCTION_RECORD_KEYS = {'manufacturing_batch_id', 'finished_batch_id', 'sku_id', 'qc_id'}
PRODUCTION_FINAL_INSPECTION = {'object_id', 'sku_id', 'qc_id', 'qc_type', 'measured_at', 'item', 'result'}
INSPECTION_QUANTITY_FIELDS = {'object_id', 'qc_id', 'qc_type', 'quantity_checked', 'quantity_checked_unit', 'qualified_count'}
REPLENISHMENT_LINK_FIELDS = {'object_id', 'qc_id', 'related_qc_id', 'qc_type', 'sku_id', 'item', 'measured_at', 'disposition'}
REPLENISHMENT_VALUE_FIELDS = {'quantity_checked', 'quantity_checked_unit', 'lower_limit', 'unit'}
INCOMING_RESULT_FIELDS = {'object_id', 'qc_id', 'qc_type', 'sku_id', 'item', 'measured_at', 'result', 'disposition'}
REPLENISHMENT = re.compile(r'补货|补供|补发|replenish', re.I)
INCOMING_INSPECTION = re.compile(r'来料|进料|incoming|\bIQC\b', re.I)
FIRST_INSPECTION_TYPE = re.compile(r'^(?:(?:生产|产线|成品|工序)?\s*(?:首检|首件检验|首件检查)(?:记录|报告)?|'
                                   r'first[-_ ]*(?:article(?:[-_ ]*inspection)?|inspection|check)|FAI)$', re.I)
CONTRACT_SETTLEMENT = {'contract_id', 'settlement', 'currency'}
CONTRACT_SETTLEMENT_CONTEXT = {'contract_type', 'contract_nature', 'service_price_cny', 'tax_basis',
                               'valid_from', 'valid_to', 'clauses_json'}
SERVICE_OBSERVATION = {'service_id', 'sku_id', 'symptom', 'inspection', 'diagnosis', 'action', 'retest'}
DRAIN_DURATIONS = ('drain_wait_s', 'drain_stage_duration_s', 'drain_elapsed_s')
DRAIN_CLOCK = ('drain_timing_start_at', 'drain_timing_end_at')
DRAIN_TIMING_FIELDS = {'service_id', 'sku_id', 'drain_timing_method', *DRAIN_CLOCK, *DRAIN_DURATIONS}
SERVICE_CONTRACT_LINK = {'service_id', 'service_contract_id', 'customer_id'}
SERVICE_REGISTRATION_FIELDS = {'service_id', 'service_contract_id', 'serial_id', 'delivery_line_id'}
CONTRACT_BUYER_FIELDS = {'buyer_id', 'buyer_ids_json'}
COOLING_RATING = {'rated_cooling_w', 'rated_cooling_input_w', 'spec_condition'}
RATED_INPUT_POWER_FIELDS = {'rated_input_power_w', 'rated_cooling_input_w', 'rated_heating_input_w'}
SALES_FULFILLMENT = {'order_line_id', 'sku_id', 'quantity', 'unit', 'due_at',
                     'signed_quantity', 'last_signed_at', 'delivery_line_ids_json'}
ORDER_DELIVERY_TERMS = {'order_line_id', 'contract_id', 'sku_id', 'quantity', 'unit', 'ordered_at', 'due_at'}
SIGNED_DELIVERY = {'delivery_line_id', 'order_line_id', 'sku_id', 'signed_quantity', 'unit', 'signed_at'}
ORDER_LINE_SIGNOFF_SUMMARY = {'order_line_id', 'sku_id', 'quantity', 'signed_quantity', 'unit'}
MAX_FULFILLMENT_DELIVERY_TABLES = 3
ORDER_LINE_COUNT = re.compile(r'(?:这|那|上述|所列)\s*(\d{1,2}|[一二两三四五六七八九十])\s*(?:行|(?:条|笔)订单行)')
PRODUCTION_BATCH_COUNT = re.compile(r'(?<![A-Za-z0-9_.\-一二两三四五六七八九十])(\d{1,2}|[一二两三四五六七八九十])\s*批')
PURCHASE_ALLOCATION_BATCH_COUNT = re.compile(r'(?<![\d一二两三四五六七八九十])(\d{1,2}|[一二两三四五六七八九十])\s*(?:个\s*)?(?:合格\s*)?(?:批次|批)')
PURCHASE_ALLOCATION_REQUESTED_AMOUNT = re.compile(r'(?<![\d.\-])([+-]?\d+(?:,\d{3})*(?:\.\d+)?)\s*(?:人民币|元|CNY)(?![A-Za-z])', re.I)
SIGNOFF_COUNT = re.compile(r'(?<![\d一二两三四五六七八九十])(\d{1,2}|[一二两三四五六七八九十])\s*(?:张|份|笔)\s*签收(?:单|记录|凭证)')
CHINESE_LINE_COUNTS = dict(zip('一二三四五六七八九十两', (*range(1, 11), 2)))
AGGREGATE_SCOPE_LIMIT = re.compile(
    r'剔除|不含|排除|除外|至少|超过|之间|起止|期间|此前|此后|以前|以后|只算|仅算|\d{4}-\d{2}')
WEIGHT_SHARE = re.compile(r'毛重[^，。？；\n]{0,24}(?:占|比)[^，。？；\n]{0,24}(?:载重|载荷|重量上限)')


class BusinessRecordClarification(ValueError):
    def __init__(self, message):
        super().__init__('NEEDS_CLARIFICATION: ' + message)
        self.message = message


class PackagingClarification(BusinessRecordClarification):
    pass


class ProductionDurationClarification(BusinessRecordClarification):
    pass


class InspectionScopeUnavailable(ValueError):
    def __init__(self, status, message, coverage):
        super().__init__(status + ': ' + message)
        self.status = status
        self.message = message
        self.coverage = coverage


def _question_tokens(question, model_ids):
    tokens = {match.group().casefold() for match in ID.finditer(question)}
    tokens.update(model for model in model_ids if MODEL_ALIAS.fullmatch(model) and literal(question, model, ignore_case=True))
    return tokens


def _previous_subject(question, previous, model_ids):
    if not previous or NEW_SUBJECT.search(question):
        return None
    if not (FOLLOW_UP.search(question) or re.search(r'刚才|前一轮|上一轮|上次', question)
            and re.search(r'只要|只看|只算|只报|不要|排除', question)):
        return None
    anchors = _question_tokens(previous[-1], model_ids)
    return next(iter(anchors)) if len(anchors) == 1 else None


def _previous_packaging_subject(question, previous, model_ids, tables):
    """Carry a delivery-line/SKU conjunction, never a prior model answer."""
    if (not previous or NEW_SUBJECT.search(question)
            or not re.search(r'配套|对应|这批|那批|该批|它的|刚才|上一轮|前一轮', question)
            or not re.search(r'内机|外机|室内机|室外机', question)):
        return ()
    anchors = _question_tokens(previous[-1], model_ids)
    if len(anchors) != 2:
        return ()
    for table in tables:
        names = {field['name'] for field in table['fields']}
        if not {'package_id','paired_package_id','delivery_line_id','sku_id'} <= names:
            continue
        selectors = _selectors(table['fields'], anchors, model_ids)
        if len(selectors) == 2 and {key['field'] for key in selectors} == {'delivery_line_id','sku_id'}:
            return tuple(sorted(anchors))
    return ()


def _selectors(fields, identifiers, model_ids):
    options = []
    for field in fields:
        if not identifier(field):
            continue
        values = field.get('typedEnum') or [
            {'sourceType': 'string', 'value': value} for value in field.get('enum', []) if isinstance(value, str)]
        known = {item['value'].casefold(): item['value'] for item in values
                 if item.get('sourceType') == 'string' and isinstance(item.get('value'), str)}
        matches = {}
        for token in sorted(identifiers):
            resolved = token if token in known else model_ids.get(token)
            if resolved in known and (resolved == token or field['name'] == 'sku_id'):
                matches[token] = known[resolved]
        if matches:
            options.append((field['name'], matches))
    if not options:
        return []
    count = max(len(matches) for _, matches in options)
    winners = [(name, matches) for name, matches in options if len(matches) == count]
    if count == len(identifiers):
        direct_counts = [sum(token == value.casefold() for token, value in matches.items())
                         for _, matches in winners]
        most_direct = max(direct_counts)
        winners = [item for item, direct in zip(winners, direct_counts) if direct == most_direct]
        if len(winners) != 1:
            return []
        name, matches = winners[0]
        return [{'field': name, 'sourceType': 'string', 'value': value} for value in matches.values()]

    predicates = []
    for token in sorted(identifiers):
        exact = [(name, matches[token]) for name, matches in options if token in matches]
        if len(exact) != 1:
            return []
        name, value = exact[0]
        predicates.append({'field': name, 'sourceType': 'string', 'value': value})
    return predicates


def _match_rows(rows, selectors):
    by_field = {}
    for key in selectors:
        by_field.setdefault(key['field'], []).append(key)
    matching = [row for row in rows if all(
        field in row['values'] and any(same_key(row['values'][field], key) for key in keys)
        for field, keys in by_field.items())]
    if any(not any(same_key(row['values'][key['field']], key) for row in matching) for key in selectors):
        return []
    return matching


def _field_question(question, prior_questions):
    """Carry an omitted monetary measure across a change of record scope."""
    if (not prior_questions or not re.search(r'分别多少|各(?:自)?(?:是|有|为)?多少', question)
            or not re.search(r'换|改|现在|只核|只看', question)
            or re.search(r'数量|订量|件数|个数|箱数|套数|单价|重量|尺寸|长度|宽度|高度|功率|时间|时长|合格率|'
                         r'几(?:台|套|件|个|箱)|多少(?:台|套|件|个|箱)', question)):
        return question
    previous = NEGATED_TIME.sub(' ', prior_questions[-1])
    if re.search(r'金额|货值|价款', previous) and not re.search(r'单价|数量|件数|箱数', previous):
        return question + '（沿用上一轮所问的金额指标）'
    return question


def _packaging_weight_fields(question, fields):
    """Select native mass fields without depending on one spelling of the key."""
    if not re.search(r'净重|毛重|箱重|多重|重量', question):
        return set()
    selected = set()
    for field in fields:
        if field.get('type') not in NUMERIC or field.get('unit') not in {None, 'kg', '千克', '公斤'}:
            continue
        name = field['name'].replace('_', ' ')
        if field.get('unit') is None and not re.search(r'\bkg\b', name, re.I):
            continue
        meaning = ' '.join(str(field.get(key) or '') for key in ('name', 'description', 'sourceDescription'))
        if re.search(r'\b(?:gross|net|weight|mass)\b|毛重|净重|重量', meaning.replace('_', ' '), re.I):
            selected.add(field['name'])
    return selected


def _fields(question, table):
    selected = []
    names = {field['name'] for field in table['fields']}
    reconciliation_fields = set()
    if ({'delivery_line_id', 'shipment_id', 'signed_quantity', 'unit'} <= names
            and re.search(r'几台|多少台|台数|交付数量|签收数量|实收数量', question)):
        reconciliation_fields.update({'delivery_line_id', 'signed_quantity', 'unit'})
    if PACKAGING_PAIR_FIELDS <= names and _packaging_pair_question(question):
        reconciliation_fields.update(PACKAGING_PAIR_FIELDS)
    if ({'package_id','paired_package_id','delivery_line_id','sku_id'} <= names
            and re.search(r'内机|外机|室内|室外|配套|配对', question)):
        reconciliation_fields.update(names & {'package_id','paired_package_id','delivery_line_id','sku_id',
                                            'component_type','set_component_role','box_count','gross_weight_kg'})
        reconciliation_fields.update(_packaging_weight_fields(question, table['fields']))
    if {'message_id','message_type'} <= names and re.search(r'状态|关闭|闭环|原因|处置', question):
        reconciliation_fields.update(names & {'message_id','message_type','closed','occurred_at','case_id'})
    if _production_quality_question(question):
        for required in (PRODUCTION_RELEASE, PRODUCTION_FINAL_INSPECTION):
            if required <= names:
                reconciliation_fields.update(required)
    if PRODUCTION_RELEASE <= names and _production_release_question(question):
        reconciliation_fields.update(PRODUCTION_RELEASE)
    if CONTRACT_SETTLEMENT <= names and _contract_settlement_question(question):
        reconciliation_fields.update(CONTRACT_SETTLEMENT | (names & CONTRACT_SETTLEMENT_CONTEXT))
    if SERVICE_OBSERVATION <= names and (_service_observation_question(question) or _service_replacement_question(question)):
        reconciliation_fields.update(SERVICE_OBSERVATION)
    if SERVICE_REGISTRATION_FIELDS <= names and _service_registration_question(question):
        reconciliation_fields.update(SERVICE_REGISTRATION_FIELDS)
    if DRAIN_TIMING_FIELDS <= names and _drain_timing_question(question):
        reconciliation_fields.update(DRAIN_TIMING_FIELDS)
    if _purchase_comparison(question):
        if PURCHASE_RECEIPT <= names:
            reconciliation_fields = {'supplier_batch_id', 'part_id', 'quantity', 'qualified_quantity', 'received_at'}
        elif PURCHASE_ORDER <= names:
            reconciliation_fields = {'part_id', 'quantity'}
    if PURCHASE_LINE_QUANTITIES <= names and _purchase_line_quantity_question(question):
        reconciliation_fields.update(PURCHASE_LINE_QUANTITIES)
    if _order_amount_question(question) and ORDER_AMOUNT <= names:
        reconciliation_fields.add('order_line_id')
    if ORDER_LINE_DEADLINE_REVIEW <= names and _order_line_deadline_review_question(question):
        reconciliation_fields.update(ORDER_LINE_DEADLINE_REVIEW | (names & {'quantity', 'unit', 'unit_price_cny', 'signed_quantity'}))
    if SUPPLY_COST_REVIEW <= names and _supply_cost_review_question(question):
        reconciliation_fields.update(SUPPLY_COST_REVIEW)
    if FINAL_INSPECTION_HANDOFF <= names and _final_inspection_handoff_question(question):
        reconciliation_fields.update(FINAL_INSPECTION_HANDOFF | (names & {'object_id', 'item', 'quantity_checked',
                                     'quantity_checked_unit', 'qualified_count', 'lower_limit', 'upper_limit',
                                     'min_value', 'max_value', 'unit'}))
    if ORDER_ALLOCATION <= names and _allocation_question(question):
        reconciliation_fields.update(ORDER_ALLOCATION)
    if _road_transport_question(question) and set(ROAD_TRANSPORT_TIMES) <= names:
        reconciliation_fields.update(ROAD_TRANSPORT_TIMES)
    if _fulfillment_question(question):
        if SALES_FULFILLMENT <= names:
            reconciliation_fields.update(SALES_FULFILLMENT)
        elif SIGNED_DELIVERY <= names:
            reconciliation_fields.update(SIGNED_DELIVERY)
    if _delivery_reconciliation_question(question):
        if ORDER_DELIVERY_TERMS <= names and not FULFILLMENT_FIELDS & names:
            reconciliation_fields.update(ORDER_DELIVERY_TERMS)
        elif SIGNED_DELIVERY <= names:
            reconciliation_fields.update(SIGNED_DELIVERY)
    if (COOLING_RATING <= names and re.search(r'制冷|配电', question)
            and re.search(r'功率|制冷能力|额定输入', question)):
        reconciliation_fields.update(COOLING_RATING | {'model', 'sku_id'})
    rated_inputs = names & RATED_INPUT_POWER_FIELDS
    if len(rated_inputs) == 1 and re.search(r'(?<!制冷)(?<!制热)额定输入', NEGATED_TIME.sub(' ', question)):
        reconciliation_fields.update(rated_inputs)
    for field in table['fields']:
        pattern = FIELD_TERMS.get(field['name'])
        if (field['name'] in reconciliation_fields or field_requested(question, field)
                or (pattern and re.search(pattern, question))):
            selected.append(field)
    names = {field['name'] for field in selected}
    quantity_fields = names & {'quantity', 'quantity_change', 'signed_quantity', 'qualified_quantity', 'final_good'}
    if quantity_fields:
        unit_fields = {'unit', 'quantity_unit'}
        unit_fields.update(field['unitField'] for field in selected
                           if field['name'] in quantity_fields and field.get('unitField'))
        selected.extend(field for field in table['fields'] if field['name'] in unit_fields - names)
    return selected


def _model_ids(entities, tables):
    aliases = {}
    for entity in entities:
        if entity.get('entity_type') != 'products':
            continue
        values = entity.get('values', {})
        model = values.get('model')
        sku = entity.get('entity_id')
        if isinstance(model, str) and isinstance(sku, str):
            aliases.setdefault(model.casefold(), set()).add(sku.casefold())
    # A native SKU field can bind its own short spelling even when this view
    # does not expose the products registry. The exact value is re-read below.
    for table in tables:
        for field in table['fields']:
            if field['name'] != 'sku_id':
                continue
            values = field.get('typedEnum') or [{'value': value, 'sourceType': 'string'}
                                                for value in field.get('enum', []) if isinstance(value, str)]
            for entry in values:
                sku = entry.get('value')
                if entry.get('sourceType') != 'string' or not isinstance(sku, str):
                    continue
                sku = sku.casefold()
                if sku.startswith('sku-') and MODEL_ALIAS.fullmatch(sku[4:]):
                    aliases.setdefault(sku[4:], set()).add(sku)
    return {name: next(iter(ids)) for name, ids in aliases.items() if len(ids) == 1}


def _narrow_native_rows(question, rows, fields, clock):
    if clock and 'occurred_at' in fields:
        year, *local_clock = clock
        matches = []
        date_bases = set()
        for row in rows:
            value = row['values'].get('occurred_at')
            if not isinstance(value, str):
                return []
            try:
                moment = datetime.fromisoformat(value.replace('Z', '+00:00'))
            except ValueError:
                return []
            if moment.utcoffset() is None:
                return []
            if (not year or moment.year == year) and [moment.month, moment.day, moment.hour, moment.minute] == local_clock:
                matches.append(row)
                date_bases.add((moment.year, moment.utcoffset()))
        if len(date_bases) != 1:
            return []
        rows = matches
    if ('最后追加' in question and '终检' in question and 'qc_type' in fields
            and not re.search(r'不要|不看|排除|剔除|除外|此前|此后', question)):
        rows = [row for row in rows if isinstance(row['values'].get('qc_type'), str)
                and '终检' in row['values']['qc_type']]
        if rows:
            rows = [max(rows, key=lambda row: row['rawRow'])]
    return rows


def _movement_pair(question, rows):
    if not re.search(r'拣货|转移', question) or re.search(
            r'出库|运输交接|发运|签收|此前|此后|之前|之后|期间|除外|排除|\d{1,2}月|\d{4}-\d{2}', question):
        return rows, None
    lookup = {row['values'].get('movement_id'): row for row in rows if row['values'].get('movement_id')}
    if not lookup or len(lookup) != len([row for row in rows if row['values'].get('movement_id')]):
        return rows, None
    pairs = []
    for movement_id, row in lookup.items():
        values = row['values']
        quantity = values.get('quantity_change')
        other = lookup.get(values.get('paired_movement_id'))
        if type(quantity) not in NUMERIC_TYPES or quantity >= 0 or not other:
            continue
        incoming = other['values']
        if (incoming.get('paired_movement_id') != movement_id or type(incoming.get('quantity_change')) not in NUMERIC_TYPES
                or incoming['quantity_change'] != -quantity
                or not values.get('unit') or values['unit'] != incoming.get('unit')
                or not values.get('node_id') or values['node_id'] != incoming.get('node_id')
                or not values.get('slot_id') or not incoming.get('slot_id') or values['slot_id'] == incoming['slot_id']
                or not values.get('occurred_at') or values['occurred_at'] != incoming.get('occurred_at')):
            continue
        if '拣货' in question and not any('拣货' in str(v.get('movement_type', '')) for v in (values, incoming)):
            continue
        pairs.append(([row, other], {'relation': 'reciprocal_same_node_movement', 'netChange': 0,
                                     'unit': values['unit'], 'movementIds': [movement_id, incoming['movement_id']]}))
    return pairs[0] if len(pairs) == 1 else (rows, None)


def _packaging_pair_question(question):
    return (bool(re.search(r'内外机|(?:室内|内)机.{0,16}(?:室外|外)机|成套', question))
            and bool(re.search(r'箱|包装', question)) and bool(re.search(r'套数|几套|多少套|附件|配对|对应', question))
            and not re.search(r'换箱|此前|历史|替换包装', question))


def _packaging_pair(scanned, matches, scans):
    deliveries = {row['values'].get('delivery_line_id') for row in matches}
    if len(deliveries) != 1 or None in deliveries or '' in deliveries:
        raise PackagingClarification('请提供唯一交付行号，以确定所问的内外机包装组。')
    skus = {row['values'].get('sku_id') for row in matches}
    if len(skus) != 1 or None in skus or '' in skus:
        raise PackagingClarification('该交付行未唯一确定成套产品，请提供产品编号。')
    rows = [row for row in scanned if row['values'].get('delivery_line_id') in deliveries and row['values'].get('sku_id') in skus]
    roles = {str(row['values'].get('set_component_role')).replace('室', ''): row for row in rows}
    if len(rows) != 2 or set(roles) != {'内机', '外机'}:
        raise ValueError('EVIDENCE_PACKAGING_PAIR_INCOMPLETE: 当前交付行缺少唯一的内机和外机包装组。')
    inside, outside = (roles[role]['values'] for role in ('内机', '外机'))
    if (not inside.get('package_id') or not outside.get('package_id') or inside['package_id'] == outside['package_id']
            or inside.get('paired_package_id') != outside['package_id'] or outside.get('paired_package_id') != inside['package_id']):
        raise ValueError('EVIDENCE_PACKAGING_PAIR_CONFLICT: 两组包装记录未相互配对。')
    counts, set_quantities = [], []
    for row in rows:
        terms = [_number_input(field, row, scans) for field in ('box_count', 'set_quantity')]
        if any(term is None or term[0] <= 0 or term[0] != term[0].to_integral_value() for term in terms):
            raise ValueError('EVIDENCE_PACKAGING_QUANTITY_INCOMPLETE: 该包装组缺少可核对的箱数或套数。')
        boxes, sets = terms
        counts.append(boxes)
        set_quantities.append(sets[0])
    if set_quantities[0] != set_quantities[1]:
        raise ValueError('EVIDENCE_PACKAGING_QUANTITY_CONFLICT: 内机与外机记录的配套数量不同。')
    total = str(int(sum(term[0] for term in counts)))
    return rows, {'operation': 'sum', 'field': 'box_count', 'unit': '箱', 'exactValue': total,
                  'displayValue': total + ' 箱', 'inputs': [term[1] for term in counts],
                  'quantityBasis': 'reciprocally_paired_indoor_and_outdoor_boxes'}


def _compact_csv_movement_row(context, raw_row):
    """Share the native row coordinates while retaining each column and fact ID."""
    facts = context['facts']
    if context['originalFormat'] != 'CSV' or len(facts) < 2:
        return context
    locators = [fact['locator'] for fact in facts]
    row = {key: value for key, value in locators[0].items() if key not in {'column', 'field'}}
    if row.get('rawRow') != raw_row or any(
        type(locator.get('column')) is not int or locator['column'] < 1
        or locator != {**row, 'column': locator['column'], 'field': fact['field']}
        for fact, locator in zip(facts, locators)
    ):
        return context
    return {**context, 'rowLocator': row, 'facts': [
        {**{key: value for key, value in fact.items() if key != 'locator'}, 'column': locator['column']}
        for fact, locator in zip(facts, locators)
    ]}


def _number_input(field, row, scans):
    number = row.get('values', {}).get(field)
    if type(number) not in NUMERIC_TYPES:
        return None
    facts = [fact for batch in scans for fact in batch.get('facts', [])
             if fact.get('field') == field and fact.get('locator', {}).get('rawRow') == row['rawRow']]
    if len(facts) != 1 or not facts[0].get('calculationEligible', False) or facts[0].get('value') != number:
        return None
    fact = facts[0]
    raw = fact.get('numberSourceExpression')
    try:
        value = Decimal(str(raw if raw is not None else number))
    except InvalidOperation:
        return None
    if not value.is_finite():
        return None
    return value, {'field': field, 'rawRow': row['rawRow'], 'factId': fact['factId'], 'value': str(value)}, fact.get('unit')


def _calculation(question, selectors, fields, rows, scans):
    if len(rows) < 2:
        return []
    operation = None
    if len(selectors) == len(rows) == 2 and re.search(r'相差|差(?:多少|几)|差值', question):
        operation = 'absolute_difference'
    elif re.search(r'合计|汇总|总和|总计|总额|总共|一共|合起来', question):
        if len(selectors) > 1 and (len(selectors) != len(rows)
                or len({canonical(key) for key in selectors}) != len(selectors)
                or any(sum(same_key(row['values'][key['field']], key) for row in rows) != 1 for key in selectors)):
            return []
        # A calculation over a group identifier must include every matched row.
        if AGGREGATE_SCOPE_LIMIT.search(question):
            return []
        operation = 'sum'
    if not operation:
        return []
    results = []
    for field in fields:
        name = field['name']
        if field['type'] not in NUMERIC or field.get('unitField'):
            continue
        unit = field.get('unit') or ('mm' if name.endswith('_mm') else 'CNY' if name.endswith('_cny') else None)
        if not unit:
            continue
        terms = [_number_input(name, row, scans) for row in rows]
        if any(term is None or term[2] not in {None, unit} for term in terms):
            continue
        value = sum(term[0] for term in terms) if operation == 'sum' else abs(terms[0][0] - terms[1][0])
        results.append({'operation': operation, 'field': name, 'unit': unit, 'exactValue': str(value),
                        'inputs': [term[1] for term in terms]})
    return results


def _delivery_unit_total(question,selectors,fields,rows,scans):
    if (not re.search(r'几台|多少台|台数|交付数量|签收数量|实收数量',question)
        or AGGREGATE_SCOPE_LIMIT.search(question) or TIME_RANGE.search(question)
        or len(selectors)!=1 or selectors[0]['field']!='shipment_id'
        or not 1 < len(rows)<=12):
        return []
    descriptors = {f['name']:f for f in fields}
    if not {'signed_quantity','unit','shipment_id','delivery_line_id'} <= descriptors.keys():
        return []
    lines = [r['values'].get('delivery_line_id') for r in rows]
    if not all(lines) or len(set(lines))!=len(rows):
        return []
    terms = [_number_input('signed_quantity',r,scans) for r in rows]
    if any(x is None or x[0]<0 or x[0]!=x[0].to_integral_value() for x in terms):
        return []
    units = [_quantity_units(r,'signed_quantity',term[2],descriptors) for r,term in zip(rows,terms)]
    if any(len(u)!=1 for u in units) or len({next(iter(u)) for u in units})!=1:
        return []
    unit = next(iter(units[0]))
    unit_facts = [f for r in rows for scan in scans for f in scan.get('facts',[])
                  if f.get('field')=='unit' and f.get('locator',{}).get('rawRow')==r['rawRow']
                  and f.get('value')==unit]
    if len(unit_facts)!=len(rows):
        return []
    results = [{'operation':'sum','field':'signed_quantity','exactValue':str(_exact_decimal_sum([x[0] for x in terms])),
                'unit':unit,'inputs':[x[1] for x in terms],'unitFactIds':[f['factId'] for f in unit_facts],
                'quantityBasis':'all_authorized_delivery_lines_for_same_shipment'}]
    if 'box_count' in descriptors and re.search(FIELD_TERMS['box_count'], question):
        boxes = [_number_input('box_count', row, scans) for row in rows]
        if all(term is not None and term[0] >= 0 and term[0] == term[0].to_integral_value()
               and term[2] in {None, '箱'} for term in boxes):
            results.append({'operation':'sum','field':'box_count','unit':'箱',
                            'exactValue':str(_exact_decimal_sum([term[0] for term in boxes])),
                            'inputs':[term[1] for term in boxes],
                            'unitBasis':'canonical_box_count_field_semantics',
                            'quantityBasis':'all_authorized_delivery_lines_for_same_shipment'})
    return results


def _selected_purchase_amount(question, selectors, fields, rows, scans):
    """Sum only individually selected lines within one explicitly selected PO."""
    counts = Counter(key['field'] for key in selectors)
    line_keys = [key for key in selectors if key['field'] == 'purchase_line_id']
    if (not re.search(r'金额|货值|价款', question)
            or counts.keys() != {'purchase_id', 'purchase_line_id'} or counts['purchase_id'] != 1
            or not 2 <= len(line_keys) == len(rows) <= 12
            or len({canonical(key) for key in line_keys}) != len(line_keys)
            or len({row['rawRow'] for row in rows}) != len(rows)
            or len(_match_rows(rows, selectors)) != len(rows)
            or any(sum(same_key(row['values'].get('purchase_line_id'), key) for row in rows) != 1
                   for key in line_keys)):
        return []
    amounts = [field for field in fields if field['name'] == 'amount_cny']
    return _calculation('指定采购行金额合计', line_keys, amounts, rows, scans)


def _selected_order_amount(question, selectors, fields, rows, scans):
    """Sum explicit order lines even when SKU adds a conjunctive predicate."""
    line_keys = [key for key in selectors if key['field']=='order_line_id']
    if (not _order_amount_question(question) or not 2 <= len(line_keys)==len(rows)<=12
            or {key['field'] for key in selectors} - {'order_line_id','sku_id'}
            or len({canonical(key) for key in line_keys})!=len(line_keys)
            or len({row['rawRow'] for row in rows})!=len(rows)
            or len(_match_rows(rows,selectors))!=len(rows)
            or any(sum(same_key(row['values'].get('order_line_id'),key) for row in rows)!=1 for key in line_keys)):
        return []
    return _calculation(question,line_keys,[field for field in fields if field['name']=='amount_cny'],rows,scans)


def _weight_share(question, row, scans):
    if not WEIGHT_SHARE.search(NEGATED_TIME.sub(' ', question)):
        return []
    gross = _number_input('gross_weight_kg', row, scans)
    capacity = _number_input('vehicle_capacity_kg', row, scans)
    if not gross or not capacity or gross[0] < 0 or capacity[0] <= 0 or {gross[2], capacity[2]} - {None, 'kg'}:
        return []
    percentage = Fraction(gross[0]) * 100 / Fraction(capacity[0])
    return [{'operation': 'percentage', 'field': 'gross_weight_kg / vehicle_capacity_kg', 'unit': '%',
             'exactValue': str(percentage), 'displayValue': str(percentage) + '%', 'inputs': [gross[1], capacity[1]]}]


def _purchase_receipt_plans(plans):
    by_role = {'order': [], 'receipt': []}
    for plan in plans:
        _, table, selectors, _ = plan
        if len(selectors) != 1 or selectors[0]['field'] != 'purchase_line_id':
            continue
        fields = {field['name'] for field in table['fields']}
        if PURCHASE_RECEIPT <= fields:
            by_role['receipt'].append(plan)
        elif PURCHASE_ORDER <= fields:
            by_role['order'].append(plan)
    orders, receipts = by_role['order'], by_role['receipt']
    if len(orders) == len(receipts) == 1 and orders[0][2] == receipts[0][2]:
        return [orders[0], receipts[0]]
    return []


def _purchase_comparison(question):
    positive = NEGATED_TIME.sub(' ', question)
    return bool(re.search(r'订量|订了|订购|订货|采购|下单|订单', positive)
                and re.search(r'实收|到货|到料|收货|合格|缺口', positive))


def asks_purchase_value_allocation(question):
    positive = NEGATED_TIME.sub(' ', question)
    return all(re.search(pattern, positive) for pattern in (
        r'金额|货值|价款', r'分摊|摊分', r'合格(?:批|量|数量|件|记录)',
    ))


def asks_purchase_line_items(question):
    positive = re.sub(r'(?:不必|不需要|不要求)[^，。；;！？?\n]*', ' ', NEGATED_TIME.sub(' ', question))
    return bool(re.search(r'(?:分别|逐[行条]|[各每][行条])[^，。；;？！!?\n]{0,18}(?:什么料|料号|零件)', positive))


def _allocation_question(question):
    return bool(re.search(r'分配记录|拣货分配|分配给|分配到', question)
                and not re.search(r'对比|比较|实际(?:拣货|出库|执行)|(?:仓储|出库|拣货)流水', question))


def _allocation_plans(question, plans):
    if not _allocation_question(question):
        return plans
    allocations = [plan for plan in plans
                   if ORDER_ALLOCATION <= {field['name'] for field in plan[1]['fields']}]
    return allocations or plans


def _batch_reconciliation_question(question):
    return (bool(ID.search(question) and re.search(r'按批|各批|逐批', question))
            and all(term in question for term in ('放行', '分配', '签收'))
            and not (AGGREGATE_SCOPE_LIMIT.search(question) or LOCAL_DATE.search(question)
                     or re.search(r'不要|不用|只看|仅看|本周|本月|最近', question)))


def _material_trace_question(question):
    return (bool(ID.search(question) and re.search(r'领用|投料|用料', question)
                 and re.search(r'来自|来源|追溯|供应批|用了多少|用量', question))
            and not (AGGREGATE_SCOPE_LIMIT.search(question) or TIME_RANGE.search(question) or LOCAL_DATE.search(question)
                     or re.search(r'本周|本月|最近|不要.*(?:领用|生产)', question)))


def _batch_reconciliation_plans(tables, assets, question):
    order_tokens = {token.casefold() for token in ID.findall(question)}
    roles = {'allocation': [], 'release': [], 'delivery': []}
    for table in tables:
        fields = {field['name']: field for field in table['fields']}
        names = fields.keys()
        if BATCH_DELIVERY <= names:
            role, required = 'delivery', BATCH_DELIVERY | (names & {'allocation_id'})
        elif ORDER_ALLOCATION <= names and 'signed_quantity' not in names:
            role, required = 'allocation', ORDER_ALLOCATION
        elif PRODUCTION_RELEASE <= names:
            role, required = 'release', {'finished_batch_id', 'sku_id', 'released_at', 'final_good', 'unit'}
        else:
            continue
        selectors = _selectors([fields['order_line_id']], order_tokens, {}) if role != 'release' else []
        if role != 'release' and len(selectors) != 1:
            continue
        quantity_field = {'allocation': 'quantity', 'release': 'final_good', 'delivery': 'signed_quantity'}[role]
        unit_field = fields[quantity_field].get('unitField')
        units = {'unit', 'quantity_unit', quantity_field + '_unit'} & names
        if isinstance(unit_field, str) and unit_field in names:
            units.add(unit_field)
        asset = assets[str(table['assetId'])]
        roles[role].append((asset, table, selectors, {key: fields[key] for key in sorted(required | units)}))
    if not all(len(plans) == 1 for plans in roles.values()):
        raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 请明确同一订单行的放行、分配及签收原表。')
    allocation, release, delivery = (role[0] for role in roles.values())
    if allocation[2] != delivery[2] or len({(plan[0]['assetId'], plan[1]['tableId']) for plan in (allocation, release, delivery)}) != 3:
        raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 三表未能绑定到同一授权订单行。')
    return allocation, release, delivery


def _fulfillment_question(question):
    return bool(re.search(r'交期前|到期前|截至交期|不晚于(?:合同)?交期', question)
                and re.search(r'收齐|交齐|签收', question)
                and re.search(r'每次签收|末次签收|最后(?:签收|提前|晚了)', question)
                and not AGGREGATE_SCOPE_LIMIT.search(question))


def _fulfillment_plans(plans):
    summaries, deliveries = [], []
    for plan in plans:
        names = {field['name'] for field in plan[1]['fields']}
        if SALES_FULFILLMENT <= names:
            summaries.append(plan)
        elif SIGNED_DELIVERY <= names:
            deliveries.append(plan)
    if len(summaries) != 1:
        return []
    ledger = summaries[0]
    related = [delivery for delivery in deliveries if ledger[2] == delivery[2]]
    if len(related) > MAX_FULFILLMENT_DELIVERY_TABLES:
        raise ValueError('NEEDS_CLARIFICATION: 有多份签收资料可能对应该订单，请明确需要核对的来源。')
    return [ledger, *related] if related else []


def _delivery_reconciliation_question(question):
    return bool(re.search(r'签收单|签收凭证|签收记录', question)
                and re.search(r'承诺(?:数|量)|订量|订单数量', question)
                and re.search(r'完成(?:数|量)|实收|实签|已签', question) and re.search(r'交期|到期', question)
                and not _fulfillment_question(question) and not AGGREGATE_SCOPE_LIMIT.search(question)
                and not re.search(r'本周|本月|最近|当周|不要(?:读|看|使用)签收', question))


def _order_receipt_plans(plans, receipt_counts):
    orders, receipts = [], []
    for plan in plans:
        names = {field['name'] for field in plan[3]}
        keys = [selector['field'] for selector in plan[2]]
        if keys.count('order_line_id') != 1 or not set(keys) <= {'order_line_id', 'sku_id'}:
            continue
        if ORDER_DELIVERY_TERMS <= names and not FULFILLMENT_FIELDS & {field['name'] for field in plan[1]['fields']}:
            orders.append(plan)
        elif SIGNED_DELIVERY <= names:
            receipts.append(plan)
    if len(orders) != 1:
        return []
    order, = orders
    related = [receipt for receipt in receipts if order[2] == receipt[2] and order[0]['assetId'] != receipt[0]['assetId']]
    if len(related) > MAX_FULFILLMENT_DELIVERY_TABLES or (len(related) > 1 and len(receipt_counts) != 1):
        return []
    return [order, *related] if related else []


def _order_delivery_rows(order, rows):
    values = order['values']
    order_line, sku = values.get('order_line_id'), values.get('sku_id')
    if not order_line or not sku:
        return []
    return [row for row in rows if row['values'].get('order_line_id') == order_line
            and row['values'].get('sku_id') == sku]


def _order_line_signoff_summary(question, plans):
    if not (re.search(r'签收|实签', question) and re.search(r'型号|产品|(?<![A-Za-z0-9_])SKU(?![A-Za-z0-9_])', question, re.I)
            and re.search(r'数量|几(?:台|套|件|个|箱)|多少|总数', question)):
        return []
    if (AGGREGATE_SCOPE_LIMIT.search(question) or LOCAL_DATE.search(question) or TIME_RANGE.search(question)
            or re.search(r'每(?:次|笔|张|份|批)|各次|逐(?:次|笔|张|条|批)|分批|第[一二三四五六七八九十\d]+(?:次|批|笔)|'
                         r'签收(?:单|凭证|记录|时间|日期|时刻)|交期|截止|截至|末次|最后一次|'
                         r'哪(?:天|次|批)|几批|何时|何日|几点|何处|地点|凭证|回单', question)
            or re.search(r'(?:不要|不用|不必|不查|不看|不问|无需|而非|而不是)[^，。；;！？?\n]{0,16}(?:签收|实签)', question)):
        return []
    projection = {'sku_id', 'signed_quantity', 'unit'}
    if re.search(r'订单数量|订量|订了|下单量|承诺量|订购数量', question):
        projection.add('quantity')
    summaries = []
    for plan in plans:
        asset, table, selectors, requested = plan
        names = {field['name'] for field in table['fields']}
        projected = {field['name'] for field in requested}
        if ([selector['field'] for selector in selectors] == ['order_line_id']
                and ORDER_LINE_SIGNOFF_SUMMARY <= names and 'delivery_line_id' not in names
                and projected <= ORDER_LINE_SIGNOFF_SUMMARY):
            fields = [field for field in table['fields'] if field['name'] in projection]
            summaries.append((asset, table, selectors, fields))
    return summaries if len(summaries) == 1 else []


def _purchase_line_quantity_question(question):
    return bool(re.search(r'采购行|订单行|两行|[二两]条|[二两]项|这[几两]行|各行|逐行|每行|两笔', question)
                and re.search(r'各自|各是|各有|各为|每条|分别|逐行|逐笔', question)
                and re.search(r'多少|几(?:台|套|件|个|箱)|数量|件数|个数|箱数|套数', question)
                and not (AGGREGATE_SCOPE_LIMIT.search(question) or LOCAL_DATE.search(question)
                         or TIME_RANGE.search(question) or NEGATED_TIME.search(question)
                         or re.search(r'到货|实收|签收|金额|单价|合计|总共|一共|为什么|原因|什么时候', question)))


def _purchase_line_quantity_plans(question, plans):
    if not _purchase_line_quantity_question(question):
        return []
    ledgers = []
    for plan in plans:
        _, table, selectors, projected = plan
        if (PURCHASE_LINE_QUANTITIES <= {field['name'] for field in table['fields']}
                and {field['name'] for field in projected} == PURCHASE_LINE_QUANTITIES):
            keys = Counter(selector['field'] for selector in selectors)
            if keys['purchase_id'] == 1 and keys['purchase_line_id'] >= 2 and len(keys) == 2:
                ledgers.append(plan)
    if len(ledgers) > 1:
        raise ValueError('NEEDS_CLARIFICATION: 有多份授权采购台账包含所问采购行，请明确来源。')
    return ledgers


def _bound_delivery_rows(order, rows):
    declared = order['values'].get('delivery_line_ids_json')
    if isinstance(declared, str):
        try:
            declared = json.loads(declared)
        except ValueError:
            return []
    if (not isinstance(declared, list) or not declared
            or any(not isinstance(key, str) or not key for key in declared)
            or len(set(declared)) != len(declared)):
        return []
    matched = _order_delivery_rows(order, rows)
    keys = [row['values'].get('delivery_line_id') for row in matched]
    if len(keys) != len(declared) or any(not isinstance(key, str) for key in keys) or set(keys) != set(declared):
        return []
    return matched


def _order_amount_question(question):
    return (re.search(r'金额|货值|含税|应付|实付', question)
            and re.search(r'订单行|订货|预算|应付款|实付款', question)
            and not re.search(r'签收|交期|交付|分批|已付了|付款记录|到账记录', question))


def _order_line_deadline_review_question(question):
    positive = NEGATED_TIME.sub(' ', question)
    return bool(re.search(r'行金额|订单行[^，。；！？\n]{0,12}(?:金额|货值)', positive)
                and re.search(r'最后签收|末次签收', positive)
                and re.search(r'截止|交期', positive))


def _supply_cost_review_question(question):
    positive = NEGATED_TIME.sub(' ', question)
    return (len(set(ID.findall(positive))) == 2 and bool(re.search(r'行金额|行货值', positive))
            and bool(re.search(r'不同|相差|差额|差异', positive))
            and bool(re.search(r'加工成本|制造成本|工序成本', positive)))


def _final_inspection_handoff_question(question):
    positive = NEGATED_TIME.sub(' ', question)
    return bool(re.search(r'终检|最终检验', positive) and re.search(r'交接|交班', positive)
                and re.search(r'统计口径|均值|平均|上下限|限值', positive))


def _order_amount_plans(question, plans):
    if not _order_amount_question(question):
        return plans
    order_plans = []
    for plan in plans:
        _, table, _, requested = plan
        fields = {field['name'] for field in table['fields']}
        if ORDER_AMOUNT <= fields and not fields & FULFILLMENT_FIELDS and any(
                field['name'] == 'amount_cny' for field in requested):
            order_plans.append(plan)
    return order_plans or plans


def _check_order_line_count(question, table_fields, rows, trace):
    if not ORDER_AMOUNT <= table_fields or table_fields & FULFILLMENT_FIELDS or not _order_amount_question(question):
        return
    requested = ORDER_LINE_COUNT.search(question)
    if not requested:
        return
    count = requested[1]
    expected = int(count) if count.isdecimal() else CHINESE_LINE_COUNTS[count]
    if len(rows) == expected:
        return
    if trace:
        trace.event('ambiguousOrderLines', {'requestedRowCount': expected, 'matchedRowCount': len(rows)})
    raise ValueError('NEEDS_CLARIFICATION: 按当前型号匹配到的订单行数与问题中指定的行数不一致。请提供要比较的订单行编号。')


def _quantity_units(row, field, native_unit, fields):
    descriptor = fields[field]
    reference = descriptor.get('unitField')
    declared = descriptor.get('unit')
    if not reference and isinstance(declared, str) and declared == native_unit:
        rule = re.fullmatch(
            r'(?P<units>[\w%°℃]+(?:[/／][\w%°℃]+)+)[，,]\s*见本行(?P<column>[a-zA-Z_][a-zA-Z_0-9]*)'
            r'(?:或关联产品[a-zA-Z_][a-zA-Z_0-9]*)?', declared)
        if rule:
            unit = row['values'].get(rule['column'])
            allowed = re.split(r'[/／]', rule['units'])
            return {unit} if isinstance(unit, str) and unit in allowed else set()
    columns = (reference,) if reference else (field + '_unit', 'quantity_unit', 'unit')
    units = [native_unit, *(row['values'].get(column) for column in columns)]
    return {unit.strip() for unit in units
            if isinstance(unit, str) and unit.strip() and not unit.strip().startswith('由记录')}


def _purchase_receipt_rows_match(order, rows):
    order_line = order['values'].get('purchase_line_id')
    part = order['values'].get('part_id')
    return bool(order_line and part and rows and all(
        row['values'].get('purchase_line_id') == order_line and row['values'].get('part_id') == part
        for row in rows))


def exact_purchase_allocation(amount, weights):
    """Allocate the recorded amount by the homologous receipt column, in exact cents."""
    cents = Fraction(Decimal(amount)) * 100
    qualified = [Fraction(Decimal(weight)) for weight in weights]
    total = sum(qualified)
    if cents < 0 or cents.denominator != 1 or total <= 0 or any(weight < 0 for weight in qualified):
        return []
    shares = [cents * weight / total for weight in qualified]
    if any(share.denominator != 1 for share in shares):
        return []
    return [f'{share.numerator // 100}.{share.numerator % 100:02}' for share in shares]


def _purchase_allocation_calculations(question, order_input, rows, qualified_terms):
    batch_counts = {int(value) if value.isdecimal() else CHINESE_LINE_COUNTS[value]
                    for value in PURCHASE_ALLOCATION_BATCH_COUNT.findall(question)}
    if len(rows) < 2 or (batch_counts and batch_counts != {len(rows)}):
        return []
    order, scans, _ = order_input
    amount = _number_input('amount_cny', order, scans)
    if not amount or not amount[2] or amount[2].casefold() not in {'cny', '元', '人民币'}:
        return []
    requested = {Decimal(value.replace(',', '')) for value in PURCHASE_ALLOCATION_REQUESTED_AMOUNT.findall(question)}
    if requested and requested != {amount[0]}:
        return []
    values = exact_purchase_allocation(amount[0], [term['value'] for term in qualified_terms])
    return [{'operation': 'allocate_purchase_value', 'field': 'amount_cny', 'unit': 'CNY',
             'exactValue': value, 'targetFactId': term['factId'], 'inputs': [amount[1], *qualified_terms]}
            for term, value in zip(qualified_terms, values)]


def _purchase_receipt_totals(question, order_input, rows, scans, fields):
    if order_input is None or AGGREGATE_SCOPE_LIMIT.search(question) or TIME_RANGE.search(question):
        return []
    order, order_scans, order_fields = order_input
    if not _purchase_receipt_rows_match(order, rows) or any(not row['values'].get('received_at') for row in rows):
        return []
    batch_ids = [row['values'].get('supplier_batch_id') for row in rows]
    if any(not batch_id for batch_id in batch_ids) or len(set(batch_ids)) != len(rows):
        return []
    totals = []
    for field in ('quantity', 'qualified_quantity'):
        terms = [_number_input(field, row, scans) for row in rows]
        if any(term is None or term[0] < 0 for term in terms):
            return []
        units_by_row = [_quantity_units(row, field, term[2], fields) for row, term in zip(rows, terms)]
        if any(len(units) > 1 for units in units_by_row):
            return []
        units = {next(iter(values), None) for values in units_by_row}
        if len(units) != 1:
            return []
        unit = units.pop()
        total = _exact_decimal_sum([term[0] for term in terms])
        totals.append({'operation': 'sum', 'field': field, 'unit': unit, 'exactValue': str(total),
                       'inputs': [term[1] for term in terms],
                       'quantityBasis': 'recorded_unit' if unit else 'unit_unspecified_in_receipt'})
    qualified = totals[1]
    ordered = _number_input('quantity', order, order_scans)
    order_units = _quantity_units(order, 'quantity', ordered[2], order_fields) if ordered else set()
    if ordered and ordered[0] >= 0 and qualified['unit'] and order_units == {qualified['unit']}:
        gap = _exact_decimal_sum([ordered[0], Decimal(qualified['exactValue']).copy_negate()])
        totals.append({'operation': 'difference', 'field': 'ordered_quantity_minus_qualified_quantity',
                       'unit': qualified['unit'], 'exactValue': str(gap),
                       'inputs': [ordered[1], *qualified['inputs']], 'quantityBasis': 'recorded_unit'})
    if asks_purchase_value_allocation(question):
        totals.extend(_purchase_allocation_calculations(question, order_input, rows, qualified['inputs']))
    return totals


def _shipment_timeline_plans(plans, *, include_events=True):
    summaries, events = [], []
    for plan in plans:
        _, table, selectors, _ = plan
        if len(selectors) != 1 or selectors[0]['field'] != 'shipment_id':
            continue
        fields = {field['name'] for field in table['fields']}
        if SHIPMENT_SUMMARY <= fields:
            summaries.append(plan)
        if SHIPMENT_EVENTS <= fields:
            events.append(plan)
    if not include_events:
        if len(summaries) > MAX_SHIPMENT_SUMMARY_TABLES:
            raise ValueError('NEEDS_CLARIFICATION: 有多份运输台账可能对应，请明确所需来源。')
        return summaries
    if len(events) != 1:
        return []
    event_table = events[0]
    related = [summary for summary in summaries if summary[2] == event_table[2]
               and summary[1]['tableId'] != event_table[1]['tableId']]
    if len(related) > MAX_SHIPMENT_SUMMARY_TABLES:
        raise ValueError('NEEDS_CLARIFICATION: 有多份运输台账可能对应，请明确需要核对的来源。')
    # Prefer the ledger that can also support the unload and destination roles.
    # The remaining ledgers still participate in the conflict check.
    def timeline_coverage(plan):
        fields = {field['name'] for field in plan[1]['fields']}
        return ('unloading_completed_at' in fields, 'destination_node_id' in fields)

    if not related:
        return []
    ordered = [*sorted(related, key=timeline_coverage, reverse=True), event_table]
    result = []
    for asset, table, selectors, requested in ordered:
        needed = SHIPMENT_EVENTS if table is event_table[1] else SHIPMENT_SUMMARY | {
            'unloading_completed_at', 'destination_node_id'}
        names = needed | {field['name'] for field in requested}
        result.append((asset, table, selectors, [field for field in table['fields'] if field['name'] in names]))
    return result


def _requested_shipment_clock(question):
    if (len(set(ID.findall(question))) != 1 or not all(term in question for term in ('台账', '事件', '签收'))
            or LOCAL_DATE.search(question) or TIME_RANGE.search(question)
            or re.search(r'(?:不看|不引用|不要(?:看|引用|读取))\s*(?:台账|事件)'
                         r'|(?:仅看|只看)\s*(?:台账|事件(?:表|流)?)(?:[，。；！？]|$)', question)):
        return None
    from services.text_temporal_literals import tokens
    temporal = [item['key'] for item in tokens(question)]
    if temporal and all(key and key[0] == 'clock' for key in temporal) and len(set(temporal)) == 1:
        return temporal[0][1]
    return None


def _road_transport_question(question):
    return (all(re.search(pattern, question) for pattern in ('装车', '出库', '发车|启程', '到达|抵达',
                                                            '签收', '时间|时刻|窗口'))
            and not re.search(r'事件流|事件表|事件号|扫描记录|对照|对比|结合|交叉核对', question))


def _road_transport_plans(plans):
    matches = [plan for plan in plans
               if len(plan[2]) == 1 and plan[2][0]['field'] == 'shipment_id'
               and set(ROAD_TRANSPORT_TIMES) <= {field['name'] for field in plan[3]}]
    if len(matches) > MAX_SHIPMENT_SUMMARY_TABLES:
        raise ValueError('NEEDS_CLARIFICATION: 有多份运输台账，请明确需要核对的来源。')
    return matches


def _shipment_duration(question, selectors, rows, scans):
    positive = NEGATED_TIME.sub(' ', question)
    if (not SHIPMENT_DURATION.search(positive) or not DURATION_WORDS.search(positive)
            or len(selectors) != 1 or selectors[0]['field'] != 'shipment_id' or len(rows) != 1):
        return []
    return _elapsed_time(positive, rows[0], scans, 'arrived_at', 'signed_at')


def _time_input(field, row, scans):
    value = row.get('values', {}).get(field)
    facts = [fact for batch in scans for fact in batch.get('facts', [])
             if fact.get('field') == field and fact.get('locator', {}).get('rawRow') == row['rawRow']
             and fact.get('value') == value]
    if isinstance(value, str) and len(facts) == 1:
        return {'field': field, 'rawRow': row['rawRow'], 'factId': facts[0]['factId'], 'value': value}


def _verified_time(source):
    if source is None:
        return None
    from services.document_time import elapsed_microseconds
    value = source['value']
    try:
        elapsed_microseconds(value, value)
        return datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        return None


def _line_amount_check(row, point, amount_field):
    quantity, unit_price, amount = [_number_input(field, row, [point])
                                    for field in ('quantity', 'unit_price_cny', amount_field)]
    quantity_unit = row['values'].get('unit')
    row_unit_fact = _record_quantity_unit_fact(row, point, quantity)
    currencies = {None, 'CNY', '元', '人民币'}
    if (quantity and unit_price and amount and isinstance(quantity_unit, str) and quantity_unit.strip()
            and (quantity[2] in {None, quantity_unit} or row_unit_fact) and amount[2] in currencies
            and unit_price[2] in currencies | {f'CNY/{quantity_unit}', f'元/{quantity_unit}'}):
        with localcontext() as context:
            context.prec = max(28, len(quantity[0].as_tuple().digits) + len(unit_price[0].as_tuple().digits))
            computed = quantity[0] * unit_price[0]
        inputs = [quantity[1], unit_price[1], amount[1]]
        if row_unit_fact:
            inputs.append({'field': 'unit', 'rawRow': row['rawRow'],
                           'factId': row_unit_fact['factId'], 'value': quantity_unit})
        return {'operation': 'order_line_amount_check', 'field': 'quantity times unit_price_cny', 'unit': 'CNY',
                'exactValue': str(computed), 'displayValue': f'{computed} CNY',
                'matchesRecordedAmount': computed == amount[0], 'inputs': inputs}


def _record_quantity_unit_fact(row, point, quantity):
    """Resolve a dictionary instruction using its verified original row unit."""
    declaration = quantity[2] if quantity else None
    unit = row['values'].get('unit')
    if (not isinstance(declaration, str) or not declaration.strip().startswith('由记录')
            or not isinstance(unit, str) or not unit.strip()):
        return None
    quantity_unit = row['values'].get('quantity_unit')
    if quantity_unit not in (None, '') and (not isinstance(quantity_unit, str) or quantity_unit.strip() != unit.strip()):
        return None
    matches = [fact for fact in point.get('facts', []) if fact['field'] == 'unit'
               and fact['value'] == unit and fact.get('locator', {}).get('rawRow') == row['rawRow']]
    return matches[0] if len(matches) == 1 else None


def _order_line_deadline_calculations(row, point):
    amount_check = _line_amount_check(row, point, 'amount_cny')
    results = [amount_check] if amount_check else []

    due, signed = [_time_input(field, row, [point]) for field in ('due_at', 'last_signed_at')]
    due_at, signed_at = _verified_time(due), _verified_time(signed)
    if due_at is not None and signed_at is not None:
        delta = due_at - signed_at
        microseconds = (delta.days * 86400 + delta.seconds) * 1000000 + delta.microseconds
        minutes, remainder = divmod(abs(microseconds), 60000000)
        hours, minute_part = divmod(minutes, 60)
        if remainder:
            interval = Decimal(microseconds) / Decimal(1000000)
            interval_unit, display = '秒', f'{abs(interval)} 秒'
        else:
            interval = microseconds // 60000000
            interval_unit = '分钟'
            display = f'{hours} 小时 {minute_part} 分钟' if hours else f'{minute_part} 分钟'
        results.append({'operation': 'deadline_time_difference', 'field': 'due_at minus last_signed_at',
                        'unit': interval_unit, 'exactValue': str(interval), 'displayValue': display,
                        'meetsDeadline': signed_at <= due_at, 'inputs': [due, signed]})
    return results


def _order_line_amount_unit_scope(row, point):
    quantity = _number_input('quantity', row, [point])
    unit = row['values'].get('unit')
    if (not quantity or not isinstance(unit, str) or not unit.strip() or quantity[2] in {None, unit}
            or _record_quantity_unit_fact(row, point, quantity)):
        return None
    matches = [fact for fact in point['facts'] if fact['field'] == 'unit' and fact['value'] == unit
               and fact.get('locator', {}).get('rawRow') == row['rawRow']]
    if len(matches) == 1:
        return {'reason': 'quantity_unit_needs_reconciliation', 'quantityFactId': quantity[1]['factId'],
                'rowUnitFactId': matches[0]['factId']}


def _drain_timing_question(question):
    positive = question.replace('能不能', '能否').replace('可不可以', '是否可以')
    return bool(re.search(r'排水|放水|排液', question) and re.search(r'等待|延迟', question)
                and re.search(r'时间|时长|耗时|计时|多久|秒', question)
                and not (AGGREGATE_SCOPE_LIMIT.search(question) or LOCAL_DATE.search(question)
                         or TIME_RANGE.search(question)
                         or re.search(r'汇总|平均|各型号|所有|本周|本月|最近|报警阈值', question)
                         or re.search(r'(?:不要|不用|不必|不应|不能|请勿|无需|不)\s*'
                                      r'(?:读取|查询|查|看|取|核对|使用|参考|统计|给出|回答|报告|输出|列出)', positive)))


def _drain_timing_plans(question, plans):
    if not _drain_timing_question(question):
        return []
    matching = [plan for plan in plans if len(plan[2]) == 1 and plan[2][0]['field'] in {'service_id', 'sku_id', 'serial_id'}
                and DRAIN_TIMING_FIELDS <= {field['name'] for field in plan[3]}]
    if len(matching) > 1:
        raise ValueError('NEEDS_CLARIFICATION: 多份授权工单可能包含所问计时，请明确服务工单及来源。')
    return matching


def _verified_drain_timing(row, scans):
    from services.document_time import elapsed_microseconds

    durations = [_number_input(field, row, scans) for field in DRAIN_DURATIONS]
    clocks = [_time_input(field, row, scans) for field in DRAIN_CLOCK]
    if (any(item is None or item[0] < 0 or item[2] not in {None, 's', '秒'} for item in durations)
            or any(clock is None for clock in clocks) or not row['values'].get('drain_timing_method')):
        return False
    try:
        elapsed = elapsed_microseconds(*(clock['value'] for clock in clocks))
    except ValueError:
        return False
    return durations[-1][0] == Decimal(elapsed) / 1000000


def _production_quality_question(question):
    return (len(set(ID.findall(question))) == 1 and '放行' in question and bool(re.search(r'终检|质检', question))
            and bool(re.search(r'当成|作为|分别|区分|对照|对比', question))
            and not re.search(r'(?:不要|不用|排除|不看|不读取|不引用)[^，。；;！？?\n]{0,12}(?:终检|质检|生产|放行)'
                              r'|(?:只|仅)(?:用|看|依据|读取|引用)\s*(?:生产|放行|质检|终检)', question))


def _production_release_question(question):
    return ('放行' in question and bool(re.search(r'合格(?:台|套|件|个)?数|final_good', question))
            and bool(re.search(r'终检单|质检单|检验凭证|qc_id', question))
            and bool(ID.search(question) or FOLLOW_UP.search(question))
            and not re.search(r'排除|剔除|只看质检|不要(?:看|读取|使用)生产|本周|本月|当天|最近|截至', question)
            and not (TIME_RANGE.search(question) or LOCAL_DATE.search(question)))


def _contract_settlement_question(question):
    return (len(set(ID.findall(question))) == 1 and bool(re.search(r'依约|约定|结算条款|结算口径|结算提醒', question))
            and bool(re.search(r'结算|付款|支付|应付', question))
            and not re.search(r'只看付款|仅以回单|不要(?:看|引用|使用)合同', question))


def _contract_settlement_plans(plans):
    matches = [plan for plan in plans if CONTRACT_SETTLEMENT <= {field['name'] for field in plan[3]}
               and len(plan[2]) == 1 and plan[2][0]['field'] == 'contract_id']
    if len(matches) != 1:
        raise ValueError('NEEDS_CLARIFICATION: 请提供唯一可核对的授权合同编号及结算条款来源。')
    return matches


def _service_observation_question(question):
    return (len(set(ID.findall(question))) == 1 and bool(re.search(r'原因|诊断|观察|检查', question))
            and bool(re.search(r'措施|处置|采取|处理|行动', question)) and bool(re.search(r'复测|复验|复核', question))
            and not re.search(r'所有|全部|每次|各次|排除|不(?:用|看|读取)工单|本周|本月|最近|截至', question)
            and not (TIME_RANGE.search(question) or LOCAL_DATE.search(question)))


def _service_replacement_question(question):
    positive = re.sub(r'(?:勿|不必)[^，。；;！？?\n]*', ' ', NEGATED_TIME.sub(' ', question))
    return bool(re.search(r'向客户|告知客户|给客户|客户沟通', positive)
                and re.search(r'换件|更换(?:零件|部件)', positive)
                and re.search(r'依据|是否|能否|要不要|需不需要', positive))


def _service_observation_plans(plans):
    keys = {'service_id', 'sku_id', 'serial_id'}
    matches = [plan for plan in plans if SERVICE_OBSERVATION <= {field['name'] for field in plan[3]}
               and len(plan[2]) == 1 and plan[2][0]['field'] in keys]
    if len(matches) != 1:
        raise BusinessRecordClarification('当前授权资料未能唯一定位所问工单。请补充完整工单号、设备序列号或能关联工单的记录。')
    return matches


def _service_contract_buyer_question(question):
    return (bool(re.search(r'买方|buyer', question, re.I)) and '合同' in question and '工单' in question
            and not re.search(r'只(?:看|用|依据|读取)|仅(?:看|用|依据|读取)|排除|不要(?:看|引用|使用)', question))


def _service_registration_question(question):
    return all(re.search(pattern, question) for pattern in (
        r'工单|售后|服务', r'服务合同|售后合同', r'序列号|序列标识|交付行',
    ))


def _service_contract_buyers(client, scope, tables, assets, tokens, aliases, trace):
    reader = Reader(client, scope)
    work_orders = []
    for table in tables:
        fields = {field['name'] for field in table['fields']}
        if not SERVICE_CONTRACT_LINK <= fields:
            continue
        identifiers = [field for field in table['fields']
                       if field['name'] in SERVICE_CONTRACT_LINK | {'case_id', 'serial_id', 'sku_id'}]
        selectors = _selectors(identifiers, tokens, aliases)
        selected = {key['value'].casefold() for key in selectors}
        if not all(token in selected or aliases.get(token) in selected for token in tokens):
            continue
        asset = assets[str(table['assetId'])]
        columns = sorted(SERVICE_CONTRACT_LINK | {key['field'] for key in selectors})
        metadata = reader.metadata(asset)
        rows, _ = scan_table(reader, asset, table, set(columns), columns, max_rows=MAX_BUSINESS_ROWS)
        work_orders.extend((asset, table, metadata, columns, row) for row in _match_rows(rows, selectors)
                           if isinstance(row['values'].get('service_id'), str) and row['values']['service_id'])

    contract_ids = {row['values'].get('service_contract_id') for _, _, _, _, row in work_orders}
    if len(contract_ids) != 1 or not all(isinstance(value, str) and value for value in contract_ids):
        raise ValueError('NEEDS_CLARIFICATION: 当前授权工单未能对应唯一服务合同，请明确工单号和合同号。')
    if len(work_orders) > 4:
        raise ValueError('NEEDS_CLARIFICATION: 当前条件对应多份工单，请明确要核对的工单号。')
    contract_id, = contract_ids
    contract_key = {'field': 'contract_id', 'sourceType': 'string', 'value': contract_id}
    contracts = []
    for table in tables:
        fields = {field['name'] for field in table['fields']}
        if 'contract_id' not in fields or not fields & CONTRACT_BUYER_FIELDS:
            continue
        asset = assets[str(table['assetId'])]
        columns = sorted({'contract_id'} | fields & (CONTRACT_BUYER_FIELDS | {'contract_type', 'contract_nature'}))
        metadata = reader.metadata(asset)
        rows, _ = scan_table(reader, asset, table, set(columns), columns, max_rows=MAX_BUSINESS_ROWS)
        contracts.extend((asset, table, metadata, columns, row) for row in _match_rows(rows, [contract_key]))
    if len(contracts) != 1:
        raise ValueError('EVIDENCE_SERVICE_CONTRACT_LINK_INCOMPLETE: 未能从授权合同原表核对唯一服务合同及其登记买方。')

    evidence = []
    for role, rows in (('contract', contracts), ('workOrder', work_orders)):
        for asset, table, metadata, columns, row in rows:
            source = _point_business_source(reader, scope, asset, table, metadata, columns, row, tokens)
            source['provenance']['serviceContractBuyerRole'] = role
            evidence.append(source)
    if trace:
        trace.event('serviceContractBuyerLink', {'roles': ['contract', *(['workOrder'] * len(work_orders))],
                                                'readIds': [entry['readId'] for entry in reader.reads]})
    return evidence


def _production_release_plans(plans):
    candidates = [plan for plan in plans if PRODUCTION_RELEASE <= {field['name'] for field in plan[3]}
                  and {selector['field'] for selector in plan[2]} <= PRODUCTION_RECORD_KEYS]
    if len(candidates) != 1:
        raise ValueError('NEEDS_CLARIFICATION: 请提供授权生产台账中可核对的 SKU 或成品批号，并明确要核对的记录范围。')
    return candidates


def _check_production_release_rows(question, rows):
    batch_ids = {row['values'].get('finished_batch_id') for row in rows}
    if not rows or None in batch_ids or '' in batch_ids or len(batch_ids) != len(rows):
        raise ValueError('NEEDS_CLARIFICATION: 请提供唯一可核对的成品批号。')
    counts = {int(value) if value.isdecimal() else CHINESE_LINE_COUNTS[value] for value in PRODUCTION_BATCH_COUNT.findall(question)}
    if counts and counts != {len(rows)}:
        raise ValueError('NEEDS_CLARIFICATION: 匹配到的生产批次数与提问不一致，请提供要核对的成品批号。')
    if len(rows) > 12:
        raise ValueError('NEEDS_CLARIFICATION: 生产批次超出一次完整核对的容量，请限定成品批号。')


def _production_quality_plans(plans):
    def role(required, key):
        return [plan for plan in plans if required <= {field['name'] for field in plan[3]}
                and len(plan[2]) == 1 and plan[2][0]['field'] == key and plan[2][0]['sourceType'] == 'string']

    release = role(PRODUCTION_RELEASE, 'finished_batch_id')
    if len(release) != 1:
        raise ValueError('EVIDENCE_PRODUCTION_QUALITY_LINK_INCOMPLETE: 请明确同一成品批的生产放行记录与关联终检报告。')
    ledger, = release
    inspections = [plan for plan in role(PRODUCTION_FINAL_INSPECTION, 'object_id')
                   if plan[0]['assetId'] != ledger[0]['assetId'] and plan[2][0]['value'] == ledger[2][0]['value']]
    if not inspections:
        raise ValueError('EVIDENCE_PRODUCTION_QUALITY_LINK_INCOMPLETE: 请明确同一成品批的生产放行记录与关联终检报告。')
    return [ledger, *inspections]


def _linked_final_inspection(release, rows, scans):
    keys = {'object_id': release['finished_batch_id'], 'qc_id': release['qc_id']}
    matches = [row for row in rows if all(value and row['values'].get(field) == value for field, value in keys.items())]
    if not matches:
        return [], None
    if len(matches) == 1:
        row, = matches
        values = row['values']
        if (release['sku_id'] and values.get('sku_id') == release['sku_id'] and values.get('item') and values.get('result')
                and re.search(r'终检|final inspection', str(values.get('qc_type')), re.I)):
            measured_at = _verified_time(_time_input('measured_at', row, scans))
            # A linked copy with an unresolved time can corroborate the other fields.
            verified_rows = matches if measured_at is not None else []
            return verified_rows, (measured_at, canonical(values['item']), canonical(values['result']))
    raise ValueError('EVIDENCE_PRODUCTION_QUALITY_LINK_INCOMPLETE: 关联终检报告的批次、型号、凭证或检验时刻未能核验。')


def _road_transport_window(row, scans):
    stamps = {field: _verified_time(_time_input(field, row, scans)) for field in ROAD_TRANSPORT_TIMES}
    if any(value is None for value in stamps.values()):
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INVALID: 授权运输台账缺少带时区的实际环节时刻。')
    if (stamps['loading_started_at'] > stamps['loading_completed_at']
            or not stamps['departed_at'] <= stamps['arrived_at'] <= stamps['signed_at']):
        raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 实际装车或发车、到达与签收时刻顺序冲突。')
    return stamps


def _shipment_summary_values(row, scans):
    values = {field: _verified_time(_time_input(field, row, scans))
              for field in (*sorted(SHIPMENT_SUMMARY), 'unloading_completed_at')
              if row['values'].get(field) is not None}
    if not SHIPMENT_SUMMARY <= set(values) or any(value is None for value in values.values()):
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INVALID: 运输台账缺少可核验的到达或签收时刻。')
    if values['signed_at'] < values['arrived_at'] or ('unloading_completed_at' in values and not
            values['arrived_at'] <= values['unloading_completed_at'] <= values['signed_at']):
        raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 到达、卸货和签收时刻顺序冲突。')
    node = row['values'].get('destination_node_id')
    if isinstance(node, str) and node:
        values['destination_node_id'] = node
    return values


def _verify_shipment_event_links(summary, rows, scans):
    node = summary.get('destination_node_id')
    required_times = {summary[field] for field in ('signed_at', 'unloading_completed_at') if field in summary}
    if len(required_times) == 1 and 'unloading_completed_at' in summary:
        raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 运输台账中的卸货和签收无法按时刻区分。')
    linked = {moment: 0 for moment in required_times}
    for row in rows:
        values = row['values']
        if values.get('event_type') and (not node or node == values.get('node_id')):
            moment = _verified_time(_time_input('timestamp', row, scans))
            if moment in linked:
                linked[moment] += 1
    if any(count != 1 for count in linked.values()):
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 运输台账的签收或卸货时刻未唯一对应当前授权事件。')


def _fulfillment_calculations(question, order_input, rows, scans, fields):
    order, order_scans, order_fields = order_input
    due_source = _time_input('due_at', order, order_scans)
    latest_source = _time_input('last_signed_at', order, order_scans)
    due, ledger_latest = _verified_time(due_source), _verified_time(latest_source)
    timestamps = [_time_input('signed_at', row, scans) for row in rows]
    dates = [_verified_time(source) for source in timestamps]
    if due is None or ledger_latest is None or not dates or any(date is None for date in dates):
        return []
    latest_index = max(range(len(dates)), key=dates.__getitem__)
    if dates[latest_index] != ledger_latest:
        return []
    ordered = _number_input('quantity', order, order_scans)
    ledger_total = _number_input('signed_quantity', order, order_scans)
    quantities = [_number_input('signed_quantity', row, scans) for row in rows]
    if not ordered or not ledger_total or any(term is None or term[0] < 0 for term in quantities):
        return []
    units = _quantity_units(order, 'quantity', ordered[2], order_fields)
    if (ordered[0] < 0 or len(units) != 1 or sum(term[0] for term in quantities) != ledger_total[0]
            or _quantity_units(order, 'signed_quantity', ledger_total[2], order_fields) != units
            or any(_quantity_units(row, 'signed_quantity', term[2], fields) != units
                   for row, term in zip(rows, quantities))):
        return []
    unit, = units
    inclusive = bool(re.search(r'截至交期|不晚于(?:合同)?交期', question))
    received = sum(term[0] for term, signed in zip(quantities, dates) if signed < due or (inclusive and signed == due))
    difference = ordered[0] - received
    time_difference = due - ledger_latest
    micros = (time_difference.days * 86400 + time_difference.seconds) * 1000000 + time_difference.microseconds
    requested = re.search(r'多少\s*(小时|分钟|秒)', question)
    duration_unit = requested[1] if requested else '分钟'
    divisor = {'小时': 3600000000, '分钟': 60000000, '秒': 1000000}[duration_unit]
    return [
        {'operation': 'deadline_receipt_sum', 'field': 'signed_quantity', 'unit': unit, 'exactValue': str(received),
         'displayValue': f'{received} {unit}',
         'quantityAtDeadline': 'inclusive' if inclusive else 'strictly_before',
         'orderFulfilledAtDeadline': difference <= 0,
         'inputs': [ordered[1], ledger_total[1], due_source, *timestamps, *(term[1] for term in quantities)]},
        {'operation': 'deadline_time_difference', 'field': 'due_at minus last_signed_at',
         'unit': duration_unit, 'exactValue': str(Fraction(micros, divisor)),
          'displayValue': f'{Fraction(micros, divisor)} {duration_unit}',
         'inputs': [due_source, latest_source, timestamps[latest_index]]},
    ]


def _delivery_signatures(rows, scans, fields):
    signatures = {}
    for row in rows:
        key = row['values'].get('delivery_line_id')
        quantity = _number_input('signed_quantity', row, scans)
        signed = _verified_time(_time_input('signed_at', row, scans))
        if not isinstance(key, str) or not key or key in signatures or (quantity and quantity[0] < 0):
            raise ValueError('EVIDENCE_DELIVERY_LINK_INVALID: 签收行缺少唯一标识或包含无效数量。')
        units = _quantity_units(row, 'signed_quantity', quantity[2] if quantity else None, fields)
        if len(units) > 1:
            raise ValueError('EVIDENCE_DELIVERY_LINK_INVALID: 签收行的计量单位不明确。')
        signatures[key] = (quantity[0] if quantity else None, signed, next(iter(units), None))
    return signatures


def _order_receipt_total(order_input, rows, scans, fields):
    order, order_scans, order_fields = order_input
    promised = _number_input('quantity', order, order_scans)
    due = _verified_time(_time_input('due_at', order, order_scans))
    quantities = [_number_input('signed_quantity', row, scans) for row in rows]
    if not promised or promised[0] < 0 or due is None or not quantities or any(term is None or term[0] < 0 for term in quantities):
        return []
    units = _quantity_units(order, 'quantity', promised[2], order_fields)
    if len(units) != 1 or any(_quantity_units(row, 'signed_quantity', term[2], fields) != units
                              for row, term in zip(rows, quantities)):
        return []
    unit, = units
    total = str(sum(term[0] for term in quantities))
    return [{'operation': 'sum', 'field': 'signed_quantity', 'unit': unit, 'exactValue': total,
             'displayValue': f'{total} {unit}', 'inputs': [term[1] for term in quantities],
             'quantityBasis': 'all_authorized_receipts_for_same_order_line_and_sku'}]


def _elapsed_time(question, row, scans, start_field, end_field):
    inputs = [_time_input(field, row, scans) for field in (start_field, end_field)]
    if any(item is None for item in inputs):
        return []
    from services.document_time import elapsed_microseconds
    try:
        elapsed = elapsed_microseconds(*(item['value'] for item in inputs))
    except ValueError:
        return []
    requested_unit = re.search(r'(?:多少|以|按|用|单位为)\s*(小时|分钟|秒)', question)
    if requested_unit:
        unit = requested_unit[1]
        divisor = {'小时': 3600000000, '分钟': 60000000, '秒': 1000000}[unit]
        value = str(Fraction(elapsed, divisor))
    elif elapsed % 60000000 == 0:
        value, unit = str(elapsed // 60000000), '分钟'
    else:
        value, unit = format(Decimal(elapsed) / 1000000, 'f'), '秒'
    return [{'operation': 'elapsed_time', 'field': start_field + ' to ' + end_field,
             'unit': unit, 'exactValue': value, 'displayValue': value + ' ' + unit,
             'inputs': inputs}]


def _point_business_source(reader, scope, asset, table, metadata, columns, row, tokens):
    operation = {'operation': 'read_table', 'tableId': table['tableId'], 'rowStart': row['rawRow'],
                 'rowEnd': row['rawRow'], 'fields': columns, 'limit': 1}
    bundle = reader.read(asset, operation)
    point_rows = bundle.get('content', {}).get('records', [])
    if (bundle['coverage'].get('returnedRowCount') != 1 or bundle['coverage'].get('truncated')
            or len(point_rows) != 1 or point_rows[0].get('rawRow') != row['rawRow']
            or canonical(point_rows[0].get('values')) != canonical(row['values'])
            or set(point_rows[0].get('absentFields', [])) != set(row.get('absentFields', []))):
        raise ValueError('Business record point read did not match its complete selector scan')
    candidate = {
        'assetId': str(asset['assetId']), 'dataVersion': str(scope[1]), 'indexVersion': str(scope[2]),
        'evidenceId': bundle['evidenceId'], 'semanticSpace': 'native.business.record',
        'locator': bundle['locator'], 'facts': {}, 'scoreType': 'verified_business_record',
        'evidenceType': 'constraint', 'qualityFlags': bundle['qualityFlags'],
        'preview': {'logicalPath': asset['logicalPath']},
        'provenance': {'scope': asset['scope'], 'originalFormat': asset['originalFormat'],
                       'verifiedBusinessRecord': True, 'explicitEntities': sorted(tokens),
                       'recordTableId': table['tableId'], 'formatDiversityContribution': False},
    }
    return source_from_bundle(scope, candidate, bundle, metadata, operation)[0]


def _scan_business_plan(reader, plan, selectors):
    asset, table, _, fields = plan
    metadata = reader.metadata(asset)
    rows, scans = scan_table(reader, asset, table, fields.keys(), list(fields), max_rows=MAX_BUSINESS_ROWS)
    return metadata, _match_rows(rows, selectors), scans


def _incoming_result_question(question):
    return (bool(re.search(r'来料|进料|\bIQC\b', question, re.I))
            and bool(re.search(r'合格吗|是否合格|合不合格|不合格吗|是否不合格|检验结果', question))
            and not re.search(r'比较|对比|汇总|统计|抽检数量|样品数|下限|上限|实测|各批|每批|补货|历史', question))


def _read_incoming_result(reader, scope, tables, assets, subject, trace):
    columns = sorted(INCOMING_RESULT_FIELDS)
    candidates = []
    for table in tables:
        asset = assets[str(table['assetId'])]
        metadata = reader.metadata(asset)
        rows, _ = scan_table(reader, asset, table, set(columns), columns, max_rows=MAX_BUSINESS_ROWS)
        for row in rows:
            values = row['values']
            if (subject == str(values.get('object_id') or '').casefold()
                    and INCOMING_INSPECTION.search(str(values.get('qc_type') or ''))):
                candidates.append((asset, table, metadata, row))
    if len(candidates) != 1 or not candidates[0][-1]['values'].get('result'):
        raise InspectionScopeUnavailable('NEEDS_CLARIFICATION',
            '当前授权资料未能唯一核对该批来料的检验结果。请提供检验单号或明确检验记录。',
            {'tableCount': len(tables), 'matchedIncomingRows': len(candidates)})
    asset, table, metadata, row = candidates[0]
    source = _point_business_source(reader, scope, asset, table, metadata, columns, row, {subject})
    source['provenance']['incomingInspectionResult'] = True
    if trace:
        trace.event('incomingInspectionResult', {'matchedIncomingRows': 1,
                    'readIds': [record['readId'] for record in reader.reads]})
    return [source]


def _replenishment_followup(question):
    positive = NEGATED_TIME.sub(' ', question)
    return (bool(REPLENISHMENT.search(positive))
            and bool(re.search(r'样品数量|样品数|样本数量|样本数|检验数量|抽检数量', positive))
            and bool(re.search(r'下限|最低限值', positive))
            and not re.search(r'换成|改成|另问|另一家|忽略|切换', positive))


def _linked_replenishment(original, rows, scans, subject):
    identity = original['values']
    if (not all(identity.get(field) for field in ('qc_id', 'sku_id', 'item'))
            or not INCOMING_INSPECTION.search(str(identity.get('qc_type') or ''))):
        return None
    disposition = str(identity.get('disposition') or '')
    if not REPLENISHMENT.search(disposition) or re.search(
            r'(?:无需|不用|未|取消|拒绝|不予|不再|不)\s*(?:安排|同意|要求)?\s*(?:补货|补供|补发)', disposition):
        return None
    related = [row for row in rows if row['values'].get('related_qc_id') == identity['qc_id']]
    if len(related) != 1:
        return None
    replacement, = related
    values = replacement['values']
    if (not all(values.get(field) for field in ('object_id', 'qc_id', 'quantity_checked_unit', 'unit'))
            or values['object_id'].casefold() == subject or values['qc_id'] == identity['qc_id']
            or any(values.get(field) != identity[field] for field in ('sku_id', 'item', 'qc_type'))):
        return None
    original_time = _verified_time(_time_input('measured_at', original, scans))
    replacement_time = _verified_time(_time_input('measured_at', replacement, scans))
    quantity = values.get('quantity_checked')
    limit = values.get('lower_limit')
    if (not original_time or not replacement_time or replacement_time <= original_time
            or type(quantity) not in NUMERIC_TYPES or type(limit) not in NUMERIC_TYPES):
        return None
    count, threshold = Decimal(str(quantity)), Decimal(str(limit))
    if not count.is_finite() or count < 0 or count != count.to_integral_value() or not threshold.is_finite():
        return None
    return replacement


def _read_replenishment_followup(reader, scope, tables, assets, subject, trace):
    columns = sorted(REPLENISHMENT_LINK_FIELDS | REPLENISHMENT_VALUE_FIELDS)
    candidates = []
    for table in tables:
        asset = assets[str(table['assetId'])]
        metadata = reader.metadata(asset)
        rows, scans = scan_table(reader, asset, table, set(columns), columns, max_rows=MAX_BUSINESS_ROWS)
        for original in rows:
            if subject == str(original['values'].get('object_id') or '').casefold():
                replacement = _linked_replenishment(original, rows, scans, subject)
                candidates.append((asset, table, metadata, original, replacement))
    if len(candidates) != 1 or not candidates[0][-1]:
        raise InspectionScopeUnavailable('NEEDS_CLARIFICATION',
            '当前授权资料未能唯一核对补货检验。请提供补货批号或检验单号，以核对样品数量、下限和单位。',
            {'tableCount': len(tables), 'matchedOriginalRows': len(candidates),
             'verifiedReplenishmentLinks': sum(bool(candidate[-1]) for candidate in candidates)})
    asset, table, metadata, original, replacement = candidates[0]
    link_columns = sorted(REPLENISHMENT_LINK_FIELDS - {'related_qc_id'})
    original = {**original, 'values': {key: value for key, value in original['values'].items() if key in link_columns},
                'absentFields': [field for field in original.get('absentFields', []) if field in link_columns]}
    evidence = []
    for role, row, fields in (('original', original, link_columns), ('replacement', replacement, columns)):
        candidate = _point_business_source(reader, scope, asset, table, metadata, fields, row, {subject})
        candidate['provenance']['replenishmentInspectionRole'] = role
        evidence.append(candidate)
    if trace:
        trace.event('replenishmentInspectionLink', {'selectorFromPriorQuestion': True, 'sourceCount': len(evidence),
                    'readIds': [record['readId'] for record in reader.reads]})
    return evidence


def _relative_inspection_pair(original, later, scans, tokens, aliases):
    left, right = original['values'], later['values']
    if (not left.get('qc_id') or right.get('related_qc_id') != left['qc_id']
            or left.get('related_qc_id') or left['qc_id'] == right.get('qc_id')
            or not all(left.get(k) and left.get(k) == right.get(k) for k in ('sku_id', 'item', 'qc_type'))):
        return False
    identities = {v.casefold() for values in (left, right) for k,v in values.items()
                  if k in {'qc_id','object_id','sku_id','part_id'} and isinstance(v,str) and v}
    sku = str(left['sku_id']).casefold()
    if any(token not in identities and aliases.get(token) != {sku} for token in tokens):
        return False
    first = _verified_time(_time_input('measured_at', original, scans))
    second = _verified_time(_time_input('measured_at', later, scans))
    if first is None or second is None or second <= first:
        return False
    for row in (original,later):
        count = _number_input('quantity_checked',row,scans)
        good = _number_input('qualified_count',row,scans)
        if (count is None or good is None or count[0] <= 0 or good[0] < 0 or good[0] > count[0]
                or count[0] != count[0].to_integral_value() or good[0] != good[0].to_integral_value()):
            return False
    return True


def _read_relative_inspection_quantities(reader, scope, tables, assets, tokens, aliases, question, trace):
    # A relative earlier inspection is proved by an explicit QC link, never by date alone.
    if re.search(r'首件|first[-_ ]*article|qc_type|检验类型.{0,8}首检|类型为.{0,4}首检', question, re.I):
        return None
    required = INSPECTION_QUANTITY_FIELDS | REPLENISHMENT_LINK_FIELDS | {'result','unit','measured_at'}
    candidates = []
    for table in tables:
        names = {f['name'] for f in table['fields']}
        if not required <= names:
            continue
        asset = assets[str(table['assetId'])]
        metadata = reader.metadata(asset)
        columns = sorted(required | ({'method'} & names))
        rows,scans = scan_table(reader,asset,table,set(columns),columns,max_rows=MAX_BUSINESS_ROWS)
        by_id = {}
        for row in rows:
            qid=row['values'].get('qc_id')
            if qid:
                by_id.setdefault(qid,[]).append(row)
        for later in rows:
            parents=by_id.get(later['values'].get('related_qc_id'),[])
            if len(parents)==1 and _relative_inspection_pair(parents[0],later,scans,tokens,aliases):
                candidates.append((asset,table,metadata,columns,parents[0],later))
    if len(candidates)!=1:
        if trace:
            trace.event('relativeInspectionLink',{'verifiedPairCount':len(candidates),'resolved':False})
        return None
    asset,table,metadata,columns,earlier,later=candidates[0]
    evidence=[]
    for role,row in (('earlier',earlier),('later',later)):
        item=_point_business_source(reader,scope,asset,table,metadata,columns,row,tokens)
        item['provenance']['relativeInspectionRole']=role
        evidence.append(item)
    if trace:
        trace.event('relativeInspectionLink',{'verifiedPairCount':1,'resolved':True,'sourceCount':2,
                    'readIds':[r['readId'] for r in reader.reads]})
    return evidence


def _first_inspection_quantity_question(question):
    positive = NEGATED_TIME.sub(' ', question.replace('能不能', '能否'))
    return bool(re.search(r'首检|首件(?:检验|检查)|first[-_ ]*(?:article|inspection|check)', positive, re.I)
                and re.search(r'数量|件数|套数|台数|样本数|多少', positive)
                and not re.search(r'来料|终检|复检|制程|过程检验|结合|汇总|累计|合计|过去|本周|本月|按日|按月', positive)
                and not (AGGREGATE_SCOPE_LIMIT.search(positive) or LOCAL_DATE.search(positive) or TIME_RANGE.search(positive)))


def _read_first_inspection_quantities(reader, scope, tables, assets, tokens, aliases, trace):
    candidates = []
    phase_counts = Counter()
    table_count = 0
    id_fields = {'object_id', 'qc_id', 'sku_id', 'part_id'}
    optional = {'sku_id', 'part_id', 'unit', 'item', 'result', 'measured_at', 'aggregation_method'}
    for table in tables:
        fields = {field['name'] for field in table['fields']}
        if not INSPECTION_QUANTITY_FIELDS <= fields:
            continue
        asset = assets[str(table['assetId'])]
        columns = sorted(INSPECTION_QUANTITY_FIELDS | (fields & optional))
        metadata = reader.metadata(asset)
        rows, _ = scan_table(reader, asset, table, set(columns), columns, max_rows=MAX_BUSINESS_ROWS)
        table_count += 1
        for row in rows:
            values = row['values']
            phase = str(values.get('qc_type') or '').strip()
            if not FIRST_INSPECTION_TYPE.fullmatch(phase):
                if re.search(r'来料|进料|incoming|\bIQC\b', phase, re.I):
                    phase_counts['incoming'] += 1
                elif re.search(r'终检|出厂|final|\b(?:FQC|OQC)\b', phase, re.I):
                    phase_counts['final'] += 1
                else:
                    phase_counts['other'] += 1
                continue
            phase_counts['first'] += 1
            ids = {value.casefold() for key, value in values.items() if key in id_fields and isinstance(value, str) and value}
            sku = str(values.get('sku_id') or '').casefold()
            if all(token in ids or (sku and aliases.get(token) == sku) for token in tokens):
                candidates.append((asset, table, metadata, columns, row))

    coverage = {'tableCount': table_count, 'rowsRead': sum(phase_counts.values()),
                'phaseCounts': dict(phase_counts), 'matchedFirstInspectionRows': len(candidates)}
    if trace:
        trace.event('firstInspectionScope', {**coverage, 'readIds': [record['readId'] for record in reader.reads]})
    if not candidates:
        message = '当前授权的检验台账中未找到可核验的首检数量记录。请提供首检原表或检验单号。'
        if not phase_counts['first'] and phase_counts['incoming'] and phase_counts['final'] and not phase_counts['other']:
            message = '当前已核对的授权检验台账只有来料检验和终检记录，无法据此填写首检数量。请提供首检原表或检验单号。'
        raise InspectionScopeUnavailable('NO_RELEVANT_EVIDENCE', message, coverage)
    records = [(row['values'].get('qc_id'), row['values'].get('object_id')) for _, _, _, _, row in candidates]
    if (any(not isinstance(value, str) or not value for record in records for value in record)
            or len(set(records)) != 1 or len(candidates) > 12):
        raise InspectionScopeUnavailable('NEEDS_CLARIFICATION', '找到多条或范围不明确的首检记录，请提供要核对的检验单号。', coverage)
    evidence = []
    for asset, table, metadata, columns, row in candidates:
        source = _point_business_source(reader, scope, asset, table, metadata, columns, row, tokens)
        source['provenance']['firstInspectionRecord'] = True
        evidence.append(source)
    return evidence


def _packaging_reconciliation_question(question):
    positive = NEGATED_TIME.sub(' ', question)
    return bool(re.search(r'包装|换箱|箱号', positive) and re.search(r'核对|对照|对比|交叉验证', positive)
                and re.search(r'两份|台账|变更|更换|消息记录|事件记录', positive))


def _packaging_reconciliation_plans(tables, assets):
    roles = {'ledger': [], 'change': []}
    for table in tables:
        fields = {field['name']: field for field in table['fields']}
        if PACKAGING_LEDGER <= fields.keys():
            role = 'ledger'
            selected = PACKAGING_LEDGER | (fields.keys() & (PACKAGING_PAIR_FIELDS | {
                'package_material','products_per_box','product_unit','package_status','replaces_package_id'}))
        elif PACKAGING_CHANGE <= fields.keys():
            role = 'change'
            selected = PACKAGING_CHANGE | (fields.keys() & {'occurred_at','timestamp','delivery_line_id','closed','case_id'})
        else:
            continue
        roles[role].append((assets[str(table['assetId'])], table, (), {key: fields[key] for key in sorted(selected)}))
    return roles if all(roles.values()) else None


def _packaging_subject_matches(tokens, aliases, values):
    exact = {value.casefold() for row in values for field, value in row.items()
             if field in PACKAGING_IDENTITY and isinstance(value, str) and value}
    skus = {row['sku_id'].casefold() for row in values if isinstance(row.get('sku_id'), str) and row['sku_id']}
    return all(token in exact or aliases.get(token) in skus for token in tokens)


def _packaging_product_calculation(row, scans):
    """Count products in one row; set quantities have a different basis."""
    boxes = _number_input('box_count', row, scans)
    per_box = _number_input('products_per_box', row, scans)
    if (not boxes or not per_box or boxes[2] not in {None,'箱'} or per_box[2] is not None
            or any(term[0] < 0 or term[0] != term[0].to_integral_value() for term in (boxes, per_box))):
        return None
    unit = row['values'].get('product_unit')
    units = [fact for bundle in scans for fact in bundle.get('facts', [])
             if fact.get('field') == 'product_unit' and fact.get('locator',{}).get('rawRow') == row['rawRow']
             and fact.get('value') == unit]
    if unit not in {'台','件','个','套'} or len(units) != 1:
        return None
    with localcontext() as context:
        context.prec = max(28, len(boxes[0].as_tuple().digits) + len(per_box[0].as_tuple().digits) + 2)
        value = boxes[0] * per_box[0]
    return {'operation':'product','field':'box_count * products_per_box','unit':unit,'exactValue':str(value),
            'inputs':[boxes[1],per_box[1]],'unitFactIds':[units[0]['factId']],
            'quantityBasis':'one_packaging_row_box_count_times_products_per_box'}


def _read_packaging_reconciliation(reader, scope, plans, tokens, aliases, trace):
    ledger = {}
    for plan in plans['ledger']:
        metadata, rows, scans = _scan_business_plan(reader, plan, ())
        for row in rows:
            values = row['values']
            key = tuple(values.get(field) for field in ('finished_batch_id', 'sku_id', 'package_id'))
            if all(isinstance(value, str) and value for value in key):
                ledger.setdefault(key, []).append((plan, metadata, row, scans))

    matches = []
    for plan in plans['change']:
        metadata, rows, scans = _scan_business_plan(reader, plan, ())
        for row in rows:
            values = row['values']
            subject = (values.get('finished_batch_id'), values.get('sku_id'))
            ids = (values.get('old_package_id'), values.get('new_package_id'))
            linkable = all(isinstance(value, str) and value for value in subject)
            links = [ledger.get((*subject, package), []) if linkable and isinstance(package, str) and package else []
                     for package in ids]
            related = [entry[2]['values'] for group in links for entry in group]
            if _packaging_subject_matches(tokens, aliases, [values, *related]):
                matches.append(((plan, metadata, row, scans), ids, links))

    if not matches:
        raise PackagingClarification('授权包装台账和变更记录中未找到所问标识，请核对成品批、型号、交付行或包装号。')
    if len(matches) != 1:
        raise PackagingClarification('当前授权范围有多条包装变更，请提供要核对的变更编号或包装号。')
    change, ids, links = matches[0]
    if ids[0] == ids[1] or any(len(group) != 1 for group in links):
        raise ValueError('EVIDENCE_PACKAGING_CHANGE_LINK_INCOMPLETE: 变更记录未能绑定唯一的旧包装与新包装台账行。')
    change_values = change[2]['values']
    if not all(isinstance(change_values.get(field), str) and change_values[field]
               for field in ('message_id', 'message_type')):
        raise ValueError('EVIDENCE_PACKAGING_CHANGE_LINK_INCOMPLETE: 变更原表未提供可核对的记录标识或类型。')
    old, new = (group[0] for group in links)
    old_line = old[2]['values'].get('delivery_line_id')
    new_line = new[2]['values'].get('delivery_line_id')
    note_line = change_values.get('delivery_line_id')
    if not isinstance(old_line, str) or not old_line or old_line != new_line or (note_line and note_line != old_line):
        raise ValueError('EVIDENCE_PACKAGING_CHANGE_LINK_CONFLICT: 旧包装与新包装未绑定同一交付行。')
    counts = [_number_input('box_count', row, scans) for _, _, row, scans in (old, new)]
    if any(term is None or term[0] < 0 or term[0] != term[0].to_integral_value() for term in counts):
        raise ValueError('EVIDENCE_PACKAGING_QUANTITY_INCOMPLETE: 旧包装或新包装的台账箱数未能核验。')

    evidence = []
    for role, (plan, metadata, row, _) in zip(('change', 'old', 'new'), (change, old, new)):
        asset, table, _, fields = plan
        source = _point_business_source(reader, scope, asset, table, metadata, list(fields), row, tokens)
        source['provenance']['packagingChangeRole'] = role
        if role in {'old','new'}:
            calculation = _packaging_product_calculation(row, [source['nativeBundle']])
            if calculation:
                context = json.loads(source['nativeModelContext'])
                context['verifiedCalculations'] = [calculation]
                source['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',',':'))
        evidence.append(source)
    if trace:
        trace.event('packagingChangeLink', {'matchedChangeRows': 1, 'linkedLedgerRows': 2,
                    'readIds': [record['readId'] for record in reader.reads]})
    return evidence


def _material_trace_plans(tables, assets, question):
    consumption, production = [], []
    for table in tables:
        fields = {field['name']: field for field in table['fields']}
        asset = assets[str(table['assetId'])]
        if MATERIAL_CONSUMPTION <= fields.keys():
            selectors = [key for key in key_candidates(question, table, ())
                         if key['field'] == 'part_id' and key['sourceType'] == 'string']
            if len(selectors) == 1:
                consumption.append((asset, table, selectors, {name: fields[name] for name in sorted(MATERIAL_CONSUMPTION)}))
        elif MATERIAL_PRODUCTION <= fields.keys():
            production.append((asset, table, (), {name: fields[name] for name in sorted(MATERIAL_PRODUCTION)}))
    if len(consumption) != 1 or not production:
        raise ValueError('NEEDS_CLARIFICATION: 请提供当前授权领用表中的料号及可核对的生产批号或型号。')
    return consumption[0], production


def _material_production_records(reader, plans, wanted):
    matched = {}
    observed = {}
    for plan in plans:
        metadata, rows, _ = _scan_business_plan(reader, plan, ())
        for row in rows:
            values = row['values']
            batch = values.get('manufacturing_batch_id')
            if not isinstance(batch, str) or batch not in wanted:
                continue
            signature = tuple(value if isinstance(value, str) and value else None
                              for value in (values.get('finished_batch_id'), values.get('sku_id')))
            previous = observed.get(batch, (None, None))
            if any(left is not None and right is not None and left != right for left, right in zip(previous, signature)):
                raise ValueError('EVIDENCE_MATERIAL_TRACE_CONFLICT: 同一制造批的成品批号或型号在授权记录中不一致。')
            observed[batch] = tuple(left or right for left, right in zip(previous, signature))
            if all(signature):
                matched.setdefault(batch, (signature, plan, metadata, row))
    if matched.keys() != wanted:
        raise ValueError('EVIDENCE_MATERIAL_TRACE_INCOMPLETE: 部分领用记录未能绑定授权生产批和型号。')
    return matched


def _read_material_trace(reader, scope, plans, question, tokens, aliases, trace):
    consumption, production = plans
    metadata, rows, scans = _scan_business_plan(reader, consumption, consumption[2])
    batches = set()
    seen = set()
    for row in rows:
        values = row['values']
        batch, record = values.get('manufacturing_batch_id'), values.get('consumption_id')
        if not isinstance(batch, str) or not batch or not isinstance(record, str) or not record or record in seen:
            raise ValueError('EVIDENCE_MATERIAL_TRACE_INCOMPLETE: 领用记录缺少唯一标识或制造批。')
        batches.add(batch)
        seen.add(record)
    if not batches:
        raise ValueError('NEEDS_CLARIFICATION: 当前授权领用表没有匹配该料号的记录。')
    matched = _material_production_records(reader, production, batches)
    batches_by_token, sku_ids = {}, set()
    for batch, ((finished, sku), _, _, _) in matched.items():
        for value in (batch, finished):
            batches_by_token.setdefault(value.casefold(), set()).add(batch)
        sku_ids.add(sku.casefold())
    part = consumption[2][0]['value'].casefold()
    selected_batches, requested_skus = set(), set()
    for token in tokens - {part}:
        candidates = batches_by_token.get(token)
        sku = aliases.get(token, token)
        if candidates and len(candidates) == 1 and sku not in sku_ids:
            selected_batches.update(candidates)
        elif not candidates and sku in sku_ids:
            requested_skus.add(sku)
        else:
            raise ValueError('NEEDS_CLARIFICATION: 请提供可唯一核对的制造批号、成品批号或型号。')
    candidates = selected_batches or set(matched)
    filtered = {batch for batch in candidates if not requested_skus or matched[batch][0][1].casefold() in requested_skus}
    if (not filtered or (selected_batches and selected_batches != filtered)
            or (requested_skus and {matched[batch][0][1].casefold() for batch in filtered} != requested_skus)):
        raise ValueError('NEEDS_CLARIFICATION: 所问型号和批次未共同匹配该料号的领用记录。')
    counts = {int(value) if value.isdecimal() else CHINESE_LINE_COUNTS[value] for value in PRODUCTION_BATCH_COUNT.findall(question)}
    if len(counts) > 1 or (counts and not min(counts)):
        raise ValueError('NEEDS_CLARIFICATION: 请明确要核对的生产批次数。')
    requested = next(iter(counts), None)
    scope_info = {'matchedBatchCount': len(filtered), 'requestedBatchCount': requested}
    selected_rows = [row for row in rows if row['values']['manufacturing_batch_id'] in filtered]
    for row in selected_rows:
        quantity = _number_input('quantity', row, scans)
        if quantity is None or quantity[0] < 0:
            raise ValueError('EVIDENCE_MATERIAL_TRACE_INCOMPLETE: 所问批次的领用数量未能核验。')
    from core.request_budget import CAPABILITIES
    if len(selected_rows) + len(filtered) > CAPABILITIES['maxSources']:
        raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 领用记录超出本次来源预算，请限定生产批号。')
    asset, table, _, fields = consumption
    evidence = [_point_business_source(reader, scope, asset, table, metadata, list(fields), row, tokens)
                for row in selected_rows]
    for source in evidence:
        source['provenance'].update(materialTraceRole='consumption', materialTraceScope=scope_info)
    for batch in sorted(filtered):
        _, (asset, table, _, fields), metadata, row = matched[batch]
        source = _point_business_source(reader, scope, asset, table, metadata, list(fields), row, tokens)
        source['provenance'].update(materialTraceRole='production', materialTraceScope=scope_info)
        evidence.append(source)
    if trace:
        trace.event('materialTraceScope', {**scope_info, 'consumptionRows': len(selected_rows),
                    'readIds': [record['readId'] for record in reader.reads]})
    return evidence


def _shipment_clock_plans(tables, assets, tokens):
    roles = {'ledger': [], 'event': []}
    for table in tables:
        fields = {field['name']: field for field in table['fields']}
        if SHIPMENT_CLOCK_LEDGER <= fields.keys():
            role, required = 'ledger', SHIPMENT_CLOCK_LEDGER
        elif SHIPMENT_CLOCK_EVENTS <= fields.keys():
            role, required = 'event', SHIPMENT_CLOCK_EVENTS
        else:
            continue
        selectors = _selectors([fields['shipment_id']], tokens, {})
        if len(selectors) == 1:
            roles[role].append((assets[str(table['assetId'])], table, selectors,
                                {key: fields[key] for key in sorted(required)}))
    if any(len(plans) != 1 for plans in roles.values()):
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 请明确同一运输单对应的计划与实际台账和事件原表。')
    ledger, events = (plans[0] for plans in roles.values())
    if ledger[2] != events[2]:
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 两份资料未能绑定同一运输单号。')
    return ledger, events


def _read_shipment_clock(reader, scope, plans, requested, tokens, trace):
    ledger, events = plans
    ledger_meta, rows, ledger_scans = _scan_business_plan(reader, ledger, ledger[2])
    if len(rows) != 1:
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 授权台账未唯一定位当前运输单。')
    ledger_row, = rows
    moments = {field: _verified_time(_time_input(field, ledger_row, ledger_scans)) for field in SHIPMENT_CLOCK_TIMES}
    destination = ledger_row['values'].get('destination_node_id')
    if any(moment is None for moment in moments.values()) or not isinstance(destination, str) or not destination:
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 台账缺少带时区的计划到达、实际到达、签收时刻或目的地。')
    if moments['arrived_at'] > moments['signed_at']:
        raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 台账实际到达时刻晚于签收。')
    event_meta, event_rows, event_scans = _scan_business_plan(reader, events, events[2])
    linked = {'arrived_at': [], 'signed_at': []}
    matching_clock = set()
    event_ids = set()
    for row in event_rows:
        values = row['values']
        event_id = values.get('event_id')
        event_type = values.get('event_type')
        moment = _verified_time(_time_input('timestamp', row, event_scans))
        if (not isinstance(event_id, str) or not event_id or event_id in event_ids or moment is None
                or not isinstance(event_type, str) or not event_type.strip()):
            raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 事件缺少唯一标识、事件类型或可核验时刻。')
        event_ids.add(event_id)
        if moment.time().isoformat().startswith(requested):
            matching_clock.add(row['rawRow'])
        event_type = event_type.strip().casefold()
        if event_type in ARRIVAL_EVENT_TYPES:
            linked['arrived_at'].append((row, moment))
        if event_type in SIGNOFF_EVENT_TYPES:
            linked['signed_at'].append((row, moment))
    matched_rows = set(matching_clock)
    for field, candidates in linked.items():
        if len(candidates) != 1:
            raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 到达与签收事件未能各自唯一定位。')
        row, moment = candidates[0]
        if row['values'].get('node_id') != destination or moment != moments[field]:
            raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 台账与事件的地点或实际时刻不一致。')
        matched_rows.add(row['rawRow'])
    from core.request_budget import CAPABILITIES
    if len(matched_rows) + 1 > CAPABILITIES['maxSources']:
        raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 同钟点事件超过本次完整读取的来源预算。')
    evidence = []
    selected_events = [row for row in event_rows if row['rawRow'] in matched_rows]
    for role, (asset, table, _, fields), metadata, selected in (
            ('ledger', ledger, ledger_meta, [ledger_row]), ('event', events, event_meta, selected_events)):
        for row in selected:
            source = _point_business_source(reader, scope, asset, table, metadata, list(fields), row, tokens)
            source['provenance']['shipmentClockRole'] = role
            context = json.loads(source['nativeModelContext'])
            if role == 'ledger':
                clock_matches = {field: moment.time().isoformat().startswith(requested) for field, moment in moments.items()}
            else:
                clock_matches = {'timestamp': row['rawRow'] in matching_clock}
            context['verifiedShipmentClockMatch'] = clock_matches
            source['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',', ':'))
            evidence.append(source)
    if trace:
        trace.event('shipmentClockCheck', {'verifiedActualEventRoles': sorted(linked),
                    'clockMatchingEventCount': len(matching_clock), 'sourceCount': len(evidence),
                    'readIds': [record['readId'] for record in reader.reads]})
    return evidence


def _verified_point_quantity(row, field, bundle, fields):
    term = _number_input(field, row, [bundle])
    if term is None or term[0] < 0:
        return None
    units = _quantity_units(row, field, term[2], fields)
    return {'exactValue': str(term[0]), 'unit': next(iter(units)) if len(units) == 1 else None,
            'factId': term[1]['factId']}


def _verified_order_allocation_quantity(row, bundle, fields):
    term = _number_input('quantity', row, [bundle])
    if term is None or term[0] < 0:
        return None
    value, evidence, declaration = term
    # A field declaration can describe how to find the unit without supplying it.
    units = _quantity_units(row, 'quantity', declaration, fields)
    if isinstance(declaration, str) and re.search(r'或|同[^\s，。]+|参照|参考|关联|见|跟随|由|\b(?:or|depends on|refer to)\b', declaration, re.I):
        units.discard(declaration.strip())
    return {'exactValue': str(value), 'unitDeclaration': declaration,
            'resolvedUnit': next(iter(units)) if len(units) == 1 else None, 'factId': evidence['factId']}


def _batch_total(quantities):
    units = {quantity['unit'] for quantity in quantities}
    if len(units) == 1 and None not in units:
        values = [Decimal(quantity['exactValue']) for quantity in quantities]
        return {'exactValue': str(_exact_decimal_sum(values)),
                'unit': next(iter(units)), 'factIds': [quantity['factId'] for quantity in quantities]}
    return None


def _exact_decimal_sum(values):
    high = max(value.adjusted() for value in values)
    low = min(value.as_tuple().exponent for value in values)
    with localcontext() as arithmetic:
        arithmetic.prec = max(arithmetic.prec, high - low + 1 + len(str(len(values))))
        return sum(values)


def _read_batch_reconciliation(reader, scope, plans, question, tokens, model_ids, trace):
    allocation, release, delivery = plans
    allocation_data = _scan_business_plan(reader, allocation, allocation[2])
    allocation_rows = allocation_data[1]
    batch_by_allocation = {}
    for row in allocation_rows:
        allocation_id = row['values'].get('allocation_id')
        batch = row['values'].get('finished_batch_id')
        if (not isinstance(allocation_id, str) or not allocation_id or allocation_id in batch_by_allocation
                or not isinstance(batch, str) or not batch):
            raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 分配表未能核验订单行的成品批及分配标识。')
        batch_by_allocation[allocation_id] = batch
    batches = set(batch_by_allocation.values())
    if not batches:
        raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 分配表未能核验订单行的成品批及分配标识。')
    counts = {int(value) if value.isdecimal() else CHINESE_LINE_COUNTS[value] for value in PRODUCTION_BATCH_COUNT.findall(question)}
    if counts and counts != {len(batches)}:
        raise ValueError('NEEDS_CLARIFICATION: 授权分配表中的成品批数与问题不一致，请提供所问批号。')
    batch_selectors = [{'field': 'finished_batch_id', 'sourceType': 'string', 'value': batch} for batch in sorted(batches)]
    release_data = _scan_business_plan(reader, release, batch_selectors)
    release_rows = release_data[1]
    by_batch = {row['values']['finished_batch_id']: row for row in release_rows}
    if len(release_rows) != len(batches) or by_batch.keys() != batches:
        raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 未能逐批定位唯一的放行记录。')
    order_line = allocation[2][0]['value'].casefold()
    requested_skus = {model_ids.get(token, token) for token in tokens - {order_line}}
    for row in release_rows:
        sku = row['values'].get('sku_id')
        if (not isinstance(sku, str) or not sku or (requested_skus and requested_skus != {sku.casefold()})
                or not _verified_time(_time_input('released_at', row, release_data[2]))):
            raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 放行表的型号或实际放行时刻未能核验。')

    delivery_data = _scan_business_plan(reader, delivery, delivery[2])
    delivery_rows = delivery_data[1]
    seen = set()
    signed_batches = set()
    for row in delivery_rows:
        values = row['values']
        line = values.get('delivery_line_id')
        batch = values.get('finished_batch_id')
        allocation_id = values.get('allocation_id')
        if (not isinstance(line, str) or not line or line in seen or not values.get('delivery_id')
                or not isinstance(batch, str) or batch not in batches
                or values.get('sku_id') != by_batch[batch]['values']['sku_id']
                or (allocation_id and (not isinstance(allocation_id, str) or batch_by_allocation.get(allocation_id) != batch))
                or not _verified_time(_time_input('signed_at', row, delivery_data[2]))):
            raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 签收行的批次、型号、分配关系或实际时刻未能核验。')
        seen.add(line)
        signed_batches.add(batch)
    if signed_batches != batches:
        raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 有成品批未找到可关联的授权签收行。')

    from core.request_budget import CAPABILITIES
    if len(allocation_rows) + len(release_rows) + len(delivery_rows) > CAPABILITIES['maxSources']:
        raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 批次核对所需的完整行数超过本次来源预算。')
    evidence = []
    batch_points = {batch: {'allocation': [], 'release': [], 'delivery': []} for batch in batches}
    for role, plan, (metadata, rows, _) in zip(('allocation', 'release', 'delivery'), plans,
                                              (allocation_data, release_data, delivery_data)):
        asset, table, _, fields = plan
        quantity_field = {'allocation': 'quantity', 'release': 'final_good', 'delivery': 'signed_quantity'}[role]
        for row in rows:
            source = _point_business_source(reader, scope, asset, table, metadata, list(fields), row, tokens)
            batch = row['values']['finished_batch_id']
            amount = _verified_point_quantity(row, quantity_field, source['nativeBundle'], fields)
            if amount is None:
                raise ValueError('EVIDENCE_BATCH_RECONCILIATION_INCOMPLETE: 批次原表缺少可核验的数量。')
            context = json.loads(source['nativeModelContext'])
            context['verifiedBatchQuantity'] = {'batchId': batch, 'role': role, **amount}
            source['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',', ':'))
            source['provenance']['batchReconciliationRole'] = role
            batch_points[batch][role].append((source, amount))
            evidence.append(source)
    comparable = 0
    for points in batch_points.values():
        allocated = _batch_total([amount for _, amount in points['allocation']])
        signed = _batch_total([amount for _, amount in points['delivery']])
        comparison = {'allocationUnit': allocated['unit'] if allocated else None, 'signedUnit': signed['unit'] if signed else None}
        if allocated and signed and allocated['unit'] == signed['unit']:
            difference = _exact_decimal_sum([Decimal(signed['exactValue']), Decimal(allocated['exactValue']).copy_negate()])
            comparison['signedMinusAllocated'] = {
                'exactValue': str(difference),
                'unit': signed['unit'], 'factIds': [*allocated['factIds'], *signed['factIds']]}
            comparable += 1
        release_source, _ = points['release'][0]
        context = json.loads(release_source['nativeModelContext'])
        context['verifiedBatchComparison'] = comparison
        release_source['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',', ':'))
    if trace:
        trace.event('businessBatchReconciliation', {'batchCount': len(batches), 'sourceCount': len(evidence),
                    'comparisonsWithSharedUnit': comparable,
                    'readIds': [record['readId'] for record in reader.reads]})
    return evidence


def records(client, scope, question, filters, intent, trace, *, prior_questions=()):
    assets = {str(asset['assetId']): asset for asset in filters.get('assets', [])
              if asset.get('scope') == 'corpus' and asset.get('nativeEvidence') and asset['originalFormat'] in FORMATS
              and (not intent.allowed or asset['originalFormat'] in intent.allowed)}
    named = {asset_id for asset_id, asset in assets.items()
             if literal(question, asset['logicalPath'].rsplit('/', 1)[-1], ignore_case=True)}
    tables = [table for table in filters.get('tables', []) if str(table['assetId']) in assets
              and (not named or str(table['assetId']) in named)]
    table_fields = [{field['name'] for field in table['fields']} for table in tables]
    release_requested = _production_release_question(question) and any(PRODUCTION_RELEASE <= fields for fields in table_fields)
    settlement_requested = (_contract_settlement_question(question)
                            and any(CONTRACT_SETTLEMENT <= fields for fields in table_fields)
                            and not any({'paid_at', 'payment_status', 'payment_id'} & fields for fields in table_fields))
    service_requested = (not settlement_requested and _service_observation_question(question)
                         and any(SERVICE_OBSERVATION <= fields for fields in table_fields))
    packaging = _packaging_pair_question(question) and any(
        PACKAGING_PAIR_FIELDS <= {field['name'] for field in table['fields']} for table in tables)
    model_ids = _model_ids(filters.get('entities', []), tables)
    tokens = _question_tokens(question, model_ids)
    if not prior_questions and len(tokens) == 1 and _incoming_result_question(question):
        inspection_tables = [table for table, fields in zip(tables, table_fields) if INCOMING_RESULT_FIELDS <= fields]
        if inspection_tables:
            return _read_incoming_result(Reader(client, scope), scope, inspection_tables, assets,
                                         next(iter(tokens)), trace)
    if prior_questions and not tokens and _replenishment_followup(question):
        prior_ids = set(match.casefold() for match in ID.findall(prior_questions[-1]))
        inspection_tables = [table for table, fields in zip(tables, table_fields)
                             if REPLENISHMENT_LINK_FIELDS | REPLENISHMENT_VALUE_FIELDS <= fields]
        if len(prior_ids) == 1 and inspection_tables:
            return _read_replenishment_followup(Reader(client, scope), scope, inspection_tables, assets,
                                               next(iter(prior_ids)), trace)
    if _service_contract_buyer_question(question) and tokens and any(SERVICE_CONTRACT_LINK <= fields for fields in table_fields):
        return _service_contract_buyers(client, scope, tables, assets, tokens, model_ids, trace)
    if (_first_inspection_quantity_question(question)
            and any(INSPECTION_QUANTITY_FIELDS <= fields for fields in table_fields)):
        mentions = re.findall(r'(?<![A-Za-z0-9_.-])[A-Za-z0-9][A-Za-z0-9_-]+(?![A-Za-z0-9_.-])', question)
        tokens.update(value.casefold() for value in mentions if MODEL_ALIAS.fullmatch(value.casefold()))
        reader = Reader(client, scope)
        related = _read_relative_inspection_quantities(reader, scope, tables, assets, tokens, model_ids, question, trace)
        if related is not None:
            return related
        return _read_first_inspection_quantities(reader, scope, tables, assets, tokens, model_ids, trace)
    if _packaging_reconciliation_question(question):
        packaging_plans = _packaging_reconciliation_plans(tables, assets)
        if packaging_plans:
            mentions = re.findall(r'(?<![A-Za-z0-9_.-])[A-Za-z0-9][A-Za-z0-9_-]+(?![A-Za-z0-9_.-])', question)
            tokens.update(value.casefold() for value in mentions if MODEL_ALIAS.fullmatch(value.casefold()))
            return _read_packaging_reconciliation(Reader(client, scope), scope, packaging_plans, tokens, model_ids, trace)
    if _material_trace_question(question) and any(MATERIAL_CONSUMPTION <= fields for fields in table_fields):
        material_plans = _material_trace_plans(tables, assets, question)
        mentions = {value.casefold() for value in re.findall(r'[A-Za-z0-9][A-Za-z0-9_-]+', question)}
        tokens.update(value for value in mentions if MODEL_ALIAS.fullmatch(value))
        return _read_material_trace(Reader(client, scope), scope, material_plans, question, tokens, model_ids, trace)
    shipment_clock = _requested_shipment_clock(question)
    if shipment_clock is not None:
        shipment_tokens = {value.casefold() for value in ID.findall(question)}
        clock_plans = _shipment_clock_plans(tables, assets, shipment_tokens)
        return _read_shipment_clock(Reader(client, scope), scope, clock_plans, shipment_clock, shipment_tokens, trace)
    if _batch_reconciliation_question(question):
        batch_plans = _batch_reconciliation_plans(tables, assets, question)
        mentions = {value.casefold() for value in re.findall(r'[A-Za-z0-9][A-Za-z0-9_-]+', question)}
        tokens.update(value for value in mentions if MODEL_ALIAS.fullmatch(value))
        return _read_batch_reconciliation(Reader(client, scope), scope, batch_plans, question, tokens, model_ids, trace)
    inherited = (_previous_packaging_subject(question, prior_questions, model_ids, tables)
                 or _previous_subject(question, prior_questions, model_ids)) if not tokens else None
    if inherited:
        tokens.update(inherited if isinstance(inherited, tuple) else (inherited,))
    positive_time = NEGATED_TIME.sub(' ', question)
    production_duration = bool(PRODUCTION_DURATION.search(positive_time) and DURATION_WORDS.search(positive_time)
                               and any(PRODUCTION_DURATION_FIELDS <= names for names in table_fields))
    if not tokens or len(tokens) > 4:
        if packaging:
            raise PackagingClarification('请提供唯一交付行号，以确定所问的内外机包装组。')
        if release_requested:
            raise ValueError('NEEDS_CLARIFICATION: 请提供要核对的生产 SKU 或成品批号。')
        if production_duration:
            raise ProductionDurationClarification('请提供要核对的生产批号或成品批号。')
        return []
    road_transport = _road_transport_question(question)
    timeline = bool(re.search(r'到达|抵达', question) and re.search(r'签收|卸货', question)
                    and (re.search(r'时间|时刻', question) or DURATION_WORDS.search(question)))
    clocks = LOCAL_CLOCK.findall(question)
    clock = tuple(int(part) if part else None for part in clocks[0]) if len(clocks) == 1 and not TIME_RANGE.search(question) else None
    field_question = _field_question(question, prior_questions)
    plans = []
    for table in tables:
        asset_id = str(table['assetId'])
        if not 0 < (table.get('rowCount') or 0) <= MAX_BUSINESS_ROWS:
            continue
        names = {field['name'] for field in table['fields']}
        selector_fields = ([field for field in table['fields'] if field['name'] in PACKAGING_KEYS]
                           if packaging and PACKAGING_PAIR_FIELDS <= names else table['fields'])
        if production_duration:
            if not PRODUCTION_DURATION_FIELDS <= names:
                continue
            # Select the batch in the production ledger, where both operands
            # come from the same original row.
            selector_fields = [field for field in selector_fields if field['name'] in PRODUCTION_RECORD_KEYS]
        selectors = _selectors(selector_fields, tokens, model_ids)
        if timeline and len(tokens) == 1 and (SHIPMENT_SUMMARY <= names or SHIPMENT_EVENTS <= names):
            selector_question = prior_questions[-1] if inherited else question
            shipment_key = next((key for key in key_candidates(selector_question, table, ())
                                 if key['field'] == 'shipment_id' and key['sourceType'] == 'string'
                                 and key['value'].casefold() in tokens), None)
            if shipment_key:
                selectors = [shipment_key]
        fields = _fields(field_question, table)
        if production_duration:
            existing_fields = {field['name'] for field in fields}
            fields.extend(field for field in table['fields'] if field['name'] in PRODUCTION_DURATION_FIELDS - existing_fields)
        if selectors and fields:
            plans.append((assets[asset_id], table, selectors, fields))
    if packaging:
        plans = [plan for plan in plans if PACKAGING_PAIR_FIELDS <= {field['name'] for field in plan[3]}
                 and len({key['field'] for key in plan[2]}) == len(plan[2])]
        if len(plans) != 1:
            raise PackagingClarification('请提供唯一交付行号及其授权包装表。')
    production_plans = _production_release_plans(plans) if release_requested and not _production_quality_question(question) else []
    settlement_plans = _contract_settlement_plans(plans) if settlement_requested else []
    drain_timing_plans = [] if settlement_plans else _drain_timing_plans(question, plans)
    service_plans = _service_observation_plans(plans) if service_requested and not drain_timing_plans else []
    if production_duration and len(plans) != 1:
        raise ProductionDurationClarification('请提供可唯一核对的生产批号或成品批号，并明确需要使用的授权生产台账。')
    if not plans:
        return []
    road_plans = _road_transport_plans(plans) if road_transport else []
    direct_duration = bool(inherited and timeline and SHIPMENT_DURATION.search(positive_time)
                           and DURATION_WORDS.search(positive_time) and not SHIPMENT_EVENT_CROSS_CHECK.search(positive_time))
    timeline_plans = _shipment_timeline_plans(plans, include_events=not direct_duration) if timeline and not road_plans else []
    corroborate_shipment = not direct_duration and bool(timeline_plans)
    quality_plans = []
    if _production_quality_question(question) and any(
            PRODUCTION_RELEASE <= {field['name'] for field in table['fields']}
            or PRODUCTION_FINAL_INSPECTION <= {field['name'] for field in table['fields']} for table in tables):
        quality_plans = _production_quality_plans(plans)
    purchase_plans = []
    if (not (settlement_plans or service_plans or quality_plans or production_plans or road_plans or timeline_plans)
            and _purchase_comparison(question)):
        purchase_plans = _purchase_receipt_plans(plans)
    fulfillment_plans = []
    reconcile_receipts = _delivery_reconciliation_question(question)
    receipt_counts = {int(value) if value.isdecimal() else CHINESE_LINE_COUNTS[value]
                      for value in SIGNOFF_COUNT.findall(question)} if reconcile_receipts else set()
    if not road_plans and not timeline_plans and not purchase_plans and (reconcile_receipts or _fulfillment_question(question)):
        fulfillment_plans = _order_receipt_plans(plans, receipt_counts) if reconcile_receipts else _fulfillment_plans(plans)
        ledger_fields = ORDER_DELIVERY_TERMS if reconcile_receipts else SALES_FULFILLMENT
        if not fulfillment_plans and any(ledger_fields <= {field['name'] for field in table['fields']}
                                         for _, table, _, _ in plans):
            raise ValueError('EVIDENCE_DELIVERY_LINK_INCOMPLETE: 未能唯一定位订单台账和对应的授权签收资料。')
    related_plans = (settlement_plans or drain_timing_plans or service_plans or quality_plans or production_plans or road_plans or timeline_plans
                     or purchase_plans or fulfillment_plans)
    purchase_line_quantities = [] if related_plans else _purchase_line_quantity_plans(field_question, plans)
    if related_plans:
        plans = related_plans
    elif purchase_line_quantities:
        plans = purchase_line_quantities
    else:
        plans = _order_amount_plans(question, _allocation_plans(question, plans))
        field_count = max(len(plan[3]) for plan in plans)
        plans = [plan for plan in plans if len(plan[3]) == field_count]
    signoff_summary = False
    if len(plans) > 2 and not (quality_plans or fulfillment_plans or timeline_plans or road_plans):
        plans = _order_line_signoff_summary(question, plans)
        if not plans:
            return []
        signoff_summary = True
    dates = LOCAL_DATE.findall(question) if inherited and timeline else []
    if dates and not timeline_plans:
        plans = [plan for plan in plans if any(field['name'] == 'arrived_at' for field in plan[3])]
    reader = Reader(client, scope)
    evidence = []
    purchase_order = None
    shipment_summary = {}
    duration_times = None
    road_times = None
    fulfillment_order = None
    production_release = None
    inspection_signature = None
    fulfillment_keys = None
    observed_deliveries = {}
    for asset, table, selectors, requested in plans:
        asset_id = str(asset['assetId'])
        metadata = reader.metadata(asset)
        key_fields = {key['field'] for key in selectors}
        columns = list(dict.fromkeys([*sorted(key_fields), *[field['name'] for field in requested]]))
        service_registration = (SERVICE_REGISTRATION_FIELDS <= {field['name'] for field in table['fields']}
                                and _service_registration_question(question))
        if len(columns) > 16:
            if fulfillment_plans:
                raise ValueError('EVIDENCE_DELIVERY_LINK_INCOMPLETE: 本次原生读取无法覆盖交期核对所需的字段。')
            if quality_plans:
                raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 本次原生读取无法覆盖放行和终检核对所需的字段。')
            if related_plans:
                return []
            continue
        checked = key_fields | {'occurred_at', 'qc_type'}.intersection(columns)
        if packaging:
            checked.update({'delivery_line_id', 'sku_id', 'package_id', 'paired_package_id', 'set_component_role'})
        if purchase_plans:
            checked.update({'part_id', 'supplier_batch_id', 'received_at'}.intersection(columns))
        if quality_plans:
            checked.update({'qc_id', 'sku_id'})
        if production_plans:
            checked.add('finished_batch_id')
        if fulfillment_plans:
            checked.update({'order_line_id', 'sku_id', 'delivery_line_id', 'delivery_line_ids_json',
                            'unit', 'quantity_unit', 'signed_quantity_unit'}.intersection(columns))
        if dates and 'arrived_at' in columns:
            checked.add('arrived_at')
        scanned, scans = scan_table(reader, asset, table, checked, columns, max_rows=MAX_BUSINESS_ROWS)
        scoped_rows = _narrow_native_rows(question, _match_rows(scanned, selectors), set(columns), clock)
        if dates and 'arrived_at' in columns:
            # A later date can correct a preceding reference. The inherited
            # shipment key is usable only when the source confirms that date.
            year, month, day = dates[0]
            suffix = f'-{int(month):02d}-{int(day):02d}T'
            date = (re.escape(year) if year else r'\d{4}') + suffix
            scoped_rows = [row for row in scoped_rows if len(dates) == 1 and re.match(
                date, str(row['values'].get('arrived_at', '')))]
        rows = _match_rows(scoped_rows, selectors)
        if purchase_line_quantities:
            line_keys = [selector for selector in selectors if selector['field'] == 'purchase_line_id']
            if (len(rows) != len(line_keys) or any(sum(same_key(row['values']['purchase_line_id'], key)
                    for row in rows) != 1 for key in line_keys)
                    or any(row['values'].get(field) in (None, '') for row in rows for field in ('quantity', 'unit'))):
                raise ValueError('NEEDS_CLARIFICATION: 未能在同一授权采购单中逐行核对所问数量和单位，请核对采购单及行号。')
        if signoff_summary and (len(rows) != 1 or any(
                rows[0]['values'].get(field) in (None, '') for field in ('sku_id', 'signed_quantity', 'unit'))):
            raise ValueError('NEEDS_CLARIFICATION: 授权订单台账中未能唯一核对该订单行的签收数量、单位和 SKU。')
        if drain_timing_plans and len(rows) != 1:
            raise ValueError('NEEDS_CLARIFICATION: 当前选择未唯一定位排水计时工单，请提供服务工单号。')
        if drain_timing_plans and not _verified_drain_timing(rows[0], scans):
            raise ValueError('EVIDENCE_DRAIN_TIMING_INCOMPLETE: 工单的计时起止、秒值或测量方法未能相互核验。')
        production_elapsed = []
        if production_duration:
            if len(rows) != 1 or len(selectors) != 1:
                raise ProductionDurationClarification('当前批号未在授权生产台账中定位到唯一记录，请明确生产批号或成品批号。')
            production_elapsed = _elapsed_time(positive_time, rows[0], scans, 'tested_at', 'released_at')
            if not production_elapsed:
                raise ProductionDurationClarification('当前生产记录缺少可核验的测试完成和正式放行时刻，请补充含时区的原始记录。')
        if service_plans and (len(rows) != 1 or not rows[0]['values'].get('service_id')):
            raise BusinessRecordClarification('当前授权资料未能唯一定位服务记录。请提供要核对的完整工单号。')
        if settlement_plans and (len(rows) != 1 or not rows[0]['values'].get('settlement')):
            raise ValueError('EVIDENCE_CONTRACT_SETTLEMENT_INCOMPLETE: 未能唯一核对该合同的实际登记结算条款。')
        if production_plans:
            _check_production_release_rows(question, rows)
        quality_role = None
        if quality_plans:
            if production_release is None:
                if (len(rows) != 1 or not rows[0]['values'].get('qc_id') or not rows[0]['values'].get('sku_id')
                        or not _verified_time(_time_input('released_at', rows[0], scans))):
                    raise ValueError('EVIDENCE_PRODUCTION_QUALITY_LINK_INCOMPLETE: 未能唯一核对该成品批的正式放行时刻。')
                production_release = rows[0]['values']
                quality_role = 'release'
            else:
                rows, signature = _linked_final_inspection(production_release, rows, scans)
                if trace:
                    trace.event('productionInspectionLink', {'assetId': asset_id, 'tableId': table['tableId'],
                                'matchedRows': int(signature is not None), 'timeVerified': bool(rows)})
                if signature is None:
                    continue
                if inspection_signature is not None and any(left is not None and right is not None and left != right
                                                             for left, right in zip(inspection_signature, signature)):
                    raise ValueError('EVIDENCE_PRODUCTION_QUALITY_LINK_CONFLICT: 同一终检报告在授权来源中的时刻、项目或结果不一致。')
                if inspection_signature is None or rows:
                    inspection_signature = signature
                if not rows:
                    continue
                quality_role = 'inspection'
        packaging_calculation = None
        if packaging:
            rows, packaging_calculation = _packaging_pair(scanned, rows, scans)
        _check_order_line_count(question, {field['name'] for field in table['fields']}, rows, trace)
        shipment_calculations = []
        if direct_duration and timeline_plans:
            if not rows:
                continue
            elapsed = _shipment_duration(question, selectors, rows, scans)
            if not elapsed:
                raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 追问所指运输单缺少唯一、可核验的到达和签收时刻。')
            times = tuple(_verified_time(value) for value in elapsed[0]['inputs'])
            if duration_times is not None and times != duration_times:
                raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 同一运输单在授权台账中的到达或签收时刻存在冲突。')
            shipment_calculations = elapsed
            duration_times = times
        if road_plans:
            if len(rows) != 1:
                raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 授权运输台账未唯一定位实际环节记录。')
            stamps = _road_transport_window(rows[0], scans)
            if road_times is not None and stamps != road_times:
                raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 同一运输记录的实际环节时刻存在冲突。')
            if road_times is None:
                shipment_calculations = _elapsed_time(positive_time, rows[0], scans, 'departed_at', 'arrived_at')
                for calculation in shipment_calculations:
                    calculation['meaning'] = '公路运输窗口，仅含实际发车至实际到达'
            road_times = stamps
        if corroborate_shipment:
            if table['tableId'] == timeline_plans[-1][1]['tableId']:
                _verify_shipment_event_links(shipment_summary, rows, scans)
            else:
                if len(rows) != 1:
                    raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 未在授权台账中唯一定位该运输记录。')
                verified_summary = _shipment_summary_values(rows[0], scans)
                if any(value != shipment_summary[key] for key, value in verified_summary.items() if key in shipment_summary):
                    raise ValueError('EVIDENCE_SHIPMENT_LINK_CONFLICT: 多份运输台账中的同一记录存在冲突。')
                if not shipment_summary:
                    shipment_calculations = _shipment_duration(question, selectors, rows, scans)
                shipment_summary.update(verified_summary)
        fulfillment_calculations = []
        if fulfillment_plans:
            if table['tableId'] == fulfillment_plans[0][1]['tableId']:
                if len(rows) != 1:
                    raise BusinessRecordClarification('当前型号未能唯一定位订单行。请提供要核对交期的订单行编号，以便逐次核对该行签收。')
                fulfillment_order = (rows[0], scans, {field['name']: field for field in requested})
            else:
                fields = {field['name']: field for field in requested}
                matched = _order_delivery_rows(fulfillment_order[0], rows)
                signatures = _delivery_signatures(matched, scans, fields)
                for key, signature in signatures.items():
                    previous = observed_deliveries.get(key, (None, None, None))
                    if any(left is not None and right is not None and left != right
                           for left, right in zip(previous, signature)):
                        raise ValueError('EVIDENCE_DELIVERY_LINK_CONFLICT: 同一签收行在授权来源中的事实不一致。')
                    observed_deliveries[key] = tuple(left if right is None else right
                                                     for left, right in zip(previous, signature))
                rows = matched if reconcile_receipts else _bound_delivery_rows(fulfillment_order[0], matched)
                if not rows or any(None in signature for signature in signatures.values()):
                    continue
                if len(rows) > 12:
                    raise ValueError('NEEDS_CLARIFICATION: 该订单的登记签收行超出一次核对容量。')
                if reconcile_receipts and (fulfillment_keys is not None or (receipt_counts and receipt_counts != {len(rows)})):
                    continue
                if fulfillment_keys is None:
                    if reconcile_receipts:
                        fulfillment_calculations = _order_receipt_total(fulfillment_order, rows, scans, fields)
                    else:
                        fulfillment_calculations = _fulfillment_calculations(question, fulfillment_order, rows, scans, fields)
                    if not fulfillment_calculations:
                        continue
                    fulfillment_keys = set(signatures)
        if related_plans and not 0 < len(rows) <= 12:
            return []
        purchase_role = None
        if purchase_plans:
            if table['tableId'] == purchase_plans[0][1]['tableId']:
                if len(rows) != 1:
                    return []
                purchase_order = (rows[0], scans, {field['name']: field for field in requested})
                purchase_role = 'order'
            else:
                if not _purchase_receipt_rows_match(purchase_order[0], rows):
                    raise ValueError('EVIDENCE_PURCHASE_RECEIPT_LINK_INCOMPLETE: 收货记录未能核验为同一采购行及料号。')
                purchase_role = 'receipt'
        if 0 < len(rows) <= 12:
            if len(selectors) == 1:
                rows, paired_movement = _movement_pair(question, rows)
            else:
                paired_movement = None
            calculations = (_calculation(question, selectors, requested, rows, scans)
                            if not packaging and len(plans) == len(key_fields) == 1 else [])
            selected_purchase_amounts = (_selected_purchase_amount(field_question, selectors, requested, rows, scans)
                                         if not related_plans and len(plans) == 1 else [])
            selected_order_amounts = (_selected_order_amount(field_question,selectors,requested,rows,scans)
                                      if not related_plans and len(plans)==1 else [])
            if selected_order_amounts:
                calculations = [c for c in calculations if (c['operation'],c['field'])!=('sum','amount_cny')]
                calculations.extend(selected_order_amounts)
            selected_order_amount = bool(selected_order_amounts)
            delivery_unit_totals = (_delivery_unit_total(field_question,selectors,requested,rows,scans)
                                    if not related_plans and len(plans)==1 else [])
            if delivery_unit_totals:
                replacement_fields = {(item['operation'], item['field']) for item in delivery_unit_totals}
                calculations = [item for item in calculations
                                if (item['operation'], item['field']) not in replacement_fields]
            calculations.extend(delivery_unit_totals)
            calculations.extend(selected_purchase_amounts)
            if purchase_role == 'receipt':
                calculations.extend(_purchase_receipt_totals(
                    question, purchase_order, rows, scans, {field['name']: field for field in requested}))
            calculations.extend(fulfillment_calculations)
            calculations.extend(shipment_calculations)
            if packaging_calculation:
                calculations.append(packaging_calculation)
            if timeline and not corroborate_shipment and not (direct_duration and timeline_plans):
                calculations.extend(_shipment_duration(question, selectors, rows, scans))
            if len(rows) == 1:
                calculations.extend(_weight_share(question, rows[0], scans))
            calculations.extend(production_elapsed)
            supply_cost_review = (len(plans) == 1 and len(rows) == len(selectors) == 2
                                  and SUPPLY_COST_REVIEW <= {field['name'] for field in requested}
                                  and all(selector['field'] == 'supplier_batch_id' for selector in selectors)
                                  and _supply_cost_review_question(question))
            table_fields = {field['name'] for field in table['fields']}
            purchase_tax_review = (len(plans) == 1 and key_fields == {'purchase_id'}
                                   and {'purchase_line_id', 'amount_cny'} <= table_fields
                                   and re.search(r'含税|未税|税口径', question))
            purchase_line_items = (purchase_tax_review and len(rows) > 1 and asks_purchase_line_items(question)
                                   and any(item['operation'] == 'sum' and item['field'] == 'amount_cny'
                                           for item in calculations))
            for index, row in enumerate(rows):
                candidate = _point_business_source(reader, scope, asset, table, metadata, columns, row, tokens)
                if selected_order_amount:
                    candidate['provenance']['orderSelectedLineAmountReview'] = True
                order_amount_scope = None
                if (len(plans) == len(rows) == 1 and ORDER_LINE_DEADLINE_REVIEW <= row['values'].keys()
                        and _order_line_deadline_review_question(question)):
                    calculations.extend(_order_line_deadline_calculations(row, candidate['nativeBundle']))
                    candidate['provenance']['orderLineAmountDeadlineReview'] = True
                    order_amount_scope = _order_line_amount_unit_scope(row, candidate['nativeBundle'])
                if (len(plans) == len(rows) == 1 and FINAL_INSPECTION_HANDOFF <= row['values'].keys()
                        and _final_inspection_handoff_question(question)
                        and re.search(r'终检|final inspection', str(row['values']['qc_type']), re.I)):
                    candidate['provenance']['finalInspectionHandoffRecord'] = True
                if supply_cost_review:
                    candidate['provenance']['supplyCostReview'] = True
                if purchase_tax_review:
                    candidate['provenance']['purchaseAmountTaxReview'] = True
                if purchase_line_items:
                    candidate['provenance']['purchaseLineItemsRequested'] = True
                if delivery_unit_totals:
                    candidate['provenance']['deliveryUnitQuantityReview'] = True
                delivery_box_review = any(total['field'] == 'box_count' for total in delivery_unit_totals)
                if delivery_box_review:
                    candidate['provenance']['deliveryQuantityRecipe'] = 'delivery-unit-and-box-2'
                if selected_purchase_amounts:
                    candidate['provenance']['purchaseSelectedLineAmountReview'] = True
                if quality_role:
                    candidate['provenance']['productionQualityRole'] = quality_role
                if service_registration:
                    candidate['provenance']['verifiedServiceRegistration'] = True
                if SERVICE_OBSERVATION <= row['values'].keys() and _service_replacement_question(question):
                    candidate['provenance']['serviceReplacementEvidence'] = True
                if drain_timing_plans:
                    candidate['provenance']['verifiedDrainTiming'] = True
                if (ORDER_ALLOCATION <= table_fields and 'signed_quantity' not in table_fields
                        and {'finished_batch_id', 'order_line_id', 'quantity'} <= row['values'].keys()
                        and all(row['values'][field] not in (None, '') for field in ('finished_batch_id', 'order_line_id', 'quantity'))):
                    candidate['provenance']['verifiedOrderAllocation'] = True
                if settlement_plans:
                    candidate['provenance']['contractSettlementTerms'] = True
                if purchase_role:
                    candidate['provenance']['purchaseReceiptRole'] = purchase_role
                if fulfillment_plans and reconcile_receipts:
                    candidate['provenance']['orderReceiptRole'] = (
                        'order' if table['tableId'] == fulfillment_plans[0][1]['tableId'] else 'receipt')
                if road_plans:
                    candidate['provenance']['verifiedRoadTransportTimeline'] = True
                if production_elapsed:
                    candidate['provenance']['verifiedProductionDuration'] = True
                if corroborate_shipment:
                    candidate['provenance']['shipmentTimelineRole'] = (
                        'event' if table['tableId'] == timeline_plans[-1][1]['tableId']
                        else 'primary_ledger' if table['tableId'] == timeline_plans[0][1]['tableId'] else 'ledger_witness')
                is_allocation = candidate['provenance'].get('verifiedOrderAllocation')
                if is_allocation or purchase_role or paired_movement or supply_cost_review or order_amount_scope or delivery_box_review or (calculations and index == 0):
                    context = json.loads(candidate['nativeModelContext'])
                    if delivery_box_review:
                        context['deliveryQuantityRecipe'] = 'delivery-unit-and-box-2'
                    if order_amount_scope:
                        context['orderAmountRecalculationLimit'] = order_amount_scope
                    if supply_cost_review:
                        line_check = _line_amount_check(row, candidate['nativeBundle'], 'line_amount_cny')
                        if line_check:
                            context['verifiedSupplyLineAmount'] = line_check
                    if is_allocation:
                        fields = {field['name']: field for field in requested}
                        allocation = _verified_order_allocation_quantity(row, candidate['nativeBundle'], fields)
                        if allocation:
                            context['verifiedOrderAllocationQuantity'] = allocation
                    if purchase_role:
                        fields = {field['name']: field for field in requested}
                        amount_fields = ('quantity',) if purchase_role == 'order' else ('quantity', 'qualified_quantity')
                        context['verifiedPurchaseQuantities'] = {
                            field: _verified_point_quantity(row, field, candidate['nativeBundle'], fields)
                            for field in amount_fields
                        }
                        if purchase_role == 'order' and asks_purchase_value_allocation(question):
                            amount = _number_input('amount_cny', row, [candidate['nativeBundle']])
                            if amount and amount[2] and amount[2].casefold() in {'cny', '元', '人民币'}:
                                context['verifiedPurchaseValue'] = {'exactValue': str(amount[0]), 'unit': 'CNY',
                                                                    'factId': amount[1]['factId']}
                    if paired_movement:
                        context = _compact_csv_movement_row(context, row['rawRow'])
                    if calculations and index == 0:
                        context['verifiedCalculations'] = calculations
                    if paired_movement and index == 0:
                        context['verifiedMovement'] = paired_movement
                        candidate['provenance']['verifiedMovementPair'] = True
                    candidate['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',', ':'))
                evidence.append(candidate)
            if trace:
                trace.event('businessNativeRecords', {'assetId': asset_id, 'tableId': table['tableId'],
                            'selectors': selectors, 'matchedRows': [row['rawRow'] for row in rows],
                            'readIds': [record['readId'] for record in reader.reads], 'calculations': calculations,
                            'verifiedMovement': paired_movement, 'selectorFromPriorQuestion': bool(inherited),
                            'orderLineSignoffSummary': signoff_summary,
                            'purchaseLineQuantities': bool(purchase_line_quantities)})
    if quality_plans and (inspection_signature is None or inspection_signature[0] is None):
        raise ValueError('EVIDENCE_PRODUCTION_QUALITY_LINK_INCOMPLETE: 授权质检资料缺少与放行凭证对应且带时区的成品终检记录。')
    if fulfillment_plans and receipt_counts and receipt_counts != {len(observed_deliveries)}:
        raise ValueError('NEEDS_CLARIFICATION: 授权签收记录数量与提问不一致，请明确需要核对的签收单。')
    if fulfillment_plans and (not fulfillment_keys or fulfillment_keys != set(observed_deliveries)):
        raise ValueError('EVIDENCE_DELIVERY_LINK_INCOMPLETE: 授权签收行未与订单台账的完整签收清单对应。')
    if direct_duration and timeline_plans and duration_times is None:
        raise ValueError('EVIDENCE_SHIPMENT_LINK_INCOMPLETE: 未在授权台账中找到追问所指日期的运输单。')
    return evidence
