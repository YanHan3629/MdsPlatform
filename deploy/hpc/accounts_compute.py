#!/usr/bin/env python3
"""Compute-only helper: DB secrets are private environment, operator secrets stdin."""
import argparse,hashlib,json,os,re,stat,subprocess,sys
from pathlib import Path
from identity_maintenance_binding import current_run,maintenance_run_path
def require(condition,code):
 if not condition:raise RuntimeError(code)
def private_file(path):
 info=path.stat();require(info.st_uid==os.getuid() and not stat.S_IMODE(info.st_mode)&0o077,'PRIVATE_CONFIGURATION_REQUIRED');return path
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--binding',required=True);p.add_argument('--workspace-root');a=p.parse_args()
 job=os.environ.get('SLURM_JOB_ID','');require(job.isdigit(),'COMPUTE_ALLOCATION_REQUIRED')
 run=maintenance_run_path(job,a.workspace_root);current_run(run,a.workspace_root);binding=Path(a.binding).resolve(strict=True)
 require(binding==run/'identity-maintenance-binding.json','CURRENT_JOB_BINDING_REQUIRED')
 private_file(binding);b=json.loads(binding.read_text());require(b.get('jobId')==job,'CURRENT_JOB_BINDING_REQUIRED')
 raw=sys.stdin.buffer.read(32769);require(len(raw)<=32768,'INPUT_TOO_LARGE');request=json.loads(raw.decode())
 require(request.get('instanceId')==b['instanceId'] and request.get('targetDatabase')==b['database'],'TARGET_MISMATCH')
 path=Path(b['privateEnvironmentFile']).resolve(strict=True);require(run in path.parents,'PRIVATE_CONFIG_SCOPE_MISMATCH')
 private_file(path);values=json.loads(path.read_text())
 required={'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MDS_INSTANCE_ID'}
 require(required.issubset(values),'PRIVATE_CONFIG_INCOMPLETE')
 require(values['MDS_INSTANCE_ID']==b['instanceId'],'PRIVATE_INSTANCE_MISMATCH')
 require(isinstance(values['SPRING_DATASOURCE_URL'],str) and re.fullmatch(r'jdbc:postgresql://127\.0\.0\.1:[0-9]+/'+re.escape(b['database']),values['SPRING_DATASOURCE_URL']),'PRIVATE_DATABASE_TARGET_MISMATCH')
 jar=Path(b['jar']).resolve(strict=True);digest=hashlib.sha256()
 with jar.open('rb') as stream:
  for block in iter(lambda:stream.read(8*1024*1024),b''):digest.update(block)
 require(digest.hexdigest()==b['jarSha256'],'CANDIDATE_ARTIFACT_MISMATCH')
 java=Path(b['java']).resolve(strict=True);require(java.name=='java' and java.is_file(),'VERIFIED_JAVA_REQUIRED')
 env=dict(os.environ);env.update({k:str(values[k]) for k in required})
 result=subprocess.run([str(java),'-Xmx512m','-Dloader.main=com.fwdrobo.sirius.security.AdministratorMaintenanceMain','-cp',str(jar),'org.springframework.boot.loader.launch.PropertiesLauncher'],
     input=raw,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=300)
 lines=[x for x in (result.stdout+result.stderr).decode('utf-8','replace').splitlines() if x.strip().startswith('{')]
 require(bool(lines),'COMPUTE_RESULT_UNAVAILABLE');print(json.dumps(json.loads(lines[-1])),flush=True);return result.returncode
if __name__=='__main__':
 try:sys.exit(main())
 except Exception:
  print(json.dumps({'success':False,'errorType':'COMPUTE_MAINTENANCE_NOT_COMPLETED'}));sys.exit(2)
