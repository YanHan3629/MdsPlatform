(() => {
  'use strict';
  const auth=window.mdsIdentity;
  const $=id=>document.getElementById(id);
  const industries={MANUFACTURER:'制造',SUPPLIER:'供应',WAREHOUSE:'仓储',CARRIER:'承运',CUSTOMER:'客户／渠道',SERVICE:'安装／售后'};
  const states={CONFIRMED:'已确认',PENDING:'待确认',DISABLED:'已停用'};
  const errors={
    OPERATION_DENIED:'当前账号没有该操作权限。导入名录还需要数据保管岗位及来源原件读取授权。',
    RESOURCE_SCOPE_NOT_AVAILABLE:'当前账号没有该来源版本的完整原件读取授权。',
    CATALOG_SOURCE_AUTHORITY_CHANGED:'来源授权已变化，请重新核对后再导入。',
    CATALOG_SOURCE_IDENTITY_CHANGED:'实际文件与冻结版本记录不一致，请保留现场并核查来源。',
    CATALOG_SOURCE_INTEGRITY_NOT_READY:'来源文件尚无完整的大小与摘要记录，请先完成完整性校验。',
    ENTERPRISE_CATALOG_SCHEMA_REQUIRED:'名录需要 record_type、company_id、name、role 字段。',
    ENTERPRISE_CATALOG_ORPHAN_NODE:'名录中存在无法关联到企业的节点，请修复新版本后重试。',
    ENTERPRISE_CATALOG_DUPLICATE_COMPANY:'名录中的企业 ID 重复，请核查来源。',
    ENTERPRISE_CATALOG_UNRECOGNIZED_INDUSTRY:'企业行业标签不在已实现的六类对应规则内，请先核查名录。',
    ENTERPRISE_MAPPING_VERSION_CHANGED:'组织或映射已变化，请刷新后重新预览。',
    ENTERPRISE_MAPPING_REVIEW_STALE:'预览之后影响范围已变化，请重新预览。',
    ENTERPRISE_MAPPING_REVIEW_EXPIRED:'预览已过期，请重新预览。',
    ENTERPRISE_MAPPING_REVIEW_NOT_AVAILABLE:'该预览不属于当前登录会话，请重新预览。',
    ENTERPRISE_REBIND_REQUIRES_RESOURCE_MIGRATION:'该组织已有数据、合同或授权历史。请先制定迁移方案，或新建业务组织；本次不会直接改绑。',
    CATALOG_CUSTODIAN_ORG_MUST_REMAIN_SEPARATE:'多企业名录的保管组织需保持独立。请新建业务组织后再绑定具体企业。',
    BUSINESS_ENTERPRISE_ALREADY_MAPPED:'该业务实体已有已确认的业务组织。受控测试请选择“受控测试映射”。',
    CONFIRMED_ENTERPRISE_MAPPING_REQUIRED:'当前没有可停用的已确认映射。',
    ENTERPRISE_ORGANIZATION_DISABLED:'该组织已停用，请先通过组织管理恢复。',
    ENTERPRISE_CONFIRMATION_REASON_REQUIRED:'请填写至少 6 字的确认依据与用途。'
  };
  let organizations=[],catalogs=[],sources=[],review=null,sequence=0,reviewSequence=0;
  const status=text=>{$('enterpriseMappingStatus').textContent=text;};
  function report(error){
    if(error?.name==='AbortError'||!$('enterpriseMappingDialog').open)return;
    const text=String(error?.message||error||'操作失败。'),key=Object.keys(errors).find(code=>text.includes(code));
    status(key?errors[key]:text);
  }
  function options(select,items,placeholder){
    select.replaceChildren(new Option(placeholder,''));
    for(const item of items){const option=new Option(item.label,item.value);option.disabled=!!item.disabled;select.append(option);}
  }
  function clearReview(){reviewSequence++;review=null;$('enterpriseMappingReview').hidden=true;$('enterpriseMappingReviewContent').replaceChildren();$('enterpriseMappingConfirmForm').reset();}
  function clear(){
    sequence++;organizations=[];catalogs=[];sources=[];clearReview();
    for(const id of ['enterpriseCatalogSource','enterpriseMappingOrg','enterpriseMappingCatalog','enterpriseMappingEntity'])$(id).replaceChildren();
    $('enterpriseMappingCurrent').textContent='';$('enterpriseCatalogImportResult').textContent='';status('');
    $('enterpriseMappingForm').reset();$('enterpriseOrganizationForm').reset();$('enterpriseCatalogImportForm').reset();
  }
  function close(){if($('enterpriseMappingDialog').open)$('enterpriseMappingDialog').close();clear();}
  function capability(){
    const allowed=auth.authenticated&&auth.has('identity.enterprise.manage');
    for(const id of ['enterpriseMappingOpen','enterpriseMappingOpenManagement'])if($(id))$(id).hidden=!allowed;
    if(!allowed)close();
  }
  function selectedOrg(){return organizations.find(org=>org.orgId===$('enterpriseMappingOrg').value);}
  function selectOrganization(){
    clearReview();const org=selectedOrg();
    $('enterpriseMappingCurrent').textContent=org?
      (org.catalogCustodian?'独立名录保管组织；不绑定单一企业。':org.entityId?
       '当前映射：'+org.enterpriseName+' · '+org.entityId+' · '+(states[org.mappingState]||org.mappingState)+'；'+org.memberCount+' 名成员':
       '尚未确认业务实体；'+org.memberCount+' 名成员'):'请选择组织。';
    $('enterpriseMappingDisable').disabled=!org||org.mappingState!=='CONFIRMED'||org.status!==1;
    if(org?.catalogRevisionId&&catalogs.some(c=>c.catalogRevisionId===org.catalogRevisionId))$('enterpriseMappingCatalog').value=org.catalogRevisionId;
    selectCatalog();
    if(org?.entityId&&[...$('enterpriseMappingEntity').options].some(o=>o.value===org.entityId))$('enterpriseMappingEntity').value=org.entityId;
    $('enterpriseMappingKind').value=org?.identityKind||'BUSINESS';
  }
  function selectCatalog(){
    clearReview();const catalog=catalogs.find(item=>item.catalogRevisionId===$('enterpriseMappingCatalog').value);
    options($('enterpriseMappingEntity'),(catalog?.entries||[]).map(e=>({value:e.entityId,label:e.name+' · '+e.entityId+' · '+(industries[e.industryKind]||e.industryKind)})),'选择业务实体');
  }
  async function load(preferredOrg='',preferredCatalog=''){
    if(!auth.has('identity.enterprise.manage'))return false;
    const expected=++sequence,revision=auth.revision;
    let values;
    try{values=await Promise.all([auth.api('/identity/enterprises'),auth.api('/identity/enterprises/catalogs'),auth.api('/identity/enterprises/catalog-sources')]);}
    catch(error){if(expected!==sequence||revision!==auth.revision||!$('enterpriseMappingDialog').open)return false;throw error;}
    if(expected!==sequence||revision!==auth.revision||!$('enterpriseMappingDialog').open)return false;
    [organizations,catalogs,sources]=values;clearReview();
    options($('enterpriseMappingOrg'),organizations.map(o=>({value:o.orgId,label:o.orgName+(o.status!==1?'（组织已停用）':'')})),'选择登录组织');
    options($('enterpriseMappingCatalog'),catalogs.map(c=>({value:c.catalogRevisionId,label:c.datasetName+' / '+c.versionName+' · '+c.companyCount+' 企业、'+c.nodeCount+' 节点'})),'选择已校验名录');
    options($('enterpriseCatalogSource'),sources.map(s=>({value:s.datasetId+':'+s.versionId,label:s.datasetName+' / '+s.versionName+(s.canImport?'':'（无原件读取授权）'),disabled:!s.canImport})),'选择可读取的已发布来源');
    $('enterpriseCatalogImport').disabled=!sources.some(s=>s.canImport);
    if(preferredOrg&&organizations.some(o=>o.orgId===preferredOrg))$('enterpriseMappingOrg').value=preferredOrg;
    if(preferredCatalog&&catalogs.some(c=>c.catalogRevisionId===preferredCatalog))$('enterpriseMappingCatalog').value=preferredCatalog;
    else if(catalogs.length)$('enterpriseMappingCatalog').value=catalogs[0].catalogRevisionId;
    selectOrganization();
    if(preferredCatalog&&catalogs.some(c=>c.catalogRevisionId===preferredCatalog)){
      $('enterpriseMappingCatalog').value=preferredCatalog;selectCatalog();
      const org=selectedOrg();if(org?.entityId&&[...$('enterpriseMappingEntity').options].some(o=>o.value===org.entityId))$('enterpriseMappingEntity').value=org.entityId;
    }
    status('请先核对组织与名录，再预览映射。');return true;
  }
  async function open(){
    if(!auth.has('identity.enterprise.manage'))return;
    clear();$('enterpriseMappingDialog').showModal();status('正在读取管理信息…');
    try{await load();}catch(error){report(error);}
  }
  function line(parent,label,value){
    const row=document.createElement('div'),name=document.createElement('b'),text=document.createElement('span');
    name.textContent=label;text.textContent=value;row.append(name,text);parent.append(row);
  }
  function showReview(result,org){
    review={reviewId:result.reviewId,orgId:org.orgId,orgName:org.orgName,revision:auth.revision};
    const p=result.preview,container=$('enterpriseMappingReviewContent');container.replaceChildren();
    line(container,'组织',p.orgName);
    line(container,'原映射',p.before?(p.before.enterpriseName+' · '+p.before.entityId+' · '+(states[p.before.state]||p.before.state)):'无');
    line(container,'新映射',p.after.enterpriseName+' · '+p.after.entityId+' · '+(industries[p.after.industryKind]||p.after.industryKind)+' · '+(states[p.after.state]||p.after.state));
    line(container,'用途',p.after.identityKind==='TEST'?'受控测试映射':'业务组织映射');
    line(container,'影响范围',p.impact.memberCount+' 名成员；'+p.impact.datasetCount+' 个数据集；'+p.impact.contractCount+' 份合同；'+p.impact.grantCount+' 条授权记录');
    line(container,'确认依据',p.reason);line(container,'预览有效至',new Date(result.expiresAt).toLocaleString());
    $('enterpriseMappingConfirmForm').reset();$('enterpriseMappingReview').hidden=false;status('请核对下方变更内容后确认。');
  }
  document.addEventListener('DOMContentLoaded',()=>{
    for(const id of ['enterpriseMappingOpen','enterpriseMappingOpenManagement'])if($(id))$(id).onclick=()=>void open();
    $('enterpriseMappingClose').onclick=close;
    $('enterpriseMappingDialog').addEventListener('cancel',event=>{event.preventDefault();close();});
    $('enterpriseMappingDialog').addEventListener('close',clear);
    $('enterpriseMappingOrg').onchange=selectOrganization;$('enterpriseMappingCatalog').onchange=selectCatalog;
    $('enterpriseMappingEntity').onchange=clearReview;$('enterpriseMappingKind').onchange=clearReview;
    $('enterpriseMappingForm').elements.reason.oninput=clearReview;
    $('enterpriseCatalogImportForm').onsubmit=event=>{event.preventDefault();const form=event.currentTarget,context=sequence;void(async()=>{
      const revision=auth.revision,parts=$('enterpriseCatalogSource').value.split(':');if(parts.length!==2)throw new Error('请选择有原件读取授权的来源版本。');
      status('正在核验冻结文件并导入名录…');
      const value=await auth.api('/identity/enterprises/catalogs/import',{method:'POST',json:{datasetId:parts[0],versionId:parts[1],path:form.elements.path.value}});
      if(revision!==auth.revision||context!==sequence||!$('enterpriseMappingDialog').open)return;
      const diff=value.difference||{},summary=(value.alreadyImported?'该名录已存在。':'名录已校验并导入。')+
       value.companyCount+' 家企业、'+value.nodeCount+' 个节点；与此前名录相比新增 '+(diff.addedEntityIds||[]).length+'、移除 '+(diff.removedEntityIds||[]).length+'、变化 '+(diff.changedEntityIds||[]).length+'。既有映射未自动修改。';
      if(!await load($('enterpriseMappingOrg').value,value.catalogRevisionId))return;
      $('enterpriseCatalogImportResult').textContent=summary;status(summary);
    })().catch(report);};
    $('enterpriseOrganizationForm').onsubmit=event=>{event.preventDefault();const form=event.currentTarget,context=sequence;void(async()=>{
      const revision=auth.revision,value=await auth.api('/orgs',{method:'POST',json:{orgName:form.elements.orgName.value.trim(),orgStatus:1,licenseType:form.elements.licenseType.value}});
      if(revision!==auth.revision)return;form.reset();if(!await load(value.orgId,$('enterpriseMappingCatalog').value))return;status('组织已创建，请选择经过校验的业务实体。');
    })().catch(report);};
    $('enterpriseMappingForm').onsubmit=event=>{event.preventDefault();const form=event.currentTarget;void(async()=>{
      const org=selectedOrg();if(!org)throw new Error('请选择组织。');
      const revision=auth.revision,pendingSequence=++reviewSequence,result=await auth.api('/identity/enterprises/'+org.orgId+'/mapping/preview',{method:'POST',json:{
        catalogRevisionId:$('enterpriseMappingCatalog').value,entityId:$('enterpriseMappingEntity').value,identityKind:$('enterpriseMappingKind').value,
        expectedOrgVersion:org.orgVersion,expectedMappingVersion:org.mappingVersion,reason:form.elements.reason.value}});
      if(revision===auth.revision&&pendingSequence===reviewSequence&&$('enterpriseMappingDialog').open)showReview(result,org);
    })().catch(report);};
    $('enterpriseMappingDisable').onclick=()=>void(async()=>{
      const org=selectedOrg();if(!org)throw new Error('请选择组织。');
      const revision=auth.revision,pendingSequence=++reviewSequence,result=await auth.api('/identity/enterprises/'+org.orgId+'/mapping/disable-preview',{method:'POST',json:{
        expectedOrgVersion:org.orgVersion,expectedMappingVersion:org.mappingVersion,reason:$('enterpriseMappingForm').elements.reason.value}});
      if(revision===auth.revision&&pendingSequence===reviewSequence&&$('enterpriseMappingDialog').open)showReview(result,org);
    })().catch(report);
    $('enterpriseMappingConfirmForm').onsubmit=event=>{event.preventDefault();const form=event.currentTarget;void(async()=>{
      const pending=review;if(!pending||pending.revision!==auth.revision)throw new Error('请重新预览当前变更。');
      if(form.elements.organizationConfirmation.value!==pending.orgName||!form.elements.reviewed.checked)throw new Error('请输入目标组织名称并确认已核对。');
      await auth.api('/identity/enterprises/'+pending.orgId+'/mapping/confirm',{method:'POST',json:{reviewId:pending.reviewId,confirm:true}});
      clearReview();await auth.policyChanged();
      if(typeof toast==='function')toast('映射已更新；受影响成员需重新登录。');
    })().catch(report);};
    window.addEventListener('dataspace-identity',()=>{close();capability();});
    capability();
  });
})();
