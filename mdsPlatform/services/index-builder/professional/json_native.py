"""Loss-aware JSON source reading, without flattening or joining arrays."""
from __future__ import annotations

import json
import math
from decimal import Decimal

from .contracts import ParsedItem


NUMBER_ENCODING_VERSION='source-number-lexemes-2'
CALCULATION_DIGITS=4096
MAX_NUMBER_TOKEN_BYTES=32768
MAX_SAFE_INTEGER=9007199254740991


class _Integer(str):pass
class _Number(str):pass
class _SourceNumber(str):
    """Wire value is an exact lexeme; numberLexemes retains its numeric type.

    This is never an ordinary source string: its pointer has sourceType=number
    and decodedValueStatus=SOURCE_LEXEME_STRING. Consumers must use that map.
    """


def number_metadata(record,field):
    numbers=record.get('numberLexemes',{})
    return numbers.get(field) or numbers.get(pointer_child(record.get('locator',{}).get('jsonPointer',''),field))


def is_encoded_number(value,info):
    return (isinstance(value,str) and isinstance(info,dict) and info.get('sourceType')=='number'
        and info.get('decodedValueStatus')=='SOURCE_LEXEME_STRING' and value==info.get('lexeme'))


def is_record_number(record,field):
    value=record['values'].get(field)
    return (isinstance(value,(int,float)) and not isinstance(value,bool)) or is_encoded_number(value,number_metadata(record,field))


def record_calculation_eligibility(value,numbers,pointer):
    return {field:numbers[pointer_child(pointer,field)]['calculationEligible'] for field in value
        if pointer_child(pointer,field) in numbers}


def _calculation_eligible(token):
    mantissa,separator,exponent=token.lower().partition('e')
    significant_exponent=exponent.lstrip('+-').lstrip('0')
    if len(significant_exponent)>5:return False
    power=(-1 if exponent.startswith('-') else 1)*int(significant_exponent or '0') if separator else 0
    fractional=len(mantissa.split('.',1)[1]) if '.' in mantissa else 0
    digits=mantissa.lstrip('+-').replace('.','').lstrip('0') or '0'
    return len(digits)<=CALCULATION_DIGITS and abs(power-fractional)<=CALCULATION_DIGITS


def pointer_child(pointer, key):
    return pointer + "/" + str(key).replace("~", "~0").replace("/", "~1")


def _path_child(path, key):
    return path + ("." + key if key.isidentifier() else "[" + json.dumps(key, ensure_ascii=False) + "]")


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON_DUPLICATE_OBJECT_KEY: " + key)
        result[key] = value
    return result


def decode_document(text):
    """Retain numeric source types and exact expressions without Infinity or zero loss.

    Arithmetic must use numberLexemes, rather than rounded binary floats. JSON
    extensions such as NaN and ambiguous duplicate object keys fail explicitly.
    Finite decoded numbers are conveniences. Out-of-range numeric values are
    encoded as lexeme strings with explicit numeric metadata, never as nulls.
    """
    def invalid_constant(token):
        raise ValueError("JSON_NONSTANDARD_NUMBER: " + token)

    value = json.loads(text, parse_int=_Integer, parse_float=_Number,
                       parse_constant=invalid_constant, object_pairs_hook=_object)
    numbers = {}

    def unwrap(node, pointer):
        if isinstance(node, (_Integer, _Number)):
            integer = isinstance(node, _Integer)
            token=str(node)
            if len(token)>MAX_NUMBER_TOKEN_BYTES:raise ValueError('JSON_NUMBER_TOKEN_BUDGET_EXCEEDED')
            native=int(token) if integer and len(token.lstrip('-'))<=16 else _SourceNumber(token) if integer else float(token)
            if integer and isinstance(native,int) and abs(native)>MAX_SAFE_INTEGER:native=_SourceNumber(token)
            if not integer:
                mantissa=token.lower().split('e',1)[0]
                nonzero=any(character in '123456789' for character in mantissa)
                if not math.isfinite(native) or (native==0 and nonzero):native=_SourceNumber(token)
            encoded=isinstance(native,_SourceNumber)
            try:exact=not encoded and (integer or Decimal(token)==Decimal.from_float(native))
            except ArithmeticError:exact=False
            numbers[pointer] = {
                "lexeme": token, "numberKind": "integer" if integer else "decimal",'sourceType':'number',
                "binaryValueExact": exact,'decodedValueStatus':'SOURCE_LEXEME_STRING' if encoded else 'EXACT_INTEGER' if integer else 'FINITE_BINARY_NUMBER',
                'calculationEligible':_calculation_eligible(token),'encodingVersion':NUMBER_ENCODING_VERSION,
            }
            return native
        if isinstance(node, dict):
            return {key: unwrap(child, pointer_child(pointer, key)) for key, child in node.items()}
        if isinstance(node, list):
            return [unwrap(child, pointer_child(pointer, index)) for index, child in enumerate(node)]
        return node

    return unwrap(value, ""), numbers


def _kind(value):
    if isinstance(value,_SourceNumber):return 'number'
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, dict):
        return "object"
    if isinstance(value, list):
        return "array"
    return "string"


def records_payload(records):
    """Missing keys and explicit nulls remain distinguishable in each record."""
    names = dict.fromkeys(key for row in records for key in row["values"])
    columns = []
    for name in names:
        present = [row["values"][name] for row in records if name in row["values"]]
        kinds = sorted({_kind(value) for value in present})
        null_count = sum(value is None for value in present)
        missing_count = len(records) - len(present)
        columns.append({"name": name, "dtype": kinds[0] if len(kinds) == 1 else "mixed",
                        "sourceTypes": kinds, "nullable": bool(null_count or missing_count),
                        "nullCount": null_count, "missingCount": missing_count, "sample": present[:5]})
    return {"columns": columns, "records": records,
            "coverage": {"complete": True, "rowCount": len(records), "flattened": False,
                         "arrayExpansion": "independent-arrays-no-cartesian-product"}}


def document_items(value, numbers, units, *, physical_line=None, root_is_record=False):
    """Independent nested tables plus scalar metadata, retaining the full tree."""
    items = []
    base = {"jsonlLine": physical_line} if physical_line is not None else {}

    def walk(node, pointer, path, within_record=False):
        if isinstance(node, list) and all(isinstance(child, dict) for child in node):
            records = []
            # Partition once per array. Scanning the entire document's numeric
            # map for every row becomes quadratic for a large source catalog.
            numeric_rows = {}
            prefix = pointer + "/"
            for number_pointer, number in numbers.items():
                if number_pointer.startswith(prefix):
                    index = number_pointer[len(prefix):].split("/", 1)[0]
                    numeric_rows.setdefault(index, {})[number_pointer] = number
            for index, child in enumerate(node):
                row_pointer = pointer_child(pointer, index)
                records.append({"rawRow": index + 1, "values": child,
                                "locator": {**base, "jsonPointer": row_pointer,
                                            "arrayPointer": pointer, "arrayIndex": index},
                                "numberLexemes": numeric_rows.get(str(index), {}),
                                'calculationEligibility':record_calculation_eligibility(child,numbers,row_pointer)})
            items.append(ParsedItem("table", records_payload(records), {
                **base, "jsonPath": path, "jsonPointer": pointer, "rawRowStart": 1,
                "rowNumbering": "one-based-array-position", "arrayIndexBase": 0,
            }, units=dict(units)))
            for index, child in enumerate(node):
                walk(child, pointer_child(pointer, index), f"{path}[{index}]", True)
        elif isinstance(node, dict):
            scalar_metadata = {key: child for key, child in node.items()
                               if not isinstance(child, (dict, list))}
            if scalar_metadata and not within_record:
                items.append(ParsedItem("text", json.dumps(scalar_metadata, ensure_ascii=False),
                                        {**base, "jsonPath": path, "jsonPointer": pointer,
                                         "contentRole": "object-scalar-fields"}))
            if not node and not within_record:
                items.append(ParsedItem("text", "{}", {**base, "jsonPath": path, "jsonPointer": pointer}))
            for key, child in node.items():
                if isinstance(child, (dict, list)):
                    walk(child, pointer_child(pointer, key), _path_child(path, key), within_record)
        elif isinstance(node, list):
            # Heterogeneous and scalar arrays are trees, never implicit tables.
            if not within_record:
                items.append(ParsedItem("text", json.dumps(node, ensure_ascii=False),
                                        {**base, "jsonPath": path, "jsonPointer": pointer}))
            for index, child in enumerate(node):
                if isinstance(child, (dict, list)):
                    walk(child, pointer_child(pointer, index), f"{path}[{index}]", within_record)
        elif not within_record:
            items.append(ParsedItem("text", json.dumps(node, ensure_ascii=False),
                                    {**base, "jsonPath": path, "jsonPointer": pointer}))

    walk(value, "", "$", root_is_record)
    return items
