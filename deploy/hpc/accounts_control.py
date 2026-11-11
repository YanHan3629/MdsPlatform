#!/usr/bin/env python3
"""Login-node Slurm control only: forward private stdin into an owned CPU job."""
import argparse,json,os,subprocess,sys
from pathlib import Path
from identity_maintenance_binding import describe_job,maintenance_run_path
def require(condition,code):
 if not condition:raise RuntimeError(code)
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--job-id',required=True);p.add_argument('--workspace-root');a=p.parse_args()
 require(a.job_id.isdigit(),'INVALID_JOB_ID')
 raw=sys.stdin.buffer.read(32769);require(len(raw)<=32768,'INPUT_TOO_LARGE');payload=json.loads(raw.decode())
 target=describe_job(a.job_id,a.workspace_root)
 require(target.get('instanceId')==payload.get('instanceId') and target.get('database')==payload.get('targetDatabase'),'TARGET_MISMATCH')
 binding=maintenance_run_path(a.job_id,a.workspace_root)/'identity-maintenance-binding.json'
 helper=Path(__file__).resolve().with_name('accounts_compute.py')
 command=['srun','--jobid='+a.job_id,'--overlap','--exact','--nodes=1','--ntasks=1','--cpus-per-task=1','--mem=2048M','--time=00:05:00','python3',str(helper),'--binding',str(binding)]
 if a.workspace_root is not None:command+=['--workspace-root',a.workspace_root]
 result=subprocess.run(command,input=raw,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=330)
 lines=[x for x in result.stdout.decode('utf-8','replace').splitlines() if x.strip().startswith('{')]
 require(bool(lines),'COMPUTE_RESULT_UNAVAILABLE');print(json.dumps(json.loads(lines[-1])),flush=True);return result.returncode
if __name__=='__main__':
 try:sys.exit(main())
 except Exception:
  print(json.dumps({'success':False,'errorType':'CONTROLLED_MAINTENANCE_NOT_COMPLETED'}));sys.exit(2)
