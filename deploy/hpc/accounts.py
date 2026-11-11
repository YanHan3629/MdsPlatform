#!/usr/bin/env python3
"""SSH control only: secret input uses getpass/stdin, never argv or files."""
import argparse,getpass,json,re,subprocess,sys
def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--action',required=True,choices=['BootstrapAdmin','UpgradeAdmin','RecoverAdmin','EnterMaintenance','ExitMaintenance','Inspect'])
 for key in ['job-id','server','user','instance','database']:p.add_argument('--'+key,required=True)
 p.add_argument('--port',type=int,default=10022);p.add_argument('--remote-root');p.add_argument('--workspace-root');p.add_argument('--target-user-id')
 p.add_argument('--reason-code',default='PLANNED_ADMINISTRATOR_MAINTENANCE');p.add_argument('--reactivate-organization',action='store_true');p.add_argument('--request-stdin',action='store_true')
 a=p.parse_args()
 def require(condition,code):
  if not condition:raise RuntimeError(code)
 require(a.job_id.isdigit() and re.fullmatch(r'[A-Za-z0-9.-]+',a.server) and re.fullmatch(r'[A-Za-z0-9_-]+',a.user),'INVALID_SSH_TARGET')
 require(1<=a.port<=65535 and re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',a.instance) and re.fullmatch(r'[A-Za-z0-9_]+',a.database),'INVALID_INSTANCE')
 workspace=a.workspace_root
 if workspace:
  require(re.fullmatch(r'/[A-Za-z0-9_./-]+',workspace) and '..' not in workspace.split('/'),'INVALID_WORKSPACE_ROOT')
 root=a.remote_root or ((workspace.rstrip('/')+'/apps/mds-platform') if workspace else '/share/home/'+a.user+'/apps/mds-platform')
 require(re.fullmatch(r'/[A-Za-z0-9_./-]+',root) and '..' not in root.split('/'),'INVALID_REMOTE_ROOT')
 actions={'BootstrapAdmin':'Initialize','UpgradeAdmin':'Upgrade','RecoverAdmin':'Recover','EnterMaintenance':'SetMaintenance','ExitMaintenance':'SetMaintenance','Inspect':'Inspect'}
 base={'instanceId':a.instance,'targetDatabase':a.database,'action':actions[a.action]}
 if a.target_user_id:base['targetUserId']=a.target_user_id
 command=['ssh','-T','-o','BatchMode=yes','-o','ConnectTimeout=15','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias=['+a.server+']:'+str(a.port),'-p',str(a.port),a.user+'@'+a.server,'python3',root+'/accounts_control.py','--job-id',a.job_id]
 if workspace:command+=['--workspace-root',workspace]
 def invoke(payload):
  result=subprocess.run(command,input=json.dumps(payload).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=360)
  lines=[x for x in result.stdout.decode('utf-8','replace').splitlines() if x.strip().startswith('{')]
  require(bool(lines),'REMOTE_CONTROL_RESULT_UNAVAILABLE');out=json.loads(lines[-1])
  require(result.returncode==0,'REMOTE_MAINTENANCE_REJECTED');return out
 if a.request_stdin:
  raw=sys.stdin.buffer.read(32769);require(len(raw)<=32768,'INPUT_TOO_LARGE');request=json.loads(raw.decode())
  for key,value in base.items():
   require(key not in request or request[key]==value,'STDIN_TARGET_MISMATCH');request[key]=value
 else:
  state=invoke(dict(base,action='Inspect'));print(json.dumps(state,ensure_ascii=False),flush=True)
  if a.action=='Inspect':return
  if a.action in ['BootstrapAdmin','UpgradeAdmin'] and state.get('initialized'):print(json.dumps({'result':'ALREADY_INITIALIZED','changed':False}));return
  request=dict(base,reasonCode=a.reason_code,confirm=True)
  if a.action in ['BootstrapAdmin','UpgradeAdmin','RecoverAdmin']:
   require(a.action=='BootstrapAdmin' or a.target_user_id,'EXPLICIT_TARGET_REQUIRED')
   if a.action=='BootstrapAdmin':request['username']=input('Administrator username: ').strip()
   new=a.action in ['BootstrapAdmin','RecoverAdmin'] or state.get('targetInfo',{}).get('knownSeedCredential')
   if new:
    value=getpass.getpass('New administrator password: ');require(value==getpass.getpass('Confirm new password: '),'PASSWORD_CONFIRMATION_MISMATCH');request['password']=value
   else:request['currentPassword']=getpass.getpass('Current non-default password (preserved): ')
  if a.action=='RecoverAdmin':request['reactivateOrganization']=a.reactivate_organization
  if a.action in ['EnterMaintenance','ExitMaintenance']:request['maintenance']=a.action=='EnterMaintenance'
 try:print(json.dumps(invoke(request),ensure_ascii=False))
 finally:request.pop('password',None);request.pop('currentPassword',None)
if __name__=='__main__':
 try:main()
 except (Exception,KeyboardInterrupt):
  print('Administrator maintenance did not complete. Passwords and request bodies are never printed.',file=sys.stderr);sys.exit(2)
