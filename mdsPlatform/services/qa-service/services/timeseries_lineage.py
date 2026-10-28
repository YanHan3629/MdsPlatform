"""Independently calculate a bounded series window from authorized native rows.

The native table is read completely, not reconstructed from topK hits. The
existing executor result is retained and checked separately. Exact source
decimals, source time policy, exclusions and duplicate witnesses stay in the
calculation lineage; only its bounded result and scope enter the model.
"""
import math
import re
import time
import uuid
from datetime import datetime, timezone
from decimal import Decimal, Inexact, localcontext
from fractions import Fraction
from zoneinfo import ZoneInfo

from services.calculation_lineage import canonical, checksum, equivalent_value, require
from services.read_receipt import verify as verify_receipt
from services.interval_lineage import OPERATIONS as INTERVAL_OPERATIONS, calculate as calculate_interval

VERSION = 'native-timeseries-calculation-2026-10-25.3'
MAX_ROWS = 512
MAX_READS = 24
MAX_BYTES = 4 * 1024 * 1024
MAX_SECONDS = 45
OPERATIONS = {'count', 'sum', 'min', 'max', 'average'} | INTERVAL_OPERATIONS
TIME_NAMES = {'timestamp', 'datetime', 'time', 'date', 'recorded_at', '时间', '日期', '时间戳', '采样时间'}


def instant(value, zone=None):
    require(isinstance(value, str), 'TIMESERIES_NATIVE_TIME_TYPE_UNSUPPORTED')
    fraction = re.search(r'[T ]\d\d:\d\d:\d\d\.(\d+)', value)
    require(not fraction or not fraction[1][6:].strip('0'), 'TIMESERIES_NATIVE_TIME_PRECISION_UNSUPPORTED')
    try:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if parsed.tzinfo is None:
            require(zone, 'TIMESERIES_NATIVE_TIMEZONE_UNAVAILABLE')
            tz = ZoneInfo(zone)
            candidates = [parsed.replace(tzinfo=tz, fold=fold) for fold in (0, 1)]
            valid = [candidate for candidate in candidates if candidate.astimezone(timezone.utc).astimezone(tz).replace(tzinfo=None) == parsed]
            require(valid and len({candidate.utcoffset() for candidate in valid}) == 1,
                    'TIMESERIES_NATIVE_LOCAL_TIME_AMBIGUOUS_OR_NONEXISTENT')
            parsed = valid[0]
        return parsed.astimezone(timezone.utc)
    except (ValueError, KeyError) as error:
        raise ValueError('TIMESERIES_NATIVE_TIME_UNAVAILABLE: 原生时间或时区无法无歧义解释') from error


def exact_number(record, field, fact):
    value = record['values'][field]
    require(fact.get('calculationEligible', True) and record.get('calculationEligibility', {}).get(field, True),
            'TIMESERIES_NATIVE_CELL_NOT_CALCULABLE')
    number = record.get('numberLexemes', {}).get(field)
    numeric = isinstance(value, (int, float)) and not isinstance(value, bool)
    encoded = isinstance(value, str) and number and number.get('decodedValueStatus') == 'SOURCE_LEXEME_STRING' and value == number.get('lexeme')
    if not numeric and not encoded:
        return None
    require(number and number.get('calculationEligible', True) and fact.get('numberSourceExpression') == number.get('lexeme'),
            'TIMESERIES_NATIVE_NUMBER_LEXEME_REQUIRED')
    try:
        decimal = Decimal(number['lexeme'])
        require(decimal.is_finite() and len(decimal.as_tuple().digits) <= 4096 and abs(decimal.as_tuple().exponent) <= 4096,
                'TIMESERIES_NATIVE_NUMBER_PRECISION_LIMIT')
        require(encoded or float(decimal) == float(value), 'TIMESERIES_NATIVE_DECODED_NUMBER_MISMATCH')
        return Fraction(decimal)
    except ArithmeticError as error:
        raise ValueError('TIMESERIES_NATIVE_NUMBER_UNAVAILABLE') from error


def exact_result(value):
    with localcontext() as context:
        context.prec = 4096
        decimal = Decimal(value.numerator) / Decimal(value.denominator)
        rounded = bool(context.flags[Inexact])
        if rounded:
            context.prec = 50
            decimal = Decimal(value.numerator) / Decimal(value.denominator)
        return {'exactValue': str(decimal) if not rounded else None,
                'exactRatio': {'numerator': format(Decimal(value.numerator), 'f'), 'denominator': format(Decimal(value.denominator), 'f')},
                'decimalValue': str(decimal), 'decimalRounded': rounded, 'decimalPrecision': context.prec,
                'arithmetic': 'exact-rational-source-lexemes-v1'}


def observed_range(timestamps):
    """Observed instants are extrema, never a claim of continuous coverage."""
    return {'start': min(timestamps).isoformat(), 'end': max(timestamps).isoformat(), 'timeBasis': 'UTC'} if timestamps else None


def scope_details(lineage):
    """Render only fields bound into the native calculation's scope fact."""
    scope = next(fact['value'] for fact in lineage['facts'] if fact['factId'] == lineage['scopeFactId'])
    window = scope['requestedTimeRange']; policy = scope['sourceTimePolicy']
    zone = policy.get('sourceTimezone')
    display_zone = ZoneInfo(zone) if zone else datetime.fromisoformat(window['start'].replace('Z', '+00:00')).tzinfo
    def display(value):
        return instant(value).astimezone(display_zone).isoformat()
    count_mode=scope.get('countMode')
    sample_label='窗口内物理记录' if count_mode=='records' else '去重后有效数值采样点' if count_mode=='numeric_samples' else '实际可计算样本'
    details = ['对象 '+scope['entityId'], f"已核对源表全部 {scope['scannedRowCount']} 条记录并完整筛选",
        f"{sample_label} {scope['sampleCount']} 个",
        '请求窗口 '+window['start']+' 至 '+window['end']+('（含两端）' if window['boundary']=='[start,end]' else '（含起点、不含终点）')]
    for key, label in (('actualSampleTimeRange', '窗口内记录起止' if count_mode=='records' else '实际可计算样本起止'), ('sourceRecordTimeRange', '源表中该序列记录起止')):
        span = scope[key]
        details.append(label+' '+display(span['start'])+' 至 '+display(span['end']) if span else label+'：无')
    details.append('起止时刻仅表示已记录点，不证明其间连续或无缺测')
    outside = scope['requestedBoundsOutsideSourceExtent']
    if outside.get('startBeforeFirst'):details.append('请求起点早于源表中该序列首条记录')
    if outside.get('endAfterLast'):details.append('请求终点晚于源表中该序列末条记录，结果仅针对实际记录样本')
    if count_mode=='records':details.append('按物理记录计数；同一时刻的重复记录保留，未按业务键去重；未读取指标值，空值、类型或公式缓存不影响记录是否存在')
    elif count_mode=='numeric_samples':details.append(f"合并 {scope['duplicateEquivalentSampleCount']} 条同一时刻同值的重复样本；同一时刻数值或单位冲突则停止计算")
    else:details.append(f"合并 {scope['duplicateEquivalentSampleCount']} 条同一时刻同值的重复样本；同一时刻数值冲突则停止计算")
    interval=scope.get('intervalCalculation')
    if interval:
        def amount(value):return value['numerator'] if value['denominator']=='1' else '('+value['numerator']+')/('+value['denominator']+')'
        method='相邻源点线性连接、按裁剪后的梯形面积加权' if interval['calculationInterpolation']=='linear' else '前一个源点保持至下一个源点'
        details.append('区间估计口径：'+method+'；不外推，不能当作连续实测')
        details.append(f"区间使用 {interval['supportPointCount']} 个源采样点，含必要的窗口外边界支持点；累计纳入 "+amount(interval['includedDurationSecondsExactRatio'])+' 秒，未覆盖 '+amount(interval['uncoveredDurationSecondsExactRatio'])+' 秒')
        details.append(f"排除 {interval['excludedIntervalCount']} 个缺测或长间隔区间；不跨空值连线、未自动剔除离群值")
        if interval['thresholdExpression'] is not None:details.append('严格大于阈值 '+interval['thresholdExpression']+' '+interval['sourceMeasurementUnit'])
    elif count_mode!='records':
        details.append(f"窗口内 {len(scope['excludedRows'])} 项空值或非数值未参与计算；未插值、未自动剔除离群值")
    if scope['locator'].get('sheet'):details.append('工作表 '+scope['locator']['sheet'])
    if zone:details.append('源声明时区 '+zone)
    for declaration in scope['sourceDeclarations']:
        locator = declaration['locator']
        details.append('源声明（'+locator['sheet']+'!'+locator['cell']+'）：'+declaration['text'])
    return details


def read_calculation(client, scope, item, filters, cache):
    query = item.get('provenance', {}).get('validatedQuery', {})
    facts = item.get('facts', {})
    require(query.get('retrievers') == ['timeseries'] and query.get('operation') == facts.get('operation'), 'TIMESERIES_NATIVE_QUERY_MISMATCH')
    require(facts.get('operation') in OPERATIONS, 'TIMESERIES_NATIVE_OPERATION_NOT_VERIFIED')
    interval_operation=facts.get('operation') in INTERVAL_OPERATIONS
    typed_count=facts.get('operation')=='count' and facts.get('countMode') in {'records','numeric_samples'}
    count_mode=(query.get('count_mode') or 'numeric_samples') if typed_count else None
    record_count=count_mode=='records'
    if typed_count:require(facts['countMode']==count_mode,'TIMESERIES_NATIVE_COUNT_MODE_MISMATCH')
    require(not query.get('hard_conditions') and not query.get('object_conditions') and not query.get('compare_time_range'), 'TIMESERIES_NATIVE_FILTER_NOT_VERIFIED')
    require(not facts.get('status') and facts.get('completeAuthorizedRange') is True, 'TIMESERIES_NATIVE_EXECUTION_UNAVAILABLE')
    require(item.get('dataVersion') == str(scope[1]) and item.get('indexVersion') == str(scope[2]), 'TIMESERIES_NATIVE_SNAPSHOT_MISMATCH')
    assets = query.get('asset_ids', [])
    require(len(assets) == 1 and (not item.get('assetId') or item['assetId'] == assets[0]), 'TIMESERIES_NATIVE_SINGLE_SOURCE_REQUIRED')
    asset_id = str(assets[0]); metric = query.get('metric'); entity = query.get('target_entity')
    require(metric == facts.get('metric') and entity and entity == item.get('entityId'), 'TIMESERIES_NATIVE_SERIES_MISMATCH')
    asset = next((asset for asset in filters.get('assets', []) if str(asset['assetId']) == asset_id), {})
    require(asset.get('nativeEvidence'), 'TIMESERIES_NATIVE_INDEX_REBUILD_REQUIRED')
    tables = [table for table in filters.get('tables', []) if str(table['assetId']) == asset_id
        and (not query.get('table_id') or table['tableId']==query['table_id'])
        and (not metric and record_count or metric in {field['name'] for field in table['fields']})]
    require(len(tables) == 1, 'TIMESERIES_NATIVE_TABLE_AMBIGUOUS')
    table = tables[0]; table_id = table['tableId']
    require(table.get('originalFormat') in {'CSV', 'TXT', 'XLSX'}, 'TIMESERIES_NATIVE_SOURCE_FORMAT_NOT_VERIFIED')
    fields = {field['name'] for field in table['fields']}
    time_fields = [field for field in fields if field.lower() in TIME_NAMES]
    require(len(time_fields) == 1 and ('series_id' in fields or entity == asset_id), 'TIMESERIES_NATIVE_TIME_OR_ENTITY_FIELD_UNRESOLVED')
    time_field = time_fields[0]
    selected_fields = sorted({time_field} | ({metric} if metric and not record_count else set()) | ({'series_id'} if 'series_id' in fields else set()))
    require(0 < table.get('rowCount', 0) <= MAX_ROWS, 'TIMESERIES_NATIVE_ROW_BUDGET')
    if asset_id not in cache:
        cache[asset_id] = client.evidence_metadata(*scope, asset_id)
    metadata = cache[asset_id]
    require(metadata.get('nativeEvidenceStatus') == 'AVAILABLE', 'TIMESERIES_NATIVE_MANIFEST_UNAVAILABLE')
    schemas = [rep for rep in metadata['representations'] if rep['kind'] == 'table_schema' and rep.get('selector', {}).get('tableId') == table_id]
    pages = [rep for rep in metadata['representations'] if rep['kind'] == 'table_records' and rep.get('selector', {}).get('tableId') == table_id]
    require(len(schemas) == 1 and pages, 'TIMESERIES_NATIVE_TABLE_MANIFEST_INCOMPLETE')
    started = time.monotonic(); reads = []; byte_count = 0; identity = None

    def read(operation):
        nonlocal byte_count, identity
        require(len(reads) < MAX_READS and time.monotonic() - started < MAX_SECONDS, 'TIMESERIES_NATIVE_READ_BUDGET')
        bundle = client.read_evidence(*scope, asset_id, operation)
        receipt = verify_receipt(bundle)
        byte_count += len(canonical(bundle).encode())
        require(byte_count <= MAX_BYTES and time.monotonic() - started < MAX_SECONDS, 'TIMESERIES_NATIVE_READ_BUDGET')
        require(receipt['operation'] == operation, 'TIMESERIES_NATIVE_READ_REQUEST_MISMATCH')
        current = bundle.get('identity', {})
        require(all(current.get(key) == str(value) for key, value in zip(('datasetId', 'versionId', 'indexVersionId'), scope)), 'TIMESERIES_NATIVE_SNAPSHOT_MISMATCH')
        require(current.get('assetId') == asset_id and current.get('logicalPath') == table['logicalPath']
                and current.get('originalFormat') == table['originalFormat'] and current.get('sourceSha256') == metadata.get('sourceSha256')
                and current.get('rawCommitId') and re.fullmatch('[0-9a-f]{64}', current.get('sourceSha256', ''))
                and (identity is None or current == identity), 'TIMESERIES_NATIVE_SOURCE_MISMATCH')
        require(bundle.get('recipeVersion') == 'native-evidence-2026-10-22.2' and bundle.get('readId') and bundle.get('readOutputSha256')
                and not bundle.get('coverage', {}).get('truncated'), 'TIMESERIES_NATIVE_READ_INCOMPLETE')
        identity = current
        reads.append({'readId': bundle['readId'], 'operation': operation, 'evidenceId': bundle['evidenceId'],
                      'representationId': bundle['representationId'], 'readOutputSha256': bundle['readOutputSha256'],
                      'sourceSha256': current['sourceSha256'], 'coverage': bundle['coverage'], 'clientReadReceipt': receipt,
                      'factIds': [fact['factId'] for fact in bundle.get('facts', [])]})
        return bundle

    schema = read({'operation': 'read_representation', 'representationId': schemas[0]['representationId']})
    require(schema.get('representationId') == schemas[0]['representationId'] and schema.get('representationKind') == 'table_schema'
            and schema.get('locator') == table['locator'] and schema['content'].get('tableId') == table_id, 'TIMESERIES_NATIVE_SCHEMA_MISMATCH')
    require(schema['coverage'].get('totalRowCount') == table['rowCount'], 'TIMESERIES_NATIVE_ROW_COUNT_MISMATCH')
    source_policy = schema['content'].get('sourceTimePolicy', {})
    zone = source_policy.get('sourceTimezone') if source_policy.get('status') == 'DECLARED' else None
    require(source_policy.get('status') in {None,'NOT_DECLARED','DECLARED'}, 'TIMESERIES_NATIVE_SOURCE_TIME_POLICY_INVALID')
    declaration_witnesses=[]; source_declarations=[]
    if zone:
        declarations=source_policy.get('declarations',[])
        require(table['originalFormat']=='XLSX' and declarations and all(row.get('timezone')==zone and row.get('validIanaTimezone') is True
                and row.get('locator',{}).get('sheet')==table['locator'].get('sheet') for row in declarations), 'TIMESERIES_NATIVE_TIME_DECLARATION_MISMATCH')
        cells=sorted({row['locator']['cell'] for row in declarations})
        policy_bundle=read({'operation':'read_workbook_cells','sheet':table['locator']['sheet'],'cells':cells})
        require(policy_bundle['locator'].get('sheet')==table['locator']['sheet'] and policy_bundle['coverage'].get('unrecordedCellCount')==0
                and len(policy_bundle.get('facts',[]))==len(cells), 'TIMESERIES_NATIVE_TIME_DECLARATION_INCOMPLETE')
        for declaration in declarations:
            locator=declaration['locator'];matches=[fact for fact in policy_bundle['facts'] if fact['locator'].get('cell')==locator['cell']
                and fact['locator'].get('sheet')==locator['sheet']]
            require(len(matches)==1 and matches[0]['value']==declaration['sourceText'] and not matches[0].get('qualityFlags')
                    and locator.get('characterIndexUnit')=='unicode-codepoint'
                    and declaration['sourceText'][locator['characterStart']:locator['characterEnd']]==zone, 'TIMESERIES_NATIVE_TIME_DECLARATION_MISMATCH')
            fact=matches[0]
            source_declarations.append({'factId':fact['factId'], 'locator':fact['locator'],
                'evidenceNature':'source_declaration', 'text':fact['value']})
            declaration_witnesses.append({'factId':fact['factId'],'readId':policy_bundle['readId'],'evidenceId':policy_bundle['evidenceId'],
                'representationId':policy_bundle['representationId'],'locator':fact['locator'],'field':fact['field'],
                'evidenceNature':fact['evidenceNature'],'valueSha256':checksum(fact['value']),'sourceSha256':identity['sourceSha256']})
    window = query.get('time_range', {})
    require(window == item.get('timeRange') and window.get('boundary') in {'[start,end]', '[start,end)'}, 'TIMESERIES_NATIVE_WINDOW_MISMATCH')
    start, end = instant(window['start']), instant(window['end'])
    require(start <= end, 'TIMESERIES_NATIVE_WINDOW_INVALID')
    calculation_policy = {'duplicatePolicy':'deduplicate-identical-preserve-conflicts', 'intervalBoundary':window['boundary'],
        'missingPolicy':'preserve', 'interpolation':'none', 'outlierPolicy':'preserve'}
    if typed_count:
        calculation_policy={'countMode':count_mode,
            'duplicatePolicy':'preserve-physical-records' if record_count else 'deduplicate-exact-equivalent-instants-reject-conflicts',
            'intervalBoundary':window['boundary'],'missingPolicy':'count-record-presence' if record_count else 'exclude-null-or-non-numeric',
            'interpolation':'none','outlierPolicy':'preserve'}
        require(item.get('units',{}).get('value')==('record' if record_count else 'sample'),'TIMESERIES_NATIVE_COUNT_UNIT_MISMATCH')
    if not interval_operation:
        require(item.get('calculationPolicy') == calculation_policy, 'TIMESERIES_NATIVE_CALCULATION_POLICY_NOT_VERIFIED')
    else:
        require(facts.get('intervalCalculation',{}).get('version')=='source-interval-statistics-2026-10-25.1', 'TIMESERIES_INTERVAL_RECIPE_NOT_VERIFIED')
    first = min(page['selector']['rowStart'] for page in pages)
    last = max(page['selector']['rowEnd'] for page in pages)
    require(first >= 1 and math.ceil((last - first + 1) / 32) + len(reads) <= MAX_READS, 'TIMESERIES_NATIVE_READ_BUDGET')
    all_rows = []; witnesses = declaration_witnesses; values = {}; excluded = []; duplicate_count = 0
    units = set(); all_positions = set(); source_times = []; window_record_count = 0; interval_points=[]
    for row_start in range(first, last + 1, 32):
        operation = {'operation': 'read_table', 'tableId': table_id, 'rowStart': row_start, 'rowEnd': min(row_start + 31, last), 'limit': 32, 'fields': selected_fields}
        bundle = read(operation)
        require(bundle['content'].get('tableId') == table_id and bundle['coverage'].get('requestedRangeComplete') is True
                and not bundle['coverage'].get('hasMore') and bundle['locator'].get('sheet') == table['locator'].get('sheet'), 'TIMESERIES_NATIVE_TABLE_SCOPE_MISMATCH')
        records = bundle['content'].get('records', []); by_fact = {}
        require(bundle['coverage'].get('totalRowCount')==table['rowCount'] and bundle['coverage'].get('returnedRowCount')==len(records), 'TIMESERIES_NATIVE_ROW_COUNT_MISMATCH')
        for fact in bundle.get('facts', []):
            key = (fact.get('locator', {}).get('rawRow'), fact.get('field'))
            require(key not in by_fact, 'TIMESERIES_NATIVE_DUPLICATE_FACT')
            by_fact[key] = fact
        require(len(by_fact) == len(records) * len(selected_fields), 'TIMESERIES_NATIVE_FACTS_INCOMPLETE')
        for record in records:
            position = record.get('rawRow'); locator = {**table['locator'],**record.get('locator', {})}
            require(isinstance(position, int) and not isinstance(position, bool) and row_start <= position <= operation['rowEnd']
                    and position not in all_positions and locator.get('sheet') == table['locator'].get('sheet')
                    and set(record.get('values', {})) == set(selected_fields), 'TIMESERIES_NATIVE_ROW_SCOPE_MISMATCH')
            all_positions.add(position); all_rows.append(record)
            bound = {}
            for field in selected_fields:
                fact = by_fact.get((position, field), {}); floc = fact.get('locator', {}); number = record.get('numberLexemes', {}).get(field)
                require(fact.get('factId') and floc.get('sheet') == table['locator'].get('sheet') and floc.get('field') == field
                        and (not locator.get('cells') or floc.get('cell') == locator['cells'].get(field))
                        and equivalent_value(fact.get('value'), record['values'][field], number), 'TIMESERIES_NATIVE_FACT_MISMATCH')
                bound[field] = fact
                if field in {time_field,'series_id'}:
                    require(fact.get('calculationEligible',True) and record.get('calculationEligibility',{}).get(field,True),
                            'TIMESERIES_NATIVE_SELECTOR_NOT_CALCULABLE')
                witnesses.append({'factId': fact['factId'], 'readId': bundle['readId'], 'evidenceId': bundle['evidenceId'],
                    'representationId': bundle['representationId'], 'locator': floc, 'field': field, 'evidenceNature': fact['evidenceNature'],
                    'valueSha256': checksum(fact['value']), 'sourceSha256': identity['sourceSha256']})
            if 'series_id' in fields and record['values']['series_id'] != entity:
                continue
            require(bound[time_field].get('calculationEligible', True) and record.get('calculationEligibility', {}).get(time_field, True), 'TIMESERIES_NATIVE_TIME_NOT_CALCULABLE')
            timestamp = instant(record['values'][time_field], zone)
            source_times.append(timestamp)
            if interval_operation:
                number=exact_number(record,metric,bound[metric]);unit=bound[metric].get('unit')
                if number is not None:
                    units.add(unit)
                    require(unit and (not query.get('unit') or query['unit']==unit),'TIMESERIES_NATIVE_UNIT_CONVERSION_NOT_VERIFIED')
                interval_points.append({'time':timestamp.isoformat(),'value':number,'rawRow':position,'unit':unit,
                    'metricFactId':bound[metric]['factId'],'timeFactId':bound[time_field]['factId']})
            if timestamp < start or (timestamp > end if window['boundary'] == '[start,end]' else timestamp >= end):
                continue
            window_record_count += 1
            if record_count:
                values[position]={'time':timestamp.isoformat(),'timeFactId':bound[time_field]['factId'],'rawRow':position}
                continue
            number = exact_number(record, metric, bound[metric])
            if number is None:
                excluded.append({'rawRow': position, 'reason': 'NULL_OR_NON_NUMERIC_SOURCE', 'factId': bound[metric]['factId']})
                continue
            unit = bound[metric].get('unit'); units.add(unit)
            require((not query.get('unit') or query['unit'] == unit) and (typed_count or interval_operation or unit == item.get('units', {}).get('value')), 'TIMESERIES_NATIVE_UNIT_CONVERSION_NOT_VERIFIED')
            key = timestamp.isoformat()
            sample = {'time': key, 'value': number, 'unit':unit, 'metricFactId': bound[metric]['factId'], 'timeFactId': bound[time_field]['factId'], 'rawRow': position}
            if key in values:
                require(values[key]['value'] == number and values[key]['unit']==unit, 'TIMESERIES_NATIVE_DUPLICATE_SAMPLE_CONFLICT')
                duplicate_count += 1
            else:
                values[key] = sample
    require(len(all_rows) == table['rowCount'], 'TIMESERIES_NATIVE_TABLE_INCOMPLETE')
    if typed_count:
        source_unit=next(iter(units)) if len(units)==1 else None
        require(facts.get('sourceRecordCount')==len(all_rows) and facts.get('sourceMeasurementUnit')==source_unit
            and facts.get('countPolicy')==('physical-records-in-window' if record_count else 'unique-calculable-time-samples'),
            'TIMESERIES_NATIVE_COUNT_SCOPE_MISMATCH')
    require((typed_count or len(units) <= 1) and (values or facts['operation'] == 'count' or interval_operation), 'TIMESERIES_NATIVE_NO_CALCULABLE_SAMPLES')
    samples = sorted(values.values(), key=lambda sample: sample['time'])
    require(len(samples) == facts.get('sampleCount'), 'TIMESERIES_NATIVE_EXECUTOR_SAMPLE_COUNT_MISMATCH')
    operation = facts['operation']; numbers = [sample['value'] for sample in samples if 'value' in sample]
    interval_calculation=None
    if interval_operation:
        threshold=query.get('threshold_expression') or (str(query['threshold']) if query.get('threshold') is not None else None)
        value,interval_calculation=calculate_interval(interval_points,window,operation,threshold,next(iter(units),query.get('unit')))
        require(interval_calculation==facts.get('intervalCalculation'),'TIMESERIES_INTERVAL_EXECUTOR_SCOPE_MISMATCH')
        calculation_policy={key:value for key,value in interval_calculation.items() if key not in {'segments','excludedIntervals'}}
        require(item.get('calculationPolicy')==calculation_policy,'TIMESERIES_NATIVE_CALCULATION_POLICY_NOT_VERIFIED')
        require(item.get('units',{}).get('value')==('s' if operation=='duration_above' else next(iter(units),query.get('unit'))),'TIMESERIES_INTERVAL_RESULT_UNIT_MISMATCH')
        expected_ratio={'numerator':str(value.numerator),'denominator':str(value.denominator)}
        require(facts.get('exactRatio')==expected_ratio,'TIMESERIES_INTERVAL_EXECUTOR_EXACT_RESULT_MISMATCH')
    else:
        value = Fraction(len(samples)) if operation == 'count' else sum(numbers, Fraction(0)) if operation == 'sum' else min(numbers) if operation == 'min' else max(numbers) if operation == 'max' else sum(numbers, Fraction(0)) / len(numbers)
        if typed_count:
            require(facts.get('exactRatio')=={'numerator':str(value.numerator),'denominator':'1'}
                and facts.get('matchedRowCount')==window_record_count
                and facts.get('excludedNullOrNonNumericCount')==len(excluded)
                and facts.get('duplicateEquivalentSampleCount')==duplicate_count,'TIMESERIES_NATIVE_COUNT_SCOPE_MISMATCH')
    expected_float = float(value); observed = facts.get('value')
    require(isinstance(observed, (int, float)) and not isinstance(observed, bool) and math.isfinite(expected_float) and math.isfinite(observed), 'TIMESERIES_NATIVE_EXECUTOR_RESULT_UNAVAILABLE')
    tolerance = 0 if operation in {'min', 'max', 'count'} else max(math.ulp(expected_float), math.ulp(float(observed))) * max(4, len(samples) * 2)
    require(abs(float(observed) - expected_float) <= tolerance, 'TIMESERIES_NATIVE_EXECUTOR_RESULT_MISMATCH')
    extrema=[sample for sample in samples if sample['value']==value] if operation in {'min','max'} else []
    if extrema:
        require(facts.get('occurredAt') and instant(facts['occurredAt']) in {instant(sample['time']) for sample in extrema}
                and facts.get('occurredInAssetId')==asset_id, 'TIMESERIES_NATIVE_EXECUTOR_EXTREME_TIME_MISMATCH')
    input_digest = checksum({'identity': identity, 'tableId': table_id, 'records': all_rows, 'sourceTimePolicy': source_policy})
    derived = {**facts, **exact_result(value), 'field': None if record_count else metric, 'matchedRowCount': window_record_count if typed_count else len(samples), 'numericInputCount': 0 if record_count else len(samples),
        'excludedNullOrNonNumericCount': len(excluded), 'sourceLexemeCount': 0 if record_count else len(samples), 'legacyDecodedNumberCount': 0,
        'completeFilteredSet': True, 'inputDigest': input_digest, 'scannedNativeRowCount': len(all_rows),
        'duplicateEquivalentSampleCount': duplicate_count, 'countPolicy': 'physical-records-in-window' if record_count else 'unique-calculable-time-samples'}
    if interval_operation:
        derived.update(numericInputCount=interval_calculation['supportPointCount'],sourceLexemeCount=interval_calculation['supportPointCount'],intervalCalculation=interval_calculation)
    if extrema:
        derived.update(occurredAt=extrema[0]['time'],extremaWitnesses=[{key:value for key,value in sample.items() if key not in {'value','unit'}} for sample in extrema])
    binding = {'version': VERSION, 'identity': identity, 'query': query, 'inputDigest': input_digest, 'facts': derived}
    result_id = str(uuid.uuid5(uuid.NAMESPACE_URL, canonical(binding))); scope_id = str(uuid.uuid5(uuid.NAMESPACE_URL, result_id + ':scope'))
    read_id = str(uuid.uuid4()); input_ids = sorted({fact['factId'] for fact in witnesses})
    coverage = {'verification': 'independent-full-native-table-window-calculation', 'completeFilteredSet': True,
        'nativeTableReadComplete': True, 'scannedRowCount': len(all_rows), 'sampleCount': len(samples), 'excludedRows': excluded,
        'duplicateEquivalentSampleCount': duplicate_count, 'nativeReadCount': len(reads), 'independentFilterReexecution': True,
        'supportedFilterScope': 'one source, one table, series_id or asset identity, explicit time window; no additional hard conditions',
        'requestedTimeRange':window, 'sourceRecordTimeRange':observed_range(source_times),
        'actualSampleTimeRange':observed_range([instant(sample['time']) for sample in samples]),
        'sourceSeriesRecordCount':len(source_times), 'windowRecordCount':window_record_count,
        'continuousCoverageVerified':False, 'observationSemantics':'recorded-point-extrema-only',
        'requestedBoundsOutsideSourceExtent':{'startBeforeFirst':start < min(source_times), 'endAfterLast':end > max(source_times)} if source_times else {},
        'timeDisplayPolicy':{'rule':'source-iana-timezone' if zone else 'request-start-fixed-offset',
            'sourceTimezone':zone, 'requestStart':window['start']},
        'entityId':entity, 'locator':table['locator'], 'calculationPolicy':calculation_policy,
        'sourceTimePolicy':source_policy, 'sourceDeclarations':source_declarations}
    if interval_operation:coverage['intervalCalculation']=interval_calculation
    if typed_count:
        coverage.update(countMode=count_mode,countPolicy=derived['countPolicy'],metricValuesRead=not record_count)
    lineage = {'version': VERSION, 'identity': identity, 'resultFactId': result_id, 'scopeFactId': scope_id, 'readId': read_id,
        'validatedQuery': query, 'validatedQuerySha256': checksum(query), 'fullFactsSha256': checksum(derived),
        'inputsSha256': input_digest, 'inputDigest': input_digest, 'inputFacts': witnesses, 'reads': reads, 'coverage': coverage,
        'sourceTimePolicy': source_policy, 'executorComparison': {'originalFacts': facts, 'binaryTolerance': tolerance,
            'policy': 'Binary executor consistency only; published exact arithmetic is independently calculated from native numeric lexemes.'},
        'facts': [{'factId': result_id, 'evidenceNature': 'program_calculation',
                  'value': {**exact_result(value), 'operation':operation, 'metric':metric, 'units':item.get('units',{})},
                  'inputFactIds': input_ids, 'readId': read_id},
                  {'factId': scope_id, 'evidenceNature': 'program_calculation', 'value': coverage, 'inputFactIds': input_ids, 'readId': read_id}]}
    output_hash = checksum({key: value for key, value in lineage.items() if key != 'readId'})
    evidence_id = str(uuid.uuid5(uuid.NAMESPACE_URL, result_id + ':evidence'))
    output = {**item, 'evidenceId': evidence_id, 'assetId': asset_id, 'facts': derived, 'locator': {**table['locator'], 'tableId': table_id},
        'preview': {**(item.get('preview') or {}), 'logicalPath': table['logicalPath']},
        'provenance': {**item.get('provenance', {}), 'candidateEvidenceId': item['evidenceId'], 'calculationLineageVersion': VERSION,
            'nativeReadId': read_id, 'sourceSha256': identity['sourceSha256'], 'rawCommitId': identity['rawCommitId'],
            'readOutputSha256': output_hash, 'originalFormat': identity['originalFormat']},
        'calculationBundle': lineage, 'calculationFactIds': [result_id, scope_id]}
    return output, {'state': 'TIMESERIES_NATIVE_INPUTS_VERIFIED', 'readId': read_id, 'evidenceId': evidence_id,
        'factIds': [result_id, scope_id], 'inputFactIds': input_ids, 'readOutputSha256': output_hash,
        'sourceSha256': identity['sourceSha256'], 'reads': reads, 'coverage': coverage}


def generation_check(result, sources, trace):
    selected = [source for source in sources if (source.calculation_lineage or {}).get('version') == VERSION]
    if not selected:
        return None
    choices = result.get('choices', [])
    if not isinstance(result.get('id'),str) or not result['id']:
        return {'status':'REJECTED','reason':'TIMESERIES_MODEL_RESPONSE_UNAVAILABLE'}
    if len(choices) != 1 or choices[0].get('finish_reason') != 'stop' or not choices[0].get('message', {}).get('content', '').strip():
        return {'status': 'REJECTED', 'reason': 'TIMESERIES_OUTPUT_LIMIT' if choices and choices[0].get('finish_reason') == 'length' else 'TIMESERIES_MODEL_RESPONSE_UNAVAILABLE'}
    calls = trace.data['modelCalls'] if trace else []
    matching = [call for call in calls if call.get('status') == 'response_received' and call.get('callId')
                and re.fullmatch('[0-9a-f]{64}',call.get('requestBodySha256','')) and call.get('response', {}).get('id') == result.get('id')]
    for source in selected:
        entries = [entry for call in matching for entry in call.get('actualEvidence', []) if entry.get('evidenceId') == source.evidence_id]
        if not any(entry.get('contextMatchedActualRequest') and entry.get('factIdsPresentInActualRequest')
                   and entry.get('factIds') == source.fact_ids and entry.get('readId') == source.provenance.get('nativeReadId')
                   and entry.get('sourceSha256') == source.provenance.get('sourceSha256')
                   and entry.get('readOutputSha256') == source.provenance.get('readOutputSha256') for entry in entries):
            return {'status': 'REJECTED', 'reason': 'TIMESERIES_ACTUAL_MODEL_INPUT_BINDING_FAILED'}
    return {'status': 'CALCULATION_INPUTS_AND_MODEL_TRANSMISSION_VERIFIED', 'modelInterpretationVerified': False,
            'sourceCount': len(selected), 'scope': 'Only deterministic result and scope presentation may acquire verified claims.'}
