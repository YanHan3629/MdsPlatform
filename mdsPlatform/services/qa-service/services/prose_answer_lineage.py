"""Read/model bindings for cited prose; these do not certify model semantics."""
import base64,copy,hashlib,json,re,uuid
from decimal import Decimal, InvalidOperation
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
    coordinates=('datasetId','versionId','indexVersionId')
    if trace.data.get('scopes'):
        frozen={key:identity[key] for key in coordinates}
        require(frozen in trace.data['scopes'] and frozen==provenance.get('frozenScope')
                and frozen['datasetId']==str(source.dataset_id),'CLAIM_PROSE_SNAPSHOT_CHANGED')
    else:
        requested=tuple(str(trace.data['snapshot'].get(key)) for key in ('dataset_id','version_id','index_version_id'))
        require(tuple(identity[key] for key in coordinates)==requested,'CLAIM_PROSE_SNAPSHOT_CHANGED')
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
    if trace.data.get('scopes'):
        require(actual.get('scope')==receipt.get('scope')==source.provenance.get('frozenScope'),
                'CLAIM_PROSE_ACTUAL_SCOPE_CHANGED')
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
        elif mixed or trace.data.get('scopes'):
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


def _append_verified_claim(presentation, statement, links, sources, input_to_model, recipe, heading='\n',
                           operation=None):
    mapping = presentation['presentationProof']['modelToFinalSourceIndices']
    for link in links:
        source = sources[link['sourceIndex'] - 1]
        model_index = input_to_model[link['sourceIndex']]
        if str(model_index) not in mapping:
            mapping[str(model_index)] = len(mapping) + 1
            presentation['sources'].append(source.model_copy(update={'provenance': {
                **source.provenance, 'modelSourceIndex': model_index, 'finalSourceIndex': mapping[str(model_index)],
                'citationScopeCheck': recipe,
            }}).model_dump(mode='json'))
        link.update(sourceIndex=mapping[str(model_index)], modelSourceIndex=model_index)
    indices = [link['sourceIndex'] for link in links]
    text = statement + ' ' + ''.join(f'[来源{index}]' for index in indices)
    start = len(presentation['answer']) + len(heading)
    presentation['answer'] += heading + text
    claim = dict(claimId=str(uuid.uuid5(uuid.NAMESPACE_URL, canonical([recipe, start, text, links]))),
        kind='registered_operation' if operation else 'source_value',
        evidenceNature='program_calculation' if operation else 'source_record',
        verification='recomputed-from-actual-model-input' if operation else 'exact-native-fields-from-actual-model-input',
        text=text, answerStart=start, answerEnd=len(presentation['answer']), offsetUnit='unicode-code-points',
        sourceIndices=indices, evidenceIds=[link['evidenceId'] for link in links],
        factIds=list(dict.fromkeys(fact for link in links for fact in link['factIds'])),
        readIds=list(dict.fromkeys(read for link in links for read in link['readIds'])), inputLinks=links)
    if operation:
        claim['operation'] = operation
    presentation['claims'].append(claim)
    return claim['claimId']


def _append_material_receipts(presentation, sources, cited_indices, input_to_model, trace, call):
    """Show linked consumption, receipt and inspection fields from verified native inputs."""
    if not trace.data.get('scopes') or not any(source.provenance.get('scopedMaterialTraceRole')
                                               in {'receipt', 'inspection'} for source in sources):
        return
    from services.multi_scope_evidence import IDENTIFIER, _count, _signed_at, _source_row

    related = set()
    consumption_links = {}
    for index in cited_indices:
        source = sources[index-1]
        if source.provenance.get('scopedMaterialTraceRole') == 'consumption':
            row, _ = _source_row(source)
            pair = row.get('supplier_batch_id'), row.get('part_id')
            if all(isinstance(value, str) and value for value in pair):
                try:
                    consumption_links[index] = _native_link(source, trace, call)
                except (KeyError, TypeError, ValueError):
                    continue
                related.add(pair)
    if not related:
        return
    identifiers = {'receipt': [], 'inspection': [], 'consumption': []}
    displayed = set()
    receipt_fields = ('supplier_batch_id', 'part_id', 'received_at', 'final_state')
    consumption_fields = ('manufacturing_batch_id', 'consumption_id', 'supplier_batch_id', 'part_id', 'quantity', 'unit')
    batches = {batch for batch, _ in related}
    for index, source in enumerate(sources, 1):
        role = source.provenance.get('scopedMaterialTraceRole')
        if role not in identifiers:
            continue
        row, facts = _source_row(source)
        if role == 'consumption':
            fields = consumption_fields
            if index not in consumption_links or not set(fields) <= row.keys():
                continue
            production, movement, batch, part, quantity, unit = (row[field] for field in fields)
            if (not all(isinstance(value, str) and IDENTIFIER.fullmatch(value) for value in (production, movement, batch, part))
                    or not _displayable_cell(unit)):
                continue
            try:
                amount = _count(quantity)
            except (InvalidOperation, TypeError, ValueError):
                continue
            statement = f'制造批 {production}，领料记录 {movement}，料号 {part}，来料批 {batch}，领料 {amount}{unit}。'
            title = '原表领料记录'
        elif role == 'receipt':
            fields = receipt_fields
            if not set(fields) <= row.keys():
                continue
            batch, part, instant, state = (row[field] for field in fields)
            if (not all(_displayable_cell(value) for value in (batch, part, state)) or (batch, part) not in related
                    or not IDENTIFIER.fullmatch(batch) or not IDENTIFIER.fullmatch(part)):
                continue
            statement = f'来料批 {batch}，料号 {part}，原表接收时刻 {instant}，状态为{state}。'
            title = '原表来料接收记录'
        else:
            inspection = _inspection_statement(source, row, '来料批')
            if inspection is None or row['object_id'] not in batches:
                continue
            statement, fields = inspection
            instant = row['measured_at']
            title = '原表来料检验记录'
        try:
            if role == 'consumption':
                link = consumption_links[index]
            else:
                _signed_at(instant)
                link = _native_link(source, trace, call)
        except (KeyError, TypeError, ValueError):
            continue
        if statement in displayed:
            continue
        displayed.add(statement)
        link.update(sourceIndex=index, factIds=[facts[field] for field in fields],
                    referenceRole=f'exact-material-{role}-fields')
        heading = f'\n\n{title}。\n' if not identifiers[role] else '\n'
        identifiers[role].append(_append_verified_claim(presentation, statement, [link], sources, input_to_model,
                                                        f'verified-material-{role}-input', heading))
    for role, key in (('receipt', 'verifiedMaterialReceiptClaimIds'), ('inspection', 'verifiedMaterialInspectionClaimIds'),
                      ('consumption', 'verifiedMaterialConsumptionClaimIds')):
        if identifiers[role]:
            presentation['presentationProof'][key] = identifiers[role]


def _displayable_cell(value):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= 128 and not re.search(r'[\[\]\r\n]', value)


def _dimension_comparison(question, targets, rows, sources, *, multi):
    from services.multi_scope_evidence import DIMENSION_FIELDS, _dimension_differences, dimension_values

    rows_for_comparison = rows
    differences = {}
    if multi:
        differences = {fact['field']:fact for fact in _dimension_differences(question, sources)}
        if not differences:
            return []
        input_rows = {index for fact in differences.values() for index in fact['sourceIndices']}
        rows_for_comparison = {index:row for index, row in rows.items() if index in input_rows}
    records, comparable = [], []
    identifiers = ('sku_id',) if multi else ('model', 'sku_id')
    include_category = multi and re.search(r'品类|用途|性能|能效', question)
    for target in targets:
        matches = [(index, row, key) for index, row in rows_for_comparison.items() if DIMENSION_FIELDS.keys() <= row.keys()
                   for key in identifiers if str(row.get(key, '')).casefold() == target]
        if len(matches) != 1:
            return []
        index, row, key = matches[0]
        sizes = dimension_values(sources[index-1], row)
        if not sizes:
            return []
        name = row[key]
        fields = [key, *DIMENSION_FIELDS]
        text = f'{name} 的外形宽×深×高为 ' + '×'.join(map(str, sizes.values())) + ' mm'
        if multi:
            fields.append('manufacturer_id')
        if include_category:
            for field, label in (('category_id', '品类编号'), ('form_factor', '产品形态'), ('spec_condition', '规格条件')):
                if _displayable_cell(row.get(field)):
                    fields.append(field)
                    text += f'，原表{label}为{row[field]}'
        if _displayable_cell(row.get('installation_type')):
            fields.append('installation_type')
            text += f'，安装方式为{row["installation_type"]}'
        records.append((text + '。', {index: fields}, None))
        comparable.append((index, name, sizes, key))
    if comparable[0][0] == comparable[1][0]:
        return []
    for axis, label in DIMENSION_FIELDS.items():
        if label[0] not in question:
            continue
        ranked = sorted(comparable, key=lambda item: item[2][axis], reverse=True)
        larger, smaller = ranked
        delta = Decimal(differences[label]['exactValue']) if multi else larger[2][axis] - smaller[2][axis]
        text = (f'{larger[1]} 比 {smaller[1]} 的{label}多 {delta} mm。' if delta
                else f'{larger[1]} 与 {smaller[1]} 的{label}相同。')
        operation = {'operation': 'product_dimension_difference', 'field': axis, 'exactValue': str(delta or 0), 'unit': 'mm'}
        identity = ('manufacturer_id',) if multi else ()
        records.append((text, {index: (key, axis, *identity) for index, _, _, key in ranked}, operation))
    return records


def _warehouse_comparison(targets, rows, sources):
    from services.multi_scope_evidence import IDENTIFIER, _signed_at

    batches = [value for value in targets if value.startswith('fg-')]
    skus = [value for value in targets if value.startswith('sku-')]
    if len(batches) != 1 or len(skus) > 1 or len(batches) + len(skus) != len(targets):
        return []
    fields = ('batch_id', 'movement_id', 'movement_type', 'occurred_at', 'quantity_change', 'unit')
    if skus:
        fields += ('sku_id',)
    entries = {}
    for index, row in rows.items():
        if str(row.get('batch_id', '')).casefold() != batches[0] or not set(fields) <= row.keys():
            continue
        if skus and str(row['sku_id']).casefold() != skus[0]:
            continue
        movement = row['movement_id']
        if (not isinstance(movement, str) or not IDENTIFIER.fullmatch(movement) or movement in entries
                or not all(_displayable_cell(row.get(key)) for key in ('unit', 'movement_type', 'occurred_at'))):
            return []
        amount = Decimal(str(row['quantity_change']))
        if not amount.is_finite():
            return []
        entries[movement] = (index, row, amount, _signed_at(row['occurred_at']))
    outbounds = [item for item in entries.values() if item[1]['movement_type'] == '运输交接出库']
    if len(outbounds) != 1:
        return []
    out_index, out, deducted, outbound_time = outbounds[0]
    shipment = out.get('source_document_id')
    if deducted >= 0 or not isinstance(shipment, str) or not re.fullmatch(r'SHIP-[A-Za-z0-9-]+', shipment):
        return []
    pairs = []
    for index, moved, quantity, moment in entries.values():
        paired = moved.get('paired_movement_id')
        if quantity >= 0 or moment >= outbound_time or not isinstance(paired, str) or paired not in entries:
            continue
        other_index, staged, received, stage_time = entries[paired]
        if (staged.get('paired_movement_id') == moved['movement_id'] and received == -quantity
                and moved['unit'] == staged['unit'] and moment == stage_time
                and re.search(r'拣货|暂存', moved['movement_type'] + staged['movement_type'])
                and sources[index-1].asset_id == sources[other_index-1].asset_id == sources[out_index-1].asset_id):
            pairs.append((index, moved, quantity, other_index, staged, received))
    if len(pairs) != 1:
        return []
    index, moved, quantity, other_index, staged, received = pairs[0]
    before = (f'{moved["batch_id"]} 于 {moved["occurred_at"]} 在仓内移位，'
              f'{moved["movement_id"]}（{moved["movement_type"]}）{quantity}{moved["unit"]}，'
              f'{staged["movement_id"]}（{staged["movement_type"]}）+{received}{staged["unit"]}，两条流水互为配对。')
    pair_fields = (*fields, 'paired_movement_id')
    after = (f'{out["batch_id"]} 于 {out["occurred_at"]} 发生运输交接出库，'
             f'流水 {out["movement_id"]} 扣减 {abs(deducted)}{out["unit"]}，关联运输单 {shipment}。')
    out_fields = (*fields, 'source_document_id')
    if _displayable_cell(out.get('note')) and re.search(r'发车|启运', out['note']):
        after += f'出库流水备注记载“{out["note"]}”。'
        out_fields += ('note',)
    return [(before, {index: pair_fields, other_index: pair_fields}, None), (after, {out_index: out_fields}, None)]


def _packaging_comparison(question, delivery, rows, sources, *, multi):
    from services.multi_scope_evidence import IDENTIFIER, PACKAGE_BOX_FIELDS, _paired_boxes, whole_count

    links = {index:row for index, row in rows.items() if str(row.get('delivery_line_id', '')).casefold()==delivery}
    waybill_fields = ('waybill_line_id', 'delivery_line_id', 'signed_quantity', 'unit')
    waybills = [(index, row) for index, row in links.items() if set(waybill_fields)<=row.keys()
                and isinstance(row['waybill_line_id'], str) and row['waybill_line_id'].lower().startswith('wbl-')
                and IDENTIFIER.fullmatch(row['waybill_line_id']) and _displayable_cell(row['unit'])]
    pair = _paired_boxes(question, sources, allow_single=not multi)
    complete_pair = len(pair)==1 and set(pair[0]['sourceIndices']) <= links.keys()
    records, limits = [], []
    boxes_on_waybill = None
    waybill_index, waybill = waybills[0] if len(waybills)==1 else (None,{})
    signed = whole_count(waybill.get('signed_quantity'))
    if signed is not None:
        text = f'交付行 {waybill["delivery_line_id"]} 的运单行 {waybill["waybill_line_id"]} 实际签收 {signed}{waybill["unit"]}'
        fields = waybill_fields
        boxes_on_waybill = whole_count(waybill.get('box_count'))
        if boxes_on_waybill is not None:
            fields += ('box_count',)
            text += f'，运单登记 {boxes_on_waybill} 箱'
        records.append((text+'。', {waybill_index:fields}, None))
    else:
        limits.append('当前引证缺少同一交付行的承运签收凭证，实签数量待核对。')

    if complete_pair:
        total, = pair
        indices = sorted(total['sourceIndices'], key=lambda index:links[index]['component_type']!='室内机')
        accessories = False
        for index in indices:
            row = links[index]
            text = (f'交付行 {row["delivery_line_id"]} 的{row["component_type"]}组 {row["package_id"]} '
                    f'登记 {whole_count(row["box_count"])} 箱，配对包装组为 {row["paired_package_id"]}')
            fields = PACKAGE_BOX_FIELDS
            part = row.get('accessory_part_id')
            quantity = whole_count(row.get('accessory_quantity'))
            if isinstance(part, str) and IDENTIFIER.fullmatch(part) and quantity is not None:
                fields += ('accessory_part_id', 'accessory_quantity')
                text += f'，登记配件 {part} 共 {quantity} 件'
                accessories = True
            records.append((text+'。', {index:fields}, None))
        count = total['exactValue']
        operation = {key:total[key] for key in ('operation', 'delivery', 'exactValue', 'unit')}
        records.append((f'同一交付行的两个包装组合计 {count} 箱。',
                        {index:PACKAGE_BOX_FIELDS for index in indices}, operation))
        if boxes_on_waybill is not None and boxes_on_waybill!=Decimal(count):
            limits.append('运单与配对包装组的登记箱数有差异，请核对交付凭证和包装清单。')
        if any(waybill.get(field) and waybill[field]!=links[indices[0]][field] for field in ('sku_id', 'finished_batch_id')):
            limits.append('运单与包装组登记的型号或成品批有差异，请核对各自的交付关联。')
        if not accessories:
            limits.append('配件所在组及数量还需核对配件登记。')
    else:
        limits.append('当前引证缺少同一交付行的配对包装原表，内外机箱数和配件归属待核对。')
    return records, ''.join(limits)


def _transport_comparison(question, target, rows, sources, *, multi):
    from services.multi_scope_evidence import _shipment_durations, _signed_at, shipment_stage

    titles = {'count':'装车清点', 'release':'仓储出库', 'depart':'发车', 'arrive':'到达', 'sign':'客户签收'}
    needed = {'release':'仓储扣减流水', 'depart':'承运发车记录', 'arrive':'承运到达记录', 'sign':'承运签收记录'}
    timeline = {}
    for index, row in rows.items():
        stage, fields = shipment_stage(row)
        reference, time_field, _ = fields
        if stage is None or str(row.get(reference, '')).casefold()!=target or not set(fields)<=row.keys():
            continue
        try:
            moment = _signed_at(row[time_field])
        except ValueError:
            continue
        timeline.setdefault(stage,{}).setdefault(moment,(index,fields))

    records, limits = [], []
    for stage, label in titles.items():
        values = timeline.get(stage,{})
        if len(values)>1:
            limits.append(f'{label}时刻有冲突，请核对相应原表。')
        elif not values and stage in needed:
            limits.append(f'当前引证缺少{needed[stage]}，{label}时刻待核对。')

    complete = sorted((moment, stage, index, fields) for stage, values in timeline.items()
                      if len(values)==1 for moment,(index,fields) in values.items())
    duration_inputs = {}
    for _, stage, index, raw_fields in complete:
        row = rows[index]
        reference, time_field, _ = raw_fields
        text = f'运输单 {row[reference]} 的{titles[stage]}时刻为 {row[time_field]}'
        fields = raw_fields
        for field, label in (('movement_id', '流水'), ('event_id', '事件'), ('waybill_line_id', '运单行'), ('node_id', '节点')):
            if _displayable_cell(row.get(field)):
                fields += (field,)
                text += f'，{label} {row[field]}'
        if stage=='release' and row.get('quantity_change') not in (None, '') and _displayable_cell(row.get('unit')):
            fields += ('quantity_change','unit')
            text += f'，扣减 {abs(Decimal(str(row["quantity_change"])))}{row["unit"]}'
        records.append((text+'。', {index:fields}, None))
        duration_inputs[index] = raw_fields

    for fact in _shipment_durations(question, sources, allow_single=not multi):
        if fact['shipment'].casefold()!=target or not set(fact['sourceIndices'])<=duration_inputs.keys():
            continue
        text = f'{fact["field"]}相隔 {fact["exactValue"]}{fact["unit"]}。'
        operation = {key:fact[key] for key in ('operation', 'shipment', 'field', 'exactValue', 'unit')}
        records.append((text, {index:duration_inputs[index] for index in fact['sourceIndices']}, operation))
    return records, ''.join(limits)


def _counting_event_comparison(target, rows):
    from services.multi_scope_evidence import IDENTIFIER, SHIPMENT_EVENT_STAGES, _signed_at, whole_count

    fields = ('shipment_id', 'event_id', 'event_type', 'node_id', 'timestamp', 'box_count', 'waybill_line_ids')
    matches = [(index, row) for index, row in rows.items() if set(fields) <= row.keys()
               and str(row['shipment_id']).casefold() == target
               and SHIPMENT_EVENT_STAGES.get(row['event_type']) == 'count']
    if len(matches) != 1:
        return []
    index, row = matches[0]
    _signed_at(row['timestamp'])
    count = whole_count(row['box_count'])
    waybills = row['waybill_line_ids']
    if isinstance(waybills, str):
        waybills = json.loads(waybills)
    if (count is None or not isinstance(waybills, list) or not waybills
            or not all(isinstance(value, str) and IDENTIFIER.fullmatch(value) for value in
                       (row['event_id'], row['node_id'], *waybills))
            or not all(value.upper().startswith('WBL-') for value in waybills)
            or len(set(waybills)) != len(waybills)):
        return []
    text = (f'运输单 {row["shipment_id"]} 的{row["event_type"]}事件为 {row["event_id"]}，'
            f'发生于 {row["timestamp"]}，节点为 {row["node_id"]}，关联运单行 {"、".join(waybills)}，清点 {count} 箱。')
    return [(text, {index:fields}, None)]


def _allocated_batch_comparison(question, target, rows, sources):
    from services.business_records import CHINESE_LINE_COUNTS, PRODUCTION_BATCH_COUNT, _exact_decimal_sum
    from services.multi_scope_evidence import IDENTIFIER, whole_count

    fields = ('order_line_id', 'finished_batch_id', 'quantity')
    allocations = [(index, row) for index, row in rows.items() if set(fields) <= row.keys()
                   and str(row['order_line_id']).casefold() == target
                   and sources[index-1].provenance.get('verifiedOrderAllocation')]
    expected_counts = {int(value) if value.isdecimal() else CHINESE_LINE_COUNTS[value]
                       for value in PRODUCTION_BATCH_COUNT.findall(question)}
    if not allocations or expected_counts != {len(allocations)}:
        return []
    if len({sources[index-1].asset_id for index, _ in allocations}) != 1:
        return []
    batches = [row['finished_batch_id'] for _, row in allocations]
    if not all(isinstance(batch, str) and batch.startswith('FG-') and IDENTIFIER.fullmatch(batch) for batch in batches):
        return []
    if len(batches) != len(set(batches)):
        return []
    quantities = [whole_count(row['quantity']) for _, row in allocations]
    if any(quantity is None for quantity in quantities):
        return []
    units = {row.get('unit') for _, row in allocations}
    if len(units) != 1:
        return []
    unit, = units
    if unit is not None and not _displayable_cell(unit):
        return []
    shown_unit = unit or ''
    total = _exact_decimal_sum(quantities)
    distribution = (f'{"、".join(batches)} 各 {quantities[0]}{shown_unit}' if len(set(quantities)) == 1
                    else '，'.join(f'{batch} 分配 {quantity}{shown_unit}' for batch, quantity in zip(batches, quantities)))
    text = f'订单行 {allocations[0][1]["order_line_id"]} 的成品批分配为 {distribution}，合计 {total}{shown_unit}。'
    if unit is None:
        text += '原表字段未标明共同计量单位。'
    operation = {'operation':'order_batch_allocation_sum', 'field':'quantity', 'exactValue':str(total)}
    if unit:
        operation['unit'] = unit
    bound_fields = fields + ('unit',) if unit else fields
    return [(text, {index:bound_fields for index, _ in allocations}, operation)]


def _append_record_comparison(question, presentation, sources, cited_indices, input_to_model, trace, call):
    from services.business_records import asks_warehouse_handoff
    from services.multi_scope_evidence import IDENTIFIER, NAMED_RECORD_SOURCE, _compares_dimensions, _single_review_topic, _source_row

    if NAMED_RECORD_SOURCE.search(question) or re.search(r'只看|仅看|只用|仅用|排除|剔除|不含|不引用', question):
        return
    multi = len(trace.data.get('scopes') or ()) > 1
    targets = tuple(dict.fromkeys(match.group().casefold() for match in IDENTIFIER.finditer(question)))
    dimensions = len(targets) == 2 and _compares_dimensions(question)
    topic = _single_review_topic(question) if len(targets)==1 else None
    if not targets or not (dimensions or topic in {'包装签收','仓储运输','运输清点'}
                           or (not multi and (topic == '批次分配' or asks_warehouse_handoff(question)))):
        return
    limits = ''
    try:
        evidence = {index: _source_row(source, allow_single=not multi) for index, source in enumerate(sources, 1)
                    if index in input_to_model and not is_user(source)}
        rows = {index: row for index, (row, _) in evidence.items() if row}
        if dimensions:
            records = _dimension_comparison(question, targets, rows, sources, multi=multi)
        elif topic=='包装签收':
            records, limits = _packaging_comparison(question, targets[0], rows, sources, multi=multi)
        elif topic=='仓储运输':
            records, limits = _transport_comparison(question, targets[0], rows, sources, multi=multi)
        elif topic=='运输清点':
            records = _counting_event_comparison(targets[0], rows)
        elif topic=='批次分配':
            records = _allocated_batch_comparison(question, targets[0], rows, sources)
            limits = '分配记录只说明批次分配，实际出库仍需核对对应批次的出库流水。'
        else:
            records = _warehouse_comparison(targets, rows, sources)
        indices = {index for _, references, _ in records for index in references}
        if not indices.intersection(cited_indices):
            return
        verified = {index: _native_link(sources[index-1], trace, call) for index in indices}
        claims = [(text, [dict(verified[index], sourceIndex=index, referenceRole='exact-record-comparison-fields',
                               factIds=list(dict.fromkeys(evidence[index][1][field] for field in fields)))
                          for index, fields in references.items()], operation) for text, references, operation in records]
    except (InvalidOperation, KeyError, TypeError, ValueError):
        return
    heading = '\n\n原表对照。\n'
    if limits:
        heading += limits+'\n'
    if dimensions and multi and re.search(r'用途|性能|能效', question):
        heading += '外形尺寸用于比较空间占用。适用场景需核对各自的产品资料，性能和能效比较还需一致的测试条件和计量口径。\n'
    for statement, links, operation in claims:
        _append_verified_claim(presentation, statement, links, sources, input_to_model, 'verified-record-comparison',
                               heading, operation=operation)
        heading = '\n'


def _inspection_statement(source, row, object_label):
    from services.multi_scope_evidence import IDENTIFIER, measurement_quantities

    fields = ('object_id', 'qc_id', 'measured_at', 'item', 'result', 'unit')
    if not all(_displayable_cell(row.get(field)) for field in fields):
        return None
    if not all(IDENTIFIER.fullmatch(row[field]) for field in ('object_id', 'qc_id')):
        return None
    quantities = {field: (value, unit) for field, value, unit in measurement_quantities(source, allow_single=True)}
    numeric = ('actual_value', 'lower_limit', 'upper_limit')
    if not all(field in quantities for field in numeric):
        return None
    actual, lower, upper = (quantities[field][0] for field in numeric)
    unit = row['unit']
    text = (f'{object_label} {row["object_id"]} 的检验 {row["qc_id"]}，时刻 {row["measured_at"]}，'
            f'测项为{row["item"]}，原表实测值 {actual} {unit}，限值 {lower} 至 {upper} {unit}，结果为{row["result"]}')
    if 'quantity_checked' in quantities and _displayable_cell(row.get('quantity_checked_unit')):
        count, count_unit = quantities['quantity_checked']
        text += f'，检查量 {count} {count_unit}'
        numeric += ('quantity_checked', 'quantity_checked_unit')
    if _displayable_cell(row.get('aggregation_method')):
        text += f'，原表统计方法为{row["aggregation_method"]}'
        fields += ('aggregation_method',)
    return text + '。', fields + numeric


def _service_statements(target, rows):
    from services.multi_scope_evidence import IDENTIFIER

    fields = ('service_id', 'serial_id', 'sku_id', 'customer_id', 'inspection', 'action', 'service_contract_id')
    sites = [(index, row) for index, row in rows.items()
             if isinstance(row.get('service_id'), str) and row['service_id'].casefold() == target
             and all(_displayable_cell(row.get(field)) for field in fields)
             and all(IDENTIFIER.fullmatch(row[field]) for field in (*fields[:4], fields[-1]))]
    if len(sites) != 1:
        return []
    index, site = sites[0]
    statement = (f'工单 {site["service_id"]} 的现场设备为 {site["sku_id"]}，序列号 {site["serial_id"]}，'
                 f'现场客户为 {site["customer_id"]}。现场检查记录为{site["inspection"]}，'
                 f'处置记录为{site["action"]}，登记合同为 {site["service_contract_id"]}。')
    records = [(index, statement, fields, 'service-site')]
    contracts = [(position, row) for position, row in rows.items()
                 if row.get('contract_id') == site['service_contract_id'] and position != index]
    if len(contracts) != 1:
        return records
    contract_index, contract = contracts[0]
    buyer, sellers = contract.get('buyer_id'), contract.get('seller_ids')
    parties = sellers if isinstance(sellers, list) else [sellers]
    if (not parties or len(parties) > 8 or not all(isinstance(party, str) and IDENTIFIER.fullmatch(party)
                                                    for party in [buyer, *parties])):
        return records
    fields = ('contract_id', 'buyer_id', 'seller_ids')
    statement = f'服务合同 {contract["contract_id"]} 的签约买方为 {buyer}，签约供方为 {"、".join(parties)}'
    if _displayable_cell(contract.get('settlement')):
        statement += f'，合同结算条款为{contract["settlement"]}'
        fields += ('settlement',)
    records.append((contract_index, statement + '。', fields, 'service-contract'))
    return records


def _contract_parties(row, fields):
    from services.multi_scope_evidence import IDENTIFIER

    registers = {}
    for field in fields:
        value = row.get(field)
        if value is None or value == '':
            continue
        if isinstance(value, str) and value.startswith('['):
            try:
                value = json.loads(value)
            except ValueError:
                return (), ()
        values = value if isinstance(value, list) else [value]
        if not 1 <= len(values) <= 8 or not all(isinstance(item, str) and IDENTIFIER.fullmatch(item) for item in values):
            return (), ()
        registers[field] = tuple(dict.fromkeys(values))
    groups = sorted(registers.values(), key=len, reverse=True)
    if not groups or any(not set(group).issubset(groups[0]) for group in groups[1:]):
        return (), ()
    return groups[0], tuple(registers)


def _append_service_contract_parties(presentation, sources, cited_indices, input_to_model, trace, call):
    from services.multi_scope_evidence import IDENTIFIER, _source_row

    roles = {}
    for index, source in enumerate(sources, 1):
        role = source.provenance.get('serviceContractBuyerRole')
        if role in ('contract', 'workOrder'):
            roles.setdefault(role, []).append(index)
    if any(len(roles.get(role, ())) != 1 for role in ('contract', 'workOrder')):
        return
    contract_index = roles['contract'][0]
    order_index = roles['workOrder'][0]
    if not {contract_index, order_index}.intersection(cited_indices):
        return
    contract_source, order_source = sources[contract_index - 1], sources[order_index - 1]
    if (contract_source.dataset_id, contract_source.data_version, contract_source.index_version) != (
            order_source.dataset_id, order_source.data_version, order_source.index_version):
        return
    contract, contract_facts = _source_row(contract_source, allow_single=True)
    order, order_facts = _source_row(order_source, allow_single=True)
    order_fields = ('service_contract_id', 'service_id', 'customer_id')
    if (not all(isinstance(order.get(field), str) and IDENTIFIER.fullmatch(order[field]) for field in order_fields)
            or order['service_contract_id'] != contract.get('contract_id')):
        return
    buyers, buyer_fields = _contract_parties(contract, ('buyer_id', 'buyer_ids_json'))
    providers, provider_fields = _contract_parties(contract, ('seller_id', 'seller_ids', 'seller_ids_json'))
    if not buyers or not providers:
        return
    try:
        contract_link = _native_link(contract_source, trace, call)
        order_link = _native_link(order_source, trace, call)
    except (KeyError, TypeError, ValueError):
        return
    contract_link.update(sourceIndex=contract_index,
                         factIds=[contract_facts[field] for field in ('contract_id', *buyer_fields, *provider_fields)],
                         referenceRole='exact-contract-party-fields')
    text = f'合同 {contract["contract_id"]} 的登记买方为 {"、".join(buyers)}，签约服务供方为 {"、".join(providers)}。'
    order_text = f'工单 {order["service_id"]} 对应合同 {order["service_contract_id"]}，登记的受服务客户为 {order["customer_id"]}'
    for field, label in (('sku_id', '设备型号标识'), ('serial_id', '设备序列号')):
        if isinstance(order.get(field), str) and IDENTIFIER.fullmatch(order[field]):
            order_text += f'，{label}为 {order[field]}'
            order_fields += (field,)
    order_link.update(sourceIndex=order_index, factIds=[order_facts[field] for field in order_fields],
                      referenceRole='exact-work-order-party-fields')
    _append_verified_claim(presentation, text, [contract_link], sources, input_to_model, 'verified-contract-party-input', '\n\n')
    _append_verified_claim(presentation, order_text + '。', [order_link], sources, input_to_model, 'verified-work-order-party-input')


def _release_statement(row, target):
    from services.multi_scope_evidence import IDENTIFIER, _count, _signed_at

    fields = ('finished_batch_id', 'manufacturing_batch_id', 'qc_id', 'released_at', 'final_good', 'unit')
    if not set(fields) <= row.keys():
        return None
    batch, manufacturing, inspection, instant, quantity, unit = (row[field] for field in fields)
    if (not all(isinstance(value, str) and IDENTIFIER.fullmatch(value) for value in (batch, manufacturing, inspection))
            or batch.casefold() != target or not _displayable_cell(unit)):
        return None
    try:
        _signed_at(instant)
        count = _count(quantity)
    except (InvalidOperation, TypeError, ValueError):
        return None
    return (f'成品批 {batch} 对应制造批 {manufacturing}，原表放行时刻 {instant}，放行合格数 {count}{unit}，终检单 {inspection}。',
            fields)


def _append_business_review(question, presentation, sources, cited_indices, input_to_model, trace, call):
    from services.multi_scope_evidence import IDENTIFIER, _signed_at, _single_review_topic, _source_row

    topic = _single_review_topic(question)
    if topic not in ('放行终检', '工单合同') or re.search(r'排除|剔除|不含|不引用|不用|不要用', question):
        return
    named = {match.group().casefold() for match in IDENTIFIER.finditer(question)}
    if len(named) != 1:
        return
    target, = named
    evidence = {index: _source_row(source, allow_single=True) for index, source in enumerate(sources, 1)}
    rows = {index: row for index, (row, _) in evidence.items()}
    if topic == '工单合同':
        records = _service_statements(target, rows)
    else:
        records = []
        releases = {}
        for index, row in rows.items():
            release = _release_statement(row, target)
            if release:
                text, fields = release
                if text not in releases or index in cited_indices:
                    releases[text] = (index, text, fields, 'production-release')
            if not isinstance(row.get('object_id'), str) or row['object_id'].casefold() != target:
                continue
            category = row.get('qc_type')
            if not _displayable_cell(category) or not re.search(r'终检|final', category, re.I):
                continue
            statement = _inspection_statement(sources[index-1], row, '成品批')
            if statement and 'quantity_checked' in statement[1]:
                text, fields = statement
                records.append((index, f'原表类别为{category}。{text}', (*fields, 'qc_type'), 'final-inspection'))
        if len(releases) == 1:
            records.extend(releases.values())
    verified = []
    for index, statement, fields, role in records:
        try:
            if role == 'final-inspection':
                _signed_at(rows[index]['measured_at'])
            link = _native_link(sources[index-1], trace, call)
        except (KeyError, TypeError, ValueError):
            continue
        link.update(sourceIndex=index, factIds=[evidence[index][1][field] for field in fields], referenceRole=f'exact-{role}-fields')
        verified.append((statement, link, role))
    if not {link['sourceIndex'] for _, link, _ in verified}.intersection(cited_indices):
        return
    if topic == '工单合同':
        if verified[0][2] != 'service-site':
            return
        heading = '\n\n原表服务核对。退款状态需对应退款凭证。\n'
    else:
        roles = {role for _, _, role in verified}
        if 'final-inspection' in roles:
            heading = '\n\n原表终检核对。单次终检的检查量和实测值仅覆盖本条记录，长期运行需相应记录。\n'
            if 'production-release' not in roles:
                heading += '制造放行需另核对同批生产记录。\n'
        else:
            heading = '\n\n原表制造放行核对。终检测项、检查量及长期运行情况需相应记录。\n'
    for statement, link, role in verified:
        _append_verified_claim(presentation, statement, [link], sources, input_to_model, f'verified-{role}-input', heading)
        heading = '\n'


def _append_inspection_handoff(presentation, sources, cited_indices, input_to_model, trace, call):
    from services.multi_scope_evidence import IDENTIFIER, _source_row

    matching = [index for index in cited_indices if sources[index-1].provenance.get('finalInspectionHandoffRecord')]
    if len(matching) != 1:
        return
    index = matching[0]
    source = sources[index-1]
    row, facts = _source_row(source, allow_single=True)
    labels = ('qc_id','object_id','item','aggregation_method','quantity_checked_unit','unit')
    measurements = ('lower_limit','min_value','actual_value','max_value','upper_limit')
    counts = ('qualified_count','quantity_checked')
    if not all(_displayable_cell(row.get(field)) for field in labels) or not set((*measurements, *counts)) <= row.keys():
        return
    if not all(IDENTIFIER.fullmatch(row[field]) for field in ('qc_id','object_id')):
        return
    try:
        lower, minimum, actual, maximum, upper = (Decimal(str(row[field])) for field in measurements)
        qualified, checked = (Decimal(str(row[field])) for field in counts)
        if not all(value.is_finite() for value in (lower, minimum, actual, maximum, upper, qualified, checked)):
            return
        if lower > upper or not (minimum <= actual <= maximum) or not (0 <= qualified <= checked):
            return
        link = _native_link(source, trace, call)
    except (InvalidOperation, KeyError, TypeError, ValueError):
        return
    unit = row['unit']
    count_unit = row['quantity_checked_unit']
    statement = (f'成品批 {row["object_id"]} 的终检记录 {row["qc_id"]}，测项为{row["item"]}。'
                 f'检查 {checked} {count_unit}，合格 {qualified} {count_unit}。原表统计方法为{row["aggregation_method"]}，'
                 f'汇总值为 {actual} {unit}。实测区间为 {minimum} 至 {maximum} {unit}，'
                 f'判定上下限为 {lower} 至 {upper} {unit}。')
    link.update(sourceIndex=index, factIds=[facts[field] for field in (*labels,*measurements,*counts)],
                referenceRole='exact-inspection-handoff-fields')
    _append_verified_claim(presentation, statement, [link], sources, input_to_model,
                           'verified-inspection-handoff-input', '\n\n原表终检交接记录。\n')


def _user_text_sha(call, text):
    text_sha = hashlib.sha256(text.encode()).hexdigest()
    return text_sha if any(part.get('textSha256') == text_sha for message in call['request']['messages']
                           if message['role'] == 'user' and isinstance(message['content'], list)
                           for part in message['content']) else None


def _fulfillment_parts(question, sources, call):
    from services.multi_scope_evidence import IDENTIFIER, _count, _signed_at, _single_review_topic, _source_row
    from services.multi_scope_evidence import computation_prompt, fulfillment

    named = {match.group().casefold() for match in IDENTIFIER.finditer(question)}
    if (_single_review_topic(question) != '订单签收' or len(named) != 1
            or re.search(r'排除|剔除|不含|不引用|不用|不要用', question)):
        return None
    calculations = fulfillment(question, sources)
    reconciliation = next((fact for fact in calculations if fact['operation'] == 'deadline_receipt_sum'), None)
    computation_sha = None
    if reconciliation:
        computation_sha = _user_text_sha(call, computation_prompt(question, sources))
        require(computation_sha, 'CLAIM_FULFILLMENT_COMPUTATIONS_NOT_TRANSMITTED')
    commitment_fields = ('order_line_id', 'quantity', 'unit', 'due_at')
    receipt_fields = ('order_line_id', 'delivery_line_id', 'signed_quantity', 'unit', 'signed_at')
    records, shown = [], set()
    has_commitment = False
    for index, source in enumerate(sources, 1):
        row, facts = _source_row(source, allow_single=True)
        order = row.get('order_line_id')
        if not isinstance(order, str) or order.casefold() not in named or not IDENTIFIER.fullmatch(order):
            continue
        commitment = set(commitment_fields) <= row.keys()
        has_commitment |= commitment
        if reconciliation and index not in reconciliation['sourceIndices']:
            continue
        fields = commitment_fields if commitment else receipt_fields
        if not set(fields) <= row.keys() or not _displayable_cell(row['unit']):
            continue
        unit = row['unit']
        if commitment:
            _signed_at(row['due_at'])
            text = f'订单行 {order} 承诺 {_count(row["quantity"])}{unit}，交期 {row["due_at"]}。'
        else:
            _signed_at(row['signed_at'])
            delivery = row['delivery_line_id']
            if not isinstance(delivery, str) or not IDENTIFIER.fullmatch(delivery):
                continue
            text = f'交付行 {delivery}（订单行 {order}）于 {row["signed_at"]} 签收 {_count(row["signed_quantity"])}{unit}。'
        if text not in shown:
            shown.add(text)
            records.append((index, text, [facts[field] for field in fields]))
    if reconciliation:
        require({index for index, _, _ in records} == set(reconciliation['sourceIndices']),
                'CLAIM_FULFILLMENT_SOURCE_ROWS_INCOMPLETE')
    elif has_commitment or not records:
        return None
    return records, calculations, computation_sha


def finish_fulfillment(question, answer, result, sources, trace, *, allow_prose):
    """Retain signed rows and calculations even when the model's narrative is withheld."""
    if not trace or not sources or any(not source.evidence_bundle or is_user(source) for source in sources):
        return None
    calls = trace.data.get('modelCalls', [])
    if not calls or calls[0].get('status') != 'response_received' or calls[0].get('purpose'):
        return None
    call = calls[0]
    parts = _fulfillment_parts(question, sources, call)
    if parts is None:
        return None
    require(hashlib.sha256(question.encode()).hexdigest() == trace.data['questionSha256'], 'CLAIM_FULFILLMENT_QUESTION_CHANGED')
    records, operations, computation_sha = parts
    _, input_to_model = _model_slots(sources, call)
    native_links = {index: _native_link(sources[index-1], trace, call) for index, _, _ in records}
    presentation = None
    if allow_prose:
        try:
            presentation = _compose(question, answer, result, sources, [], trace)
        except (KeyError, TypeError, ValueError):
            pass
    if presentation is None:
        proof = dict(recipeVersion='verified-fulfillment-1', modelCallId=call['callId'],
                     modelRequestBodySha256=call['requestBodySha256'], modelToFinalSourceIndices={})
        presentation = dict(answer='', claims=[], sources=[], presentationProof=proof,
                            modelInterpretationVerified=False, generationStatus='partial',
                            notice='已交付原表签收依据。模型解释未纳入本次答复。')
    presentation['presentationProof']['fulfillmentModelCallId'] = call['callId']
    if computation_sha:
        presentation['presentationProof']['multiScopeComputationsSha256'] = computation_sha
    heading = '\n\n原表签收核对。\n' if presentation['answer'] else ''
    if not operations:
        heading += '当前来源缺少订单承诺和交期，无法核对是否按期收齐或计算提前时间。\n'
        presentation['generationStatus'] = 'partial'
    for index, statement, facts in records:
        link = dict(native_links[index], sourceIndex=index, factIds=facts, referenceRole='exact-fulfillment-fields')
        _append_verified_claim(presentation, statement, [link], sources, input_to_model, 'verified-fulfillment-input', heading)
        heading = '\n'
    for operation in operations:
        links = []
        for index in operation['sourceIndices']:
            facts = [fact for fact in operation['factIds'] if fact in sources[index-1].fact_ids]
            require(facts, 'CLAIM_FULFILLMENT_CALCULATION_FACTS_MISSING')
            links.append(dict(native_links[index], sourceIndex=index, factIds=facts, referenceRole='fulfillment-calculation-input'))
        value = operation['exactValue'] + operation['unit']
        if operation['operation'] == 'deadline_receipt_sum':
            state = '已达到承诺数量' if operation['orderFulfilledAtDeadline'] else '现有签收凭证不足以确认收齐'
            statement = f'按交期核算签收合计 {value}，{state}。'
        else:
            direction = '提前' if operation['early'] else '晚于交期'
            statement = ('累计达到承诺数量的时刻与交期相同。' if operation['exactValue'] == '0'
                         else f'累计达到承诺数量的时刻{direction} {value}。')
        _append_verified_claim(presentation, statement, links, sources, input_to_model, 'verified-fulfillment-calculation',
                               operation={**operation, 'inputSourceIndices': operation['sourceIndices'],
                                          'sourceIndices': [presentation['presentationProof']['modelToFinalSourceIndices']
                                                            [str(input_to_model[index])] for index in operation['sourceIndices']]})
    presentation['presentationProof']['finalAnswerSha256'] = hashlib.sha256(presentation['answer'].encode()).hexdigest()
    trace.event('fulfillmentPresentation', presentation)
    return presentation


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
    if trace.data.get('scopes'):
        from services.multi_scope_evidence import computation_prompt
        view = computation_prompt(question,sources)
        if view:
            view_sha = _user_text_sha(call, view)
            require(view_sha,
                    'CLAIM_PROSE_MULTISCOPE_COMPUTATIONS_NOT_TRANSMITTED')
            quantity_views['multiScopeComputationsSha256'] = view_sha
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
        presentationTransforms=display_edits,presentationProof={**proof, 'modelToFinalSourceIndices': dict(proof['modelToFinalSourceIndices'])})
    _append_material_receipts(presentation, sources, indices, input_to_model, trace, call)
    _append_service_contract_parties(presentation, sources, indices, input_to_model, trace, call)
    _append_business_review(question, presentation, sources, indices, input_to_model, trace, call)
    _append_record_comparison(question, presentation, sources, indices, input_to_model, trace, call)
    _append_inspection_handoff(presentation, sources, indices, input_to_model, trace, call)
    return presentation


def verify_presentation(question,answer,result,sources,image_parts,trace,presentation):
    """Revalidate native inputs and reject any unregistered final-output change."""
    expected=_compose(question,answer,result,sources,image_parts,trace)
    supplied=dict(presentation);previous=supplied.pop('finalClaimCheck',None)
    require(same(supplied,expected),'CLAIM_PROSE_FINAL_PRESENTATION_CHANGED')
    final=expected['answer'];claims=expected['claims']
    require(claims and claims[0]['answerStart']==len(HEADER) and claims[-1]['answerEnd']==len(final),
            'CLAIM_PROSE_FINAL_CLAIM_SPAN_CHANGED')
    previous_end = len(HEADER)
    for claim in claims:
        start,end=claim['answerStart'],claim['answerEnd']
        require(claim['offsetUnit']=='unicode-code-points' and previous_end<=start<end<=len(final)
                and final[start:end]==claim['text'],'CLAIM_PROSE_FINAL_CLAIM_SPAN_CHANGED')
        previous_end=end
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
