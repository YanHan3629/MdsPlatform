(() => {
  'use strict';
  const storageKey='mds.identity.session.v1';
  const channelName='mds.identity.v1';
  let saved;
  try { saved=JSON.parse(sessionStorage.getItem(storageKey)||'null'); } catch { saved=null; }
  let access=saved?.access||'', refresh=saved?.refresh||'', bound=saved?.root||'';
  let sessionGeneration=saved?.generation||'';
  let identity=null, activation=null, revision=0, abort=new AbortController(), rotating=null, checking=null;
  const nonce=crypto.randomUUID();
  const channel=typeof BroadcastChannel==='function'?new BroadcastChannel(channelName):null;
  const copy=value=>value==null?null:JSON.parse(JSON.stringify(value));
  const interrupted=()=>new DOMException('账号或授权范围已变化','AbortError');
  const generationKey=root=>channelName+'.switch.'+root;
  const latestGeneration=root=>localStorage.getItem(generationKey(root))||'';
  const configuredRoot = () => location.origin + '/api';
  const timedSignal = signal => AbortSignal.any([signal, AbortSignal.timeout(10000)]);

  function persist() {
    if(access&&bound)sessionStorage.setItem(storageKey,JSON.stringify({access,refresh,root:bound,generation:sessionGeneration}));
    else sessionStorage.removeItem(storageKey);
    for(const key of ['qaToken','qaAccount'])sessionStorage.removeItem(key);
  }
  function emit(reason) {
    window.dispatchEvent(new CustomEvent('dataspace-identity',{detail:{authenticated:!!identity,identity:copy(identity),revision,reason,activationRequired:!!activation,activationUsername:activation?.username||''}}));
  }
  function broadcast(kind,generation) {
    const event={nonce,kind,root:bound,stamp:crypto.randomUUID(),generation};
    channel?.postMessage(event);
    localStorage.setItem(channelName,JSON.stringify(event));localStorage.removeItem(channelName);
  }
  function recordSwitch(kind) {
    // A closed or suspended document can miss a transient cross-tab event.
    // Only a non-secret generation is shared, scoped to the configured backend.
    sessionGeneration=crypto.randomUUID();
    localStorage.setItem(generationKey(bound),sessionGeneration);
    persist();
    broadcast(kind,sessionGeneration);
  }
  function invalidate(reason,{drop=true,announce=false}={}) {
    const priorRoot=bound;
    revision++;abort.abort();abort=new AbortController();identity=null;activation=null;checking=null;rotating=null;
    if(drop){access='';refresh='';sessionGeneration='';persist();}
    if(reason!=='page-hidden'){
      for(const key of ['qaIndex','qaDraft','retrievalDraft','qaSelection','retrievalSelection'])sessionStorage.removeItem(key);
    }
    localStorage.removeItem('activeBusiness');
    emit(reason);
    if(announce){const current=bound;bound=priorRoot;broadcast(reason);bound=current;}
  }
  function checkSessionGeneration() {
    if(!bound||(!access&&!activation)||sessionGeneration===latestGeneration(bound))return true;
    invalidate('another-tab-changed-identity');
    return false;
  }
  function scopedUrl(raw) {
    const root=new URL(bound), url=new URL(raw,root.href+'/');
    if(configuredRoot()!==bound||url.origin!==root.origin||!url.pathname.startsWith(root.pathname+'/')||url.username||url.password)
      throw new Error('后端地址已变化，请重新登录。');
    return url.href;
  }
  const responseMessages = {
    400:'请核对必填项和输入格式后重试。',401:'登录已失效，请重新登录。',
    403:'当前账号无法访问此资源，请核对授权或返回列表。',
    404:'当前账号无法访问此资源，请核对授权或返回列表。',
    408:'处理超时，请稍后重试。',409:'记录或授权已变化，请刷新后重试。',
    410:'该链接或授权已到期，请重新获取。',412:'授权版本已变化，请刷新后重试。',
    413:'文件或请求超出大小限制，请缩小后重试。',415:'格式不受支持，请核对文件类型。',
    422:'资料无法解析，请检查文件内容和格式。',428:'请刷新授权后重新提交。',
    429:'请求较多，请稍后重试。',502:'后端服务暂时无法连接，请稍后重试。',
    503:'服务暂时不可用，请稍后重试。',504:'处理超时，请缩小范围后重试。'
  };
  function requestNumber(value) {
    return typeof value==='string'&&/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value)?value:null;
  }
  function apiError(message,status,code,requestId) {
    return Object.assign(new Error(requestId?`${message} 请求编号 ${requestId}`:message),{status,code,requestId});
  }
  async function readResponse(response,method,args=[]) {
    try { return await response[method](...args); }
    catch(error) {
      if(error?.name==='AbortError')throw error;
      if(error?.name==='TimeoutError')throw new DOMException('连接或处理超时，请稍后重试。','TimeoutError');
      const malformed=method==='json'&&error?.name==='SyntaxError';
      throw apiError(malformed?'平台返回的响应格式无效，请刷新页面后重试。':'读取平台响应失败，请检查网络或访问隧道后重试。',
        response.status,malformed?'INVALID_RESPONSE_FORMAT':'RESPONSE_READ_FAILED',requestNumber(response.headers.get('X-Request-Id')));
    }
  }
  async function responseError(response, fallback) {
    let body;
    try { body=await response.json(); } catch {}
    const status=response.status;
    const requestId=requestNumber(body?.requestId)||requestNumber(response.headers.get('X-Request-Id'));
    const allowedDetail=typeof body?.message==='string' && body.message.length<=300 &&
      /^\p{Script=Han}/u.test(body.message) && !/[<>\r\n]/.test(body.message) &&
      !/\b(?:[\w.$]*(?:Exception|Error)|Traceback)\b|[A-Za-z]:[\\/]/i.test(body.message) &&
      status!==403&&status!==404&&(status<500||requestId);
    const message=allowedDetail?body.message:responseMessages[status]||fallback||'请求处理失败，请稍后重试。';
    return apiError(message,status,body?.errorType,requestId);
  }
  async function networkFetch(url, options) {
    try { return await fetch(url, options); }
    catch(error) {
      if(error.name==='AbortError')throw error;
      if(error.name==='TimeoutError')throw new DOMException('连接或处理超时，请稍后重试。','TimeoutError');
      if(error instanceof TypeError)throw new Error('无法连接平台，请检查网络或访问隧道后重试。');
      throw error;
    }
  }
  async function rotate(expected) {
    if(!refresh||expected!==revision)return false;
    if(rotating)return rotating;
    const old=refresh, controller=abort, root=bound;
    const pending=(async()=>{
      try {
        const response=await fetch(root+'/auth/refresh',{method:'POST',credentials:'omit',redirect:'error',signal:timedSignal(controller.signal),
          headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:old})});
        if(expected!==revision||old!==refresh||!checkSessionGeneration())throw interrupted();
        if(response.status===401||response.status===403) {
          invalidate('session-invalid',{announce:true});
          return false;
        }
        if(!response.ok)throw new Error('服务器暂时无法刷新会话。');
        const value=await readResponse(response,'json');
        if(expected!==revision||old!==refresh||!checkSessionGeneration())throw interrupted();
        if(typeof value?.token!=='string'||typeof value.refreshToken!=='string')throw new Error('刷新会话响应无效。');
        access=value.token;refresh=value.refreshToken;persist();return true;
      } catch(error) {
        if(expected!==revision)throw interrupted();
        throw error;
      }
    })();
    rotating=pending;
    try{return await pending;}finally{if(rotating===pending)rotating=null;}
  }
  async function rawAccess(url,options={},allowRotate=true) {
    if(!checkSessionGeneration())throw interrupted();
    const expected=revision, sentAccess=access, headers=new Headers(options.headers||{});
    headers.set('Authorization','Bearer '+sentAccess);
    const signal=options.signal?AbortSignal.any([options.signal,abort.signal]):abort.signal;
    const response=await networkFetch(scopedUrl(url),{...options,headers,signal,credentials:'omit',redirect:'error'});
    if(expected!==revision||!checkSessionGeneration())throw interrupted();
    if(response.status===401&&allowRotate) {
      const renewed=sentAccess!==access||await rotate(expected);
      if(expected!==revision||!checkSessionGeneration())throw interrupted();
      if(renewed)return rawAccess(url,options,false);
    }
    return {response,expected};
  }
  async function checkIdentity({force=false}={}) {
    if(!access||!bound){if(force)emit('signed-out');return null;}
    if(!checkSessionGeneration())return null;
    if(configuredRoot()!==bound){invalidate('backend-changed');return null;}
    if(checking)return checking;
    const expected=revision;
    const pending=(async()=>{
      try {
        const result=await rawAccess(bound+'/users/me',{cache:'no-store',signal:timedSignal(abort.signal)});
        if(result.response.status===401||result.response.status===403){
          invalidate('session-invalid',{announce:true});return null;
        }
        if(!result.response.ok)throw new Error('服务器暂时无法验证身份。');
        const value=await readResponse(result.response,'json');
        if(result.expected!==revision||!checkSessionGeneration())throw interrupted();
        if(!value?.userId||!value.orgId||!Array.isArray(value.operations))throw new Error('身份验证响应无效。');
        const previous=identity;
        const changed=previous&&(previous.userId!==value.userId||previous.orgId!==value.orgId||
          previous.permissionVersion!==value.permissionVersion||previous.policyVersion!==value.policyVersion);
        if(changed) {
          invalidate('permissions-changed',{drop:false});
          identity=value;emit('permissions-verified');
        } else {identity=value;if(!previous||force)emit('identity-verified');}
        return copy(identity);
      } catch(error) {
        if(error.name==='AbortError')return null;
        if(expected===revision)invalidate('authority-unavailable',{drop:false});
        return null;
      }
    })();
    checking=pending;
    try{return await pending;}finally{if(checking===pending)checking=null;}
  }
  function guardedResponse(response,expected) {
    return new Proxy(response,{get(target,key){
      if(['json','text','blob','arrayBuffer','formData'].includes(key))return async(...args)=>{
        if(expected!==revision||!checkSessionGeneration())throw interrupted();
        try { return await readResponse(target,key,args); }
        finally { if(expected!==revision||!checkSessionGeneration())throw interrupted(); }
      };
      const value=Reflect.get(target,key,target);return typeof value==='function'?value.bind(target):value;
    }});
  }
  async function authenticatedFetch(url,options={}) {
    if(!identity||!access)throw new Error('请先登录数据空间账号。');
    const result=await rawAccess(url,options);
    if(!result.response.ok) {
      const status=result.response.status, error=await responseError(result.response);
      if(result.expected!==revision||!checkSessionGeneration())throw interrupted();
      if(status===401)invalidate('session-invalid',{announce:true});
      else if(status===403||status===404)void checkIdentity();
      throw error;
    }
    const permission=result.response.headers.get('X-Identity-Permission-Version');
    const policy=result.response.headers.get('X-Authorization-Policy-Version');
    if(permission&&Number(permission)!==identity.permissionVersion||policy&&Number(policy)!==identity.policyVersion)
      queueMicrotask(()=>void checkIdentity());
    return guardedResponse(result.response,result.expected);
  }
  async function api(path,options={}) {
    if(typeof path!=='string'||!path.startsWith('/')||path.startsWith('//'))throw new Error('接口地址无效。');
    let request={...options};
    if('json' in request){request.headers={...request.headers,'Content-Type':'application/json'};request.body=JSON.stringify(request.json);delete request.json;}
    const result=await authenticatedFetch(bound+path,request);
    if(result.status===204)return null;
    return result.json();
  }
  async function file(path,options={}) {
    if(typeof path!=='string'||!path.startsWith('/')||path.startsWith('//'))throw new Error('接口地址无效。');
    const expected=revision,response=await authenticatedFetch(bound+path,{...options,method:'GET'});
    const blob=await response.blob();
    if(expected!==revision)throw interrupted();
    return {blob,sha256:response.headers.get('X-Export-Sha256'),contentType:response.headers.get('Content-Type')||''};
  }
  async function revokeCaptured(oldAccess,oldRefresh,root) {
    if(!oldAccess||!root)return true;
    if(root!==configuredRoot())return false;
    for(let attempt=0;attempt<3;attempt++) {
      try {
        let response=await fetch(root+'/auth/logout',{method:'POST',credentials:'omit',redirect:'error',
          headers:{Authorization:'Bearer '+oldAccess},signal:AbortSignal.timeout(5000)});
        if(response.status===401&&oldRefresh) {
          const renewed=await fetch(root+'/auth/refresh',{method:'POST',credentials:'omit',redirect:'error',
            headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:oldRefresh}),signal:AbortSignal.timeout(5000)});
          const value=await renewed.json();
          if(renewed.ok&&value.token) {
            oldAccess=value.token;oldRefresh=value.refreshToken||'';
            response=await fetch(root+'/auth/logout',{method:'POST',credentials:'omit',redirect:'error',
              headers:{Authorization:'Bearer '+oldAccess},signal:AbortSignal.timeout(5000)});
          } else if(renewed.status===401||renewed.status===403)return false;
        }
        if(response.ok&&(await response.json()).allSessionsRevoked===true)return true;
        if(response.status===401||response.status===403)return false;
      } catch { /* Retry only the captured prior identity; never substitute the current session. */ }
      if(attempt<2)await new Promise(resolve=>setTimeout(resolve,500*(attempt+1)));
    }
    return false;
  }
  function endSession() {
    const hadSession=!!(access&&bound);
    invalidate('logout');
    if(hadSession)recordSwitch('logout');
  }
  async function logout() {
    if(!checkSessionGeneration())return false;
    const captured={access,refresh,root:bound};
    endSession();
    return revokeCaptured(captured.access,captured.refresh,captured.root);
  }
  async function login(credentials) {
    checkSessionGeneration();
    const root=configuredRoot(), captured={access,refresh,root:bound};
    const startingGeneration=latestGeneration(root);
    invalidate('login-start');
    const expected=revision, controller=abort;
    if(captured.access&&!await revokeCaptured(captured.access,captured.refresh,captured.root))
      throw new Error('旧会话撤销未确认，请恢复连接后重试。');
    if(expected!==revision||startingGeneration!==latestGeneration(root))throw interrupted();
    const response=await networkFetch(root+'/auth/login',{method:'POST',credentials:'omit',redirect:'error',signal:timedSignal(controller.signal),
      headers:{'Content-Type':'application/json'},body:JSON.stringify(credentials)});
    const outcome=response.ok?await readResponse(response,'json'):await responseError(response,'登录失败，请核对账号和密码。');
    if(expected!==revision||startingGeneration!==latestGeneration(root))throw interrupted();
    if(!response.ok)throw outcome;
    const value=outcome;
    bound=root;sessionGeneration=startingGeneration;
    if(value?.activationRequired) {
      if(typeof value.activationToken!=='string')throw new Error('账号激活凭据无效。');
      activation={token:value.activationToken,username:value.user?.username||credentials.userName,root};emit('activation-required');
      return {activationRequired:true};
    }
    if(typeof value?.token!=='string'||!value.token)throw new Error('登录响应缺少有效会话。');
    access=value.token;refresh=value.refreshToken||'';recordSwitch('login');
    const verified=await checkIdentity({force:true});
    if(!verified)throw new Error('登录身份未能通过服务端确认，请重新登录。');
    if(expected!==revision||!checkSessionGeneration())throw interrupted();
    return {activationRequired:false,identity:verified};
  }
  async function activate(newPassword) {
    if(!activation||!checkSessionGeneration())throw new Error('请先使用待激活账号登录。');
    const pending=activation,expected=revision;
    const response=await networkFetch(pending.root+'/auth/activate',{method:'POST',credentials:'omit',redirect:'error',signal:timedSignal(abort.signal),
      headers:{Authorization:'Bearer '+pending.token,'Content-Type':'application/json'},body:JSON.stringify({newPassword})});
    const error=response.ok?null:await responseError(response,'激活失败，请核对密码要求后重试。');
    if(expected!==revision||activation!==pending||!checkSessionGeneration())throw interrupted();
    if(error)throw error;
    activation=null;invalidate('activated');recordSwitch('activation');return pending.username;
  }
  async function policyChanged() {
    broadcast('policy');invalidate('permissions-changed',{drop:false});
    await checkIdentity({force:true});
  }
  function receive(event) {
    const value=event?.data||event;
    if(!value||value.nonce===nonce||value.root!==bound)return;
    if(value.generation){checkSessionGeneration();return;}
    if(value.kind==='policy'){invalidate('permissions-changed',{drop:false});void checkIdentity({force:true});}
    else if(value.kind==='session-invalid'){
      // A stale tab can only establish that its own credential failed.
      void checkIdentity();
    } else invalidate('another-tab-changed-identity');
  }
  channel?.addEventListener('message',receive);
  window.addEventListener('storage',event=>{
    if(bound&&event.key===generationKey(bound))checkSessionGeneration();
    else if(event.key===channelName&&event.newValue){try{receive(JSON.parse(event.newValue));}catch {}}
  });
  window.addEventListener('pagehide',()=>invalidate('page-hidden',{drop:false}));
  window.addEventListener('pageshow',()=>void checkIdentity({force:true}));
  window.addEventListener('focus',()=>void checkIdentity());
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')void checkIdentity();});
  document.addEventListener('DOMContentLoaded',()=>{
    for(const key of ['qaToken','qaAccount'])sessionStorage.removeItem(key);
    if(!bound||!access)invalidate('signed-out');
    else void checkIdentity({force:true});
    setInterval(()=>{if(document.visibilityState==='visible')void checkIdentity();},5000);
  });
  window.mdsIdentity={
    fetch:authenticatedFetch,api,file,login,logout,endSession,activate,check:checkIdentity,policyChanged,
    cancelActivation(){invalidate('activation-cancelled');},
    has(operation){return !!identity?.operations.includes(operation);},
    get authenticated(){return !!identity;},get identity(){return copy(identity);},get revision(){return revision;},
    get activationRequired(){return !!activation;}
  };
})();
