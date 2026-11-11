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
  const notice=message=>window.dispatchEvent(new CustomEvent('dataspace-notice',{detail:{message}}));
  const generationKey=root=>channelName+'.switch.'+root;
  const latestGeneration=root=>localStorage.getItem(generationKey(root))||'';
  function configuredRoot() {
    const raw=document.getElementById('apiBase')?.value||localStorage.getItem('dataSpaceApi')||'/api/data-space';
    const url=new URL(raw,location.href), path=url.pathname.replace(/\/+$/,'');
    if(!['http:','https:'].includes(url.protocol)||url.username||url.password||url.search||url.hash||!path.endsWith('/data-space'))
      throw new Error('后端地址应指向数据空间 API。');
    return url.origin+path.slice(0,-'/data-space'.length);
  }
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
    for(const key of ['qaIndex','qaDraft','retrievalDraft'])sessionStorage.removeItem(key);
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
  async function errorMessage(response,fallback) {
    try {const body=await response.json();return body.detail||body.message||body.errorType||fallback;} catch {return fallback;}
  }
  async function rotate(expected) {
    if(!refresh||expected!==revision)return false;
    if(rotating)return rotating;
    const old=refresh, controller=abort, root=bound;
    const pending=(async()=>{
      try {
        const response=await fetch(root+'/auth/refresh',{method:'POST',credentials:'omit',redirect:'error',signal:controller.signal,
          headers:{'Content-Type':'application/json'},body:JSON.stringify({refreshToken:old})});
        const value=await response.json();
        if(expected!==revision||old!==refresh||!checkSessionGeneration())throw interrupted();
        if(!response.ok||typeof value.token!=='string'||typeof value.refreshToken!=='string')return false;
        access=value.token;refresh=value.refreshToken;persist();return true;
      } catch(error) {if(error.name==='AbortError')throw error;return false;}
    })();
    rotating=pending;
    try{return await pending;}finally{if(rotating===pending)rotating=null;}
  }
  async function rawAccess(url,options={},allowRotate=true) {
    if(!checkSessionGeneration())throw interrupted();
    const expected=revision, headers=new Headers(options.headers||{});
    headers.set('Authorization','Bearer '+access);
    const signal=options.signal?AbortSignal.any([options.signal,abort.signal]):abort.signal;
    const response=await fetch(scopedUrl(url),{...options,headers,signal,credentials:'omit',redirect:'error'});
    if(expected!==revision||!checkSessionGeneration())throw interrupted();
    if(response.status===401&&allowRotate&&await rotate(expected))return rawAccess(url,options,false);
    return {response,expected};
  }
  async function checkIdentity({force=false}={}) {
    if(!access||!bound){if(force)emit('signed-out');return null;}
    if(!checkSessionGeneration())return null;
    let configured;
    try{configured=configuredRoot();}catch{invalidate('backend-invalid',{drop:false});return null;}
    if(configured!==bound){invalidate('backend-changed');return null;}
    if(checking)return checking;
    const expected=revision;
    const pending=(async()=>{
      try {
        const result=await rawAccess(bound+'/users/me');
        const value=await result.response.json();
        if(result.expected!==revision||!checkSessionGeneration())throw interrupted();
        if(!result.response.ok||!value.userId||!value.orgId||!Array.isArray(value.operations)){
          invalidate('session-invalid',{announce:true});return null;
        }
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
        const value=await target[key](...args);
        if(expected!==revision||!checkSessionGeneration())throw interrupted();
        return value;
      };
      const value=Reflect.get(target,key,target);return typeof value==='function'?value.bind(target):value;
    }});
  }
  async function authenticatedFetch(url,options={}) {
    if(!identity||!access)throw new Error('请先登录数据空间账号。');
    const result=await rawAccess(url,options);
    if(!result.response.ok) {
      const status=result.response.status, message=await errorMessage(result.response,'请求失败（'+status+'）。');
      if(result.expected!==revision||!checkSessionGeneration())throw interrupted();
      if(status===401)invalidate('session-invalid',{announce:true});
      else if(status===403||status===404)void checkIdentity();
      const error=new Error(message);error.status=status;throw error;
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
          } else if(renewed.status===401)return true;
        }
        if(response.ok||response.status===401)return true;
      } catch { /* Retry only the captured prior identity; never substitute the current session. */ }
      if(attempt<2)await new Promise(resolve=>setTimeout(resolve,500*(attempt+1)));
    }
    return false;
  }
  async function logout() {
    if(!checkSessionGeneration())return true;
    const captured={access,refresh,root:bound};invalidate('logout');
    if(captured.access&&captured.root)recordSwitch('logout');
    const revoked=await revokeCaptured(captured.access,captured.refresh,captured.root);
    if(!revoked)notice('本机已退出；服务器会话撤销未能确认，请恢复连接后再次登录并退出。');
    return revoked;
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
    const response=await fetch(root+'/auth/login',{method:'POST',credentials:'omit',redirect:'error',signal:controller.signal,
      headers:{'Content-Type':'application/json'},body:JSON.stringify(credentials)});
    const value=await response.json();
    if(expected!==revision||startingGeneration!==latestGeneration(root))throw interrupted();
    if(!response.ok)throw new Error(value.detail||value.message||value.errorType||'登录失败。');
    bound=root;sessionGeneration=startingGeneration;
    if(value.activationRequired) {
      if(typeof value.activationToken!=='string')throw new Error('账号激活凭据无效。');
      activation={token:value.activationToken,username:value.user?.username||credentials.userName,root};emit('activation-required');
      return {activationRequired:true};
    }
    if(typeof value.token!=='string'||!value.token)throw new Error('登录响应缺少有效会话。');
    access=value.token;refresh=value.refreshToken||'';persist();
    const verified=await checkIdentity({force:true});
    if(!verified)throw new Error('登录身份未能通过服务端确认，请重新登录。');
    if(expected!==revision||!checkSessionGeneration())throw interrupted();
    recordSwitch('login');return {activationRequired:false,identity:verified};
  }
  async function activate(newPassword) {
    if(!activation||!checkSessionGeneration())throw new Error('请先使用待激活账号登录。');
    const pending=activation,expected=revision;
    const response=await fetch(pending.root+'/auth/activate',{method:'POST',credentials:'omit',redirect:'error',signal:abort.signal,
      headers:{Authorization:'Bearer '+pending.token,'Content-Type':'application/json'},body:JSON.stringify({newPassword})});
    const value=await response.json();
    if(expected!==revision||activation!==pending||!checkSessionGeneration())throw interrupted();
    if(!response.ok)throw new Error(value.detail||value.message||value.errorType||'激活失败。');
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
    fetch:authenticatedFetch,api,file,login,logout,activate,check:checkIdentity,policyChanged,
    cancelActivation(){invalidate('activation-cancelled');},
    configure(){let changed=false;try{changed=!!bound&&configuredRoot()!==bound;}catch{changed=true;}if(changed){const captured={access,refresh,root:bound};invalidate('backend-changed');void revokeCaptured(captured.access,captured.refresh,captured.root);}},
    has(operation){return !!identity?.operations.includes(operation);},
    get authenticated(){return !!identity;},get identity(){return copy(identity);},get revision(){return revision;},
    get activationRequired(){return !!activation;}
  };
})();
