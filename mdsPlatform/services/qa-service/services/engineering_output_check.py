"""Deterministic engineering output limits; this is not a semantic judge."""
from __future__ import annotations
import re
from decimal import Decimal,InvalidOperation

VERSION='engineering-output-scope-2026-10-22.2'
NUMBER=r'[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?'
CITATION=re.compile(r'\[来源\s*(\d+)\]')
QUANTITY=re.compile(r'('+NUMBER+r')\s*(mm[23²³]?|cm[23²³]?|m[23²³]?|毫米|厘米|平方毫米|平方厘米|立方毫米|立方厘米|平方米|立方米|米|kg|公斤)(?![A-Za-z])',re.I)
UNITS={'毫米':'mm','厘米':'cm','米':'m','平方毫米':'mm2','平方厘米':'cm2','平方米':'m2','立方毫米':'mm3','立方厘米':'cm3','立方米':'m3','公斤':'kg'}

def _unit(value):return UNITS.get(value,str(value).lower().replace('²','2').replace('³','3'))
def _numbers(value):
    if isinstance(value,bool) or value is None:return
    if isinstance(value,(int,float,Decimal)):
        yield str(value)
    elif isinstance(value,dict):
        for item in value.values():yield from _numbers(item)
    elif isinstance(value,list):
        for item in value:yield from _numbers(item)

def _equal(value,expected,approximate=False):
    try:
        if len(value)>128 or len(expected)>128:return False
        actual,target=Decimal(value),Decimal(expected)
        if not actual.is_finite() or not target.is_finite() or abs(actual.adjusted())>10000 or abs(target.adjusted())>10000:return False
        tolerance=max(Decimal('1e-9'),abs(target)*Decimal('1e-9'))
        if approximate:tolerance=max(tolerance,Decimal(5).scaleb(actual.as_tuple().exponent-1))
        return abs(actual-target)<=tolerance
    except (InvalidOperation,OverflowError):return False

def _supported(value,unit,sources,fields=None,approximate=False):
    identifiers=[]
    for source in sources:
        sent=set(source.fact_ids)
        for fact in (source.evidence_bundle or {}).get('facts',[]):
            if fact['factId'] not in sent or not fact.get('calculationEligible') or fact.get('evidenceNature')!='program_calculation':continue
            if fields is not None and fact['field'] not in fields:continue
            if unit is not None and _unit(fact.get('unit'))!=_unit(unit):continue
            values=[fact['numberSourceExpression']] if fact.get('numberSourceExpression') is not None else list(_numbers(fact['value']))
            if any(_equal(value,item,approximate) for item in values):identifiers.append(fact['factId'])
    return sorted(set(identifiers))

def validate(answer,sources,*,truncated=False):
    engineering=[source for source in sources if source.provenance.get('engineeringReadVersion')]
    if not engineering:return None
    reasons=[];quantities=[];cited={int(value) for value in CITATION.findall(answer)}
    if not cited:reasons.append('ENGINEERING_CITATION_REQUIRED')
    if any(not 1<=value<=len(sources) for value in cited):reasons.append('ENGINEERING_CITATION_OUTSIDE_ACTUAL_SOURCES')
    if truncated:reasons.append('ENGINEERING_OUTPUT_LIMIT')
    clean=CITATION.sub('',answer)
    if re.search(r'"(?:visualObservationRequired|exactDimensionsMustComeFromGeometryFacts|displayColoursNotSourceMaterial|定位说明)"\s*:',clean):
        reasons.append('INTERNAL_EVIDENCE_CONTEXT_ECHO')
    native_3d=[source for index,source in enumerate(sources,1) if index in cited and source.provenance.get('engineeringReadVersion') and source.original_format in {'OBJ','STEP'}]
    any_3d=any(source.original_format in {'OBJ','STEP'} for source in engineering)
    if any_3d:
        tool_facts=[fact for source in native_3d for fact in (source.evidence_bundle or {}).get('facts',[])
            if fact['factId'] in source.fact_ids and fact.get('calculationEligible') and fact.get('evidenceNature')=='program_calculation']
        if not tool_facts:
            for match in re.finditer(r'(?:由|通过)[^，。；;\n]{0,16}(?:几何|程序)[^，。；;\n]{0,8}计算[^，。；;\n]{0,8}(?:确定|得出|给出)|(?:几何|程序)[^，。；;\n]{0,8}计算(?:得到|得出|确定)',clean):
                before=re.split(r'[，。；;！？?\n]',clean[max(0,match.start()-16):match.start()])[-1]
                if not re.search(r'不(?:是|能|可|曾)|(?:而|并)?非|未经|尚未',before):reasons.append('GEOMETRY_COMPUTATION_CLAIM_WITHOUT_TOOL_FACT')
        if re.search(r'(?:直径|外径|内径|孔径|孔(?:洞|口)?的?直径|壁厚|间隙|干涉量)\s*(?:约|大约|为|是|=|：|:|大约为|约为)*\s*'+NUMBER,clean,re.I):
            reasons.append('UNREGISTERED_GEOMETRY_MEASUREMENT')
        for match in QUANTITY.finditer(clean):
            facts=_supported(match[1],match[2],native_3d,approximate=bool(re.search(r'约|近似',clean[max(0,match.start()-8):match.start()])))
            quantities.append({'value':match[1],'unit':_unit(match[2]),'supportingFactIds':facts})
            if not facts:reasons.append('ENGINEERING_QUANTITY_WITHOUT_TOOL_FACT')
        for field,pattern in [('surfaceArea',r'(?:表面积|surfaceArea)'),('volume',r'(?:体积|volume)')]:
            for match in re.finditer(pattern+r'\s*(?:约|大约|为|是|=|：|:)*\s*('+NUMBER+r')',clean,re.I):
                if not _supported(match[1],None,native_3d,{field},approximate=bool(re.search(r'约|近似',match[0]))):reasons.append('ENGINEERING_PROPERTY_VALUE_MISMATCH')
    if engineering and all(source.original_format=='SVG' for source in engineering):
        for match in re.finditer(r'(?:实际|物理|三维)?实测(?:值|结果|数据)?|几何(?:工具|计算|实测)|测得',clean):
            before=re.split(r'[，。；;！？?\n]',clean[max(0,match.start()-24):match.start()])[-1]
            if not re.search(r'不(?:是|属于|代表|等同|构成|能|可)|(?:而|并)?非|未(?:经|作|做|进行|提供)|无(?:法|实际)',before):reasons.append('SVG_DECLARATION_UPGRADED_TO_MEASUREMENT')
    lines=[re.sub(r'^\s*[-*\d.、]+\s*','',line).strip() for line in clean.splitlines()]
    if any(len(line)>=12 and lines.count(line)>2 for line in set(lines)):reasons.append('ENGINEERING_REPEATED_OUTPUT')
    return {'version':VERSION,'status':'REJECTED' if reasons else 'SCOPE_CHECKED',
        'reasons':sorted(set(reasons)),'citedSourceIndices':sorted(cited),'checkedQuantities':quantities,
        'scope':'Registered source indices, supported numeric operations/units and output limits only; complete statement semantics are not certified.'}


def public_result(result):
    if result['status']!='REJECTED':return result
    return {**{key:value for key,value in result.items() if key!='checkedQuantities'},
        'checkedQuantityCount':len(result['checkedQuantities'])}
