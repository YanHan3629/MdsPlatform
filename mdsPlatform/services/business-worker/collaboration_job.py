"""Trusted job entrypoint for the frozen collaboration compiler bundle.
The scheduler must freeze this code, capsule and resource scope together.
No approval, resource grant, publication or index activation occurs here.
"""
import argparse,base64,hashlib,json,os,shutil
from pathlib import Path
import collaboration_view as cv
SCHEMA='COLLABORATION_JOB_CAPSULE_V1'
MODULES={'collaboration_job.py','collaboration_view.py','scope_ownership.py','scoped_xlsx.py'}
RESPONSIBILITY_SCHEMA='ENTERPRISE_RESPONSIBILITY_RULES_V1'
def metadata_path(value):
    return cv.safe_path(value[1:] if isinstance(value,str) and value.startswith('/') else value)
def validated_inventory(capsule,input_root):
    cv.keys(capsule,['schema','sourceRepoId','modules','inventory','rules'])
    if capsule['schema']!=SCHEMA:cv.fail('JOB_CAPSULE_SCHEMA_INVALID')
    repo=cv.uuid_value(capsule['sourceRepoId'])
    required=MODULES|({'enterprise_view.py','enterprise_collaboration.py'} if capsule.get('rules',{}).get('schema')==RESPONSIBILITY_SCHEMA else set())
    if not isinstance(capsule['modules'],dict) or set(capsule['modules'])!=required:cv.fail('JOB_MODULE_BUNDLE_INCOMPLETE')
    for name,item in capsule['modules'].items():
        cv.keys(item,['data','sha256']);expected=cv.hash_value(item['sha256'])
        try:data=base64.b64decode(item['data'],validate=True)
        except (TypeError,ValueError):cv.fail('JOB_MODULE_ENCODING_INVALID')
        if cv.digest(data)!=expected or (Path(__file__).parent/name).read_bytes()!=data:cv.fail('JOB_MODULE_BYTE_IDENTITY_CHANGED')
    try:raw=base64.b64decode(capsule['inventory'],validate=True)
    except (TypeError,ValueError):cv.fail('JOB_INVENTORY_ENCODING_INVALID')
    inventory=cv.loads(raw.decode('utf-8'))
    if not isinstance(inventory,dict) or not isinstance(inventory.get('version'),dict):cv.fail('CAPSULE_INPUT_INVENTORY_INVALID')
    version=inventory['version']
    commit=cv.uuid_value(version.get('raw_commit_id'))
    if os.environ.get('MDS_INPUT_REPO_ID')!=repo or os.environ.get('MDS_INPUT_COMMIT_ID')!=commit:cv.fail('JOB_FROZEN_INPUT_BINDING_CHANGED')
    metadata_file=input_root/'files.json'
    if not metadata_file.is_file() or metadata_file.is_symlink():cv.fail('BOOTSTRAP_INPUT_INVENTORY_REQUIRED')
    metadata=cv.loads(metadata_file.read_text(encoding='utf-8'))
    if not isinstance(metadata,dict) or cv.uuid_value(metadata.get('artifactId'))!=repo or cv.uuid_value(metadata.get('commitId'))!=commit or str(metadata.get('commitStatus','')).upper()!='PUBLISHED':cv.fail('BOOTSTRAP_FROZEN_SOURCE_NOT_AVAILABLE')
    listed=metadata.get('fileRespList');declared=metadata.get('total')
    if not isinstance(listed,list) or not listed or isinstance(declared,bool) or not isinstance(declared,int) or declared!=len(listed):cv.fail('BOOTSTRAP_INPUT_INVENTORY_INCOMPLETE')
    if not isinstance(inventory.get('files'),list) or cv.integer(inventory.get('inventoryFileCount',version.get('raw_file_count')))!=declared or cv.integer(version.get('raw_file_count'))<declared:cv.fail('CAPSULE_INPUT_INVENTORY_INCOMPLETE')
    observed={};identities=set()
    for item in listed:
        if not isinstance(item,dict):cv.fail('BOOTSTRAP_FILE_DESCRIPTOR_INVALID')
        path=metadata_path(item.get('logicalPath'));identity=cv.uuid_value(item.get('fileId'));size=cv.integer(item.get('sizeBytes'),0);sha=cv.hash_value(item.get('sha256'))
        if path in observed or identity in identities or item.get('uploadState')!='READY':cv.fail('BOOTSTRAP_FILE_IDENTITY_AMBIGUOUS')
        observed[path]=(identity,size,sha);identities.add(identity)
    expected={}
    for item in inventory['files']:
        if not isinstance(item,dict):cv.fail('CAPSULE_FILE_DESCRIPTOR_INVALID')
        path=metadata_path(item.get('logical_path'))
        if path in expected:cv.fail('CAPSULE_FILE_IDENTITY_AMBIGUOUS')
        expected[path]=(cv.uuid_value(item.get('file_id')),cv.integer(item.get('size_bytes'),0),cv.hash_value(str(item.get('sha256','')).strip()))
    if observed!=expected:cv.fail('CAPSULE_AND_BOOTSTRAP_INPUTS_DIFFER')
    rules=capsule['rules']
    if not isinstance(rules,dict) or not isinstance(rules.get('source'),dict) or rules['source'].get('inputInventorySha256')!=cv.digest(raw):cv.fail('CAPSULE_RULE_INVENTORY_BINDING_CHANGED')
    return raw,inventory,expected,cv.sha(metadata_file)
def execute(capsule,input_dir='/tmp/in',output_dir='/tmp/out',workspace='/tmp/collaboration-work',candidate_dir='/tmp/collaboration-candidate'):
    roots=[Path(p) for p in [input_dir,output_dir,workspace,candidate_dir]]
    for p in roots:
        if p.is_symlink():cv.fail('JOB_WORKSPACE_SYMLINK_FORBIDDEN')
    source,out,work,candidate=roots;resolved=[p.resolve() for p in roots]
    if len(set(resolved))!=4 or any(a in b.parents for i,a in enumerate(resolved) for j,b in enumerate(resolved) if i!=j):cv.fail('JOB_WORKSPACES_MUST_BE_SEPARATE')
    if not source.is_dir() or work.exists() or candidate.exists() or (out.exists() and (not out.is_dir() or any(out.iterdir()))):cv.fail('JOB_WORKSPACE_NOT_NEW_OR_EMPTY')
    raw,inventory,expected,bootstrap_sha=validated_inventory(capsule,source)
    work.mkdir(mode=0o700,parents=True);cache=work/'input';cache.mkdir(mode=0o700)
    manifest=work/'inventory.json';manifest.write_bytes(raw)
    for path,(identity,size,sha) in expected.items():
        original=source/path
        if original.is_symlink() or not original.is_file() or source.resolve() not in original.resolve().parents:cv.fail('BOOTSTRAP_FILE_PATH_ESCAPES_INPUT')
        data=original.read_bytes()
        if len(data)!=size or cv.digest(data)!=sha:cv.fail('BOOTSTRAP_FILE_CHANGED_BEFORE_COMPILATION')
        (cache/identity).write_bytes(data)
    snapshot=cv.Snapshot(manifest,cache)
    if capsule['rules'].get('schema')==RESPONSIBILITY_SCHEMA:
        import enterprise_collaboration
        result=enterprise_collaboration.compile_candidate(snapshot,capsule['rules'],candidate,{n:i['sha256'] for n,i in capsule['modules'].items()})
    else:result=cv.Compiler(snapshot,capsule['rules'],candidate).run()
    receipt=cv.loads((candidate/'private-provenance.json').read_text(encoding='utf-8'))
    public=candidate/'public';observed={str(p.relative_to(public)).replace('\\','/') for p in public.rglob('*') if p.is_file()}
    approved={f['path']:f for f in receipt['files']}
    if observed!=set(approved) or any(p.is_symlink() for p in public.rglob('*')):cv.fail('CANDIDATE_PUBLIC_INVENTORY_CHANGED')
    for path,item in approved.items():
        p=public/cv.safe_path(path)
        if p.stat().st_size!=item['bytes'] or cv.sha(p)!=item['sha256']:cv.fail('CANDIDATE_PUBLIC_FILE_CHANGED')
    out.mkdir(mode=0o700,parents=True,exist_ok=True)
    for path,item in approved.items():
        target=out/cv.safe_path(path);target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(str(public/path),str(target))
        if target.stat().st_size!=item['bytes'] or cv.sha(target)!=item['sha256']:cv.fail('PUBLIC_UPLOAD_STAGING_FILE_CHANGED')
    execution={'schema':SCHEMA,'state':'CANDIDATE_AWAITING_REVIEW','bootstrapInventorySha256':bootstrap_sha,'inputInventorySha256':cv.digest(raw),
      'ruleSha256':result['ruleSha256'],'candidateReceiptSha256':result['candidateReceiptSha256'],'publicFileCount':len(approved),
      'moduleSha256':{n:i['sha256'] for n,i in capsule['modules'].items()},'authorizationGranted':False,'published':False}
    (work/'private-execution-receipt.json').write_bytes(cv.canonical(execution)+b'\n')
    return result
def main():
    p=argparse.ArgumentParser();p.add_argument('--capsule',required=True);a=p.parse_args()
    result=execute(cv.loads(Path(a.capsule).read_text(encoding='utf-8')))
    print(json.dumps({'state':result['state'],'fileCount':result['fileCount'],'authorizationGranted':False,'published':False}))
if __name__=='__main__':main()
