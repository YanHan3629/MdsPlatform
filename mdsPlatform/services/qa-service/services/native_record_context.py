"""Present source numeric expressions and formula trust without inventing values."""
from decimal import Decimal, InvalidOperation


def context_record(values,policy,locator):
    lexemes=policy.get('numberLexemes',{})
    def project(value,pointer,info=None):
        info=info or lexemes.get(pointer)
        if info:
            encoded=(isinstance(value,str) and info.get('sourceType')=='number' and info.get('decodedValueStatus')=='SOURCE_LEXEME_STRING' and value==info.get('lexeme'))
            if encoded:return {'sourceNumber':info['lexeme'],'type':'number','decodedValueStatus':'SOURCE_LEXEME_STRING','usableForCalculation':info.get('calculationEligible',True)}
            if isinstance(value,(int,float)) and not isinstance(value,bool):
                try:
                    if Decimal(info['lexeme'].strip())!=Decimal(str(value)):
                        return {'sourceNumber':info['lexeme'],'type':'number','decodedValueIsRounded':True}
                except (ValueError,InvalidOperation):return {'sourceExpression':info.get('lexeme'),'numericReadStatus':'INVALID_SOURCE_EXPRESSION'}
        if isinstance(value,dict):return {key:project(child,pointer+'/'+key.replace('~','~0').replace('/','~1')) for key,child in value.items()}
        if isinstance(value,list):return [project(child,pointer+'/'+str(index)) for index,child in enumerate(value)]
        return value
    result={field:project(value,locator.get('jsonPointer','')+'/'+field.replace('~','~0').replace('/','~1'),lexemes.get(field)) for field,value in values.items()}
    for field,info in policy.get('formulas',{}).items():
        if field in result:
            result[field]={'formula':info.get('formula'),'cachedValue':result[field],
                'cacheStatus':info.get('cacheStatus'),'usableForCalculation':False}
    return result
