"""Read/model bindings for cited prose; these do not certify model semantics."""
import base64,copy,hashlib,json,re,uuid
from core.request_budget import max_image_pixels
from services.answer_lineage import Catalogue,IDENTITY,require,same,canonical
from services.read_receipt import verify,digest
from services.evidence_trace import source_frame
from services.user_input_evidence import is_user,has_user,verify as verify_user,verify_context,question_matches,citation_allowed

VERSION='prose-actual-input-lineage-2026-11-07.2'
CATALOGUE_DISPLAY_VERSION='validated-catalogue-source-text-display-2026-11-08.1'
FINAL_DISPLAY_VERSION='prose-display-citations-and-exact-spans-2026-11-07.1'
EXCERPT_VERSION='document-native-excerpt-lineage-2026-10-27.1'
CITATION=re.compile(r'\[来源\s*(\d+)\]')
PARENTHESIZED_SOURCE=re.compile(r'([（(]\s*来源\s*)([1-9][0-9]*)(\s*[）)])')
HEADER='模型解释（语义尚未认证）：\n'


def catalogue_display(answer,sources,indices):
    """Format only exact, already bound internal catalogues echoed by the model.

    Keep unrelated JSON intact and reject a changed copy of a bound catalogue.
    The actual model response and request remain available in the trace.
    """
    known={}
    bound_ids=set()
    for index in indices:
        source=sources[index-1]
        if is_user(source):continue
        try:value=json.loads(source.model_context)
        except (TypeError,ValueError,RecursionError):continue
        if not isinstance(value,dict) or set(value)!={'evidenceId','facts'} or value['evidenceId']!=source.evidence_id:continue
        facts=value['facts']
        if not isinstance(facts,list) or not facts or any(not isinstance(f,dict) or set(f)!={'factId','text','nature'}
            or not isinstance(f['text'],str) or not f['text'].strip() or f['factId'] not in source.fact_ids for f in facts):continue
        known.setdefault(canonical(value),[]).append((index,source,value))
        bound_ids.add(source.evidence_id)
    decoder=json.JSONDecoder();cursor=0;search=0;parts=[];edits=[]
    while search<len(answer):
        start=answer.find('{',search)
        if start<0:break
        try:value,length=decoder.raw_decode(answer[start:])
        except (ValueError,RecursionError):search=start+1;continue
        end=start+length;search=end;matches=known.get(canonical(value),[])
        if len(matches)!=1:
            if isinstance(value,dict) and 'facts' in value and isinstance(value.get('evidenceId'),str):
                require(value['evidenceId'] not in bound_ids,'CLAIM_PROSE_BOUND_CATALOGUE_CHANGED')
            continue
        index,source,catalogue=matches[0];replacement='\n'.join(f['text'] for f in catalogue['facts'])
        parts.extend([answer[cursor:start],replacement]);cursor=end
        edits.append(dict(recipeVersion=CATALOGUE_DISPLAY_VERSION,operation='display-validated-catalogue-source-text',
            inputSourceIndex=index,evidenceId=source.evidence_id,factIds=[f['factId'] for f in catalogue['facts']],
            inputStart=start,inputEnd=end,offsetUnit='unicode-code-points',
            inputSha256=hashlib.sha256(answer[start:end].encode()).hexdigest(),outputSha256=hashlib.sha256(replacement.encode()).hexdigest()))
    parts.append(answer[cursor:]);return ''.join(parts),edits

def _call(result,trace,allow_truncated=False, *, revision_sources=None):
    require(trace is not None,'CLAIM_PROSE_AUDIT_REQUIRED')
    calls=trace.data.get('modelCalls',[])
    if revision_sources is not None and len(calls) in (2, 3):
        verify_numeric_revision(trace, calls, revision_sources)
    else:
        from services.business_answer_guard import IMAGE_USE_STATE_REVIEW_SCHEMA
        require(len(calls)==1 and not calls[0].get('purpose'),'CLAIM_PROSE_MODEL_CALL_UNCONFIRMED')
        require(calls[0].get('request',{}).get('structured_outputs',{}).get('json',{}).get('title') != IMAGE_USE_STATE_REVIEW_SCHEMA,
                'CLAIM_IMAGE_USE_STATE_REVIEW_REQUIRED')
    return _response_call(result,calls[-1],allow_truncated)


def _response_call(result,call,allow_truncated=False):
    require(call.get('status')=='response_received','CLAIM_PROSE_MODEL_CALL_UNCONFIRMED')
    response=call.get('response',{});choices=response.get('choices',[])
    require(response.get('id')==result.get('id') and response.get('id') and len(choices)==1,'CLAIM_PROSE_RESPONSE_CHANGED')
    require(same(choices,result.get('choices')),'CLAIM_PROSE_RESPONSE_BODY_CHANGED')
    require(choices[0].get('finish_reason') in (('stop','length') if allow_truncated else ('stop',)), 'CLAIM_PROSE_OUTPUT_INCOMPLETE')
    require(isinstance(choices[0].get('message',{}).get('content'),str) and choices[0]['message']['content'].strip(),'CLAIM_PROSE_RESPONSE_EMPTY')
    return call


def verify_numeric_revision(trace, calls, sources):
    if len(calls) == 2:
        return _verify_numeric_revision_pair(trace, calls, sources)
    require(len(calls) == 3 and sources and all(is_user(source) and source.source_type == 'user_image' for source in sources),
            'CLAIM_PROSE_NUMERIC_REVISION_UNCONFIRMED')
    selected = [event['value'] for event in trace.data['events'] if event['kind'] == 'numericRevisionSelected']
    initial = [event['value'] for event in trace.data['events'] if event['kind'] == 'numericRevisionInitialCheck']
    recipes = {'IMAGE_COMPLETENESS_UNVERIFIED': 'image-scope-rewrite-then-neutral-quantity-1',
               'BUSINESS_IMAGE_USE_INVALID_REFERENCE': 'image-reference-rewrite-then-neutral-quantity-1',
               'IMAGE_USE_STATE_REVIEW_REQUIRED':'image-use-state-review-then-neutral-quantity-1'}
    require(len(initial) == 1 and initial[0].get('reason') in recipes,
            'CLAIM_PROSE_NUMERIC_REVISION_VALIDATION_CHANGED')
    require(len(selected) == 1 and selected[0].get('chainRecipe') == recipes[initial[0]['reason']],
            'CLAIM_PROSE_NUMERIC_REVISION_TRACE_INCOMPLETE')
    require(len({call['response'].get('id') for call in calls}) == 3
            and selected[0]['selectedCallId'] == calls[2]['callId'],
            'CLAIM_PROSE_NUMERIC_REVISION_RESPONSE_CHANGED')
    _verify_numeric_revision_pair(trace, calls[:2], sources, selected_end_key='intermediateCallId', expected_accepted=False)
    replacement = _verify_numeric_revision_pair(trace, calls[1:], sources, initial_purpose='numeric_revision',
        event_kinds=('numericRevisionQuantitySelected', 'numericRevisionQuantityInitialCheck', 'numericRevisionQuantityCheck'))
    require(replacement is not None, 'CLAIM_PROSE_NUMERIC_REVISION_REQUEST_CHANGED')
    return replacement


def _verify_numeric_revision_pair(trace, calls, sources, *, selected_end_key='selectedCallId',
                                  expected_accepted=True, initial_purpose=None,
                                  event_kinds=('numericRevisionSelected', 'numericRevisionInitialCheck', 'numericRevisionCheck')):
    from services.business_answer_guard import (
        IMAGE_LABEL_FRAGMENT_DRAFT_GUIDANCE, REVISION_DRAFT_GUIDANCE, REVISION_REASONS, check_numeric_answer, cited_block_schema, cited_section_count,
        image_label_quantity_schema, image_quantity_edit_plan, image_quantity_replacement_keys, image_quantity_replacement_schema, image_use_completion_schema, image_use_state_review_schema, image_use_review_initial_check, legacy_image_digit_pattern,
        merge_image_quantity_revision,
        numeric_revision_draft, numeric_revision_instruction, qualitative_image_revision,
    )
    from services.user_input_evidence import image_label_selection

    initial, revised = calls
    require(initial.get('status') == revised.get('status') == 'response_received'
            and initial.get('purpose') == initial_purpose and revised.get('purpose') == 'numeric_revision', 'CLAIM_PROSE_NUMERIC_REVISION_UNCONFIRMED')
    events = {key: [event['value'] for event in trace.data['events'] if event['kind'] == kind]
              for key, kind in zip(('numericRevisionSelected', 'numericRevisionInitialCheck', 'numericRevisionCheck'), event_kinds)}
    require(all(len(rows) == 1 for rows in events.values()), 'CLAIM_PROSE_NUMERIC_REVISION_TRACE_INCOMPLETE')
    selected, = events['numericRevisionSelected']
    require(selected['initialCallId'] == initial['callId'] and selected[selected_end_key] == revised['callId']
            and initial['response'].get('id') and initial['response'].get('id') != revised['response'].get('id'),
            'CLAIM_PROSE_NUMERIC_REVISION_RESPONSE_CHANGED')
    first_request, second_request = initial['request'], revised['request']
    cited_blocks = 'structured_outputs' in first_request
    first_messages, second_messages = first_request['messages'], second_request['messages']
    has_images = any(part['type'] == 'image_url' for message in first_messages for part in message['content'])
    first_schema = first_request.get('structured_outputs')
    second_schema = second_request.get('structured_outputs')
    part_count = cited_section_count(first_schema, len(sources))
    image_use_completion = image_use_completion_schema(first_schema, sources)
    label_paragraph_quantities = image_label_quantity_schema(first_schema, sources)
    legacy_digit_pattern = legacy_image_digit_pattern(first_schema)
    initial_answer, initial_check = check_numeric_answer(initial['response'], sources, cited_blocks=cited_blocks,
                                                         part_count=part_count, image_use_completion=image_use_completion, legacy_digit_pattern=legacy_digit_pattern,
                                                         label_paragraph_quantities=label_paragraph_quantities)
    state_review_schema=image_use_state_review_schema(first_schema,sources)
    if state_review_schema and initial_purpose is None:
        require(selected.get('chainRecipe') in {'image-use-state-review-1','image-use-state-review-then-neutral-quantity-1'},
                'CLAIM_IMAGE_USE_STATE_REVIEW_REQUIRED')
        initial_check=image_use_review_initial_check(initial['response'],sources,first_schema)
        require(initial_check is not None,'CLAIM_IMAGE_USE_STATE_REVIEW_REQUIRED')
    replacement_keys = []
    edit_plan = None
    if (image_use_completion or label_paragraph_quantities) and second_schema != first_schema:
        replacement_keys = image_quantity_replacement_keys(initial_check, len(sources),label_paragraph_quantities=label_paragraph_quantities)
        if not legacy_digit_pattern:
            edit_plan = image_quantity_edit_plan(initial['response'],replacement_keys,sources=sources,
                                                label_paragraph_quantities=label_paragraph_quantities,
                                                fragment_version=1 if (second_schema or {}).get('json',{}).get('title') in {'image-quantity-fragments-1','image-label-quantity-fragments-1'} else 2)
        require(replacement_keys and (edit_plan is None or edit_plan) and second_schema == image_quantity_replacement_schema(first_schema, replacement_keys, edit_plan=edit_plan),
                'CLAIM_PROSE_NUMERIC_REVISION_REQUEST_CHANGED')
    if label_paragraph_quantities and initial_check.get('reason') == 'BUSINESS_IMAGE_REVISION_UNVERIFIED_NUMBER':
        require(replacement_keys and edit_plan,'CLAIM_PROSE_NUMERIC_REVISION_REQUEST_CHANGED')
    qualitative_images = (not replacement_keys and qualitative_image_revision(cited_blocks, has_images, sources, initial_check.get('reason'))
        and second_schema == {'json': cited_block_schema(len(sources), qualitative_images=True, legacy_digit_pattern=legacy_image_digit_pattern(second_schema))})
    if second_schema != first_schema and not replacement_keys:
        require(qualitative_images,'CLAIM_PROSE_NUMERIC_REVISION_REQUEST_CHANGED')
    if qualitative_images:
        require(not part_count and qualitative_image_revision(cited_blocks, has_images, sources, initial_check.get('reason'))
                and second_schema == {'json': cited_block_schema(len(sources), qualitative_images=True, legacy_digit_pattern=legacy_image_digit_pattern(second_schema))},
                'CLAIM_PROSE_NUMERIC_REVISION_REQUEST_CHANGED')
    ignored = {'messages', 'max_tokens', 'structured_outputs'}
    require(same({key: value for key, value in first_request.items() if key not in ignored},
                 {key: value for key, value in second_request.items() if key not in ignored})
            and (not cited_blocks or first_schema == {'json': cited_block_schema(
                len(sources), qualitative_images=image_label_selection(sources), part_count=part_count,
                image_use_completion=image_use_completion, legacy_digit_pattern=legacy_digit_pattern,
                label_paragraph_quantities=label_paragraph_quantities,visual_state_review=state_review_schema,
            )}),
            'CLAIM_PROSE_NUMERIC_REVISION_REQUEST_CHANGED')
    require(len(first_messages) == len(second_messages) == 2 and first_messages[0]['role'] == second_messages[0]['role'] == 'system'
            and first_messages[1]['role'] == second_messages[1]['role'] == 'user'
            and same(initial.get('actualEvidence'), revised.get('actualEvidence'))
            and initial.get('expectedSourcePartSha256') and initial['expectedSourcePartSha256'] == revised.get('expectedSourcePartSha256'),
            'CLAIM_PROSE_NUMERIC_REVISION_EVIDENCE_CHANGED')
    first_system, = first_messages[0]['content']
    second_system, = second_messages[0]['content']
    instruction = numeric_revision_instruction(cited_blocks, has_images, initial_check.get('reason'),
                                               qualitative_images=qualitative_images,
                                               image_use_completion=image_use_completion,
                                               image_quantity_fields=initial_check.get('imageQuantityFields', ()),
                                               image_replacement_keys=replacement_keys, bounded_image_edits=bool(edit_plan))
    draft = numeric_revision_draft(initial_answer, initial_check, sources, has_images=has_images,
                                  label_edit_plan=edit_plan if (label_paragraph_quantities or (edit_plan and edit_plan[0].get('fragmentVersion') == 2)) else None,initial_result=initial['response'])
    original_content = first_messages[1]['content']
    revised_content = second_messages[1]['content']
    if draft:
        instruction += (IMAGE_LABEL_FRAGMENT_DRAFT_GUIDANCE if edit_plan and (label_paragraph_quantities or edit_plan[0].get('fragmentVersion') == 2) else REVISION_DRAFT_GUIDANCE)
        require(len(revised_content) == len(original_content) + 1 and len(revised_content) >= 2,
                'CLAIM_PROSE_NUMERIC_REVISION_DRAFT_CHANGED')
        part = revised_content[-2]
        require(part.get('type') == 'text' and part.get('characters') == len(draft)
                and part.get('textSha256') == hashlib.sha256(draft.encode()).hexdigest()
                and ('text' not in part or part['text'] == draft), 'CLAIM_PROSE_NUMERIC_REVISION_DRAFT_CHANGED')
        revised_content = [*revised_content[:-2], revised_content[-1]]
    require(same(original_content, revised_content), 'CLAIM_PROSE_NUMERIC_REVISION_EVIDENCE_CHANGED')
    require(second_system.get('characters') == first_system.get('characters') + len(instruction),
            'CLAIM_PROSE_NUMERIC_REVISION_INSTRUCTION_CHANGED')
    if 'text' in first_system:
        require(second_system.get('text') == first_system['text'] + instruction,
                'CLAIM_PROSE_NUMERIC_REVISION_INSTRUCTION_CHANGED')
    maximum, retry_limit = first_request.get('max_tokens'), second_request.get('max_tokens')
    first_used = (initial['response'].get('usage') or {}).get('completion_tokens')
    second_used = (revised['response'].get('usage') or {}).get('completion_tokens')
    require(all(type(value) is int and value > 0 for value in (maximum, retry_limit, first_used, second_used))
            and maximum == selected['maximumCombinedOutputTokens'] and first_used + retry_limit <= maximum
            and second_used <= retry_limit, 'CLAIM_PROSE_NUMERIC_REVISION_OUTPUT_BUDGET_CHANGED')
    _, revised_check = check_numeric_answer(revised['response'], sources, cited_blocks=cited_blocks,
                                            qualitative_images=qualitative_images, part_count=part_count,
                                            image_use_completion=image_use_completion,
                                            label_paragraph_quantities=label_paragraph_quantities,
                                            image_replacement=((initial['response'], replacement_keys, edit_plan) if edit_plan else (initial['response'], replacement_keys)) if replacement_keys else None,
                                            legacy_digit_pattern=legacy_image_digit_pattern(second_schema))
    require(initial_check.get('reason') in REVISION_REASONS and revised_check['accepted'] is expected_accepted
            and same(events['numericRevisionInitialCheck'][0], initial_check)
            and same(events['numericRevisionCheck'][0], revised_check), 'CLAIM_PROSE_NUMERIC_REVISION_VALIDATION_CHANGED')
    if not expected_accepted:
        require((initial_check.get('reason') == 'IMAGE_COMPLETENESS_UNVERIFIED' and label_paragraph_quantities
                 or initial_check.get('reason') in {'BUSINESS_IMAGE_USE_INVALID_REFERENCE','IMAGE_USE_STATE_REVIEW_REQUIRED'} and image_use_completion)
                and revised_check.get('reason') == 'BUSINESS_IMAGE_REVISION_UNVERIFIED_NUMBER'
                and not replacement_keys,
                'CLAIM_PROSE_NUMERIC_REVISION_VALIDATION_CHANGED')
    if replacement_keys:
        raw = revised['response']['choices'][0]['message']['content']
        return merge_image_quantity_revision(initial['response'], raw, replacement_keys, edit_plan=edit_plan, sources=sources,
                                              label_paragraph_quantities=label_paragraph_quantities), replacement_keys

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
        elif source.provenance.get('stepCylinderDimensions'):
            from services.engineering_evidence_plan import verify_step_cylinder_context
            facts = verify_step_cylinder_context(source.evidence_bundle, source.model_context, source.fact_ids)
            link['factIds'] = list(source.fact_ids)
            link['factLocators'] = {key: facts[key]['locator'] for key in source.fact_ids}
            link['factNatures'] = {key: facts[key]['evidenceNature'] for key in source.fact_ids}
        elif mixed:
            facts={f['factId']:f for f in source.evidence_bundle.get('facts',[])}
            require(set(source.fact_ids)<=set(facts),'CLAIM_PROSE_MIXED_FACT_NOT_READ')
            link['factIds']=list(source.fact_ids);link['factLocators']={k:facts[k]['locator'] for k in source.fact_ids}
            link['factNatures']={k:facts[k]['evidenceNature'] for k in source.fact_ids}
        else:
            references = source.provenance.get('jsonRecordBindingReferences', [])
            anchor_evidence = {reference['evidenceId'] for reference in references}
            anchors = [candidate for candidate in sources if candidate.evidence_id in anchor_evidence
                       and candidate.source_id != source.source_id]
            catalogue = Catalogue([source, *anchors])
            require(not catalogue.unsupported, 'CLAIM_PROSE_TEXT_ADAPTER_UNAVAILABLE')
            catalogue.bind_calls([call], [event['value'] for event in trace.data['events'] if event['kind'] == 'nativeReadReceipt'])
            anchor_facts = {reference['factId'] for reference in references}
            expected = set(source.fact_ids) | anchor_facts
            require(set(catalogue.facts) == expected and set(catalogue.proofs) == expected,
                    'CLAIM_PROSE_TEXT_FACTS_NOT_TRANSMITTED')
            link['factIds'] = list(source.fact_ids)
            link['factLocators'] = {fact_id: catalogue.facts[fact_id]['locator'] for fact_id in source.fact_ids}
            link['factNatures'] = {fact_id: catalogue.facts[fact_id]['evidenceNature'] for fact_id in source.fact_ids}
            if references:
                link['recordAnchorFactIds'] = sorted(anchor_facts)
                link['readIds'] = sorted({*link['readIds'], *(reference['readId'] for reference in references)})
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
        require(all(payload.get(k)==image.get(k) for k in ['imageId','sha256','width','height']) and image['width']*image['height']<=max_image_pixels(),'CLAIM_PROSE_IMAGE_MAPPING_CHANGED')
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

def _model_slots(sources,call):
    """Resolve labels from actual recorded model order, including replay subsets."""
    actual=call.get('actualEvidence',[]);require(isinstance(actual,list) and actual,'CLAIM_PROSE_ACTUAL_SOURCE_ORDER_REQUIRED')
    texts=[]
    user_text_hashes = set()
    for message in call.get('request',{}).get('messages',[]):
        content=message.get('content',[])
        if isinstance(content,str):texts.append(content)
        elif isinstance(content,list):
            for part in content:
                if isinstance(part,dict) and part.get('type')=='text':
                    if isinstance(part.get('text'),str):texts.append(part['text'])
                    if message.get('role')=='user' and isinstance(part.get('textSha256'),str):
                        user_text_hashes.add(part['textSha256'])
    by_model={};by_input={}
    for index,source in enumerate(sources,1):
        matches=[i for i,item in enumerate(actual,1) if item.get('sourceId')==source.source_id and item.get('evidenceId')==source.evidence_id]
        require(len(matches)==1,'CLAIM_PROSE_SOURCE_ORDER_AMBIGUOUS');model=matches[0]
        require(model not in by_model,'CLAIM_PROSE_DUPLICATE_SOURCE_INPUT')
        context=('用户所附图片，文件名不构成图片内容依据。' if source.source_type=='user_image' else source.model_context or source.content_preview)
        require(isinstance(context,str) and context,'CLAIM_PROSE_SOURCE_FRAME_EMPTY')
        frame = source_frame(source, model)
        proof = actual[model-1].get('sourceFrameProof')
        expected_part = call.get('expectedSourcePartSha256')
        if proof is None and expected_part is None:
            # Replay traces created before frame proofs may still have the actual text.
            matched = any(text.startswith(frame) or '\n'+frame in text for text in texts)
        else:
            matched = (isinstance(proof,dict) and proof.get('position') == model
                       and proof.get('frameSha256') == hashlib.sha256(frame.encode()).hexdigest()
                       and isinstance(proof.get('sourcePartSha256'),str)
                       and proof['sourcePartSha256'] == expected_part
                       and proof['sourcePartSha256'] in user_text_hashes)
        require(matched,'CLAIM_PROSE_ACTUAL_SOURCE_FRAME_CHANGED')
        by_model[model]=index;by_input[index]=model
    return by_model,by_input


def _compose(question,answer,result,sources,image_parts,trace):
    """Recompute the registered display using native proofs and the real response."""
    from services.ordered_text_report import PURPOSE, row_ids, verify as verify_ordered_report

    calls = trace.data['modelCalls'] if trace else []
    report_segments = []
    image_replacement = None
    if calls and calls[0].get('purpose') == PURPOSE:
        require(len(sources) == 1 and is_user(sources[0]) and not image_parts
                and not trace.data['snapshot'], 'ORDERED_TEXT_REPORT_SCOPE_CHANGED')
        raw, report_segments = verify_ordered_report(row_ids(question, sources[0]), trace)
        for segment_call in calls:
            verify_context(sources, trace, segment_call)
            verify_user(sources[0], question, trace, segment_call)
            _model_slots(sources, segment_call)
        call = calls[-1]
        require(same(call['response']['choices'], result.get('choices')) and call['response']['id'] == result.get('id'),
                'CLAIM_PROSE_RESPONSE_CHANGED')
    elif len(calls) in (2, 3):
        image_replacement = verify_numeric_revision(trace, calls, sources)
        call = _response_call(result, calls[-1])
        raw = image_replacement[0] if image_replacement else result['choices'][0]['message']['content']
    else:
        call=_call(result,trace)
        raw=result['choices'][0]['message']['content']
    mixed=has_user(sources)
    require(hashlib.sha256(question.encode()).hexdigest() == trace.data['questionSha256']
        and sources and all(question_matches(s,question,mixed) for s in sources),'CLAIM_PROSE_QUESTION_CHANGED')
    if mixed:
        verify_context(sources,trace,call)
        for source in sources:
            if is_user(source):verify_user(source,question,trace,call,image_parts)
    from services.business_answer_guard import (
        batch_quantity_summary, cited_block_schema, cited_section_count, cited_section_prompt,
        image_label_quantity_schema, image_use_completion_schema, image_use_state_review_schema, legacy_image_digit_pattern, purchase_quantity_summary, render_cited_blocks,
    )
    from services.user_input_evidence import image_label_selection
    quantity_views = {}
    for render, key, error in (
        (batch_quantity_summary, 'batchQuantityViewSha256', 'CLAIM_PROSE_BATCH_QUANTITY_VIEW_NOT_TRANSMITTED'),
        (purchase_quantity_summary, 'purchaseQuantityViewSha256', 'CLAIM_PROSE_PURCHASE_QUANTITY_VIEW_NOT_TRANSMITTED'),
    ):
        view = render(sources)
        if view:
            view_sha = hashlib.sha256(view.encode()).hexdigest()
            require(any(part.get('textSha256') == view_sha for message in call['request']['messages']
                        if message['role'] == 'user' and isinstance(message['content'], list) for part in message['content']), error)
            quantity_views[key] = view_sha
    schema_call = calls[-2] if image_replacement else call
    output_schema = schema_call.get('request', {}).get('structured_outputs')
    legacy_digit_pattern = legacy_image_digit_pattern(output_schema)
    part_count = cited_section_count(output_schema, len(sources))
    image_use_completion = image_use_completion_schema(output_schema, sources)
    label_paragraph_quantities = image_label_quantity_schema(output_schema, sources)
    # Normal cited blocks can share the qualitative-image schema. A retry alone
    # does not establish that any image was supplied to the model.
    qualitative_images = (bool(image_parts)
                          and (call.get('purpose') == 'numeric_revision' or image_label_selection(sources))
                          and output_schema == {'json': cited_block_schema(
                              len(sources), qualitative_images=True, image_use_completion=image_use_completion, legacy_digit_pattern=legacy_digit_pattern,
                              label_paragraph_quantities=label_paragraph_quantities,visual_state_review=image_use_state_review_schema(output_schema,sources))})
    cited_blocks = (bool(part_count) or image_use_completion or qualitative_images
                    or output_schema == {'json': cited_block_schema(len(sources),legacy_digit_pattern=legacy_digit_pattern)})
    rendered = raw
    if part_count:
        from services.prompt_builder import explicit_answer_parts

        parts = explicit_answer_parts(question)
        outline_sha = hashlib.sha256(cited_section_prompt(parts).encode()).hexdigest()
        require(not image_parts and not mixed and len(parts) == part_count
                and any(part.get('textSha256') == outline_sha for message in call['request']['messages']
                        if message['role'] == 'user' and isinstance(message['content'], list) for part in message['content']),
                'CLAIM_PROSE_ANSWER_PARTS_CHANGED')
    if cited_blocks:
        rendered = render_cited_blocks(raw, len(sources), qualitative_images=qualitative_images, sources=sources,
                                      part_count=part_count, image_use_completion=image_use_completion, legacy_digit_pattern=legacy_digit_pattern,
                                      label_paragraph_quantities=label_paragraph_quantities)
        require(part_count or answer == rendered, 'CLAIM_PROSE_CITED_BLOCKS_RENDER_CHANGED')
    if answer != rendered:
        checked=[e['value'] for e in trace.data['events'] if e['kind']=='textOutputCheck']
        require(len(checked)==1 and checked[0].get('status')!='REJECTED' and checked[0].get('normalizedAnswer')==answer,'CLAIM_PROSE_PRESENTATION_CHANGED_WITHOUT_CHECK')
    labels=re.findall(r'\[来源[^\]\n]*\]',answer);require(labels and all(CITATION.fullmatch(x) for x in labels),'CLAIM_PROSE_CITATION_MISSING_OR_INVALID')
    model_to_input,input_to_model=_model_slots(sources,call)
    model_indices=sorted({int(x) for x in CITATION.findall(answer)})
    require(all(i in model_to_input for i in model_indices),'CLAIM_PROSE_CITATION_OUT_OF_RANGE')
    indices=[model_to_input[i] for i in model_indices]
    if mixed:
        from services.evidence_policy import format_intent
        allowed=format_intent(question).allowed
        require(not allowed or all(sources[i-1].original_format in allowed for i in indices),'CLAIM_PROSE_EXPLICIT_FORMAT_CONFLICT')
        require(all(not is_user(sources[i-1]) or citation_allowed(sources[i-1],question) for i in indices),'CLAIM_PROSE_EXPLICIT_SOURCE_CONFLICT')
    links=_text_links(question,sources,indices,trace,call,mixed)+_image_links(sources,indices,image_parts,trace,call)
    for i in indices:
        if not is_user(sources[i-1]):
            continue
        link=dict(verify_user(sources[i-1],question,trace,call,image_parts),sourceIndex=i)
        evidence=link.get('attachmentLabelEvidence')
        if evidence:
            from services.user_input_evidence import drawing_unit_statement, label_statement

            model_index=input_to_model[i]
            lines={CITATION.sub('',line).strip() for line in answer.splitlines()
                   if CITATION.findall(line)==[str(model_index)]}
            versioned = label_paragraph_quantities and evidence.get('drawingUnitRecipe') == 'drawing-unit-double-ocr-1'
            selected=[item for item in evidence['observations'] if label_statement(item,drawing_units=versioned) in lines]
            units = [item for item in evidence.get('drawingUnits',[]) if drawing_unit_statement(item) in lines] if label_paragraph_quantities else []
            if selected or units:
                link['attachmentLabelEvidence']={**evidence,'observations':selected,
                                                **({'drawingUnits':units} if label_paragraph_quantities else {})}
            else:
                link.pop('attachmentLabelEvidence')
        links.append(link)
    require(set(indices)=={x['sourceIndex'] for x in links},'CLAIM_PROSE_CITED_SOURCE_HAS_NO_ACTUAL_INPUT')
    mapping={old:new for new,old in enumerate(indices,1)}
    for link in links:
        index=link['sourceIndex'];link['modelSourceIndex']=input_to_model[index];link['sourceIndex']=mapping[index]
    displayed,display_edits=catalogue_display(answer,sources,indices)
    for edit in display_edits:
        index=edit['inputSourceIndex'];edit.update(modelSourceIndex=input_to_model[index],sourceIndex=mapping[index])
    model_mapping={input_to_model[index]:mapping[index] for index in indices}
    numbered_display = displayed
    if all(source.source_type == 'user_image' for source in sources):
        renumbered = []
        for line in displayed.splitlines(keepends=True):
            cited = set(CITATION.findall(line))
            renumbered.append(PARENTHESIZED_SOURCE.sub(
                lambda label: label[1] + str(model_mapping[int(label[2])]) + label[3]
                if label[2] in cited else label[0], line))
        numbered_display = ''.join(renumbered)
    text=CITATION.sub(lambda m:'[来源'+str(model_mapping[int(m[1])])+']',numbered_display);final=HEADER+text
    proof=dict(recipeVersion=FINAL_DISPLAY_VERSION,modelCallId=call['callId'],modelRequestBodySha256=call['requestBodySha256'],
        modelResponseTextSha256=hashlib.sha256(result['choices'][0]['message']['content'].encode()).hexdigest(),acceptedTextSha256=hashlib.sha256(answer.encode()).hexdigest(),
        displayedTextSha256=hashlib.sha256(displayed.encode()).hexdigest(),finalClaimTextSha256=hashlib.sha256(text.encode()).hexdigest(),
        modelToFinalSourceIndices={str(k):v for k,v in model_mapping.items()},normalizedByTextOutputCheck=answer!=rendered)
    if cited_blocks:
        proof['renderedFromBusinessCitedBlocks']=True
    if part_count:
        proof.update(answerPartCount=part_count, transmittedAnswerPartsSha256=outline_sha)
    if call.get('purpose') == 'numeric_revision':
        proof['revisedAfterNumericCheck'] = True
        proof['initialModelCallId'] = trace.data['modelCalls'][0]['callId']
    if image_replacement:
        original_raw = calls[0]['response']['choices'][0]['message']['content']
        proof.update(imageQuantityReplacementKeys=image_replacement[1],
                     initialModelResponseTextSha256=hashlib.sha256(original_raw.encode()).hexdigest(),
                     composedModelTextSha256=hashlib.sha256(raw.encode()).hexdigest())
        if len(calls) == 3:
            proof.update(imageQuantityReplacementBaseModelCallId=calls[1]['callId'],
                imageQuantityReplacementBaseResponseTextSha256=hashlib.sha256(calls[1]['response']['choices'][0]['message']['content'].encode()).hexdigest(),
                numericRevisionChainRecipe=next(event['value']['chainRecipe'] for event in trace.data['events']
                    if event['kind'] == 'numericRevisionSelected'))
    if report_segments:
        proof['orderedReportSegments'] = report_segments
        proof['joinedModelTextSha256'] = hashlib.sha256(raw.encode()).hexdigest()
    proof.update(quantity_views)
    claim=dict(claimId=str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([VERSION,text,links,display_edits,proof]))),kind='source_interpretation',text=text,answerStart=len(HEADER),answerEnd=len(final),offsetUnit='unicode-code-points',
        evidenceNature='model_interpretation',verification='actual-input-binding-only-not-semantic-verification',sourceIndices=list(range(1,len(indices)+1)),
        evidenceIds=[sources[i-1].evidence_id for i in indices],factIds=sorted({f for x in links for f in x.get('factIds',[])}),
        imageIds=[x['imageId'] for x in links if x.get('imageId')],readIds=sorted({v for x in links for v in x['readIds']}),inputLinks=links,
        presentationTransforms=display_edits,presentationProof=proof)
    final_sources=[sources[i-1].model_copy(update={'provenance':{**sources[i-1].provenance,'modelSourceIndex':input_to_model[i],'finalSourceIndex':mapping[i],'citationScopeCheck':'actual-input-binding-only'}}).model_dump(mode='json') for i in indices]
    presentation=dict(version=VERSION,answer=final,claims=[claim],sources=final_sources,generationStatus='source_interpretation_unverified',modelInterpretationVerified=False,
        notice='引用已关联实际读取和入模的文字或图片；模型解释的事实语义尚未认证。',verificationScope='Native receipts, exact actual contexts/images, declared citations and final offsets only; no semantic correctness or independent corroboration claim.',
        presentationTransforms=display_edits,presentationProof=proof)
    return presentation


def verify_presentation(question,answer,result,sources,image_parts,trace,presentation):
    """Revalidate native inputs and reject any unregistered final-output change."""
    expected=_compose(question,answer,result,sources,image_parts,trace)
    supplied=dict(presentation);previous=supplied.pop('finalClaimCheck',None)
    require(same(supplied,expected),'CLAIM_PROSE_FINAL_PRESENTATION_CHANGED')
    final=expected['answer'];claims=expected['claims'];require(len(claims)==1,'CLAIM_PROSE_FINAL_CLAIM_COUNT_CHANGED')
    claim=claims[0]
    require(claim['offsetUnit']=='unicode-code-points' and claim['answerStart']==len(HEADER) and claim['answerEnd']==len(final)
        and final[claim['answerStart']:claim['answerEnd']]==claim['text'],'CLAIM_PROSE_FINAL_CLAIM_SPAN_CHANGED')
    check=dict(version=FINAL_DISPLAY_VERSION,status='VERIFIED_NATIVE_INPUTS_REGISTERED_DISPLAY_AND_EXACT_SPANS',
        answerSha256=hashlib.sha256(final.encode()).hexdigest(),claimsSha256=hashlib.sha256(canonical(claims).encode()).hexdigest(),
        presentationTransformsSha256=hashlib.sha256(canonical(expected['presentationTransforms']).encode()).hexdigest(),
        sourceIndexMap=expected['presentationProof']['modelToFinalSourceIndices'],modelInterpretationVerified=False,
        verificationScope='Actual native inputs and receipts, unchanged model response, registered display, citations and Unicode spans; no semantic certification.')
    require(previous is None or same(previous,check),'CLAIM_PROSE_FINAL_CHECK_RECEIPT_CHANGED')
    return check


def finish(question,answer,result,sources,image_parts,trace):
    """Bind prose to the native inputs and verify the exact displayed claims."""
    presentation=_compose(question,answer,result,sources,image_parts,trace)
    presentation['finalClaimCheck']=verify_presentation(question,answer,result,sources,image_parts,trace,presentation)
    trace.event('proseFinalPresentationCheck',presentation['finalClaimCheck'])
    trace.event('prosePresentation',presentation);return presentation

def finish_excerpts(question,answer,result,sources,claims,trace):
    """Bind rejected-output fallback quotes without repairing model semantics."""
    call=_call(result,trace,allow_truncated=True,revision_sources=sources)
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
