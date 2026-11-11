(() => {
 'use strict';
 const auth=window.mdsIdentity,$=id=>document.getElementById(id),base='/identity/resource-grants';
 const scopeLabels={DATASET_CUSTODY:'本企业数据保管（包括后续版本）',FROZEN_VERSION:'完整冻结版本',CONTROLLED_VIEW:'已就绪受控视图'};
 const purposeLabels={GENERAL_READ:'常规使用',VIEW_BUILD:'索引／视图构建',CONTROLLED_DELIVERY:'合同交付'};
 const labels={'data.catalog.read':'查看授权目录','data.preview':'预览授权内容','data.search':'专业检索','data.qa':'证据问答','data.raw.download':'下载完整原件','data.view.export':'导出授权视图','dataset.share.manage':'配置本方资源分享','dataset.create':'创建数据集','product.publish':'发布数据产品','product.unpublish':'下架数据产品','product.summary.manage':'维护公开产品摘要','application.submit':'提交本企业消费申请','application.review':'审核本方供给申请','delivery.endpoint.manage':'维护本企业接收端','dataset.upload':'上传数据','dataset.draft.manage':'维护草稿','dataset.publish':'发布版本','dataset.quality.run':'发起质量评估','dataset.quality.read':'查看质量详情','dataset.index.build':'构建索引','dataset.index.activate':'切换索引','job.read':'查看范围内任务','job.log.read':'读取范围内任务日志','job.cancel':'取消范围内任务','contract.read':'查看本方合同','contract.edit':'拟定合同','contract.confirm':'代表本企业确认合同','contract.revoke':'撤销本方合同','delivery.execute':'执行合同交付','delivery.receipt.read':'查看交付回执','delivery.package.download':'下载授权交付包'};
 const errors={
  RESOURCE_SCOPE_NOT_AVAILABLE:'当前账号没有覆盖本次全部动作、用途和范围的分享授权。',
  COMPLETE_PROVIDER_SHARE_AUTHORITY_REQUIRED:'分享授权已变化，请重新核对来源授权后预览。',
  OPERATION_DENIED:'当前账号缺少该操作权限。资源授权不会自动增加账号的业务操作权限。',
  OWN_PROVIDER_RESOURCE_REQUIRED:'只能分享本企业负责且已获明确授权的资源。',
  CROSS_ENTERPRISE_FULL_VERSION_CONTRACT_REQUIRED:'跨企业开放完整冻结版本，需要绑定已确认的全版本合同及其当前修订。',
  CROSS_ENTERPRISE_CUSTODY_FORBIDDEN:'本企业数据保管范围不能跨企业授予，请选择受控视图或有明确合同的冻结版本。',
  CROSS_ENTERPRISE_CONSUMPTION_ACTIONS_AND_EXPIRY_REQUIRED:'跨企业分享需填写结束时间，并只选择已实现的消费及合同交付动作。',
  GRANT_REVIEW_NOT_AVAILABLE:'该预览不属于当前登录会话，请重新预览。',
  GRANT_REVIEW_EXPIRED:'预览已过期，请重新预览。',
  GRANT_REVIEW_STALE:'资源、成员或授权影响已变化，请重新预览。',
  GRANT_VERSION_OR_STATE_CHANGED:'授权版本或状态已变化，请刷新后重新预览。',
  CONFIRMED_GRANTEE_ORGANIZATION_REQUIRED:'接收组织需要处于有效、已确认的企业映射状态。',
  GRANTEE_MEMBER_NOT_AVAILABLE:'未找到该组织内有效的目标成员，请核对成员 ID。',
  FROZEN_GRANT_RESOURCE_NOT_AVAILABLE:'该冻结版本或索引尚未就绪，或已不可用。',
  CONTROLLED_GRANT_VIEW_NOT_AVAILABLE:'该受控视图或授权版本已变化，请刷新范围。',
  VIEW_WITH_ORIGINAL_COPIES_REQUIRES_RAW_OPERATION:'该视图包含完整文件副本，导出还需要在同一条授权中明确选择原件下载。',
  COMPLETE_DELIVERY_EXPORT_ACTIONS_REQUIRED:'交付需要同一条授权覆盖所选交付动作及原件／视图导出动作。',
  DELIVERY_CONTRACT_REQUIRED:'交付动作需绑定明确合同。',
  EXACT_CONFIRMED_CONTRACT_NOT_AVAILABLE:'未找到与本次企业、冻结版本、索引和修订完全一致的已确认合同。',
  CONTRACT_ACTION_OR_PURPOSE_NOT_GRANTED:'合同没有授予本次完整动作和用途。',
  GRANT_OUTSIDE_CONTRACT_VALIDITY:'授权有效期必须完整处于所选合同有效期内。',
  GRANT_PURPOSE_FIELDS_CONFLICT:'请使用用途多选配置，不要同时提交旧版单用途字段。',
  UNIQUE_SUPPORTED_GRANT_PURPOSES_REQUIRED:'请至少选择一种用途，每种用途只能选择一次。',
  GRANT_REVIEW_REASON_REQUIRED:'请填写至少 6 字的授权依据与用途。'
 };
 let sequence=0,draft=0,memberSequence=0,listSequence=0,resourceSequence=0,resources=[],organizations=[],operations=[],review=null,resourcePage=1,grantPage=1;
 const allowed=()=>auth.authenticated&&(auth.has('identity.resource.manage')||auth.has('dataset.share.manage'));
 const context=()=>({sequence,revision:auth.revision,draft});
 const current=(c,checkDraft=false)=>c.sequence===sequence&&c.revision===auth.revision&&(!checkDraft||c.draft===draft)&&$('resourceGrantDialog').open;
 function status(text){$('resourceGrantStatus').textContent=text;}
 function report(error,c){if(error?.name==='AbortError'||(c&&!current(c)))return;const text=String(error?.message||error),code=Object.keys(errors).find(key=>text.includes(key));status(code?errors[code]:text);}
 function options(select,items,placeholder){select.replaceChildren(new Option(placeholder,''));for(const item of items)select.append(new Option(item.label,item.value));}
 function clearReview(){draft++;review=null;$('resourceGrantReview').hidden=true;$('resourceGrantReviewContent').replaceChildren();$('resourceGrantConfirmForm').reset();}
 function clear(){
  sequence++;memberSequence++;listSequence++;resourceSequence++;resources=[];organizations=[];operations=[];resourcePage=1;grantPage=1;clearReview();
  for(const id of ['resourceGrantResource','resourceGrantOrg','resourceGrantMember'])$(id).replaceChildren();
  $('resourceGrantOperations').replaceChildren();$('resourceGrantRows').replaceChildren();$('resourceGrantResourcePage').textContent='';$('resourceGrantListPage').textContent='';$('resourceGrantScopeNotice').textContent='';
  $('resourceGrantForm').reset();status('');
 }
 function close(){if($('resourceGrantDialog').open)$('resourceGrantDialog').close();clear();}
 function capability(){for(const id of ['resourceGrantsOpen','resourceGrantsOpenManagement'])if($(id))$(id).hidden=!allowed();if(!allowed())close();}
 function selected(){return resources.find((_,index)=>String(index)===$('resourceGrantResource').value);}
 function activeOrg(){return organizations.find(o=>o.orgId===$('resourceGrantOrg').value);}
 function purposes(){return [...$('resourceGrantPurposes').querySelectorAll('input:checked')].map(input=>input.value).sort();}
 function purposeKey(){return purposes().join(',');}
 function scopeName(r){return (r.datasetName||r.datasetId)+' · '+(scopeLabels[r.scopeKind]||r.scopeKind)+(r.versionName?' / '+r.versionName:'');}
 function showOperations(){
  const root=$('resourceGrantOperations');root.replaceChildren();
  for(const op of operations){const label=document.createElement('label'),input=document.createElement('input'),text=document.createElement('span');input.type='checkbox';input.value=op.code;input.onchange=clearReview;text.textContent=labels[op.code]||op.label||op.code;label.append(input,text);root.append(label);}
 }
 async function loadResources(c){
  const expected=++resourceSequence;resources=[];listSequence++;clearReview();
  $('resourceGrantResource').replaceChildren();$('resourceGrantRows').replaceChildren();
  for(const id of ['resourceGrantResourcePrevious','resourceGrantResourceNext','resourceGrantListPrevious','resourceGrantListNext'])$(id).disabled=true;
  const page=resourcePage,selectedPurpose=purposeKey();let value;
  if(!selectedPurpose){$('resourceGrantResourcePage').textContent='请至少选择一种授权用途。';return false;}
  try{value=await auth.api(base+'/resources?'+new URLSearchParams({purposeCodes:selectedPurpose,page:String(page),size:'25'}));}
  catch(error){if(!current(c)||expected!==resourceSequence||page!==resourcePage||selectedPurpose!==purposeKey())return false;throw error;}
  if(!current(c)||expected!==resourceSequence||page!==resourcePage||selectedPurpose!==purposeKey())return false;
  resources=value.items;options($('resourceGrantResource'),resources.map((r,index)=>({value:String(index),label:scopeName(r)})),'选择明确数据范围');
  $('resourceGrantResourcePage').textContent='第 '+page+' 页，共 '+value.total+' 个可配置范围';
  $('resourceGrantResourcePrevious').disabled=page<=1;$('resourceGrantResourceNext').disabled=page*25>=value.total;
  $('resourceGrantRows').replaceChildren();$('resourceGrantListPage').textContent='先选择资源范围。';clearReview();return true;
 }
 function reloadResources(){const c=context();void loadResources(c).catch(error=>report(error,c));}
 async function loadMembers(){
  const c=context(),expected=++memberSequence,org=activeOrg(),isUser=$('resourceGrantForm').elements.subjectKind.value==='USER';
  clearReview();options($('resourceGrantMember'),[],'选择有效成员');$('resourceGrantMemberId').value='';
  const directory=!!org&&(auth.has('identity.resource.manage')||(auth.has('identity.member.read')&&org.orgId===auth.identity?.orgId));
  $('resourceGrantMemberLabel').hidden=!isUser||!directory;$('resourceGrantMemberIdLabel').hidden=!isUser||directory;
  $('resourceGrantMember').required=isUser&&directory;$('resourceGrantMemberId').required=isUser&&!directory;
  if(!isUser||!org||!directory)return;
  try{
   const members=await auth.api(base+'/members?'+new URLSearchParams({orgId:org.orgId}));
   if(current(c)&&expected===memberSequence&&org.orgId===$('resourceGrantOrg').value)
    options($('resourceGrantMember'),members.map(m=>({value:m.userId,label:m.username})),'选择有效成员');
  }catch(error){if(expected===memberSequence)report(error,c);}
 }
 async function loadGrants(){
  const c=context(),expected=++listSequence,r=selected(),page=grantPage;clearReview();$('resourceGrantRows').replaceChildren();
  if(!r){$('resourceGrantListPage').textContent='先选择资源范围。';return;}
  try{
   const value=await auth.api(base+'?'+new URLSearchParams({datasetId:r.datasetId,page:String(page),size:'10'}));
   if(!current(c)||expected!==listSequence||selected()!==r||page!==grantPage)return;
   $('resourceGrantListPage').textContent='第 '+page+' 页，共 '+value.total+' 条可管理授权；列表包含该数据集的其他冻结范围。';
   $('resourceGrantListPrevious').disabled=page<=1;$('resourceGrantListNext').disabled=page*10>=value.total;
   for(const item of value.items){
    const d=item.definition,row=document.createElement('article');row.className='resource-grant-row';
    const title=document.createElement('b'),text=document.createElement('p'),detail=document.createElement('p'),button=document.createElement('button');
    title.textContent=item.grantId+' · '+(item.state==='ACTIVE'?(item.liveTimeWindow?(item.sourceConstraintsCurrent===false?(item.sourceCurrentOperations?.length?'原始授权动作受限':'原始授权约束已失效'):'授权期限内'):'尚未生效／已到期'):item.state==='EXPIRED'?'已到期':'已撤销');
    const org=organizations.find(o=>o.orgId===d.granteeOrgId);
    text.textContent=(org?.orgName||d.granteeOrgId)+' · '+(d.subjectKind==='ORG'?'组织全体成员':d.subjectId)+' · '+(scopeLabels[d.scopeKind]||d.scopeKind);
    detail.textContent=(d.operations||[]).map(code=>labels[code]||code).join('、')+'；用途：'+(item.purposeCodes||[d.purpose]).map(p=>purposeLabels[p]||p).join('、');
    detail.textContent+='；来源约束：'+(item.sourceConstraintsCurrent===true?'全部动作当前有效':item.sourceConstraintsCurrent===false?(item.sourceCurrentOperations?.length?'部分动作当前有效':'已失效'):'待核验');
    if(item.sourceCurrentOperations?.length)detail.textContent+='；来源支持：'+item.sourceCurrentOperations.map(code=>labels[code]||code).join('、');
    button.type='button';button.className='btn';button.textContent='预览撤销本条授权';button.disabled=item.state!=='ACTIVE';button.onclick=()=>void revoke(item);
    row.append(title,text,detail,button);$('resourceGrantRows').append(row);
   }
  }catch(error){if(expected===listSequence)report(error,c);}
 }
 function changeResource(){
  clearReview();grantPage=1;const r=selected();$('resourceGrantScopeNotice').textContent=!r?'':r.scopeKind==='DATASET_CUSTODY'?
   '本企业保管范围涵盖当前及后续版本。跨企业分享请另选冻结视图或有明确合同的冻结版本。':
   (r.hasOriginalByteCopies?'该视图含完整文件副本，导出还需明确原件下载权。':'本次授权固定到所选版本与索引。');
  void loadGrants();
 }
 async function open(){
  if(!allowed())return;clear();$('resourceGrantDialog').showModal();status('正在读取授权管理信息…');const c=context();
  try{
   const values=await Promise.all([auth.api(base+'/operations'),auth.api(base+'/organizations')]);
   if(!current(c))return;[operations,organizations]=values;showOperations();
   options($('resourceGrantOrg'),organizations.map(o=>({value:o.orgId,label:o.orgName+(o.enterpriseName?' · '+o.enterpriseName:'')})),'选择接收组织');
   if(organizations.some(o=>o.orgId===auth.identity?.orgId))$('resourceGrantOrg').value=auth.identity.orgId;
   await Promise.all([loadResources(c),loadMembers()]);if(current(c))status('请明确选择目标、数据范围、动作和有效期，再预览。');
  }catch(error){report(error,c);}
 }
 function line(root,label,value){const row=document.createElement('div'),name=document.createElement('b'),text=document.createElement('span');name.textContent=label;text.textContent=String(value??'未设置');row.append(name,text);root.append(row);}
 function showReview(result){
  const p=result.preview,d=p.definition,root=$('resourceGrantReviewContent'),creating=p.action==='CREATE';root.replaceChildren();
  const expectedText=creating?p.grantee.orgName:p.grantId;
  review={id:result.reviewId,grantId:p.grantId,action:p.action,confirmation:expectedText,revision:auth.revision,draft};
  line(root,'操作',creating?'创建授权':'撤销本条授权');line(root,'授权 ID',p.grantId);
  line(root,'接收主体',creating?p.grantee.orgName+' · '+(d.subjectKind==='ORG'?'组织全体成员（当前 '+p.grantee.affectedMemberCount+' 人，后续有效成员也适用）':d.subjectId):d.granteeOrgId+' · '+d.subjectId);
  line(root,'资源范围',(p.resource?.datasetName||d.datasetId)+' · '+(scopeLabels[d.scopeKind]||d.scopeKind));
  if(d.versionId)line(root,'冻结版本 / 索引',d.versionId+' / '+d.indexVersionId);
  line(root,'业务动作',(d.operations||[]).map(code=>labels[code]||code).join('、'));
  line(root,'用途',(p.purposeCodes||d.purposeCodes||[d.purpose]).map(v=>purposeLabels[v]||v).join('、'));
  if(creating)line(root,'用途与动作','每一种所选用途均覆盖上述全部业务动作，由同一条完整授权承载。');
  line(root,'有效期',(d.validFrom?new Date(d.validFrom).toLocaleString():'立即')+' 至 '+(d.validTo?new Date(d.validTo).toLocaleString():'直到撤销'));
  if(d.contractId)line(root,'合同',d.contractId+' · 修订 '+d.contractRevision);
  if(!creating)line(root,'其他来源支持的授权',p.otherActiveGrantCount+' 条期限内且至少一项来源动作有效的授权；具体访问仍须核验账号能力、动作、范围和合同。本次撤销这一条，引用它的转授也受来源约束影响。');
  if(creating&&p.sourceAuthority?.kind==='ORIGINAL_PROVIDER_GRANT')line(root,'来源约束','同一份完整原始授权及转授人的操作能力须持续有效；另发授权不会自动续接。');
  if(!creating&&p.sourceImpact){
   line(root,'下游转授引用',p.sourceImpact.dependentGrantRecordCount+' 条当前代际链中的授权记录，涉及 '+p.sourceImpact.dependentSubjectCount+' 个接收主体');
   line(root,'引用来源的通用任务',p.sourceImpact.genericActiveTaskCount+' 项正在排队或运行');
   for(const task of p.sourceImpact.visibleGenericTasks||[])line(root,'通用任务',task.jobId+' / '+task.runId+' · '+({QUEUED:'排队',RUNNING:'运行中'}[task.status]||'状态待核验'));
  }
  if(p.sourceAuthority)line(root,'配置依据',p.sourceAuthority.kind==='ORIGINAL_PROVIDER_GRANT'?'现有分享授权 '+p.sourceAuthority.grantId+' · 版本 '+p.sourceAuthority.version:'平台授权配置管理');
  line(root,'依据',p.reason);line(root,'预览有效至',new Date(result.expiresAt).toLocaleString());
  $('resourceGrantConfirmationLabel').textContent=creating?'确认请输入接收组织名称：'+expectedText:'确认请输入将撤销的授权 ID：'+expectedText;
  $('resourceGrantConfirmForm').reset();$('resourceGrantReview').hidden=false;status('请核对下方变更。授权不会自动给账号分配业务操作。');
  $('resourceGrantReview').scrollIntoView({block:'start'});$('resourceGrantConfirmForm').elements.confirmation.focus({preventScroll:true});
 }
 function timestamp(input){if(!input.value)return null;const date=new Date(input.value);if(!Number.isFinite(date.getTime()))throw Error('请填写有效时间。');return date.toISOString();}
 async function preview(event){
  event.preventDefault();clearReview();const c=context(),r=selected(),o=activeOrg(),form=event.currentTarget;
  try{
   if(!r||!o)throw Error('请先选择数据范围和接收组织。');
   const kind=form.elements.subjectKind.value,member=!$('resourceGrantMemberLabel').hidden?$('resourceGrantMember').value:$('resourceGrantMemberId').value.trim();
   const body={subjectKind:kind,subjectId:kind==='ORG'?o.orgId:member,granteeOrgId:o.orgId,scopeKind:r.scopeKind,datasetId:r.datasetId,
    versionId:r.versionId||null,indexVersionId:r.indexVersionId||null,viewId:r.viewId||null,operations:[...$('resourceGrantOperations').querySelectorAll('input:checked')].map(input=>input.value),
    purposeCodes:purposes(),validFrom:timestamp(form.elements.validFrom),validTo:timestamp(form.elements.validTo),contractId:form.elements.contractId.value.trim()||null,
    contractRevision:form.elements.contractId.value.trim()?Number(form.elements.contractRevision.value):null,reason:form.elements.reason.value};
   if(!body.subjectId)throw Error('请选择成员或填写明确的成员 ID。');
   if(!body.operations.length)throw Error('请逐项选择业务动作。');
   if(!body.purposeCodes.length)throw Error('请至少选择一种授权用途。');
   const result=await auth.api(base+'/preview',{method:'POST',json:body});if(current(c,true))showReview(result);
  }catch(error){if(current(c,true))report(error,c);}
 }
 async function revoke(item){
  clearReview();const c=context();
  try{
   const reason=$('resourceGrantForm').elements.reason.value;if(reason.trim().length<6)throw Error('请先在授权依据栏填写至少 6 字的撤销原因。');
   const result=await auth.api(base+'/'+item.grantId+'/revoke-preview',{method:'POST',json:{expectedVersion:item.version,reason}});
   if(current(c,true))showReview(result);
  }catch(error){if(current(c,true))report(error,c);}
 }
 async function confirm(event){
  event.preventDefault();const c=context(),pending=review,form=event.currentTarget;
  try{
   if(!pending||pending.revision!==auth.revision||pending.draft!==draft)throw Error('请重新预览当前变更。');
   if(form.elements.confirmation.value!==pending.confirmation||!form.elements.reviewed.checked)throw Error('请填写确认内容并勾选已核对。');
   await auth.api(base+'/reviews/'+pending.id+'/confirm',{method:'POST',json:{confirm:true}});
   clearReview();await auth.policyChanged();if(typeof toast==='function')toast(pending.action==='CREATE'?'授权策略已创建；访问仍按业务操作和当前范围校验。':'本条授权已撤销；其他授权或合同可能仍有效。');
  }catch(error){report(error,c);}
 }
 document.addEventListener('DOMContentLoaded',()=>{
  for(const id of ['resourceGrantsOpen','resourceGrantsOpenManagement'])if($(id))$(id).onclick=()=>void open();
  $('resourceGrantClose').onclick=close;$('resourceGrantDialog').addEventListener('close',clear);
  $('resourceGrantDialog').addEventListener('cancel',event=>{event.preventDefault();close();});
  $('resourceGrantForm').onsubmit=event=>void preview(event);$('resourceGrantConfirmForm').onsubmit=event=>void confirm(event);
  $('resourceGrantResource').onchange=changeResource;$('resourceGrantOrg').onchange=()=>void loadMembers();$('resourceGrantForm').elements.subjectKind.onchange=()=>void loadMembers();
  $('resourceGrantForm').addEventListener('input',event=>{if(event.target!==$('resourceGrantResource')&&event.target!==$('resourceGrantOrg'))clearReview();});
  for(const input of $('resourceGrantPurposes').querySelectorAll('input'))input.onchange=()=>{clearReview();resourcePage=1;reloadResources();};
  $('resourceGrantResourcePrevious').onclick=()=>{if(resourcePage>1){resourcePage--;clearReview();reloadResources();}};
  $('resourceGrantResourceNext').onclick=()=>{resourcePage++;clearReview();reloadResources();};
  $('resourceGrantListPrevious').onclick=()=>{if(grantPage>1){grantPage--;void loadGrants();}};
  $('resourceGrantListNext').onclick=()=>{grantPage++;void loadGrants();};
  window.addEventListener('dataspace-identity',()=>{close();capability();});capability();
 });
})();
