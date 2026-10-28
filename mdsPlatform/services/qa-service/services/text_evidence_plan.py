"""Read a named frozen TXT and select complete source record boundaries.

Discovery uses registered native prose, preserving original line endings and
code-point coordinates. No filename, evaluation answer or model summary is
used as content. Selected native bundles remain unchanged.
"""
from __future__ import annotations
import hashlib,json,re,time,uuid
from services.native_evidence_read import source_from_bundle
from services.read_receipt import verify,digest
from services.evidence_policy import terms

VERSION='txt-record-scope-read-2026-10-24.3'
CONTEXT_VERSION='txt-context-literal-prose-2026-10-24.1'
LEGACY_CONTEXT_VERSION='txt-context-compact-json-2026-10-24.1'
MAX_READS=24
MAX_BYTES=2*1024*1024
MAX_SECONDS=45
MAX_TARGET_CHARACTERS=1800
IDENTIFIER=re.compile(r'(?<![A-Za-z0-9_-])[A-Za-z]{2,12}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_-])')

def require(value,code):
    if not value:raise ValueError(code)

def literal(text,value):return bool(value and re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(value)+r'(?![A-Za-z0-9_.-])',text))

def residual(question,path):
    for name in (path,path.rsplit('/',1)[-1]):question=question.replace(name,' ')
    return question

def named_asset(question,filters,intent):
    if not re.search(r'\.txt\b',question,re.I):return None
    names={name.casefold() for name in re.findall(r'(?<![A-Za-z0-9_.-])([\w.-]+\.txt)\b',question,re.I)}
    if len(names)>1:raise ValueError('NEEDS_CLARIFICATION: 本次 TXT 段落精读需要唯一文件，不能忽略其他文件。')
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus' and asset.get('originalFormat')=='TXT' and asset.get('nativeEvidence')]
    full=[asset for asset in assets if literal(question,asset['logicalPath'])]
    selected=full or [asset for asset in assets if literal(question,asset['logicalPath'].rsplit('/',1)[-1])]
    if re.search(r'\b(?:https?|file|ftp)://',question,re.I):raise ValueError('NEEDS_CLARIFICATION: 外部地址不能替换为同名快照文件。')
    if len(selected)!=1:raise ValueError('NEEDS_CLARIFICATION: 指定 TXT 未能唯一匹配当前授权快照，请明确逻辑路径。')
    asset=selected[0]
    if intent.allowed and 'TXT' not in intent.allowed:raise ValueError('NEEDS_CLARIFICATION: 指定 TXT 与来源格式限制不一致。')
    return asset

def native_text(bundle):
    receipt=verify(bundle);where=bundle.get('locator',{});content=bundle.get('content',{});facts=bundle.get('facts',[])
    start,end=where.get('characterStart'),where.get('characterEnd');text=content.get('text')
    require(bundle.get('representationKind')=='text_passage' and bundle['identity'].get('originalFormat')=='TXT','TXT_NATIVE_KIND_MISMATCH')
    require(receipt['operation']=={'operation':'read_representation','representationId':bundle['representationId']},'TXT_READ_OPERATION_CHANGED')
    require(type(start) is int and type(end) is int and 0<=start<end and where.get('characterIndexUnit')=='unicode-codepoint','TXT_CHARACTER_SCOPE_INVALID')
    require(isinstance(text,str) and len(text)==end-start and not bundle.get('coverage',{}).get('truncated'),'TXT_NATIVE_TEXT_INCOMPLETE')
    require(len(facts)==1 and facts[0].get('value')==text and facts[0].get('locator')==where
        and facts[0].get('evidenceNature')=='source_text','TXT_NATIVE_FACT_MISMATCH')
    return start,end,text

def merge(bundles):
    pieces=sorted((native_text(bundle) for bundle in bundles),key=lambda row:(row[0],row[1]))
    require(bool(pieces),'TXT_PROSE_UNAVAILABLE')
    origin=pieces[0][0];joined=''
    for start,end,text in pieces:
        offset=start-origin
        require(offset<=len(joined),'TXT_PROSE_SCOPE_GAP')
        overlap=min(len(joined)-offset,len(text))
        require(joined[offset:offset+overlap]==text[:overlap],'TXT_PROSE_OVERLAP_CONFLICT')
        joined+=text[overlap:]
    return origin,joined

def locate_target(question,path,text,origin=0):
    query=residual(question,path)
    if re.search(r'第\s*-?\d+\s*行|上一版|历史版本|比较版本|排除|除外|不要读取',query):
        raise ValueError('NEEDS_CLARIFICATION: 当前 TXT 记录精读尚不支持该行号、版本或排除条件，请明确一个记录范围。')
    identifiers=list(dict.fromkeys(IDENTIFIER.findall(query)))
    if len(identifiers)>1:raise ValueError('NEEDS_CLARIFICATION: 多个标识的同一记录关系尚未验证，请分开记录或明确关联依据。')
    explicit_fields=set(re.findall(r'(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+(?![A-Za-z0-9_])',query))
    if any(not re.search(r'(?<![A-Za-z0-9_])'+re.escape(field)+r'(?![A-Za-z0-9_])',text) for field in explicit_fields):
        raise ValueError('NEEDS_CLARIFICATION: 部分所问字段未在已登记 TXT 原文中找到，不能用其他字段替代。')
    if identifiers:
        identifier=identifiers[0];family=identifier.split('-',1)[0]
        anchors=list(re.finditer(r'^'+re.escape(family)+r'-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_-])',text,re.M))
        if len(anchors)>=2:
            matches=[(match.start(),anchors[index+1].start() if index+1<len(anchors) else len(text))
                for index,match in enumerate(anchors) if match[0]==identifier]
            method='source-line-anchor-family'
        else:
            blocks=[];start=0
            for delimiter in re.finditer(r'\r?\n[ \t]*\r?\n',text):blocks.append((start,delimiter.start()));start=delimiter.end()
            blocks.append((start,len(text)))
            matches=[(start,end) for start,end in blocks if literal(text[start:end],identifier)]
            method='source-blank-line-paragraph'
        if not matches:return None
        if len(matches)!=1:raise ValueError('NEEDS_CLARIFICATION: 该标识出现在多个原文记录中，请补充可验证的记录范围。')
        start,end=matches[0]
    else:
        start,end=0,len(text);identifier=None;method='all-registered-small-prose'
        content_query=re.sub(r'TXT|文件|原文|记录|字段|只依据|仅依据|是什么|分别|请问|说明',' ',query,flags=re.I)
        if len(terms(content_query)&terms(text))<2 and not explicit_fields:return None
    if end-start>MAX_TARGET_CHARACTERS:
        raise ValueError('TXT_TARGET_BUDGET_EXCEEDED: 完整目标记录超出本次精读容量，请明确较小的记录或段落范围。')
    selected=text[start:end]
    return {'characterStart':origin+start,'characterEnd':origin+end,'characterIndexUnit':'unicode-codepoint',
        'identifier':identifier,'selectionMethod':method,'sourceTextSha256':hashlib.sha256(selected.encode()).hexdigest()}

def cover(bundles,target):
    result=[];position=target['characterStart']
    while position<target['characterEnd']:
        choices=[bundle for bundle in bundles if bundle['locator']['characterStart']<=position<bundle['locator']['characterEnd']]
        require(bool(choices),'TXT_TARGET_SCOPE_GAP')
        chosen=max(choices,key=lambda bundle:bundle['locator']['characterEnd']);result.append(chosen);position=chosen['locator']['characterEnd']
    return result

def model_context(bundle,target,context_version=CONTEXT_VERSION):
    start,end,_=native_text(bundle)
    # Keep every source character. Repeating full identity/locator/hash objects
    # in each single-text fact wastes the actual 2048-token serving context.
    # The immutable native bundle and receipt retain those complete objects.
    if context_version==CONTEXT_VERSION:
        fact,=bundle['facts']
        return (f"读取事实 {fact['factId']}\n原文字符区间 [{start},{end})；目标记录 [{target['characterStart']},{target['characterEnd']})\n"
            +bundle['content']['text'])
    require(context_version==LEGACY_CONTEXT_VERSION,'TXT_CONTEXT_VERSION_UNSUPPORTED')
    value={'evidenceId':bundle['evidenceId'],'range':[start,end],
        'focus':[target['characterStart'],target['characterEnd']],
        'facts':[{'factId':fact['factId'],'value':fact['value'],
            **({'unit':fact['unit']} if fact.get('unit') is not None else {})} for fact in bundle['facts']]}
    return json.dumps(value,ensure_ascii=False,separators=(',',':'))

def validate_item(question,item):
    provenance=item['provenance'];bundle=item['nativeBundle'];identity=bundle['identity'];target=provenance['textTarget']
    require(provenance.get('textReadVersion')==VERSION and provenance.get('validatedTextQuestion')==question,'TXT_READ_NOT_VERIFIED')
    require(identity['assetId']==item['assetId'] and identity['versionId']==item['dataVersion'] and identity['indexVersionId']==item['indexVersion']
        and identity['sourceSha256']==provenance.get('sourceSha256') and identity['logicalPath']==provenance.get('explicitSourcePath')
        and literal(question,identity['logicalPath'].rsplit('/',1)[-1]),'TXT_SOURCE_IDENTITY_MISMATCH')
    identifiers=list(dict.fromkeys(IDENTIFIER.findall(residual(question,identity['logicalPath']))))
    require(identifiers==([target['identifier']] if target.get('identifier') else []),'TXT_TARGET_IDENTIFIER_CHANGED')
    start,end,text=native_text(bundle)
    require(type(target.get('characterStart')) is int and type(target.get('characterEnd')) is int
        and target['characterStart']<target['characterEnd'] and max(start,target['characterStart'])<min(end,target['characterEnd']),'TXT_TARGET_SCOPE_MISMATCH')
    kind=provenance.get('textContextVersion',LEGACY_CONTEXT_VERSION)
    require(item['nativeModelContext']==model_context(bundle,target,kind) and item['nativeHumanContext']==text,'TXT_MODEL_CONTEXT_CHANGED')
    return True

def validate_group(question,items):
    require(bool(items),'TXT_TARGET_EVIDENCE_INCOMPLETE')
    target=items[0]['provenance']['textTarget'];identity=items[0]['nativeBundle']['identity']
    for item in items:
        validate_item(question,item)
        require(item['nativeBundle']['identity']==identity and item['provenance']['textTarget']==target,'TXT_TARGET_COHORT_MISMATCH')
    origin,text=merge([item['nativeBundle'] for item in items]);start,end=target['characterStart']-origin,target['characterEnd']-origin
    require(0<=start<end<=len(text),'TXT_TARGET_EVIDENCE_INCOMPLETE')
    selected=text[start:end]
    require(hashlib.sha256(selected.encode()).hexdigest()==target['sourceTextSha256'],'TXT_TARGET_TEXT_CHANGED')
    require(not target.get('identifier') or literal(selected,target['identifier']),'TXT_TARGET_ANCHOR_NOT_READ')
    return True

def direct_text_question(client,scope,question,filters,intent):
    asset=named_asset(question,filters,intent)
    if asset is None:return None
    metadata=client.evidence_metadata(*scope,asset['assetId'])
    require(metadata.get('nativeEvidenceStatus')=='AVAILABLE','EVIDENCE_MANIFEST_UNAVAILABLE')
    descriptors=[rep for rep in metadata['representations'] if rep.get('kind')=='text_passage']
    require(0<len(descriptors)<=MAX_READS,'TXT_DISCOVERY_READ_BUDGET_EXCEEDED')
    started=time.monotonic();bundles=[];reads=[];size=0
    for descriptor in sorted(descriptors,key=lambda rep:rep['locator']['characterStart']):
        require(time.monotonic()-started<MAX_SECONDS,'TXT_DISCOVERY_TIME_BUDGET_EXCEEDED')
        operation={'operation':'read_representation','representationId':descriptor['representationId']}
        bundle=client.read_evidence(*scope,asset['assetId'],operation);native_text(bundle)
        identity=bundle['identity']
        require(all(identity.get(key)==str(value) for key,value in zip(('datasetId','versionId','indexVersionId'),scope))
            and identity['assetId']==asset['assetId'] and identity['sourceSha256']==metadata['sourceSha256']
            and identity['rawCommitId']==metadata['rawCommitId'] and identity['logicalPath']==asset['logicalPath'],'TXT_DISCOVERY_SOURCE_MISMATCH')
        require(bundle['representationId']==descriptor['representationId'] and bundle['locator']==descriptor['locator'],'TXT_DISCOVERY_LOCATOR_MISMATCH')
        size+=len(json.dumps(bundle,ensure_ascii=False).encode())
        require(size<=MAX_BYTES and time.monotonic()-started<=MAX_SECONDS,'TXT_DISCOVERY_BUDGET_EXCEEDED')
        bundles.append(bundle);reads.append({'state':'READ_FOR_TEXT_SCOPE','readId':bundle['readId'],'evidenceId':bundle['evidenceId'],
            'representationId':bundle['representationId'],'operation':operation,'readOutputSha256':bundle['readOutputSha256'],
            'coverage':bundle['coverage'],'selectedForContextBudgeting':False})
    origin,text=merge(bundles);require(origin==0,'TXT_REGISTERED_PROSE_START_UNRESOLVED')
    target=locate_target(question,asset['logicalPath'],text)
    if target is None:return {'evidence':[],'nativeReads':reads,'status':'UNKNOWN_ENTITY' if IDENTIFIER.search(residual(question,asset['logicalPath'])) else 'NO_RELEVANT_EVIDENCE',
        'textPlan':{'version':VERSION,'requiredEvidenceIds':[],'discoveryReads':len(reads),'registeredProseCharacters':len(text)}}
    evidence=[]
    for bundle in cover(bundles,target):
        operation={'operation':'read_representation','representationId':bundle['representationId']}
        candidate={'assetId':asset['assetId'],'dataVersion':str(scope[1]),'indexVersion':str(scope[2]),
            'evidenceId':str(uuid.uuid5(uuid.NAMESPACE_URL,str(scope)+asset['assetId']+bundle['representationId'])),
            'semanticSpace':'native.txt.record','locator':bundle['locator'],'facts':{},'scoreType':'verified_text_read',
            'rawScore':None,'rank':len(evidence)+1,'evidenceType':'constraint','qualityFlags':bundle['qualityFlags'],
            'preview':{'logicalPath':asset['logicalPath']},'provenance':{'scope':asset['scope'],'originalFormat':'TXT'}}
        item,_=source_from_bundle(scope,candidate,bundle,metadata,operation)
        item['provenance'].update(textReadVersion=VERSION,validatedTextQuestion=question,textTarget=target,
            textContextVersion=CONTEXT_VERSION,
            explicitSourcePath=asset['logicalPath'],registeredProseCharacters=len(text),
            registeredContentRoles=sorted({rep['locator'].get('contentRole','prose') for rep in descriptors}))
        item['nativeModelContext']=model_context(bundle,target)
        evidence.append(item)
        next(read for read in reads if read['readId']==bundle['readId'])['selectedForContextBudgeting']=True
    validate_group(question,evidence)
    return {'evidence':evidence,'nativeReads':reads,'status':'OK','planningMethod':'bounded-source-txt-record',
        'textPlan':{'version':VERSION,'target':target,'requiredEvidenceIds':[item['evidenceId'] for item in evidence],
            'discoveryReads':len(reads),'selectedWholePassages':len(evidence),'readBytes':size,'registeredProseCharacters':len(text),
            'coverageScope':'registered-prose-only; tabular sample rows require their own native operation'}}
