"""Bind answer statements to values actually read and transmitted.

This is a provenance/typed-value verifier, not a natural-language semantic
judge. Free model interpretations never acquire source-fact verification.
"""
from __future__ import annotations
import copy,hashlib,json,re,uuid
from services.native_evidence_read import _native_numbers,csv_empty_source
from services.read_receipt import verify as verify_receipt,digest as receipt_digest

VERSION='actual-input-answer-lineage-2026-10-24.1'
IDENTITY=('datasetId','versionId','indexVersionId','rawCommitId','assetId','logicalPath','sourceSha256','originalFormat')
SHA=re.compile(r'^[0-9a-f]{64}$')
NUMBER=re.compile(r'^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$')

class LineageError(ValueError):pass
def require(condition,code):
    if not condition:raise LineageError(code)
def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def checksum(value):return hashlib.sha256(canonical(value).encode()).hexdigest()
def same(left,right):return canonical(left)==canonical(right)
def source_dict(source):
    if hasattr(source,'model_dump'):return source.model_dump(mode='json')
    source=copy.deepcopy(source)
    if 'nativeBundle' in source:
        bundle=source['nativeBundle'];identity=bundle['identity']
        return dict(source_id=source['evidenceId'],evidence_id=source['evidenceId'],asset_id=identity['assetId'],
            logical_path=identity['logicalPath'],data_version=identity['versionId'],index_version=identity['indexVersionId'],
            original_format=identity['originalFormat'],representation_id=bundle['representationId'],
            locator=bundle['locator'],source_type='dataset_native',
            model_context=source['nativeModelContext'],content_preview=source.get('nativeHumanContext',''),
            evidence_bundle=bundle,fact_ids=source.get('modelFactIds',[fact['factId'] for fact in bundle['facts']]),
            provenance=source['provenance'],calculation_lineage=None)
    return source

def _model_value(fact,bundle):
    value=_native_numbers(fact['value'],fact.get('locator',{}).get('jsonPointer',''),bundle.get('content',{}).get('numberLexemes',{}))
    if fact.get('numberSourceExpression') is not None:value={'sourceNumber':fact['numberSourceExpression'],'type':'number'}
    if fact.get('evidenceNature')=='ocr_text':
        value={'recognizedText':fact['value'],'evidenceNature':'ocr_text','usableForExactCalculation':False,
            'recognitionConfidenceMinimum':fact.get('locator',{}).get('ocrConfidenceMinimum'),'qualityFlags':fact.get('qualityFlags',[])}
    elif not fact.get('calculationEligible',True):
        value={'sourceExpression':fact.get('sourceValue'),'cachedValue':fact['value'],'usableForCalculation':False,'qualityFlags':fact.get('qualityFlags',[])}
    return value

def value_type(value):
    if isinstance(value,dict) and value.get('type')=='number' and 'sourceNumber' in value:return 'number'
    return 'null' if value is None else 'boolean' if isinstance(value,bool) else 'number' if isinstance(value,(int,float)) else 'string' if isinstance(value,str) else 'array' if isinstance(value,list) else 'object'

class Catalogue:
    """A read catalogue becomes model evidence only after bind_calls succeeds."""
    def __init__(self,sources):
        self.sources=[source_dict(source) for source in sources];self.facts={};self.proofs={};self.unsupported=[];self.required_aux_receipts=set()
        for index,source in enumerate(self.sources,1):
            bundle=source.get('evidence_bundle')
            if not bundle:
                self.unsupported.append({'sourceIndex':index,'reason':'CALCULATION_LINEAGE_ADAPTER_PENDING' if source.get('calculation_lineage') else 'NATIVE_BUNDLE_REQUIRED'});continue
            if not source.get('fact_ids'):
                self.unsupported.append({'sourceIndex':index,'reason':'IMAGE_OR_EMPTY_FACT_ADAPTER_PENDING'});continue
            if bundle.get('clientReadParents') and not bundle.get('clientReadReceipt'):
                self.unsupported.append({'sourceIndex':index,'reason':'DERIVED_CALCULATION_ADAPTER_PENDING'});continue
            identity=bundle.get('identity',{});provenance=source.get('provenance',{})
            receipt=verify_receipt(bundle)
            if provenance.get('csvReadVersion'):
                from services.native_evidence_read import CSV_READ_VERSION
                require(provenance['csvReadVersion']==CSV_READ_VERSION and identity.get('originalFormat')=='CSV'
                    and bundle.get('representationKind')=='table_record_projection','CLAIM_CSV_RECIPE_UNSUPPORTED')
                require(receipt['operation'].get('operation')=='read_table' and same(receipt['operation'],provenance.get('nativeReadOperation'))
                    and bundle.get('coverage',{}).get('requestedRangeComplete'),'CLAIM_CSV_NATIVE_READ_INCOMPLETE')
                requested=provenance.get('csvRequestedFieldNames',[])
                require(requested and set(requested)<={f['field'] for f in bundle['facts']},'CLAIM_CSV_REQUESTED_FIELDS_CHANGED')
            record_validation=None
            if provenance.get('recordReadVersion'):
                from services.record_claim_view import validate_source
                record_validation=validate_source(source);self.required_aux_receipts.update(record_validation['scanReceiptSha256s'])
            if provenance.get('tableReadVersion'):
                from services.worksheet_claim_view import validate_source
                validate_source(source)
            if provenance.get('engineeringReadVersion') and not provenance.get('engineeringVisualRead'):
                from services.engineering_claim_view import validate_source
                validate_source(source)
            require(all(identity.get(key) for key in IDENTITY),'CLAIM_SOURCE_IDENTITY_INCOMPLETE')
            require(SHA.fullmatch(identity['sourceSha256']) is not None,'CLAIM_SOURCE_HASH_INVALID')
            require(str(source.get('asset_id'))==identity['assetId'] and source.get('evidence_id')==bundle['evidenceId'], 'CLAIM_SOURCE_IDENTITY_MISMATCH')
            require(source.get('logical_path')==identity['logicalPath'] and source.get('original_format')==identity['originalFormat'],'CLAIM_PUBLIC_SOURCE_IDENTITY_MISMATCH')
            require(source.get('representation_id')==bundle['representationId'] and same(source.get('locator'),bundle['locator']),'CLAIM_PUBLIC_SOURCE_LOCATOR_MISMATCH')
            require(source.get('data_version')==identity['versionId'] and source.get('index_version')==identity['indexVersionId'],'CLAIM_SNAPSHOT_MISMATCH')
            require(provenance.get('sourceSha256')==identity['sourceSha256'] and provenance.get('nativeReadId')==bundle.get('readId'), 'CLAIM_READ_IDENTITY_MISMATCH')
            require(provenance.get('rawCommitId')==identity['rawCommitId'],'CLAIM_RAW_COMMIT_MISMATCH')
            require(provenance.get('readOutputSha256')==bundle.get('readOutputSha256') and SHA.fullmatch(bundle.get('readOutputSha256','')) is not None,'CLAIM_READ_HASH_MISMATCH')
            require(bundle.get('readId') and source.get('model_context'),'CLAIM_ACTUAL_READ_AND_CONTEXT_REQUIRED')
            context=json.loads(source['model_context'])
            require(context.get('evidenceId')==source['evidence_id'] or context.get('evidenceId') is None and provenance.get('engineeringReadVersion'),'CLAIM_CONTEXT_EVIDENCE_MISMATCH')
            rows=context.get('facts',[]);require(isinstance(rows,list),'CLAIM_CONTEXT_FACTS_INVALID')
            by_id={row['factId']:row for row in rows};require(len(by_id)==len(rows),'CLAIM_CONTEXT_FACT_ID_DUPLICATE')
            originals={fact['factId']:fact for fact in bundle.get('facts',[])}
            for fact_id in source.get('fact_ids',[]):
                require(isinstance(fact_id,str) and 0<len(fact_id)<=256,'CLAIM_FACT_ID_INVALID')
                require(fact_id in originals and fact_id in by_id,'CLAIM_FACT_NOT_IN_NATIVE_CONTEXT')
                fact=originals[fact_id];row=by_id[fact_id];value=row.get('value') if 'value' in row else row.get('text')
                require(fact.get('evidenceNature') in {'source_record','source_text','source_declaration','program_calculation','ocr_text','program_rendering'},'CLAIM_MODEL_FACT_IS_NOT_NATIVE_EVIDENCE')
                raw_adapter=bool(provenance.get('documentReadVersion') or provenance.get('engineeringReadVersion'))
                lexeme_adapter=bool(provenance.get('engineeringReadVersion') and fact.get('numberSourceExpression') is not None)
                require(same(value,_model_value(fact,bundle)) or raw_adapter and same(value,fact['value']) or lexeme_adapter and same(value,fact['numberSourceExpression']),'CLAIM_CONTEXT_VALUE_DIFFERS_FROM_READ')
                csv_original=csv_empty_source(fact,bundle)
                require(same(row.get('originalField'),csv_original),'CLAIM_CONTEXT_ORIGINAL_CSV_FIELD_CHANGED')
                locator=fact.get('locator',{});require('locator' not in row or same(row['locator'],locator),'CLAIM_CONTEXT_LOCATOR_MISMATCH')
                expected_type='number' if fact.get('numberSourceExpression') is not None else value_type(_native_numbers(fact['value'],locator.get('jsonPointer',''),bundle.get('content',{}).get('numberLexemes',{})))
                require('sourceType' not in row or row['sourceType']==expected_type,'CLAIM_CONTEXT_TYPE_MISMATCH')
                unit=fact.get('unit')
                require(same(row.get('unit',row.get('measurementUnit')),unit),'CLAIM_CONTEXT_UNIT_MISMATCH')
                require('field' not in row or row['field']==fact['field'],'CLAIM_CONTEXT_FIELD_MISMATCH')
                require('nature' not in row or row['nature']==fact['evidenceNature'],'CLAIM_CONTEXT_NATURE_MISMATCH')
                if lexeme_adapter:value={'sourceNumber':fact['numberSourceExpression'],'type':'number'}
                field=row.get('sourceField') or fact['field']
                if row.get('sourceField') is not None:
                    require(field==locator.get('jsonPointer','').rsplit('/',1)[-1].replace('~1','/').replace('~0','~'),'CLAIM_SOURCE_FIELD_MISMATCH')
                node={'factId':fact_id,'field':field,'value':value,'sourceType':value_type(value),'unit':unit,
                    'evidenceNature':fact['evidenceNature'],'calculationEligible':fact.get('calculationEligible',True),
                    'identity':identity,'locator':locator,'qualityFlags':fact.get('qualityFlags',[]),
                    'recordBindings':context.get('recordBindings',{}),'supportFactIds':[],
                    'csvOriginalField':csv_original,
                    'selectable':provenance.get('jsonTarget',{}).get('role')!='record-anchor',
                    'bindings':[{'sourceIndex':index,'sourceId':source['source_id'],'evidenceId':source['evidence_id'],
                        'representationId':bundle['representationId'],'readId':bundle['readId'],
                        'sourceLocator':source['locator'],
                        'clientReadReceiptSha256':receipt_digest(receipt),'clientDecodedBodySha256':receipt['decodedBodySha256'],
                        'readOutputSha256':bundle['readOutputSha256'],'contextTextSha256':hashlib.sha256(source['model_context'].encode()).hexdigest()}]}
                if provenance.get('tableReadVersion'):
                    from services.worksheet_claim_view import view
                    node['worksheetView']=view(source,fact)
                    node['selectable']=node['worksheetView']['selectable']
                if provenance.get('engineeringReadVersion') and not provenance.get('engineeringVisualRead'):
                    from services.engineering_claim_view import view
                    node['engineeringView']=view(source,fact)
                if record_validation:
                    from services.record_claim_view import view
                    node['recordView']=view(source,fact,record_validation);node['selectable']=node['recordView']['selectable']
                    node['bindings'][0].update(selectionReadIds=record_validation['scanReadIds'],selectionReceiptSha256s=record_validation['scanReceiptSha256s'])
                if fact_id in self.facts:
                    old=self.facts[fact_id];require(same({key:old[key] for key in ('value','identity','locator','unit','evidenceNature')},
                        {key:node[key] for key in ('value','identity','locator','unit','evidenceNature')}),'CLAIM_FACT_ID_COLLISION')
                    old['bindings'].extend(node['bindings'])
                else:self.facts[fact_id]=node
        for node in self.facts.values():
            expected={};references=[]
            for binding in node['bindings']:
                source=self.sources[binding['sourceIndex']-1];refs=source.get('provenance',{}).get('jsonRecordBindingReferences',[])
                for reference in refs:
                    require(reference['factId'] in self.facts,'CLAIM_RECORD_ANCHOR_NOT_AVAILABLE')
                    anchor=self.facts[reference['factId']]
                    require(same(anchor['identity'],node['identity']) and anchor['locator'].get('jsonlLine')==node['locator'].get('jsonlLine'),'CLAIM_RECORD_ANCHOR_SCOPE_MISMATCH')
                    require(anchor['locator'].get('jsonPointer','').rsplit('/',1)[0]==source['provenance']['jsonTarget']['recordPointer'],'CLAIM_RECORD_ANCHOR_PATH_MISMATCH')
                    require(any(edge['evidenceId']==reference['evidenceId'] and edge['readId']==reference['readId'] and edge['readOutputSha256']==reference['readOutputSha256'] for edge in anchor['bindings']),'CLAIM_RECORD_ANCHOR_READ_MISMATCH')
                    expected[anchor['field']]=anchor['value'];references.append(anchor['factId'])
                if source.get('provenance',{}).get('recordReadVersion'):
                    from services.record_claim_view import bindings
                    record_bindings,record_references=bindings(source,node,self.facts)
                    require(not expected or same(expected,record_bindings),'CLAIM_RECORD_BINDINGS_DISAGREE')
                    expected.update(record_bindings);references.extend(record_references)
            require(same(expected,node['recordBindings']),'CLAIM_RECORD_BINDING_VALUE_MISMATCH')
            node['supportFactIds']=sorted(set(references))
            if node.get('worksheetView'):
                extra=node['worksheetView']['supportFactIds']
                require(all(identifier in self.facts and same(self.facts[identifier]['identity'],node['identity'])
                    and self.facts[identifier]['locator'].get('sheet')==node['locator'].get('sheet') for identifier in extra),
                    'CLAIM_WORKSHEET_HEADER_NOT_AVAILABLE')
                node['supportFactIds']=sorted(set([*node['supportFactIds'],*extra]))
            if node.get('engineeringView'):
                extra=node['engineeringView']['supportFactIds']
                require(all(identifier in self.facts and same(self.facts[identifier]['identity'],node['identity'])
                    and self.facts[identifier]['locator'].get('instanceId')==node['locator'].get('instanceId') for identifier in extra),
                    'CLAIM_ENGINEERING_UNIT_ANCHOR_MISSING')
                node['supportFactIds']=sorted(set([*node['supportFactIds'],*extra]))
        self.required_fact_ids=sorted(node['factId'] for node in self.facts.values() if node['selectable'] and any(
            self.sources[edge['sourceIndex']-1].get('provenance',{}).get('jsonTarget',{}).get('role')=='requested-field'
            or self.sources[edge['sourceIndex']-1].get('provenance',{}).get('tableReadVersion')
            or self.sources[edge['sourceIndex']-1].get('provenance',{}).get('csvReadVersion')
                and node['field'] in self.sources[edge['sourceIndex']-1]['provenance']['csvRequestedFieldNames']
            or node.get('engineeringView') or node.get('recordView') for edge in node['bindings']))

    def bind_calls(self,calls,read_receipts=()):
        """Prove exact contexts/facts were sent in a completed real model call."""
        self.proofs={};receipts={receipt_digest(receipt) for receipt in read_receipts}
        require(self.required_aux_receipts<=receipts,'CLAIM_RECORD_SCAN_RECEIPT_NOT_IN_CURRENT_TRACE')
        for fact_id,node in self.facts.items():
            links=[]
            for binding in node['bindings']:
                if binding['clientReadReceiptSha256'] not in receipts:continue
                for call in calls:
                    if call.get('status')!='response_received':continue
                    require(call.get('callId') and SHA.fullmatch(call.get('requestBodySha256','')) is not None,'CLAIM_MODEL_CALL_IDENTITY_INVALID')
                    for actual in call.get('actualEvidence',[]):
                        if actual.get('sourceId')!=binding['sourceId'] or actual.get('evidenceId')!=binding['evidenceId']:continue
                        if (not actual.get('contextMatchedActualRequest') or not actual.get('factIdsPresentInActualRequest') or fact_id not in actual.get('factIds',[])):continue
                        if any(actual.get(key)!=binding[key] for key in ('representationId','readId','readOutputSha256','contextTextSha256','clientReadReceiptSha256','clientDecodedBodySha256')):continue
                        if actual.get('sourceSha256')!=node['identity']['sourceSha256']:continue
                        if not same(actual.get('locator'),binding['sourceLocator']):continue
                        links.append({**binding,'factId':fact_id,'identity':node['identity'],'locator':node['locator'],
                            'modelCallId':call['callId'],'modelRequestBodySha256':call['requestBodySha256']})
            if links:self.proofs[fact_id]=links
        return self

    def links(self,fact_id):
        require(fact_id in self.facts,'CLAIM_UNKNOWN_FACT')
        identifiers=[fact_id,*self.facts[fact_id]['supportFactIds']]
        require(all(identifier in self.proofs for identifier in identifiers),'CLAIM_FACT_OR_ANCHOR_NOT_ACTUALLY_TRANSMITTED')
        return copy.deepcopy([link for identifier in identifiers for link in self.proofs[identifier]])

def display(value):
    if isinstance(value,dict) and value.get('type')=='number' and 'sourceNumber' in value:
        require(isinstance(value['sourceNumber'],str) and NUMBER.fullmatch(value['sourceNumber']) is not None,'CLAIM_SOURCE_NUMBER_INVALID')
        return value['sourceNumber']
    if isinstance(value,dict):return '{'+','.join(json.dumps(key,ensure_ascii=False)+':'+display(item) for key,item in value.items())+'}'
    if isinstance(value,list):return '['+','.join(display(item) for item in value)+']'
    return json.dumps(value,ensure_ascii=False,allow_nan=False)

def _original_link_index(link):
    return link.get('modelSourceIndex',link['sourceIndex'])


def _original_links(links):
    result=copy.deepcopy(links)
    for link in result:
        link['sourceIndex']=_original_link_index(link);link.pop('modelSourceIndex',None)
    return result


def render(catalogue,fact_ids,comparisons=()):
    """Render only exact typed source values and registered equality results."""
    require(len(fact_ids)<=32 and len(set(fact_ids))==len(fact_ids),'CLAIM_SELECTION_DUPLICATE_OR_TOO_LARGE')
    rows=[]
    for fact_id in fact_ids:
        node=catalogue.facts.get(fact_id);require(node is not None and node['selectable'],'CLAIM_UNSELECTABLE_FACT')
        links=catalogue.links(fact_id);subject='、'.join(key+'='+display(value) for key,value in node['recordBindings'].items())
        location=node['locator'];label=subject or node['identity']['logicalPath']+' '+canonical(location)
        if node.get('recordView'):label=node['identity']['logicalPath'].rsplit('/',1)[-1]+' · '+label
        nature={'source_record':'原始字段','source_text':'原文摘录','source_declaration':'源声明','ocr_text':'OCR识别文字，尚未核实原图','program_calculation':'程序计算'}.get(node['evidenceNature'],node['evidenceNature'])
        text=f"{nature} · {label} · {node['field']} = {display(node['value'])}"
        if node.get('csvOriginalField'):
            text=f"{nature} · {label} · {node['field']}：原始 CSV 空字段 \"\"；源文件未指定 NULL 与空字符串的区分，读取值归一化为 null"
        worksheet=node.get('worksheetView')
        if worksheet:
            label='工作表 '+str(worksheet['locator']['sheet'])+' · '+str(worksheet['locator']['cell'])
            header=worksheet.get('sourceColumnHeader')
            field=display(header['value'])+'（表头 '+header['cell']+'）' if header else node['field']
            cache=worksheet.get('formulaCache')
            if cache:
                # Keep the original cache lexeme even if its JSON numeric
                # decoding rounded it; the decoded type is reported separately.
                cache_type=value_type(cache['cachedValue'])
                text=f"源声明 · {label} · {field}：公式原文 {display(cache['sourceExpression'])}；已有缓存值 {display(cache['cachedValue'])}"
                text+='；原始缓存表达 '+display(cache['cacheSourceRaw'])+'；缓存类型 '+cache_type+'；缓存未验证，本次未执行公式'
            else:text=f"原始字段 · {label} · {field} = {display(node['value'])}"
            if node['unit'] is not None:text+='（字段单位声明：'+str(node['unit'])+'）'
        elif node['unit'] is not None:text+=' '+str(node['unit'])
        if node.get('engineeringView'):
            from services.engineering_claim_view import render_text
            text=render_text(node,display)
        if node['identity']['originalFormat'] in {'JSON','JSONL'}:text+='（JSON 类型：'+node['sourceType']+'）'
        if not node['calculationEligible'] and not node.get('engineeringView'):text+='；此记录未核验为可用于计算的值'
        rows.append({'text':text,'factIds':[fact_id,*node['supportFactIds']],'links':links,'evidenceNature':node['evidenceNature'],
            'kind':'source_value','primaryFactIds':[fact_id],
            'verification':'exact-actual-input-value-not-semantic-endorsement'})
    for operation in comparisons:
        if operation.get('operation')=='record_group_value_equal':
            require(set(operation)=={'operation','inputFactIds'} and isinstance(operation['inputFactIds'],list),'CLAIM_COMPARISON_NOT_REGISTERED')
            nodes=[catalogue.facts.get(identifier) for identifier in operation['inputFactIds']]
            require(all(node is not None for node in nodes),'CLAIM_COMPARISON_FACT_INVALID')
            from services.record_group_comparison import registered,calculation,VERSION as GROUP_VERSION
            require(registered(nodes)==operation,'CLAIM_RECORD_GROUP_NOT_REGISTERED')
            computed=calculation(nodes);result=computed['result']
            links=[link for node in nodes for link in catalogue.links(node['factId'])]
            ids=sorted({identifier for node in nodes for identifier in [node['factId'],*node['supportFactIds']]})
            descriptions=[node['identity']['logicalPath'].rsplit('/',1)[-1]+' 的 '+node['field']+'='+display(node['value'])
                +'（类型 '+node['sourceType']+'，声明单位 '+str(node['unit'])+'）' for node in nodes]
            identifier_only=any(check.get('unitInterpretation') for check in computed['pairChecks'])
            scope='程序只比较标识字段的原始类型与字符串值，未判定物理单位等价，' if identifier_only else '程序按原始类型、值及已登记单位核对，'
            text='原始记录多来源核对：'+'；'.join(descriptions)+'。'+scope+('全部一致。' if result else '存在不一致。')
            if any(check['operation']=='record_unit_value_equal' for check in computed['pairChecks']):text+=' 不同声明单位已按登记规则精确换算，原始值保留。'
            if any(node['sourceType']=='null' for node in nodes):text+=' null 表示未记录的值，不能据此证明物理量相同。'
            rows.append({'text':text,'kind':'registered_operation','primaryFactIds':operation['inputFactIds'],
                'factIds':ids,'links':links,'evidenceNature':'program_calculation','verification':'exact-all-matching-sources-typed-value-unit-equality',
                'operation':{'name':'record_group_value_equal','version':GROUP_VERSION,'calculationId':computed['calculationId'],
                    'inputFactIds':computed['inputFactIds'],'inputTypes':computed['inputTypes'],'inputUnits':computed['inputUnits'],
                    'pairChecks':computed['pairChecks'],'result':result},'result':result})
            continue
        if operation.get('operation')=='record_unit_value_equal':
            require(set(operation)=={'operation','leftFactId','rightFactId'},'CLAIM_COMPARISON_NOT_REGISTERED')
            left=catalogue.facts.get(operation['leftFactId']);right=catalogue.facts.get(operation['rightFactId'])
            require(left is not None and right is not None,'CLAIM_COMPARISON_FACT_INVALID')
            from services.record_unit_conversion import calculation,ratio_text,VERSION as UNIT_VERSION
            computed=calculation(left,right);conversion=computed['unitConversion'];result=computed['result']
            links=[*catalogue.links(left['factId']),*catalogue.links(right['factId'])]
            ids=sorted(set([left['factId'],right['factId'],*left['supportFactIds'],*right['supportFactIds']]))
            text=(f"原始记录换算核对：{left['identity']['logicalPath'].rsplit('/',1)[-1]} 的 {left['field']}={display(left['value'])} {left['unit']}"
                f" 与 {right['identity']['logicalPath'].rsplit('/',1)[-1]} 的 {right['field']}={display(right['value'])} {right['unit']}；"
                f"程序统一换算为 {conversion['canonicalUnit']} 后为 {ratio_text(conversion['normalizedValues'][0])} 和 {ratio_text(conversion['normalizedValues'][1])}，数值"+('相同。' if result else '不同。'))
            rows.append({'text':text,'kind':'registered_operation','primaryFactIds':[left['factId'],right['factId']],
                'factIds':ids,'links':links,'evidenceNature':'program_calculation','verification':'exact-same-key-registered-unit-conversion',
                'operation':{'name':'record_unit_value_equal','version':UNIT_VERSION,'calculationId':computed['calculationId'],
                    'inputFactIds':computed['inputFactIds'],'unitConversion':conversion,'result':result},'result':result})
            continue
        if operation.get('operation')=='record_value_equal':
            require(set(operation)=={'operation','leftFactId','rightFactId'},'CLAIM_COMPARISON_NOT_REGISTERED')
            left=catalogue.facts.get(operation['leftFactId']);right=catalogue.facts.get(operation['rightFactId'])
            require(left is not None and right is not None,'CLAIM_COMPARISON_FACT_INVALID')
            from services.record_claim_view import equal,calculation_id,comparison_version,identifier_comparison_scope
            result=equal(left,right);links=[*catalogue.links(left['factId']),*catalogue.links(right['factId'])]
            ids=sorted(set([left['factId'],right['factId'],*left['supportFactIds'],*right['supportFactIds']]))
            text=(f"原始记录核对：{left['identity']['logicalPath'].rsplit('/',1)[-1]} 的 {left['field']}={display(left['value'])}"
                f" 与 {right['identity']['logicalPath'].rsplit('/',1)[-1]} 的 {right['field']}={display(right['value'])}，类型及值"+('相同。' if result else '不同。'))
            if left['sourceType']==right['sourceType']=='number':text+=' 字段声明单位均为 '+str(left['unit'])+'。'
            identifier_scope=identifier_comparison_scope(left,right)
            if identifier_scope:text+=' 单位标注分别为 '+display(left['unit'])+' 和 '+display(right['unit'])+'；此处仅比较标识字符串，不判定物理单位等价。'
            if 'null' in {left['sourceType'],right['sourceType']}:text+=' null 表示未记录的值，不能据此证明物理量相同。'
            rows.append({'text':text,'kind':'registered_operation','primaryFactIds':[left['factId'],right['factId']],
                'factIds':ids,'links':links,'evidenceNature':'program_calculation','verification':'exact-identifier-string-with-raw-unit-annotations' if identifier_scope else 'exact-same-key-declared-unit-typed-value-equality',
                'operation':{'name':'record_value_equal','version':comparison_version(left,right),'calculationId':calculation_id(left,right),'inputFactIds':[left['factId'],right['factId']],'result':result,**identifier_scope},'result':result})
            continue
        require(set(operation)=={'operation','leftFactId','rightFactId'} and operation['operation']=='equal','CLAIM_COMPARISON_NOT_REGISTERED')
        left=catalogue.facts.get(operation['leftFactId']);right=catalogue.facts.get(operation['rightFactId'])
        require(left is not None and right is not None and left['factId']!=right['factId'],'CLAIM_COMPARISON_FACT_INVALID')
        require(left['evidenceNature'] in {'source_record','source_declaration'} and right['evidenceNature'] in {'source_record','source_declaration'},'CLAIM_COMPARISON_NATURE_UNSUPPORTED')
        require(value_type(left['value'])==value_type(right['value']) and value_type(left['value']) in {'string','boolean','null'},'CLAIM_COMPARISON_TYPE_UNSUPPORTED')
        links=[*catalogue.links(left['factId']),*catalogue.links(right['factId'])]
        result=same(left['value'],right['value']);ids=sorted(set([left['factId'],right['factId'],*left['supportFactIds'],*right['supportFactIds']]))
        rows.append({'text':f"原始字段值比较：{left['field']}={display(left['value'])} 与 {right['field']}={display(right['value'])}，值"+('相同。' if result else '不同。'),
            'kind':'registered_operation','primaryFactIds':[left['factId'],right['factId']],
            'factIds':ids,'links':links,'evidenceNature':'program_calculation','verification':'exact-typed-value-equality-only',
            'operation':{'name':'equal','version':VERSION,'inputFactIds':[left['factId'],right['factId']],'result':result}})
    used=sorted({link['sourceIndex'] for row in rows for link in row['links']});mapping={old:new for new,old in enumerate(used,1)}
    answer='';claims=[]
    for row in rows:
        if answer:answer+='\n\n'
        start=len(answer);answer+=row['text'];end=len(answer)
        require(len(answer)<=12000,'CLAIM_PRESENTATION_BUDGET_EXCEEDED')
        indices=sorted({mapping[link['sourceIndex']] for link in row['links']})
        answer+=' '+''.join(f'[来源{index}]' for index in indices)
        public_links=copy.deepcopy(row['links'])
        for link in public_links:
            old=link['sourceIndex'];link['sourceIndex']=mapping[old]
            if old!=link['sourceIndex']:link['modelSourceIndex']=old
        claims.append({'claimId':str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([VERSION,row['text'],row['factIds']]))),
            'text':row['text'],'answerStart':start,'answerEnd':end,'offsetUnit':'unicode-code-points',
            'sourceIndices':indices,'evidenceNature':row['evidenceNature'],'verification':row['verification'],
            'kind':row['kind'],'primaryFactIds':row['primaryFactIds'],
            'factIds':row['factIds'],'evidenceIds':sorted({link['evidenceId'] for link in row['links']}),
            'readIds':sorted({identifier for link in row['links'] for identifier in [link['readId'],*link.get('selectionReadIds',[])]}),'inputLinks':public_links,
            **({'operation':row['operation']} if 'operation' in row else {})})
    return {'version':VERSION,'answer':answer,'claims':claims,'sourceIndices':used,
        'sources':copy.deepcopy([catalogue.sources[index-1] for index in used]),
        'missingRequiredFactIds':sorted(set(catalogue.required_fact_ids)-set(fact_ids)),
        'verificationScope':'Exact values and registered operations only; question completeness and free model interpretation are not certified.'}

def verify_presentation(result,catalogue):
    """Every visible data span must equal a registered source/operation view."""
    answer=result['answer'];claims=result['claims'];used=sorted({_original_link_index(link) for claim in claims for link in claim['inputLinks']})
    require(result['sourceIndices']==used and same(result['sources'],[catalogue.sources[index-1] for index in used]),'CLAIM_FINAL_SOURCE_MAPPING_CHANGED')
    mapping={old:new for new,old in enumerate(used,1)};cursor=0;verified=[]
    for claim in claims:
        start,end=claim['answerStart'],claim['answerEnd']
        require(type(start) is int and type(end) is int and cursor<=start<end<=len(answer)
            and claim['offsetUnit']=='unicode-code-points' and answer[start:end]==claim['text'],'CLAIM_FINAL_SPAN_CHANGED')
        require(answer[cursor:start].strip()=='','CLAIM_UNATTRIBUTED_FINAL_TEXT')
        if claim['kind']=='source_value':
            require(len(claim['primaryFactIds'])==1,'CLAIM_PRIMARY_FACT_INVALID')
            expected=render(catalogue,claim['primaryFactIds'])['claims'][0]
        elif claim['kind']=='registered_operation':
            operation=claim.get('operation',{})
            if operation.get('name')=='record_group_value_equal':
                require(3<=len(operation.get('inputFactIds',[]))<=4,'CLAIM_OPERATION_INVALID')
                expected=render(catalogue,[],[{'operation':operation['name'],'inputFactIds':operation['inputFactIds']}])['claims'][0]
            else:
                require(operation.get('name') in {'equal','record_value_equal','record_unit_value_equal'} and len(operation.get('inputFactIds',[]))==2,'CLAIM_OPERATION_INVALID')
                left,right=operation['inputFactIds']
                expected=render(catalogue,[],[{'operation':operation['name'],'leftFactId':left,'rightFactId':right}])['claims'][0]
        else:raise LineageError('CLAIM_UNREGISTERED_FINAL_STATEMENT')
        ignored={'answerStart','answerEnd','sourceIndices','inputLinks'}
        require(same({key:value for key,value in claim.items() if key not in ignored},
            {key:value for key,value in expected.items() if key not in ignored}),'CLAIM_FINAL_VALUE_OR_PROOF_CHANGED')
        require(same(_original_links(claim['inputLinks']),_original_links(expected['inputLinks'])),'CLAIM_FINAL_ACTUAL_INPUT_MAPPING_CHANGED')
        require(all(link['sourceIndex']==mapping[_original_link_index(link)] for link in claim['inputLinks']),'CLAIM_FINAL_LINK_CITATION_MAPPING_CHANGED')
        indices=sorted({mapping[_original_link_index(link)] for link in expected['inputLinks']})
        require(claim['sourceIndices']==indices,'CLAIM_FINAL_CITATION_MAPPING_CHANGED')
        citations=' '+''.join(f'[来源{index}]' for index in indices)
        require(answer[end:end+len(citations)]==citations,'CLAIM_FINAL_CITATION_TEXT_CHANGED')
        cursor=end+len(citations);verified.append(claim['claimId'])
    require(answer[cursor:].strip()=='','CLAIM_UNATTRIBUTED_FINAL_TEXT')
    return {'version':VERSION,'status':'VERIFIED_EXACT_SPANS' if claims else 'NO_CLAIMS','verifiedClaimIds':verified,
        'answerSha256':hashlib.sha256(answer.encode()).hexdigest(),'claimsSha256':checksum(claims),
        'scope':'Typed source values and registered operations only. This does not certify question completeness or free interpretation.'}
