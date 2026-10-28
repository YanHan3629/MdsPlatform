"""Exact property claims and explicitly unverified actual-image observations."""
import base64,hashlib,json,re,uuid
from services.answer_lineage import Catalogue,LineageError,canonical,checksum,require,render,verify_presentation,same
from services.read_receipt import digest
from services.user_input_evidence import is_user,has_user,verify as verify_user,verify_context,question_matches,citation_allowed

VERSION='engineering-answer-lineage-2026-10-25.1'
CITATION=re.compile(r'\[来源\s*(\d+)\]')
VISUAL_HEADER='图像观察（当前模型，语义尚未认证）：\n'

def _call(result,trace):
    require(trace is not None,'CLAIM_ENGINEERING_AUDIT_REQUIRED')
    calls=trace.data.get('modelCalls',[])
    require(len(calls)==1 and calls[0].get('status')=='response_received','CLAIM_ENGINEERING_MODEL_CALL_UNCONFIRMED')
    call=calls[0];response=call.get('response',{});choices=response.get('choices',[])
    require(response.get('id') and response['id']==result.get('id') and len(choices)==1,'CLAIM_ENGINEERING_MODEL_RESPONSE_CHANGED')
    require(choices[0].get('finish_reason')!='length','CLAIM_ENGINEERING_OUTPUT_LIMIT')
    require(choices[0].get('finish_reason')=='stop' and isinstance(choices[0].get('message',{}).get('content'),str)
        and choices[0]['message']['content'].strip(),'CLAIM_ENGINEERING_MODEL_RESPONSE_INCOMPLETE')
    return call

def _visual_links(sources,image_parts,trace,call,indices,question=None):
    events=[event['value'] for event in trace.data['events'] if event['kind']=='generationInputs']
    require(len(events)==1,'CLAIM_ENGINEERING_IMAGE_INPUTS_AMBIGUOUS')
    transmitted=events[0]['images'];wire=[part['imageSha256'] for message in call['request']['messages'] for part in message['content'] if part['type']=='image_url']
    require(wire==[image['sha256'] for image in transmitted] and len(wire)==len(image_parts),'CLAIM_ENGINEERING_IMAGE_TRANSMISSION_CHANGED')
    receipts={digest(event['value']):event['value'] for event in trace.data['events'] if event['kind']=='nativeReadReceipt'}
    links=[]
    for index in indices:
        source=sources[index-1];bundle=source.evidence_bundle;identity=bundle.get('identity',{}) if bundle else {}
        if is_user(source):
            from services.evidence_policy import format_intent
            allowed=format_intent(question).allowed
            require(not allowed or source.original_format in allowed,'CLAIM_ENGINEERING_EXPLICIT_FORMAT_CONFLICT')
            require(citation_allowed(source,question),'CLAIM_ENGINEERING_EXPLICIT_SOURCE_CONFLICT')
            links.append(dict(verify_user(source,question,trace,call,image_parts),sourceIndex=index));continue
        require(source.provenance.get('engineeringVisualRead') and not source.fact_ids,'CLAIM_ENGINEERING_VISUAL_SOURCE_REQUIRED')
        matches=[image for image in transmitted if image['sourceId']==source.source_id]
        require(len(matches)==1,'CLAIM_ENGINEERING_IMAGE_IDENTITY_AMBIGUOUS');image=matches[0]
        payloads=[part for part in image_parts if part['sourceId']==source.source_id]
        require(len(payloads)==1,'CLAIM_ENGINEERING_IMAGE_BYTES_UNAVAILABLE');payload=payloads[0]
        require(payload['url'].startswith('data:image/png;base64,') and hashlib.sha256(base64.b64decode(payload['url'].split(',',1)[1],validate=True)).hexdigest()==image['sha256'],
            'CLAIM_ENGINEERING_IMAGE_BYTES_CHANGED')
        require(all(payload.get(key)==image.get(key) for key in ('imageId','sha256','width','height')) and image['width']*image['height']<=1048576,
            'CLAIM_ENGINEERING_IMAGE_BUDGET_OR_MAPPING_CHANGED')
        metadata=image.get('renderedRegion',{});expected=source.provenance.get('renderedRegion') or source.provenance.get('registeredImage')
        require(expected and same(metadata,expected) and same(metadata.get('identity'),identity),'CLAIM_ENGINEERING_IMAGE_SOURCE_CHANGED')
        require(identity.get('sourceSha256')==source.provenance.get('sourceSha256') and identity.get('assetId')==str(source.asset_id)
            and identity.get('logicalPath')==source.logical_path and identity.get('indexVersionId')==source.index_version
            and identity.get('versionId')==source.data_version and identity.get('originalFormat')==source.original_format
            and identity.get('rawCommitId')==source.provenance.get('rawCommitId')
            and all(identity.get(key)==str(trace.data['snapshot'][name]) for key,name in (
                ('datasetId','dataset_id'),('versionId','version_id'),('indexVersionId','index_version_id'))),
            'CLAIM_ENGINEERING_VISUAL_SCOPE_CHANGED')
        require(bundle.get('evidenceId')==source.evidence_id and bundle.get('representationId')==source.representation_id
            and same(bundle.get('locator'),source.locator) and same(json.loads(source.model_context).get('locator'),source.locator),
            'CLAIM_ENGINEERING_VISUAL_LOCATOR_CHANGED')
        require(image.get('sourceImageSha256')==metadata.get('outputSha256') and metadata.get('readId'),'CLAIM_ENGINEERING_RENDER_IDENTITY_CHANGED')
        preprocessing=image.get('preprocessing',{})
        require(preprocessing.get('sourceWidth')==metadata.get('width') and preprocessing.get('sourceHeight')==metadata.get('height')
            and preprocessing.get('outputWidth')==image['width'] and preprocessing.get('outputHeight')==image['height'],
            'CLAIM_ENGINEERING_IMAGE_TRANSFORM_CHANGED')
        actual=[entry for entry in call.get('actualEvidence',[]) if entry['sourceId']==source.source_id and entry['evidenceId']==source.evidence_id]
        require(len(actual)==1 and actual[0].get('contextMatchedActualRequest')
            and actual[0].get('contextTextSha256')==hashlib.sha256(source.model_context.encode()).hexdigest()
            and actual[0].get('sourceSha256')==identity['sourceSha256'],'CLAIM_ENGINEERING_IMAGE_CONTEXT_NOT_TRANSMITTED')
        require(actual[0].get('representationId')==source.representation_id and same(actual[0].get('locator'),source.locator)
            and actual[0].get('readId')==source.provenance.get('nativeReadId')==bundle.get('readId')
            and actual[0].get('readOutputSha256')==source.provenance.get('readOutputSha256')==bundle.get('readOutputSha256'),
            'CLAIM_ENGINEERING_IMAGE_READ_BINDING_CHANGED')
        requested=source.provenance.get('requestedEngineeringRegion')
        if requested:
            require(all(same(metadata['parameters'].get(key),value) for key,value in requested.items())
                and same(source.locator.get('bbox'),requested['bbox']),'CLAIM_ENGINEERING_IMAGE_REGION_CHANGED')
        parents=bundle.get('clientReadParents',[])
        require(parents and sorted(digest(parent) for parent in parents)==sorted(actual[0].get('clientParentReadReceiptSha256',[])),
            'CLAIM_ENGINEERING_IMAGE_PARENT_RECEIPT_CHANGED')
        for parent in parents:
            require(digest(parent) in receipts and same(parent,receipts[digest(parent)])
                and parent['assetId']==identity['assetId']
                and all(parent['scope'][key]==identity[key] for key in ('datasetId','versionId','indexVersionId')),
                'CLAIM_ENGINEERING_IMAGE_PARENT_NOT_READ')
        links.append({'sourceIndex':index,'sourceId':source.source_id,'evidenceId':source.evidence_id,
            'representationId':source.representation_id,'readIds':sorted({metadata['readId'],*[parent['readId'] for parent in parents]}),
            'imageId':image['imageId'],'promptImageSha256':image['sha256'],'sourceImageSha256':image['sourceImageSha256'],
            'identity':identity,'locator':source.locator,'renderParameters':metadata['parameters'],'renderTransform':metadata['transform'],
            'preprocessing':preprocessing,'parentClientReadReceiptSha256':sorted(digest(parent) for parent in parents),
            'modelCallId':call['callId'],'modelRequestBodySha256':call['requestBodySha256']})
    return links

def finish(question,result,sources,image_parts,trace,output_check):
    """Display verified fields, or label visual prose with its actual image links."""
    call=_call(result,trace)
    mixed=has_user(sources)
    require(all((question_matches(source,question,True) if is_user(source) else source.provenance.get('validatedEngineeringQuestion')==question) for source in sources),'CLAIM_ENGINEERING_QUESTION_CHANGED')
    if mixed:
        verify_context(sources,trace,call)
        for source in sources:
            if is_user(source):verify_user(source,question,trace,call,image_parts)
    properties=[]
    for index,source in enumerate(sources,1):
        if source.provenance.get('engineeringReadVersion') and not source.provenance.get('engineeringVisualRead'):
            properties.append(source.model_copy(update={'provenance':{**source.provenance,'modelSourceIndex':index,
                'finalSourceIndex':len(properties)+1,'citationScopeCheck':'exact-actual-input-property-spans'}}))
    if properties:
        catalogue=Catalogue(properties)
        require(not catalogue.unsupported and catalogue.facts,'CLAIM_ENGINEERING_PROPERTY_ADAPTER_UNAVAILABLE')
        receipts=[event['value'] for event in trace.data['events'] if event['kind']=='nativeReadReceipt']
        catalogue.bind_calls(trace.data['modelCalls'],receipts)
        trace.event('engineeringFieldCatalogue',{'version':VERSION,'sources':catalogue.sources,'requiredFactIds':catalogue.required_fact_ids})
        presentation=render(catalogue,list(catalogue.facts));check=verify_presentation(presentation,catalogue)
        require(check['status']=='VERIFIED_EXACT_SPANS' and not presentation['missingRequiredFactIds'],'CLAIM_ENGINEERING_PROPERTY_PRESENTATION_INCOMPLETE')
        visual=bool(image_parts)
        presentation.update(generationStatus='partial' if visual else 'source_values_verified',
            notice='所列源声明或程序几何值及引用已核对；模型解释与完整问题的语义尚未认证。'+(' 图像已入模，本次未交付未认证的图像解释。' if visual else ''),
            finalClaimCheck=check,modelInterpretationVerified=False,withheldRawInterpretation=True,
            verificationScope='Actual native property values, units, source nature and exact final spans; not physical metrology, complete question semantics or raw model prose.')
        trace.event('engineeringFieldPresentation',presentation)
        return presentation
    require(image_parts and output_check.get('status')=='SCOPE_CHECKED','CLAIM_ENGINEERING_VISUAL_DRAFT_WITHHELD')
    raw=result['choices'][0]['message']['content'];indices=sorted({int(value) for value in CITATION.findall(raw)})
    require(indices and all(1<=index<=len(sources) for index in indices),'CLAIM_ENGINEERING_VISUAL_CITATION_CHANGED')
    links=_visual_links(sources,image_parts,trace,call,indices,question)
    mapping={old:new for new,old in enumerate(indices,1)}
    for link in links:
        link['modelSourceIndex']=link['sourceIndex'];link['sourceIndex']=mapping[link['sourceIndex']]
    text=CITATION.sub(lambda match:'[来源'+str(mapping[int(match[1])])+']',raw)
    answer=VISUAL_HEADER+text
    claim={'claimId':str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([VERSION,text,links]))),
        'text':text,'answerStart':len(VISUAL_HEADER),'answerEnd':len(answer),'offsetUnit':'unicode-code-points',
        'sourceIndices':list(range(1,len(indices)+1)),'evidenceNature':'model_interpretation',
        'verification':'actual-transmitted-image-binding-only-not-semantic-verification','kind':'visual_interpretation',
        'factIds':[],'evidenceIds':[source.evidence_id for index,source in enumerate(sources,1) if index in indices],
        'readIds':sorted({identifier for link in links for identifier in link['readIds']}),
        'imageIds':[link['imageId'] for link in links],'inputLinks':links}
    final_sources=[source.model_copy(update={'provenance':{**source.provenance,'modelSourceIndex':index,'finalSourceIndex':mapping[index],
        'citationScopeCheck':'actual-image-input-binding-only'}}).model_dump(mode='json') for index,source in enumerate(sources,1) if index in indices]
    presentation={'version':VERSION,'answer':answer,'claims':[claim],'sourceIndices':indices,'sources':final_sources,
        'generationStatus':'source_interpretation_unverified','modelInterpretationVerified':False,
        'notice':'图像和来源映射已核对；以下为当前模型的图像解释，语义、形状及数量判断尚未认证。',
        'verificationScope':'Actual image bytes, source/region/instance mapping and model-call linkage only; no semantic correctness claim.'}
    trace.event('engineeringVisualPresentation',presentation)
    return presentation
