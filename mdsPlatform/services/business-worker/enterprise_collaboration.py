"""Bridge the fixed enterprise recipe to private proposal-preview receipts.
Responsibility is deterministic; candidate creation never approves publication.
"""
import csv,io,json,re,uuid
from pathlib import Path
import collaboration_view as cv
import enterprise_view
from scope_ownership import Corpus,json_text

SCHEMA='ENTERPRISE_RESPONSIBILITY_RULES_V1'
COMPLETE_EXT={'.txt','.pdf','.png','.jpg','.jpeg','.svg','.obj','.step','.stp'}
KINDS={'ROW_PROJECTION','EXPLICIT_PARTY_COLUMN_PROJECTION','JSON_POINTER_PROJECTION','JSONL_RECORD_PROJECTION','XLSX_VISIBLE_TYPED_CELL_REBUILD','SERIES_SCOPE_PROJECTION','COMPLETE_FILE'}
def compile_candidate(snapshot,rules,target,module_hashes):
    cv.keys(rules,['schema','viewId','targetOrgId','targetEnterpriseId','source','files','reason'])
    if rules['schema']!=SCHEMA:cv.fail('UNKNOWN_RESPONSIBILITY_RULE_SCHEMA')
    view=cv.uuid_value(rules['viewId']);org=cv.uuid_value(rules['targetOrgId']);enterprise=rules['targetEnterpriseId']
    if not isinstance(enterprise,str) or not re.fullmatch('ENT-[A-Za-z0-9]{1,100}',enterprise):cv.fail('TARGET_ENTERPRISE_ID_REQUIRED')
    if not isinstance(rules['reason'],str) or not 6<=len(rules['reason'].strip())<=500:cv.fail('EXPLICIT_RULE_REASON_REQUIRED')
    if rules['source']!=snapshot.source:cv.fail('SOURCE_SNAPSHOT_BINDING_MISMATCH')
    inventory=cv.loads(snapshot.manifest_path.read_text(encoding='utf-8'))
    if inventory.get('inventoryScope')!='ENTERPRISE_RESPONSIBILITY_INPUTS_V1':cv.fail('RESPONSIBILITY_INPUT_SCOPE_PROTOCOL_REQUIRED')
    source_files=inventory.get('sourceFiles')
    if not isinstance(source_files,list) or len(source_files)!=inventory['version']['raw_file_count']:cv.fail('RESPONSIBILITY_REQUIRES_COMPLETE_SOURCE_METADATA')
    all_files={};identities=set();casefold=set()
    for f in source_files:
        cv.keys(f,['logical_path','file_id','size_bytes','sha256'])
        name=f['logical_path'];name=cv.safe_path(name[1:] if isinstance(name,str) and name.startswith('/') else name)
        identity=cv.uuid_value(f['file_id']);size=cv.integer(f['size_bytes'],0);digest=cv.hash_value(f['sha256'])
        if name in all_files or name.casefold() in casefold or identity in identities:cv.fail('RESPONSIBILITY_SOURCE_METADATA_COLLISION')
        all_files[name]=(identity,size,digest);identities.add(identity);casefold.add(name.casefold())
    if not isinstance(rules['files'],list) or len(rules['files'])>250:cv.fail('COMPLETE_FILE_REQUEST_LIST_REQUIRED')
    target=Path(target)
    if target.exists() or target.is_symlink():cv.fail('OUTPUT_MUST_BE_NEW_PRIVATE_DIRECTORY')
    resolved=target.resolve()
    if resolved==snapshot.cache or snapshot.cache in resolved.parents or resolved in snapshot.cache.parents:cv.fail('OUTPUT_OVERLAPS_SOURCE_CACHE')
    rule_sha=cv.digest(cv.canonical(rules));requests={};aliases=set()
    for f in rules['files']:
        cv.keys(f,['sourcePath','sourceSha256','outputPath','kind','acknowledgement','recordIds'])
        source=cv.safe_path(f['sourcePath'],True);out=cv.safe_path(f['outputPath'],True)
        if source not in snapshot.files or snapshot.files[source]['sha256']!=cv.hash_value(f['sourceSha256']):cv.fail('RULE_SOURCE_FILE_IDENTITY_MISMATCH')
        if source!=out or source.casefold() in aliases or Path(source).suffix.lower() not in COMPLETE_EXT or f['kind']!='COMPLETE_FILE' or f['acknowledgement']!=cv.ACK:cv.fail('RESPONSIBILITY_COMPLETE_FILE_REQUEST_INVALID')
        aliases.add(source.casefold());records=cv.string_list(f['recordIds'])
        requests[source]={'enterprise_id':enterprise,'source_sha256':f['sourceSha256'],'review_state':'PENDING_PUBLICATION_REVIEW','request_sha256':rule_sha,'record_ids':records}
    required={p for p in all_files if p.startswith('catalog/') or p.startswith('corpus/') and Path(p).suffix in {'.csv','.json','.jsonl','.xlsx'}}|set(requests)
    if set(snapshot.files)!=required:cv.fail('RESPONSIBILITY_REQUIRED_INPUTS_INCOMPLETE')
    for p,f in snapshot.files.items():
        if (f['id'],f['size'],f['sha256'])!=all_files[p]:cv.fail('RESPONSIBILITY_SELECTED_INPUT_METADATA_CHANGED')
    try:
        corpus=Corpus(snapshot.manifest_path,snapshot.cache,allow_scoped_inventory=True)
        # This is an inventory digest, not the artifact manifest object's digest.
        corpus.source_snapshot=dict(snapshot.source)
        if enterprise not in corpus.companies:cv.fail('TARGET_ENTERPRISE_NOT_IN_SOURCE')
        builder=enterprise_view.Builder(corpus,enterprise,target,uuid.UUID(view),requests,review_pending=True)
        builder.run()
        if builder.pending:cv.fail('RESPONSIBILITY_FORMULA_OR_SCHEMA_DECISION_PENDING')
        if not builder.assets or len(builder.assets)>2048:cv.fail('RESPONSIBILITY_BUSINESS_FILE_COUNT_INVALID')
        copied=set()
        for f in builder.provenance:
            if f['kind']=='REQUESTED_COMPLETE_FILE':f['kind']='COMPLETE_FILE';copied.add(f['path'])
            if f['kind'] not in KINDS:cv.fail('RESPONSIBILITY_PROVENANCE_KIND_UNSUPPORTED')
            f['selectedRecordCount']=len(f['locations']);f['sourceRecordCount']=None
            if f['kind'] in {'ROW_PROJECTION','EXPLICIT_PARTY_COLUMN_PROJECTION','SERIES_SCOPE_PROJECTION'}:
                reader=csv.DictReader(io.StringIO(snapshot.read(f['sourcePath']).decode('utf-8-sig')))
                f['sourceRecordCount']=sum(1 for _ in reader)
            if f['kind']=='JSONL_RECORD_PROJECTION':f['sourceRecordCount']=sum(bool(line.strip()) for line in snapshot.read(f['sourcePath']).decode('utf-8-sig').splitlines())
            if f['kind']=='COMPLETE_FILE':f['sourceRecordCount']=len(f['locations'])
        if copied!=set(requests):cv.fail('RESPONSIBILITY_COMPLETE_REQUEST_NOT_MATERIALIZED')
        for asset in builder.assets:
            asset['format']=cv.FORMATS[Path(asset['path']).suffix.lower()]
        public=target/'public';catalog=public/'catalog'
        (catalog/'assets.json').write_text(json_text(builder.assets)+'\n',encoding='utf-8')
        # Keep the same seven approved catalog paths as other controlled previews.
        (catalog/'assets.csv').unlink()
        (catalog/'view_scope.json').write_text(json_text({'viewId':view,'ruleSha256':rule_sha,'responsibilityRule':enterprise_view.RULE_VERSION,'policyCheckRequired':True,'hasOriginalByteCopies':bool(copied)})+'\n',encoding='utf-8')
        files=[{'path':str(p.relative_to(public)).replace('\\','/'),'sha256':cv.sha(p),'bytes':p.stat().st_size} for p in sorted(public.rglob('*')) if p.is_file()]
        receipt={'schema':SCHEMA,'viewId':view,'targetOrgId':org,'targetEnterpriseId':enterprise,'source':snapshot.source,'ruleSha256':rule_sha,
          'state':'CANDIDATE_AWAITING_REVIEW','hasOriginalByteCopies':bool(copied),'files':files,'provenance':builder.provenance,'moduleSha256':module_hashes,'pendingDecisionCount':0}
        (target/'private-rules.json').write_bytes(cv.canonical(rules)+b'\n')
        (target/'private-provenance.json').write_text(json_text(receipt)+'\n',encoding='utf-8')
        result={'schema':SCHEMA,'viewId':view,'state':'CANDIDATE_AWAITING_REVIEW','fileCount':len(builder.assets),'hasOriginalByteCopies':bool(copied),'ruleSha256':rule_sha,
          'candidateReceiptSha256':cv.sha(target/'private-provenance.json'),'originalObjectsModified':False,'authorizationGranted':False,'published':False}
        (target/'candidate-state.json').write_bytes(cv.canonical(result)+b'\n');return result
    except BaseException as error:
        if target.is_dir() and not target.is_symlink():
            (target/'candidate-state.json').write_bytes(cv.canonical({'state':'FAILED_NOT_PUBLISHED','errorType':type(error).__name__,'errorCode':str(error) if isinstance(error,cv.RuleError) else 'RESPONSIBILITY_PROJECTION_FAILED','authorizationGranted':False,'published':False})+b'\n')
        raise
