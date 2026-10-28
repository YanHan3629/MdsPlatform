"""Bounded execution inputs for authorized native-source verification in QA."""
import hashlib
import json

VERSION = 'structured-calculation-inputs-2026-10-23.1'
MAX_ROWS = 512
MAX_BYTES = 1024 * 1024


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def checksum(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def receipt(rows, plan, field, scanned_rows):
    header = {'version': VERSION, 'matchedRowCount': len(rows), 'scannedRowCount': scanned_rows,
              'field': field, 'operation': plan.operation, 'completeFilteredSet': True}
    if len(rows) > MAX_ROWS:
        return {**header, 'status': 'NATIVE_VERIFICATION_ROW_BUDGET', 'maxRows': MAX_ROWS}
    fields = {condition.field for condition in plan.hard_conditions}
    if field:
        fields.add(field)
    # A count without a field still needs an original record-presence witness.
    if not fields and rows:
        fields.add(next(iter(rows[0]['values']), ''))
    if plan.query_text or plan.target_entity:
        fields.update(key for row in rows for key in row['values'])
    if len(fields) > 64 or any(name not in row['values'] for name in fields for row in rows):
        return {**header, 'status': 'NATIVE_VERIFICATION_FIELDS_UNSUPPORTED'}
    fields = sorted(fields)
    inputs = []
    for row in rows:
        pointers = {row['locator'].get('jsonPointer', '') + '/' + name.replace('~', '~0').replace('/', '~1') for name in fields}
        inputs.append({'assetId': row['assetId'], 'tableId': row['unitId'], 'logicalPath': row['logicalPath'],
            'locator': row['locator'], 'values': {name: row['values'][name] for name in fields},
            'numberLexemes': {key: value for key, value in row.get('numberLexemes', {}).items()
                              if key in fields or any(key == pointer or key.startswith(pointer + '/') for pointer in pointers)},
            'calculationEligibility': {name: row.get('calculationEligibility', {}).get(name, True) for name in fields},
            'units': {name: row.get('units', {}).get(name) for name in fields}})
    output = {**header, 'status': 'AVAILABLE', 'fields': fields, 'rows': inputs, 'inputsSha256': checksum(inputs)}
    if len(canonical(output).encode()) > MAX_BYTES:
        return {**header, 'status': 'NATIVE_VERIFICATION_BYTE_BUDGET', 'maxBytes': MAX_BYTES}
    return output
