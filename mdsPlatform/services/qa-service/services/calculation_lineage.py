"""Bind a structured tool result to actual, caller-authorized native reads.

The executor establishes the complete filtered set. This verifier establishes
the original values used by that execution; it does not claim a second query
engine or independent validation of the filter semantics.
"""
import hashlib
import json
import time
import uuid
from decimal import Decimal

VERSION = 'native-calculation-lineage-2026-10-24.1'
MAX_ROWS = 512
MAX_READS = 32
MAX_BYTES = 8 * 1024 * 1024
MAX_SECONDS = 30


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def checksum(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def require(condition, code):
    if not condition:
        raise ValueError(code + ': 计算结果尚未完成原生输入核验')


def equivalent_value(left,right,number=None):
    if canonical(left)==canonical(right):return True
    # JSON has one number type. Some gateway decoders serialize an integral
    # float as an integer. Accept that representation change only when both
    # values equal the same already-verified original CSV/OOXML lexeme.
    if {type(left),type(right)}!={int,float} or not isinstance(number,dict):return False
    if number.get('sourceType') not in {'csv-field','ooxml-number'}:return False
    try:
        original=Decimal(number['lexeme'])
        return original.is_finite() and Decimal(str(left))==Decimal(str(right))==original
    except (ValueError,TypeError,KeyError,ArithmeticError):return False


def equivalent_record(left,right,numbers):
    return (isinstance(left,dict) and isinstance(right,dict) and set(left)==set(right)
        and all(equivalent_value(left[key],right[key],numbers.get(key)) for key in left))


def _number(row, field):
    if not field:
        return None
    value = row['values'].get(field)
    pointer = row['locator'].get('jsonPointer', '') + '/' + field.replace('~', '~0').replace('/', '~1')
    number = row.get('numberLexemes', {}).get(field) or row.get('numberLexemes', {}).get(pointer)
    encoded = (isinstance(value, str) and number and number.get('sourceType') == 'number'
               and number.get('decodedValueStatus') == 'SOURCE_LEXEME_STRING' and number.get('lexeme') == value)
    numeric = (isinstance(value, (int, float)) and not isinstance(value, bool)) or encoded
    if not numeric:
        return None
    require(row.get('calculationEligibility', {}).get(field, True), 'CALCULATION_SOURCE_NOT_ELIGIBLE')
    require(not number or number.get('calculationEligible', True), 'CALCULATION_SOURCE_NOT_ELIGIBLE')
    return Decimal(number['lexeme'] if number else str(value))


def read_calculation(client, scope, item, filters, cache):
    facts = item.get('facts', {})
    if (item.get('evidenceType') != 'calculation' or item.get('semanticSpace') != 'structured.typed'
            or facts.get('status') or facts.get('arithmetic') != 'exact-rational-source-lexemes-v1'):
        return item, None
    require(item.get('dataVersion') == str(scope[1]) and item.get('indexVersion') == str(scope[2]), 'CALCULATION_SNAPSHOT_MISMATCH')
    execution = item.get('provenance', {}).get('structuredCalculationInputs', {})
    require(execution.get('status') == 'AVAILABLE', execution.get('status', 'CALCULATION_INPUT_RECEIPT_UNAVAILABLE'))
    require(execution.get('version') == 'structured-calculation-inputs-2026-10-23.1', 'CALCULATION_INPUT_RECIPE_UNSUPPORTED')
    rows = execution.get('rows', [])
    require(len(rows) <= MAX_ROWS and len(rows) == facts.get('matchedRowCount') == execution.get('matchedRowCount'), 'CALCULATION_INPUT_COUNT_MISMATCH')
    require(checksum(rows) == execution.get('inputsSha256'), 'CALCULATION_INPUT_DIGEST_MISMATCH')
    query = item.get('provenance', {}).get('validatedQuery', {})
    table_id = query.get('table_id')
    tables = [table for table in filters.get('tables', []) if table['tableId'] == table_id]
    require(len(tables) == 1 and query.get('retrievers') == ['structured'], 'CALCULATION_SINGLE_TABLE_REQUIRED')
    table = tables[0]
    require(execution['operation'] == facts['operation'] == query.get('operation')
            and execution.get('field') == facts.get('field') and execution.get('completeFilteredSet') is True
            and facts.get('completeFilteredSet') is True, 'CALCULATION_SCOPE_MISMATCH')
    field = query.get('aggregate_field') or (query.get('hard_conditions') or [{}])[-1].get('field')
    require(field == facts.get('field'), 'CALCULATION_FIELD_MISMATCH')
    asset_id = str(table['assetId'])
    asset = next((asset for asset in filters.get('assets', []) if str(asset['assetId']) == asset_id), {})
    require(asset.get('nativeEvidence'), 'CALCULATION_NATIVE_INDEX_REBUILD_REQUIRED')
    if asset_id not in cache:
        cache[asset_id] = client.evidence_metadata(*scope, asset_id)
    metadata = cache[asset_id]
    require(metadata.get('nativeEvidenceStatus') == 'AVAILABLE', 'CALCULATION_MANIFEST_UNAVAILABLE')
    fields = execution.get('fields', [])
    require(len(fields) <= 64 and set(fields) <= {field['name'] for field in table['fields']}, 'CALCULATION_FIELDS_MISMATCH')
    indexed = {}
    for row in rows:
        position = row.get('locator', {}).get('rawRow')
        require(row.get('assetId') == asset_id and row.get('tableId') == table_id and isinstance(position, int)
                and not isinstance(position, bool) and position > 0 and position not in indexed, 'CALCULATION_ROW_SCOPE_MISMATCH')
        indexed[position] = row
    groups = []
    for position in sorted(indexed):
        if groups and position == groups[-1][-1] + 1 and len(groups[-1]) < 32:
            groups[-1].append(position)
        else:
            groups.append([position])
    if not groups:
        groups = [[]]  # A zero-result count still reads the table's scope.
    json_source = metadata.get('originalFormat') in {'JSON', 'JSONL'}
    if json_source and rows:
        groups = [[position, name] for position in sorted(indexed) for name in fields]
    require(len(groups) <= MAX_READS, 'CALCULATION_NATIVE_READ_BUDGET')
    started = time.monotonic()
    total_bytes = 0
    reads = []
    witnesses = {}
    identity = None
    for group in groups:
        require(time.monotonic() - started < MAX_SECONDS, 'CALCULATION_NATIVE_READ_TIMEOUT')
        if json_source and rows:
            position, name = group
            expected = indexed[position]
            pointer = expected['locator']['jsonPointer'] + '/' + name.replace('~', '~0').replace('/', '~1')
            request = {'operation': 'read_json_subtree', 'jsonPointer': pointer}
            if metadata['originalFormat'] == 'JSONL':
                require(expected['locator'].get('jsonlLine'), 'CALCULATION_JSONL_LINE_REQUIRED')
                request['jsonlLine'] = expected['locator']['jsonlLine']
        elif json_source:
            descriptors = [descriptor for descriptor in metadata['representations'] if descriptor.get('kind') in {'json_subtree', 'json_documents_page'}]
            require(descriptors, 'CALCULATION_SCOPE_REPRESENTATION_MISSING')
            request = {'operation': 'read_representation', 'representationId': descriptors[0]['representationId']}
        else:
            request = {'operation': 'read_table', 'tableId': table_id, 'rowStart': group[0] if group else 1,
                       'rowEnd': group[-1] if group else 1, 'limit': 32, 'fields': fields}
        bundle = client.read_evidence(*scope, asset_id, request)
        total_bytes += len(canonical(bundle).encode())
        require(total_bytes <= MAX_BYTES and time.monotonic() - started < MAX_SECONDS, 'CALCULATION_NATIVE_READ_BUDGET')
        current = bundle.get('identity', {})
        for key, value in zip(('datasetId', 'versionId', 'indexVersionId'), scope):
            require(current.get(key) == str(value), 'CALCULATION_SNAPSHOT_MISMATCH')
        require(current.get('assetId') == asset_id and current.get('sourceSha256') == metadata.get('sourceSha256')
                and current.get('logicalPath') == table['logicalPath'] and (identity is None or identity == current), 'CALCULATION_SOURCE_MISMATCH')
        require(bundle.get('recipeVersion') == 'native-evidence-2026-10-22.2' and bundle.get('readId')
                and bundle.get('readOutputSha256') and (json_source or bundle.get('coverage', {}).get('requestedRangeComplete') is True)
                and not bundle['coverage'].get('truncated'), 'CALCULATION_NATIVE_READ_INCOMPLETE')
        identity = current
        if json_source:
            selected_fact_ids = []
            if rows:
                require(bundle['coverage'].get('completeSubtree') is True and len(bundle.get('facts', [])) == 1, 'CALCULATION_NATIVE_READ_INCOMPLETE')
                fact = bundle['facts'][0]
                require(fact.get('locator', {}).get('jsonPointer') == pointer
                        and fact['locator'].get('jsonlLine') == expected['locator'].get('jsonlLine'), 'CALCULATION_NATIVE_LOCATOR_MISMATCH')
                require(canonical(fact['value']) == canonical(expected['values'][name]) and fact.get('unit') == expected['units'][name], 'CALCULATION_NATIVE_FACT_MISMATCH')
                number = {key: value for key, value in expected['numberLexemes'].items() if key == pointer or key.startswith(pointer + '/')}
                require(canonical(bundle['content'].get('numberLexemes', {})) == canonical(number), 'CALCULATION_NATIVE_NUMBER_TYPE_MISMATCH')
                require(fact.get('calculationEligible', True) == expected['calculationEligibility'][name], 'CALCULATION_NATIVE_ELIGIBILITY_MISMATCH')
                selected_fact_ids.append(fact['factId'])
                witnesses[(position, name)] = {'factId': fact['factId'], 'readId': bundle['readId'],
                    'evidenceId': bundle['evidenceId'], 'representationId': bundle['representationId'],
                    'locator': fact['locator'], 'field': name, 'evidenceNature': fact['evidenceNature'],
                    'valueSha256': checksum(fact['value']), 'sourceSha256': current['sourceSha256']}
            reads.append({'state': 'READ', 'readId': bundle['readId'], 'operation': request, 'evidenceId': bundle['evidenceId'],
                'representationId': bundle['representationId'], 'factIds': selected_fact_ids, 'readOutputSha256': bundle['readOutputSha256'],
                'sourceSha256': current['sourceSha256'], 'coverage': bundle['coverage']})
            continue
        records = bundle.get('content', {}).get('records', [])
        if group:
            require([record.get('rawRow') for record in records] == group, 'CALCULATION_NATIVE_ROWS_MISMATCH')
        selected_fact_ids = []
        for record in records if group else []:
            position = record['rawRow']
            expected = indexed[position]
            require(canonical(record.get('numberLexemes', {})) == canonical(expected['numberLexemes']), 'CALCULATION_NATIVE_NUMBER_TYPE_MISMATCH')
            require(equivalent_record(record['values'],expected['values'],expected['numberLexemes']), 'CALCULATION_NATIVE_VALUE_MISMATCH')
            for name in fields:
                require(record.get('calculationEligibility', {}).get(name, True) == expected['calculationEligibility'][name], 'CALCULATION_NATIVE_ELIGIBILITY_MISMATCH')
                candidates = [fact for fact in bundle['facts'] if fact.get('locator', {}).get('rawRow') == position and fact.get('field') == name]
                require(len(candidates) == 1, 'CALCULATION_NATIVE_FACT_MISSING')
                fact = candidates[0]
                number=expected['numberLexemes'].get(name)
                require(equivalent_value(fact['value'],expected['values'][name],number) and fact.get('unit') == expected['units'][name], 'CALCULATION_NATIVE_FACT_MISMATCH')
                if number and number.get('lexeme') is not None:
                    require(fact.get('numberSourceExpression')==number['lexeme'],'CALCULATION_NATIVE_FACT_LEXEME_MISMATCH')
                for key in ('sheet', 'jsonPath', 'jsonlLine', 'page'):
                    require(key not in expected['locator'] or fact['locator'].get(key) == expected['locator'][key], 'CALCULATION_NATIVE_LOCATOR_MISMATCH')
                selected_fact_ids.append(fact['factId'])
                witnesses[(position, name)] = {'factId': fact['factId'], 'readId': bundle['readId'],
                    'evidenceId': bundle['evidenceId'], 'representationId': bundle['representationId'],
                    'locator': fact['locator'], 'field': name, 'evidenceNature': fact['evidenceNature'],
                    'valueSha256': checksum(fact['value']), 'sourceSha256': current['sourceSha256'],
                    **({'decodedValueComparison':{'policy':'same-original-lexeme-integral-decoding-1',
                        'sourceLexeme':number['lexeme'],'executorDecodedType':type(expected['values'][name]).__name__,
                        'nativeDecodedType':type(fact['value']).__name__}}
                        if canonical(fact['value'])!=canonical(expected['values'][name]) else {})}
        reads.append({'state': 'READ', 'readId': bundle['readId'], 'operation': request, 'evidenceId': bundle['evidenceId'],
            'representationId': bundle['representationId'], 'factIds': selected_fact_ids, 'readOutputSha256': bundle['readOutputSha256'],
            'sourceSha256': current['sourceSha256'], 'coverage': bundle['coverage']})
    digest = hashlib.sha256()
    for row in rows:
        number = _number(row, facts.get('field')) if facts['operation']!='count' else None
        digest.update(canonical({'assetId': row['assetId'], 'tableId': row['tableId'], 'locator': row['locator'],
            'value': str(number) if number is not None else None, 'unit': row['units'].get(facts.get('field'))}).encode())
    require(digest.hexdigest() == facts.get('inputDigest'), 'CALCULATION_NATIVE_INPUT_DIGEST_MISMATCH')
    # Stable facts identify the frozen operation, result, and native inputs.
    # Per-request read IDs remain outside this stable identity.
    inputs = list(witnesses.values())
    binding = {'version': VERSION, 'identity': identity, 'query': query, 'factsSha256': checksum(facts),
               'inputFactIds': sorted(witness['factId'] for witness in inputs), 'inputsSha256': execution['inputsSha256']}
    result_id = str(uuid.uuid5(uuid.NAMESPACE_URL, canonical(binding)))
    scope_id = str(uuid.uuid5(uuid.NAMESPACE_URL, result_id + ':scope'))
    round_id = str(uuid.uuid4())
    lineage = {'version': VERSION, 'identity': identity, 'resultFactId': result_id, 'scopeFactId': scope_id,
        'readId': round_id, 'validatedQuery': query, 'validatedQuerySha256': checksum(query),
        'fullFactsSha256': checksum(facts), 'inputsSha256': execution['inputsSha256'],
        'inputDigest': digest.hexdigest(), 'inputFacts': inputs, 'reads': reads,
        'coverage': {'verification': 'used-input-values-against-authorized-native-reads', 'completeFilteredSet': True,
            'matchedRowCount': len(rows), 'scannedRowCount': execution['scannedRowCount'], 'nativeReadCount': len(reads),
            'executorEstablishesFilterCompleteness': True, 'independentFilterReexecution': False}}
    lineage['facts'] = [
        {'factId': result_id, 'evidenceNature': 'program_calculation',
         'value': {key: facts.get(key) for key in ('operation', 'field', 'exactValue', 'exactRatio', 'decimalValue', 'decimalRounded', 'arithmetic')},
         'units': item.get('units', {}), 'inputFactIds': binding['inputFactIds'], 'readId': round_id},
        {'factId': scope_id, 'evidenceNature': 'program_calculation',
         'value': {key: facts.get(key) for key in ('matchedRowCount', 'numericInputCount', 'excludedNullOrNonNumericCount', 'countPolicy', 'completeFilteredSet', 'inputDigest')
                   if key!='countPolicy' or facts['operation']=='count'},
         'validatedQuerySha256': checksum(query), 'inputFactIds': binding['inputFactIds'], 'readId': round_id}]
    output_hash = checksum({key: value for key, value in lineage.items() if key != 'readId'})
    provenance = {**item['provenance'], 'calculationLineageVersion': VERSION, 'nativeReadId': round_id,
        'sourceSha256': identity['sourceSha256'], 'rawCommitId': identity['rawCommitId'], 'readOutputSha256': output_hash}
    provenance.pop('structuredCalculationInputs', None)
    output = {**item, 'provenance': provenance, 'calculationBundle': lineage, 'calculationFactIds': [result_id, scope_id]}
    return output, {'state': 'CALCULATION_INPUTS_VERIFIED', 'readId': round_id, 'evidenceId': item['evidenceId'],
        'factIds': [result_id, scope_id], 'inputFactIds': binding['inputFactIds'], 'readOutputSha256': output_hash,
        'sourceSha256': identity['sourceSha256'], 'reads': reads, 'coverage': lineage['coverage']}


def final_claims(answer, sources):
    """Only the exact spans emitted by the deterministic renderer are verified.

    No arbitrary model sentence becomes a verified claim from its citation.
    """
    claims = []
    for index, source in enumerate(sources, 1):
        lineage = source.calculation_lineage
        presentation = source.provenance.get('verifiedCalculationPresentation')
        if not lineage or not presentation:
            continue
        cursor = 0
        for key, fact_key in (('result', 'resultFactId'), ('scope', 'scopeFactId')):
            text = presentation.get(key)
            if not text:
                continue
            start = answer.find(text, cursor)
            require(start >= 0, 'CALCULATION_FINAL_CLAIM_MISMATCH')
            end = start + len(text)
            fact_id = lineage[fact_key]
            claims.append({'claimId': str(uuid.uuid5(uuid.NAMESPACE_URL, canonical([fact_id, text]))),
                'evidenceNature': 'program_calculation', 'verification': 'deterministic-renderer-exact-span',
                'text': text, 'answerStart': start, 'answerEnd': end, 'offsetUnit': 'unicode-code-points',
                'sourceIndices': [index], 'evidenceIds': [source.evidence_id], 'factIds': [fact_id],
                'readIds': [lineage['readId']], 'inputFactIds': sorted({fact['factId'] for fact in lineage['inputFacts']})})
            cursor = end
    return claims
