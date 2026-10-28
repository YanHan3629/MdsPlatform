"""Bounded named-source record lookup using actual frozen schemas and reads.

Enums discover a selector only. Complete authorized table scans establish its
uniqueness; the matching row is then read again for the eventual model input.
This module does not infer joins, run user code, convert units or make answers.
"""
from __future__ import annotations
import hashlib,json,re,time
from services.native_evidence_read import source_from_bundle
from services.read_receipt import digest,verify

VERSION='native-record-lookup-2026-10-26.6'
SUPPORTED_READ_VERSIONS=frozenset({VERSION,'native-record-lookup-2026-10-26.1','native-record-lookup-2026-10-26.2','native-record-lookup-2026-10-26.3','native-record-lookup-2026-10-26.4','native-record-lookup-2026-10-26.5'})
MAX_ROWS=128
MAX_SOURCES=4
MAX_FIELDS=4
MAX_READS=32
MAX_BYTES=8*1024*1024
MAX_SECONDS=45
FORMATS={'CSV','JSON','JSONL','XLSX'}
# These meanings apply only when the named key is registered in that table.
FIELD_WORDS={
    'rated_cooling_w':r'额定制冷量|额定制冷能力',
    'rated_heating_w':r'额定制热量|额定制热能力',
    'rated_cooling_input_w':r'额定制冷输入功率|制冷额定输入功率',
    'rated_heating_input_w':r'额定制热输入功率|制热额定输入功率',
    'rated_input_power_w':r'额定输入功率',
    'voltage_v':r'额定电压|供电电压',
}

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def literal(question,value,*,ignore_case=False):
    return isinstance(value,str) and bool(value) and bool(re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(value)+r'(?![A-Za-z0-9_.-])',question,re.I if ignore_case else 0))
def native_type(value):
    return 'null' if value is None else 'boolean' if isinstance(value,bool) else 'number' if isinstance(value,(int,float)) else 'string' if isinstance(value,str) else 'object' if isinstance(value,dict) else 'array'
def field_requested(question,field):
    if literal(question,field['name'],ignore_case=True):return True
    if field['name'] in FIELD_WORDS and re.search(FIELD_WORDS[field['name']],question):return True
    for label in (field.get('description'),field.get('sourceDescription')):
        if label and '来源为本文件实际字段' not in label and len(label)>=2 and literal(question,label):
            if len(label)==2 and re.search(r'室外机|室外单元|外机|室内机|室内单元|内机|包装|净重|毛重',question):continue
            return True
    return False
def identifier(field):
    name=field['name'].lower();description=field.get('description') or ''
    return name in {'id','sku','serial','model'} or name.endswith(('_id','_no','_number')) or bool(re.search(r'标识|编号|序列号',description))

def requested_fields(question,table,key):
    """A registered key label binding a literal selects a row, not an output field."""
    field,= [field for field in table.get('fields',[]) if field['name']==key['field']]
    labels=[field['name'],*[label for label in (field.get('description'),field.get('sourceDescription'))
        if label and len(label)>=2 and '来源为本文件实际字段' not in label]]
    value=key['value'] if key['sourceType']=='string' else canonical(key['value'])
    targets=question
    for label in labels:
        name=r'(?<![A-Za-z0-9_.-])(?i:'+re.escape(label)+r')(?![A-Za-z0-9_.-])'
        literal_value=re.escape(value)
        quoted=r'[\"“「『\']\s*'+literal_value+r'\s*[\"”」』\']'
        expression=name+r'\s*(?:(?:=|为|是|等于)\s*(?:'+quoted+'|'+literal_value+r'(?![A-Za-z0-9_.-]))|'+quoted+')'
        targets=re.sub(expression,' ',targets)
    return [field['name'] for field in table.get('fields',[]) if field_requested(targets,field)]

def explicit_key_binding(question,key):
    """An output field name alone is never a binding for another field's key."""
    value=key['value'] if key['sourceType']=='string' else canonical(key['value'])
    name=r'(?<![A-Za-z0-9_.-])(?i:'+re.escape(key['field'])+r')(?![A-Za-z0-9_.-])'
    token=re.escape(value)
    if key['sourceType']=='string':
        expression=r'[\"“「『\']\s*'+token+r'\s*[\"”」』\']'
        if not re.fullmatch(r'(?:-?\d+(?:\.\d+)?|true|false|null)',value,re.I):
            expression+='|'+token+r'(?![A-Za-z0-9_.-])'
    else:expression=token+r'(?![A-Za-z0-9_.-])'
    return bool(re.search(name+r'\s*(?:=|为|是|等于)\s*(?:'+expression+')',question))

def key_candidates(question,table,requested):
    result=[]
    for field in table.get('fields',[]):
        if not identifier(field):continue
        values=field.get('typedEnum')
        if values is None:values=[{'sourceType':native_type(value),'value':value} for value in field.get('enum',[])]
        for entry in values:
            value=entry.get('value');kind=entry.get('sourceType')
            # Numeric/boolean identifiers require an explicit key expression;
            # ordinary numeric words elsewhere in the question are not keys.
            if kind=='string' and literal(question,value):result.append({'field':field['name'],'sourceType':'string','value':value})
            elif kind in {'number','boolean','null'}:
                token=canonical(value)
                if re.search(re.escape(field['name'])+r'\s*(?:=|为|是|等于)\s*'+re.escape(token)+r'(?![A-Za-z0-9_.-])',question,re.I):
                    result.append({'field':field['name'],'sourceType':kind,'value':value})
        # An enum is never proof of absence. Use only an identifier namespace
        # actually observed for this field (or an explicitly named key field)
        # to discover an unseen selector, then scan the complete native table.
        types=field.get('sourceTypes',[])
        if 'string' in types or field.get('type')=='string':
            prefixes={entry['value'].split('-',1)[0] for entry in values if entry.get('sourceType')=='string' and isinstance(entry.get('value'),str) and re.match(r'^[A-Za-z]{1,16}-',entry['value'])}
            for value in re.findall(r'(?<![A-Za-z0-9_.-])[A-Za-z]{1,16}-[A-Za-z0-9][A-Za-z0-9_.-]{0,127}(?![A-Za-z0-9_.-])',question):
                candidate={'field':field['name'],'sourceType':'string','value':value}
                if value.split('-',1)[0] in prefixes or explicit_key_binding(question,candidate):result.append(candidate)
    result=list({canonical(entry):entry for entry in result}.values())
    bound=[entry for entry in result if explicit_key_binding(question,entry)]
    if bound:
        # A declared field/type disambiguates repeated reference columns for the
        # same literal. Distinct other literals or multiple bindings stay closed.
        tokens={entry['value'] if entry['sourceType']=='string' else canonical(entry['value']) for entry in bound}
        return bound+[entry for entry in result if not explicit_key_binding(question,entry)
            and (entry['value'] if entry['sourceType']=='string' else canonical(entry['value'])) not in tokens]
    return result

def same_key(value,key):
    # In particular, false != 0 and "001" != 1. Numeric lexical equivalence
    # requires a registered numeric selector, which is not inferred here.
    return native_type(value)==key['sourceType'] and canonical(value)==canonical(key['value'])

class Reader:
    def __init__(self,client,scope):self.client=client;self.scope=scope;self.started=time.monotonic();self.bytes=0;self.metadata_bytes=0;self.reads=[]
    def remaining(self):
        remaining=MAX_SECONDS-(time.monotonic()-self.started)
        if remaining<=0:raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 记录精读的时间超过本次限制。')
        return remaining
    def metadata(self,asset):
        value=self.client.evidence_metadata(*self.scope,asset['assetId'],timeout=min(15,self.remaining()))
        self.remaining()
        self.metadata_bytes+=len(canonical(value).encode())
        if self.bytes+self.metadata_bytes>MAX_BYTES:raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 元数据与记录精读的总字节超过本次限制。')
        if any(str(value.get(key))!=str(expected) for key,expected in zip(('datasetId','versionId','indexVersionId','assetId'),(*self.scope,asset['assetId']))):
            raise ValueError('EVIDENCE_RECORD_SCOPE_MISMATCH: 元数据来自不同的冻结来源。')
        return value
    def read(self,asset,operation):
        remaining=self.remaining()
        if remaining<=0 or len(self.reads)>=MAX_READS:raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 记录精读的次数或时间超过本次限制。')
        bundle=self.client.read_evidence(*self.scope,asset['assetId'],operation,timeout=min(15,remaining))
        self.remaining();receipt=verify(bundle)
        if receipt['operation']!=operation:raise ValueError('EVIDENCE_RECORD_SCOPE_MISMATCH: 实际读取操作与计划不一致。')
        self.bytes+=len(canonical(bundle).encode())
        if self.bytes+self.metadata_bytes>MAX_BYTES:raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 元数据与记录精读的总字节超过本次限制。')
        identity=bundle.get('identity',{})
        if (any(identity.get(key)!=str(value) for key,value in zip(('datasetId','versionId','indexVersionId'),self.scope))
            or identity.get('assetId')!=asset['assetId'] or identity.get('logicalPath')!=asset['logicalPath']):
            raise ValueError('EVIDENCE_RECORD_SCOPE_MISMATCH: 记录来自不同的冻结来源。')
        self.reads.append({'state':'READ','assetId':asset['assetId'],'readId':bundle['readId'],'operation':operation,
            'evidenceId':bundle['evidenceId'],'representationId':bundle['representationId'],'readOutputSha256':bundle['readOutputSha256'],
            'clientReadReceiptSha256':digest(bundle['clientReadReceipt']),'coverage':bundle['coverage']})
        return bundle

def _scan(reader,asset,table,key,fields,*,allow_missing=False):
    count=table.get('rowCount')
    if type(count) is not int or count<0 or count>MAX_ROWS:raise ValueError('EVIDENCE_RECORD_SCOPE_TOO_LARGE: 该表超出本次完整记录扫描容量，不能用样本代替全范围核对。')
    if not count and not allow_missing:raise ValueError('UNKNOWN_ENTITY: 指定来源的当前数组或工作表为空，不能生成记录值。')
    start=1;bundles=[];rows=[];seen=set()
    while True:
        operation={'operation':'read_table','tableId':table['tableId'],'rowStart':start,'rowEnd':2147483647,'fields':fields,'limit':32}
        bundle=reader.read(asset,operation);coverage=bundle.get('coverage',{});page=bundle.get('content',{}).get('records',[])
        if bundle.get('content',{}).get('tableId')!=table['tableId'] or coverage.get('totalRowCount')!=count or coverage.get('returnedRowCount')!=len(page):raise ValueError('EVIDENCE_RECORD_COVERAGE_MISMATCH: 实际记录表或记录数与冻结表范围不一致。')
        for row in page:
            number=row.get('rawRow')
            if type(number) is not int or number<start or number in seen:raise ValueError('EVIDENCE_RECORD_COVERAGE_MISMATCH: 行号重复或超出续读范围。')
            anchors=[fact for fact in bundle.get('facts',[]) if fact.get('field')==key['field'] and fact.get('locator',{}).get('rawRow')==number]
            if key['field'] in row.get('values',{}):
                if len(anchors)!=1 or canonical(anchors[0].get('value'))!=canonical(row['values'][key['field']]):
                    raise ValueError('EVIDENCE_RECORD_COVERAGE_MISMATCH: 实际记录键与原生字段事实不一致。')
            elif key['field'] not in row.get('absentFields',[]):
                raise ValueError('EVIDENCE_RECORD_COVERAGE_MISMATCH: 实际记录键的存在状态未知。')
            seen.add(number);rows.append(row)
        bundles.append(bundle)
        if not coverage.get('hasMore'):
            if coverage.get('truncated') or not coverage.get('requestedRangeComplete'):raise ValueError('EVIDENCE_RECORD_SCAN_INCOMPLETE: 未完整读取指定表范围。')
            break
        next_row=coverage.get('nextRow')
        if not page or type(next_row) is not int or next_row<=page[-1]['rawRow']:raise ValueError('EVIDENCE_RECORD_COVERAGE_MISMATCH: 缺少有效的实际续读位置。')
        start=next_row
    if len(rows)!=count:raise ValueError('EVIDENCE_RECORD_SCAN_INCOMPLETE: 不完整记录不能证明键唯一。')
    matches=[row for row in rows if key['field'] in row['values'] and same_key(row['values'][key['field']],key)]
    if not matches:
        if allow_missing:return None,bundles
        raise ValueError('UNKNOWN_ENTITY: 完整读取指定来源后没有找到该类型和原值的记录键。')
    if len(matches)!=1:raise ValueError('NEEDS_CLARIFICATION: 指定键在来源中对应多条记录，请补充实际主键或时间范围。')
    return matches[0],bundles

def validate_discovery_question(question,filters,candidates):
    """Closed phrase scope: do not discard an extra object/time/field condition."""
    scoped_formats=re.findall(r'\b(?:CSV|JSONL?|XLSX)\s*(?:文件|记录|数据)?\s*(?:中|里|内|的|:|：)',question,re.I)
    if len(scoped_formats)>1:
        raise ValueError('EVIDENCE_RECORD_RELATION_UNSUPPORTED: 当前自动定位不支持为不同来源分别指定字段，请明确相同字段的核对范围。')
    terms=set()
    for candidate in candidates:
        terms.add(str(candidate['selector']['value']))
        table,=[table for table in filters.get('tables',[]) if table['tableId']==candidate['tableId']]
        for field in table.get('fields',[]):
            if field['name'] not in {*candidate['fields'],candidate['selector']['field']}:continue
            terms.add(field['name'])
            terms.update(label for label in (field.get('description'),field.get('sourceDescription')) if label and '来源为本文件实际字段' not in label)
            if field['name'] in FIELD_WORDS:terms.update(FIELD_WORDS[field['name']].split('|'))
    remaining=question
    for term in sorted(terms,key=lambda value:(-len(value),value)):
        remaining=re.sub(r'(?<![A-Za-z0-9_.-])'+re.escape(term)+r'(?![A-Za-z0-9_.-])',' ',remaining)
    remaining=re.sub(r'\b(?:CSV|JSONL?|XLSX)\b',' ',remaining,flags=re.I)
    words=('是否一致','是否相同','是否一样','原始记录','原始数值','原始值','声明值','所声明','两份资料','两个来源','两份','两个',
        '帮我','读取','读出','核对','比较','对比','分别','各自','资料','文件','记录','数据','来源','原件','数值','字段','是否','一致','相同','一样',
        '只依据','仅依据','依据','根据','参考','并','什么','请','在','中','里','内','和','与','及','的','为','是','吗','一下')
    remaining=re.sub('|'.join(re.escape(word) for word in sorted(words,key=len,reverse=True)),' ',remaining)
    remaining=re.sub(r'[\s，。；、：？！,.?:;=“”「」『』"\'()（）]+','',remaining)
    if remaining:
        raise ValueError('NEEDS_CLARIFICATION: 当前记录规划尚未解析全部对象、字段或时间限定，请补充明确的记录核对范围。')

def discover_record_sources(question,filters,intent):
    """Use every plausible schema; never choose two formats to hide ambiguity."""
    if re.search(r'室外机|室外单元|外机|室内机|室内单元|内机|包装|净重|毛重',question):
        raise ValueError('EVIDENCE_RECORD_RELATION_UNSUPPORTED: 当前自动定位尚未登记该部件或包装口径，不能把它替换成普通产品字段。')
    available=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus' and asset.get('nativeEvidence')
        and asset.get('originalFormat') in FORMATS and (not intent.allowed or asset['originalFormat'] in intent.allowed)]
    candidates=[]
    for asset in available:
        options=[]
        for table in filters.get('tables',[]):
            if str(table['assetId'])!=asset['assetId']:continue
            initial=[field['name'] for field in table.get('fields',[]) if field_requested(question,field)]
            if not initial:continue
            keys=key_candidates(question,table,initial)
            if len(keys)>1:raise ValueError('NEEDS_CLARIFICATION: 匹配到多个记录对象，请明确本次核对的对象。')
            if not keys:continue
            fields=requested_fields(question,table,keys[0])
            if fields:options.append({'tableId':table['tableId'],'fields':fields,'selector':keys[0]})
        if len(options)>1:raise ValueError('NEEDS_CLARIFICATION: 同一资料的多个工作表或数组都可能匹配，请说明业务用途或记录范围。')
        if options:candidates.append({'asset':asset,**options[0]})
    if len(candidates)<2:return None
    validate_discovery_question(question,filters,candidates)
    return {'assets':[candidate['asset'] for candidate in candidates],
        'diagnostics':{'method':'complete-frozen-key-field-scans','potentialSourceCount':len(candidates),
            'capabilityFiltersSha256':hashlib.sha256(canonical(filters).encode()).hexdigest(),
            'candidates':[{key:value for key,value in candidate.items() if key!='asset'}|{'assetId':candidate['asset']['assetId'],'logicalPath':candidate['asset']['logicalPath']} for candidate in candidates],
            'scope':'All plausible frozen schemas are considered; complete native scans still prove record existence and uniqueness.'}}

def direct_record_question(client,scope,question,filters,intent):
    if not re.search(r'分别|核对|比较|对比|一致|相同',question):return None
    available=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus' and asset.get('nativeEvidence')]
    named=[asset for asset in available if literal(question,asset['logicalPath']) or literal(question,asset['logicalPath'].rsplit('/',1)[-1])]
    named=[asset for asset in named if not intent.allowed or asset['originalFormat'] in intent.allowed]
    discovery={'method':'explicit-named-sources'};automatic=False
    if len(named)<2:
        if named:return None
        if re.search(r'[\w.-]+\.(?:csv|xlsx|jsonl?|txt|pdf|jpe?g|png|svg|obj|step|stp)\b',question,re.I):
            raise ValueError('NEEDS_CLARIFICATION: 有指定资料未能在当前授权快照中定位。')
        discovered=discover_record_sources(question,filters,intent)
        if not discovered:return None
        named=discovered['assets'];discovery=discovered['diagnostics'];automatic=True
    if any(asset['originalFormat'] not in FORMATS for asset in named):return None
    if not automatic and len(named)>MAX_SOURCES:raise ValueError('EVIDENCE_RECORD_SOURCE_BUDGET: 本次命名记录核对的来源数超限。')
    if re.search(r'\b(?:https?|file|ftp)://',question,re.I):raise ValueError('NEEDS_CLARIFICATION: 外部地址不能替换为当前快照中的同名来源。')
    basenames={}
    for asset in named:basenames.setdefault(asset['logicalPath'].rsplit('/',1)[-1],[]).append(asset)
    if any(len(group)>1 and not all(literal(question,asset['logicalPath']) for asset in group) for group in basenames.values()):
        raise ValueError('NEEDS_CLARIFICATION: 同名文件对应多个来源，请指定完整逻辑路径。')
    without_files=question
    for asset in available:
        for name in (asset['logicalPath'],asset['logicalPath'].rsplit('/',1)[-1]):without_files=without_files.replace(name,' ')
    if re.search(r'[\w.-]+\.(?:csv|xlsx|jsonl?|txt|pdf|jpe?g|png|svg|obj|step|stp)\b',without_files,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 有指定文件未能在当前授权快照中定位。')
    plans=[]
    for asset in named:
        options=[]
        for table in filters.get('tables',[]):
            if str(table['assetId'])!=asset['assetId']:continue
            requested=[field['name'] for field in table.get('fields',[]) if field_requested(without_files,field)]
            if not requested:continue
            keys=key_candidates(without_files,table,requested)
            if len(keys)==1:
                requested=requested_fields(without_files,table,keys[0])
                if not requested:continue
                if len(requested)>MAX_FIELDS:raise ValueError('EVIDENCE_RECORD_FIELD_BUDGET: 本次记录核对的字段数超限。')
                options.append((table,requested,keys[0]))
            elif len(keys)>1:raise ValueError('NEEDS_CLARIFICATION: 问题中的多个记录键需要明确字段或关系，不能自动拼接。')
        if not options:raise ValueError('EVIDENCE_RECORD_TARGET_UNAVAILABLE: 指定来源没有可唯一识别的记录键与已注册字段。')
        if len(options)>1:raise ValueError('NEEDS_CLARIFICATION: 同一文件的多个数组或工作表都匹配，请补充实际数组路径或工作表。')
        table,requested,key=options[0];plans.append({'asset':asset,'table':table,'requestedFields':requested,'key':key})
    if len({canonical(plan['key']) for plan in plans})!=1 or len({canonical(sorted(plan['requestedFields'])) for plan in plans})!=1:
        raise ValueError('NEEDS_CLARIFICATION: 不同来源尚未建立同类型、同键字段的记录对应关系。')
    if not automatic:
        validate_discovery_question(without_files,filters,[{'asset':plan['asset'],'tableId':plan['table']['tableId'],
            'fields':plan['requestedFields'],'selector':plan['key']} for plan in plans])
    # Reserve complete scans for every plausible table and all final point reads.
    # MAX_SOURCES limits matching witnesses, never metadata possibilities.
    estimated=MAX_SOURCES if automatic else len(plans)
    for plan in plans:
        count=plan['table'].get('rowCount')
        if type(count) is not int or not 0<=count<=MAX_ROWS:raise ValueError('EVIDENCE_RECORD_SCOPE_TOO_LARGE: 候选表超出完整记录扫描容量，不能用样本排除来源。')
        estimated+=max(1,(count+31)//32)
    if estimated>MAX_READS:raise ValueError('EVIDENCE_RECORD_READ_BUDGET: 全部候选来源的完整扫描与精读超出本次次数限制。')
    reader=Reader(client,scope);evidence=[];required=[];matched=[];scan_results=[]
    for plan in plans:
        asset=plan['asset'];table=plan['table'];key=plan['key'];fields=list(dict.fromkeys([key['field'],*plan['requestedFields']]))
        metadata=reader.metadata(asset)
        registered=any(entry['kind']=='table_schema' and entry.get('selector',{}).get('tableId')==table['tableId'] or entry['kind']=='json_empty_table_schemas' and table['tableId'] in entry.get('selector',{}).get('tableIds',[]) for entry in metadata.get('representations',[]))
        if not registered:raise ValueError('EVIDENCE_INDEX_REBUILD_REQUIRED: 当前冻结索引尚未注册所需原生记录表，请选择已验证的候选索引。')
        row,scans=_scan(reader,asset,table,key,fields,allow_missing=automatic)
        scan_results.append({'assetId':asset['assetId'],'logicalPath':asset['logicalPath'],'tableId':table['tableId'],
            'status':'UNIQUE_EXACT_KEY_FOUND' if row is not None else 'COMPLETE_SCAN_KEY_ABSENT',
            'readIds':[scan['readId'] for scan in scans],'readOutputSha256s':[scan['readOutputSha256'] for scan in scans],
            'receiptSha256s':[digest(scan['clientReadReceipt']) for scan in scans],'registeredRows':table['rowCount']})
        if row is None:continue
        if any(field not in row['values'] for field in plan['requestedFields']):raise ValueError('EVIDENCE_RECORD_FIELD_ABSENT: 匹配记录未记录被问字段，不能补成 null 或零。')
        matched.append({**plan,'metadata':metadata,'row':row,'scans':scans})
    if len(matched)<2:raise ValueError('UNKNOWN_ENTITY: 完整读取全部候选来源后，未找到至少两份可核对的同键记录。')
    if len(matched)>MAX_SOURCES:raise ValueError('EVIDENCE_RECORD_SOURCE_BUDGET: 完整扫描后实际匹配来源数超限，请限定来源范围。')
    if automatic:discovery.update(scanResults=scan_results,completeCandidateScans=True,matchingSourceCount=len(matched))
    plans=matched
    for plan in plans:
        asset=plan['asset'];table=plan['table'];key=plan['key'];row=plan['row'];scans=plan['scans'];metadata=plan['metadata']
        fields=list(dict.fromkeys([key['field'],*plan['requestedFields']]))
        operation={'operation':'read_table','tableId':table['tableId'],'rowStart':row['rawRow'],'rowEnd':row['rawRow'],'fields':fields,'limit':1}
        bundle=reader.read(asset,operation)
        candidate={'assetId':asset['assetId'],'dataVersion':str(scope[1]),'indexVersion':str(scope[2]),'evidenceId':bundle['evidenceId'],
            'semanticSpace':'native.record.read','locator':bundle['locator'],'facts':{},'scoreType':'verified_record_read','evidenceType':'constraint',
            'qualityFlags':bundle['qualityFlags'],'preview':{'logicalPath':asset['logicalPath']},'provenance':{'scope':asset['scope'],'originalFormat':asset['originalFormat']}}
        candidate,_=source_from_bundle(scope,candidate,bundle,metadata,operation)
        context=json.loads(candidate['nativeModelContext'])
        key_context,=[fact for fact in context['facts'] if fact['field']==key['field']]
        context['recordBindings']={key['field']:key_context['value']}
        candidate['nativeModelContext']=json.dumps(context,ensure_ascii=False,separators=(',',':'))
        candidate['modelFactIds']=[fact['factId'] for fact in bundle['facts']]
        candidate['provenance'].update(recordReadVersion=VERSION,validatedRecordQuestion=question,explicitSourcePath=asset['logicalPath'],sourceDiscovery=discovery,
            recordSelector=key,recordTableId=table['tableId'],requestedRecordFields=plan['requestedFields'],recordScanBundles=scans,
            requiredRelationSourcePaths=sorted(entry['asset']['logicalPath'] for entry in plans),formatDiversityContribution=False)
        evidence.append(candidate);required.append(candidate['evidenceId'])
    return {'evidence':evidence,'nativeReads':reader.reads,'status':'OK','planningMethod':'bounded-native-record-plan',
        'recordPlan':{'version':VERSION,'sourceDiscovery':discovery,'requiredEvidenceIds':required,'requiredSourcePaths':sorted(plan['asset']['logicalPath'] for plan in plans),
            'requestedFields':plans[0]['requestedFields'],'selector':plans[0]['key'],'actualReadCount':len(reader.reads),'actualReadBytes':reader.bytes,'actualMetadataBytes':reader.metadata_bytes,
            'completeScansRequired':True,'scope':'Exact typed same-key records only; no inferred relation, unit conversion, aggregate or semantic endorsement.'}}
