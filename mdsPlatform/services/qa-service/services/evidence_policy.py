"""Absolute channel admission followed by fact deduplication and set selection.

Relevance is an explained retrieval utility, never a correctness probability.
Thresholds live in a versioned backend file and are validated on real snapshots.
"""
from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from services.document_contract import READ_VERSION as DOCUMENT_READ_VERSION,context_kind as document_context_kind
from services.json_contract import READ_VERSION as JSON_READ_VERSION

POLICY = json.loads(Path(__file__).with_suffix(".json").read_text(encoding="utf-8"))


@dataclass(frozen=True)
class FormatIntent:
    allowed: tuple[str, ...] = ()
    preferred: tuple[str, ...] = ()
    reason: str = "未限定依据格式"


def format_intent(question):
    aliases = {"pdf": ("PDF",), "csv": ("CSV",), "xlsx": ("XLSX",), "json": ("JSON",), "jsonl": ("JSONL",),
        "表格": ("CSV", "XLSX", "JSON", "JSONL"), "图片": ("JPG", "PNG", "WEBP", "SVG"),
        "图像": ("JPG", "PNG", "WEBP", "SVG"), "png": ("PNG",), "jpg": ("JPG",),
        "jpeg": ("JPG",), "webp": ("WEBP",), "svg": ("SVG",), "txt": ("TXT",),
        "step": ("STEP", "STP"), "stp": ("STEP", "STP"), "obj": ("OBJ",),
        "三维模型": ("OBJ", "STEP", "STP"), "3d模型": ("OBJ", "STEP", "STP",)}
    # Output instructions cannot constrain evidence. Remove only that clause.
    text = re.sub(r"(?:请)?(?:用|以|输出为?|回答用|回答采用)\s*(?:表格|JSON|CSV|PDF|XLSX)(?:形式)?\s*(?:来)?(?:回答|输出|呈现|展示|格式)", "", question, flags=re.I)
    allowed, preferred = [], []
    for clause in re.split(r"[。；;\n]", text):
        format_word=r'(?<![A-Za-z0-9_.-])(?:CSV|JSONL?|XLSX|TXT|PDF|PNG|JPG|JPEG|WEBP|SVG|OBJ|STEP|STP)(?![A-Za-z0-9_.-])'
        source_scope=bool(re.search(format_word+r'(?:\s*(?:和|与|及|、)\s*'+format_word+r')*\s*(?:文件|记录|数据|资料)?\s*(?:中|里|内)',clause,re.I))
        source = source_scope or bool(re.search(r"依据|根据|基于|参考|结合|来源|资料|文件|优先|只用|仅用|只看|仅看", clause))
        if not source:
            continue
        # A filename identifies a source; its suffix is not another requested
        # format and cannot cancel an explicit, conflicting format constraint.
        clause=re.sub(r'[\w.-]+\.(?:jsonl?|csv|xlsx|txt|pdf|png|jpe?g|webp|svg|obj|stp|step)\b',' ',clause,flags=re.I)
        # In "PNG 图片", 图片 describes the stated carrier; it must not
        # silently widen a strict PNG request to JPG/SVG/WEBP as well.
        clause=re.sub(r'\b(PNG|JPG|JPEG|WEBP|SVG)\s*(?:图片|图像)',r'\1',clause,flags=re.I)
        clause=re.sub(r'(?<![A-Za-z0-9])(OBJ|STEP|STP)\s*(?:三维|3D)?模型',r'\1',clause,flags=re.I)
        explicit=bool(re.search(r'(?<![A-Za-z0-9_-])(?:JSONL|JSON|CSV|XLSX|TXT|PDF|PNG|JPG|JPEG|WEBP|SVG|OBJ|STEP|STP)(?![A-Za-z0-9_-])',clause,re.I))
        separate_category=bool(re.search(r'(?:和|与|及|、|结合|或)\s*(?:图片|图像|表格|三维模型)|(?:图片|图像|表格|三维模型)\s*(?:和|与|及|、|或)',clause))
        if explicit and not separate_category:clause=re.sub(r'图片|图像|表格|三维模型|3D模型',' ',clause,flags=re.I)
        # Match whole format names: JSONL must not be mistaken for JSON, nor
        # should a format fragment inside a business identifier restrict sources.
        formats = [fmt for word, values in aliases.items()
            if re.search((r'(?<![A-Za-z0-9_-])'+re.escape(word)+r'(?![A-Za-z0-9_-])')
                if word.isascii() else re.escape(word), clause, re.I) for fmt in values]
        if re.search(r"只|仅|限于", clause) or source_scope and not re.search(r'优先|尽量',clause):
            allowed.extend(formats)
        elif re.search(r"优先|尽量", clause):
            preferred.extend(formats)
    return FormatIntent(tuple(sorted(set(allowed))), tuple(sorted(set(preferred))),
        "严格限制依据格式" if allowed else "优先指定依据格式" if preferred else "未限定依据格式")


_BOILERPLATE = ["有哪些信息", "有哪些内容", "请问", "请", "本数据空间中", "本数据空间", "数据空间", "这个", "这些", "什么", "如何", "哪些", "是否", "为什么", "是什么", "怎么样", "告诉我", "回答", "说明", "结合", "依据", "只", "仅", "优先", "以及", "和", "与", "的", "吗", "呢"]


def terms(text):
    text=re.sub(r'(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])','',text)
    text=re.sub(r'(?:只|仅)?(?:依据|参考|基于)\s*(?:PDF|CSV|XLSX|JSON|PNG|JPG|OBJ|STEP|表格|图片)|优先(?:使用)?(?:表格|图片|PDF|CSV|XLSX)|(?:请)?用表格回答',' ',text,flags=re.I)
    text = re.sub(r"(?<![a-z0-9])[a-z]{2,12}-[a-z0-9]+(?:-[a-z0-9]+)*", " ", text.lower())
    for stop in _BOILERPLATE:
        text = text.replace(stop, " ")
    words = set(re.findall(r"[a-z][a-z0-9_]{2,}|[\u4e00-\u9fff]{2,}", text))
    result = set()
    for word in words:
        if re.search(r"[\u4e00-\u9fff]", word):
            result.update(word[i:i+2] for i in range(len(word)-1))
        else:
            result.add(word)
    return result


def evidence_text(item):
    facts = item.get("facts", {})
    return str(facts.get("text") or json.dumps({k:v for k,v in facts.items() if k != "channelScores"}, ensure_ascii=False, sort_keys=True))


def original_format(item):
    value = item.get("provenance", {}).get("originalFormat", "").upper()
    return "JPG" if value == "JPEG" else value


def _native_requested_fact(text,fields,provenance):
    """A reference to a specification is not the specification's field values."""
    compact=re.sub(r'\s+','',text)
    triple=bool(re.search(r'(?:宽[xX×*]深[xX×*]高|(?:产品|内机|整机)(?:外形|尺寸))[^；。]{0,20}\d+(?:\.\d+)?[xX×*]\d+(?:\.\d+)?[xX×*]\d+(?:\.\d+)?(?:mm|毫米)',compact))
    labels={'width_mm':'宽度|宽|width_mm','depth_mm':'深度|深|depth_mm','height_mm':'高度|高|height_mm'}
    for field in fields:
        if field=='model' and (provenance.get('matchedEntityAliases') or re.search(r'型号[:：=]?[A-Za-z][A-Za-z0-9_-]+',compact)):
            return True
        if field in labels and (triple or re.search(r'(?:'+labels[field]+r')[:：=]?\d+(?:\.\d+)?(?:mm|毫米)',compact,re.I)):
            return True
        if field=='total_volume_l' and re.search(r'(?:总)?容积[:：=]?\d+(?:\.\d+)?(?:L|升)',compact,re.I):
            return True
        if field=='revision' and re.search(r'(?:工程|设计)?版本[:：=]?[A-Za-z0-9]+',compact):
            return True
        if field in {'spec','part_class'} and re.search(r'(?:规格|参数|零件类别|部件类别)[:：=][^，。；]+',compact):
            return True
        if field not in {*labels,'model','total_volume_l','revision','spec','part_class'} and re.search(re.escape(field)+r'\s*[:：=]\s*\S+',text):
            return True
    return False


def _literal_record_lookup(question, record, fields):
    """A simple field lookup may use an exact source cell without catalog entities.

    This proves a record match, not primary-key uniqueness or a relation. Other
    matching records remain separate evidence. Additional question topics must
    still pass the normal relevance policy.
    """
    if not isinstance(record, dict) or not fields or not all(
            field in record and record[field] is not None for field in fields):
        return False
    identifiers = re.findall(r'(?<![A-Za-z0-9_-])[A-Za-z]{2,12}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_-])', question)
    if not identifiers or not all(any(isinstance(value, str) and value.lower() == identifier.lower()
            for value in record.values()) for identifier in identifiers):
        return False
    residual = question
    for identifier in identifiers:
        residual = re.sub(re.escape(identifier), ' ', residual, flags=re.I)
    for field in sorted(fields, key=len, reverse=True):
        residual = re.sub(r'(?<![A-Za-z0-9_])'+re.escape(field)+r'(?![A-Za-z0-9_])', ' ', residual, flags=re.I)
    residual = re.sub(r'这条|该条|记录|字段|数值|是多少|是什么|多少|请问|告诉我|查询|查看|给出|各自|分别|以及|等于|这|该|行|中|的|值|是|为|请|和|与|及', '', residual)
    return not re.search(r'[A-Za-z0-9\u4e00-\u9fff]', residual)


def admit(question, item):
    provenance, facts = item.get("provenance", {}), item.get("facts", {})
    if not provenance.get("originalFormat") or not item.get("dataVersion") or not item.get("indexVersion"):
        return None, "INCOMPLETE_PROVENANCE"
    if provenance.get("scope") == "catalog":
        return None, "CATALOG_IS_NOT_BUSINESS_EVIDENCE"
    if item.get('scoreType')=='verified_record_read':
        from services.answer_lineage import source_dict
        from services.record_claim_view import validate_source
        try:
            if provenance.get('validatedRecordQuestion')!=question:return None,'RECORD_QUESTION_CHANGED'
            validate_source(source_dict(item))
        except (ValueError,KeyError,TypeError):return None,'RECORD_READ_NOT_VERIFIED'
        return POLICY['metadata']['exactRoleUtility'],'EXPLICIT_AUTHORIZED_UNIQUE_RECORD_READ'
    if item.get('scoreType')=='verified_text_read':
        from services.text_evidence_plan import validate_item
        try:validate_item(question,item)
        except (ValueError,KeyError,TypeError):return None,'TXT_READ_NOT_VERIFIED'
        return POLICY['metadata']['exactRoleUtility'],'EXPLICIT_AUTHORIZED_TXT_RECORD_READ'
    if item.get('scoreType')=='verified_table_read':
        from services.table_evidence_plan import validate_item
        try:validate_item(question,item)
        except (ValueError,KeyError,TypeError):return None,'TABLE_READ_NOT_VERIFIED'
        return POLICY['metadata']['exactRoleUtility'],'EXPLICIT_AUTHORIZED_WORKSHEET_READ'
    if item.get('scoreType')=='verified_json_read':
        bundle=item.get('nativeBundle',{});identity=bundle.get('identity',{});target=provenance.get('jsonTarget',{})
        if (provenance.get('jsonReadVersion')!=JSON_READ_VERSION or provenance.get('validatedJsonQuestion')!=question
                or identity.get('originalFormat') not in {'JSON','JSONL'} or not bundle.get('readId') or not bundle.get('readOutputSha256')
                or not bundle.get('facts') or bundle.get('coverage',{}).get('partial')
                or identity.get('assetId')!=item.get('assetId') or identity.get('versionId')!=item.get('dataVersion')
                or identity.get('indexVersionId')!=item.get('indexVersion') or identity.get('sourceSha256')!=provenance.get('sourceSha256')
                or identity.get('logicalPath')!=provenance.get('explicitSourcePath')
                or target.get('role') not in {'requested-field','record-anchor'}
                or bundle.get('locator',{}).get('jsonPointer')!=target.get('jsonPointer')
                or bundle.get('locator',{}).get('jsonlLine')!=target.get('jsonlLine')
                or not re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(identity.get('logicalPath','').rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question)):
            return None,'JSON_READ_NOT_VERIFIED'
        return POLICY['metadata']['exactRoleUtility'],'EXPLICIT_AUTHORIZED_JSON_FIELD_READ'
    if item.get('scoreType')=='verified_engineering_read':
        from services.engineering_evidence_plan import READ_VERSIONS
        bundle=item.get('nativeBundle',{});identity=bundle.get('identity',{})
        if (provenance.get('engineeringReadVersion') not in READ_VERSIONS
                or provenance.get('validatedEngineeringQuestion')!=question or not bundle.get('readId')
                or identity.get('assetId')!=item.get('assetId') or identity.get('versionId')!=item.get('dataVersion')
                or identity.get('indexVersionId')!=item.get('indexVersion') or identity.get('sourceSha256')!=provenance.get('sourceSha256')
                or identity.get('logicalPath')!=provenance.get('explicitSourcePath')
                or not re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(identity.get('logicalPath','').rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question,re.I)):
            return None,'ENGINEERING_READ_NOT_VERIFIED'
        if provenance.get('engineeringVisualRead'):
            view=item.get('nativeRegisteredView',{})
            if not view.get('imageId') or view.get('sourceSha256')!=identity.get('sourceSha256') or not view.get('outputSha256'):
                return None,'ENGINEERING_IMAGE_NOT_REGISTERED'
        elif not bundle.get('facts') or not provenance.get('requestedPropertyFields'):
            return None,'ENGINEERING_PROPERTY_FACTS_ABSENT'
        return POLICY['metadata']['exactRoleUtility'],'EXPLICIT_AUTHORIZED_ENGINEERING_READ'
    if item.get('scoreType') in {'verified_document_visual_read','verified_document_text_read'}:
        bundle=item.get('nativeBundle',{});identity=bundle.get('identity',{})
        if (provenance.get('documentReadVersion')!=DOCUMENT_READ_VERSION
                or provenance.get('validatedDocumentQuestion')!=question or not bundle.get('readId')
                or identity.get('assetId')!=item.get('assetId') or identity.get('versionId')!=item.get('dataVersion')
                or identity.get('indexVersionId')!=item.get('indexVersion') or identity.get('sourceSha256')!=provenance.get('sourceSha256')
                or identity.get('logicalPath')!=provenance.get('explicitSourcePath')
                or not re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(identity.get('logicalPath','').rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question,re.I)):
            return None,'DOCUMENT_READ_NOT_VERIFIED'
        if item['scoreType']=='verified_document_visual_read':
            if bundle.get('representationKind') not in {'document_page','document_region_text_projection'} or not item.get('nativeRegionRequest'):
                return None,'DOCUMENT_VISUAL_SCOPE_NOT_VERIFIED'
            return POLICY['metadata']['exactRoleUtility'],'EXPLICIT_AUTHORIZED_VISUAL_READ_TARGET'
        text=bundle.get('content',{}).get('text','')
        if not bundle.get('facts') or not text:return None,'DOCUMENT_SOURCE_TEXT_ABSENT'
        residual=question.replace(identity['logicalPath'].rsplit('/',1)[-1],' ')
        identifiers=re.findall(r'(?<![A-Za-z0-9_])(?=[A-Za-z0-9-]*\d)[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_])',residual)
        source_identifier=any(re.sub(r'\s+','',identifier).casefold() in re.sub(r'\s+','',text).casefold() for identifier in identifiers)
        from services.document_row_context import verified as verified_row_context
        row_context=verified_row_context(item,question)
        if not (terms(question)&terms(text)) and not source_identifier and not row_context and not (provenance.get('documentContextOnly') and
                document_context_kind(text)):
            return None,'DOCUMENT_QUESTION_CONTENT_NOT_SUPPORTED'
        return POLICY['metadata']['exactRoleUtility'],'EXPLICIT_READ_WITH_SOURCE_CONTENT'
    if item.get('scoreType')=='verified_native_selector':
        bundle=item.get('nativeBundle',{});selector=provenance.get('validatedNativeSelector',{});identity=bundle.get('identity',{})
        if (provenance.get('nativeSelectorVersion')!='native-selector-exact-1' or provenance.get('validatedNativeQuestion')!=question
                or identity.get('assetId')!=selector.get('assetId') or identity.get('assetId')!=item.get('assetId')
                or identity.get('versionId')!=item.get('dataVersion') or identity.get('indexVersionId')!=item.get('indexVersion')
                or not bundle.get('readId') or not bundle.get('facts') or bundle.get('coverage',{}).get('partial')
                or any(bundle.get('locator',{}).get(key)!=value for key,value in selector.items() if key!='assetId')):
            return None,'NATIVE_SELECTOR_NOT_VERIFIED'
        return POLICY['metadata']['exactRoleUtility'],'EXACT_AUTHORIZED_NATIVE_SELECTOR'
    if item.get('locator',{}).get('source')=='filename':
        return None, "FILE_NAME_IS_IDENTITY_ONLY"
    flags = set(item.get("qualityFlags", []))
    if flags & {"PARSE_FAILED", "SOURCE_TIMEZONE_UNSPECIFIED", "UNIT_MAPPING_REQUIRED", "UNIT_UNKNOWN"}:
        return None, "QUALITY_NOT_VERIFIED"
    content = evidence_text(item)
    entities = provenance.get("explicitEntities", [])
    visual = facts.get("visualOnly", False)
    if item.get('scoreType')=='verified_temporal_role':
        if (provenance.get('temporalRoleVersion')!='verified-temporal-roles-1' or provenance.get('validatedMetadataQuestion')!=question
                or not provenance.get('verifiedPrimaryRow') or not provenance.get('temporalMetadata')):
            return None,'UNVERIFIED_TEMPORAL_ROLE_SCOPE'
        return POLICY['metadata']['exactRoleUtility'],'EXACT_PRIMARY_ROW_DATE_ROLE'
    requested_fields=set(provenance.get('expandedFields',[]))
    native_fields=set()
    record=None
    if requested_fields and facts.get('text'):
        try:
            record=json.loads(facts['text'])
        except (ValueError,TypeError):
            record=None
        if isinstance(record,dict) and not any(record.get(field) is not None for field in requested_fields):
            return None, "REQUESTED_FIELDS_ABSENT"
        if not isinstance(record,dict):
            if provenance.get('factPresentationVersion')=='source-matched-fields-1':
                # A component's height in a work order is not the product's
                # height. The source adapter binds observed values to their
                # actual record before these fields can admit a carrier.
                native_fields={field for claim in provenance.get('factClaims',[])
                    if not entities or claim.get('primaryKey') in entities
                    for field in claim.get('fields',[]) if field in requested_fields}
            else:
                native_fields={field for field in requested_fields if _native_requested_fact(content,{field},provenance)}
            if not native_fields:return None,"REQUESTED_FIELDS_ABSENT"
        if re.search(r'规格|产品参数',question) and requested_fields&{'width_mm','depth_mm','height_mm','total_volume_l'}:
            substantive=requested_fields&{'width_mm','depth_mm','height_mm','total_volume_l'}
            if not (any(record.get(field) is not None for field in substantive) if isinstance(record,dict) else substantive&native_fields):
                return None,'PRODUCT_SPECIFICATION_VALUES_ABSENT'
    anchored = bool(entities)
    if not anchored and not visual and _literal_record_lookup(question, record, requested_fields):
        return POLICY['metadata']['exactRoleUtility'], 'EXACT_SOURCE_RECORD_FIELD_LOOKUP'
    validated_query = provenance.get('validatedQuery', {})
    calculated_entity_match = item.get('evidenceType') == 'calculation' and bool(validated_query) and (
        validated_query.get('target_entity') in entities or any(
            c.get('op')=='eq' and str(c.get('value')) in entities for c in validated_query.get('hard_conditions',[])))
    matched_identifiers=list(entities)+list(provenance.get('matchedEntityAliases',{}).values())
    if not visual and entities and not calculated_entity_match and not any(re.search(r'(?<![A-Za-z0-9_-])'+re.escape(entity)+r'(?![A-Za-z0-9_-])',content,re.I) for entity in matched_identifiers):
        return None, "ENTITY_NOT_IN_MATCHED_CONTENT"
    aspect_question=provenance.get('textAspectQuery',question) if item.get('scoreType')=='weighted_rrf' else question
    query_terms, content_terms = terms(aspect_question), terms(content)
    # Typed field names provide verifiable aspect semantics even when a CSV
    # stores English column names. They do not supply additional business facts.
    for pattern, labels in [(r'"(?:width_mm|height_mm|depth_mm)"', {"规格","参数","尺寸","宽度","高度","深度"}),
                            (r'"model"', {"型号","规格","参数"}), (r'"sku_id"', {"产品"}),
                            (r'"sales_price_cny_tax_included"', {"售价","价格","含税"})]:
        if re.search(pattern, content):
            content_terms.update(labels)
    native_labels={'model':{'型号','规格'},'width_mm':{'宽度','尺寸','规格'},'depth_mm':{'深度','尺寸','规格'},
        'height_mm':{'高度','尺寸','规格'},'total_volume_l':{'容积','规格'},'revision':{'版本'},'spec':{'规格','参数'}}
    for field in native_fields:
        content_terms.update(native_labels.get(field,terms(field)))
    if native_fields:content_terms.add('参数')
    overlap = len(query_terms & content_terms) / max(1, len(query_terms))
    score_type = item.get("scoreType")
    if item.get("evidenceType") in {"calculation", "constraint"}:
        if facts.get("status") or ("sampleCount" in facts and not facts["sampleCount"] and facts.get("operation") != "count"):
            return None, "INVALID_COMPUTATION"
        validated = provenance.get("validatedQuery", {})
        if (not anchored and not validated.get("table_id")) or (facts.get("metric") and facts["metric"] not in question):
            return None, "COMPUTATION_DOES_NOT_MATCH_QUESTION"
        return min(.95, .60 + .25 * anchored + .10 * overlap), "EXACT_SCOPE_AND_CONTENT"
    if score_type == "clip_image_text_cosine":
        if not anchored:
            return None, "IMAGE_ENTITY_CONFIRMATION_REQUIRED"
        aspect_terms=query_terms-terms('这张图片 这幅照片 相关产品 图中 图像 说明外观')
        aspect_coverage=len(aspect_terms&content_terms)/max(1,len(aspect_terms))
        if aspect_terms and aspect_coverage<POLICY['imageText']['aspectCoverageFloor']:
            return None,"QUESTION_ASPECT_NOT_SUPPORTED"
        score = item.get("rawScore")
        floor = POLICY["imageText"]["anchoredCosineFloor" if anchored else "cosineFloor"]
        if not isinstance(score,(int,float)) or not math.isfinite(score) or score < floor:
            return None, "BELOW_IMAGE_TEXT_ADMISSION"
        return min(.95,.5 + score/2), "IMAGE_TEXT_CHANNEL_CALIBRATION"
    if score_type == "weighted_rrf":
        scores = facts.get("channelScores", {})
        cosine, bm25 = scores.get("cosine"), scores.get("bm25", 0)
        config = POLICY["text"]
        semantic = isinstance(cosine, (int,float)) and math.isfinite(cosine) and cosine >= config["anchoredCosineFloor" if anchored else "cosineFloor"]
        lexical = overlap >= config["lexicalCoverageFloor"] and bm25 >= config["bm25Floor"]
        if not (semantic or lexical):
            return None, "BELOW_CHANNEL_ADMISSION"
        if anchored and query_terms and overlap < config["anchoredCoverageFloor"]:
            return None, "QUESTION_ASPECT_NOT_SUPPORTED"
        return min(.98, .65 * max(0,float(cosine or 0)) + .35 * overlap + (.08 if anchored else 0)), "PINNED_BGE_OR_ABSOLUTE_LEXICAL"
    if score_type == "clip_cosine":
        score = item.get("rawScore")
        if not re.search(r"外观|颜色|图像|图片|图纸|标注|照片|布局|形状|现场", question):
            return None, "VISUAL_NOT_CONTRIBUTING_TO_QUESTION"
        if re.search(r'标注(?:图|图片|照片)|检验标注',question) and not re.search(r'标注|annotat',provenance.get('visualDescription',''),re.I):
            return None,'REQUESTED_VISUAL_ANNOTATION_NOT_VERIFIED'
        topic_question=re.sub(r'(?:图片|图像|照片)(?:中|里|上)(?:的)?',' ',provenance.get('visualAspectQuery',question))
        topic_question=re.sub(r'\b(?:PDF|CSV|XLSX|JSON|PNG|JPG|JPEG|WEBP|SVG|OBJ|STEP|STP|TXT)\b',' ',topic_question,flags=re.I)
        topic_question=re.sub(r'产品规格|产品参数|产品外观|这张|这幅|图中|图片|图像|照片|现场|外观|规格|参数|型号|产品|结构|颜色|标注|展示|显示|核对|实际|真实|可见|资料|依据|说明|描述|相关|信息|情况|内容|特点|特征|样子|看起来|介绍',' ',topic_question)
        visual_topics=terms(topic_question)
        metadata_terms=terms(provenance.get('relevanceMetadata',''))
        topic_coverage=len(visual_topics&metadata_terms)/max(1,len(visual_topics))
        if visual_topics and topic_coverage<POLICY['visual']['topicCoverageFloor']:
            return None,'VISUAL_TOPIC_NOT_SUPPORTED_BY_BOUND_METADATA'
        floor = (POLICY['visual']['topicAnchoredCosineFloor'] if anchored and visual_topics else
                 POLICY["visual"]["anchoredCosineFloor" if anchored else "cosineFloor"])
        if not isinstance(score,(int,float)) or not math.isfinite(score) or score < floor:
            return None, "BELOW_CLIP_ADMISSION"
        return min(.95, .5 + float(score) / 2 + (.08 if anchored else 0)), "CLIP_WITH_VISUAL_TASK"
    return None, "NO_CALIBRATION_FOR_SCORE_TYPE"


def _engineering_visual(item):
    return bool(item.get('provenance',{}).get('engineeringVisualRead') and item.get('nativeBundle'))


def identity(item):
    if (item.get('provenance',{}).get('documentRowContext') or item.get('provenance',{}).get('documentHeaderForRows')) and item.get('nativeBundle'):
        bundle=item['nativeBundle'];frozen=bundle['identity']
        return ('native-document-row-column',tuple(frozen[key] for key in ('datasetId','versionId','indexVersionId','rawCommitId','assetId','sourceSha256')),
            json.dumps(bundle['locator'],sort_keys=True,separators=(',',':')))
    if item.get('provenance',{}).get('recordReadVersion') and item.get('nativeBundle'):
        bundle=item['nativeBundle'];frozen=bundle['identity'];provenance=item['provenance']
        return ('native-record',tuple(frozen[key] for key in ('datasetId','versionId','indexVersionId','rawCommitId','assetId','sourceSha256')),
            provenance['recordTableId'],json.dumps(provenance['nativeReadOperation'],sort_keys=True,separators=(',',':')))
    if item.get('provenance',{}).get('textReadVersion') and item.get('nativeBundle'):
        bundle=item['nativeBundle'];frozen=bundle['identity']
        return ('native-txt',tuple(frozen[key] for key in ('datasetId','versionId','indexVersionId','rawCommitId','assetId','sourceSha256')),
            json.dumps(bundle['locator'],sort_keys=True,separators=(',',':')))
    if item.get('provenance',{}).get('tableReadVersion') and item.get('nativeBundle'):
        bundle=item['nativeBundle'];frozen=bundle['identity']
        return ('native-worksheet',tuple(frozen[key] for key in ('datasetId','versionId','indexVersionId','rawCommitId','assetId','sourceSha256')),
            json.dumps(item['provenance']['tableTarget'],sort_keys=True,separators=(',',':')))
    if item.get('provenance',{}).get('jsonReadVersion')==JSON_READ_VERSION and item.get('nativeBundle'):
        frozen=item['nativeBundle']['identity']
        return ('native-json',tuple(frozen[key] for key in ('datasetId','versionId','indexVersionId','rawCommitId','assetId','sourceSha256')),
            json.dumps(item['nativeBundle']['locator'],sort_keys=True,separators=(',',':')))
    if _engineering_visual(item):
        frozen=item['nativeBundle']['identity']
        scope=tuple(frozen[key] for key in ('datasetId','versionId','indexVersionId','rawCommitId','assetId','sourceSha256'))
        view=item.get('nativeRegisteredView',{})
        selection={'registeredImageId':view.get('imageId'),'parameters':item.get('nativeEngineeringRegionRequest')}
        return ('engineering-visual',scope,json.dumps(selection,sort_keys=True,separators=(',',':')))
    text = re.sub(r"\s+", "", evidence_text(item)).lower()
    if item.get('facts',{}).get('visualOnly'):
        return ('visual',item.get('assetId'),json.dumps(item.get('locator',{}),sort_keys=True))
    observed=[{key:claim.get(key) for key in ['businessType','primaryKey','fieldValues','fieldUnits','timeRange']}
        for claim in item.get('provenance',{}).get('factClaims',[])]
    return ('text',text,json.dumps(item.get('units',{}),sort_keys=True,ensure_ascii=False),
        json.dumps(observed,sort_keys=True,ensure_ascii=False))


def redundancy(left, right):
    if identity(left) == identity(right):
        return 1.0
    if any(item.get('provenance',{}).get('recordReadVersion') for item in (left,right)):
        return 0.0  # Retain the explicit relation's witnesses; give no diversity/complement bonus below.
    if any(item.get('provenance',{}).get('textReadVersion') for item in (left,right)):
        return 0.0  # Distinct required ranges are not redundant record copies.
    if any(item.get('provenance',{}).get('documentRowContext') or item.get('provenance',{}).get('documentHeaderForRows') for item in (left,right)):
        return 0.0  # Equal native column text in different rows must retain its row binding.
    if any(item.get('provenance',{}).get('tableReadVersion') for item in (left,right)):
        return 0.0  # Equal values at distinct source coordinates are separate facts.
    if any(item.get('provenance',{}).get('jsonReadVersion')==JSON_READ_VERSION for item in (left,right)):
        return 0.0  # Equal scalar/null values at different record pointers are not the same fact.
    if left.get("facts", {}).get("visualOnly") or right.get("facts", {}).get("visualOnly") or _engineering_visual(left) or _engineering_visual(right):
        return 0.0
    focus=set(left.get('provenance',{}).get('expandedFields',[]))
    explicit=set(left.get('provenance',{}).get('explicitEntities',[]))
    def claims(item):
        result=[]
        raw=item.get('provenance',{}).get('factClaims',[])
        requested_types={claim.get('businessType') for claim in raw if claim.get('primaryKey') in explicit}
        for claim in raw:
            if claim.get('businessType') in requested_types and claim.get('primaryKey') not in explicit:continue
            fields=set(claim.get('fields',[]));matching=fields&focus
            # Unasked service/order attributes cannot turn a repeated model
            # reference into complementary evidence for a field-only question.
            if focus and not matching:continue
            result.append({**claim,'fields':matching or fields})
        return result
    candidate_claims=claims(left);selected_claims=claims(right)
    aliases=left.get('provenance',{}).get('matchedEntityAliases',{})
    if focus and 'model' in focus and aliases and not any(claim['fields']-{'model'} for claim in candidate_claims):
        text=evidence_text(left)
        try:native=not isinstance(json.loads(text),dict)
        except (TypeError,ValueError):native=True
        if native and not _native_requested_fact(text,focus-{'model'},left.get('provenance',{})) and all(
            re.search(r'(?<![A-Za-z0-9_-])'+re.escape(model)+r'(?![A-Za-z0-9_-])',evidence_text(right),re.I)
            for model in aliases.values()):return 1.0
    # Direction matters: a new order must not be discarded merely because it
    # also repeats a product attribute already present in another source.
    if candidate_claims and all(any((a.get('businessType'),a.get('primaryKey'),a.get('timeRange'))==
        (b.get('businessType'),b.get('primaryKey'),b.get('timeRange')) and a['fields']<=b['fields'] and all(
            (field not in a.get('fieldValues',{}) and field not in b.get('fieldValues',{})) or
            (field in a.get('fieldValues',{}) and field in b.get('fieldValues',{}) and
             a['fieldValues'][field]==b['fieldValues'][field] and a.get('fieldUnits',{}).get(field)==b.get('fieldUnits',{}).get(field))
            for field in a['fields'])
        for b in selected_claims) for a in candidate_claims):return 1.0
    a, b = terms(evidence_text(left)), terms(evidence_text(right))
    overlap=len(a & b) / max(1,len(a | b))
    # Lexical tokenization deliberately removes business identifiers. It must
    # not override verified evidence that the candidate contains a new record
    # or an uncovered field/window, even when the remaining words are equal.
    return min(.79,overlap) if candidate_claims and selected_claims else overlap


def select_evidence(question, candidates, intent=None, limit=None):
    intent = intent or format_intent(question)
    diagnostics = {"policyVersion": POLICY["version"], "calibrationStatus": POLICY["calibrationStatus"],
                   "candidateCount": len(candidates), "rejections": [], "singleFormatReason": None}
    eligible, seen = [], {}
    for item in candidates:
        if intent.allowed and original_format(item) not in intent.allowed:
            diagnostics["rejections"].append({"evidenceId":item["evidenceId"],"reason":"FORMAT_RESTRICTION"})
            continue
        relevance, reason = admit(question, item)
        if relevance is None:
            diagnostics["rejections"].append({"evidenceId":item["evidenceId"],"reason":reason})
            continue
        item = {**item, "relevance": relevance, "admissionReason": reason}
        key = identity(item)
        if key in seen:
            seen[key].setdefault("alternateSources", []).append(item)
            diagnostics["rejections"].append({"evidenceId":item["evidenceId"],"reason":"SAME_FACT_COPY"})
            continue
        seen[key] = item
        eligible.append(item)
    eligible.sort(key=lambda e:(-e["relevance"],e["evidenceId"]))
    selected=[]; weights=POLICY["weights"]
    while eligible and len(selected)<(limit or POLICY["maxSelectedSources"]):
        formats={original_format(e) for e in selected}
        def utility(item):
            duplicate=max((redundancy(item,s) for s in selected),default=0)
            record_witness=item.get('provenance',{}).get('formatDiversityContribution') is False
            new_format=0.0 if record_witness else float(original_format(item) not in formats)
            complement=0.0 if record_witness else 1-duplicate
            preference=.02 if not record_witness and original_format(item) in intent.preferred else 0
            return (weights["relevance"]*item["relevance"] + weights["newOriginalFormat"]*new_format
                + weights["complementaryInformation"]*complement - weights["redundancy"]*duplicate+preference)
        if not selected:
            best=eligible[0]
        else:
            complement_formats=[e for e in eligible if e.get('provenance',{}).get('formatDiversityContribution') is not False and original_format(e) not in formats and max(redundancy(e,s) for s in selected)<.8]
            pool=complement_formats if not intent.allowed and len(formats)<2 and complement_formats else eligible
            if re.search(r'规格|参数|型号|宽度|高度|深度|容积|售价|数值',question) and not any(not s.get('facts',{}).get('visualOnly') for s in selected):
                factual=[e for e in pool if not e.get('facts',{}).get('visualOnly')]
                if factual:
                    pool=factual
            best=sorted(pool,key=lambda e:(-utility(e),e["evidenceId"]))[0]
        eligible.remove(best)
        if selected and max(redundancy(best,s) for s in selected)>=.95:
            diagnostics["rejections"].append({"evidenceId":best["evidenceId"],"reason":"DUPLICATE_INFORMATION"})
            continue
        selected.append(best)
    diagnostics["eligibleCount"]=len(seen)
    diagnostics["selectedFormats"]=sorted({original_format(e) for e in selected})
    record_witnesses=[item for item in selected if item.get('provenance',{}).get('recordReadVersion')]
    if record_witnesses:diagnostics.update(recordRelationWitnesses=len(record_witnesses),recordWitnessFormatAndComplementBonusesApplied=False)
    if len(diagnostics["selectedFormats"])<2:
        diagnostics["singleFormatReason"]="EXPLICIT_FORMAT_RESTRICTION" if intent.allowed else "NO_SECOND_RELEVANT_COMPLEMENTARY_FORMAT"
    return selected, diagnostics
