"""Read/model bindings for cited prose; these do not certify model semantics."""
import base64,copy,hashlib,json,re,uuid
from services.answer_lineage import Catalogue,IDENTITY,require,same,canonical
from services.read_receipt import verify,digest
from services.user_input_evidence import is_user,has_user,verify as verify_user,verify_context,question_matches,citation_allowed

VERSION='prose-actual-input-lineage-2026-10-26.1'
EXCERPT_VERSION='document-native-excerpt-lineage-2026-10-27.1'
CITATION=re.compile(r'\[来源\s*(\d+)\]')
HEADER='模型解释（语义尚未认证）：\n'

def _call(result,trace,allow_truncated=False):
    require(trace is not None,'CLAIM_PROSE_AUDIT_REQUIRED')
    calls=trace.data.get('modelCalls',[])
    require(len(calls)==1 and calls[0].get('status')=='response_received','CLAIM_PROSE_MODEL_CALL_UNCONFIRMED')
    call=calls[0];response=call.get('response',{});choices=response.get('choices',[])
    require(response.get('id')==result.get('id') and response.get('id') and len(choices)==1,'CLAIM_PROSE_RESPONSE_CHANGED')
    require(same(choices,result.get('choices')),'CLAIM_PROSE_RESPONSE_BODY_CHANGED')
    require(choices[0].get('finish_reason') in (('stop','length') if allow_truncated else ('stop',)), 'CLAIM_PROSE_OUTPUT_INCOMPLETE')
    require(isinstance(choices[0].get('message',{}).get('content'),str) and choices[0]['message']['content'].strip(),'CLAIM_PROSE_RESPONSE_EMPTY')
    return call

def _identity(source,trace):
    bundle=source.evidence_bundle or {};identity=bundle.get('identity',{});provenance=source.provenance
    require(all(identity.get(k) for k in IDENTITY),'CLAIM_PROSE_IDENTITY_INCOMPLETE')
    require(all(identity[k]==str(trace.data['snapshot'][name]) for k,name in [('datasetId','dataset_id'),('versionId','version_id'),('indexVersionId','index_version_id')]),'CLAIM_PROSE_SNAPSHOT_CHANGED')
    require(identity['assetId']==str(source.asset_id) and identity['logicalPath']==source.logical_path and identity['originalFormat']==source.original_format
        and identity['sourceSha256']==provenance.get('sourceSha256') and identity['rawCommitId']==provenance.get('rawCommitId')
        and identity['versionId']==source.data_version and identity['indexVersionId']==source.index_version,'CLAIM_PROSE_SOURCE_CHANGED')
    require(bundle.get('evidenceId')==source.evidence_id and bundle.get('representationId')==source.representation_id and same(bundle.get('locator'),source.locator),'CLAIM_PROSE_LOCATOR_CHANGED')
    require(bundle.get('readId')==provenance.get('nativeReadId') and bundle.get('readOutputSha256')==provenance.get('readOutputSha256'),'CLAIM_PROSE_READ_CHANGED')
    return identity

def _actual(source,call):
    matches=[x for x in call.get('actualEvidence',[]) if x.get('sourceId')==source.source_id and x.get('evidenceId')==source.evidence_id]
    require(len(matches)==1,'CLAIM_PROSE_INPUT_AMBIGUOUS');actual=matches[0];bundle=source.evidence_bundle
    require(actual.get('contextMatchedActualRequest') and actual.get('contextTextSha256')==hashlib.sha256(source.model_context.encode()).hexdigest()
        and actual.get('sourceSha256')==source.provenance['sourceSha256'] and actual.get('representationId')==source.representation_id
        and actual.get('readId')==bundle['readId'] and actual.get('readOutputSha256')==bundle['readOutputSha256']
        and same(actual.get('locator'),source.locator),'CLAIM_PROSE_NOT_ACTUALLY_TRANSMITTED')
    require(set(actual.get('factIds',[]))==set(source.fact_ids),'CLAIM_PROSE_FACT_SELECTION_CHANGED')
    if source.fact_ids:require(actual.get('factIdsPresentInActualRequest'),'CLAIM_PROSE_FACT_IDS_NOT_TRANSMITTED')
    return actual

def _native_link(source,trace,call):
    identity=_identity(source,trace);actual=_actual(source,call);receipt=verify(source.evidence_bundle)
    observed={digest(e['value']) for e in trace.data['events'] if e['kind']=='nativeReadReceipt'}
    require(digest(receipt) in observed and digest(receipt)==actual.get('clientReadReceiptSha256')
        and receipt['decodedBodySha256']==actual.get('clientDecodedBodySha256'),'CLAIM_PROSE_NATIVE_RECEIPT_NOT_IN_TRACE')
    return dict(sourceId=source.source_id,evidenceId=source.evidence_id,representationId=source.representation_id,
        identity=identity,locator=source.locator,readIds=[receipt['readId']],readOutputSha256=receipt['readOutputSha256'],
        clientReadReceiptSha256=digest(receipt),modelCallId=call['callId'],modelRequestBodySha256=call['requestBodySha256'])

def _text_links(question,sources,indices,trace,call,mixed=False):
    text_sources=[sources[i-1] for i in indices if sources[i-1].provenance.get('textReadVersion')]
    if text_sources:
        from services.text_evidence_plan import validate_group
        # The complete selected cohort establishes record coverage, including
        # read paragraphs the model did not subsequently cite.
        cohort=[s for s in sources if s.provenance.get('textReadVersion')]
        items=[dict(assetId=str(s.asset_id),dataVersion=s.data_version,indexVersion=s.index_version,
            nativeBundle=s.evidence_bundle,nativeModelContext=s.model_context,nativeHumanContext=s.content_preview,provenance=s.provenance) for s in cohort]
        validate_group(question,items)
    links=[]
    for i in indices:
        source=sources[i-1]
        if is_user(source):continue
        if not source.fact_ids:continue
        link=_native_link(source,trace,call)
        if source.provenance.get('textReadVersion'):
            facts=source.evidence_bundle['facts'];require(len(facts)==1 and source.fact_ids==[facts[0]['factId']],'CLAIM_PROSE_TXT_FACT_CHANGED')
            link['factIds']=list(source.fact_ids);link['factLocators']={f['factId']:f['locator'] for f in facts}
        elif mixed:
            facts={f['factId']:f for f in source.evidence_bundle.get('facts',[])}
            require(set(source.fact_ids)<=set(facts),'CLAIM_PROSE_MIXED_FACT_NOT_READ')
            link['factIds']=list(source.fact_ids);link['factLocators']={k:facts[k]['locator'] for k in source.fact_ids}
            link['factNatures']={k:facts[k]['evidenceNature'] for k in source.fact_ids}
        else:
            catalogue=Catalogue([source]);require(not catalogue.unsupported,'CLAIM_PROSE_TEXT_ADAPTER_UNAVAILABLE')
            catalogue.bind_calls([call],[e['value'] for e in trace.data['events'] if e['kind']=='nativeReadReceipt'])
            require(set(catalogue.facts)==set(source.fact_ids) and set(catalogue.proofs)==set(source.fact_ids),'CLAIM_PROSE_TEXT_FACTS_NOT_TRANSMITTED')
            link['factIds']=list(source.fact_ids);link['factLocators']={k:v['locator'] for k,v in catalogue.facts.items()}
            link['factNatures']={k:v['evidenceNature'] for k,v in catalogue.facts.items()}
        link['sourceIndex']=i;link['referenceRole']='actual-read-input-reference-not-semantic-entailment';links.append(link)
    return links

def _image_links(sources,indices,image_parts,trace,call):
    generations=[e['value'] for e in trace.data['events'] if e['kind']=='generationInputs'];require(len(generations)==1,'CLAIM_PROSE_IMAGE_INPUTS_AMBIGUOUS')
    images=generations[0]['images'];wire=[p['imageSha256'] for m in call['request']['messages'] for p in m['content'] if p['type']=='image_url']
    require(wire==[x['sha256'] for x in images] and len(wire)==len(image_parts),'CLAIM_PROSE_IMAGE_ORDER_CHANGED')
    receipts={digest(e['value']):e['value'] for e in trace.data['events'] if e['kind']=='nativeReadReceipt'};links=[]
    for i in indices:
        source=sources[i-1];matching=[x for x in images if x['sourceId']==source.source_id]
        if is_user(source):continue
        if not matching:
            require(not source.provenance.get('documentVisualRead'),'CLAIM_PROSE_VISUAL_IMAGE_MISSING');continue
        require(len(matching)==1,'CLAIM_PROSE_IMAGE_SOURCE_AMBIGUOUS');image=matching[0]
        identity=_identity(source,trace);actual=_actual(source,call);payloads=[x for x in image_parts if x['sourceId']==source.source_id]
        require(len(payloads)==1,'CLAIM_PROSE_IMAGE_BYTES_MISSING');payload=payloads[0]
        require(payload['url'].startswith('data:image/png;base64,') and hashlib.sha256(base64.b64decode(payload['url'].split(',',1)[1],validate=True)).hexdigest()==image['sha256'],'CLAIM_PROSE_IMAGE_BYTES_CHANGED')
        require(all(payload.get(k)==image.get(k) for k in ['imageId','sha256','width','height']) and image['width']*image['height']<=1048576,'CLAIM_PROSE_IMAGE_MAPPING_CHANGED')
        rendered=image.get('renderedRegion');require(rendered and same(rendered,source.provenance.get('renderedRegion')) and same(rendered['identity'],identity),'CLAIM_PROSE_RENDER_SOURCE_CHANGED')
        require(image.get('sourceImageSha256')==rendered['outputSha256'] and rendered.get('readId'),'CLAIM_PROSE_RENDER_HASH_CHANGED')
        events=[e['value'] for e in trace.data['events'] if e['kind']=='regionRender' and e['value'].get('readId')==rendered['readId']]
        require(len(events)==1 and all(same(events[0].get(k),rendered.get(k)) for k in ['identity','parameters','transform','outputSha256','width','height']),'CLAIM_PROSE_RENDER_NOT_READ')
        preprocessing=image.get('preprocessing',{})
        require(preprocessing.get('sourceWidth')==rendered['width'] and preprocessing.get('sourceHeight')==rendered['height']
            and preprocessing.get('outputWidth')==image['width'] and preprocessing.get('outputHeight')==image['height'],'CLAIM_PROSE_PREPROCESSING_CHANGED')
        bundle=source.evidence_bundle;parents=([verify(bundle)] if bundle.get('clientReadReceipt') else [])+bundle.get('clientReadParents',[])
        require(parents and all(digest(p) in receipts and p['assetId']==identity['assetId'] and all(p['scope'][k]==identity[k] for k in ['datasetId','versionId','indexVersionId']) for p in parents),'CLAIM_PROSE_IMAGE_NATIVE_PARENT_MISSING')
        require(sorted(digest(p) for p in bundle.get('clientReadParents',[]))==sorted(actual.get('clientParentReadReceiptSha256',[])),'CLAIM_PROSE_IMAGE_PARENT_CHANGED')
        links.append(dict(sourceIndex=i,sourceId=source.source_id,evidenceId=source.evidence_id,representationId=source.representation_id,
            identity=identity,locator=source.locator,imageId=image['imageId'],promptImageSha256=image['sha256'],sourceImageSha256=image['sourceImageSha256'],
            renderParameters=rendered['parameters'],renderTransform=rendered['transform'],preprocessing=preprocessing,
            readIds=sorted({rendered['readId'],*[p['readId'] for p in parents]}),parentClientReadReceiptSha256=[digest(p) for p in parents],
            modelCallId=call['callId'],modelRequestBodySha256=call['requestBodySha256'],referenceRole='actual-image-input-reference-not-semantic-entailment'))
    return links

def finish(question,answer,result,sources,image_parts,trace):
    """Attach explicit model citations; omit uncited sources and keep semantics unverified."""
    call=_call(result,trace)
    mixed=has_user(sources)
    require(sources and all(question_matches(s,question,mixed) for s in sources),'CLAIM_PROSE_QUESTION_CHANGED')
    if mixed:
        verify_context(sources,trace,call)
        for source in sources:
            if is_user(source):verify_user(source,question,trace,call,image_parts)
    raw=result['choices'][0]['message']['content']
    if answer!=raw:
        checked=[e['value'] for e in trace.data['events'] if e['kind']=='textOutputCheck']
        require(len(checked)==1 and checked[0].get('status')!='REJECTED' and checked[0].get('normalizedAnswer')==answer,'CLAIM_PROSE_PRESENTATION_CHANGED_WITHOUT_CHECK')
    labels=re.findall(r'\[来源[^\]\n]*\]',answer);require(labels and all(CITATION.fullmatch(x) for x in labels),'CLAIM_PROSE_CITATION_MISSING_OR_INVALID')
    indices=sorted({int(x) for x in CITATION.findall(answer)});require(all(1<=i<=len(sources) for i in indices),'CLAIM_PROSE_CITATION_OUT_OF_RANGE')
    if mixed:
        from services.evidence_policy import format_intent
        allowed=format_intent(question).allowed
        require(not allowed or all(sources[i-1].original_format in allowed for i in indices),'CLAIM_PROSE_EXPLICIT_FORMAT_CONFLICT')
        require(all(not is_user(sources[i-1]) or citation_allowed(sources[i-1],question) for i in indices),'CLAIM_PROSE_EXPLICIT_SOURCE_CONFLICT')
    links=_text_links(question,sources,indices,trace,call,mixed)+_image_links(sources,indices,image_parts,trace,call)
    for i in indices:
        if is_user(sources[i-1]):links.append(dict(verify_user(sources[i-1],question,trace,call,image_parts),sourceIndex=i))
    require(set(indices)=={x['sourceIndex'] for x in links},'CLAIM_PROSE_CITED_SOURCE_HAS_NO_ACTUAL_INPUT')
    mapping={old:new for new,old in enumerate(indices,1)}
    for link in links:link['modelSourceIndex']=link['sourceIndex'];link['sourceIndex']=mapping[link['sourceIndex']]
    text=CITATION.sub(lambda m:'[来源'+str(mapping[int(m[1])])+']',answer);final=HEADER+text
    claim=dict(claimId=str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([VERSION,text,links]))),kind='source_interpretation',text=text,answerStart=len(HEADER),answerEnd=len(final),offsetUnit='unicode-code-points',
        evidenceNature='model_interpretation',verification='actual-input-binding-only-not-semantic-verification',sourceIndices=list(range(1,len(indices)+1)),
        evidenceIds=[sources[i-1].evidence_id for i in indices],factIds=sorted({f for x in links for f in x.get('factIds',[])}),
        imageIds=[x['imageId'] for x in links if x.get('imageId')],readIds=sorted({v for x in links for v in x['readIds']}),inputLinks=links)
    final_sources=[s.model_copy(update={'provenance':{**s.provenance,'modelSourceIndex':i,'finalSourceIndex':mapping[i],'citationScopeCheck':'actual-input-binding-only'}}).model_dump(mode='json') for i,s in enumerate(sources,1) if i in indices]
    presentation=dict(version=VERSION,answer=final,claims=[claim],sources=final_sources,generationStatus='source_interpretation_unverified',modelInterpretationVerified=False,
        notice='引用已关联实际读取和入模的文字或图片；模型解释的事实语义尚未认证。',verificationScope='Native receipts, exact actual contexts/images, declared citations and final offsets only; no semantic correctness or independent corroboration claim.')
    trace.event('prosePresentation',presentation);return presentation

def finish_excerpts(question,answer,result,sources,claims,trace):
    """Bind rejected-output fallback quotes without repairing model semantics."""
    call=_call(result,trace,allow_truncated=True)
    require(claims and sources,'CLAIM_QUOTE_INPUT_REQUIRED')
    checks=[e['value'] for e in trace.data['events'] if e['kind']=='documentOutputCheck']
    require(len(checks)==1 and checks[0].get('status')=='REJECTED','CLAIM_QUOTE_REJECTION_NOT_IN_TRACE')
    from services.document_output_check import source_excerpts
    expected_answer,expected_claims=source_excerpts(sources,checks[0])
    require(answer==expected_answer and same(claims,expected_claims),'CLAIM_QUOTE_RENDER_CHANGED')
    mixed=has_user(sources)
    require(all(question_matches(s,question,mixed) for s in sources),'CLAIM_QUOTE_QUESTION_CHANGED')
    if mixed:
        verify_context(sources,trace,call)
        for source in sources:
            if is_user(source):verify_user(source,question,trace,call)
    selected=sorted({i for claim in claims for i in claim.get('sourceIndices',[])})
    require(selected and all(type(i) is int and 1<=i<=len(sources) for i in selected),'CLAIM_QUOTE_SOURCE_INVALID')
    links=_text_links(question,sources,selected,trace,call,mixed)
    by_source={link['sourceIndex']:link for link in links}
    require(set(by_source)==set(selected),'CLAIM_QUOTE_ACTUAL_INPUT_MISSING')
    mapping={old:new for new,old in enumerate(selected,1)}
    final='';cursor=0;bound=[]
    for original in claims:
        claim=copy.deepcopy(original);start=claim['answerStart'];end=claim['answerEnd']
        require(claim.get('offsetUnit')=='unicode-code-points' and type(start) is int and type(end) is int
            and cursor<=start<end<=len(answer) and answer[start:end]==claim['text'],'CLAIM_QUOTE_SPAN_CHANGED')
        require(len(claim['sourceIndices'])==1 and len(claim.get('factIds',[]))==1,'CLAIM_QUOTE_FACT_AMBIGUOUS')
        index=claim['sourceIndices'][0];source=sources[index-1];bundle=source.evidence_bundle
        matches=[f for f in bundle['facts'] if f['factId']==claim['factIds'][0] and f['factId'] in source.fact_ids]
        require(len(matches)==1,'CLAIM_QUOTE_FACT_NOT_READ');fact=matches[0]
        require(fact.get('evidenceNature') in {'source_text','source_declaration','ocr_text'} and fact.get('value')==claim['text']
            and claim['evidenceNature']==fact['evidenceNature'] and same(claim['locator'],fact['locator']),'CLAIM_QUOTE_VALUE_OR_LOCATOR_CHANGED')
        require(claim['quotedValueSha256']==hashlib.sha256(claim['text'].encode()).hexdigest()
            and claim['sourceSha256']==bundle['identity']['sourceSha256'] and claim['readOutputSha256']==bundle['readOutputSha256']
            and claim['readIds']==[bundle['readId']] and claim['evidenceIds']==[source.evidence_id],'CLAIM_QUOTE_READ_IDENTITY_CHANGED')
        expected=('exact-ocr-observation-quote-not-source-value-verification' if fact['evidenceNature']=='ocr_text'
            else 'exact-source-quote-not-semantic-endorsement')
        require(claim.get('verification')==expected,'CLAIM_QUOTE_NATURE_CHANGED')
        # Remap generated citation markers only outside exact quoted spans.
        # A source's own bracket labels remain literal, unmodified source text.
        gap=answer[cursor:start]
        final+=CITATION.sub(lambda m:'[来源'+str(mapping[int(m[1])])+']' if int(m[1]) in mapping else m[0],gap)
        claim['answerStart']=len(final);final+=claim['text'];claim['answerEnd']=len(final)
        link=copy.deepcopy(by_source[index]);link.update(sourceIndex=mapping[index],modelSourceIndex=index,
            factIds=[fact['factId']],factLocators={fact['factId']:fact['locator']},referenceRole='exact-actual-input-quote-not-model-interpretation')
        claim.update(kind='source_quote',sourceIndices=[mapping[index]],inputLinks=[link]);bound.append(claim);cursor=end
    final+=CITATION.sub(lambda m:'[来源'+str(mapping[int(m[1])])+']' if int(m[1]) in mapping else m[0],answer[cursor:])
    final_sources=[s.model_copy(update={'provenance':{**s.provenance,'modelSourceIndex':i,'finalSourceIndex':mapping[i],
        'citationScopeCheck':'exact-actual-input-quote-only'}}).model_dump(mode='json') for i,s in enumerate(sources,1) if i in mapping]
    presentation=dict(version=EXCERPT_VERSION,answer=final,claims=bound,sources=final_sources,generationStatus='validation_failed',
        modelInterpretationVerified=False,verificationScope='Exact native quotes, actual-read receipts, transmitted contexts and final spans only. Raw model rejection remains in force; no complete-question acceptance.')
    trace.event('documentExcerptPresentation',presentation);return presentation


def finish_duration(question,answer,result,sources,trace):
    """Recompute an exact native-text duration and bind its input read and call."""
    from services.document_time import VERSION as TIME_VERSION,calculate_duration,render_duration
    require(len(sources)==1 and sources[0].provenance.get('documentTimeVersion')==TIME_VERSION,'CLAIM_PROSE_DURATION_SCOPE_CHANGED')
    call=_call(result,trace,allow_truncated=True);source=sources[0];identity=_identity(source,trace);actual=_actual(source,call)
    require(source.provenance.get('validatedDocumentQuestion')==question,'CLAIM_PROSE_DURATION_QUESTION_CHANGED')
    parent=source.provenance.get('documentTimeInputBundle');require(isinstance(parent,dict),'CLAIM_PROSE_DURATION_PARENT_BODY_REQUIRED')
    receipt=verify(parent);observed={digest(e['value']) for e in trace.data['events'] if e['kind']=='nativeReadReceipt'}
    require(digest(receipt) in observed and same(parent['identity'],identity),'CLAIM_PROSE_DURATION_PARENT_NOT_READ')
    expected=calculate_duration(question,[{'nativeBundle':parent}]);require(expected is not None,'CLAIM_PROSE_DURATION_NOT_RECOMPUTABLE')
    bundle=source.evidence_bundle;calculated=expected['bundle']
    for key in ['identity','locator','facts','content','transforms','evidenceId','representationId','representationKind','recipeVersion','evidenceNature','parentEvidenceIds','clientDerivation','clientDerivationChain','clientReadParents']:
        require(same(bundle.get(key),calculated.get(key)),'CLAIM_PROSE_DURATION_RECIPE_OR_INPUT_CHANGED')
    require(same(source.provenance.get('calculationInputs'),expected['inputs']),'CLAIM_PROSE_DURATION_INPUTS_CHANGED')
    require(source.fact_ids==[f['factId'] for f in bundle['facts']] and sorted(actual.get('clientParentReadReceiptSha256',[]))==sorted(digest(p) for p in bundle['clientReadParents']), 'CLAIM_PROSE_DURATION_FACTS_OR_PARENT_CHANGED')
    context=json.dumps({'evidenceId':bundle['evidenceId'],'facts':bundle['facts'],'calculation':bundle['content']['calculation']},ensure_ascii=False,separators=(',',':'))
    require(source.model_context==context and answer==render_duration(sources),'CLAIM_PROSE_DURATION_FINAL_VALUE_CHANGED')
    calculation=bundle['content']['calculation'];fact=next(f for f in bundle['facts'] if f['field']=='elapsed_time')
    link=dict(sourceIndex=1,sourceId=source.source_id,evidenceId=source.evidence_id,representationId=source.representation_id,identity=identity,locator=source.locator,
        factIds=list(source.fact_ids),readIds=[receipt['readId'],bundle['readId']],inputFactIds=calculation['inputFactIds'],
        inputClientReadReceiptSha256=digest(receipt),inputReadOutputSha256=parent['readOutputSha256'],
        modelCallId=call['callId'],modelRequestBodySha256=call['requestBodySha256'])
    if 'modelSourceIndex' in source.provenance:link['modelSourceIndex']=source.provenance['modelSourceIndex']
    claim=dict(claimId=str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([VERSION,answer,link]))),kind='registered_operation',
        text=answer,answerStart=0,answerEnd=len(answer),offsetUnit='unicode-code-points',evidenceNature='program_calculation',
        verification='independently-recomputed-native-time-difference-exact-span',sourceIndices=[1],evidenceIds=[source.evidence_id],factIds=[fact['factId']],
        readIds=link['readIds'],inputLinks=[link],operation={'name':'elapsed_time','recipeVersion':TIME_VERSION,**calculation})
    final_sources=[source.model_copy(update={'provenance':{**source.provenance,'modelSourceIndex':source.provenance.get('modelSourceIndex',1),'finalSourceIndex':1,'citationScopeCheck':'exact-recomputed-time-difference'}}).model_dump(mode='json')]
    partial=result['choices'][0]['finish_reason']=='length'
    presentation=dict(version=VERSION,answer=answer,claims=[claim],sources=final_sources,generationStatus='partial' if partial else 'source_values_verified',
        modelInterpretationVerified=False,notice='时间差按已读原文的两个时区时间独立重算；模型解释的完整语义尚未认证。'+(' 模型原始输出达到长度上限。' if partial else ''),
        verificationScope='Exact source timestamps, explicit UTC offsets, native parent receipt, deterministic formula, actual model input and final span; no raw-model semantic certification.')
    trace.event('documentDurationPresentation',presentation);return presentation
