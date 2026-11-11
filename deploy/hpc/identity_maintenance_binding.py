#!/usr/bin/env python3
"""Private finite-job binding for SSH-only administrator maintenance."""
import argparse,hashlib,json,os,pwd,re,stat,subprocess,uuid
from pathlib import Path

def require(value,code):
 if not value:raise RuntimeError(code)

def private_path(path,directory=False):
 info=path.lstat()
 require(not stat.S_ISLNK(info.st_mode) and info.st_uid==os.getuid() and not stat.S_IMODE(info.st_mode)&0o077,'PRIVATE_OWNED_PATH_REQUIRED')
 require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode),'PRIVATE_PATH_TYPE_MISMATCH')
 return path

def write_private(path,value):
 flags=os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0)
 fd=os.open(str(path),flags,0o600)
 with os.fdopen(fd,'w') as stream:
  json.dump(value,stream,ensure_ascii=False,sort_keys=True);stream.write('\n')

def instance_id(data_root,configured=None):
 root_info=data_root.lstat()
 require(stat.S_ISDIR(root_info.st_mode) and root_info.st_uid==os.getuid() and not stat.S_IMODE(root_info.st_mode)&0o022,'OWNED_DEPLOYMENT_DIRECTORY_REQUIRED')
 path=data_root/'identity-instance.json'
 if path.exists() or path.is_symlink():
  value=json.loads(private_path(path).read_text())['instanceId']
  require(isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',value),'INVALID_PERSISTENT_INSTANCE_ID')
  require(not configured or configured==value,'PERSISTENT_INSTANCE_ID_MISMATCH')
  return value
 value=configured or 'mds-hpc-'+uuid.uuid4().hex
 require(isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',value),'INVALID_INSTANCE_ID')
 write_private(path,{'schema':'MDS_HPC_INSTANCE_ID_V1','instanceId':value})
 return value

def current_run(run,workspace_root=None):
 job=os.environ.get('SLURM_JOB_ID','')
 require(job.isdigit(),'FINITE_COMPUTE_ALLOCATION_REQUIRED')
 root=Path.home()
 if workspace_root is not None:
  root=Path(workspace_root)
  require(root.is_absolute() and not root.is_symlink(),'PRIVATE_WORKSPACE_ROOT_REQUIRED')
  private_path(root,True);root=root.resolve(strict=True)
 expected=root/'data/mds-platform/runs'/job
 require(run==expected and run.resolve(strict=True)==expected,'CURRENT_OWNED_RUN_REQUIRED')
 private_path(run,True)
 return job

def create_binding(run,instance,database,java,jar,environment,workspace_root=None):
 job=current_run(run,workspace_root)
 require(re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',instance) and re.fullmatch(r'[A-Za-z0-9_]+',database),'INVALID_TARGET_IDENTITY')
 require(environment.get('MDS_INSTANCE_ID')==instance,'PRIVATE_INSTANCE_MISMATCH')
 keys=['SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MDS_INSTANCE_ID']
 require(all(isinstance(environment.get(k),str) and environment[k] for k in keys),'PRIVATE_DATABASE_CONFIGURATION_REQUIRED')
 require(re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/'+database,environment['SPRING_DATASOURCE_URL']),'PRIVATE_DATABASE_TARGET_MISMATCH')
 java=Path(java).resolve(strict=True);jar=Path(jar).resolve(strict=True)
 require(java.is_file() and java.name=='java' and os.access(str(java),os.X_OK) and jar.is_file(),'VERIFIED_EXECUTABLE_AND_ARTIFACT_REQUIRED')
 digest=hashlib.sha256()
 with jar.open('rb') as stream:
  for block in iter(lambda:stream.read(8*1024*1024),b''):digest.update(block)
 config=run/'identity-maintenance-environment.json';binding=run/'identity-maintenance-binding.json'
 require(all(not p.exists() and not p.is_symlink() for p in [config,binding]),'CURRENT_JOB_BINDING_ALREADY_EXISTS')
 created=[]
 try:
  write_private(config,{k:environment[k] for k in keys});created.append(config)
  write_private(binding,{'schema':'MDS_HPC_IDENTITY_MAINTENANCE_BINDING_V1','jobId':job,'instanceId':instance,
   'database':database,'java':str(java),'jar':str(jar),'jarSha256':digest.hexdigest(),'privateEnvironmentFile':str(config)});created.append(binding)
 except BaseException:
  for path in reversed(created):path.unlink()
  raise
 return {'jobId':job,'instanceId':instance,'database':database,'jarSha256':digest.hexdigest()}

def clear_binding(run,workspace_root=None):
 current_run(run,workspace_root)
 # Only the two exact files created for this allocation may be removed.
 for name in ['identity-maintenance-binding.json','identity-maintenance-environment.json']:
  path=run/name
  if path.exists() or path.is_symlink():private_path(path).unlink()

def maintenance_run_path(job,workspace_root=None):
 require(isinstance(job,str) and job.isdigit(),'INVALID_JOB_ID')
 root=Path.home()
 if workspace_root is not None:
  root=Path(workspace_root)
  require(root.is_absolute() and '..' not in root.parts and not root.is_symlink(),'PRIVATE_WORKSPACE_ROOT_REQUIRED')
  private_path(root,True);root=root.resolve(strict=True)
 run=root/'data/mds-platform/runs'/job
 require(run.resolve(strict=True)==run,'CURRENT_OWNED_RUN_REQUIRED')
 return private_path(run,True)

def describe_job(job,workspace_root=None):
 require(job.isdigit(),'INVALID_JOB_ID')
 lines=subprocess.check_output(['squeue','-h','-j',job,'-o','%i|%u|%T|%P'],universal_newlines=True,timeout=20).strip().splitlines()
 require(len(lines)==1,'RUNNING_OWNED_CPU_JOB_REQUIRED')
 fields=lines[0].split('|')
 require(fields[0]==job and fields[1]==pwd.getpwuid(os.getuid()).pw_name and fields[2]=='RUNNING' and fields[3] in ['amd','intel','fata'],'RUNNING_OWNED_CPU_JOB_REQUIRED')
 run=maintenance_run_path(job,workspace_root)
 value=json.loads(private_path(run/'identity-maintenance-binding.json').read_text())
 require(value.get('jobId')==job,'BINDING_JOB_MISMATCH')
 return {k:value[k] for k in ['jobId','instanceId','database','jarSha256']}

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--describe-job',required=True);parser.add_argument('--workspace-root')
 arguments=parser.parse_args()
 try:print(json.dumps(describe_job(arguments.describe_job,arguments.workspace_root)))
 except Exception:
  print(json.dumps({'success':False,'errorType':'VERIFIED_RUNNING_TARGET_UNAVAILABLE'}));raise SystemExit(2)
