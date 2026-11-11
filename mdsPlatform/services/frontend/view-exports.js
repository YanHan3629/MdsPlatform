(() => {
  'use strict';
  const auth=window.mdsIdentity;
  let views=[],sequence=0,controller=new AbortController(),busy=false;
  const urls=new Set();
  const interrupted=()=>new DOMException('账号或授权范围已变化','AbortError');
  function controls(){
    const allowed=auth.has('data.view.export');
    $('#controlledViewExports').hidden=!allowed;
    $('#viewExportForm').inert=!allowed;
    $('#viewExportSelect').disabled=!allowed||busy||!views.length;
    $('#viewExportDownload').disabled=!allowed||busy||!views.length;
    $('#viewExportRefresh').disabled=!allowed||busy;
  }
  function clear(){
    sequence++;controller.abort();controller=new AbortController();views=[];busy=false;
    for(const url of urls)URL.revokeObjectURL(url);urls.clear();
    $('#viewExportSelect').innerHTML=option('','选择当前获准的视图');
    $('#viewExportNotice').textContent='';controls();
  }
  function explain(error){
    if(error.status===401)return '请重新登录后重试。';
    if(error.status===403)return '当前账号没有视图导出权限。';
    if(error.status===404)return '该视图当前不可用或未获授权。';
    if(error.status===409||error.status===428)return '视图授权版本已变化，请刷新可导出范围后重试。';
    if(error.status===429)return '当前导出任务较多，请稍后重试。';
    if(error.status===413)return '该视图超出当前导出限制，请联系数据维护者拆分范围。';
    return '下载未完成，请刷新权限后重试。';
  }
  async function load(){
    if(!auth.has('data.view.export'))return clear();
    const expected=auth.revision,request=++sequence;busy=true;controls();
    try{
      const result=await auth.api('/data-space/controlled-views',{signal:controller.signal});
      if(expected!==auth.revision||request!==sequence)throw interrupted();
      views=result;
      $('#viewExportSelect').innerHTML=option('','选择当前获准的视图')+views.map(view=>option(view.viewId,
        view.datasetName+' / '+view.versionName+' / 授权版 '+view.authorizationVersion)).join('');
      if(views.length===1)$('#viewExportSelect').value=views[0].viewId;
      $('#viewExportNotice').textContent=views.length?'导出包含所选冻结视图的获准文件和校验清单。':'当前没有可导出的授权视图。';
    }catch(error){if(expected===auth.revision&&request===sequence&&error.name!=='AbortError')$('#viewExportNotice').textContent=explain(error);}
    finally{if(expected===auth.revision&&request===sequence){busy=false;controls();}}
  }
  async function download(event){
    event.preventDefault();
    if(busy)return;
    const view=views.find(item=>item.viewId===$('#viewExportSelect').value);
    if(!view||!auth.has('data.view.export'))return;
    const expected=auth.revision,request=++sequence;busy=true;controls();
    $('#viewExportNotice').textContent='正在生成并校验授权视图导出…';
    try{
      const path='/data-space/controlled-views/'+view.viewId+'/'+view.versionId+'/'+view.indexVersionId+'/export';
      const result=await auth.file(path,{signal:controller.signal,headers:{'If-Match':'"'+view.authorizationVersion+'"'}});
      if(!result.contentType.toLowerCase().startsWith('application/zip')||!/^[a-f0-9]{64}$/.test(result.sha256||''))throw new Error('Invalid export identity');
      const digest=await crypto.subtle.digest('SHA-256',await result.blob.arrayBuffer());
      const actual=[...new Uint8Array(digest)].map(v=>v.toString(16).padStart(2,'0')).join('');
      if(expected!==auth.revision||request!==sequence)throw interrupted();
      if(actual!==result.sha256)throw new Error('Export checksum mismatch');
      const url=URL.createObjectURL(result.blob);urls.add(url);
      const link=document.createElement('a');link.href=url;link.download='authorized-view-'+view.viewId+'.zip';
      document.body.append(link);link.click();link.remove();
      setTimeout(()=>{URL.revokeObjectURL(url);urls.delete(url);},1500);
      $('#viewExportNotice').textContent='授权视图已下载；文件校验通过。范围与文件清单位于压缩包内。';
    }catch(error){if(expected===auth.revision&&request===sequence&&error.name!=='AbortError')$('#viewExportNotice').textContent=explain(error);}
    finally{if(expected===auth.revision&&request===sequence){busy=false;controls();}}
  }
  document.addEventListener('DOMContentLoaded',()=>{
    $('#viewExportForm').onsubmit=download;$('#viewExportRefresh').onclick=()=>void load();
    window.addEventListener('dataspace-identity',()=>{clear();if(auth.authenticated)void load();});
    window.addEventListener('pagehide',clear);
    clear();if(auth.authenticated)void load();
  });
})();
