"""Bounded reading of a scoped document, using frozen native locators.

A file name may locate an authorized document. Content and subject
identification come from the registered native reads.
"""
from __future__ import annotations
import json,re,time
from core.request_budget import CAPABILITIES, current_budget, max_image_pixels
from services.evidence_policy import terms
from services.document_contract import PAGE_SELECTION, READ_VERSION, context_kind
from services.read_receipt import verify

VERSION=READ_VERSION


def _compact(text):return re.sub(r'\s+','',text).casefold()


def _selected_pages(question, pages):
    available = {page['selector']['page'] for page in pages}
    requested = set()
    for match in re.finditer(PAGE_SELECTION, question):
        first, last = match['first'], match['last'] or match['first']
        if max(len(first), len(last)) > 9:
            raise ValueError('NEEDS_CLARIFICATION: 指定页码不在该冻结文件中。')
        start, end = int(first), int(last)
        selected = {number for number in available if start <= number <= end}
        if start > end or not selected or len(selected) != end - start + 1:
            raise ValueError('NEEDS_CLARIFICATION: 指定页码范围有倒序或未登记的页面。')
        requested.update(selected)
    if re.search(r'末页|最后一页', question):
        requested.add(pages[-1]['selector']['page'])
    return [page for page in pages if page['selector']['page'] in requested] if requested else pages


def _item(scope,asset,bundle,question,operation,*,visual=False,text=None):
    identity=bundle['identity'];facts=bundle['facts']
    model_facts=[{'factId':fact['factId'],'text':fact['value'],'nature':fact['evidenceNature'],
        **({'exactCalculationEligible':False,'recognitionConfidenceMinimum':fact.get('locator',{}).get('ocrConfidenceMinimum'),
            'recognitionConfidenceScale':'engine-score-not-calibrated-probability'} if fact['evidenceNature']=='ocr_text' else {})} for fact in facts]
    visual_region=visual and bundle['representationKind']=='document_region_text_projection'
    if visual_region:model_facts=[]
    context={'evidenceId':bundle['evidenceId'],'facts':model_facts}
    if visual:context.update(visualObservationRequired=True,page=bundle['locator'].get('page',1),
        textIsNotAVisualObservation=True)
    human=text if text is not None else bundle['content'].get('text','')
    item={'assetId':asset['assetId'],'dataVersion':str(scope[1]),'indexVersion':str(scope[2]),
        'evidenceId':bundle['evidenceId'],'semanticSpace':'native.document.read','locator':bundle['locator'],
        'facts':{'text':human},'scoreType':'verified_document_visual_read' if visual else 'verified_document_text_read',
        'rawScore':None,'evidenceType':'constraint','qualityFlags':bundle['qualityFlags'],
        'preview':{'logicalPath':asset['logicalPath']},'nativeBundle':bundle,
        'nativeHumanContext':human or '按指定原始来源读取图像；文字或文件名不证明图中内容。',
        'nativeModelContext':json.dumps(context,ensure_ascii=False,separators=(',',':')),
        'provenance':{'scope':asset['scope'],'originalFormat':asset['originalFormat'],
            'sourceSha256':identity['sourceSha256'],'rawCommitId':identity['rawCommitId'],
            'documentReadVersion':VERSION,'validatedDocumentQuestion':question,'explicitSourcePath':asset['logicalPath'],
            'nativeReadId':bundle['readId'],'nativeReadOperation':operation,'readOutputSha256':bundle['readOutputSha256'],
            'documentVisualRead':visual,'representationId':bundle['representationId'],
            'representationKind':bundle['representationKind']}}
    if visual_region:
        item['modelFactIds']=[]
        item['provenance']['modelFactSelection']={'selectedFactIds':[],'reason':'VISUAL_REGION_READ_WITHOUT_OCR_TEXT_ASSERTIONS',
            'readFactIds':[fact['factId'] for fact in facts]}
    return item


def _read_record(bundle,operation):
    return {'state':'READ','readId':bundle['readId'],'operation':operation,'evidenceId':bundle['evidenceId'],
        'representationId':bundle['representationId'],'sourceSha256':bundle['identity']['sourceSha256'],
        'readOutputSha256':bundle['readOutputSha256'],'factIds':[fact['factId'] for fact in bundle['facts']],
        'coverage':bundle['coverage']}


def _full_page_item(scope,asset,bundle,question,number):
    operation={'operation':'read_representation','representationId':bundle['representationId']}
    item=_item(scope,asset,bundle,question,operation,visual=True)
    item['provenance']['documentFullView']=True
    item['nativeRegionRequest']={'page':number,'bbox':bundle['locator']['bbox'],
        'dpi':160,'maxPixels':max_image_pixels()}
    return item


def locate_installation_image(question, filters, intent):
    if (len(intent.allowed) != 1 or intent.allowed[0] not in {'JPG', 'PNG'}
            or not re.search(r'相对位置|安装位置|墙面|地面', question)
            or re.search(r'\b(?:https?|file|ftp)://|不要(?:看|读取|使用)|排除', question, re.I)):
        return None
    identifiers = set(re.findall(r'(?<![A-Za-z0-9_.-])[A-Za-z]{2,12}-[A-Za-z0-9]*\d[A-Za-z0-9]*(?![A-Za-z0-9_.-])',
                                 question))
    if len(identifiers) != 1:
        return None
    token = next(iter(identifiers))
    filename_match = re.compile(r'(?<![A-Za-z0-9-])' + re.escape(token) + r'(?![A-Za-z0-9-])', re.I)
    candidates = [asset for asset in filters.get('assets', [])
                  if asset.get('scope') == 'corpus' and asset.get('nativeEvidence')
                  and asset.get('originalFormat') == intent.allowed[0]
                  and filename_match.search(asset['logicalPath'].rsplit('/', 1)[-1].rsplit('.', 1)[0])]
    return str(candidates[0]['assetId']) if len(candidates) == 1 else None


PRODUCT_DOCUMENT_ID = re.compile(
    r'(?<![A-Za-z0-9_.-])[A-Za-z]{2,12}-[A-Za-z0-9]*\d[A-Za-z0-9]*(?![A-Za-z0-9_.-])')
PRODUCT_DOCUMENT_TOPIC = re.compile(r'技术页|规格页|产品规格书|产品技术资料')
COMPARISON_MODEL_ID = re.compile(
    r'(?<![A-Za-z0-9_.-])(?=[A-Za-z0-9-]*\d)[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_.-])')
DIMENSION_QUERY = re.compile(r'尺寸|外形|宽度|深度|高度')
POWER_QUERY = re.compile(r'供电|电压|频率')
POWER_DECLARATION = re.compile(r'(?<![\d.])\d+(?:\.\d+)?\s*(?:V(?:AC)?|Hz)(?![A-Za-z])'
                               r'|(?<![\d.])\d+(?:\.\d+)?\s*(?:伏(?:特)?|赫兹)', re.I)
UNIT_DIMENSIONS = re.compile(r'\d+(?:\.\d+)?\s*[×x*]\s*\d+(?:\.\d+)?\s*[×x*]\s*\d+(?:\.\d+)?\s*(?:mm\b|毫米)', re.I)
UNIT_LABEL = re.compile(r'内机|外机|indoor|outdoor', re.I)
COMPONENT_NUMBER_VERSION = re.compile(r'(?<![A-Za-z0-9-])([A-Za-z][A-Za-z0-9-]*\d[A-Za-z0-9-]*)\s*[/／]\s*[A-Za-z0-9][A-Za-z0-9.-]*')
COMPONENT_QUANTITY = re.compile(r'\d+(?:\.\d+)?\s*(?:个|件|套|台|只|片|块|根|支)\s*$')
COMPONENT_GENERIC_TERMS = terms('安装 团队 复核 接口 部件 零件 料号 编号 版本 数量 用量 每套 适配 兼容 尺寸 内机 外机')
COMPONENT_USE_QUESTION = re.compile(
    r'(?:' + PRODUCT_DOCUMENT_TOPIC.pattern + r')(?:中|里)?\s*(?:(?:给出|列出|提到|所列|所示)的?|的)?\s*'
    r'(?P<subjects>[\u4e00-\u9fff、\s]{5,62}?)\s*(?:各自?|分别)(?:做什么|有什么作用|起什么作用|用于什么|有什么用途)')
COMPONENT_USE_QUANTITY_HEADER = r'每套\s*(?:用量|数量)'
COMPONENT_USE_CONTEXT = re.compile(
    COMPONENT_USE_QUANTITY_HEADER + r'|(?:关键\s*BOM|关键用料)[^。；;\n]{0,26}(?:追溯范围|仅列|只列)|'
    r'(?:不能|不提供|无法|不足以)[^。；;\n]{0,20}(?:整机成本|库存|现货|生产放行)', re.I)
MAINTENANCE_COMPONENT = re.compile(r'滤网|滤芯|集水|碗篮|风道')
MAINTENANCE_PHOTO_TYPES = {'product_detail_photo', 'simulated_product_detail_photo'}


def maintenance_image_topic(question):
    if (not re.search(r'图中|图上|画面|照片(?:中|里)', question)
            or not re.search(r'维护|保养|清洁|清洗', question)
            or not re.search(r'可见|看到|打开|露出', question)
            or PRODUCT_DOCUMENT_ID.search(question)
            or re.search(r'\b(?:https?|file|ftp)://|[\w.-]+\.(?:pdf|txt|csv|jsonl?|xlsx|jpe?g|png|webp|svg|obj|step|stp)\b|'
                         r'结合|对比|比较|两(?:张|幅)|多(?:张|幅)|附件|上传|不要(?:看|读取|使用)|排除', question, re.I)):
        return None
    topics = set(MAINTENANCE_COMPONENT.findall(question))
    return next(iter(topics)) if len(topics) == 1 else None


def maintenance_image_sources(question, filters, intent):
    topic = maintenance_image_topic(question)
    if not topic:
        return None
    candidates = [asset for asset in filters.get('assets', [])
                  if asset.get('scope') == 'corpus' and asset.get('nativeEvidence')
                  and asset.get('originalFormat') in {'JPG', 'PNG'}
                  and (not intent.allowed or asset['originalFormat'] in intent.allowed)
                  and (not intent.preferred or asset['originalFormat'] in intent.preferred)]
    registered = [asset for asset in candidates if asset.get('purpose') in MAINTENANCE_PHOTO_TYPES
                  and topic in (asset.get('summary') or '')]
    if len(registered) == 1:
        return registered, topic
    possible = registered + [asset for asset in candidates if not asset.get('purpose') or not asset.get('summary')]
    if 0 < len(possible) <= min(2, CAPABILITIES['defaultImageCount']):
        return possible, topic
    return None


def scoped_raster_observation_sources(question, filters, intent):
    boundary_photo = (bool(re.search(r'图像|图片|照片|实拍|这张图|该图|photo', question, re.I))
                      and bool(re.search(r'量测|测量|毫米|\bmm\b|尺寸|距离|合规|规范|标准|比例尺|精确', question, re.I)))
    if (boundary_photo and not re.search(
            r'\b(?:https?|file|ftp)://|[\w.-]+\.(?:pdf|txt|csv|jsonl?|xlsx|jpe?g|png|svg|obj|step|stp)\b|'
            r'结合|比较|对比|两张|多张|附件|上传|不要(?:看|读取|使用)|排除', question, re.I)):
        photos = []
        for asset in filters.get('assets', []):
            if (asset.get('scope') != 'corpus' or not asset.get('nativeEvidence')
                    or asset.get('originalFormat') not in {'JPG', 'PNG'}
                    or intent.allowed and asset['originalFormat'] not in intent.allowed):
                continue
            # Catalogue descriptions locate a candidate; the image itself supplies evidence.
            description = ' '.join(str(asset.get(key) or '') for key in
                                   ('purpose','title','summary','attributes','recordMetadata'))
            if (re.search(r'照片|实拍|实景|现场|拍摄|摄影|实物|photograph|photo', description, re.I)
                    and not re.search(r'示意|绘制|diagram|illustration|非实拍', description, re.I)):
                photos.append(asset)
        if len(photos) == 1:
            return photos
        # An explicit sole format can select one registered source even when
        # catalogue descriptions are empty. Its content still comes from pixels.
        explicit_formats = {value.upper() for value in re.findall(
            r'(?<![A-Za-z0-9_.-])(?:JPG|JPEG|PNG)(?![A-Za-z0-9_.-])',question,re.I)}
        explicit_formats = {'JPG' if value=='JPEG' else value for value in explicit_formats}
        if re.search(r'(?:不是|非|不看|不用)\s*(?:JPG|JPEG|PNG)',question,re.I):
            explicit_formats = set()
        requested_formats = intent.allowed or intent.preferred or tuple(sorted(explicit_formats))
        if not photos and requested_formats in (('JPG',),('PNG',)):
            exact = [a for a in filters.get('assets',[]) if a.get('scope')=='corpus'
                     and a.get('nativeEvidence') and a.get('originalFormat')==requested_formats[0]]
            if len(exact)==1:
                return exact
    if boundary_photo and not re.search(
            r'\b(?:https?|file|ftp)://|[\w.-]+\.(?:pdf|txt|csv|jsonl?|xlsx|jpe?g|png|svg|obj|step|stp)\b|'
            r'结合|比较|对比|两张|多张|附件|上传|不要(?:看|读取|使用)|排除',question,re.I):
        # Empty catalogue descriptions cannot identify a photograph. Read the
        # bounded raster set and let the visible content identify the photo.
        if not intent.allowed and not intent.preferred and not re.search(
                r'(?<![A-Za-z0-9_.-])(?:JPG|JPEG|PNG)(?![A-Za-z0-9_.-])',question,re.I):
            raster = [a for a in filters.get('assets',[]) if a.get('scope')=='corpus'
                      and a.get('nativeEvidence') and a.get('originalFormat') in {'JPG','PNG'}]
            if 0 < len(raster)<=2 and len({a['originalFormat'] for a in raster})==len(raster):
                return raster
    appearance = re.search(r'图中|图上|画面|照片|图片', question) and re.search(r'外观|可见|看到|看出|遮挡|颜色|位置|结构', question)
    installation = ('图' in question and re.search(r'安装|维护', question)
                    and re.search(r'固定|卡扣|挂板|支架|连接', question))
    if (not (appearance or installation)
            or re.search(r'\b(?:https?|file|ftp)://|[\w.-]+\.(?:pdf|txt|csv|jsonl?|xlsx|jpe?g|png|webp|svg|obj|step|stp)\b|'
                         r'结合|交叉核对|比较|对比|两(?:张|幅)|附件|上传|不要(?:看|读取|使用)|排除|图纸|公差|承重|测量|剖面|三维|工程尺寸', question, re.I)):
        return None
    image_formats = {'JPG', 'PNG', 'WEBP', 'SVG'}
    if set(intent.allowed) - image_formats:
        return None
    candidates = [asset for asset in filters.get('assets', [])
                  if asset.get('scope') == 'corpus' and asset.get('nativeEvidence')
                  and asset['originalFormat'] in {'JPG', 'PNG'}
                  and (not intent.allowed or asset['originalFormat'] in intent.allowed)
                  and (not intent.preferred or asset['originalFormat'] in intent.preferred)]
    return candidates if 0 < len(candidates) <= min(2, CAPABILITIES['defaultImageCount']) else None


def _image_only_item(scope, asset, bundle, question, number):
    item = _full_page_item(scope, asset, bundle, question, number)
    item['modelFactIds'] = []
    item['facts'] = {'text': ''}
    item['nativeHumanContext'] = '本次只使用完整页面图像。'
    item['nativeModelContext'] = json.dumps({'evidenceId': bundle['evidenceId'], 'page': number,
                                             'visualObservationRequired': True},
                                            ensure_ascii=False, separators=(',', ':'))
    return item


def _read_image_observations(client, scope, question, assets, selection, topic=None):
    budget = current_budget()
    evidence = []
    reads = []
    for asset in assets:
        metadata = client.evidence_metadata(*scope, asset['assetId'], timeout=budget.remaining())
        if metadata.get('nativeEvidenceStatus') != 'AVAILABLE':
            return None
        pages = [rep for rep in metadata['representations'] if rep['kind'] == 'document_page']
        if len(pages) != 1:
            return None
        page, = pages
        operation = {'operation': 'read_representation', 'representationId': page['representationId']}
        bundle = client.read_evidence(*scope, asset['assetId'], operation, timeout=budget.remaining())
        budget.remaining()
        identity = bundle['identity']
        if (any(identity[key] != str(value) for key, value in zip(('datasetId', 'versionId', 'indexVersionId'), scope))
                or identity['assetId'] != asset['assetId'] or identity['logicalPath'] != asset['logicalPath']
                or identity['originalFormat'] != asset['originalFormat']
                or identity['sourceSha256'] != metadata['sourceSha256'] or identity['rawCommitId'] != metadata['rawCommitId']
                or bundle['representationId'] != page['representationId'] or bundle['representationKind'] != 'document_page'
                or bundle['locator']['page'] != page['selector']['page'] or verify(bundle)['operation'] != operation):
            raise ValueError('EVIDENCE_SOURCE_MISMATCH: 图像精读返回了不同冻结来源')
        item = _image_only_item(scope, asset, bundle, question, page['selector']['page'])
        item['provenance']['documentImageOnly'] = True
        if topic:
            item['provenance']['maintenanceImageTopic'] = topic
        if selection == 'authorized-raster-observation':
            item['provenance']['scopedRasterObservation'] = True
        evidence.append(item)
        reads.append(_read_record(bundle, operation))
    required = [item['evidenceId'] for item in evidence]
    return {'evidence': evidence, 'nativeReads': reads, 'status': 'OK', 'planningMethod': VERSION,
            'documentPlan': {'visualOnly': True, 'selection': selection,
                             'requiredSourcePaths': [asset['logicalPath'] for asset in assets],
                             'requiredEvidenceIds': required, 'requiredImageEvidenceIds': required}}


def direct_scoped_raster_observation(client, scope, question, filters, intent):
    assets = scoped_raster_observation_sources(question, filters, intent)
    return _read_image_observations(client, scope, question, assets, 'authorized-raster-observation') if assets else None


def asks_component_specification(question):
    return bool(re.search(r'料号|部件编号|零件编号', question) and '版本' in question
                and re.search(r'数量|用量|每套', question))


def _component_bands(native, content):
    from services.document_row_context import same_row

    for rep in native:
        if COMPONENT_NUMBER_VERSION.match(content[rep['representationId']].lstrip()):
            members = [other for other in native if same_row(rep['locator'], other['locator'])]
            if any(COMPONENT_QUANTITY.search(content[member['representationId']]) for member in members):
                yield rep, members


def component_rows(representations, content, page_bundles, question):
    from services.document_row_context import groups

    native = [rep for rep in representations if rep.get('locator', {}).get('extractionMethod') == 'pdf-native'
              and rep['representationId'] in content]
    topic = terms(question) - COMPONENT_GENERIC_TERMS
    matches = {}
    for rep, members in _component_bands(native, content):
        anchors = [(member, term) for member in members
                   for term in sorted(topic & terms(content[member['representationId']]))
                   if term in question and term in content[member['representationId']]]
        if anchors:
            matches[rep['representationId']] = (members, anchors)
    if len(matches) != 1:
        return None
    members, anchors = next(iter(matches.values()))
    anchor, term = anchors[0]
    if len(members) == 1:
        return members, []
    row_groups = [group for group in groups(native, content, [term], page_bundles)
                  if group['anchor'] == anchor and all(member in group['members'] for member in members)]
    return (members, row_groups) if row_groups else None


def component_use_topics(question):
    match = COMPONENT_USE_QUESTION.search(question)
    if match is None:
        return ()
    subjects = tuple(_compact(name) for name in re.split(r'以及|和|与|及|、', match['subjects']))
    if 2 <= len(subjects) <= 4 and len(set(subjects)) == len(subjects) and all(2 <= len(name) <= 18 for name in subjects):
        return subjects
    return ()


def component_use_rows(representations, content, subjects):
    from services.document_row_context import groups

    native = [rep for rep in representations if rep.get('locator', {}).get('extractionMethod') == 'pdf-native'
              and rep['representationId'] in content]
    bands = list(_component_bands(native, content))
    selected, selected_bands, row_groups = [], set(), []
    for subject in subjects:
        head, tail = subject[:2], subject[-2:]
        extended = re.compile(re.escape(head) + r'[\u4e00-\u9fff]{1,5}' + re.escape(tail)) if len(subject) == 4 else None
        matches = []
        for rep, members in bands:
            for member in members:
                text = _compact(content[member['representationId']])
                if subject in text or (extended and extended.search(text)):
                    matches.append((rep, members, member, subject if subject in text else head))
                    break
        if len(matches) != 1 or matches[0][0]['representationId'] in selected_bands:
            return None
        rep, members, anchor, term = matches[0]
        selected_bands.add(rep['representationId'])
        selected.extend(members)
        row_groups.extend(group for group in groups(native, content, [term]) if group['anchor'] == anchor)
    pages = {rep['locator']['page'] for rep in selected}
    # These passages carry the quantity heading and the matching page's declared limits.
    related = [rep for rep in native if rep['locator'].get('page') in pages
               and COMPONENT_USE_CONTEXT.search(content[rep['representationId']])]
    return [*selected, *related[:2]], row_groups


def product_document_identifier(question):
    if not PRODUCT_DOCUMENT_TOPIC.search(question) and not asks_component_specification(question):
        return None
    if re.search(r'\b(?:https?|file|ftp)://|[\w.-]+\.(?:pdf|txt|csv|jsonl?|xlsx|jpe?g|png|svg|obj|step|stp)\b|'
                 r'其他(?:文件|资料|来源)|多份(?:文件|资料)|跨(?:文件|来源)|图像|示意图|照片|图纸|'
                 r'不要(?:看|读取|使用)|排除', question, re.I):
        return None
    identifiers = set(PRODUCT_DOCUMENT_ID.findall(question))
    return next(iter(identifiers)) if len(identifiers) == 1 else None


def product_pdf_comparison_identifier(question):
    if (not re.search(r'(?<![A-Za-z0-9_.-])PDF(?![A-Za-z0-9_.-])', question, re.I)
            or not re.search(r'比较|对比|对照|核对|一致|相同|差异', question)
            or not re.search(r'产品|内外机|室内机|室外机|内机|外机|型号|规格', question)
            or not re.search(r'型号|尺寸|宽度|深度|高度|规格|参数|供电|电压|频率', question)
            or re.search(r'\b(?:https?|file|ftp)://|[\w.-]+\.(?:pdf|txt|csv|jsonl?|xlsx|jpe?g|png|svg|obj|step|stp)\b|'
                         r'只(?:用|看|读|依据|根据|参考)|仅(?:用|看|读|依据|根据|参考)|不要(?:看|读|使用)|排除', question, re.I)):
        return None
    identifiers = {token.casefold() for token in COMPARISON_MODEL_ID.findall(question)}
    return next(iter(identifiers)) if len(identifiers) == 1 else None


def matches_product_document_filename(identifier, path, *, product_comparison=False):
    name = path.rsplit('/', 1)[-1].rsplit('.', 1)[0]
    identifier_characters = r'A-Za-z0-9' if product_comparison else r'A-Za-z0-9-'
    return bool(re.search(rf'(?<![{identifier_characters}]){re.escape(identifier)}(?![{identifier_characters}])', name, re.I))


def locate_product_document(question, filters, intent, *, product_comparison=False):
    identifier = (product_pdf_comparison_identifier(question) if product_comparison
                  else product_document_identifier(question))
    if not identifier or (intent.allowed and 'PDF' not in intent.allowed):
        return None
    if not product_comparison and intent.allowed and intent.allowed != ('PDF',):
        return None
    matches = [asset for asset in filters.get('assets', [])
               if asset.get('scope') == 'corpus' and asset.get('originalFormat') == 'PDF'
               and asset.get('nativeEvidence') and matches_product_document_filename(
                   identifier, asset['logicalPath'], product_comparison=product_comparison)]
    return (matches[0], identifier) if len(matches) == 1 else None


def product_comparison_sku(question, record):
    identifier = product_pdf_comparison_identifier(question)
    identity = record.get('identity', {})
    if not identifier or identity.get('originalFormat') != 'JSON':
        return None
    receipt = verify(record)
    if receipt['operation'].get('operation') != 'read_table' or record.get('coverage', {}).get('returnedRowCount') != 1:
        return None
    expected = 'sku-' + identifier
    if any(fact.get('field') == 'sku_id' and str(fact.get('value', '')).casefold() == expected
           for fact in record.get('facts', [])):
        return expected
    return None


def _native_product_text_anchor(text, identifier, sku):
    tokens = {token.casefold() for token in COMPARISON_MODEL_ID.findall(text)}
    return identifier in tokens or bool(sku and sku in tokens)


def verify_product_document_anchor(question, identity, proof):
    if not isinstance(proof, dict):
        return False
    comparison = product_pdf_comparison_identifier(question) if proof.get('locator') == 'product-pdf-comparison' else None
    identifier = comparison or product_document_identifier(question)
    if not identifier or proof.get('identifier') != identifier:
        return False
    bundle = proof.get('bundle') or {}
    if (bundle.get('identity') != identity or identity.get('originalFormat') != 'PDF'
            or not matches_product_document_filename(identifier, identity.get('logicalPath', ''), product_comparison=bool(comparison))
            or bundle.get('representationKind') != 'text_passage'
            or bundle.get('locator', {}).get('extractionMethod') != 'pdf-native'):
        return False
    receipt = verify(bundle)
    if (receipt['operation'] != {'operation': 'read_representation',
                                'representationId': bundle.get('representationId')}
            or bundle.get('coverage', {}).get('truncated')):
        return False
    text = bundle.get('content', {}).get('text', '')
    if comparison:
        record = proof.get('productRecord') or {}
        if record and any(record.get('identity', {}).get(key) != identity.get(key)
                          for key in ('datasetId', 'versionId', 'indexVersionId')):
            return False
        sku = product_comparison_sku(question, record) if record else None
        anchor_matches = _native_product_text_anchor(text, identifier, sku)
    else:
        anchor_matches = any(token.casefold() == identifier.casefold() for token in PRODUCT_DOCUMENT_ID.findall(text))
    return bool(any(fact.get('evidenceNature') == 'source_text' and fact.get('value') == text
                    for fact in bundle.get('facts', [])) and anchor_matches)


def direct_document_question(client,scope,question,filters,intent,*,installation_asset_id=None,
                             located_by_filename=False,allow_product_locator=False,allow_maintenance_locator=False,
                             product_comparison=False, product_record=None):
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus'
        and asset.get('originalFormat') in {'PDF','JPG','PNG'} and asset.get('nativeEvidence')
        and (str(asset['assetId']) == installation_asset_id if installation_asset_id else
             re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(asset['logicalPath'].rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question,re.I))]
    product_identifier = None
    if not assets and allow_product_locator and not installation_asset_id:
        product = locate_product_document(question, filters, intent, product_comparison=product_comparison)
        if product:
            asset, product_identifier = product
            assets = [asset]
    if not assets and allow_maintenance_locator and not installation_asset_id:
        maintenance = maintenance_image_sources(question, filters, intent)
        if maintenance:
            candidates, topic = maintenance
            return _read_image_observations(client, scope, question, candidates, 'authorized-maintenance-images', topic)
    if not assets:return None
    if re.search(r'\b(?:https?|file|ftp)://',question,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 外部地址不能替换为快照中的同名文件。请明确已授权来源。')
    if len(assets)!=1:raise ValueError('NEEDS_CLARIFICATION: 本次精读需要一个明确来源；多个同名或不同文件需分别规划。')
    asset=assets[0]
    bound_installation = installation_asset_id is not None
    if bound_installation and (asset['originalFormat'] not in {'JPG', 'PNG'} or intent.allowed != (asset['originalFormat'],)):
        return None
    if intent.allowed and asset['originalFormat'] not in intent.allowed:
        raise ValueError('NEEDS_CLARIFICATION: 指定文件与来源格式限制不一致。')
    if re.search(r'不要(?:看|读取|使用)|排除(?:文件|图片|资料)?',question):return None
    budget = current_budget()
    started = time.monotonic()
    reads = []
    page_limit = budget.profile['documentPagesPerBatch']
    metadata=client.evidence_metadata(*scope,asset['assetId'],timeout=budget.remaining())
    if metadata.get('nativeEvidenceStatus')!='AVAILABLE':raise ValueError('EVIDENCE_MANIFEST_UNAVAILABLE: 原生文档清单不可用')
    def read(operation):
        if len(reads) >= read_limit:
            raise ValueError('EVIDENCE_DOCUMENT_PLAN_LIMIT: 文档精读达到步骤上限，请明确页码或区域。')
        bundle=client.read_evidence(*scope,asset['assetId'],operation,timeout=budget.remaining())
        budget.remaining()
        identity=bundle['identity']
        if any(identity[key]!=str(value) for key,value in zip(('datasetId','versionId','indexVersionId'),scope)) or identity['assetId']!=asset['assetId'] or identity['sourceSha256']!=metadata['sourceSha256']:
            raise ValueError('EVIDENCE_SOURCE_MISMATCH: 文档精读返回了不同冻结来源')
        reads.append(_read_record(bundle,operation));return bundle
    reps=metadata['representations'];pages=sorted([rep for rep in reps if rep['kind']=='document_page'],key=lambda rep:rep['selector']['page'])
    if not pages:raise ValueError('EVIDENCE_DOCUMENT_PAGE_UNAVAILABLE: 该索引未注册文档页')
    pages = _selected_pages(question, pages)
    batches = [pages[start:start + page_limit] for start in range(0, len(pages), page_limit)]
    read_limit = len(batches) * budget.profile['documentReadOperationsPerBatch']
    name=re.escape(asset['logicalPath'].rsplit('/',1)[-1])
    full_image_only=bool(re.search(
        r'(?:^|[。！？；!?;\n])\s*(?:请\s*)?(?:只|仅)(?:看|读|阅读|依据|根据|参考|使用)\s*(?:文件\s*)?'+name
        +r'\s*(?:的\s*)?(?:'+PAGE_SELECTION+r'\s*)?(?:的\s*)?(?:完整(?:图像|图片|页面|照片)|整页图像)',question,re.I))
    if full_image_only and len(pages) > CAPABILITIES['maxImageCount']:
        raise ValueError('NEEDS_CLARIFICATION: 需观察的完整页面超过本次图片预算，请指定关键页。')
    page_bundles = {}
    for batch in batches:
        budget.remaining()
        for page in batch:
            page_bundles[page['selector']['page']] = read({
                'operation': 'read_representation', 'representationId': page['representationId']})
    batch_trace = {'pageBatches': [[page['selector']['page'] for page in batch] for batch in batches],
                   'pageBudgetPerBatch': page_limit, 'readBudget': read_limit}
    if full_image_only or bound_installation:
        evidence=[]
        for number,bundle in page_bundles.items():
            item=_image_only_item(scope,asset,bundle,question,number)
            if bound_installation:
                item['provenance']['installationVisualTarget'] = (
                    'single-current-file-locator-1' if located_by_filename else 'unique-bound-installation-image-1')
                if located_by_filename:
                    item['provenance']['imageSelectionMethod'] = 'single-current-image-locator'
                    context = json.loads(item['nativeModelContext'])
                    context['sourceIdentityLimit'] = '文件名仅用来定位图像。根据图像描述位置，无法从画面核实型号时不作确认。'
                    item['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',', ':'))
            else:
                item['provenance']['documentImageOnly']=True
            item['provenance']['modelFactSelection']={'selectedFactIds':[],
                'reason':'BOUND_INSTALLATION_IMAGE' if bound_installation else 'EXPLICIT_FULL_IMAGE_ONLY',
                'readFactIds':[fact['factId'] for fact in bundle['facts']]}
            evidence.append(item)
        return {'evidence':evidence,'nativeReads':reads,'status':'OK','planningMethod':VERSION,
            'documentPlan':{**batch_trace, 'pagesRead':list(page_bundles), 'visualOnly':full_image_only,
                'selection':'bound-installation-image' if bound_installation else 'explicit-full-image-only',
                'requiredSourcePaths':[asset['logicalPath']],
                'requiredEvidenceIds':[item['evidenceId'] for item in evidence],
                'requiredImageEvidenceIds':[item['evidenceId'] for item in evidence]}}
    residual=question.replace(asset['logicalPath'].rsplit('/',1)[-1],' ')
    residual=re.sub(r'PDF|PNG|JPG|JPEG|文件|图片|只看|只依据|末页|最后一页|打印|'+PAGE_SELECTION,' ',residual,flags=re.I)
    residual=re.sub(r'(?:不要|不用|不能|无需|不应|请勿|而非|而不是)[^，。；;！？?\n]*',' ',residual)
    query_terms=terms(residual)
    identifiers=re.findall(r'(?<![A-Za-z0-9_])(?=[A-Za-z0-9-]*\d)[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_])',residual)
    content_by_rep={};words_by_page={}
    for number,bundle in page_bundles.items():
        blocks={block['blockIndex']:'\n'.join(''.join(span['text'] for span in line['spans']) for line in block['lines']) for block in bundle['content'].get('nativeBlocks',[])}
        for rep in reps:
            loc=rep.get('locator',{})
            if rep['kind']=='text_passage' and loc.get('page',1)==number and loc.get('extractionMethod')=='pdf-native':
                content_by_rep[rep['representationId']]=blocks.get(loc.get('blockIndex'),'')
        word_reps=[rep for rep in reps if rep['kind']=='document_words' and rep['selector']['page']==number]
        if bundle['content'].get('ocr',{}).get('status') not in {'NOT_NEEDED','NOT_RUN'}:
            words=[]
            for rep in sorted(word_reps,key=lambda rep:rep['locator']['wordPageIndex']):
                words.extend(read({'operation':'read_representation','representationId':rep['representationId']})['content']['words'])
            if len(words)>20000 or len({(word['extractionMethod'],word['wordIndex']) for word in words})!=len(words):
                raise ValueError('EVIDENCE_DOCUMENT_WORD_LIMIT: 文档词序数量超限或标识重复')
            words_by_page[number]=words
            for passage in reps:
                loc=passage.get('locator',{})
                if passage['kind']!='text_passage' or loc.get('page',1)!=number or loc.get('extractionMethod')!='tesseract-ocr':continue
                indices=set(loc.get('wordIndices',[]));text=' '.join(word['text'] for word in words if word['extractionMethod']=='tesseract-ocr' and word['wordIndex'] in indices)
                if text:content_by_rep[passage['representationId']]=text
    all_text=' '.join(content_by_rep.values())
    identifiers_confirmed=not identifiers or all(_compact(identifier) in _compact(all_text) for identifier in identifiers)
    if not identifiers_confirmed and product_identifier:
        return None
    if not identifiers_confirmed and asset['originalFormat']=='PDF':
        return {'evidence':[],'nativeReads':reads,'status':'UNKNOWN_ENTITY','planningMethod':VERSION}
    ranked=[];context=[]
    for rep in reps:
        text=content_by_rep.get(rep['representationId'],'')
        if not text:continue
        overlap=query_terms&terms(text);anchors=sum(_compact(identifier) in _compact(text) for identifier in identifiers)
        if overlap or anchors:ranked.append((anchors*100+len(overlap),rep,text,overlap))
        # Qualifications belong with the selected values. A unit-only context
        # search used to omit written declaration/measurement limitations.
        kind=context_kind(text)
        if kind:context.append((rep,text,kind=='source_qualification'))
    ranked.sort(key=lambda row:(-row[0],row[1]['representationId']))
    if not identifiers_confirmed:ranked=[]
    chosen=[];covered=set()
    for score,rep,text,overlap in ranked:
        if len(chosen)>=4:break
        if chosen and not overlap-covered and not any(_compact(identifier) in _compact(text) for identifier in identifiers):continue
        chosen.append((rep,text));covered.update(overlap)
    # PDF rows can be split into one native text block per column. Matching
    # a row identifier alone must not discard its horizontally aligned values.
    from services.document_row_context import groups,VERSION as ROW_VERSION,MAX_ROW_SOURCES
    component_request = bool(product_identifier and asks_component_specification(question))
    component_subjects = component_use_topics(question) if product_identifier and not component_request else ()
    component = component_rows(reps, content_by_rep, page_bundles, question) if component_request else (
        component_use_rows(reps, content_by_rep, component_subjects) if component_subjects else None)
    if (component_request or component_subjects) and not component:
        return None
    row_groups=groups(reps,content_by_rep,identifiers,page_bundles,question) if asset['originalFormat']=='PDF' else []
    component_members = []
    if component:
        component_members, row_groups = component
    component_ids = {rep['representationId'] for rep in component_members}
    row_members={rep['representationId']:rep for group in row_groups for rep in [*group['members'],*group['headers']]}
    row_members.update((rep['representationId'], rep) for rep in component_members)
    if len(row_members)>MAX_ROW_SOURCES:
        raise ValueError('EVIDENCE_DOCUMENT_PLAN_LIMIT: 所需原生表行列超过本次精读范围，请缩小行数。')
    if row_members:
        ordered=sorted(row_members.values(),key=lambda rep:(rep['locator']['page'],rep['locator']['bbox'][1],rep['locator']['bbox'][0],rep['representationId']))
        chosen=[(rep,content_by_rep[rep['representationId']]) for rep in ordered]
    if chosen:
        context.sort(key=lambda row:(not row[2],row[0]['representationId']))
        for rep,text,_ in context:
            if len(chosen)>=max(6,len(row_members)):break
            if rep['representationId'] not in {value[0]['representationId'] for value in chosen}:chosen.append((rep,text))
    product_anchor = None
    if product_identifier:
        sku = product_comparison_sku(question, product_record) if product_comparison and product_record else None
        product_anchor = next((rep for rep in reps
                               if rep.get('locator', {}).get('extractionMethod') == 'pdf-native'
                               and (_native_product_text_anchor(content_by_rep.get(rep['representationId'], ''), product_identifier, sku)
                                    if product_comparison else any(match.casefold() == product_identifier.casefold()
                                    for match in PRODUCT_DOCUMENT_ID.findall(content_by_rep.get(rep['representationId'], ''))))), None)
        topic = PRODUCT_DOCUMENT_TOPIC.sub(' ', re.sub(re.escape(product_identifier), ' ', residual, flags=re.I))
        topic_terms = terms(topic)
        requested_specs = []
        compares_dimensions = product_comparison and DIMENSION_QUERY.search(question)
        compares_power = product_comparison and POWER_QUERY.search(question)
        if product_anchor and (compares_dimensions or compares_power):
            for rep in reps:
                text = content_by_rep.get(rep['representationId'], '')
                location = rep.get('locator', {})
                if location.get('extractionMethod') == 'pdf-native' and (
                    (compares_dimensions and UNIT_DIMENSIONS.search(text) and UNIT_LABEL.search(text))
                    or (compares_power and location.get('page') == product_anchor['locator']['page'] and POWER_DECLARATION.search(text))
                ):
                    requested_specs.append((rep, text))
            if requested_specs:
                chosen = list({rep['representationId']: (rep, text) for rep, text in [*chosen, *requested_specs]}.values())
        topic_matches = any(len(topic_terms & terms(text)) >= 2 for _, text in chosen)
        if product_anchor is None or (not component and not requested_specs and not topic_matches):
            return None
        if all(rep['representationId'] != product_anchor['representationId'] for rep, _ in chosen):
            chosen.append((product_anchor, content_by_rep[product_anchor['representationId']]))
    evidence=[];read_bundles={}
    for rep,_ in chosen:
        operation={'operation':'read_representation','representationId':rep['representationId']};bundle=read(operation)
        read_bundles[rep['representationId']]=bundle
        item=_item(scope,asset,bundle,question,operation)
        item['provenance'].update(documentContentTerms=sorted(query_terms&terms(bundle['content']['text'])),documentContextOnly=rep in [row[0] for row in context])
        if component_subjects and rep['representationId'] in component_ids and re.search(
                COMPONENT_USE_QUANTITY_HEADER, bundle['content']['text']):
            item['provenance']['documentComponentUseQuantityHeader'] = True
        evidence.append(item)
    for item in evidence:
        member=item['nativeBundle']['representationId']
        anchors=[{'identifier':identifier,'bundle':read_bundles[group['anchor']['representationId']]}
            for group in row_groups if member in {rep['representationId'] for rep in group['members']}
            for identifier in group['identifiers']]
        if anchors:
            item['provenance']['documentRowContext']={'version':ROW_VERSION,'anchors':anchors,
                'selectionNature':'same-page-native-row-band-context-not-a-semantic-join'}
            item['provenance']['formatDiversityContribution']=False
            model=json.loads(item['nativeModelContext'])
            model['row']=[item['locator']['page'],*sorted({anchor['identifier'] for anchor in anchors})]
            # The shared prompt declares native-row context once. Preserve
            # values and fact IDs; omit only repeated source_text type labels.
            for fact in model['facts']:
                if fact.get('nature')=='source_text':fact.pop('nature')
            labels=[(group,group['columnHeaders'][member]) for group in row_groups if member in group['columnHeaders']]
            if labels:
                group,label=labels[0];header=read_bundles[group['headers'][0]['representationId']]
                model['header']=[header['facts'][0]['factId'],label]
            item['nativeModelContext']=json.dumps(model,ensure_ascii=False,separators=(',',':'))
        header_groups=[group for group in row_groups if member in {rep['representationId'] for rep in group['headers']}]
        if header_groups:
            item['provenance']['documentHeaderForRows']={'version':ROW_VERSION,'page':item['locator']['page'],
                'anchorIdentifiers':sorted({identifier for group in header_groups for identifier in group['identifiers']}),
                'nativeColumnLabels':header_groups[0]['headerColumns']}
            item['provenance']['formatDiversityContribution']=False
            model=json.loads(item['nativeModelContext']);model['page']=item['locator']['page']
            item['nativeModelContext']=json.dumps(model,ensure_ascii=False,separators=(',',':'))
    from services.document_time import calculate_duration,VERSION as TIME_VERSION
    calculated=calculate_duration(question,evidence) if asset['originalFormat']=='PDF' else None
    if calculated:
        bundle=calculated['bundle'];operation={'operation':'calculate_document_duration','inputReadId':calculated['inputs']['inputReadId']}
        item=_item(scope,asset,bundle,question,operation)
        item['provenance'].update(documentTimeVersion=TIME_VERSION,calculationInputs=calculated['inputs'],
            documentTimeInputBundle=calculated['parent']['nativeBundle'])
        item['nativeModelContext']=json.dumps({'evidenceId':bundle['evidenceId'],'facts':bundle['facts'],
            'calculation':bundle['content']['calculation']},ensure_ascii=False,separators=(',',':'))
        evidence=[item];record=_read_record(bundle,operation);record['state']='CALCULATED';reads.append(record)
    if asset['originalFormat'] in {'JPG','PNG'} and evidence:
        by_page={}
        for item in evidence:
            loc=item['locator']
            if loc.get('bbox'):by_page.setdefault(loc.get('page',1),[]).append(loc['bbox'])
        for number,boxes in by_page.items():
            bounds=page_bundles[number]['locator']['bbox']
            box=[max(bounds[0],min(row[0] for row in boxes)-12),max(bounds[1],min(row[1] for row in boxes)-12),
                min(bounds[2],max(row[2] for row in boxes)+12),min(bounds[3],max(row[3] for row in boxes)+12)]
            first=next(item for item in evidence if item['locator'].get('page',1)==number)
            first['nativeRegionRequest']={'page':number,'bbox':box,'dpi':200,'maxPixels':1000000}
    visual_terms=r'图中|画面|可见|外观|颜色|形状|位置|布局|方向|开口|结构|读数|数量|文字|标注|部件|前面|后面|左|右|上|下|顶部|底部|几个|几块|几扇|几名|对着'
    visual=(asset['originalFormat'] in {'JPG','PNG'} and bool(chosen or re.search(visual_terms,residual))) or (
        asset['originalFormat']=='PDF' and bool(re.search(r'图像|图表|图纸|示意图|照片|颜色|外观|标注|看图',residual)))
    if visual:
        if len(page_bundles) > CAPABILITIES['maxImageCount']:
            raise ValueError('NEEDS_CLARIFICATION: 需观察的完整页面超过本次图片预算，请指定关键页。')
        for number,bundle in reversed(list(page_bundles.items())):
            item=_full_page_item(scope,asset,bundle,question,number)
            if not identifiers_confirmed:
                item['nativeModelContext']=json.dumps({'evidenceId':bundle['evidenceId'],'visualObservationRequired':True,
                    'requestedIdentifiers':identifiers,'textRecognitionDoesNotConfirmTarget':True,
                    'instruction':'需在实际图像中确认标识及关联；无法辨认就说明不确定。'},ensure_ascii=False,separators=(',',':'))
            # Put the complete visual source before OCR crops, so a caption
            # cannot be presented as the only image of a requested appliance.
            evidence.insert(0,item)
        if not identifiers_confirmed and len(page_bundles)==1:
            number=next(iter(page_bundles));bounds=page_bundles[number]['locator']['bbox']
            anchors=[word for word in words_by_page.get(number,[]) if any(_compact(identifier) in _compact(word['text']) for identifier in identifiers)]
            if anchors:
                height=max(word['bbox'][3]-word['bbox'][1] for word in anchors)
                box=[bounds[0],max(bounds[1],min(word['bbox'][1] for word in anchors)-2*height),bounds[2],
                    min(bounds[3],max(word['bbox'][3] for word in anchors)+3*height)]
                operation={'operation':'read_region_text','page':number,'bbox':box,'limit':256};bundle=read(operation)
                item=_item(scope,asset,bundle,question,operation,visual=True)
                item['nativeRegionRequest']={'page':number,'bbox':box,'dpi':200,'maxPixels':1000000}
                item['provenance']['identifierRecognitionStatus']='PARTIAL_OCR_MATCH_REQUIRES_VISUAL_CONFIRMATION'
                evidence.append(item)
    if product_anchor:
        anchor = read_bundles[product_anchor['representationId']]
        proof = {'identifier': product_identifier, 'bundle': anchor}
        if product_comparison:
            proof['locator'] = 'product-pdf-comparison'
            if product_record:
                proof['productRecord'] = product_record
        if not evidence or not verify_product_document_anchor(question, anchor['identity'], proof):
            return None
        for item in evidence:
            item['provenance']['productDocumentAnchor'] = proof
    return {'evidence':evidence,'nativeReads':reads,'status':'OK','planningMethod':VERSION,
        'documentPlan':{'pagesRead':list(page_bundles),'steps':len(reads),'elapsedSeconds':time.monotonic()-started,
            'requiredImageEvidenceIds':[item['evidenceId'] for item in evidence if item['provenance'].get('documentFullView')],
             'rowContextVersion':ROW_VERSION,'requiredEvidenceIds':[
                 item['evidenceId'] for item in evidence
                 if (item['provenance'].get('documentRowContext') or item['provenance'].get('documentHeaderForRows')
                     or item['nativeBundle']['representationId'] in component_ids
                     or (product_anchor and item['nativeBundle']['representationId'] == product_anchor['representationId']))],
            'rowColumnProjections':[{'page':g['anchor']['locator']['page'],'anchorIdentifiers':g['identifiers'],
                'unrequestedColumnRepresentationIds':g.get('unrequestedColumnRepresentationIds',[])} for g in row_groups],
             **batch_trace,'selection':('authorized-model-and-native-text' if product_anchor
                                         else 'source-text-overlap-with-page-qualifications-and-units'),
            'omittedContextRepresentationIds':[rep['representationId'] for rep,_,_ in context if rep['representationId'] not in {row[0]['representationId'] for row in chosen}]}}
