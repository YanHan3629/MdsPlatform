"""Typed native-field answers through the actual QA/model/audit path.

The model chooses fact references. Only registered source projections and raw
equality results become visible data statements. This does not certify that
the complete natural-language question has been answered.
"""
import time
from datetime import datetime,timezone
from core.config import settings
from schemas.qa_resp import QAResponse,QAMeta,SourceItem,RetrievedItem
from services.answer_lineage import Catalogue,LineageError
from services.claim_protocol import VERSION,bounded_grammar,messages,finish
from services.evidence_policy import POLICY
from services.evidence_trace import current
from adapters.vllm_client import VllmClientError

def eligible(sources,images,*,task_type,status):
    return bool(sources) and not images and task_type=='general_qa' and status=='OK' and all(
        source.evidence_bundle and (source.original_format in {'JSON','JSONL'} and source.provenance.get('jsonReadVersion')
            or source.original_format=='XLSX' and source.provenance.get('tableReadVersion')
            or source.original_format=='CSV' and source.provenance.get('csvReadVersion')
            or source.original_format in {'CSV','JSON','JSONL','XLSX'} and source.provenance.get('recordReadVersion'))
        for source in sources)

def answer(service,*,question,sources,items,scope,diagnostics,started,retrieval_ms,output_tokens,temperature):
    trace=current();generation_started=time.perf_counter();result={};presentation=None;error=None
    diagnostics.update(generationPolicy=VERSION,actualContextEvidenceIds=[source.evidence_id for source in sources])
    try:
        if trace is None:raise LineageError('CLAIM_AUDIT_REQUIRED')
        for source in sources:
            if source.provenance.get('csvReadVersion') and source.provenance.get('validatedCsvQuestion')!=question:
                raise LineageError('CLAIM_CSV_QUESTION_CHANGED')
            if source.provenance.get('tableReadVersion') and source.provenance.get('validatedTableQuestion')!=question:
                raise LineageError('CLAIM_WORKSHEET_QUESTION_CHANGED')
        catalogue=Catalogue(sources)
        if catalogue.unsupported:raise LineageError('CLAIM_NATIVE_ADAPTER_UNAVAILABLE')
        from services.record_claim_view import preflight
        preflight(catalogue,question)
        if any(source.provenance.get('recordReadVersion') for source in sources):
            from services.record_claim_view import calculate,COMPARISON_VERSION
            comparisons=calculate(catalogue,question)
            trace.event('recordComparisonTools',{'version':COMPARISON_VERSION,'operations':comparisons,
                'sourceReadIds':sorted({source.evidence_bundle['readId'] for source in sources}),
                'scanReadReceiptSha256s':sorted(catalogue.required_aux_receipts),
                'scope':'Deterministic read-only comparison executed before model explanation; no new independent source.'})
        grammar=bounded_grammar(catalogue,question)
        body=messages(question,catalogue)
        trace.event('claimInputCatalogue',{'version':VERSION,'sources':catalogue.sources,
            'requiredFactIds':catalogue.required_fact_ids})
        trace.generation_inputs(sources,[])
        count=service.client.tokenize_messages(body)
        limit=min(POLICY['maxContextTokens'],int(count.get('max_model_len',POLICY['maxContextTokens'])))
        diagnostics.update(promptTokens=int(count['count']),outputReserved=output_tokens)
        if int(count['count'])+output_tokens>limit:raise LineageError('CLAIM_CONTEXT_BUDGET_EXCEEDED')
        service._get_search_client().check_scope(*scope)
        result=service.client.chat_completions(messages=body,max_tokens=output_tokens,temperature=temperature,
            presence_penalty=0.0,logprobs=True,structured_outputs=grammar)
        choices=result.get('choices',[])
        if len(choices)!=1:raise LineageError('CLAIM_MODEL_RESPONSE_UNAVAILABLE')
        choice=choices[0]
        presentation=finish(choice.get('message',{}).get('content',''),catalogue,trace.data['modelCalls'],
            question=question,truncated=choice.get('finish_reason')=='length')
        if not presentation['claims']:raise LineageError('CLAIM_NO_SUPPORTED_SELECTION')
        trace.event('claimProtocolResult',presentation)
    except VllmClientError as failure:
        # Upstream failure is not a source-validation failure. Recheck authority
        # after the wait, then let the public route classify it without raw URLs.
        if trace:trace.event('claimProtocolFailure',{'code':'MODEL_SERVICE_FAILURE','category':failure.category,'modelCallCount':len(trace.data['modelCalls'])})
        service._get_search_client().check_scope(*scope)
        raise
    except (ValueError,KeyError,TypeError) as failure:
        # Keep the real model response in the trace. Never retry via free text
        # after a schema, budget, source-integrity or final-span failure.
        error=str(failure) if isinstance(failure,LineageError) else type(failure).__name__
        presentation=None
        if trace:trace.event('claimProtocolFailure',{'code':error,'modelCallCount':len(trace.data['modelCalls'])})
    # This authorization check also covers the time spent in the tokenizer and
    # model. Authorization errors propagate and cannot be turned into values.
    service._get_search_client().check_scope(*scope)
    if presentation is None:
        final_sources=[];claims=[]
        generation_status='output_limit' if error=='CLAIM_PROTOCOL_OUTPUT_LIMIT' else 'not_requested' if error=='CLAIM_CONTEXT_BUDGET_EXCEEDED' else 'validation_failed'
        answer_text=('所需字段超过本次处理容量，请缩小记录或字段范围后重试。' if error=='CLAIM_CONTEXT_BUDGET_EXCEEDED' else
            '本次未能生成通过来源核验的字段回答，请缩小记录或字段范围后重试。')
        notice='本次未交付未经核验的数据结论。'
        diagnostics['claimProtocol']={'version':VERSION,'status':'REJECTED','reason':error,'questionCompleteness':'NOT_CERTIFIED'}
        retrieval_status='CONTEXT_EVIDENCE_NOT_FIT' if error=='CLAIM_CONTEXT_BUDGET_EXCEEDED' else 'EVIDENCE_VALIDATION_FAILED'
    else:
        final_sources=[SourceItem.model_validate(source) for source in presentation['sources']]
        claims=presentation['claims'];answer_text=presentation['answer'];retrieval_status='OK'
        generation_status='partial' if presentation['status']=='PARTIAL' else 'source_values_verified'
        notice=('本次只完成了部分来源字段核验，完整问题尚未答完。' if presentation['status']=='PARTIAL' else
            '已核对所列字段的原始值；问题中的解释、推断或尚未列出的字段仍未完成核验。')
        diagnostics['claimProtocol']={key:presentation[key] for key in ('protocolVersion','status','questionCompleteness',
            'missingRequiredFactIds','withheldInterpretations','finalClaimCheck','verificationScope')}
        if 'missingRecordComparisons' in presentation:diagnostics['claimProtocol']['missingRecordComparisons']=presentation['missingRecordComparisons']
        if trace:trace.event('finalClaims',claims)
    kept={source.evidence_id for source in final_sources}
    retained=[item for item in items if item['evidenceId'] in kept]
    diagnostics.update(finalEvidenceIds=[source.evidence_id for source in final_sources],
        finalFormats=sorted({source.original_format for source in final_sources}),answerComposition='actual-input-typed-claims')
    usage=result.get('usage',{})
    return QAResponse(answer=answer_text,answer_claims=claims,confidence=0.0,sources=final_sources,
        processing_time_ms=int((time.perf_counter()-started)*1000),timestamp=datetime.now(timezone.utc).isoformat(),
        model=result.get('model') or settings.model_name,task_type='general_qa',retrieval_mode='search_service' if retained else 'none',
        backend='vllm',notice=notice,retrieved=[RetrievedItem(asset_id=item.get('assetId'),
            logical_path=(item.get('preview') or {}).get('logicalPath'),score=item['relevance'],source_type=item['semanticSpace']) for item in retained],
        meta=QAMeta(model_name=result.get('model') or settings.model_name,image_count=0,text_count=len(sources),
            prompt_tokens=usage.get('prompt_tokens',diagnostics.get('promptTokens',0)),completion_tokens=usage.get('completion_tokens',0),
            total_tokens=usage.get('total_tokens',0),request_id=result.get('id'),retrieval_type='curated',
            retrieval_status=retrieval_status,generation_status=generation_status,evidence_selection=diagnostics,
            retrieval_ms=retrieval_ms,generation_ms=round((time.perf_counter()-generation_started)*1000,3)))
