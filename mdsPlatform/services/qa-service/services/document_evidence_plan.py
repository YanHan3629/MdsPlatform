"""Bounded reading of a caller-named document, using frozen native locators.

An explicit file is a read target, not a statement of its contents. Unnamed
retrieval keeps its existing absolute admission rules. No model, free path or
evaluation label participates in this plan.
"""
from __future__ import annotations
import json,re,time
from services.evidence_policy import terms
from services.document_contract import READ_VERSION,context_kind

VERSION=READ_VERSION
MAX_PAGES=4
MAX_READS=24
MAX_SECONDS=45


def _compact(text):return re.sub(r'\s+','',text).casefold()


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


def direct_document_question(client,scope,question,filters,intent):
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus'
        and asset.get('originalFormat') in {'PDF','JPG','PNG'} and asset.get('nativeEvidence')
        and re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(asset['logicalPath'].rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question,re.I)]
    if not assets:return None
    if re.search(r'\b(?:https?|file|ftp)://',question,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 外部地址不能替换为快照中的同名文件。请明确已授权来源。')
    if len(assets)!=1:raise ValueError('NEEDS_CLARIFICATION: 本次精读需要一个明确来源；多个同名或不同文件需分别规划。')
    asset=assets[0]
    if intent.allowed and asset['originalFormat'] not in intent.allowed:
        raise ValueError('NEEDS_CLARIFICATION: 指定文件与来源格式限制不一致。')
    if re.search(r'不要(?:看|读取|使用)|排除(?:文件|图片|资料)?',question):return None
    started=time.monotonic();reads=[]
    metadata=client.evidence_metadata(*scope,asset['assetId'])
    if metadata.get('nativeEvidenceStatus')!='AVAILABLE':raise ValueError('EVIDENCE_MANIFEST_UNAVAILABLE: 原生文档清单不可用')
    def read(operation):
        if len(reads)>=MAX_READS or time.monotonic()-started>MAX_SECONDS:
            raise ValueError('EVIDENCE_DOCUMENT_PLAN_LIMIT: 文档精读达到步骤或时间限制，请指定页码或区域。')
        bundle=client.read_evidence(*scope,asset['assetId'],operation)
        if time.monotonic()-started>MAX_SECONDS:raise ValueError('EVIDENCE_DOCUMENT_PLAN_LIMIT: 文档精读超过本次时间限制')
        identity=bundle['identity']
        if any(identity[key]!=str(value) for key,value in zip(('datasetId','versionId','indexVersionId'),scope)) or identity['assetId']!=asset['assetId'] or identity['sourceSha256']!=metadata['sourceSha256']:
            raise ValueError('EVIDENCE_SOURCE_MISMATCH: 文档精读返回了不同冻结来源')
        reads.append(_read_record(bundle,operation));return bundle
    reps=metadata['representations'];pages=sorted([rep for rep in reps if rep['kind']=='document_page'],key=lambda rep:rep['selector']['page'])
    if not pages:raise ValueError('EVIDENCE_DOCUMENT_PAGE_UNAVAILABLE: 该索引未注册文档页')
    page_numbers={int(value) for value in re.findall(r'第\s*(\d+)\s*页',question)}
    if re.search(r'末页|最后一页',question):page_numbers.add(pages[-1]['selector']['page'])
    if page_numbers:
        if not page_numbers<={page['selector']['page'] for page in pages}:raise ValueError('NEEDS_CLARIFICATION: 指定页码不在该冻结文件中。')
        pages=[page for page in pages if page['selector']['page'] in page_numbers]
    if len(pages)>MAX_PAGES:raise ValueError('NEEDS_CLARIFICATION: 文件超过本次四页精读范围，请指定页码或区域。')
    page_bundles={page['selector']['page']:read({'operation':'read_representation','representationId':page['representationId']}) for page in pages}
    residual=question.replace(asset['logicalPath'].rsplit('/',1)[-1],' ')
    residual=re.sub(r'PDF|PNG|JPG|JPEG|文件|图片|只看|只依据|末页|最后一页|打印|第\s*\d+\s*页',' ',residual,flags=re.I)
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
    row_groups=groups(reps,content_by_rep,identifiers,page_bundles,question) if asset['originalFormat']=='PDF' else []
    row_members={rep['representationId']:rep for group in row_groups for rep in [*group['members'],*group['headers']]}
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
    evidence=[];read_bundles={}
    for rep,_ in chosen:
        operation={'operation':'read_representation','representationId':rep['representationId']};bundle=read(operation)
        read_bundles[rep['representationId']]=bundle
        item=_item(scope,asset,bundle,question,operation)
        item['provenance'].update(documentContentTerms=sorted(query_terms&terms(bundle['content']['text'])),documentContextOnly=rep in [row[0] for row in context])
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
        for number,bundle in list(page_bundles.items())[:2]:
            operation={'operation':'read_representation','representationId':bundle['representationId']}
            item=_item(scope,asset,bundle,question,operation,visual=True)
            item['provenance']['documentFullView']=True
            if not identifiers_confirmed:
                item['nativeModelContext']=json.dumps({'evidenceId':bundle['evidenceId'],'visualObservationRequired':True,
                    'requestedIdentifiers':identifiers,'textRecognitionDoesNotConfirmTarget':True,
                    'instruction':'需在实际图像中确认标识及关联；无法辨认就说明不确定。'},ensure_ascii=False,separators=(',',':'))
            item['nativeRegionRequest']={'page':number,'bbox':bundle['locator']['bbox'],'dpi':160,'maxPixels':1000000}
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
    return {'evidence':evidence,'nativeReads':reads,'status':'OK','planningMethod':VERSION,
        'documentPlan':{'pagesRead':list(page_bundles),'steps':len(reads),'elapsedSeconds':time.monotonic()-started,
            'rowContextVersion':ROW_VERSION,'requiredEvidenceIds':[item['evidenceId'] for item in evidence if item['provenance'].get('documentRowContext') or item['provenance'].get('documentHeaderForRows')],
            'rowColumnProjections':[{'page':g['anchor']['locator']['page'],'anchorIdentifiers':g['identifiers'],
                'unrequestedColumnRepresentationIds':g.get('unrequestedColumnRepresentationIds',[])} for g in row_groups],
            'readBudget':MAX_READS,'pageBudget':MAX_PAGES,'selection':'source-text-overlap-with-page-qualifications-and-units',
            'omittedContextRepresentationIds':[rep['representationId'] for rep,_,_ in context if rep['representationId'] not in {row[0]['representationId'] for row in chosen}]}}
