"""Read selected native evidence under the current caller, before budgeting it."""
from __future__ import annotations
import hashlib,json,re,uuid
from services.native_record_context import context_record
from services.document_contract import READ_VERSION as DOCUMENT_READ_VERSION
CSV_READ_VERSION='native-csv-selected-fields-2026-10-27.1'

def csv_empty_source(fact,bundle):
    """CSV has no native NULL token/type; retain the actual empty field."""
    if bundle.get('identity',{}).get('originalFormat')=='CSV' and fact.get('evidenceNature')=='source_record' and fact.get('value') is None and fact.get('sourceValue')=='':
        return {'kind':'csv-empty-field','sourceValue':'','nullVersusEmptyString':'unspecified-by-source','normalizedReadValue':None}
    return None


def _same_locator(left,right):
    keys=('sheet','jsonPath','jsonlLine','page','table','jsonPointer')
    return all(key not in left or key not in right or left[key]==right[key] for key in keys)


def _fields(question,table,item):
    focused=set(item.get('provenance',{}).get('expandedFields',[]))
    fields=table.get('fields',table.get('columns',[]))
    for field in fields:
        name=field['name'];labels=[name,field.get('description',''),field.get('sourceDescription','')]
        if any(label and re.search(r'(?<![A-Za-z0-9_])'+re.escape(label)+r'(?![A-Za-z0-9_])',question,re.I) for label in labels):focused.add(name)
        if name=='id' or name.endswith('_id'):
            if any(isinstance(value,str) and len(value)>1 and re.search(r'(?<![A-Za-z0-9_-])'+re.escape(value)+r'(?![A-Za-z0-9_-])',question,re.I) for value in field.get('enum',[])):focused.add(name)
    return [field['name'] for field in fields if field['name'] in focused][:64]


def _native_numbers(value,pointer,numbers):
    if pointer in numbers:return {'sourceNumber':numbers[pointer]['lexeme'],'type':'number'}
    if isinstance(value,dict):return {key:_native_numbers(child,pointer+'/'+key.replace('~','~0').replace('/','~1'),numbers) for key,child in value.items()}
    if isinstance(value,list):return [_native_numbers(child,pointer+'/'+str(index),numbers) for index,child in enumerate(value)]
    return value


def read_selected(client,scope,item,question,filters,cache):
    if item.get('nativeBundle'):return item,None
    if item.get('evidenceType')=='calculation':
        if item.get('semanticSpace')=='timeseries.facts':
            from services.timeseries_lineage import read_calculation
            return read_calculation(client,scope,item,filters,cache)
        from services.calculation_lineage import read_calculation
        return read_calculation(client,scope,item,filters,cache)
    if item.get('facts',{}).get('visualOnly'):return item,None
    asset_id=str(item.get('assetId') or '')
    asset=next((asset for asset in filters.get('assets',[]) if str(asset['assetId'])==asset_id),{})
    if not asset.get('nativeEvidence'):return item,{'candidateEvidenceId':item['evidenceId'],'state':'LEGACY_INDEX_REBUILD_REQUIRED'}
    if asset_id not in cache:cache[asset_id]=client.evidence_metadata(*scope,asset_id)
    metadata=cache[asset_id]
    if metadata.get('nativeEvidenceStatus')!='AVAILABLE':raise ValueError('EVIDENCE_MANIFEST_UNAVAILABLE: 候选快照没有可读取的原生证据')
    locator=item.get('locator',{});representations=metadata['representations'];operation=None
    table_id=item.get('provenance',{}).get('tableId')
    tables=[table for table in filters.get('tables',[]) if str(table['assetId'])==asset_id and _same_locator(locator,table.get('locator',{}))]
    if locator.get('jsonPointer') is not None and metadata['originalFormat'] in {'JSON','JSONL'}:
        pointer=locator['jsonPointer']
        try:record=json.loads(item.get('facts',{}).get('text',''))
        except (ValueError,TypeError):record=None
        if isinstance(record,dict):
            names=[name for name in record if re.search(r'(?<![A-Za-z0-9_])'+re.escape(name)+r'(?![A-Za-z0-9_])',question,re.I)]
            if len(names)==1:pointer+='/'+names[0].replace('~','~0').replace('/','~1')
        operation={'operation':'read_json_subtree','jsonPointer':pointer}
        if metadata['originalFormat']=='JSONL':
            if not locator.get('jsonlLine'):raise ValueError('EVIDENCE_JSONL_LINE_REQUIRED: 原生JSONL候选缺少物理行定位')
            operation['jsonlLine']=locator['jsonlLine']
    elif locator.get('rawRow') is not None:
        if table_id:tables=[table for table in tables if table['tableId']==table_id]
        if len(tables)!=1:raise ValueError('EVIDENCE_TABLE_AMBIGUOUS: 候选记录未能唯一绑定工作表或数组')
        table=tables[0]
        operation={'operation':'read_table','tableId':table['tableId'],'rowStart':locator['rawRow'],'rowEnd':locator['rawRow'],'limit':1}
        fields=_fields(question,table,item)
        if fields:operation['fields']=fields
    else:
        choices=[descriptor for descriptor in representations if descriptor.get('kind')=='text_passage' and descriptor.get('locator')==locator]
        if len(choices)==1:operation={'operation':'read_representation','representationId':choices[0]['representationId']}
    if operation is None:raise ValueError('EVIDENCE_OPERATION_UNRESOLVED: 当前候选没有可唯一定位的原生精读操作')
    bundle=client.read_evidence(*scope,asset_id,operation)
    output,record=source_from_bundle(scope,item,bundle,metadata,operation)
    if metadata['originalFormat']=='CSV' and operation['operation']=='read_table':
        requested=[fact['field'] for fact in bundle['facts'] if re.search(r'(?<![A-Za-z0-9_])'+re.escape(fact['field'])+r'(?![A-Za-z0-9_])',question,re.I)]
        output['provenance'].update(csvReadVersion=CSV_READ_VERSION,validatedCsvQuestion=question,
            csvRequestedFieldNames=requested or [fact['field'] for fact in bundle['facts']])
    return output,record


def source_from_bundle(scope,item,bundle,metadata,operation):
    """Use the same context contract for an already completed native read."""
    asset_id=str(item.get('assetId') or '')
    identity=bundle.get('identity',{})
    for key,value in zip(('datasetId','versionId','indexVersionId'),scope):
        if identity.get(key)!=str(value):raise ValueError('EVIDENCE_SNAPSHOT_MISMATCH: 精读返回了不同快照')
    if identity.get('assetId')!=asset_id or identity.get('sourceSha256')!=metadata.get('sourceSha256'):
        raise ValueError('EVIDENCE_SOURCE_MISMATCH: 精读来源身份或摘要不同')
    if not bundle.get('facts'):
        raise ValueError('EVIDENCE_PARTIAL_READ_REQUIRED: 该子树尚需读取其子证据，不能将目录视为已读取事实')
    content=bundle['content'];values=[]
    if 'records' in content:
        for record in content['records']:
            values.append({'locator':record.get('locator',{}),'values':context_record(record['values'],record,record.get('locator',{}))})
    elif 'text' in content:values=content['text']
    elif 'value' in content:
        value=content['value'];numbers=content.get('numberLexemes',{});pointer=bundle['locator'].get('jsonPointer','')
        if pointer in numbers:value={'sourceNumber':numbers[pointer]['lexeme'],'type':'number'}
        values=value
    else:values=content
    model_facts=[]
    for fact in bundle['facts']:
        value=_native_numbers(fact['value'],fact.get('locator',{}).get('jsonPointer',''),content.get('numberLexemes',{}))
        if fact.get('numberSourceExpression') is not None:value={'sourceNumber':fact['numberSourceExpression'],'type':'number'}
        if fact.get('evidenceNature')=='ocr_text':
            value={'recognizedText':fact['value'],'evidenceNature':'ocr_text','usableForExactCalculation':False,
                'recognitionConfidenceMinimum':fact.get('locator',{}).get('ocrConfidenceMinimum'),'qualityFlags':fact.get('qualityFlags',[])}
        elif not fact.get('calculationEligible',True):
            value={'sourceExpression':fact.get('sourceValue'),'cachedValue':fact['value'],'usableForCalculation':False,'qualityFlags':fact.get('qualityFlags',[])}
        row={'factId':fact['factId'],'field':fact['field'],'locator':fact.get('locator',{}),'value':value,'unit':fact.get('unit')}
        original=csv_empty_source(fact,bundle)
        if original is not None:row['originalField']=original
        model_facts.append(row)
    human=json.dumps(values,ensure_ascii=False,separators=(',',':')) if not isinstance(values,str) else values
    # Projection uses a generic "value" fact field. The original key and JSONL
    # physical line must accompany that value in the actual model context;
    # provenance recorded only outside the request cannot establish the binding.
    model=json.dumps({'evidenceId':bundle['evidenceId'],'originalFormat':identity['originalFormat'],
        'logicalPath':identity['logicalPath'],'locator':bundle['locator'],'facts':model_facts},ensure_ascii=False,separators=(',',':'))
    provenance={**item.get('provenance',{}),'candidateEvidenceId':item['evidenceId'],'representationId':bundle['representationId'],
        'representationKind':bundle['representationKind'],'originalFormat':identity['originalFormat'],'rawCommitId':identity['rawCommitId'],
        'sourceSha256':identity['sourceSha256'],'nativeReadId':bundle['readId'],'nativeReadOperation':operation,'readOutputSha256':bundle['readOutputSha256'],
        'nativeContextVersion':'native-context-with-source-locator-2026-10-28.1'}
    # A newly projected native read may contain fewer fields than the candidate.
    # Old decoded-value/date claims cannot override the actual re-read facts.
    for key in ('factClaims','factPresentationVersion','temporalRoleVersion','temporalMetadata','expandedFields','contextProjection','unitResolutionVersion','validatedMetadataQuestion'):
        provenance.pop(key,None)
    output={**item,'evidenceId':bundle['evidenceId'],'locator':bundle['locator'],'provenance':provenance,
        'nativeBundle':bundle,'nativeHumanContext':human,'nativeModelContext':model}
    record={'state':'READ','candidateEvidenceId':item['evidenceId'],'readId':bundle['readId'],'operation':operation,
        'evidenceId':bundle['evidenceId'],'representationId':bundle['representationId'],'factIds':[fact['factId'] for fact in bundle['facts']],
        'readOutputSha256':bundle['readOutputSha256'],'contextTextSha256':hashlib.sha256(model.encode()).hexdigest(),'coverage':bundle['coverage']}
    return output,record


def native_region_request(item,question,filters):
    """Only a previously read native locator can select a crop; no free path."""
    bundle=item.get('nativeBundle')
    if not bundle:return None
    if item.get('provenance',{}).get('documentReadVersion')==DOCUMENT_READ_VERSION and item.get('nativeRegionRequest'):
        return item['nativeRegionRequest']
    format=bundle['identity']['originalFormat'];locator=bundle['locator']
    if format not in {'PDF','JPG','PNG'} or not isinstance(locator.get('bbox'),list):return None
    if format=='PDF' and bundle['evidenceNature']!='ocr_text' and not re.search(r'图片|图像|图表|图纸|示意图|照片|颜色|外观|标注|看图',question):return None
    asset=next((asset for asset in filters.get('assets',[]) if str(asset['assetId'])==bundle['identity']['assetId']),{})
    pages=asset.get('attributes',{}).get('documentContent',{}).get('pages',[])
    page=next((page for page in pages if page['page']==locator.get('page',1)),None)
    if not page:raise ValueError('EVIDENCE_REGION_PAGE_UNRESOLVED')
    box=locator['bbox'];bounds=page['bounds'];padding=4 if format=='PDF' else 8
    region=[max(bounds[0],box[0]-padding),max(bounds[1],box[1]-padding),min(bounds[2],box[2]+padding),min(bounds[3],box[3]+padding)]
    return {'page':page['page'],'bbox':region,'dpi':200,'maxPixels':1_000_000}


def direct_native_question(client,scope,question,filters,intent):
    """Resolve an explicit root-field read, never a free-form executable plan."""
    if not re.search(r'顶层|根节点|根对象',question):return None
    quoted=re.search(r'(?:字段|属性|键)\s*[`"“\'‘]([^`"”\'’]{1,128})[`"”\'’]',question)
    literal=re.search(r'(?:顶层|根节点|根对象)\s*(?:的)?\s*(?:字段|属性|键)?\s*([A-Za-z_][A-Za-z0-9_.-]{0,127})(?![A-Za-z0-9_.-])',question)
    field=quoted[1] if quoted else literal[1] if literal else None
    if not field:return None
    if re.search(r'定义|含义|解释|为何|原因|影响|建议|分析|总结',question):return None
    end=quoted.end() if quoted else literal.end()
    if re.search(r'和|以及|、',question[end:]):return None  # The JSON plan handles multiple explicit keys.
    if re.search(r'上一版|历史版本|之前版本|比较|对比|排除|除外|大于|小于',question):
        raise ValueError('NEEDS_CLARIFICATION: 请先明确一个冻结文件与顶层字段；版本比较或筛选需要另外的范围计划。')
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus' and asset.get('originalFormat') in {'JSON','JSONL'}
        and asset.get('nativeEvidence') and re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(asset['logicalPath'].rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question,re.I)]
    if not assets:return None
    if re.search(r'\b(?:https?|file|ftp)://',question,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 本次只能读取已授权快照中的文件，外部地址不能按同名文件替换；请明确快照内的逻辑路径。')
    if len(assets)!=1:raise ValueError('NEEDS_CLARIFICATION: 文件名对应多个冻结来源，请补充完整逻辑路径。')
    asset=assets[0]
    if intent.allowed and asset['originalFormat'] not in intent.allowed:
        raise ValueError('NEEDS_CLARIFICATION: 指定文件与来源格式限制不一致。')
    pointer='/'+field.replace('~','~0').replace('/','~1');locator={'jsonPointer':pointer}
    if asset['originalFormat']=='JSONL':
        line=re.search(r'第\s*(\d+)\s*行',question)
        if not line or int(line[1])<1:raise ValueError('NEEDS_CLARIFICATION: JSONL 顶层字段读取需要物理行号。')
        locator['jsonlLine']=int(line[1])
    candidate={'assetId':asset['assetId'],'dataVersion':str(scope[1]),'indexVersion':str(scope[2]),
        'evidenceId':str(uuid.uuid5(uuid.NAMESPACE_URL,json.dumps([list(map(str,scope)),asset['assetId'],locator,question],sort_keys=True,ensure_ascii=False))),
        'semanticSpace':'native.exact-selector','locator':locator,
        'facts':{},'scoreType':'verified_native_selector','rawScore':None,'rank':1,'evidenceType':'constraint',
        'qualityFlags':[],'preview':{'logicalPath':asset['logicalPath']},'provenance':{'originalFormat':asset['originalFormat'],'scope':asset['scope']}}
    candidate,read=read_selected(client,scope,candidate,question,filters,{})
    candidate['provenance'].update(nativeSelectorVersion='native-selector-exact-1',validatedNativeQuestion=question,
        validatedNativeSelector={'assetId':asset['assetId'],**locator})
    return {'evidence':[candidate],'nativeReads':[read],'status':'OK','planningMethod':'bounded-explicit-native-selector'}


def render_verified_selector(question,sources):
    selected=[(index,source) for index,source in enumerate(sources,1) if source.provenance.get('nativeSelectorVersion')=='native-selector-exact-1'
        and source.provenance.get('validatedNativeQuestion')==question and source.evidence_bundle]
    if len(selected)!=1:return None
    index,source=selected[0];facts=source.evidence_bundle['facts']
    if len(facts)!=1:return None
    fact=facts[0];pointer=fact['locator'].get('jsonPointer','');name=pointer.rsplit('/',1)[-1].replace('~1','/').replace('~0','~')
    value=fact['numberSourceExpression'] if fact.get('numberSourceExpression') is not None else json.dumps(fact['value'],ensure_ascii=False,separators=(',',':'))
    return f'源字段 {name} 的值为 {value}。[来源{index}]'
