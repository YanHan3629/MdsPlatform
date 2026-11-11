(() => {
  'use strict';
  const auth=window.mdsIdentity;
  let users=[],types=[],orgs=[],catalog=[],selectedUser='',selectedType='',loadSequence=0;
  let auditSequence=0,auditCursor=null,auditTarget='';
  const states={PENDING:'待激活',ACTIVE:'启用',DISABLED:'停用',CLOSED:'已注销'};
  const industries={MANUFACTURER:'制造',SUPPLIER:'供应',WAREHOUSE:'仓储',CARRIER:'承运',CUSTOMER:'客户／渠道',SERVICE:'安装／售后'};
  const operationLabels={
    'identity.resource.manage':'管理明确资源授权','dataset.share.manage':'配置本方资源分享','data.catalog.read':'查看授权目录','data.preview':'预览授权内容','data.search':'专业检索','data.qa':'证据问答','data.raw.download':'下载完整原件','data.view.export':'导出授权视图',
    'dataset.create':'创建数据集','dataset.upload':'上传与完成上传','dataset.draft.manage':'维护草稿','dataset.publish':'发布原始版本','dataset.quality.run':'发起质量评估','dataset.quality.read':'查看质量详情','dataset.index.build':'构建索引','dataset.index.activate':'切换索引',
    'product.publish':'发布数据产品','product.unpublish':'下架数据产品','product.summary.manage':'管理公开摘要','application.submit':'提交消费申请','application.review':'审核本方申请',
    'contract.read':'查看本方合约','contract.edit':'拟定与修订合约','contract.confirm':'代表企业确认合约','contract.revoke':'撤销合约授权',
    'delivery.endpoint.manage':'管理接收端','delivery.execute':'触发与重试交付','delivery.receipt.read':'查看交付回执','delivery.package.download':'下载授权交付包',
    'job.read':'查看本范围任务','job.log.read':'查看本范围日志','job.cancel':'取消本范围任务',
    'identity.self.read':'查看自己的身份','identity.self.password':'修改自己的密码','identity.self.logout':'退出此账号的全部会话','identity.self.close':'注销自己',
    'identity.member.read':'查看管理范围成员','identity.member.create':'创建普通成员','identity.member.update':'修改普通成员','identity.member.disable':'停用与恢复成员','identity.member.reset':'重置成员密码','identity.member.close':'注销成员','identity.member.type.assign':'分配被委托的业务类型',
    'identity.type.manage':'管理业务类型','identity.platform-admin.manage':'管理平台管理员资格','identity.enterprise.manage':'管理企业身份映射','identity.audit.read':'查看授权审计','platform.runtime.manage':'平台运行维护'
  };
  const errorLabels={
    IDENTITY_REVIEW_REQUIRED:'请先审阅本次变更影响，再确认提交。',
    IDENTITY_REVIEW_STALE:'账号、权限、会话或任务状态已变化，请重新审阅。',
    IDENTITY_REVIEW_EXPIRED:'本次审阅已过期，请重新审阅。',
    IDENTITY_REVIEW_CLOSED:'本次审阅已提交或取消，请重新审阅。',
    IDENTITY_REVIEW_REQUEST_CHANGED:'提交内容与审阅内容不一致，请重新审阅。',
    IDENTITY_REVIEW_HAS_BLOCKERS:'当前变更存在冲突，请先解决列出的阻断项。',
    IDENTITY_REVIEW_POSTCONDITION_FAILED:'本次变更核验未通过，已回滚，请重新检查配置。',
    IDENTITY_REVIEW_POLICY_MISMATCH:'权限计算无法核对，本次变更已停止，请联系维护人员。',
    TYPE_IN_USE_REPLACEMENT_REQUIRED:'此类型仍被成员或管理者委托使用，请选择有效替代类型。',
    READ_ONLY_CONFLICTING_ADDITIONAL_TYPE:'只读主类型与写入或导出岗位冲突，请先移除冲突岗位。',
    ACTIVATE_AND_LOGIN_BEFORE_ADMIN_PROMOTION:'接任账号需先激活并完成一次真实登录。',
    OPERATION_DENIED:'当前账号没有该操作权限。',
    MEMBER_SCOPE_NOT_AVAILABLE:'目标成员不在当前管理范围。',
    TYPE_NOT_DELEGATED:'该业务类型不在允许分配的范围内。',
    TYPE_NOT_APPLICABLE_TO_ENTERPRISE:'该类型不适用于所选企业，请核对企业映射、产业链标签和组织范围。',
    READ_ONLY_TYPE_CONTAINS_WRITE_OR_EXPORT:'只读配置不能包含写入、下载或导出操作。',
    READ_ONLY_PROFILE_CONFLICT:'只读主配置不能与写入或导出岗位并用。',
    IDENTITY_VERSION_CONFLICT:'该信息已被其他操作修改，请刷新后重试。',
    EXPECTED_VERSION_REQUIRED:'请刷新目标信息后重试。',
    CLOSED_IDENTITY_IS_TERMINAL:'已注销账号不能恢复。',
    RESERVED_OR_INVALID_TYPE_CODE:'业务代码需为大写字母、数字和下划线；不能使用管理员保留代码或 ROLE 前缀。',
    ADMIN_INITIALIZATION_REQUIRED:'该实例尚未初始化管理员，请由部署维护人员完成初始化。',
    AUTHORIZATION_MAINTENANCE_MODE:'实例正在维护，请稍后再试。',
    TYPE_STILL_ASSIGNED:'该类型仍有成员，请选择有效替代类型后再删除。',
    AUDIT_QUERY_INVALID:'请核对审计查询的目标 ID 和分页参数。'
  };
  function message(error){
    const value=String(error?.message||error||'操作失败。');
    const key=Object.keys(errorLabels).find(code=>value.includes(code));
    if(key)return errorLabels[key];
    if(/LAST.*ADMIN|LAST_USABLE|最后.*管理员/.test(value))return '至少保留一名可登录的平台管理员；请先新增并验证接任管理员。';
    return value;
  }
  const report=error=>{if(error?.name!=='AbortError')toast(message(error));};
  const values=select=>[...select.selectedOptions].map(option=>option.value).filter(Boolean);
  function setValues(select,selected){for(const option of select.options)option.selected=selected.includes(option.value);}
  const activeType=id=>types.find(type=>type.typeId===id);
  const currentUser=()=>users.find(user=>user.userId===selectedUser);
  const currentType=()=>types.find(type=>type.typeId===selectedType);
  const me=()=>auth.identity;
  const orgName=id=>orgs.find(org=>org.orgId===id)?.orgName||id||'未指定';
  const businessCatalog=()=>catalog.filter(op=>!['identity','platform'].includes(op.group));
  function readonlyOperation(code){
    const operation=catalog.find(op=>op.code===code);
    if(operation)return !operation.mutation&&!['data.raw.download','data.view.export','delivery.package.download'].includes(code);
    return ['data.catalog.read','data.preview','data.search','data.qa','dataset.quality.read','contract.read','delivery.receipt.read','job.read','job.log.read'].includes(code);
  }
  function matchingTypes(orgId){
    const org=orgs.find(item=>item.orgId===orgId);
    return types.filter(type=>type.state==='ACTIVE'&&(type.scope==='GLOBAL'||type.orgId===orgId)&&
      (!(type.applicableOrgIds||[]).length||type.applicableOrgIds.includes(orgId))&&
      (!(type.industryTags||[]).length||org?.mappingState==='CONFIRMED'&&type.industryTags.includes(org.industryKind)));
  }
  function fillUserTypes(primary='',additional=[]){
    const available=matchingTypes($('#identityUserOrg').value);
    $('#identityPrimaryType').innerHTML=option('','仅账号自助（不配置业务主类型）')+available.map(type=>option(type.typeId,type.name+' · '+type.code)).join('');
    $('#identityAdditionalTypes').innerHTML=available.map(type=>option(type.typeId,type.name+' · '+type.code)).join('');
    $('#identityPrimaryType').value=available.some(type=>type.typeId===primary)?primary:'';
    setValues($('#identityAdditionalTypes'),additional.filter(id=>id!==primary));
    $('#identityDelegatedTypes').innerHTML=available.map(type=>option(type.typeId,type.name+' · '+type.code)).join('');
  }
  function selectUser(id){
    selectedUser=users.some(user=>user.userId===id)?id:'';
    const user=currentUser(),form=$('#identityUserForm');form.reset();
    $('#identityUserSelect').value=selectedUser;
    form.elements.username.value=user?.username||'';
    form.elements.orgId.value=user?.orgId||me()?.orgId||orgs[0]?.orgId||'';
    const primary=(user?.types||[]).find(type=>type.primary)?.typeId||'';
    fillUserTypes(primary,(user?.types||[]).filter(type=>!type.primary).map(type=>type.typeId));
    $('#identityTemporaryPassword').hidden=!!user;
    form.elements.temporaryPassword.required=!user;
    $('#identityUserState').textContent=user?
      user.accountState==='CLOSED'?'已注销 · 历史记录保留；账号不能再登录。':states[user.accountState]+' · '+(user.online?'在线':'离线')+' · '+(user.activationRequired?'须重新激活':'已设置有效凭据'):'新账号首次登录后需设置新密码。';
    $('#identityToggleState').textContent=user?.accountState==='DISABLED'?'恢复账号':'停用账号';
    $('#identityResetForm').reset();$('#identityDeleteForm').reset();
    const member=(user?.qualifications||[]).find(item=>item.qualification==='ENTERPRISE_MANAGER');
    const platform=(user?.qualifications||[]).find(item=>item.qualification==='PLATFORM_ADMIN');
    $('#identityPlatformForm').elements.active.value=platform?.active?'true':'false';
    $('#identityMemberManagerForm').elements.active.value=member?.active?'true':'false';
    setValues($('#identityDelegatedTypes'),member?.delegatedTypeIds||[]);
    applyCapabilities();
  }
  function selectType(id){
    selectedType=types.some(type=>type.typeId===id)?id:'';
    const type=currentType(),form=$('#identityTypeForm');form.reset();
    $('#identityTypeSelect').value=selectedType;
    for(const key of ['code','name','description'])form.elements[key].value=type?.[key]||'';
    form.elements.orgId.value=type?.orgId||me()?.orgId||orgs[0]?.orgId||'';
    form.elements.scope.value=type?.scope||'GLOBAL';form.elements.state.value=type?.state||'ACTIVE';
    form.elements.readOnly.value=type?.readOnly?'true':'false';
    setValues(form.elements.industryTags,type?.industryTags||[]);
    setValues($('#identityApplicableOrgs'),type?.applicableOrgIds||[]);
    for(const checkbox of $('#identityOperationPicker').querySelectorAll('input[type=checkbox]'))checkbox.checked=(type?.operations||[]).includes(checkbox.value);
    $('#identityTypeMembers').textContent=type?'当前管理范围内成员数：'+type.assignedCount+'；版本 '+type.version:'配置系统已实现的业务操作。管理资格单独授予。';
    $('#identityReplacementType').innerHTML=option('','无成员时可直接删除')+types.filter(item=>item.typeId!==selectedType&&item.state==='ACTIVE').map(item=>option(item.typeId,item.name+' · '+item.code)).join('');
    $('#identityDeleteTypeForm').reset();enforceReadonlyPicker();applyCapabilities();
  }
  function enforceReadonlyPicker(){
    const readonly=$('#identityTypeForm').elements.readOnly.value==='true';
    for(const input of $('#identityOperationPicker').querySelectorAll('input')){
      input.disabled=readonly&&!readonlyOperation(input.value);
      if(input.disabled)input.checked=false;
    }
  }
  function assignment(){
    const primary=$('#identityPrimaryType').value;
    const additional=values($('#identityAdditionalTypes')).filter(id=>id!==primary);
    if(!primary&&additional.length)throw new Error('有附加岗位时请明确选择业务主配置。');
    if(activeType(primary)?.readOnly&&additional.some(id=>(activeType(id)?.operations||[]).some(op=>!readonlyOperation(op))))
      throw new Error('只读主配置不能与写入或导出岗位并用，请先移除冲突岗位。');
    return {primaryTypeId:primary||null,typeIds:[...(primary?[primary]:[]),...additional]};
  }
  function expectedUser(){
    const user=currentUser();if(!user||user.accountState==='CLOSED')throw new Error('请先选择可管理的现有账号。');return user;
  }
  async function mutation(path,method,json,success){
    const impactPath=method==='PATCH'&&/^\/identity\/users\/[^/]+$/.test(path)?path+'/impact':
      path.endsWith('/qualification')?path+'-impact':path.endsWith('/types')&&method==='PUT'?path+'-impact':
      /^\/identity\/types\/[^/]+$/.test(path)&&method==='PUT'?path+'/impact':path.endsWith('/delete')?path.slice(0,-7)+'/delete-impact':null;
    if(impactPath)return reviewedMutation(path,method,json,success,impactPath);
    await auth.api(path,{method,json});toast(success);await auth.policyChanged();
  }
  let activeImpact=null,impactSequence=0;
  const identityStamp=()=>[auth.revision,me()?.userId,me()?.orgId,me()?.permissionVersion].join(':');
  function cancelImpact(){
    impactSequence++;
    const current=activeImpact;if(!current)return;activeImpact=null;current.resolve(false);
    if(current.dialog.open)current.dialog.close();current.dialog.remove();
    if(current.review&&current.stamp===identityStamp())void auth.api('/identity/changes/'+current.review.review_id+'/cancel',{method:'POST',json:{reviewSha256:current.review.review_sha256}}).catch(()=>{});
  }
  async function reviewedMutation(path,method,body,success,previewPath){
    cancelImpact();const stamp=identityStamp(),sequence=loadSequence,requestSequence=impactSequence,selection=[selectedUser,selectedType].join(':'),request=JSON.parse(JSON.stringify(body));
    const review=await auth.api(previewPath,{method:'POST',json:request});
    const current=()=>stamp===identityStamp()&&sequence===loadSequence&&requestSequence===impactSequence&&selection===[selectedUser,selectedType].join(':');
    if(!current())throw new DOMException('身份或管理列表已变化，请重新审阅。','AbortError');
    const dialog=document.createElement('dialog');dialog.className='identity-impact-dialog';dialog.style.cssText='max-width:900px;width:90vw;max-height:85vh;overflow:auto';
    const add=(parent,tag,text)=>{const el=document.createElement(tag);el.textContent=text;parent.append(el);return el;};
    add(dialog,'h3','确认账号与权限变化');
    add(dialog,'p','提交会撤销受影响账号的旧会话。操作能力仍需具体资源授权；此审阅不授予数据，也不承诺任务已经停止。');
    add(dialog,'p','审阅有效至 '+new Date(review.expiresAt).toLocaleString()+'；状态变化后需重新审阅。');
    if(review.impact.typeBefore){const b=review.impact.typeBefore,a=review.impact.typeAfter;add(dialog,'p','类型：'+b.role_name+'（'+b.role_code+'）→ '+a.role_name+'（'+a.role_code+'）；'+b.type_state+' → '+a.type_state);}
    const operationText=list=>(list||[]).map(code=>operationLabels[code]||code).join('、')||'无';
    const typeText=roles=>(roles||[]).map(r=>r.role_name+'（'+r.role_code+(r.primary?'，主类型':'')+'，'+r.type_state+'）').join('、')||'无';
    const qualificationText=list=>(list||[]).map(q=>(q.qualification==='PLATFORM_ADMIN'?'平台管理员':'企业成员管理者')+'：'+(q.active?'启用':'停用')+'；可分配 '+typeText(q.delegated)).join('；')||'无';
    for(const entry of review.impact.users||[]){
      const b=entry.before,a=entry.after,section=document.createElement('section');dialog.append(section);
      add(section,'h4',b.user_name+' · '+orgName(b.org_id));
      if(b.user_name!==a.user_name||b.org_id!==a.org_id)add(section,'p','账号／企业：'+b.user_name+'／'+orgName(b.org_id)+' → '+a.user_name+'／'+orgName(a.org_id));
      add(section,'p','业务类型：'+typeText(b.roles)+' → '+typeText(a.roles));
      add(section,'p','管理资格：'+qualificationText(b.qualifications)+' → '+qualificationText(a.qualifications));
      const before=new Set(b.effectiveOperations||[]),after=new Set(a.effectiveOperations||[]);
      add(section,'p','当前有效操作：'+operationText([...before]));add(section,'p','变更后有效操作：'+operationText([...after]));
      add(section,'p','新增：'+operationText([...after].filter(c=>!before.has(c)))+'；移除：'+operationText([...before].filter(c=>!after.has(c))));
      if(b.account_state!=='ACTIVE')add(section,'p','账号当前不可使用业务功能；配置操作：'+operationText(b.configuredOperations)+' → '+operationText(a.configuredOperations));
      add(section,'p','将撤销的有效会话：'+b.sessions+'；当前排队／运行任务：'+(b.tasks||[]).length);
      const taskNames={EXECUTION:'计算／索引执行',INDEX_QUEUE:'自动索引队列',QUALITY:'质量检查',QA_REQUEST:'问答请求',RENDER_REQUEST:'区域渲染请求'};
      const taskStates={QUEUED:'排队',RETRY:'等待重试',PROCESSING:'处理中',WAITING_INDEX:'等待索引完成',RUNNING:'运行中',UNCONFIRMED:'状态待核实'};
      for(const task of b.tasks||[])add(section,'p',(taskNames[task.taskKind]||'任务')+' '+(task.taskId||task.runId)+' · '+(taskStates[task.status]||task.status)+' · '+(['FROZEN_USER_AUTHORITY','FROZEN_USER_REQUEST'].includes(task.authority)?'依赖原账号授权':'历史来源待核验'));
      if(entry.delegatedResourceImpact){
        const d=entry.delegatedResourceImpact;
        add(section,'p','转授来源引用：'+d.referencedGrantRecordCount+' 条当前代际链的授权记录，涉及 '+d.referencedSubjectCount+' 个接收主体；引用这些来源的排队／运行通用任务 '+d.genericActiveTaskCount+' 项。');
        add(section,'p','转授人将失去的操作：'+operationText(d.removedIssuerOperations)+'；原企业的分享能力将'+(d.issuerShareCapabilityWillRemain?'保留':'不可使用')+'。');
        if(d.issuerOrgChanged)add(section,'p','所属企业变化，原企业签发的转授来源约束将失效。');
        add(section,'p','这是来源引用清单。已失效授权可能仍保留引用记录；每项访问和任务仍按实际所需操作核验，移除无关操作不表示全部任务都会停止。');
        for(const task of d.visibleGenericTasks||[])add(section,'p','下游通用任务 '+task.runId+' · '+({QUEUED:'排队',RUNNING:'运行中'}[task.status]||'状态待核验'));
        if(d.fileSourceImpact){
          const f=d.fileSourceImpact;
          add(section,'p','文件来源引用：计算任务 '+f.genericExecutionCount+' 条，原生索引执行 '+f.nativeIndexExecutionCount+' 条，其中读取索引文件的计算任务 '+f.indexedSourceBlobExecutionCount+' 条；生成文件 '+f.derivedFileRecordCount+' 条，输出快照 '+f.derivedSnapshotCount+' 个。');
          add(section,'p','相关排队／运行任务：计算 '+f.activeGenericTaskCount+' 项，原生索引 '+f.activeNativeIndexTaskCount+' 项。');
          for(const task of f.visibleGenericTasks||[])add(section,'p','文件来源相关计算任务 '+task.runId+' · '+({QUEUED:'排队',RUNNING:'运行中'}[task.status]||'状态待核验'));
          for(const task of f.visibleNativeIndexTasks||[])add(section,'p','文件来源相关索引任务 '+task.runId+' · '+({QUEUED:'排队',RUNNING:'运行中'}[task.status]||'状态待核验'));
          if(f.uncapturedExecutionCount||f.invalidCapturedSnapshotCount)add(section,'p','缺少冻结文件来源快照 '+f.uncapturedExecutionCount+' 条，来源快照结构异常 '+f.invalidCapturedSnapshotCount+' 条，不能据此确认完整文件影响范围。');
          if(f.unresolvedSourceExecutionCount||f.unrecordedNativeOriginCount)add(section,'p','部分来源快照或历史索引执行来源无法完整核对，相关数量包含保守估算，请在确认前复核。');
          add(section,'p','这些数量表示来源引用，不表示所有任务都会因本次变更而停止。质量、渲染、问答和交付任务尚未全部纳入本统计；实际访问与停止行为仍由服务端实时授权决定。');
        }
      }

    }
    if(review.impact.directTaskCoverage)add(dialog,'p','直接任务清单包含计算／索引执行、自动索引队列、质量检查及正在等待响应的问答和区域渲染请求。状态待核实表示未确认请求或下游工作器已结束；独立交付服务和这些下游工作仍需另行核对。');
    if(!(review.impact.users||[]).length)add(dialog,'p','当前没有直接成员或受委托管理者。');
    for(const blocker of review.impact.blockers||[])add(dialog,'p','无法提交：'+message(blocker));
    const controls=document.createElement('div');dialog.append(controls);const cancel=add(controls,'button','取消'),confirm=add(controls,'button','已核对，确认提交');cancel.type=confirm.type='button';confirm.disabled=!review.canApply;
    document.body.append(dialog);
    const approved=await new Promise(resolve=>{
      activeImpact={dialog,review,stamp,resolve};cancel.onclick=cancelImpact;
      dialog.addEventListener('cancel',event=>{event.preventDefault();cancelImpact();});
      confirm.onclick=()=>{
        if(!current()||Date.now()>=Date.parse(review.expiresAt)){cancelImpact();return;}
        confirm.disabled=true;activeImpact=null;dialog.close();dialog.remove();resolve(true);
      };dialog.showModal();
    });
    if(!approved)return;
    if(!current())throw new DOMException('身份已变化。','AbortError');
    await auth.api(path,{method,json:request,headers:{'X-Identity-Review-Id':review.review_id,'X-Identity-Review-Sha256':review.review_sha256,'X-Identity-Review-Confirm':'true'}});
    toast(success);await auth.policyChanged();
  }

  async function loadAudit(next=false){
    if(!me()?.platformAdmin||!auth.has('identity.audit.read')||(next&&!auditCursor))return;
    const sequence=++auditSequence,revision=auth.revision,params=new URLSearchParams();
    if(!next)auditTarget=$('#identityAuditForm').elements.targetId.value.trim();
    if(auditTarget)params.set('targetId',auditTarget);
    if(next)params.set('before',auditCursor);
    auditCursor=null;
    $('#identityAuditNext').disabled=true;
    $('#identityAuditRows').replaceChildren();
    $('#identityAuditStatus').textContent='正在读取审计记录。';
    try{
      const page=await auth.api('/identity/audit?'+params);
      if(sequence!==auditSequence||revision!==auth.revision)return;
      for(const row of page.items){
        const item=document.createElement('article'),title=document.createElement('strong'),meta=document.createElement('p');
        title.textContent=(operationLabels[row.operation]||row.operation)+' · '+row.outcome+' · '+row.reasonCode;
        meta.textContent=[new Date(row.occurredAt).toLocaleString(),row.targetKind,row.targetId,
          '操作者 '+(row.actorUserId||row.actorServiceId||'运维入口'),'组织 '+(row.actorOrgId||'未指定'),'请求 '+row.requestId].filter(Boolean).join(' · ');
        const details=document.createElement('details'),summary=document.createElement('summary'),body=document.createElement('pre');
        summary.textContent='查看范围和变更前后';
        body.textContent=JSON.stringify({范围:row.scope,变更前:row.beforeValue,变更后:row.afterValue},null,2);
        details.append(summary,body);item.append(title,meta,details);$('#identityAuditRows').append(item);
      }
      auditCursor=page.nextCursor;
      $('#identityAuditNext').disabled=!auditCursor;
      $('#identityAuditStatus').textContent=page.items.length?'本页 '+page.items.length+' 条记录。':'没有匹配的审计记录。';
    }catch(error){
      if(sequence===auditSequence&&revision===auth.revision&&error.name!=='AbortError')
        $('#identityAuditStatus').textContent=message(error);
    }
  }
  function clearManagement(){
    cancelImpact();
    loadSequence++;users=[];types=[];orgs=[];catalog=[];selectedUser='';selectedType='';
    auditSequence++;auditCursor=null;auditTarget='';
    $('#identityAuditRows').replaceChildren();$('#identityAuditStatus').textContent='';$('#identityAuditNext').disabled=true;
    for(const id of ['identityUserSelect','identityTypeSelect','identityUserOrg','identityPrimaryType','identityAdditionalTypes','identityTypeOrg','identityApplicableOrgs','identityDelegatedTypes','identityReplacementType'])$('#'+id).replaceChildren();
    $('#identityOperationPicker').replaceChildren();$('#identityUserState').textContent='';$('#identityTypeMembers').textContent='';
    for(const form of $$('#identityManagement form'))form.reset();
    $('#identityManagementContent').hidden=true;
  }
  function showSelf(){
    const identity=me();
    $('#identitySignedOut').hidden=!!identity;$('#identitySummary').hidden=!identity;$('#identitySelfForms').hidden=!identity;
    $('#identitySummary').replaceChildren();$('#identityEffectiveOperations').replaceChildren();
    if(identity){
      const primary=identity.account?.types?.find(type=>type.primary);
      const primaryStatus=primary?.state==='DISABLED'?' · 类型已停用':identity.readOnly?' · 业务只读':'';
      const management=[identity.platformAdmin?'平台管理员':'',identity.enterpriseMemberManager?'企业成员管理者':''].filter(Boolean).join('、')||'无管理资格';
      const mapping=identity.enterpriseIdentity;
      $('#identitySummary').innerHTML='<p><b>'+esc(identity.username)+'</b> · '+esc(states[identity.account?.accountState]||'启用')+'</p>'+
        '<p>当前企业组织：'+esc(identity.orgName||identity.orgId)+(mapping?.entityId?' · '+esc(mapping.entityId)+' · '+esc(industries[mapping.industryKind]||mapping.industryKind):' · 企业映射待确认')+'</p>'+
        '<p>业务主配置：'+esc(primary?.name||'仅账号自助')+primaryStatus+'</p><p>管理资格：'+esc(management)+'</p>';
      for(const code of identity.operations||[]){
        const item=document.createElement('span');item.textContent=operationLabels[code]||code;$('#identityEffectiveOperations').append(item);
      }
      if(!state.snapshot)$('#currentOrgName').textContent=identity.orgName||identity.orgId;
    }
    const manager=!!identity&&(identity.platformAdmin||identity.enterpriseMemberManager);
    $('#identityManageNav').hidden=!manager;$('#identityBulkLogout').hidden=!identity?.platformAdmin;
    $('#identityAuditPanel').hidden=!identity?.platformAdmin||!auth.has('identity.audit.read');
    $('#identityManagerHeading').textContent=identity?.platformAdmin?'平台账号与类型管理':'本企业普通成员管理';
    $('#identityManagementNotice').textContent=identity?.platformAdmin?'管理资格与业务数据权限分别生效。更改账号、类型或资格后，旧会话会按服务端规则失效。':
      identity?.enterpriseMemberManager?'仅管理本企业普通成员，并分配平台委托的适用业务类型。':'仅向平台管理员或企业成员管理者开放。';
    $('#identityQualificationsPanel').hidden=!identity?.platformAdmin;$('#identityTypesPanel').hidden=!identity?.platformAdmin;
    for(const form of $$('#identitySelfForms form'))form.reset();
    const dialog=$('#identityActivationDialog');
    if(auth.activationRequired){
      $('#identityActivationUser').textContent='待激活账号：'+(showSelf.activationUsername||'');
      if(!dialog.open)dialog.showModal();
    }else if(dialog.open){dialog.close();$('#identityActivationForm').reset();}
    if(!manager&&location.hash==='#identityManagement')location.hash='#identity';
    applyCapabilities();
  }
  async function loadManagement(){
    if(!auth.authenticated||!me()?.platformAdmin&&!me()?.enterpriseMemberManager)return;
    const sequence=++loadSequence,revision=auth.revision;
    try{
      const result=await Promise.all([auth.api('/identity/users'),auth.api('/identity/types'),auth.api('/identity/organizations'),me().platformAdmin?auth.api('/identity/operations'):Promise.resolve([])]);
      if(sequence!==loadSequence||revision!==auth.revision)return;
      [users,types,orgs,catalog]=result;
      $('#identityUserSelect').innerHTML=option('','新建账号')+users.map(user=>option(user.userId,user.username+' · '+orgName(user.orgId)+' · '+states[user.accountState])).join('');
      $('#identityTypeSelect').innerHTML=option('','新建业务类型')+types.map(type=>option(type.typeId,type.name+' · '+type.code+' · '+states[type.state])).join('');
      const orgOptions=orgs.filter(org=>org.status===1).map(org=>option(org.orgId,org.orgName+(org.entityId?' · '+org.entityId:''))).join('');
      for(const id of ['identityUserOrg','identityTypeOrg','identityApplicableOrgs'])$('#'+id).innerHTML=orgOptions;
      const groups={data:'数据使用',supply:'数据供给与维护',product:'产品与申请',contract:'合约',delivery:'交付',jobs:'任务'};
      $('#identityOperationPicker').innerHTML=Object.entries(groups).map(([group,label])=>'<section class="identity-operation-group"><b>'+label+'</b>'+businessCatalog().filter(op=>op.group===group).map(op=>'<label><input type="checkbox" value="'+esc(op.code)+'" />'+esc(operationLabels[op.code]||op.label)+'</label>').join('')+'</section>').join('');
      $('#identityManagementContent').hidden=false;selectUser('');selectType('');
    }catch(error){if(sequence===loadSequence)report(error);}
  }
  const formOperations={spaceForm:'dataset.create',sourceForm:'dataset.draft.manage',uploadForm:'dataset.upload',catalogForm:'dataset.draft.manage',governForm:'dataset.quality.run',indexForm:'dataset.index.build',publishForm:'product.publish',intentForm:'application.submit',receiverForm:'delivery.endpoint.manage',contractForm:'contract.edit',qaIngestForm:'dataset.upload',qaForm:'data.qa',retrievalForm:'data.search',identityPasswordForm:'identity.self.password',identityCloseForm:'identity.self.close',identityUserForm:selectedUser?'identity.member.update':'identity.member.create',identityResetForm:'identity.member.reset',identityDeleteForm:'identity.member.close',identityPlatformForm:'identity.platform-admin.manage',identityMemberManagerForm:'identity.platform-admin.manage',identityTypeForm:'identity.type.manage',identityDeleteTypeForm:'identity.type.manage'};
  function applyCapabilities(){
    if(!document.getElementById('identitySummary'))return;
    formOperations.identityUserForm=selectedUser?'identity.member.update':'identity.member.create';
    for(const [id,operation] of Object.entries(formOperations)){
      const form=document.getElementById(id);if(!form)continue;
      const blocked=!auth.has(operation)||(id==='qaIngestForm'&&!!form.dataset.ingestBusy)||(id==='identityUserForm'&&currentUser()?.accountState==='CLOSED')||(id.startsWith('identity')&&['identityResetForm','identityDeleteForm','identityPlatformForm','identityMemberManagerForm'].includes(id)&&(!currentUser()||currentUser().accountState==='CLOSED'));
      form.inert=blocked;form.setAttribute('aria-disabled',String(blocked));
      // The retrieval form also depends on the selected index's loaded capabilities.
      if(id==='retrievalForm')continue;
      for(const button of form.querySelectorAll('button[type=submit],button:not([type])'))if(button.disabled!==blocked)button.disabled=blocked;
    }
    const ingestSubmit=$('#qaIngestSubmit');
    const ingestBusy=!!$('#qaIngestForm')?.dataset.ingestBusy;
    const creatingDataset=!!$('#qaNewDataset')?.value.trim();
    const ingestLabel=creatingDataset?'创建数据集':!auth.has('dataset.publish')?'上传并保存草稿':
      auth.has('dataset.index.build')?'上传并发布，自动建立索引':'上传并发布版本';
    if(ingestSubmit&&ingestSubmit.textContent!==ingestLabel)ingestSubmit.textContent=ingestLabel;
    for(const id of ['qaNewSpace','qaNewDataset'])$('#'+id).disabled=ingestBusy||!auth.has('dataset.create');
    for(const id of ['qaIngestFiles','qaIngestFolder'])$('#'+id).disabled=ingestBusy||creatingDataset||!auth.has('dataset.upload');
    $('#qaNewVersion').disabled=ingestBusy||creatingDataset||!auth.has('dataset.upload')||!auth.has('dataset.draft.manage');
    for(const [id,operation] of Object.entries({qaSaveMapping:'dataset.draft.manage',qaBackfill:'dataset.index.build',identityToggleState:'identity.member.disable',identityForceLogout:'identity.member.disable',identityBulkLogout:'identity.platform-admin.manage'})){
      const element=document.getElementById(id);if(element)element.disabled=!auth.has(operation)||(['qaSaveMapping','qaBackfill'].includes(id)&&ingestBusy)||(id.startsWith('identity')&&id!=='identityBulkLogout'&&(!currentUser()||currentUser().accountState==='CLOSED'));
    }
    const selectors={'[data-verify]':'platform.runtime.manage','[data-quality]':'dataset.quality.read','[data-decide]':'application.review','[data-confirm-contract]':'contract.confirm','[data-revoke-contract]':'contract.revoke','[data-revise-contract]':'contract.edit','[data-dispatch]':'delivery.execute','[data-delivery-json]':'delivery.receipt.read','[data-delivery-download]':'delivery.package.download','[data-contract-history]':'contract.read'};
    for(const [selector,operation] of Object.entries(selectors))for(const button of $$(selector))button.disabled=!auth.has(operation);
  }
  const bindForm=(id,action)=>{$('#'+id).onsubmit=event=>{event.preventDefault();const form=event.currentTarget;Promise.resolve().then(()=>action(form)).catch(report);};};
  document.addEventListener('DOMContentLoaded',()=>{
    $('#identityReload').onclick=()=>void auth.check({force:true});$('#identityManagementReload').onclick=loadManagement;
    $('#identityAuditForm').onsubmit=event=>{event.preventDefault();void loadAudit();};
    $('#identityAuditNext').onclick=()=>void loadAudit(true);
    $('#identityLogout').onclick=()=>void auth.logout();
    $('#identityBulkLogout').onclick=()=>mutation('/identity/users/logout-non-admin','POST',{},'所有非平台管理员的旧会话已撤销。').catch(report);
    $('#identityUserSelect').onchange=event=>selectUser(event.target.value);$('#identityNewUser').onclick=()=>selectUser('');
    $('#identityUserOrg').onchange=()=>fillUserTypes();
    $('#identityPrimaryType').onchange=()=>{const primary=$('#identityPrimaryType').value;for(const option of $('#identityAdditionalTypes').options)if(option.value===primary)option.selected=false;};
    $('#identityTypeSelect').onchange=event=>selectType(event.target.value);$('#identityNewType').onclick=()=>selectType('');
    $('#identityTypeForm').elements.readOnly.onchange=enforceReadonlyPicker;
    bindForm('identityActivationForm',async form=>{
      if(form.elements.newPassword.value!==form.elements.confirmPassword.value)throw new Error('两次输入的新密码不一致。');
      const username=await auth.activate(form.elements.newPassword.value);form.reset();$('#qaLogin').elements.userName.value=username;toast('激活完成，请使用新密码登录。');
    });
    const cancel=()=>{auth.cancelActivation();$('#identityActivationForm').reset();};
    $('#identityCancelActivation').onclick=cancel;$('#identityActivationDialog').addEventListener('cancel',event=>{event.preventDefault();cancel();});
    bindForm('identityPasswordForm',async form=>{
      if(form.elements.newPassword.value!==form.elements.confirmPassword.value)throw new Error('两次输入的新密码不一致。');
      await auth.api('/users/me/password',{method:'PATCH',json:{oldPassword:form.elements.oldPassword.value,newPassword:form.elements.newPassword.value}});
      form.reset();await auth.logout();toast('密码已更新，请重新登录。');
    });
    bindForm('identityCloseForm',async form=>{
      const identity=me();if(!identity||form.elements.confirmation.value!==identity.username)throw new Error('请输入当前用户名确认注销。');
      await auth.api('/users/me/close',{method:'POST',json:{expectedVersion:identity.permissionVersion}});await auth.logout();toast('账号已注销，业务记录保留。');
    });
    bindForm('identityUserForm',async form=>{
      const configured=assignment(),user=currentUser(),body={username:form.elements.username.value.trim(),orgId:form.elements.orgId.value,...configured};
      if(user)await mutation('/identity/users/'+user.userId,'PATCH',{...body,expectedVersion:user.permissionVersion},'账号与业务配置已更新。');
      else await mutation('/identity/users','POST',{...body,temporaryPassword:form.elements.temporaryPassword.value},'账号已创建，首次登录须设置新密码。');
    });
    $('#identityToggleState').onclick=()=>Promise.resolve().then(()=>{const user=expectedUser();return mutation('/identity/users/'+user.userId+'/state','PATCH',{state:user.accountState==='DISABLED'?'ACTIVE':'DISABLED',expectedVersion:user.permissionVersion},user.accountState==='DISABLED'?'账号已恢复。':'账号已停用。');}).catch(report);
    $('#identityForceLogout').onclick=()=>Promise.resolve().then(()=>{const user=expectedUser();return mutation('/identity/users/'+user.userId+'/logout','POST',{expectedVersion:user.permissionVersion},'目标账号的旧会话已撤销。');}).catch(report);
    bindForm('identityResetForm',form=>{const user=expectedUser();return mutation('/identity/users/'+user.userId+'/password-reset','POST',{temporaryPassword:form.elements.temporaryPassword.value,expectedVersion:user.permissionVersion},'临时密码已重置；成员需重新激活。');});
    bindForm('identityDeleteForm',form=>{const user=expectedUser();if(form.elements.confirmation.value!==user.username)throw new Error('请输入目标用户名确认注销。');return mutation('/identity/users/'+user.userId+'/close','POST',{expectedVersion:user.permissionVersion},'账号已注销，业务记录保留。');});
    bindForm('identityPlatformForm',form=>{const user=expectedUser();return mutation('/identity/users/'+user.userId+'/qualification','PUT',{qualification:'PLATFORM_ADMIN',active:form.elements.active.value==='true',delegatedTypeIds:[],expectedVersion:(user.qualifications||[]).find(item=>item.qualification==='PLATFORM_ADMIN')?.version??0},'平台管理员资格已更新。');});
    bindForm('identityMemberManagerForm',form=>{const user=expectedUser();return mutation('/identity/users/'+user.userId+'/qualification','PUT',{qualification:'ENTERPRISE_MANAGER',active:form.elements.active.value==='true',delegatedTypeIds:values($('#identityDelegatedTypes')),expectedVersion:(user.qualifications||[]).find(item=>item.qualification==='ENTERPRISE_MANAGER')?.version??0},'企业成员管理范围已更新。');});
    bindForm('identityTypeForm',form=>{
      const type=currentType(),body={orgId:form.elements.orgId.value,code:form.elements.code.value.trim(),name:form.elements.name.value.trim(),description:form.elements.description.value,scope:form.elements.scope.value,industryTags:values(form.elements.industryTags),applicableOrgIds:values($('#identityApplicableOrgs')),readOnly:form.elements.readOnly.value==='true',operations:[...$('#identityOperationPicker').querySelectorAll('input:checked')].map(input=>input.value),state:form.elements.state.value,expectedVersion:type?.version??null};
      return mutation(type?'/identity/types/'+type.typeId:'/identity/types',type?'PUT':'POST',body,'业务类型已保存；相关成员旧会话按服务端规则失效。');
    });
    bindForm('identityDeleteTypeForm',form=>{
      const type=currentType();if(!type||form.elements.confirmation.value!==type.code)throw new Error('请输入当前类型代码确认删除。');
      const replacement=activeType(form.elements.replacementTypeId.value);
      return mutation('/identity/types/'+type.typeId+'/delete','POST',{expectedVersion:type.version,replacementTypeId:replacement?.typeId||null,replacementTypeVersion:replacement?.version||null},'业务类型已删除；如有成员，已完成原子迁移。');
    });
    window.addEventListener('dataspace-identity',event=>{
      showSelf.activationUsername=event.detail.activationUsername;clearManagement();showSelf();if(auth.authenticated)void loadManagement();
    });
    window.addEventListener('dataspace-notice',event=>toast(event.detail.message));
    const observer=new MutationObserver(()=>{queueMicrotask(applyCapabilities);});observer.observe(document.querySelector('main'),{childList:true,subtree:true});
    showSelf();if(auth.authenticated)void loadManagement();
  });
  window.mdsIdentityUi={applyCapabilities};
})();
