"""Discover a named frozen JSON tree, then read only verified question targets.

Discovery is not answer evidence. Actual keys and record identifiers establish
selectors; every selected value is read again through the caller's gateway.
No business identifiers, values, evaluation labels or executable model plans
participate in this module.
"""
from __future__ import annotations
import hashlib,json,re,time,uuid
from collections import defaultdict
from services.json_contract import READ_VERSION
from services.native_evidence_read import source_from_bundle

MAX_READS=32
MAX_BYTES=8*1024*1024
MAX_NODES=20000
MAX_SECONDS=45

# General field meanings, used only if that actual key exists in this source.
# Explicit key names and frozen source dictionaries take priority.
FIELD_WORDS={
    'outdoor_model':r'外机(?:的)?型号|室外机(?:的)?型号|配对外机|配套外机',
    'indoor_model':r'内机(?:的)?型号|室内机(?:的)?型号|配对内机|配套内机',
    'installation_type':r'安装(?:位置|方式)|安装在|挂墙|落地',
    'applicability_rule':r'适配规则|适用规则|适配条件|共用接口|可互换',
    'quantity_per_product':r'单台用量|每台用量|单套用量|每件用量|BOM用量',
    'effective_from':r'生效时间|生效日期|何时生效',
    'quantity':r'数量|多少|用量',
    'unit':r'计量单位|单位',
    'action':r'动作|处理措施|采取|处理方式',
    'measurement_units':r'测量单位|指标.{0,6}单位',
    'loading_or_access_type':r'投放方向|投入口|开门方式|顶部开盖',
}


def child(pointer,key):return pointer+'/'+str(key).replace('~','~0').replace('/','~1')


def literal(text,value):
    return bool(value and re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(str(value))+r'(?![A-Za-z0-9_.-])',text))


def source_type(value,number=False):
    if number:return 'number'
    return ('null' if value is None else 'boolean' if isinstance(value,bool) else
        'number' if isinstance(value,(int,float)) else 'object' if isinstance(value,dict) else
        'array' if isinstance(value,list) else 'string')


def record_array(value):return isinstance(value,list) and bool(value) and all(isinstance(item,dict) for item in value)


def named_asset(question,filters,intent):
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus'
        and asset.get('originalFormat') in {'JSON','JSONL'} and asset.get('nativeEvidence')]
    full=[asset for asset in assets if literal(question,asset['logicalPath'])]
    selected=full or [asset for asset in assets if literal(question,asset['logicalPath'].rsplit('/',1)[-1])]
    if not selected:return None
    if re.search(r'\b(?:https?|file|ftp)://',question,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 外部地址不能按同名文件替换为当前快照的来源。')
    if len(selected)!=1:
        if len({asset['logicalPath'].rsplit('/',1)[-1] for asset in selected})>1:return None
        raise ValueError('NEEDS_CLARIFICATION: 文件名对应多个冻结来源，请给出完整逻辑路径。')
    asset=selected[0]
    if intent.allowed and asset['originalFormat'] not in intent.allowed:
        raise ValueError('NEEDS_CLARIFICATION: 指定文件与来源格式限制不一致。')
    return asset


def _question_without_file(question,asset):
    for name in (asset['logicalPath'],asset['logicalPath'].rsplit('/',1)[-1]):
        question=question.replace(name,' ')
    return question


def _identifier_groups(question):
    identifiers=re.findall(r'(?<![A-Za-z0-9_-])[A-Za-z]{2,12}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_-])',question)
    groups=defaultdict(set)
    for value in identifiers:groups[value.split('-',1)[0]].add(value)
    return dict(groups)


def plan_targets(question,segments,field_labels=None,*,record_document_prefixes=()):
    """Return exact pointers plus their record anchors, retaining native nulls.

    Different identifiers in the same namespace describe separate records.
    Different namespaces must coexist within the record being selected. This
    does not infer relations or cartesian joins between unrelated arrays.
    """
    field_labels=field_labels or {};groups=_identifier_groups(question)
    objects=[];nodes={};visited=0
    def walk(value,pointer):
        nonlocal visited
        visited+=1
        if visited>MAX_NODES:raise ValueError('JSON_DISCOVERY_BUDGET_EXCEEDED: JSON结构节点超过本次精读容量。')
        if pointer.count('/')>64:raise ValueError('JSON_DISCOVERY_BUDGET_EXCEEDED: JSON结构层级超过本次精读容量。')
        nodes[pointer]=value
        if isinstance(value,dict):
            objects.append((pointer,value))
            for key,item in value.items():walk(item,child(pointer,key))
        elif isinstance(value,list):
            for index,item in enumerate(value):walk(item,child(pointer,index))
    for pointer,value in segments:walk(value,pointer)
    seen=set()
    for _,record in objects:
        for value in record.values():
            if isinstance(value,str) and any(value in values for values in groups.values()):seen.add(value)
    unknown=sorted({value for values in groups.values() for value in values}-seen)
    if unknown:return {'targets':[],'unresolvedIdentifiers':unknown,'reason':'IDENTIFIERS_NOT_IN_ACTUAL_TREE'}
    branch_hints=[pointer for pointer,value in nodes.items() if pointer and record_array(value)
        and literal(question,pointer.rsplit('/',1)[-1].replace('~1','/').replace('~0','~'))]
    def matched_identifiers(record):
        return {key:value for key,value in record.items() if isinstance(value,str) and
            (key=='id' or key.endswith('_id') or any(value in values for values in groups.values())) and literal(question,value)}
    anchored_field_names=set()
    if groups:
        for _,record in objects:
            matched=matched_identifiers(record)
            if all(any(value in values for value in matched.values()) for values in groups.values()):anchored_field_names.update(record)
    # A literal scalar/object field requested outside a record is an explicit
    # target, not permission to substitute related product rows.
    explicit_roots=[(child(pointer,key),value) for pointer,record in objects if not re.search(r'/\d+(?:/|$)',pointer)
        and not (groups and any(pointer==prefix or pointer.startswith(prefix+'/') for prefix in record_document_prefixes))
        for key,value in record.items() if literal(question,key) and not record_array(value)
        and (key not in anchored_field_names or re.search(r'顶层|根对象|根节点',question))
        and not any(pointer==hint or pointer.startswith(hint+'/') for hint in branch_hints)]
    if explicit_roots:
        return {'targets':[{'jsonPointer':pointer,'role':'requested-field','reason':'EXPLICIT_SOURCE_KEY'} for pointer,_ in explicit_roots],
            'unresolvedIdentifiers':[],'reason':'EXPLICIT_NON_RECORD_FIELDS'}
    if groups and not anchored_field_names:
        raise ValueError('NEEDS_CLARIFICATION: 标识在文件中存在，但尚未在同一记录中证实所问对应关系；请明确数组或关联键。')
    selected=[];required_records=[]
    specific_model=bool(re.search(FIELD_WORDS['outdoor_model']+'|'+FIELD_WORDS['indoor_model'],question))
    for pointer,record in objects:
        matches=matched_identifiers(record)
        if groups and not all(any(value in values for value in matches.values()) for values in groups.values()):continue
        if not groups and not branch_hints:continue
        if branch_hints and not any(pointer.startswith(hint+'/') for hint in branch_hints):continue
        fields=[]
        for key in record:
            direct=literal(question,key)
            labels=field_labels.get(key,[])
            described=any(label and label!=key and literal(question,label) for label in labels)
            if key=='model' and specific_model and not direct:described=False
            semantic=bool(re.search(FIELD_WORDS[key],question,re.I)) if key in FIELD_WORDS else key=='model' and not specific_model and '型号' in question
            if direct or described or semantic:fields.append((key,'EXPLICIT_SOURCE_KEY' if direct else 'SOURCE_FIELD_LABEL' if described else 'SOURCE_FIELD_MEANING'))
        if not fields:continue
        anchors=list(matches)
        if not anchors:anchors=[key for key in record if (key=='id' or key.endswith('_id')) and isinstance(record[key],str)][:1]
        for key in anchors:
            selected.append({'jsonPointer':child(pointer,key),'recordPointer':pointer,'role':'record-anchor','reason':'ACTUAL_RECORD_IDENTIFIER'})
        for key,reason in fields:
            selected.append({'jsonPointer':child(pointer,key),'recordPointer':pointer,'role':'requested-field','reason':reason})
        required_records.append(pointer)
    # Preserve record-specific nulls/empty values; de-duplicate only exact paths.
    targets={item['jsonPointer']:item for item in selected}
    return {'targets':list(targets.values()),'unresolvedIdentifiers':[],'recordPointers':required_records,
        'reason':'ACTUAL_RECORDS_AND_REQUESTED_FIELDS' if targets else 'NO_VERIFIED_FIELD_OR_RECORD_TARGET'}


def plan_line_targets(question,documents,field_labels=None):
    """Temporary prefixes isolate documents during selection, never at read time."""
    lines={};segments=[]
    for line,parts in documents:
        if not isinstance(line,int) or isinstance(line,bool) or line<1 or line in lines.values():
            raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSONL物理行定位缺失或重复。')
        prefix='/@jsonl:'+str(line);lines[prefix]=line
        segments.extend((prefix+pointer,value) for pointer,value in parts)
    planned=plan_targets(question,segments,field_labels,record_document_prefixes=tuple(lines))
    for target in planned['targets']:
        prefix=next((prefix for prefix in lines if target['jsonPointer']==prefix or target['jsonPointer'].startswith(prefix+'/')),None)
        if prefix is None:raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSONL目标没有对应的实际文档。')
        target['jsonlLine']=lines[prefix];target['jsonPointer']=target['jsonPointer'][len(prefix):]
        if 'recordPointer' in target:
            if target['recordPointer']!=prefix and not target['recordPointer'].startswith(prefix+'/'):
                raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSONL记录定位跨越了独立事件行。')
            target['recordPointer']=target['recordPointer'][len(prefix):]
    return planned


class Reader:
    def __init__(self,client,scope,asset,metadata):
        self.client,self.scope,self.asset,self.metadata=client,scope,asset,metadata
        self.started=time.monotonic();self.bytes=0;self.reads=[]
    def remaining(self,for_request=True):
        value=MAX_SECONDS-(time.monotonic()-self.started)
        if value<=0 or len(self.reads)>MAX_READS or self.bytes>MAX_BYTES or for_request and len(self.reads)>=MAX_READS:
            raise ValueError('JSON_DISCOVERY_BUDGET_EXCEEDED: 本次JSON读取超出时间、次数或字节容量。')
        return value
    def read(self,operation,*,discovery=False):
        remaining=self.remaining()
        bundle=self.client.read_evidence(*self.scope,self.asset['assetId'],operation,timeout=remaining)
        identity=bundle.get('identity',{})
        expected={**dict(zip(('datasetId','versionId','indexVersionId'),map(str,self.scope))),
            'assetId':self.asset['assetId'],'sourceSha256':self.metadata['sourceSha256'],'rawCommitId':self.metadata['rawCommitId'],
            'logicalPath':self.asset['logicalPath'],'originalFormat':self.asset['originalFormat']}
        if any(identity.get(key)!=value for key,value in expected.items()):
            raise ValueError('EVIDENCE_SNAPSHOT_MISMATCH: JSON精读身份与冻结范围不一致。')
        if not bundle.get('readId') or not bundle.get('readOutputSha256'):
            raise ValueError('EVIDENCE_READ_ID_MISSING: JSON精读缺少读取标识或摘要。')
        if operation['operation']=='read_json_subtree' and (bundle.get('locator',{}).get('jsonPointer')!=operation['jsonPointer']
                or bundle.get('locator',{}).get('jsonlLine')!=operation.get('jsonlLine')):
            raise ValueError('EVIDENCE_LOCATOR_MISMATCH: JSON精读的指针或物理行与本次操作不一致。')
        if operation['operation']=='read_representation' and bundle.get('representationId')!=operation['representationId']:
            raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSON精读返回了其他登记表示。')
        self.bytes+=len(json.dumps(bundle,ensure_ascii=False,separators=(',',':')).encode())
        self.reads.append({'state':'DISCOVERED' if discovery else 'READ','readId':bundle['readId'],'operation':operation,
            'evidenceId':bundle['evidenceId'],'representationId':bundle['representationId'],'sourceSha256':identity['sourceSha256'],
            'readOutputSha256':bundle['readOutputSha256'],'factIds':[fact['factId'] for fact in bundle.get('facts',[])],
            'coverage':bundle.get('coverage',{}),'usedForDiscoveryOnly':discovery})
        from services.evidence_trace import current
        if current():current().event('jsonNativeRead',self.reads[-1])
        self.remaining(for_request=False)
        return bundle
    def discover(self,pointer='',line=None):
        operation={'operation':'read_json_subtree','jsonPointer':pointer}
        if line is not None:operation['jsonlLine']=line
        bundle=self.read(operation,discovery=True);content=bundle['content']
        if bundle.get('coverage',{}).get('completeSubtree') and 'value' in content:return [(pointer,content['value'])]
        pages=content.get('childPages',[])
        if not pages:raise ValueError('JSON_PARTIAL_DISCOVERY: 当前JSON分段需要进一步明确字段或字符范围。')
        known={row['representationId']:row for row in self.metadata['representations']}
        segments=[]
        for identifier in pages:
            if identifier not in known or known[identifier].get('kind')!='json_children_page':
                raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSON子页没有冻结登记。')
            page=self.read({'operation':'read_representation','representationId':identifier},discovery=True)
            for item in page['content'].get('children',[]):
                if 'value' in item:segments.append((item['jsonPointer'],item['value']))
                elif item.get('representationId') in known:segments.extend(self.discover(item['jsonPointer'],line))
                else:raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSON子树没有冻结登记。')
        return segments

    def discover_documents(self,line=None):
        if line is not None:return [(line,self.discover(line=line))]
        pages=[row for row in self.metadata['representations'] if row.get('kind')=='json_documents_page']
        roots=[row for row in self.metadata['representations'] if row.get('kind')=='json_subtree'
            and row.get('locator',{}).get('jsonPointer')=='' and row['locator'].get('jsonlLine') is not None]
        expected=set();documents={}
        for descriptor in pages:
            declared=descriptor.get('selector',{}).get('jsonlLines',[])
            if not declared or len(set(declared))!=len(declared) or expected.intersection(declared):
                raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSONL文档页缺少唯一物理行清单。')
            expected.update(declared)
            page=self.read({'operation':'read_representation','representationId':descriptor['representationId']},discovery=True)
            values=page['content'].get('documents',[])
            if page.get('coverage',{}).get('truncated') or len(values)!=len(declared):
                raise ValueError('JSON_PARTIAL_DISCOVERY: JSONL文档页内容尚未完整读取。')
            actual=[]
            for item in values:
                number=item.get('physicalLine')
                if not isinstance(number,int) or isinstance(number,bool) or number<1 or number in documents or 'value' not in item:
                    raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSONL文档缺少唯一行定位或原生值。')
                actual.append(number);documents[number]=[('',item['value'])]
            if set(actual)!=set(declared):raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSONL实际行与登记清单不同。')
        for descriptor in roots:
            number=descriptor['locator']['jsonlLine']
            if number in expected:raise ValueError('EVIDENCE_MANIFEST_MISMATCH: JSONL根文档与文档页重复。')
            expected.add(number);documents[number]=self.discover(line=number)
        if not expected or set(documents)!=expected:
            raise ValueError('EVIDENCE_MANIFEST_UNAVAILABLE: 当前快照没有可完整定位的JSONL文档。')
        totals={row.get('coverage',{}).get('totalDocumentCount') for row in pages}- {None}
        if len(totals)>1 or totals and next(iter(totals))!=len(documents):
            raise ValueError('JSON_PARTIAL_DISCOVERY: JSONL登记文档总数与实际读取范围不一致。')
        return sorted(documents.items())


def direct_json_question(client,scope,question,filters,intent):
    asset=named_asset(question,filters,intent)
    if asset is None:return None
    filenames=re.findall(r'[\w.-]+\.(?:jsonl?|csv|xlsx|txt|pdf|png|jpe?g|svg|obj|step|stp)\b',question,re.I)
    if any(name!=asset['logicalPath'].rsplit('/',1)[-1] for name in filenames):return None
    # Exact aggregates have their own complete-range executor. Do not turn
    # versions, negated requirements or quantitative predicates into a lookup.
    if re.search(r'上一版|历史版本|之前版本',question):return None
    metadata=client.evidence_metadata(*scope,asset['assetId'])
    if metadata.get('nativeEvidenceStatus')!='AVAILABLE':raise ValueError('EVIDENCE_MANIFEST_UNAVAILABLE: 当前快照缺少原生JSON证据。')
    line=None
    if asset['originalFormat']=='JSONL':
        match=re.search(r'第\s*(\d+)\s*行',question)
        if match:
            line=int(match[1])
            if line<1:raise ValueError('NEEDS_CLARIFICATION: JSONL需要有效物理行号。')
    reader=Reader(client,scope,asset,metadata)
    labels=defaultdict(list)
    for table in filters.get('tables',[]):
        if str(table['assetId'])!=asset['assetId']:continue
        for field in table.get('fields',[]):
            for key in ('description','sourceDescription'):
                label=field.get(key,'')
                if label and '来源为本文件实际字段' not in label:labels[field['name']].append(label)
    if asset['originalFormat']=='JSONL':
        planned=plan_line_targets(_question_without_file(question,asset),reader.discover_documents(line),labels)
    else:planned=plan_targets(_question_without_file(question,asset),reader.discover(),labels)
    plan={'version':READ_VERSION,'assetId':asset['assetId'],'logicalPath':asset['logicalPath'],
        'targets':planned['targets'],'unresolvedIdentifiers':planned['unresolvedIdentifiers'],'selectionReason':planned['reason'],
        'requiredEvidenceIds':[],'discoveryOnlyReadIds':[row['readId'] for row in reader.reads]}
    if not planned['targets']:
        return {'evidence':[],'nativeReads':reader.reads,'status':'UNKNOWN_ENTITY' if planned['unresolvedIdentifiers'] else 'OK',
            'planningMethod':'bounded-native-json-plan','jsonPlan':plan,'fallbackToSearch':not planned['unresolvedIdentifiers']}
    evidence=[]
    for target in planned['targets']:
        operation={'operation':'read_json_subtree','jsonPointer':target['jsonPointer']}
        if target.get('jsonlLine') is not None:operation['jsonlLine']=target['jsonlLine']
        bundle=reader.read(operation)
        if bundle.get('coverage',{}).get('partial') or not bundle.get('facts'):
            raise ValueError('JSON_PARTIAL_TARGET: 指定JSON字段尚需分段读取，本次未作为完整依据。')
        # Reuse the native adapter's single actual-read -> single source mapping.
        candidate={'assetId':asset['assetId'],'dataVersion':str(scope[1]),'indexVersion':str(scope[2]),
            'evidenceId':bundle['evidenceId'],'semanticSpace':'native.json.read','locator':bundle['locator'],
            'facts':{},'scoreType':'verified_json_read','evidenceType':'constraint','qualityFlags':bundle['qualityFlags'],
            'preview':{'logicalPath':asset['logicalPath']},'provenance':{'originalFormat':asset['originalFormat'],'scope':asset['scope']}}
        candidate,_=source_from_bundle(scope,candidate,bundle,metadata,operation)
        context=json.loads(candidate['nativeModelContext'])
        for fact,model_fact in zip(bundle['facts'],context['facts']):
            model_fact['sourceType']=source_type(fact['value'],fact.get('numberSourceExpression') is not None)
        candidate['nativeModelContext']=json.dumps(context,ensure_ascii=False,separators=(',',':'))
        candidate['provenance'].update(jsonReadVersion=READ_VERSION,validatedJsonQuestion=question,
            explicitSourcePath=asset['logicalPath'],jsonTarget=target,nativeReadId=bundle['readId'],readOutputSha256=bundle['readOutputSha256'])
        evidence.append(candidate);plan['requiredEvidenceIds'].append(candidate['evidenceId'])
    # A field projection's generic value key does not describe its business
    # meaning. Carry the actual source key and the identifiers read from that
    # same record together; no neighbouring record or discovery-only value may
    # supply a binding. Anchor sources still participate in final budgeting.
    anchors=defaultdict(list)
    for item in evidence:
        target=item['provenance']['jsonTarget']
        if target['role']=='record-anchor':anchors[(target.get('jsonlLine'),target['recordPointer'])].append(item)
    for item in evidence:
        context=json.loads(item['nativeModelContext']);target=item['provenance']['jsonTarget']
        bindings={};references=[]
        for anchor in anchors.get((target.get('jsonlLine'),target.get('recordPointer')),[]):
            bundle=anchor['nativeBundle'];fact=bundle['facts'][0]
            name=bundle['locator']['jsonPointer'].rsplit('/',1)[-1].replace('~1','/').replace('~0','~')
            bindings[name]=fact['value']
            references.append({'evidenceId':bundle['evidenceId'],'factId':fact['factId'],'readId':bundle['readId'],
                'readOutputSha256':bundle['readOutputSha256'],'locator':bundle['locator']})
        if bindings:context['recordBindings']=bindings
        for fact in context['facts']:
            fact['sourceField']=fact['locator']['jsonPointer'].rsplit('/',1)[-1].replace('~1','/').replace('~0','~')
            if fact.get('unit') is None:fact.pop('unit',None)
        item['nativeModelContext']=json.dumps(context,ensure_ascii=False,separators=(',',':'))
        item['provenance'].update(jsonContextVersion='same-record-source-fields-1',jsonRecordBindingReferences=references)
    plan.update(actualReadCount=len(reader.reads),actualReadBytes=reader.bytes)
    return {'evidence':evidence,'nativeReads':reader.reads,'status':'OK','planningMethod':'bounded-native-json-plan','jsonPlan':plan}
