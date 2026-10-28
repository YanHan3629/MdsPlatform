"""Exact elapsed time from one unambiguous native-text pair with source offsets."""
import copy,hashlib,json,re,uuid
from datetime import datetime
from fractions import Fraction
from services.evidence_policy import terms

VERSION='document-time-difference-2026-10-22.1'
TIMESTAMP=r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})'


def calculate_duration(question,evidence):
    positive=re.sub(r'(?:不要|不用|不能|无需|不应|请勿|而非|而不是)[^，。；;！？?\n]*',' ',question)
    if not re.search(r'相差|间隔|经过|持续|时长|多长|多少(?:分钟|小时|秒)',positive):return None
    units=[(word,name,divisor) for word,name,divisor in [('分钟','min',60000000),('小时','h',3600000000),('秒','s',1000000)] if word in positive]
    if len(units)!=1:raise ValueError('NEEDS_CLARIFICATION: 时间差计算需要明确一种输出时间单位。')
    options=[]
    for item in evidence:
        bundle=item['nativeBundle'];text=bundle['content'].get('text','');matches=list(re.finditer(TIMESTAMP,text))
        if len(matches)!=2 or any(fact['evidenceNature']!='source_text' or not fact.get('calculationEligible',True) for fact in bundle['facts']):continue
        label=text[:matches[0].start()].strip();score=len(terms(label)&terms(positive))
        if score>=2:options.append((score,item,[match[0] for match in matches],label))
    options.sort(key=lambda option:-option[0])
    if not options or len(options)>1 and options[0][0]==options[1][0]:
        raise ValueError('NEEDS_CLARIFICATION: 未唯一定位到与所问时间角色对应的两个原生时区时间，不能由模型代算。')
    _,parent,values,label=options[0]
    if any(re.search(r'\.\d{7,}',value) for value in values):
        raise ValueError('EVIDENCE_TIME_PRECISION_UNSUPPORTED: 本工具尚不支持高于微秒的时间精度')
    try:start,end=[datetime.fromisoformat(value.replace('Z','+00:00')) for value in values]
    except ValueError as error:raise ValueError('EVIDENCE_TIME_INVALID: 原生时间表达无效') from error
    delta=end-start;micros=(delta.days*86400+delta.seconds)*1000000+delta.microseconds
    if micros<0:raise ValueError('EVIDENCE_TIME_ORDER_INVALID: 来源结束时间早于开始时间')
    _,unit,divisor=units[0];number=Fraction(micros,divisor);display=str(number.numerator) if number.denominator==1 else f'{number.numerator}/{number.denominator}'
    original=parent['nativeBundle'];inputs={'start':values[0],'end':values[1],'field':label,'unit':unit,
        'inputFactIds':[fact['factId'] for fact in original['facts']],'inputReadId':original['readId'],
        'inputSourceSha256':original['identity']['sourceSha256'],'formula':'elapsed_microseconds / output_unit_microseconds',
        'elapsedMicroseconds':str(micros),'unitMicroseconds':str(divisor),'exactNumerator':str(number.numerator),'exactDenominator':str(number.denominator)}
    specification={key:value for key,value in inputs.items() if key!='inputReadId'}
    canonical=json.dumps({'version':VERSION,'inputs':specification},ensure_ascii=False,sort_keys=True,separators=(',',':'))
    evidence_id=str(uuid.uuid5(uuid.NAMESPACE_URL,canonical));fact_id=str(uuid.uuid5(uuid.NAMESPACE_URL,evidence_id+':value'))
    bundle=copy.deepcopy(original)
    from services.read_receipt import mark_derivation
    mark_derivation(original,bundle,VERSION,specification)
    bundle.update(evidenceId=evidence_id,representationId=evidence_id,representationKind='document_duration_calculation',
        recipeVersion=VERSION,evidenceNature='program_calculation',parentEvidenceIds=[original['evidenceId']],readId=str(uuid.uuid4()))
    bundle['content']['calculation']={**specification,'value':display};bundle['facts'].append({'factId':fact_id,'field':'elapsed_time','value':display,
        'unit':unit,'evidenceNature':'program_calculation','locator':original['locator'],'calculationEligible':True,'qualityFlags':[]})
    bundle['transforms'].append({'version':VERSION,'parameters':specification});bundle.pop('readOutputSha256',None)
    content={key:value for key,value in bundle.items() if key not in {'readId','readOperation','readRepresentations','representationContentSha256'}}
    bundle['representationContentSha256']=hashlib.sha256(json.dumps(content,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    bundle['readOutputSha256']=hashlib.sha256(json.dumps(bundle,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return {'parent':parent,'bundle':bundle,'inputs':inputs,'operation':'elapsed_time','field':label,'value':display,'unit':unit}


def render_duration(sources):
    values=[(index,source) for index,source in enumerate(sources,1) if source.provenance.get('documentTimeVersion')==VERSION]
    if len(values)!=1:return None
    index,source=values[0];calculation=source.evidence_bundle['content']['calculation'];labels={'min':'分钟','h':'小时','s':'秒'}
    return (f"{calculation['field']}：从 {calculation['start']} 至 {calculation['end']}，相差 {calculation['value']} {labels[calculation['unit']]}。[来源{index}]"
        '\n该时间差由程序按两个原生时间的时区偏移计算。')
