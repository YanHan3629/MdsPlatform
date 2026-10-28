"""Validate complete lookup witnesses and same-row record claim bindings."""
import hashlib,re,uuid
from decimal import Decimal,InvalidOperation
from services.answer_lineage import LineageError,require,canonical,same,value_type
from services.record_evidence_plan import VERSION,SUPPORTED_READ_VERSIONS,MAX_ROWS,MAX_SOURCES,same_key
from services.read_receipt import verify,digest
COMPARISON_VERSION='native-record-declared-value-equality-2026-10-26.1'
IDENTIFIER_COMPARISON_VERSION='native-record-identifier-string-equality-2026-10-26.1'

def identifier_unit_annotations(left,right):
    """No physical-unit equivalence is inferred for original ID strings."""
    from services.record_evidence_plan import identifier
    return (left['field']==right['field'] and identifier({'name':left['field']})
        and left['sourceType']==right['sourceType']=='string'
        and all(node['unit'] in {None,'不适用','见对应源表字段/记录'} for node in (left,right)))

def comparison_version(left,right):
    return IDENTIFIER_COMPARISON_VERSION if not same(left['unit'],right['unit']) and identifier_unit_annotations(left,right) else COMPARISON_VERSION

def identifier_comparison_scope(left,right):
    if comparison_version(left,right)==IDENTIFIER_COMPARISON_VERSION:
        return {'inputUnitAnnotations':[left['unit'],right['unit']],
            'unitInterpretation':'Identifier raw strings only; no physical-unit equivalence.'}
    return {}

def validate_source(source):
    provenance=source.get('provenance',{});bundle=source.get('evidence_bundle',{});identity=bundle.get('identity',{})
    require(provenance.get('recordReadVersion') in SUPPORTED_READ_VERSIONS,'CLAIM_RECORD_RECIPE_UNSUPPORTED')
    verify(bundle);operation=provenance.get('nativeReadOperation',{});selector=provenance.get('recordSelector',{})
    fields=provenance.get('requestedRecordFields',[]);paths=provenance.get('requiredRelationSourcePaths',[])
    require(isinstance(fields,list) and 1<=len(fields)<=4 and len(set(fields))==len(fields),'CLAIM_RECORD_FIELDS_INVALID')
    require(set(selector)=={'field','sourceType','value'} and selector['sourceType'] in {'string','number','boolean','null'},'CLAIM_RECORD_SELECTOR_INVALID')
    require(isinstance(paths,list) and 2<=len(paths)<=MAX_SOURCES and len(set(paths))==len(paths) and identity.get('logicalPath') in paths,'CLAIM_RECORD_REQUIRED_SOURCES_INVALID')
    require(identity.get('logicalPath')==provenance.get('explicitSourcePath'),'CLAIM_RECORD_SOURCE_PATH_CHANGED')
    requested=list(dict.fromkeys([selector['field'],*fields]));rows=bundle.get('content',{}).get('records',[]);coverage=bundle.get('coverage',{})
    require(operation.get('operation')=='read_table' and operation.get('tableId')==provenance.get('recordTableId')==bundle.get('content',{}).get('tableId'),'CLAIM_RECORD_TABLE_MISMATCH')
    require(operation.get('fields')==requested and operation.get('limit')==1 and operation.get('rowStart')==operation.get('rowEnd'),'CLAIM_RECORD_POINT_REQUEST_INVALID')
    require(len(rows)==coverage.get('returnedRowCount')==1 and coverage.get('requestedRangeComplete') and not coverage.get('hasMore') and not coverage.get('truncated'),'CLAIM_RECORD_POINT_INCOMPLETE')
    row=rows[0];number=row.get('rawRow');require(type(number) is int and number==operation['rowStart'],'CLAIM_RECORD_POINT_ROW_MISMATCH')
    require(selector['field'] in row.get('values',{}) and same_key(row['values'][selector['field']],selector),'CLAIM_RECORD_POINT_KEY_MISMATCH')
    require(all(field in row['values'] for field in fields),'CLAIM_RECORD_FIELD_ABSENT')
    facts=bundle.get('facts',[]);by_field={fact['field']:fact for fact in facts}
    require(len(by_field)==len(facts) and set(by_field)==set(requested),'CLAIM_RECORD_POINT_FACTS_INVALID')
    for field,fact in by_field.items():
        require(fact.get('evidenceNature')=='source_record' and fact.get('locator',{}).get('rawRow')==number,'CLAIM_RECORD_POINT_FACT_SCOPE_MISMATCH')
        require(same(fact.get('value'),row['values'][field]),'CLAIM_RECORD_POINT_VALUE_MISMATCH')
        require(not isinstance(fact.get('value'),(dict,list)),'CLAIM_RECORD_COMPOSITE_REQUIRES_SUBTREE_READER')
        require('FORMULA_CACHE_UNVERIFIED' not in fact.get('qualityFlags',[]),'CLAIM_RECORD_FORMULA_REQUIRES_WORKBOOK_READER')
        if identity.get('originalFormat') in {'JSON','JSONL'}:
            pointer=row.get('locator',{}).get('jsonPointer','')+'/'+field.replace('~','~0').replace('/','~1')
            require(fact['locator'].get('jsonPointer')==pointer and fact['locator'].get('jsonlLine')==row.get('locator',{}).get('jsonlLine'),'CLAIM_RECORD_FIELD_POINTER_MISMATCH')
    scans=provenance.get('recordScanBundles',[]);require(isinstance(scans,list) and 1<=len(scans)<=4,'CLAIM_RECORD_SCAN_UNAVAILABLE')
    total=coverage.get('totalRowCount');require(type(total) is int and 1<=total<=MAX_ROWS,'CLAIM_RECORD_SCAN_CAPACITY')
    start=1;seen=set();matches=[];receipts=[];read_ids=[]
    for index,scan in enumerate(scans):
        receipt=verify(scan);scan_request=receipt['operation'];read_ids.append(scan['readId']);receipts.append(digest(receipt))
        require(same(scan.get('identity'),identity),'CLAIM_RECORD_SCAN_SOURCE_MISMATCH')
        require(scan_request=={'operation':'read_table','tableId':operation['tableId'],'rowStart':start,'rowEnd':2147483647,'fields':requested,'limit':32},'CLAIM_RECORD_SCAN_REQUEST_CHANGED')
        scan_rows=scan.get('content',{}).get('records',[]);scan_coverage=scan.get('coverage',{})
        require(scan.get('content',{}).get('tableId')==operation['tableId'] and scan_coverage.get('totalRowCount')==total and scan_coverage.get('returnedRowCount')==len(scan_rows),'CLAIM_RECORD_SCAN_COVERAGE_MISMATCH')
        for record in scan_rows:
            raw=record.get('rawRow');require(type(raw) is int and raw>=start and raw not in seen,'CLAIM_RECORD_SCAN_ROW_DUPLICATE_OR_MISSING');seen.add(raw)
            if selector['field'] in record.get('values',{}):
                anchors=[fact for fact in scan.get('facts',[]) if fact.get('field')==selector['field'] and fact.get('locator',{}).get('rawRow')==raw]
                require(len(anchors)==1 and same(anchors[0]['value'],record['values'][selector['field']]),'CLAIM_RECORD_SCAN_KEY_FACT_MISMATCH')
                if same_key(record['values'][selector['field']],selector):matches.append(record)
            else:require(selector['field'] in record.get('absentFields',[]),'CLAIM_RECORD_SCAN_KEY_PRESENCE_UNKNOWN')
        if index==len(scans)-1:
            require(not scan_coverage.get('hasMore') and not scan_coverage.get('truncated') and scan_coverage.get('requestedRangeComplete'),'CLAIM_RECORD_SCAN_INCOMPLETE')
        else:
            require(scan_rows and scan_coverage.get('hasMore') and scan_coverage.get('truncated'),'CLAIM_RECORD_SCAN_CONTINUATION_INVALID')
            start=scan_coverage.get('nextRow');require(type(start) is int and start>scan_rows[-1]['rawRow'],'CLAIM_RECORD_SCAN_CONTINUATION_INVALID')
    require(len(seen)==total and len(matches)==1,'CLAIM_RECORD_KEY_NOT_PROVEN_UNIQUE')
    require(matches[0]['rawRow']==number and same(matches[0]['values'],row['values']),'CLAIM_RECORD_SCAN_AND_POINT_DISAGREE')
    return {'selector':selector,'requestedFields':fields,'keyFactId':by_field[selector['field']]['factId'],'scanReceiptSha256s':receipts,'scanReadIds':read_ids,'requiredSourcePaths':paths}

def view(source,fact,validated):
    return {**validated,'selectable':fact['field'] in validated['requestedFields'],'sourcePath':source['logical_path']}

def bindings(source,node,facts):
    meta=node['recordView'];anchor=facts.get(meta['keyFactId'])
    require(anchor is not None and same(anchor['identity'],node['identity']) and anchor['locator'].get('rawRow')==node['locator'].get('rawRow'),'CLAIM_RECORD_ANCHOR_UNAVAILABLE')
    require(anchor['locator'].get('sheet')==node['locator'].get('sheet') and anchor['locator'].get('jsonlLine')==node['locator'].get('jsonlLine'),'CLAIM_RECORD_ANCHOR_SCOPE_MISMATCH')
    if node['identity']['originalFormat'] in {'JSON','JSONL'}:
        require(anchor['locator'].get('jsonPointer','').rsplit('/',1)[0]==node['locator'].get('jsonPointer','').rsplit('/',1)[0],'CLAIM_RECORD_ANCHOR_ARRAY_MISMATCH')
    return {meta['selector']['field']:anchor['value']},[anchor['factId']] if anchor['factId']!=node['factId'] else []

def comparison_pairs(nodes):
    grouped={}
    for node in nodes:
        if node.get('recordView'):grouped.setdefault(node['field'],[]).append(node)
    result=[]
    for field,pair in sorted(grouped.items()):
        if len(pair)>2:
            from services.record_group_comparison import registered
            operation=registered(pair)
            if operation:result.append(operation)
            continue
        if len(pair)!=2:continue
        left,right=sorted(pair,key=lambda node:node['recordView']['sourcePath'])
        if (any(left['identity'][key]!=right['identity'][key] for key in ('datasetId','versionId','indexVersionId','rawCommitId'))
            or left['identity']['assetId']==right['identity']['assetId'] or not same(left['recordView']['selector'],right['recordView']['selector'])
            or sorted(left['recordView']['requiredSourcePaths'])!=sorted(right['recordView']['requiredSourcePaths'])):continue
        if not same(left['unit'],right['unit']):
            if identifier_unit_annotations(left,right):
                result.append({'operation':'record_value_equal','leftFactId':left['factId'],'rightFactId':right['factId']});continue
            from services.record_unit_conversion import compatible,OPERATION
            if compatible(left,right):result.append({'operation':OPERATION,'leftFactId':left['factId'],'rightFactId':right['factId']})
            continue
        if 'number' in {left['sourceType'],right['sourceType']} and left['unit'] is None:continue
        result.append({'operation':'record_value_equal','leftFactId':left['factId'],'rightFactId':right['factId']})
    return result

def preflight(catalogue,question):
    sources=[source for source in catalogue.sources if source.get('provenance',{}).get('recordReadVersion')]
    if not sources:return
    require(len(sources)==len(catalogue.sources),'CLAIM_RECORD_MIXED_CONTRACT_UNSUPPORTED')
    actual=sorted(source['logical_path'] for source in sources)
    for source in sources:
        provenance=source['provenance']
        require(provenance.get('validatedRecordQuestion')==question,'CLAIM_RECORD_QUESTION_CHANGED')
        require(actual==sorted(provenance['requiredRelationSourcePaths']),'CLAIM_RECORD_REQUIRED_SOURCE_ABSENT')
    require(not re.search(r'差多少|差值|相差|合计|求和|总和|平均|趋势|增长|换算|高于|低于|更大|更小',question),'CLAIM_RECORD_RELATION_UNSUPPORTED')
    if re.search(r'相同|一致|一样|同一个|同一人|同一主体',question):
        nodes=[node for node in catalogue.facts.values() if node['selectable']]
        require(all(node['sourceType'] in {'number','string','boolean','null'} for node in nodes),'CLAIM_RECORD_COMPARISON_TYPE_UNSUPPORTED')
        require(all(node['calculationEligible'] for node in nodes if node['sourceType']=='number'),'CLAIM_RECORD_NUMBER_NOT_CALCULABLE')
        pairs=comparison_pairs(nodes)
        require(len(pairs)==len({node['field'] for node in nodes}),'CLAIM_RECORD_UNIT_OR_RELATION_UNAVAILABLE')

def equal(left,right):
    require(any(pair['operation']=='record_value_equal' for pair in comparison_pairs([left,right])),'CLAIM_RECORD_COMPARISON_NOT_REGISTERED')
    if left['sourceType']!=right['sourceType']:return False
    if left['sourceType']=='number':
        require(left['calculationEligible'] and right['calculationEligible'],'CLAIM_RECORD_NUMBER_NOT_CALCULABLE')
        values=[]
        for node in (left,right):
            try:value=Decimal(node['value']['sourceNumber'])
            except (InvalidOperation,TypeError,KeyError):raise LineageError('CLAIM_RECORD_NUMBER_INVALID')
            require(value.is_finite() and len(value.as_tuple().digits)<=4096 and abs(value.as_tuple().exponent)<=4096,'CLAIM_RECORD_NUMBER_BUDGET')
            values.append(value)
        return values[0]==values[1]
    require(left['sourceType'] in {'string','boolean','null'},'CLAIM_RECORD_COMPARISON_TYPE_UNSUPPORTED')
    return same(left['value'],right['value'])

def calculation_id(left,right):
    return str(uuid.uuid5(uuid.NAMESPACE_URL,canonical([comparison_version(left,right),'record_value_equal',left['factId'],right['factId'],left['recordView']['selector'],left['unit'],right['unit']])))

def calculate(catalogue,question):
    """Execute requested read-only operations before the explanation step."""
    if not any(source.get('provenance',{}).get('recordReadVersion') for source in catalogue.sources):return []
    preflight(catalogue,question)
    if not re.search(r'相同|一致|一样|同一个|同一人|同一主体',question):return []
    nodes=[node for node in catalogue.facts.values() if node['selectable']];results=[]
    for operation in comparison_pairs(nodes):
        if operation['operation']=='record_group_value_equal':
            from services.record_group_comparison import calculation
            results.append(calculation([catalogue.facts[identifier] for identifier in operation['inputFactIds']]))
            continue
        left=catalogue.facts[operation['leftFactId']];right=catalogue.facts[operation['rightFactId']]
        if operation['operation']=='record_unit_value_equal':
            from services.record_unit_conversion import calculation
            results.append(calculation(left,right));continue
        results.append({'calculationId':calculation_id(left,right),'recipeVersion':comparison_version(left,right),'evidenceNature':'program_calculation',
            'operation':'record_value_equal','inputFactIds':[left['factId'],right['factId']],'field':left['field'],
            'recordKey':left['recordView']['selector'],'inputTypes':[left['sourceType'],right['sourceType']],
            'declaredUnit':left['unit'],'result':equal(left,right),**identifier_comparison_scope(left,right)})
    return results

def verify_calculations_transmitted(results,comparisons,calls):
    wanted={(operation['operation'],*(operation['inputFactIds'] if operation['operation']=='record_group_value_equal' else [operation['leftFactId'],operation['rightFactId']]))
        for operation in comparisons if operation['operation'] in {'record_value_equal','record_unit_value_equal','record_group_value_equal'}}
    require(wanted<={(result['operation'],*result['inputFactIds']) for result in results},'CLAIM_RECORD_CALCULATION_NOT_ACTUALLY_TRANSMITTED')
    expected=calculation_context(results);expected_sha=hashlib.sha256(expected.encode()).hexdigest()
    for result in results:
        if (result['operation'],*result['inputFactIds']) not in wanted:continue
        matched=False
        for call in calls:
            if call.get('status')!='response_received':continue
            actual_facts={identifier for source in call.get('actualEvidence',[]) if source.get('contextMatchedActualRequest') and source.get('factIdsPresentInActualRequest') for identifier in source.get('factIds',[])}
            if not set(result['inputFactIds'])<=actual_facts:continue
            for message in call.get('request',{}).get('messages',[]):
                if message.get('role')!='user':continue
                content=message.get('content',[])
                if not isinstance(content,list):continue
                for part in content:
                    if (part.get('type')=='text' and part.get('textSha256')==expected_sha and part.get('characters')==len(expected)
                        and ('text' not in part or part['text']==expected)):matched=True
        require(matched,'CLAIM_RECORD_CALCULATION_NOT_ACTUALLY_TRANSMITTED')

def calculation_context(results):
    return '程序只读比较结果（关联已有字段，不是第三份独立来源）：'+canonical(results)
