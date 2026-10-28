import json
from fastapi import APIRouter, UploadFile, File, Form
from uuid import UUID
from schemas.search_req import TextToImageReq
from schemas.search_resp import TextToImageResp, ImageToTextResp
from schemas.professional import EvidencePack, ProfessionalSearchRequest, CandidateRequest, QueryPlan
from services.text_to_image_service import TextToImageService
from services.image_to_text_service import ImageToTextService
from services.index_registry import registry
from services.professional_retrievers import ProfessionalRetrieverSuite, _has_identifier
from services.snapshot_scope import SnapshotScope
from services.qa_query import content_query,visual_subjects,aspect_queries

router = APIRouter()


@router.post("/search/text-to-image", response_model=TextToImageResp)
def text_to_image(req: TextToImageReq):
    service = TextToImageService()
    return service.search(req)


@router.post("/search/image-to-text", response_model=ImageToTextResp)
async def image_to_text(
    datasetId: UUID = Form(...),
    versionId: UUID = Form(...),
    indexVersionId: UUID = Form(...),
    topK: int = Form(5),
    file: UploadFile = File(...)
):
    content = await file.read()
    service = ImageToTextService()
    return service.search(datasetId, versionId, indexVersionId, topK, content)


@router.post("/search/execute", response_model=EvidencePack)
@registry.serialized
def execute_professional_search(req: ProfessionalSearchRequest):
    """Execute only schema-validated, bounded and read-only retrieval steps."""
    loaded = registry.ensure_loaded(str(req.dataset_id), str(req.version_id), str(req.index_version_id))

    def visual_search(plan):
        scope = SnapshotScope.from_suite(suite)
        return TextToImageService().search_visual(loaded, plan.query_text, plan.top_k,
            allowed=lambda asset, locator: scope.allowed_unit({"assetId": asset, "metadata": {"bindings": scope.visual_bindings(asset, locator)}}, plan))

    suite = ProfessionalRetrieverSuite(
        loaded.manifest.get("indexes", []), loaded.professional_files or {}, visual_search,
        cache=loaded.professional_cache if loaded.professional_cache is not None else {},
    )
    return suite.execute(req.plan, str(req.version_id), str(req.index_version_id))


@router.get("/indexes/{dataset_id}/{version_id}/{index_version_id}/capabilities")
def index_capabilities(dataset_id: UUID, version_id: UUID, index_version_id: UUID):
    loaded = registry.ensure_capabilities(str(dataset_id), str(version_id), str(index_version_id))
    return {
        "datasetId": dataset_id,
        "versionId": version_id,
        "indexVersionId": index_version_id,
        "snapshot": {"dataVersion": str(version_id), "indexVersion": str(index_version_id)},
        "filters": loaded.scope.describe(),
        "capabilities": [{
            "semanticSpace": item.get("semanticSpace"),
            "indexType": item.get("indexType"),
            "status": item.get("status"),
            "coverage": item.get("coverage", {}),
        } for item in loaded.manifest.get("indexes", [])],
    }


@router.post("/search/candidates")
@registry.serialized
def qa_candidates(req: CandidateRequest):
    """Bounded per-format recall, with the original hit and location intact."""
    import re
    loaded = registry.ensure_loaded(str(req.dataset_id), str(req.version_id), str(req.index_version_id))
    suite = ProfessionalRetrieverSuite(loaded.manifest.get("indexes", []), loaded.professional_files or {},
        cache=loaded.professional_cache if loaded.professional_cache is not None else {})
    scope = SnapshotScope.from_suite(suite)
    known = scope.entities
    prefixes = {entity.split("-", 1)[0].lower() for entity in known if "-" in entity}
    from services.qa_query import named_sources
    named_files,file_spans=named_sources(req.question,scope.assets,known)
    missing_files=[match.group(0) for match in re.finditer(r'[A-Za-z0-9_-]+\.(?:pdf|csv|xlsx|jsonl?|png|jpe?g|webp|svg|txt|obj|step|stp)\b',req.question,re.I)
        if not any(start<=match.start()<end for start,end in file_spans)]
    if missing_files:
        return {'evidence':[],'status':'UNKNOWN_ENTITY','unknownEntities':missing_files,
            'snapshot':{'dataVersion':str(req.version_id),'indexVersion':str(req.index_version_id)}}
    mentioned = [match.group(0) for match in re.finditer(r"(?<![A-Za-z0-9])[A-Za-z]{2,12}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*", req.question)
        if not any(start<=match.start()<end for start,end in file_spans)]
    canonical = {entity.lower(): entity for entity in known}
    aliases = {str(item.get("values",{}).get("model")).lower(): entity for entity,item in known.items() if item.get("values",{}).get("model")}
    unknown = [value for value in mentioned if value.lower() not in canonical and value.lower() not in aliases
        and (value.split("-", 1)[0].lower() in prefixes or re.search(r"SKU|订单|合同|料号|序列|型号|客户",req.question,re.I))]
    if unknown:
        return {"evidence": [], "status": "UNKNOWN_ENTITY", "unknownEntities": unknown,
                "snapshot": {"dataVersion": str(req.version_id), "indexVersion": str(req.index_version_id)}}
    entities = [canonical.get(value.lower()) or aliases[value.lower()] for value in mentioned if value.lower() in canonical or value.lower() in aliases]
    # A joint identifier request (e.g. inspection + batch + SKU) must describe
    # the same matched record/binding. Explicit comparisons may retrieve each
    # object separately so that their independent evidence can be compared.
    entity_match=any if re.search(r'比较|对比|分别|各自|逐个|对照|是否(?:就是|相同|同一)|是不是(?:同一|相同)',req.question) else all
    file_bound={identifier for asset_id in named_files for binding in scope.assets[asset_id].get('bindings',[])
        for identifier in [*binding.get('entity_ids',[]),binding.get('sku_id')] if identifier}
    strict_named=any(re.search(r'(?:只|仅)(?:依据|参考|基于|使用|用)(?:文件|资料)?\s*$',req.question[max(0,start-20):start]) for start,_ in file_spans)
    separate_named_targets=bool(named_files and entities and entity_match is any and not strict_named and not set(entities)&file_bound)
    from services.qa_query import temporal_role_comparison
    if temporal_role_comparison(req.question,file_spans,entities):
        hits=[] if strict_named else scope.temporal_role_evidence(named_files,entities,req.question,str(req.version_id),str(req.index_version_id),req.formats)
        return {'evidence':[item.model_dump() for item in hits],'status':'OK',
            'snapshot':{'dataVersion':str(req.version_id),'indexVersion':str(req.index_version_id)}}
    def named_target_allowed(asset_id,entity_ok):
        if not named_files:return entity_ok
        return (asset_id in named_files or entity_ok) if separate_named_targets else (asset_id in named_files and entity_ok)
    semantic_question=content_query(req.question)
    expanded_question=semantic_question
    field_aliases={'model':['型号'],'width_mm':['宽度','尺寸'],'height_mm':['高度','尺寸'],'depth_mm':['深度','尺寸'],
        'sales_price_cny_tax_included':['价格','售价','含税'],'total_volume_l':['容积'],'revision':['版本','工程版本']}
    available_fields={field['name'] for table in scope.tables.values() if table['scope']=='corpus' for field in table['fields']}
    from services.qa_query import requested_fields
    expanded_fields=requested_fields(req.question,available_fields,field_aliases)
    if re.search(r'规格|产品参数',req.question):
        kinds={known[entity].get('entity_type') for entity in entities}
        spec_fields=['spec','part_class','revision'] if 'parts' in kinds or re.search(r'零件|料号',req.question) else ['model','width_mm','height_mm','depth_mm','total_volume_l']
        expanded_fields.extend(name for name in spec_fields if name in available_fields)
    text_aspect,visual_aspect=aspect_queries(semantic_question,expanded_fields,field_aliases)
    expanded_question=text_aspect+' '+ ' '.join(sorted(set(expanded_fields)))
    visual_question=visual_aspect+' '+' '.join(visual_subjects(entities,known))
    from services.qa_query import requests_product_picture
    product_picture=requests_product_picture(req.question,entities,known)
    formats = req.formats or sorted({a["originalFormat"] for a in scope.assets.values() if a["scope"] == "corpus"})
    evidence, counts = [], {}
    visual_service=TextToImageService()
    visual_query=None
    text_query=None
    for fmt in formats:
        for kind in ["text", "visual"]:
            if not any(a["scope"] == "corpus" and a["originalFormat"] == fmt and kind in a["contentTypes"] for a in scope.assets.values()):
                continue
            plan = QueryPlan(taskIntent="qa_candidates", retrievers=[kind], queryText=expanded_question if kind=='text' else visual_question,
                contentType=kind, formats=[fmt], topK=req.per_format)
            # A cited entity must occur in the matched text or have an explicit
            # record/whole-image binding, not just somewhere else in the file.
            if entities:
                plan = plan.model_copy(update={"object_conditions": []})
            if kind == "text":
                retriever = scope.scoped_retriever(suite._prepare("text"), "text", plan)
                if text_query is None and retriever.dense is not None:
                    from services.text_embedding import encode_query,MODEL_VERSION
                    from services.professional_retrievers import _hash_vector
                    text_query=(encode_query(expanded_question) if retriever.dense_model_version==MODEL_VERSION else _hash_vector(expanded_question,retriever.dense.d).reshape(1,-1))
                retriever.encoded_query=text_query
                allowed = retriever.allowed_document
                retriever.allowed_document = lambda unit, allowed=allowed: allowed(unit) and named_target_allowed(unit.get('asset_id') or unit.get('assetId'),not entities or entity_match(
                    _has_identifier(unit.get("payload", ""),entity) or (
                        known[entity].get('values',{}).get('model') and
                        _has_identifier(unit.get('payload',''),known[entity]['values']['model'])) for entity in entities)
                    and (entity_match is any or scope.matches_qa_entities(unit,entities)))
                general_allowed=retriever.allowed_document
                hits = retriever.search(plan, str(req.version_id), str(req.index_version_id))
                if named_files:
                    targeted=[]
                    for asset_id in named_files:
                        if scope.assets[asset_id]['originalFormat']!=fmt:continue
                        retriever.allowed_document=lambda unit,asset_id=asset_id: (unit.get('asset_id') or unit.get('assetId'))==asset_id and general_allowed(unit)
                        targeted.extend(retriever.search(plan.model_copy(update={'top_k':1}),str(req.version_id),str(req.index_version_id)))
                    seen=set();hits=[hit for hit in [*targeted,*hits] if not (hit.evidenceId in seen or seen.add(hit.evidenceId))][:req.per_format]
            else:
                hits = []
                def allowed_visual(asset_id, locator):
                    asset = scope.assets.get(asset_id, {})
                    if product_picture and not scope.is_product_picture(asset_id,locator,entities):return False
                    return scope.allowed_asset(asset_id, plan) and named_target_allowed(asset_id,not entities or any(entity_match(
                        entity in b.get("entity_ids", []) or entity == b.get("sku_id")
                        for entity in entities) and (entity_match is any or scope.matches_qa_entities({'metadata':{'bindings':[b]}},entities))
                        for b in scope.visual_bindings(asset_id, locator)))
                if visual_query is None:
                    visual_query=visual_service.encoder.encode_text(visual_question)
                items = visual_service.search_visual(loaded, visual_question, req.per_format, allowed=allowed_visual,encoded_query=visual_query)
                from services.professional_retrievers import _evidence
                for rank, item in enumerate(items, 1):
                    hits.append(_evidence(version=str(req.version_id), index_version=str(req.index_version_id),
                        space="visual.clip-cn-v1", asset_id=item["assetId"], locator=item["locator"],
                        facts={"visualOnly": True}, score=item["score"], score_type="clip_cosine", rank=rank, preview=item["preview"]))
            for hit in hits:
                scope.enrich(hit)
                hit_entities=named_files.get(str(hit.assetId),entities) if separate_named_targets or not entities else entities
                hit.provenance.update({"explicitEntities": hit_entities, "representationKind": "visual" if kind == "visual" else "text",
                    "representationId": hit.evidenceId, "queryExpansionVersion":"snapshot-fields-6", "expandedFields":expanded_fields,
                    "textAspectQuery":text_aspect,"visualAspectQuery":visual_aspect,
                    "retrievalContentQuery":expanded_question if kind=='text' else visual_question})
                if str(hit.assetId) in named_files:
                    hit.provenance['explicitSourcePath']=scope.assets[str(hit.assetId)]['logicalPath']
                    hit.provenance['namedSourceScopeVersion']='snapshot-file-targets-1'
                hit.provenance['matchedEntityAliases']={entity:known[entity]['values']['model'] for entity in hit_entities
                    if known[entity].get('values',{}).get('model') and _has_identifier(hit.facts.get('text',''),known[entity]['values']['model'])}
                evidence.append(hit)
            counts[f"{fmt}:{kind}"] = len(hits)
    return {"evidence": [e.model_dump() for e in evidence], "status": "OK", "counts": counts,
            "snapshot": {"dataVersion": str(req.version_id), "indexVersion": str(req.index_version_id)}}


@router.post("/search/image-candidates", response_model=EvidencePack)
async def image_candidates(datasetId: UUID=Form(...),versionId: UUID=Form(...),indexVersionId: UUID=Form(...),
        plan: str=Form(...),file: UploadFile=File(...)):
    import json
    from fastapi import HTTPException
    try:
        query=QueryPlan.model_validate_json(plan)
    except ValueError as error:
        raise HTTPException(status_code=422,detail=str(error)) from error
    if query.retrievers != ["text"] or query.operation != "search":
        raise HTTPException(status_code=422,detail="图搜文仅支持文档目标与检索操作")
    content=await file.read()
    if len(content)>10*1024*1024:raise HTTPException(status_code=413,detail="查询图片不得超过10 MB")
    return _image_candidates_loaded(datasetId,versionId,indexVersionId,query,content)


@registry.serialized
def _image_candidates_loaded(datasetId,versionId,indexVersionId,query,content):
    from fastapi import HTTPException
    from services.query_encoder import QueryEncoder
    from retriever.faiss_retriever import FaissRetriever
    from services.professional_retrievers import _evidence
    loaded=registry.ensure_loaded(str(datasetId),str(versionId),str(indexVersionId))
    suite=ProfessionalRetrieverSuite(loaded.manifest.get("indexes",[]),loaded.professional_files or {},cache=loaded.professional_cache if loaded.professional_cache is not None else {})
    scope=SnapshotScope.from_suite(suite);scope.validate(query)
    try:vector=QueryEncoder().encode_image_bytes(content)
    except Exception as error:raise HTTPException(status_code=422,detail="查询文件不是可解码图片") from error
    scores,indices=FaissRetriever.search(loaded.text_index,vector,loaded.text_index.ntotal)
    hits=[]
    for score,index in zip(scores,indices):
        if index<0:continue
        row=loaded.text_metadata.iloc[index]
        if row.get("kind") in {"image-caption","file-fallback","geometry-summary","cad-summary"}:continue
        asset=str(row['asset_id']);text=str(row.get('text') or '')
        locator=row.get('locator') or {}
        if isinstance(locator,str):locator=json.loads(locator)
        bindings=[b for b in scope.assets.get(asset,{}).get('bindings',[]) if any(_has_identifier(text,e) for e in b.get('entity_ids',[]))]
        if not scope.allowed_unit({'assetId':asset,'metadata':{'bindings':bindings}},query):continue
        hit=_evidence(version=str(versionId),index_version=str(indexVersionId),space='text.clip-image-query',asset_id=asset,
            locator=locator,facts={'text':text},score=float(score),score_type='clip_image_text_cosine',rank=len(hits)+1,
            preview={'logicalPath':str(row['logical_path'])})
        scope.enrich(hit);hit.provenance.update(representationId=str(row.get('representation_id') or hit.evidenceId),representationKind='text')
        hits.append(hit)
        if len(hits)>=query.top_k:break
    return EvidencePack(snapshot={'dataVersion':str(versionId),'indexVersion':str(indexVersionId)},plan=query,evidence=hits,
        coverage={'evidenceCount':len(hits),'filteredBeforeTruncation':True})
