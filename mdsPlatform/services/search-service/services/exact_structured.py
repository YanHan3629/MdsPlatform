"""Decimal arithmetic from source numeric expressions, with an input audit."""
from __future__ import annotations

import hashlib
import json
import math
from decimal import Decimal, Inexact, localcontext
from fractions import Fraction
from schemas.numeric_operand import operand_decimal

ALIASES={'°C':'C','℃':'C','摄氏度':'C','开尔文':'K','毫米':'mm','厘米':'cm','米':'m',
         'mm²':'mm2','cm²':'cm2','m²':'m2','mm³':'mm3','cm³':'cm3','m³':'m3'}
FACTORS={
    'mm':('length','0.001'),'cm':('length','0.01'),'m':('length','1'),
    's':('time','1'),'min':('time','60'),'h':('time','3600'),
    'mm2':('area','0.000001'),'cm2':('area','0.0001'),'m2':('area','1'),
    'mm3':('volume','0.000000001'),'cm3':('volume','0.000001'),'m3':('volume','1'),
}


def nested(values,field):
    if field in values:
        return values[field]
    value=values
    for part in field.split('.'):
        if isinstance(value,dict):value=value.get(part)
        elif isinstance(value,list) and part.isdigit() and int(part)<len(value):value=value[int(part)]
        else:return None
    return value


def numeric_source(row,field):
    value=nested(row['values'],field)
    lexemes=row.get('numberLexemes',{})
    entry=lexemes.get(field)
    if entry is None:
        parts=[field] if field in row['values'] else field.split('.')
        pointer=row.get('locator',{}).get('jsonPointer','')+''.join('/'+part.replace('~','~0').replace('/','~1') for part in parts)
        entry=lexemes.get(pointer)
    encoded=(isinstance(value,str) and entry is not None and entry.get('sourceType')=='number'
        and entry.get('decodedValueStatus')=='SOURCE_LEXEME_STRING' and value==entry.get('lexeme'))
    numeric=(isinstance(value,(int,float)) and not isinstance(value,bool)) or encoded
    return value,entry,numeric


def source_decimal(row,field):
    value,entry,numeric=numeric_source(row,field)
    if not numeric:return None,False
    if entry is not None:
        if entry.get('calculationEligible') is False:raise ValueError('SOURCE_NUMERIC_PRECISION_LIMIT')
        try:return Decimal(entry['lexeme'].strip()),True
        except ArithmeticError as error:raise ValueError('SOURCE_NUMERIC_EXPRESSION_UNSUPPORTED') from error
    return Decimal(str(value)),False


def convert(value,source,target):
    source,target=ALIASES.get(source,source),ALIASES.get(target,target)
    if not target or source==target:return value
    if not source:raise ValueError('UNIT_UNKNOWN')
    if source=='C' and target=='K':return value+Fraction('273.15')
    if source=='K' and target=='C':return value-Fraction('273.15')
    left,right=FACTORS.get(source),FACTORS.get(target)
    if not left or not right or left[0]!=right[0]:raise ValueError('UNIT_INCOMPATIBLE')
    return value*Fraction(left[1])/Fraction(right[1])


def _fraction(value):
    digits=value.as_tuple()
    if not value.is_finite() or abs(digits.exponent)>4096 or len(digits.digits)>4096:
        raise ValueError('SOURCE_NUMERIC_PRECISION_LIMIT')
    return Fraction(value)


def _integer_text(value):
    if value.bit_length()>54428:raise ValueError('RESULT_NUMERIC_PRECISION_LIMIT')
    text=format(Decimal(value),'f')
    if len(text.lstrip('-'))>16384:raise ValueError('RESULT_NUMERIC_PRECISION_LIMIT')
    return text


def numeric_match(row,condition):
    value,from_source=source_decimal(row,condition.field)
    encoding=condition.value_encoding
    if value is None:return False if encoding else None
    if (not from_source and not encoding) or condition.op not in {'eq','in','gt','gte','lt','lte','between'}:return None
    operands=condition.value if condition.op in {'in','between'} else [condition.value]
    values=[operand_decimal(item,encoding) for item in operands]
    if condition.op=='in':values=[item for item in values if item is not None]
    if not values or any(item is None for item in values):return False
    if condition.unit:
        value=convert(_fraction(value),row.get('units',{}).get(condition.field),condition.unit)
        values=[_fraction(bound) for bound in values]
    if condition.op=='eq':return value==values[0]
    if condition.op=='in':return value in values
    if condition.op=='gt':return value>values[0]
    if condition.op=='gte':return value>=values[0]
    if condition.op=='lt':return value<values[0]
    if condition.op=='lte':return value<=values[0]
    return values[0]<=value<=values[1]


def aggregate(rows,field,operation,target_unit):
    numbers=[];missing=0;legacy=0;digest=hashlib.sha256()
    for row in rows:
        # Count concerns record presence, including nulls and nonnumeric values.
        # It must not parse or impose arithmetic precision on a field's value.
        value,from_source=source_decimal(row,field) if field and operation!='count' else (None,False)
        digest.update(json.dumps({'assetId':row.get('assetId'),'tableId':row.get('unitId'),'locator':row.get('locator'),
            'value':str(value) if value is not None else None,'unit':row.get('units',{}).get(field)},ensure_ascii=False,sort_keys=True,separators=(',',':')).encode())
        if value is None:missing+=1;continue
        if not from_source:legacy+=1
        exact=_fraction(value)
        numbers.append(exact if operation=='count' else convert(exact,row.get('units',{}).get(field),target_unit))
    if operation=='count':result=Fraction(len(rows))
    elif not numbers:result=None
    elif operation=='sum':result=sum(numbers,Fraction(0))
    elif operation=='min':result=min(numbers)
    elif operation=='max':result=max(numbers)
    else:result=sum(numbers,Fraction(0))/len(numbers)
    ratio={'numerator':_integer_text(result.numerator),'denominator':_integer_text(result.denominator)} if result is not None else None
    with localcontext() as context:
        context.prec=4096
        decimal=Decimal(result.numerator)/Decimal(result.denominator) if result is not None else None
        rounded=bool(context.flags[Inexact])
        if rounded:
            context.prec=50
            decimal=Decimal(result.numerator)/Decimal(result.denominator)
        status='FINITE_JSON_NUMBER';serialized=None
        if result is not None:
            if result.denominator==1:
                if abs(result.numerator)<=9007199254740991:serialized=result.numerator
                else:status='INTEGER_PRECISION_SEE_EXACT_VALUE'
            else:
                serialized=float(decimal)
                if not math.isfinite(serialized):serialized=None;status='OVERFLOW_SEE_DECIMAL_VALUE'
                elif serialized==0 and result:serialized=None;status='UNDERFLOW_SEE_DECIMAL_VALUE'
        output={'value':serialized,'decimalValue':str(decimal) if decimal is not None else None,
                'exactValue':str(decimal) if decimal is not None and not rounded else None,
                'exactRatio':ratio,'numericInputCount':len(numbers),'excludedNullOrNonNumericCount':missing,
                'sourceLexemeCount':len(numbers)-legacy,'legacyDecodedNumberCount':legacy,
                'inputDigest':digest.hexdigest(),'decimalRounded':rounded,'numericValueStatus':status,
                'arithmetic':'exact-rational-source-lexemes-v1','decimalPrecision':context.prec}
        if operation=='count':output.update(numericInputCount=None,excludedNullOrNonNumericCount=None,countPolicy='all-matched-rows')
        return output
