"""Request-local user evidence, kept distinct from frozen dataset identities."""
import base64,copy,hashlib,json,re,uuid
from io import BytesIO
from PIL import Image
from schemas.qa_resp import SourceItem

VERSION='user-input-receipt-2026-10-27.1'
IMAGE_CONTEXT='用户所附图片，文件名不构成图片内容依据。'
CITATION=re.compile(r'\[来源\s*(\d+)\]')
def digest(value):return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
def sha(raw):return hashlib.sha256(raw).hexdigest()
def require(value,code):
    if not value:raise ValueError(code)
def is_user(source):return source.source_type in {'user_text','user_image'} and source.provenance.get('userInputVersion')==VERSION
def has_user(sources):return any(is_user(s) for s in sources)
def prose_eligible(sources):return bool(sources) and has_user(sources) and all(is_user(s) or bool(s.evidence_bundle) for s in sources)

def image_original(name,raw):
    require(len(raw)<=10*1024*1024,'USER_IMAGE_BYTE_LIMIT: 每张用户图片不得超过10 MB。')
    try:
        with Image.open(BytesIO(raw)) as image:
            require(0<image.width*image.height<=32_000_000,'USER_IMAGE_PIXEL_LIMIT: 用户原图超过3200万像素，请缩小图片后再提交。')
            require(getattr(image,'n_frames',1)==1,'USER_IMAGE_MULTIFRAME_UNSUPPORTED: 当前仅支持单帧图片，请将动图或多帧图像导出为单张图片。')
            image.load()
            return dict(filename=str(name)[:512],sourceSha256=sha(raw),bytes=len(raw),width=image.width,height=image.height,
                originalFormat='JPG' if image.format=='JPEG' else str(image.format).upper(),exifOrientation=image.getexif().get(274,1))
    except Image.DecompressionBombError:
        raise ValueError('USER_IMAGE_PIXEL_LIMIT: 用户原图超过3200万像素，请缩小图片后再提交。') from None
    except ValueError as error:
        if str(error).startswith('USER_IMAGE_'):raise
        raise ValueError('USER_IMAGE_UNREADABLE: 当前附件无法解码为完整图片，请提供有效的单帧图片。') from None
    except (OSError,EOFError,SyntaxError):
        raise ValueError('USER_IMAGE_UNREADABLE: 当前附件无法解码为完整图片，请提供有效的单帧图片。') from None

def _register(kind,input_id,context,original,locator,question,trace,part=None):
    require(trace is not None,'USER_INPUT_AUDIT_REQUIRED')
    evidence=str(uuid.uuid5(uuid.NAMESPACE_URL,trace.id+':'+input_id+':'+original['sourceSha256']))
    representation=str(uuid.uuid5(uuid.NAMESPACE_URL,evidence+':'+sha(context.encode())+(':'+part['sha256'] if part else '')))
    identity=dict(kind=kind,traceId=trace.id,inputId=input_id,originalFormat=original['originalFormat'],sourceSha256=original['sourceSha256'])
    receipt=dict(version=VERSION,receiptId=str(uuid.uuid5(uuid.NAMESPACE_URL,evidence+':receipt')),identity=identity,
        evidenceId=evidence,representationId=representation,original=original,locator=locator,
        questionSha256=sha(question.encode()),contextTextSha256=sha(context.encode()))
    if part:
        transform=dict(version='pillow-rgb-area-1048576-1',sourceWidth=original['width'],sourceHeight=original['height'],
            outputWidth=part['width'],outputHeight=part['height'],sourceToPromptScale=[part['width']/original['width'],part['height']/original['height']],
            outputOrientation='raw-source-pixels',sourceExifOrientation=original['exifOrientation'])
        receipt['image']=dict(imageId=part['imageId'],promptImageSha256=part['sha256'],width=part['width'],height=part['height'],preprocessing=transform)
        part.update(userInputReceiptId=receipt['receiptId'],sourceImageSha256=original['sourceSha256'],preprocessing=transform)
    trace.event('userInputReceipt',receipt)
    return SourceItem(source_id=input_id,source_type=kind,evidence_id=evidence,representation_id=representation,
        original_format=original['originalFormat'],locator=locator,model_context=context,
        content_preview=('用户提供图片：'+original['filename'] if part else context),
        evidence_type='user_provided_material',provenance=dict(userInputVersion=VERSION,userInputReceipt=receipt,
            userInputReceiptSha256=digest(receipt),validatedUserQuestion=question,sourceSha256=original['sourceSha256'],formatDiversityContribution=False))

def text_source(input_id,text,question,trace):
    return _register('user_text',input_id,text,dict(sourceSha256=sha(text.encode()),bytes=len(text.encode()),originalFormat='TXT'),
        dict(inputId=input_id,characterStart=0,characterEnd=len(text),characterIndexUnit='unicode-codepoint'),question,trace)
def image_source(input_id,original,part,question,trace):
    return _register('user_image',input_id,IMAGE_CONTEXT,original,
        dict(inputId=input_id,filename=original['filename'],bbox=[0,0,original['width'],original['height']],coordinateSystem='user-source-image-pixels-top-left'),question,trace,part)

def verify(source,question,trace,call,image_parts=None):
    require(is_user(source),'USER_INPUT_VERSION_REQUIRED')
    receipt=source.provenance.get('userInputReceipt',{});checksum=digest(receipt)
    require(checksum==source.provenance.get('userInputReceiptSha256') and any(e['kind']=='userInputReceipt' and digest(e['value'])==checksum for e in trace.data['events']),'USER_INPUT_RECEIPT_NOT_RECORDED')
    identity=receipt['identity']
    require(identity['traceId']==trace.id and identity['inputId']==source.source_id and identity['kind']==source.source_type
        and identity['sourceSha256']==source.provenance.get('sourceSha256') and identity['originalFormat']==source.original_format,'USER_INPUT_IDENTITY_CHANGED')
    require(receipt['questionSha256']==sha(question.encode())==trace.data['questionSha256'] and source.provenance.get('validatedUserQuestion')==question,'USER_INPUT_QUESTION_CHANGED')
    require(receipt['evidenceId']==source.evidence_id and receipt['representationId']==source.representation_id and receipt['locator']==source.locator
        and receipt['contextTextSha256']==sha(source.model_context.encode()),'USER_INPUT_SOURCE_CHANGED')
    actual=[a for a in call['actualEvidence'] if a['sourceId']==source.source_id and a['evidenceId']==source.evidence_id]
    require(len(actual)==1 and actual[0]['contextMatchedActualRequest'] and actual[0]['contextTextSha256']==receipt['contextTextSha256']
        and actual[0]['sourceSha256']==identity['sourceSha256'] and actual[0]['representationId']==source.representation_id,'USER_INPUT_NOT_ACTUALLY_TRANSMITTED')
    link=dict(sourceId=source.source_id,evidenceId=source.evidence_id,representationId=source.representation_id,identity=identity,
        locator=source.locator,inputReceiptId=receipt['receiptId'],inputReceiptSha256=checksum,readIds=[],
        modelCallId=call['callId'],modelRequestBodySha256=call['requestBodySha256'],referenceRole='actual-user-input-only-not-semantic-verification')
    if source.source_type=='user_text':
        require(sha(source.model_context.encode())==identity['sourceSha256'] and source.locator['characterEnd']==len(source.model_context),'USER_TEXT_CONTENT_CHANGED')
    else:
        generation=[e['value'] for e in trace.data['events'] if e['kind']=='generationInputs'];require(len(generation)==1,'USER_IMAGE_INPUTS_AMBIGUOUS')
        images=[i for i in generation[0]['images'] if i['sourceId']==source.source_id];require(len(images)==1,'USER_IMAGE_MAPPING_AMBIGUOUS')
        image=images[0];expected=receipt['image'];wire=[p['imageSha256'] for m in call['request']['messages'] for p in m['content'] if p['type']=='image_url']
        require(image['imageId']==expected['imageId'] and image['sha256']==expected['promptImageSha256'] and image['sha256'] in wire
            and image['sourceImageSha256']==identity['sourceSha256'] and image['preprocessing']==expected['preprocessing']
            and [image['width'],image['height']]==[expected['width'],expected['height']] and 0<image['width']*image['height']<=1048576,'USER_IMAGE_CONTENT_OR_TRANSFORM_CHANGED')
        if image_parts is not None:
            payload=[i for i in image_parts if i['sourceId']==source.source_id];require(len(payload)==1,'USER_IMAGE_PAYLOAD_MISSING')
            require(sha(base64.b64decode(payload[0]['url'].split(',',1)[1],validate=True))==image['sha256'],'USER_IMAGE_PAYLOAD_CHANGED')
        link.update(imageId=image['imageId'],promptImageSha256=image['sha256'],sourceImageSha256=identity['sourceSha256'],preprocessing=image['preprocessing'])
    return link

def verify_context(sources,trace,call):
    from services.curated_qa import _source_text_content
    expected=_source_text_content(sources)
    require(any(p.get('textSha256')==sha(expected.encode()) and p.get('characters')==len(expected)
        for m in call['request']['messages'] if m['role']=='user' for p in m['content'] if p['type']=='text'),'USER_INPUT_SOURCE_ORDER_OR_CONTEXT_CHANGED')

def question_matches(source,question,mixed=False):
    if is_user(source):return source.provenance.get('validatedUserQuestion')==question
    keys=['validatedDocumentQuestion','validatedTextQuestion','validatedEngineeringQuestion','validatedRecordQuestion']
    declared=[source.provenance[k] for k in keys if k in source.provenance]
    return all(q==question for q in declared) if declared else bool(mixed and source.evidence_bundle)

def citation_allowed(source,question):
    from services.evidence_policy import format_intent
    allowed=format_intent(question).allowed
    if allowed and source.original_format not in allowed:return False
    restrictive=bool(re.search(r'只(?:依据|看|使用|用)|仅(?:依据|看|使用|用)',question))
    explicit_user=bool(re.search(r'用户|所附|附图|上传|补充资料|我提供|我给',question))
    names=re.findall(r'(?<![A-Za-z0-9_./-])([A-Za-z0-9_\u4e00-\u9fff][A-Za-z0-9_.\-\u4e00-\u9fff]*\.(?:pdf|jpg|jpeg|png|svg|obj|step|stp|txt|csv|xlsx|jsonl|json))(?![A-Za-z0-9_])',question,re.I)
    if restrictive and names and not explicit_user:
        filename=source.provenance['userInputReceipt']['original'].get('filename','').replace('\\','/').rsplit('/',1)[-1]
        return filename.casefold() in {n.casefold() for n in names}
    return True

def prune(answer,claims,sources):
    used=sorted({i for c in claims for i in c['sourceIndices']});require(used and all(1<=i<=len(sources) for i in used),'MIXED_FINAL_SOURCE_INDEX_INVALID')
    mapping={old:new for new,old in enumerate(used,1)}
    if used==list(range(1,len(sources)+1)):return answer,claims,sources
    require(not any(c.get('kind')=='source_quote' for c in claims),'MIXED_FINAL_QUOTE_REMAP_UNAVAILABLE')
    matches=list(CITATION.finditer(answer));require(all(int(m[1]) in mapping for m in matches),'MIXED_FINAL_UNSUPPORTED_CITATION')
    replace=lambda text:CITATION.sub(lambda m:'[来源'+str(mapping[int(m[1])])+']',text)
    def offset(position):
        require(all(not m.start()<position<m.end() for m in matches),'MIXED_FINAL_OFFSET_INSIDE_CITATION')
        return len(replace(answer[:position]))
    final=replace(answer);updated=[]
    for original in claims:
        require(answer[original['answerStart']:original['answerEnd']]==original['text'],'MIXED_FINAL_CLAIM_SPAN_CHANGED')
        claim=copy.deepcopy(original);claim['answerStart']=offset(original['answerStart']);claim['answerEnd']=offset(original['answerEnd']);claim['text']=final[claim['answerStart']:claim['answerEnd']]
        claim['sourceIndices']=[mapping[i] for i in original['sourceIndices']]
        if 'inputLinks' not in claim:
            # Deterministic aggregate claims use their complete native operand
            # graph rather than prose inputLinks. Keep that graph intact.
            require(claim.get('evidenceNature')=='program_calculation' and claim.get('verification')=='deterministic-renderer-exact-span','MIXED_FINAL_CLAIM_LINKS_MISSING')
            native=[sources[i-1] for i in original['sourceIndices']]
            require(all(s.calculation_lineage and s.provenance.get('verifiedCalculationPresentation') for s in native),'MIXED_FINAL_CALCULATION_GRAPH_MISSING')
            require(set(claim['evidenceIds'])=={s.evidence_id for s in native}
                and set(claim['readIds'])=={s.calculation_lineage['readId'] for s in native}
                and set(claim['factIds'])<={s.calculation_lineage[k] for s in native for k in ('resultFactId','scopeFactId')}
                and bool(claim['factIds'])
                and set(claim['inputFactIds'])=={f['factId'] for s in native for f in s.calculation_lineage['inputFacts']},'MIXED_FINAL_CALCULATION_GRAPH_CHANGED')
        for link in claim.get('inputLinks',[]):
            old=link['sourceIndex'];link.setdefault('modelSourceIndex',old);link['sourceIndex']=mapping[old]
        claim['parentClaimId']=original['claimId'];claim['claimId']=str(uuid.uuid5(uuid.NAMESPACE_URL,VERSION+':'+digest(claim)))
        updated.append(claim)
    kept=[s.model_copy(update={'provenance':{**s.provenance,'modelSourceIndex':s.provenance.get('modelSourceIndex',i),'finalSourceIndex':mapping[i]}}) for i,s in enumerate(sources,1) if i in mapping]
    return final,updated,kept
