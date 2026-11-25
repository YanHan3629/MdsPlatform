"""Verified engineering properties and source-linked model interpretations."""
import base64,hashlib,json,re,uuid
from core.request_budget import max_image_pixels
from services.answer_lineage import Catalogue,LineageError,canonical,require,render,verify_presentation,same
from services.read_receipt import digest
from services.user_input_evidence import is_user,has_user,verify as verify_user,verify_context,question_matches,citation_allowed

VERSION='engineering-answer-lineage-2026-11-22.2'
CITATION=re.compile(r'\[来源\s*(\d+)\]')
VISUAL_HEADER='图像观察（当前模型，语义尚未认证）：\n'
PROPERTY_EXPLANATION_HEADER = '适用范围与依据（模型解释）：\n'
PROPERTY_EXPLANATION_GUIDANCE = (
    '\n系统会另行列出经核对的工程属性、数值和单位。请按原问说明当前资料能用于什么、'
    '还缺哪些字段或核验步骤。区分图纸声明、程序计算、实物测量和正式放行。'
    '解释每项理由及其与所问业务的关系，保留原资料限制，不自行推算或补写公差、物理单位及适用许可。'
    '顶点数、面数或不同文件面数相等只说明记录数量，不能据此推断闭合性、流形性或拓扑完整性。'
    '只有当前输入包含对应的实际拓扑检验结果时，才能在该检验范围内说明拓扑结论；'
    '未提供的边邻接、边界边等检查应列为待核项，不得以“基本闭合”或“大体完整”等措辞替代检验。'
    '直接写解释，每段注明支持该段的 [来源n]，无需重列属性值。'
    '开场、结论、列表和边界说明也逐段保留引用；不要另加未带引用的引言或总结。'
    '如需标题，使用独立 Markdown 标题并在紧接的正文段落中引用。'
)
PROPERTY_CITATION_REVISION_GUIDANCE = (
    '\n上一稿的工程解释包含未带引用的段落。请根据同一资料重新完整回答原问。'
    '每个正文段、列表项、开场和结论分别注明支持它的 [来源n]；也可省去引言和总结。'
    '只保留已有依据的解释及限制，不补数值、公差、实测结论或正式放行许可。'
    '标题如单独成段，使用 Markdown 标题并紧接带引用的正文。'
    '输入中另列上一稿实际缺引用或引用格式有误的定位片段；片段未通过核验，不是事实或新增证据。'
    '对照原资料修订这些段落，也检查其余正文；不要把定位编号当成业务数量，不能仅添加任意引用代替依据核对。'
)
PROPERTY_APPROVAL_ASSERTION = re.compile(
    r'(?<![不未无])(?:'
    r'(?:可以|能够|足以|[可能](?=直接|用于|作为|用作))[^，。；！？\n]{0,10}'
    r'(?:作为|用作|用于|支撑|满足|直接|进行|正式)|已(?:经)?(?:完成|获准|批准|通过))'
    r'[^，。；！？\n]{0,16}(?:生产放行|制造公差|加工验收|正式验收)'
)
PROPERTY_SECTION_HEADING = re.compile(r'(?:#{1,6}\s+[^\n\[\]。；！？]+|\*\*[^\n*\[\]。；！？]+\*\*)[:：]?')
DRAWING_OBSERVATION_SCHEMA = {
    'type': 'object', 'properties': {'observation': {'type': 'string'}},
    'required': ['observation'], 'additionalProperties': False,
}


def drawing_review_image_index(sources, image_parts):
    """A drawing review has one registered whole view and properties from that same SVG."""
    if len(sources) < 2 or len(image_parts) != 1 or has_user(sources):
        return None
    visual = [(index, source) for index, source in enumerate(sources, 1)
              if source.provenance.get('engineeringVisualRead')]
    if len(visual) != 1:
        return None
    index, view = visual[0]
    if (not view.provenance.get('svgDrawingReviewRecord') or not view.provenance.get('engineeringFullView')
            or view.source_id != image_parts[0].get('sourceId') or not view.asset_id
            or not view.provenance.get('sourceSha256')):
        return None
    if all(source.original_format == 'SVG' and source.provenance.get('engineeringReadVersion')
           and source.asset_id == view.asset_id and source.data_version == view.data_version
           and source.index_version == view.index_version
           and source.provenance.get('sourceSha256') == view.provenance['sourceSha256'] for source in sources):
        return index


def _drawing_observation(raw, index):
    try:
        fields = json.loads(raw, object_pairs_hook=tuple)
    except (TypeError, ValueError) as error:
        raise LineageError('CLAIM_DRAWING_OBSERVATION_RESPONSE_INVALID') from error
    require(isinstance(fields, tuple) and len(fields) == 1 and fields[0][0] == 'observation'
            and isinstance(fields[0][1], str) and fields[0][1].strip(), 'CLAIM_DRAWING_OBSERVATION_RESPONSE_INVALID')
    text = fields[0][1].strip()
    cited = {int(value) for value in CITATION.findall(text)}
    prose = CITATION.sub('', text)
    require(cited <= {index} and prose.strip() and '[来源' not in prose
            and not re.search(r'程序几何计算|几何计算所得|原始字段|\bmetadata\b', prose, re.I)
            and not PROPERTY_APPROVAL_ASSERTION.search(prose), 'CLAIM_DRAWING_OBSERVATION_SCOPE_CHANGED')
    return (text, False) if cited else (f'[来源{index}] {text}', True)


def check_output(answer, sources, image_parts, *, truncated=False):
    from services.engineering_output_check import validate

    index = drawing_review_image_index(sources, image_parts)
    if index is None or truncated:
        return validate(answer, sources, truncated=truncated)
    try:
        draft, _ = _drawing_observation(answer, index)
        require(_svg_text_observation_supported(draft, sources, [index]), 'CLAIM_DRAWING_OBSERVATION_HAS_QUANTITIES')
    except LineageError as error:
        checked = validate('', sources)
        return {**checked, 'reasons': [str(error)]}
    return validate(draft, sources)


SVG_PROPERTY_VISUAL_GUIDANCE = (
    '\n本次同时读取了同一 SVG 的源属性和已注册完整图像。图像观察引用实际图像来源，'
    '属性声明引用对应属性来源；结合两类证据回答原业务问题。保留简化及适用范围声明，'
    '描述数量时先明确计数对象是分组、通道、独立边界线还是源路径；一个分组可能包含多条边界，不能用分组数代替独立线段数。'
    '逐条对照登记的路径、端点与完整图像后再报告对应数量；无法核实的数量保持待核，同时回答可确认的形状和用途。'
    '区分图纸声明、画面观察和实物测量，不把图纸直接升级为生产放行许可。'
)


def svg_property_visual_index(sources, image_parts):
    if len(sources) < 2 or len(image_parts) != 1 or has_user(sources):
        return None
    visual = [(index, source) for index, source in enumerate(sources, 1)
              if source.provenance.get('engineeringVisualRead')]
    if len(visual) != 1:
        return None
    index, view = visual[0]
    if (not view.provenance.get('engineeringFullView')
            or view.provenance.get('svgDrawingReviewRecord')
            or view.source_id != image_parts[0].get('sourceId') or not view.asset_id
            or not view.provenance.get('sourceSha256')
            or not any(source.fact_ids for source in sources if source is not view)):
        return None
    for source in sources:
        if (source.original_format != 'SVG' or not source.provenance.get('engineeringReadVersion')
                or source.asset_id != view.asset_id or source.data_version != view.data_version
                or source.index_version != view.index_version or source.logical_path != view.logical_path
                or source.provenance.get('sourceSha256') != view.provenance['sourceSha256']
                or source.provenance.get('engineeringLocalView')
                or source.provenance.get('engineeringVisualPropertyRecipe') != 'svg-properties-and-view-1'):
            return None
        try:
            context = json.loads(source.model_context or '{}')
        except (ValueError, TypeError):
            return None
        if not isinstance(context, dict) or context.get('engineeringVisualPropertyRecipe') != 'svg-properties-and-view-1':
            return None
    return index


def requests_property_explanation(question, sources, image_parts):
    from services.business_answer_guard import permits_cited_blocks
    from services.engineering_evidence_plan import checks_geometry_applicability, engineering_question_scope

    if not sources or not permits_cited_blocks(question):
        return False
    if image_parts:
        return svg_property_visual_index(sources, image_parts) is not None
    if (not all(source.provenance.get('engineeringReadVersion')
                       and not source.provenance.get('engineeringVisualRead') and not is_user(source)
                       for source in sources)):
        return False
    for source in sources:
        scope = engineering_question_scope(question, source.logical_path)
        if checks_geometry_applicability(source.original_format, scope):
            return True
        if source.original_format == 'SVG' and re.search(r'适用边界|生产放行|放行(?:依据|条件|要求)|仅供审阅', scope):
            return True
    return False


def _property_explanation(question, raw, sources, trace, call, presentation, image_parts=()):
    from services.business_answer_guard import validate as validate_quantities
    from services.engineering_output_check import validate as validate_engineering
    from services.prose_answer_lineage import _model_slots, _native_link

    require(hashlib.sha256(question.encode()).hexdigest() == trace.data['questionSha256'],
            'CLAIM_ENGINEERING_EXPLANATION_QUESTION_CHANGED')
    by_model, _ = _model_slots(sources, call)
    require(all(by_model.get(index) == index for index in range(1, len(sources) + 1)),
            'CLAIM_ENGINEERING_EXPLANATION_SOURCE_ORDER_CHANGED')
    mapping = {source['provenance']['modelSourceIndex']: index
               for index, source in enumerate(presentation['sources'], 1)}
    paragraphs = [paragraph.strip() for paragraph in re.split(r'\n\s*\n', raw.strip())]
    cited = [bool(CITATION.search(paragraph) and CITATION.sub('', paragraph).strip())
             for paragraph in paragraphs]
    # A standalone Markdown title introduces the next paragraph. Keep it in the
    # presentation and apply the same numeric and engineering checks to its text.
    require(all(has_citation or (
                index + 1 < len(paragraphs) and cited[index + 1]
                and PROPERTY_SECTION_HEADING.fullmatch(paragraphs[index]))
                for index, has_citation in enumerate(cited))
            and all('[来源' not in CITATION.sub('', paragraph) for paragraph in paragraphs),
            'CLAIM_ENGINEERING_EXPLANATION_CITATION_REQUIRED')
    indices = sorted({int(value) for value in CITATION.findall(raw)})
    final_sources = list(presentation['sources'])
    visual_index = svg_property_visual_index(sources, image_parts) if image_parts else None
    visual_links = {}
    if image_parts:
        require(visual_index is not None and visual_index in indices,
                'CLAIM_ENGINEERING_EXPLANATION_IMAGE_REQUIRED')
        require(all(1 <= index <= len(sources) for index in indices),
                'CLAIM_ENGINEERING_EXPLANATION_SOURCE_CHANGED')
        visual_links = {link['sourceIndex']: link for link in
                        _visual_links(sources, image_parts, trace, call, [visual_index], question)}
        mapping[visual_index] = len(final_sources) + 1
        source = sources[visual_index - 1]
        final_sources.append(source.model_copy(update={'provenance':{
            **source.provenance, 'modelSourceIndex': visual_index, 'finalSourceIndex': mapping[visual_index],
            'citationScopeCheck': 'actual-image-input-binding-only'}}).model_dump(mode='json'))
    for index, source in enumerate(sources, 1):
        if (index in indices and index not in mapping
                and source.provenance.get('svgGraphicRecordRecipe') in {'svg-graphic-record-context-3','svg-graphic-record-context-4','svg-graphic-record-context-5','svg-graphic-record-context-6','svg-graphic-record-context-7'}):
            require(visual_index is not None, 'CLAIM_SVG_GRAPHIC_SUPPLEMENT_SCOPE_CHANGED')
            mapping[index] = len(final_sources) + 1
            final_sources.append(source.model_copy(update={'provenance': {
                **source.provenance, 'modelSourceIndex': index, 'finalSourceIndex': mapping[index],
                'citationScopeCheck': 'actual-native-input-binding-only',
                'svgGraphicPresentation': 'supplemental-source-1'}}).model_dump(mode='json'))
    require(all(index in mapping for index in indices), 'CLAIM_ENGINEERING_EXPLANATION_SOURCE_CHANGED')
    require(not PROPERTY_APPROVAL_ASSERTION.search(raw), 'CLAIM_ENGINEERING_UNVERIFIED_APPROVAL')
    require(validate_quantities(raw, sources)['accepted']
            and validate_engineering(raw, sources)['status'] == 'SCOPE_CHECKED',
            'CLAIM_ENGINEERING_EXPLANATION_VALUE_UNVERIFIED')
    links = []
    for index in indices:
        source = sources[index - 1]
        link = visual_links.get(index) or _native_link(source, trace, call)
        link.update(sourceIndex=mapping[index], modelSourceIndex=index, factIds=list(source.fact_ids),
                    referenceRole='actual-read-input-reference-not-semantic-entailment')
        links.append(link)
    text = CITATION.sub(lambda match: f'[来源{mapping[int(match[1])]}]', raw.strip())
    heading = presentation['answer'] + '\n\n' + PROPERTY_EXPLANATION_HEADER
    claim = {'claimId': str(uuid.uuid5(uuid.NAMESPACE_URL, canonical([VERSION, text, links]))),
             'kind': 'source_interpretation', 'text': text, 'answerStart': len(heading),
             'answerEnd': len(heading) + len(text), 'offsetUnit': 'unicode-code-points',
             'evidenceNature': 'model_interpretation',
             'verification': 'actual-input-binding-only-not-semantic-verification',
             'sourceIndices': sorted(mapping[index] for index in indices),
             'factIds': sorted({fact for link in links for fact in link['factIds']}),
             'evidenceIds': sorted({link['evidenceId'] for link in links}),
             'readIds': sorted({read for link in links for read in link['readIds']}),
             'inputLinks': links,
             'presentationProof': {'modelCallId': call['callId'], 'modelRequestBodySha256': call['requestBodySha256'],
                                   'modelResponseTextSha256': hashlib.sha256(raw.encode()).hexdigest()}}
    if visual_links:
        claim['imageIds'] = [link['imageId'] for link in visual_links.values()]
    return {**presentation, 'answer': heading + text, 'claims': [*presentation['claims'], claim],
            'sources': final_sources,
            **({'sourceIndices': [source['provenance']['modelSourceIndex'] for source in final_sources]}
               if visual_links else {}),
            'generationStatus': 'source_interpretation_unverified', 'withheldRawInterpretation': False,
            'notice': '属性值与实际来源已核对。模型解释用于说明资料范围，业务结论仍需复核。',
            'verificationScope': ('Exact property spans plus actual native and image input links; not semantic or metrology verification.'
                                  if visual_links else 'Exact property spans and actual-input links for the model interpretation.')}

def property_citation_revision_feedback(raw):
    """Locate formatting failures in the immediate previous response, not source facts."""
    if not isinstance(raw, str) or not raw.strip():
        return None
    paragraphs = [paragraph.strip() for paragraph in re.split(r'\n\s*\n', raw.strip())]
    cited = [bool(CITATION.search(paragraph) and CITATION.sub('', paragraph).strip())
             for paragraph in paragraphs]
    missing = []
    for index, paragraph in enumerate(paragraphs):
        supported_heading = (index + 1 < len(paragraphs) and cited[index + 1]
                             and PROPERTY_SECTION_HEADING.fullmatch(paragraph))
        malformed = '[来源' in CITATION.sub('', paragraph)
        if malformed or not (cited[index] or supported_heading):
            missing.append({'paragraphOrdinal': index + 1,
                            'issue': 'malformed_citation' if malformed else 'missing_citation',
                            'excerpt': paragraph[:256], 'truncated': len(paragraph) > 256})
    if not missing:
        return None
    return json.dumps({'purpose': '未通过核验的引用修订定位，不是资料、事实或新证据',
                       'instruction': '按原问题及原资料完整重答。逐段核对依据和引用；纯文本标题也需引用，或改为紧接有引用正文的Markdown标题。',
                       'paragraphs': missing[:8], 'omittedParagraphs': max(0, len(missing) - 8)},
                      ensure_ascii=False, separators=(',', ':'))


def verify_property_citation_revision(trace, calls, sources):
    """Check the planned citation retry, including its unchanged numeric prefix."""
    from services.curated_qa import _svg_retry_message_fingerprints

    require(trace is not None and len(calls) in (2, 3),
            'CLAIM_ENGINEERING_CITATION_REVISION_UNCONFIRMED')
    plans = [event['value'] for event in trace.data['events']
             if event['kind'] == 'engineeringCitationRevisionPlan']
    require(len(plans) == 1, 'CLAIM_ENGINEERING_CITATION_REVISION_PLAN_CHANGED')
    plan, = plans
    require(set(plan) == {'recipe','initialCallId','previousCallId','previousResponseId',
                         'previousCallCount','maximumCombinedOutputTokens','requestMaxTokens',
                         'contextLimit','sourcePartSha256','baseMessageFingerprints',
                         'requestMessageFingerprints','instructionSha256'},
            'CLAIM_ENGINEERING_CITATION_REVISION_PLAN_CHANGED')
    previous, revised = calls[-2:]
    root = calls[0]
    after_numeric = len(calls) == 3
    recipe = ('svg-numeric-then-citation-revision-1' if after_numeric
              else 'engineering-citation-revision-1')
    require(plan.get('recipe') == recipe and plan.get('initialCallId') == root['callId']
            and plan.get('previousCallId') == previous['callId']
            and plan.get('previousResponseId') == previous['response'].get('id')
            and type(plan.get('previousCallCount')) is int
            and plan['previousCallCount'] == len(calls) - 1
            and not root.get('purpose')
            and revised.get('purpose') == 'engineering_citation_revision',
            'CLAIM_ENGINEERING_CITATION_REVISION_PLAN_CHANGED')
    reasons = [event['value'].get('reason') for event in trace.data['events']
               if event['kind'] == 'engineeringPropertyExplanationWithheld']
    prior_reasons = (['CLAIM_ENGINEERING_EXPLANATION_VALUE_UNVERIFIED',
                      'CLAIM_ENGINEERING_EXPLANATION_CITATION_REQUIRED'] if after_numeric
                     else ['CLAIM_ENGINEERING_EXPLANATION_CITATION_REQUIRED'])
    require(reasons[:len(prior_reasons)] == prior_reasons,
            'CLAIM_ENGINEERING_CITATION_REVISION_SCOPE_CHANGED')
    if after_numeric:
        from services.prose_answer_lineage import verify_numeric_revision

        visual = [source for source in sources if source.provenance.get('engineeringVisualRead')]
        require(len(visual) == 1 and svg_property_visual_index(
                    sources, [{'sourceId': visual[0].source_id}]) is not None,
                'CLAIM_ENGINEERING_CITATION_REVISION_SCOPE_CHANGED')
        verify_numeric_revision(trace, calls[:2], sources)
    else:
        require(not previous.get('purpose'), 'CLAIM_ENGINEERING_CITATION_REVISION_SCOPE_CHANGED')
    raw = previous['response']['choices'][0]['message']['content']
    paragraphs = [paragraph.strip() for paragraph in re.split(r'\n\s*\n', raw.strip())]
    cited = [bool(CITATION.search(paragraph) and CITATION.sub('', paragraph).strip())
             for paragraph in paragraphs]
    require(not (all(has_citation or (
                index + 1 < len(paragraphs) and cited[index + 1]
                and PROPERTY_SECTION_HEADING.fullmatch(paragraphs[index]))
                for index, has_citation in enumerate(cited))
                and all('[来源' not in CITATION.sub('', paragraph) for paragraph in paragraphs)),
            'CLAIM_ENGINEERING_CITATION_REVISION_NOT_NEEDED')
    require(all(call.get('status') == 'response_received' for call in calls[:-1])
            and revised.get('status') in {'response_received', 'failed-or-transmission-unconfirmed'},
            'CLAIM_ENGINEERING_CITATION_REVISION_UNCONFIRMED')
    received_ids = [call['response'].get('id') for call in calls
                    if call.get('status') == 'response_received']
    require(all(received_ids) and len(set(received_ids)) == len(received_ids),
            'CLAIM_ENGINEERING_CITATION_REVISION_RESPONSE_CHANGED')
    first_request, second_request = previous['request'], revised['request']
    require('structured_outputs' not in first_request and 'structured_outputs' not in second_request
            and same({key:value for key,value in first_request.items() if key not in {'messages','max_tokens'}},
                     {key:value for key,value in second_request.items() if key not in {'messages','max_tokens'}}),
            'CLAIM_ENGINEERING_CITATION_REVISION_REQUEST_CHANGED')
    require(root.get('actualEvidence') and all(
                same(call.get('actualEvidence'), root['actualEvidence'])
                and call.get('expectedSourcePartSha256') == root.get('expectedSourcePartSha256')
                for call in calls)
            and root.get('expectedSourcePartSha256') == plan.get('sourcePartSha256'),
            'CLAIM_ENGINEERING_CITATION_REVISION_EVIDENCE_CHANGED')
    base_fingerprints = _svg_retry_message_fingerprints(first_request['messages'], traced=True)
    request_fingerprints = _svg_retry_message_fingerprints(second_request['messages'], traced=True)
    import copy

    feedback = property_citation_revision_feedback(raw)
    require(feedback is not None, 'CLAIM_ENGINEERING_CITATION_REVISION_NOT_NEEDED')
    expected = copy.deepcopy(first_request['messages'])
    require(expected[1]['content'] and expected[1]['content'][-1].get('type') == 'text',
            'CLAIM_ENGINEERING_CITATION_REVISION_EVIDENCE_CHANGED')
    expected[1]['content'].insert(-1, {'type': 'text', 'text': feedback,
                                     'textSha256': hashlib.sha256(feedback.encode()).hexdigest(),
                                     'characters': len(feedback)})
    expected_fingerprints = _svg_retry_message_fingerprints(expected, traced=True)
    require(same(base_fingerprints, plan.get('baseMessageFingerprints'))
            and same(request_fingerprints, plan.get('requestMessageFingerprints'))
            and same(request_fingerprints[1:], expected_fingerprints[1:]),
            'CLAIM_ENGINEERING_CITATION_REVISION_EVIDENCE_CHANGED')
    first_system, = first_request['messages'][0]['content']
    second_system, = second_request['messages'][0]['content']
    require(plan.get('instructionSha256') == hashlib.sha256(PROPERTY_CITATION_REVISION_GUIDANCE.encode()).hexdigest()
            and second_system.get('characters') == first_system.get('characters', -1) + len(PROPERTY_CITATION_REVISION_GUIDANCE),
            'CLAIM_ENGINEERING_CITATION_REVISION_INSTRUCTION_CHANGED')
    if 'text' in first_system:
        require(second_system.get('text') == first_system['text'] + PROPERTY_CITATION_REVISION_GUIDANCE,
                'CLAIM_ENGINEERING_CITATION_REVISION_INSTRUCTION_CHANGED')
    maximum = root['request'].get('max_tokens')
    limit = second_request.get('max_tokens')
    used = [call['response'].get('usage', {}).get('completion_tokens') for call in calls[:-1]]
    require(type(maximum) is int and maximum > 0 and maximum == plan.get('maximumCombinedOutputTokens')
            and all(type(value) is int and value > 0 for value in used)
            and type(limit) is int and 0 < limit <= maximum - sum(used)
            and limit == plan.get('requestMaxTokens'),
            'CLAIM_ENGINEERING_CITATION_REVISION_BUDGET_CHANGED')
    if revised['status'] == 'response_received':
        completion = revised['response'].get('usage', {}).get('completion_tokens')
        prompt = revised['response'].get('usage', {}).get('prompt_tokens')
        context = plan.get('contextLimit')
        require(type(completion) is int and 0 < completion <= limit
                and type(prompt) is int and prompt > 0 and type(context) is int
                and prompt + limit <= context,
                'CLAIM_ENGINEERING_CITATION_REVISION_BUDGET_CHANGED')
    return True


def _call(result,trace,sources):
    require(trace is not None,'CLAIM_ENGINEERING_AUDIT_REQUIRED')
    calls=trace.data.get('modelCalls',[])
    if len(calls) in (2, 3) and calls[-1].get('purpose') == 'engineering_citation_revision':
        verify_property_citation_revision(trace,calls,sources)
    elif len(calls)==2:
        from services.prose_answer_lineage import verify_numeric_revision
        verify_numeric_revision(trace,calls,sources)
    else:
        require(len(calls)==1 and not calls[0].get('purpose'),'CLAIM_ENGINEERING_MODEL_CALL_UNCONFIRMED')
    call=calls[-1];response=call.get('response',{});choices=response.get('choices',[])
    require(call.get('status')=='response_received','CLAIM_ENGINEERING_MODEL_CALL_UNCONFIRMED')
    require(response.get('id') and response['id']==result.get('id') and len(choices)==1
        and same(choices,result.get('choices')),'CLAIM_ENGINEERING_MODEL_RESPONSE_CHANGED')
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
        require(all(payload.get(key)==image.get(key) for key in ('imageId','sha256','width','height')) and image['width']*image['height']<=max_image_pixels(),
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
        if source.provenance.get('svgLimitationNoteRegion'):
            note_texts = json.loads(source.model_context).get('原图限制说明')
            require(requested and note_texts and len(parents) >= 3, 'CLAIM_SVG_NOTE_SOURCE_MISSING')
            derivation = {**requested, 'noteReadReceiptSha256': sorted(map(digest, parents[1:])),
                          'noteTextSha256': digest(note_texts)}
            require(bundle['clientDerivation']['parametersSha256'] == digest(derivation),
                    'CLAIM_SVG_NOTE_SOURCE_CHANGED')
        links.append({'sourceIndex':index,'sourceId':source.source_id,'evidenceId':source.evidence_id,
            'representationId':source.representation_id,'readIds':sorted({metadata['readId'],*[parent['readId'] for parent in parents]}),
            'imageId':image['imageId'],'promptImageSha256':image['sha256'],'sourceImageSha256':image['sourceImageSha256'],
            'identity':identity,'locator':source.locator,'renderParameters':metadata['parameters'],'renderTransform':metadata['transform'],
            'preprocessing':preprocessing,'parentClientReadReceiptSha256':sorted(digest(parent) for parent in parents),
            'modelCallId':call['callId'],'modelRequestBodySha256':call['requestBodySha256']})
    return links

def _visual_presentation(question,raw,sources,image_parts,trace,call,indices,*,prefix='',source_offset=0):
    links=_visual_links(sources,image_parts,trace,call,indices,question)
    mapping={old:new for new,old in enumerate(indices,source_offset+1)}
    for link in links:
        link['modelSourceIndex']=link['sourceIndex'];link['sourceIndex']=mapping[link['sourceIndex']]
    text=CITATION.sub(lambda match:'[来源'+str(mapping[int(match[1])])+']',raw)
    heading=prefix+VISUAL_HEADER
    answer=heading+text
    claim={'claimId':str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([VERSION,text,links]))),
        'text':text,'answerStart':len(heading),'answerEnd':len(answer),'offsetUnit':'unicode-code-points',
        'sourceIndices':list(mapping.values()),'evidenceNature':'model_interpretation',
        'verification':'actual-transmitted-image-binding-only-not-semantic-verification','kind':'visual_interpretation',
        'factIds':[],'evidenceIds':[source.evidence_id for index,source in enumerate(sources,1) if index in indices],
        'readIds':sorted({identifier for link in links for identifier in link['readIds']}),
        'imageIds':[link['imageId'] for link in links],'inputLinks':links}
    final_sources=[source.model_copy(update={'provenance':{**source.provenance,'modelSourceIndex':index,'finalSourceIndex':mapping[index],
        'citationScopeCheck':'actual-image-input-binding-only'}}).model_dump(mode='json') for index,source in enumerate(sources,1) if index in indices]
    return answer,claim,final_sources


def _mixed_observation_has_no_property_echo(raw,properties):
    from services.engineering_evidence_plan import _field_selected
    # Bind the whole observation to its leading image citation. Attribute
    # questions already have independently verified output in the field section.
    if not CITATION.match(raw.lstrip()) or re.search(r'程序几何计算|几何计算所得|原始字段|\bmetadata\b',raw,re.I):
        return False
    fields={fact['field'] for source in properties for fact in source.evidence_bundle['facts']
        if fact['factId'] in source.fact_ids}
    return not any(_field_selected(field,raw) for field in fields)


def _svg_text_observation_supported(raw, sources, indices):
    text_image_indices = {index for index, source in enumerate(sources, 1)
                          if source.provenance.get('svgLimitationNoteRegion')
                          or source.provenance.get('svgDrawingReviewRecord')}
    if not text_image_indices:
        return True
    from services.business_answer_guard import LIST_ITEM, _numbers
    from services.engineering_output_check import QUANTITY
    prose = CITATION.sub('', raw)
    return (bool(text_image_indices.intersection(indices)) and not QUANTITY.search(prose)
            and not any(_numbers(LIST_ITEM.sub('', line)) for line in prose.splitlines()))


def _property_value_catalogue(catalogue, call, receipts):
    displayed = [source for source in catalogue.sources
                 if source.get('provenance', {}).get('svgGraphicRecordRecipe') not in {'svg-graphic-record-context-3','svg-graphic-record-context-4','svg-graphic-record-context-5','svg-graphic-record-context-6','svg-graphic-record-context-7'}]
    require(displayed, 'CLAIM_SVG_GRAPHIC_PROPERTY_SOURCE_REQUIRED')
    selected = Catalogue(displayed).bind_calls([call], receipts)
    require(not selected.unsupported and selected.facts, 'CLAIM_ENGINEERING_PROPERTY_ADAPTER_UNAVAILABLE')
    return selected


def finish(question,result,sources,image_parts,trace,output_check):
    """Keep exact property claims separate from observations linked to actual images."""
    call=_call(result,trace,sources)
    mixed=has_user(sources)
    require(all((question_matches(source,question,True) if is_user(source) else source.provenance.get('validatedEngineeringQuestion')==question) for source in sources),'CLAIM_ENGINEERING_QUESTION_CHANGED')
    if mixed:
        verify_context(sources,trace,call)
        for source in sources:
            if is_user(source):verify_user(source,question,trace,call,image_parts)
    model_text=result['choices'][0]['message']['content']
    raw=model_text
    indices=sorted({int(value) for value in CITATION.findall(raw)})
    drawing_index = drawing_review_image_index(sources, image_parts)
    visual_draft=bool(image_parts) and output_check.get('status')=='SCOPE_CHECKED'
    if drawing_index is not None:
        visual_draft = False
        try:
            require(same(call['request'].get('structured_outputs'), {'json': DRAWING_OBSERVATION_SCHEMA}),
                    'CLAIM_DRAWING_OBSERVATION_SCHEMA_CHANGED')
            raw, citation_added = _drawing_observation(model_text, drawing_index)
            require(_svg_text_observation_supported(raw, sources, [drawing_index]), 'CLAIM_DRAWING_OBSERVATION_HAS_QUANTITIES')
            require(output_check.get('status') == 'SCOPE_CHECKED', 'CLAIM_DRAWING_OBSERVATION_SCOPE_REJECTED')
        except LineageError as error:
            trace.event('engineeringMixedObservationWithheld', {'reason': str(error)})
        else:
            indices = [drawing_index]
            visual_draft = True
    properties=[]
    for index,source in enumerate(sources,1):
        if source.provenance.get('engineeringReadVersion') and not source.provenance.get('engineeringVisualRead'):
            properties.append(source.model_copy(update={'provenance':{**source.provenance,'modelSourceIndex':index,
                'finalSourceIndex':len(properties)+1,'citationScopeCheck':'exact-actual-input-property-spans'}}))
    if properties:
        catalogue=Catalogue(properties)
        require(not catalogue.unsupported and catalogue.facts,'CLAIM_ENGINEERING_PROPERTY_ADAPTER_UNAVAILABLE')
        receipts=[event['value'] for event in trace.data['events'] if event['kind']=='nativeReadReceipt']
        catalogue.bind_calls([call],receipts)
        trace.event('engineeringFieldCatalogue',{'version':VERSION,'sources':catalogue.sources,'requiredFactIds':catalogue.required_fact_ids})
        presentation_catalogue = catalogue
        supplementary = [source for source in properties
                         if source.provenance.get('svgGraphicRecordRecipe') in {'svg-graphic-record-context-3','svg-graphic-record-context-4','svg-graphic-record-context-5','svg-graphic-record-context-6','svg-graphic-record-context-7'}]
        if supplementary:
            require(svg_property_visual_index(sources, image_parts) is not None,
                    'CLAIM_SVG_GRAPHIC_SUPPLEMENT_SCOPE_CHANGED')
            supplementary_fact_ids = {fact_id for source in supplementary for fact_id in source.fact_ids}
            require(supplementary_fact_ids <= set(catalogue.proofs),
                    'CLAIM_SVG_GRAPHIC_SUPPLEMENT_NOT_ACTUALLY_TRANSMITTED')
            presentation_catalogue = _property_value_catalogue(catalogue, call, receipts)
            trace.event('engineeringSupplementaryGraphicInputs', {
                'recipe': 'svg-graphic-supplemental-presentation-1',
                'modelSourceIndices': [source.provenance['modelSourceIndex'] for source in supplementary],
                'supplementaryFactCount': len(supplementary_fact_ids),
                'displayedPropertyFactCount': len(presentation_catalogue.facts),
                'completeReadCatalogueAndActualInputBindingsRetained': True})
        presentation=render(presentation_catalogue,list(presentation_catalogue.facts))
        check=verify_presentation(presentation,presentation_catalogue)
        require(check['status']=='VERIFIED_EXACT_SPANS' and not presentation['missingRequiredFactIds'],'CLAIM_ENGINEERING_PROPERTY_PRESENTATION_INCOMPLETE')
        visual=bool(image_parts)
        presentation.update(generationStatus='partial' if visual else 'source_values_verified',
            notice='所列源声明或程序几何值及引用已核对；模型解释与完整问题的语义尚未认证。'+(' 图像已入模，本次未交付未认证的图像解释。' if visual else ''),
            finalClaimCheck=check,modelInterpretationVerified=False,withheldRawInterpretation=True,
            verificationScope='Actual native property values, units, source nature and exact final spans; not physical metrology, complete question semantics or raw model prose.')
        trace.event('engineeringFieldPresentation',presentation)
        if requests_property_explanation(question, sources, image_parts):
            try:
                require(output_check['status'] == 'SCOPE_CHECKED', 'CLAIM_ENGINEERING_EXPLANATION_SCOPE_REJECTED')
                combined = _property_explanation(question, raw, sources, trace, call, presentation, image_parts)
            except (LineageError, KeyError, TypeError) as error:
                trace.event('engineeringPropertyExplanationWithheld',
                            {'reason': str(error) if isinstance(error, LineageError) else type(error).__name__})
                return {**presentation, 'generationStatus': 'partial',
                        'notice': '工程属性已核对，本次解释未通过证据核对。'}
            combined['propertyClaimCheck'] = combined.pop('finalClaimCheck')
            trace.event('engineeringPropertyExplanation', combined)
            if image_parts:
                trace.event('engineeringPropertyVisualExplanation', combined)
            return combined
        # A combined native question can show a separate observation if every
        # model citation is to an actual image and each image binding verifies.
        if visual_draft and not mixed and indices and all(
            1<=index<=len(sources) and sources[index-1].provenance.get('engineeringVisualRead') for index in indices
        ) and (drawing_index is not None or (_mixed_observation_has_no_property_echo(raw,properties)
                                             and _svg_text_observation_supported(raw,sources,indices))):
            try:
                answer,claim,visual_sources=_visual_presentation(question,raw,sources,image_parts,trace,call,indices,
                    prefix=presentation['answer']+'\n\n',source_offset=len(presentation['sources']))
            except (ValueError,KeyError,TypeError) as error:
                trace.event('engineeringMixedObservationWithheld',{'reason':str(error) if isinstance(error,LineageError) else type(error).__name__})
            else:
                if drawing_index is not None:
                    claim['presentationProof'] = {'jsonField': 'observation',
                         'modelResponseTextSha256': hashlib.sha256(model_text.encode()).hexdigest(),
                         'modelRequestBodySha256': call['requestBodySha256'], 'citationAddedForActualImage': citation_added}
                combined={**presentation,'answer':answer,'claims':[*presentation['claims'],claim],
                    'sources':[*presentation['sources'],*visual_sources],'generationStatus':'source_interpretation_unverified',
                    'withheldRawInterpretation':False,
                    'notice':'属性值和图像来源已核对。图像观察由模型生成，形状、数量及完整语义尚未认证。',
                    'verificationScope':'Exact actual-input property spans and separately linked actual-image observation; no semantic correctness claim.'}
                combined['sourceIndices']=[source['provenance']['modelSourceIndex'] for source in combined['sources']]
                combined['propertyClaimCheck']=combined.pop('finalClaimCheck')
                trace.event('engineeringMixedPresentation',combined)
                return combined
        return presentation
    require(visual_draft,'CLAIM_ENGINEERING_VISUAL_DRAFT_WITHHELD')
    require(indices and all(1<=index<=len(sources) for index in indices),'CLAIM_ENGINEERING_VISUAL_CITATION_CHANGED')
    answer,claim,final_sources=_visual_presentation(question,raw,sources,image_parts,trace,call,indices)
    presentation={'version':VERSION,'answer':answer,'claims':[claim],'sourceIndices':indices,'sources':final_sources,
        'generationStatus':'source_interpretation_unverified','modelInterpretationVerified':False,
        'notice':'图像和来源映射已核对；以下为当前模型的图像解释，语义、形状及数量判断尚未认证。',
        'verificationScope':'Actual image bytes, source/region/instance mapping and model-call linkage only; no semantic correctness claim.'}
    trace.event('engineeringVisualPresentation',presentation)
    return presentation
