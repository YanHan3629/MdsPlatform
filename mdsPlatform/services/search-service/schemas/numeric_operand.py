"""Bounded numeric operands, independent of binary float transport."""
import math
import re
from decimal import Decimal,InvalidOperation

NUMBER_LEXEME_ENCODING='source-number-lexeme-v1'
JSON_NUMBER=re.compile(r'-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\Z')

def operand_decimal(value,encoding=None):
    if encoding:
        if encoding!=NUMBER_LEXEME_ENCODING or not isinstance(value,str) or len(value)>32768 or not JSON_NUMBER.fullmatch(value):
            raise ValueError('INVALID_NUMERIC_OPERAND')
        expression=value
    else:
        if not isinstance(value,(int,float)) or isinstance(value,bool):return None
        if isinstance(value,float) and not math.isfinite(value):raise ValueError('INVALID_NUMERIC_OPERAND')
        expression=str(value)
    try:decimal=Decimal(expression)
    except (InvalidOperation,ValueError) as error:raise ValueError('INVALID_NUMERIC_OPERAND') from error
    if not decimal.is_finite() or len(decimal.as_tuple().digits)>4096 or abs(decimal.as_tuple().exponent)>4096:
        raise ValueError('NUMERIC_OPERAND_PRECISION_LIMIT')
    return decimal
