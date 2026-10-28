"""Check present-request measurement attribution, without judging document truth.

A source can describe a historic measurement. Reading that source is not a
measurement performed by this request. Quotes keep the original written/OCR
nature and bind exact spans to facts actually transmitted to the model.
"""
from __future__ import annotations
import hashlib,re,uuid
from decimal import Decimal,InvalidOperation

VERSION='document-output-attribution-2026-10-23.2'
CURRENT_MEASUREMENT=re.compile(
    r'(?:本次|本系统|本工具|此次)[^，。；;！？?\n]{0,16}(?:几何|工具|程序)[^，。；;！？?\n]{0,10}(?:实测|测得|测量(?:得到|得出|结果))'
    r'|(?:已|刚刚)(?:通过|使用|用)[^，。；;！？?\n]{0,12}(?:几何|测量)工具[^，。；;！？?\n]{0,10}(?:测得|测出|实测)')
NEGATION=re.compile(r'(?:不(?:是|属于|代表|等同|构成|能|可|曾)|(?:而|并)?非|未(?:经|作|做|进行|提供|使用|调用)|尚未|没有|无(?:法|实际))')
LITERAL_COUNT=re.compile(r'(?<![A-Za-z0-9_.])([+-]?\d+(?:\.\d+)?)\s*(箱|套|台|件|个|名|组|批|盒|包|扇|块|人)(?![A-Za-z])')


def _negative_count(text,start):
    before=re.split(r'[，。；;！？?\n]|而是|却是|但是|但|而',text[max(0,start-32):start])[-1]
    return bool(re.search(r'不能|不是|不应|不等于|不可|并非|而非|是否|能否|是不是',before))


def _literal_counts(sources):
    counts={}
    for source in sources:
        if not source.provenance.get('documentReadVersion'):continue
        for fact in (source.evidence_bundle or {}).get('facts',[]):
            if fact['factId'] not in source.fact_ids or fact.get('evidenceNature') not in {'source_text','source_declaration'} or not isinstance(fact.get('value'),str):continue
            for match in LITERAL_COUNT.finditer(fact['value']):
                if len(match[1])>128 or _negative_count(fact['value'],match.start()):continue
                try:number=Decimal(match[1])
                except InvalidOperation:continue
                counts.setdefault(match[2],{}).setdefault(number,[]).append(fact['factId'])
    return counts


def validate(answer,sources):
    documents=[source for source in sources if source.provenance.get('documentReadVersion') or source.provenance.get('textReadVersion')]
    if not documents:return None
    # Registered geometry facts may support a separate actual tool operation;
    # this guard does not certify that operation or its numeric correctness.
    geometry=any(source.provenance.get('engineeringReadVersion') and source.representation_kind=='geometry_properties'
        and any(fact.get('evidenceNature')=='program_calculation' and fact['factId'] in source.fact_ids
                for fact in (source.evidence_bundle or {}).get('facts',[])) for source in sources)
    # A visible region or a computed result can have a different counting
    # scope. This literal membership check cannot validate those operations.
    literal_only=not any(source.source_type in {'dataset_visual','user_image'} or any(
        fact.get('evidenceNature')=='program_calculation' and fact['factId'] in source.fact_ids
        for fact in (source.evidence_bundle or {}).get('facts',[])) for source in sources)
    rejected=[];counts=_literal_counts(documents) if literal_only else {};checked=[]
    if not geometry:
        for match in CURRENT_MEASUREMENT.finditer(answer):
            before=re.split(r'[，。；;！？?\n]|而是|却是|但是|但|而',answer[max(0,match.start()-32):match.start()])[-1]
            actions=list(re.finditer(r'实测|测得|测出|测量',match[0]))
            qualifier=before+match[0][:actions[-1].start()]
            if not NEGATION.search(qualifier):rejected.append({'start':match.start(),'end':match.end(),'reason':'DOCUMENT_READ_UPGRADED_TO_CURRENT_MEASUREMENT'})
    for match in LITERAL_COUNT.finditer(answer):
        if match[2] not in counts or len(match[1])>128:continue
        if _negative_count(answer,match.start()):continue
        number=Decimal(match[1]);supported=sorted(set(counts[match[2]].get(number,[])))
        checked.append({'start':match.start(),'end':match.end(),'supportingFactIds':supported})
        if not supported:rejected.append({'start':match.start(),'end':match.end(),'reason':'OBJECT_COUNT_ABSENT_FROM_ACTUAL_NATIVE_TEXT'})
    return {'version':VERSION,'status':'REJECTED' if rejected else 'ATTRIBUTION_AND_LITERAL_COUNT_SCOPE_CHECKED','rejections':rejected,'checkedLiteralCounts':checked,
        'scope':'Current tool-measurement attribution and explicit native-text object-count/unit membership only. Matching a count does not verify its entity or statement semantics. OCR, visual counts, conversions and derived counts are not certified.'}


def source_excerpts(sources,check=None):
    """A bounded fallback of exact input quotes, never a repaired model answer."""
    reasons={row['reason'] for row in (check or {}).get('rejections',[])}
    answer=('模型的测量归因或数量表述缺少已读证据支持，该解释未通过核验。' if reasons else '模型的测量归因未通过核验。')
    answer+='以下仅列出本次实际读取的文字片段，不能视为完整问题已获解答。'
    if not check or 'DOCUMENT_READ_UPGRADED_TO_CURRENT_MEASUREMENT' in reasons:answer+='本次未执行所述几何测量。'
    claims=[];seen=set();omitted=0
    for index,source in enumerate(sources,1):
        bundle=source.evidence_bundle or {}
        if not (source.provenance.get('documentReadVersion') or source.provenance.get('textReadVersion')) or not bundle.get('readId') or not bundle.get('readOutputSha256'):continue
        for fact in bundle.get('facts',[]):
            text=fact.get('value');nature=fact.get('evidenceNature')
            if fact['factId'] not in source.fact_ids or nature not in {'source_text','source_declaration','ocr_text'} or not isinstance(text,str) or not text:continue
            key=(bundle['identity']['sourceSha256'],str(fact.get('locator')),text)
            if key in seen:continue
            seen.add(key)
            if len(claims)>=8 or len(answer)+len(text)>6000:omitted+=1;continue
            label='OCR 识别观察，尚未核实原图文字' if nature=='ocr_text' else '原文摘录'
            answer+=f'\n\n{label}：\n';start=len(answer);answer+=text;end=len(answer);answer+=f' [来源{index}]'
            claims.append({'claimId':str(uuid.uuid5(uuid.NAMESPACE_URL,fact['factId']+':'+text)),
                'evidenceNature':nature,'verification':'exact-ocr-observation-quote-not-source-value-verification' if nature=='ocr_text' else 'exact-source-quote-not-semantic-endorsement',
                'text':text,'answerStart':start,'answerEnd':end,'offsetUnit':'unicode-code-points',
                'sourceIndices':[index],'evidenceIds':[source.evidence_id],'factIds':[fact['factId']],
                'readIds':[bundle['readId']],'readOutputSha256':bundle['readOutputSha256'],
                'sourceSha256':bundle['identity']['sourceSha256'],'locator':fact['locator'],
                'quotedValueSha256':hashlib.sha256(text.encode()).hexdigest()})
    if omitted:answer+='\n\n部分已读片段未在此展开；以上摘录不是完整文件结论。'
    if not claims:answer+='\n本次没有可完整列出的已读文字片段，请核对原始来源。'
    return answer,claims
