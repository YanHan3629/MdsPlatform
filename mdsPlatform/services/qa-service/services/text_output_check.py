"""Bounded literal safeguards for scoped TXT interpretation.

This checks source references, requested literal identifiers and explicit
quantity-unit declarations and explicit temporal literal membership. It does
not certify free sentence semantics, temporal roles, calculations or inference.
"""
import re
from services.text_evidence_plan import IDENTIFIER,merge,validate_group,residual,literal
from services.text_temporal_literals import check as temporal_check

VERSION='txt-literal-output-scope-2026-10-24.3'
REFERENCE=re.compile(r'\[来源(?:编号)?\s*[:：]?\s*([^\]\s]+)\s*\]')
UNITS=re.compile(r'(?<![A-Za-z0-9_.])[-+]?\d+(?:\.\d+)?\s*(套|台|件|个|箱|组|包|盒|扇|块)(?![A-Za-z])')

def requested_identifiers(query,focused):
    source_ids=set(IDENTIFIER.findall(focused));required=set()
    for identifier in source_ids:
        namespace=identifier.split('-',1)[0]
        if re.search(r'(?<![A-Za-z0-9_-])'+re.escape(namespace)+r'(?![A-Za-z0-9_-])',query):required.add(identifier)
    for match in re.finditer(r'([A-Za-z_][A-Za-z0-9_]*|[\u4e00-\u9fff]{2,12})\s*[:：]?\s*([A-Za-z]{2,12}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*)',focused):
        label,identifier=match.groups()
        asks_identifier=(label.endswith('_id') and re.search(r'(?<![A-Za-z0-9_])'+re.escape(label)+r'(?![A-Za-z0-9_])',query)
            or re.search(re.escape(label)+r'(?:的)?(?:号|编号|标识|ID)',query)
            or re.search(re.escape(label)+r'(?:分别|各自)?(?:是(?:什么|谁)|为谁)',query))
        if asks_identifier and identifier in source_ids:required.add(identifier)
    return required

def validate(question,answer,sources,*,truncated=False):
    if not sources or not all(source.provenance.get('textReadVersion') for source in sources):return None
    items=[{'assetId':str(source.asset_id),'dataVersion':source.data_version,'indexVersion':source.index_version,
        'nativeBundle':source.evidence_bundle,'nativeModelContext':source.model_context,'nativeHumanContext':source.content_preview,
        'provenance':source.provenance} for source in sources]
    validate_group(question,items)
    origin,text=merge([source.evidence_bundle for source in sources]);target=sources[0].provenance['textTarget']
    focused=text[target['characterStart']-origin:target['characterEnd']-origin]
    reasons=[];normalized=[];unknown=[]
    aliases={}
    for index,source in enumerate(sources,1):
        identifiers={source.evidence_id,*({fact['factId'] for fact in source.evidence_bundle['facts']}&set(source.fact_ids))}
        for identifier in identifiers:aliases.setdefault(identifier,set()).add(index)
    mapping={identifier:next(iter(indices)) for identifier,indices in aliases.items() if len(indices)==1}
    def reference(match):
        value=match[1]
        index=int(value) if value.isascii() and value.isdecimal() else mapping.get(value)
        if not index or not 1<=index<=len(sources):unknown.append(value);return match[0]
        result=f'[来源{index}]'
        if result!=match[0]:normalized.append({'original':match[0],'normalized':result})
        return result
    answer=REFERENCE.sub(reference,answer)
    technical=[]
    # Remove only a known read/fact alias at a provenance-only line prefix;
    # keep all business text and the visible source number unchanged.
    def technical_prefix(match):
        identifier=match[2]
        if identifier not in mapping:unknown.append(identifier);return match[0]
        technical.append({'original':match[0],'knownReadAlias':identifier})
        return match[1].rstrip()+('\n' if '\n' in match[0] else ' ')
    answer=re.sub(r'(?m)^(\s*(?:\[来源\d+\]\s*)?)读取事实\s+([A-Za-z0-9][A-Za-z0-9-]{6,80})(?:\s*中)?\s*[，:：]?\s*',technical_prefix,answer)
    cited={int(value) for value in re.findall(r'\[来源(\d+)\]',answer)}
    if truncated:reasons.append('TXT_OUTPUT_LIMIT')
    if unknown:reasons.append('TXT_UNKNOWN_SOURCE_REFERENCE')
    if cited!=set(range(1,len(sources)+1)):reasons.append('TXT_REQUIRED_RECORD_SOURCE_NOT_CITED')
    content=re.sub(r'\[来源\d+\]','',answer).strip(' \t\r\n，。；;:：-*#')
    if not content:reasons.append('TXT_NO_ANSWER_CONTENT')
    source_ids=set(IDENTIFIER.findall(focused));output_ids=set(IDENTIFIER.findall(answer))
    # A filename is identity, not an output business identifier.
    path=sources[0].logical_path
    output_ids=set(IDENTIFIER.findall(residual(answer,path)))
    if output_ids-source_ids:reasons.append('TXT_IDENTIFIER_NOT_IN_TARGET_RECORD')
    query=residual(question,path);required=requested_identifiers(query,focused)
    missing=sorted(identifier for identifier in required if not literal(answer,identifier))
    if missing:reasons.append('TXT_REQUESTED_LITERAL_IDENTIFIER_MISSING')
    source_units=set(UNITS.findall(focused));unit_checks=[]
    if re.search(r'计量单位|单位',query) and len(source_units)==1:
        expected=next(iter(source_units));plain=re.sub(r'[*`#]','',answer)
        # A complete field-line declaration cannot replace a known source
        # quantity unit with "none" or a metadata definition.
        for match in re.finditer(r'(?:计量)?单位\s*[:：]\s*([^\n]+)',plain):
            present=expected in match[1];unit_checks.append({'sourceQuantityUnit':expected,'answerDeclaration':match[1],'matches':present})
            if not present:reasons.append('TXT_QUANTITY_UNIT_DECLARATION_MISMATCH')
    temporal=temporal_check(answer,focused,sources,target)
    if any(not row['supported'] for row in temporal):reasons.append('TXT_TEMPORAL_LITERAL_NOT_IN_TARGET_RECORD')
    return {'version':VERSION,'status':'REJECTED' if reasons else 'CITATION_AND_LITERAL_SCOPE_ONLY',
        'reasons':sorted(set(reasons)),'normalizedAnswer':answer,'referenceNormalizations':normalized,
        'technicalReferenceRemovals':technical,
        'citedSourceIndices':sorted(cited),'requiredLiteralIdentifiers':sorted(required),'missingLiteralIdentifiers':missing,
        'unitDeclarationChecks':unit_checks,
        'temporalLiteralChecks':temporal,
        'verificationScope':'Literal output safeguards and temporal value membership only. Matching a date or clock does not verify its role, entity, order or cited statement. Full sentence semantics, exact per-sentence source support, calculations and inferred relationships remain unverified.'}
