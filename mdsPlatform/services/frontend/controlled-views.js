(() => {
 'use strict';
 const auth=window.mdsIdentity,$=id=>document.getElementById(id),proposals='/controlled-view-proposals',previews='/controlled-view-previews',publications='/controlled-view-publications';
 const ops=[['data.catalog.read','查看目录'],['data.preview','预览授权内容'],['data.search','专业检索'],['data.qa','证据问答'],['data.raw.download','下载完整文件'],['data.view.export','导出授权视图']];
 const states={BUILDING:'生成私有预览中',AWAITING_REVIEW:'私有预览已校验，等待核对',RAW_PUBLISHED:'独立原始版本已冻结，等待索引',INDEXING:'正在建立索引并校验',READY:'索引与授权已登记',FAILED:'本次未通过校验',CANCELED:'本次已取消',REVOKED:'分享已撤销'};
 const errors={
  VIEW_RESPONSIBILITY_ORIGINAL_SOURCE_REQUIRED:'企业责任范围需要完整业务来源，请为父视图使用逐文件选择。',
  VIEW_RESPONSIBILITY_COMPLETE_RECORDS_REQUIRED:'请列出完整文件中全部业务记录的标识，并核对它们都属于接收企业。',
  VIEW_RESPONSIBILITY_COMPLETE_FILE_INVALID:'完整附件的来源、文件位置或确认信息无效，请重新选择。',
  CONTROLLED_VIEW_PARENT_NOT_AVAILABLE:'父视图、冻结索引或原授权当前不可用，请重新选择来源。',
  CONTROLLED_VIEW_PERIOD_OUTSIDE_PARENT:'分享期限必须在父视图获批的有效期内。',
  VIEW_PROPOSAL_SOURCE_NOT_AVAILABLE:'来源授权或冻结版本当前不可用，请刷新来源。',
  VIEW_PROPOSAL_SOURCE_INDEX_NOT_AVAILABLE:'来源索引与该冻结版本不匹配，请重新选择来源。',
  VIEW_PROPOSAL_CONFIRMED_TARGET_NOT_AVAILABLE:'接收企业映射已变化，请重新选择接收企业。',
  VIEW_PROPOSAL_SOURCE_QUERY_TOO_BROAD:'来源过多，请按数据集或版本名称收窄。',
  VIEW_PROPOSAL_CONTEXT_CHANGED:'来源、企业映射或权限已变化，请重新生成范围。',
  VIEW_PROPOSAL_NOT_AVAILABLE:'该草稿当前不可用，或不属于本次登录会话。',
  PREVIEW_TASK_ALREADY_RUNNING:'预览任务仍在运行，请刷新已有记录。',
  CONTROLLED_VIEW_PREVIEW_NOT_AVAILABLE:'该私有预览当前不可用，或不属于本次登录会话。',
  CONTROLLED_VIEW_ORIGINAL_COMPLETE_SOURCE_AUTHORITY_REQUIRED:'原来源授权已变化，本次不能继续发布。',
  CONTROLLED_VIEW_PROPOSAL_ALREADY_PUBLISHED:'该范围已有发布记录，请从发布记录管理；重新发布需新建范围。',
  CONTROLLED_VIEW_SHARING_PERIOD_INVALID:'分享有效期无效，请核对开始与结束时间。',
  CROSS_ENTERPRISE_SHARING_EXPIRY_REQUIRED:'跨企业分享需要明确结束时间。',
  ORIGINAL_BYTE_VIEW_EXPORT_REQUIRES_RAW_OPERATION:'完整文件副本的导出需要同一条授权明确包含完整文件下载。',
  CONTROLLED_VIEW_PUBLICATION_REVIEW_CHANGED:'本次审核内容已变化，请重新核对。',
  CONTROLLED_VIEW_PUBLICATION_REVIEW_EXPIRED:'本次审核已过期，请重新核对。',
  CONTROLLED_VIEW_PUBLICATION_REVIEW_NOT_AVAILABLE:'本次审核不属于当前登录会话，请重新核对。',
  CONTROLLED_VIEW_REVOKE_VERSION_CHANGED:'发布或授权状态已变化，请刷新后重新撤销。',
  CONTROLLED_VIEW_INDEX_IMAGE_CONFIGURATION_REQUIRED:'索引运行环境尚未配置完整，请联系运维人员。',
  CONTROLLED_VIEW_IMAGE_CONFIGURATION_REQUIRED:'私有预览运行环境尚未配置完整，请联系运维人员。'
 };
 let generation=0,draft=0,busy=false,controller=new AbortController(),timer=null,owner='',uiRevision=0,resume=null,pending=null;
 let readRequest=0,fileAbort=new AbortController();
 let sources=[],targets=[],files=[],sourcePage=1,filePage=1,sourceRequest=0,fileRequest=0,source=null,proposal=null,preview=null,publication=null,review=null;
 const selected=new Map(),urls=new Set(),readFiles=new Set();
 const allowed=()=>auth.authenticated&&auth.has('dataset.share.manage');
 const canBuild=()=>allowed();
 const canPublish=()=>canBuild()&&['dataset.create','dataset.publish','dataset.index.build'].every(code=>auth.has(code));
 const key=()=>{const a=auth.identity;return a?a.userId+':'+a.orgId:'';};
 const node=(tag,cls,text)=>{const e=document.createElement(tag);if(cls)e.className=cls;if(text!==undefined)e.textContent=String(text);return e;};
 const button=(text,run)=>{const e=node('button','btn',text);e.type='button';e.onclick=run;return e;};
 const status=text=>{$('cvStatus').textContent=text;};
 const context=()=>({generation,revision:auth.revision,draft});
 const current=(c,checkDraft=false)=>c.generation===generation&&c.revision===auth.revision&&(!checkDraft||c.draft===draft)&&$('controlledViewDialog').open&&allowed();
 const api=(path,options={})=>auth.api(path,{...options,signal:controller.signal});
 function report(error,c){
  if(error?.name==='AbortError'||c&&!current(c))return;
  const text=String(error?.message||error||''),code=Object.keys(errors).find(k=>text.includes(k));
  status(code?errors[code]:error?.status===401?'请重新登录后重试。':error?.status===403?'当前账号缺少本次操作能力。':error?.status===404?'该范围在当前身份和授权下不可见，请刷新记录。':error?.status>=500?'服务暂不可用，请保留记录并稍后刷新。':error?.status===409?'范围或状态已变化，请刷新后重新核对。':text||'操作未完成，请刷新记录。');
 }
 function line(root,label,value){const r=node('div'),b=node('b','',label),t=node('span','',value??'未设置');r.append(b,t);root.append(r);}
 function options(select,rows,placeholder){select.replaceChildren(new Option(placeholder,''));for(const row of rows)select.append(new Option(row.label,row.value));}
 function date(value){if(!value)return '未设置';const d=new Date(value);return Number.isNaN(d.getTime())?String(value):d.toLocaleString();}
 function localInput(value){const d=new Date(value);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);}
 function instant(input){if(!input.value)return null;const d=new Date(input.value);if(!Number.isFinite(d.getTime()))throw Error('请填写有效时间。');return d.toISOString();}
 function lines(value){return String(value).split(/\r?\n/).filter(x=>x!=='');}
 function safeInteger(value,label){const n=Number(value);if(!Number.isSafeInteger(n)||n<1)throw Error(label+'需要正整数。');return n;}
 function revokeUrls(){for(const u of urls)URL.revokeObjectURL(u);urls.clear();$('cvFileDisplay').replaceChildren();}
 function stopPolling(){if(timer)clearTimeout(timer);timer=null;}
 function clearReview(){review=null;$('cvApprovalPanel').hidden=true;$('cvApprovalDetails').replaceChildren();$('cvConfirmForm').reset();}
 function clearActive(){readRequest++;fileAbort.abort();fileAbort=new AbortController();stopPolling();pending=null;proposal=null;preview=null;publication=null;readFiles.clear();revokeUrls();clearReview();for(const id of ['cvPreviewPanel','cvProgressPanel'])$(id).hidden=true;}
 function dirty(){draft++;clearReview();if(preview||proposal){clearActive();status('范围草稿已改变，请重新生成私有预览。');}controls();}
 const responsibility=()=>$('cvScopeMode').value==='ENTERPRISE_RESPONSIBILITY';
 function scopeDescription(){
  const auto=responsibility();$('cvScopeMode').querySelector('option[value="ENTERPRISE_RESPONSIBILITY"]').disabled=source?.sourceKind==='CONTROLLED_VIEW';
  $('cvScopeHelp').textContent=auto?'按已确认企业及业务主表筛选记录、参与方字段、JSON 集合和可见表格单元格；公式按保留范围重新计算。完整附件需要另外逐项确认。生成后仍须核对实际预览并批准发布。':'逐文件填写明确的行列或行段；适用于原始来源和本方已就绪的父视图。';
  $('cvFilesTitle').textContent=auto?'2. 可选的完整附件':'2. 逐项限定业务文件';
  $('cvFileHelp').textContent=auto?'结构化资料由责任范围规则筛选。下方仅可额外选择完整附件；请确认全部业务记录、隐藏内容及元信息都可提供给该企业。':'结构化资料请列明保留字段、主键及记录；全部记录需要单独确认。完整文件包含其全部内容和元信息。';
 }
 function controls(){
  scopeDescription();$('cvScopeMode').disabled=busy||!source||!canBuild();
  for(const input of $('cvOperations').querySelectorAll('input'))input.disabled=busy||!auth.has(input.value);
  const active=allowed();for(const id of ['controlledViewsOpen','controlledViewsOpenManagement','controlledViewsOpenProvider'])$(id).hidden=!active;
  $('cvBuilderNotice').textContent=canBuild()?'生成预览使用本次明确的分享范围。裁剪结果按视图导出能力读取；完整文件副本仍需原件下载能力。':'生成预览需要分享管理能力及当前来源授权。';
  $('cvRulesForm').inert=busy||!canBuild();$('cvSourceSearch').inert=busy||!canBuild();$('cvSource').disabled=busy||!canBuild();
  $('cvGenerate').disabled=busy||!canBuild()||!source||!$('cvTarget').value||!responsibility()&&!selected.size;
  $('cvReview').disabled=busy||!canPublish()||preview?.state!=='AWAITING_REVIEW'||!readFiles.size;
  $('cvPublishCapability').textContent=canPublish()?'请先打开至少一个实际预览文件并核对范围。裁剪结果可按视图导出能力读取，完整文件副本需要原件下载能力。':'发布还需要创建数据集、发布版本、构建索引及所选消费动作的业务能力。';
  $('cvConfirm').disabled=busy||!review||Date.now()>=Date.parse(review.expiresAt);
  $('cvPublicationForm').inert=busy;$('cvConfirmForm').inert=busy;$('cvRevokeForm').inert=busy;
  $('cvStartIndex').disabled=busy||!canPublish();$('cvRefresh').disabled=busy;
 }
 function setBusy(value){busy=value;controls();}
 function reset(){
  generation++;draft++;sourceRequest++;fileRequest++;controller.abort();controller=new AbortController();stopPolling();revokeUrls();clearReview();
  selected.clear();readFiles.clear();sources=[];targets=[];files=[];source=null;proposal=null;preview=null;publication=null;pending=null;busy=false;sourcePage=1;filePage=1;
  for(const id of ['cvSource','cvTarget','cvFileChoices','cvRules','cvDraftRows','cvAttemptRows','cvPublicationRows','cvPreviewFiles','cvFrozenScope','cvSelections','cvProgress','cvApprovalDetails'])$(id).replaceChildren();
  for(const id of ['cvPreviewPanel','cvApprovalPanel','cvProgressPanel'])$(id).hidden=true;
  $('cvBuilder').hidden=false;$('cvRulesForm').reset();$('cvPublicationForm').reset();$('cvConfirmForm').reset();$('cvRevokeForm').reset();
  $('cvSourcePage').textContent='';$('cvFilePage').textContent='';$('cvReadStatus').textContent='';status('');owner=key();uiRevision=auth.revision;controls();
 }
 function close({keepResume=false}={}){if(!keepResume)resume=null;if($('controlledViewDialog').open)$('controlledViewDialog').close();reset();}
 async function open(reference){
  if(!allowed())return;reset();$('controlledViewDialog').showModal();const c=context();status('正在读取当前可用的来源与记录…');
  try{
   await Promise.all([canBuild()?loadSources(c):Promise.resolve(),loadRecords(c)]);
   if(!current(c))return;status('请选择明确范围，或继续核对当前记录。');
   if(reference?.kind==='publication')await openPublication(reference.id,c);
   else if(reference?.kind==='preview')await openAttempt(reference.id,c);
   else if(reference?.kind==='revoked-receipt'){pending=reference;status('分享已撤销，冻结文件、索引和历史记录保留。可查看的记录以当前授权为准。');}
  }catch(e){report(e,c);}
 }
 async function loadSources(c){
  clearActive();draft++;source=null;selected.clear();targets=[];files=[];$('cvRules').replaceChildren();$('cvFileChoices').replaceChildren();options($('cvTarget'),[],'选择接收企业');$('cvFilePage').textContent='';controls();
  const seq=++sourceRequest,page=sourcePage;const result=await api(proposals+'/sources?'+new URLSearchParams({page:String(page),size:'25',keyword:$('cvSourceKeyword').value}));
  if(!current(c)||seq!==sourceRequest)return;
  sources=result.items;options($('cvSource'),sources.map((s,n)=>({value:String(n),label:(s.sourceKind==='CONTROLLED_VIEW'?'受控视图 · ':'')+s.datasetName+' / '+s.versionName})),'选择冻结来源');
  $('cvSourcePage').textContent='第 '+page+' 页，共 '+result.total+' 个可用冻结版本';
  $('cvSourcePrevious').disabled=page<=1;$('cvSourceNext').disabled=page*25>=result.total;
  if(sources.length===1){$('cvSource').value='0';await changeSource();}
 }
 async function changeSource(){
  clearActive();draft++;selected.clear();$('cvRules').replaceChildren();files=[];targets=[];options($('cvTarget'),[],'选择接收企业');$('cvFileChoices').replaceChildren();
  source=sources[Number($('cvSource').value)];if($('cvSource').value==='')source=null;if(source?.sourceKind==='CONTROLLED_VIEW')$('cvScopeMode').value='MANUAL';filePage=1;controls();if(!source)return;
  const c=context(),chosen=source;
  try{
   const result=await api(proposals+'/sources/'+chosen.datasetId+'/versions/'+chosen.versionId+'/targets');
   if(!current(c)||source!==chosen)return;targets=result;
   options($('cvTarget'),targets.map(t=>({value:t.orgId,label:t.orgName+' · '+t.enterpriseName})),'选择已经确认的接收企业');
   if(targets.length===1)$('cvTarget').value=targets[0].orgId;
   await loadFiles(c);if(current(c))status(targets.length?'请选择范围方式，并核对接收企业与分享依据。':'该来源尚无已确认的接收企业，请先由有资格的管理人员配置企业映射。');
  }catch(e){report(e,c);}
 }
 async function loadFiles(c){
  if(!source)return;const seq=++fileRequest,chosen=source,page=filePage;
  const result=await api(proposals+'/sources/'+chosen.datasetId+'/versions/'+chosen.versionId+'/files?'+new URLSearchParams({page:String(page),size:'25'}));
  if(!current(c)||seq!==fileRequest||source!==chosen)return;files=result.items;
  $('cvFilePage').textContent='第 '+page+' 页，共 '+result.total+' 个可选业务文件';$('cvFilePrevious').disabled=page<=1;$('cvFileNext').disabled=page*25>=result.total;renderFiles();
 }
 function renderFiles(){
  $('cvFileChoices').replaceChildren();for(const file of files){
   if(responsibility()&&!['txt','pdf','png','jpg','jpeg','svg','obj','step','stp'].includes(file.format))continue;
   const label=node('label','cv-file-choice'),input=node('input');input.type='checkbox';input.checked=selected.has(file.path);input.disabled=!$('cvTarget').value||busy;
   input.onchange=()=>{try{if(input.checked)addRule(file);else{selected.get(file.path)?.node.remove();selected.delete(file.path);dirty();}}catch(e){input.checked=selected.has(file.path);report(e);}};
   label.append(input,node('span','',file.path+' · '+file.bytes.toLocaleString()+' 字节'));$('cvFileChoices').append(label);
  }controls();
 }
 function inputField(root,label,kind='input',value='',name){
  const l=node('label','full',label),i=node(kind);if(kind==='input')i.type='text';if(name)i.dataset.field=name;i.value=value;l.append(i);root.append(l);return i;
 }
 function rowsEditor(root,preset={}){
  const area=node('div','form-grid'),keyInput=inputField(area,'主键字段','input',preset.key||''),columns=inputField(area,'保留字段（每行一个，必须包含主键）','textarea',(preset.columns||[]).join('\n'));
  keyInput.required=true;columns.required=true;const label=node('label','full','保留记录'),mode=node('select');mode.append(new Option('仅指定主键记录','keys'),new Option('该表全部记录（需明确确认）','all'));label.append(mode);area.append(label);
  const keys=inputField(area,'保留的主键值（每行一个，保留原始写法）','textarea',(preset.rowKeys||[]).join('\n')),ackLabel=node('label','cv-check full'),ack=node('input');ack.type='checkbox';ackLabel.append(ack,node('span','','我确认该表全部记录均可提供给所选企业'));area.append(ackLabel);root.append(area);
  mode.value=preset.allRows?'all':'keys';ack.checked=!!preset.allRows;
  const toggle=()=>{const all=mode.value==='all';keys.closest('label').hidden=all;keys.required=!all;ackLabel.hidden=!all;ack.required=all;};mode.onchange=toggle;toggle();
  return {key:keyInput,columns,mode,keys,ack};
 }
 function readRows(r){
  const key=r.key.value,columns=lines(r.columns.value);if(!key||!columns.length||!columns.includes(key))throw Error('每个结构化选择都需要主键，且主键必须列在保留字段中。');
  const out={key,columns};if(r.mode.value==='all'){if(!r.ack.checked)throw Error('请明确确认全部记录可提供给接收企业。');out.allRows=true;}else{out.rowKeys=lines(r.keys.value);if(!out.rowKeys.length)throw Error('请填写明确的主键记录。');}return out;
 }
 function addSheet(entry,preset={}){
  if(entry.sheets.length>=100)throw Error('单个工作簿最多选择 100 张工作表。');
  const box=node('section','cv-sheet'),fields=node('div','form-grid'),name=inputField(fields,'工作表名称','input',preset.sheet||''),header=inputField(fields,'表头行号','input',String(preset.headerRow||1));name.required=true;header.type='number';header.min='1';header.step='1';header.required=true;box.append(fields);
  const row=rowsEditor(box,preset),sheet={node:box,name,header,row};box.append(button('移除此工作表',()=>{box.remove();entry.sheets=entry.sheets.filter(x=>x!==sheet);dirty();}));entry.sheetRoot.append(box);entry.sheets.push(sheet);
 }
 function drawRule(entry,preset){
  entry.body.replaceChildren();entry.rows=null;entry.sheets=[];entry.pointer=null;entry.ranges=null;entry.ack=null;entry.recordIds=null;
  const kind=entry.kind.value;
  if(['CSV_ROWS','JSON_RECORDS','JSONL_RECORDS'].includes(kind)){
   if(kind==='JSON_RECORDS')entry.pointer=inputField(entry.body,'记录集合位置（根数组留空，嵌套集合如 /records）','input',preset?.collectionPointer||'');
   entry.rows=rowsEditor(entry.body,preset||{});
  }else if(kind==='XLSX_SHEETS'){
   entry.sheetRoot=node('div');entry.body.append(entry.sheetRoot);for(const sheet of preset?.sheets||[{}])addSheet(entry,sheet);
   entry.body.append(button('添加工作表',()=>{try{addSheet(entry);dirty();}catch(e){report(e);}}));
  }else if(kind==='TEXT_LINES'){entry.ranges=inputField(entry.body,'保留行段（每行如 1-20 或 35，按原顺序且不重叠）','textarea',(preset?.ranges||[]).map(r=>r[0]===r[1]?String(r[0]):r.join('-')).join('\n'));entry.ranges.required=true;}
  else{
   const l=node('label','cv-check cv-warning'),ack=node('input');ack.type='checkbox';ack.required=true;ack.checked=preset?.acknowledgement==='EXACT_SOURCE_BYTES_INCLUDING_HIDDEN_CONTENT';
   l.append(ack,node('span','','确认该完整文件的全部页面、图层、隐藏内容、批注和元信息均可提供给所选企业。无法确认时请移除此文件。'));entry.body.append(l);entry.ack=ack;
   if(responsibility()){entry.recordIds=inputField(entry.body,'完整文件中的全部业务记录标识（每行一个）','textarea',(preset?.recordIds||[]).join('\n'));entry.recordIds.required=true;}
  }
 }
 function addRule(file,preset){
  if(selected.has(file.path))return;if(selected.size>=250)throw Error('每次范围最多选择 250 个业务文件。');
  const box=node('section','cv-rule'),head=node('div','card-head');head.append(node('h4','',file.path),button('移除此文件',()=>{box.remove();selected.delete(file.path);renderFiles();dirty();}));box.append(head);
  const type=node('label','full','保留方式'),select=node('select'),kinds={csv:'CSV_ROWS',json:'JSON_RECORDS',jsonl:'JSONL_RECORDS',xlsx:'XLSX_SHEETS',txt:'TEXT_LINES'};
  if(!responsibility()&&kinds[file.format])select.append(new Option('仅保留明确选择的行列或行段',kinds[file.format]));select.append(new Option('完整文件（含隐藏内容与元信息）','COMPLETE_FILE'));select.value=preset?.kind||(responsibility()?'COMPLETE_FILE':kinds[file.format])||'COMPLETE_FILE';type.append(select);box.append(type);
  const output=inputField(box,'视图内文件位置','input',preset?.outputPath||file.path);output.required=true;output.readOnly=responsibility();const body=node('div');box.append(body);
  const details=node('details'),summary=node('summary','','来源文件校验信息');details.append(summary,node('code','',file.sha256));box.append(details);
  const entry={file,node:box,kind:select,output,body,sheets:[]};selected.set(file.path,entry);$('cvRules').append(box);drawRule(entry,preset);select.onchange=()=>{drawRule(entry);dirty();};dirty();renderFiles();
 }
 function collectRules(){
  const result=[];for(const e of selected.values()){
   const r={sourcePath:e.file.path,sourceSha256:e.file.sha256,outputPath:e.output.value,kind:e.kind.value};
   if(e.rows)Object.assign(r,readRows(e.rows));
   if(e.pointer)r.collectionPointer=e.pointer.value;
   if(e.kind.value==='XLSX_SHEETS'){if(!e.sheets.length)throw Error('请选择至少一张工作表。');r.sheets=e.sheets.map(s=>({sheet:s.name.value,headerRow:safeInteger(s.header.value,'表头行号'),...readRows(s.row)}));}
   if(e.ranges){let end=0;r.ranges=lines(e.ranges.value).map(value=>{const m=value.match(/^(\d+)(?:\s*-\s*(\d+))?$/);if(!m)throw Error('行段请填写 1-20 或单个行号。');const a=safeInteger(m[1],'行号'),b=safeInteger(m[2]||m[1],'行号');if(a<=end||b<a)throw Error('行段需要按顺序且不重叠。');end=b;return[a,b];});if(!r.ranges.length)throw Error('请填写明确的行段。');}
   if(e.ack){if(!e.ack.checked)throw Error('请确认完整文件的全部内容可向所选企业开放。');r.acknowledgement='EXACT_SOURCE_BYTES_INCLUDING_HIDDEN_CONTENT';}
   if(responsibility()){if(r.kind!=='COMPLETE_FILE'||r.outputPath!==r.sourcePath)throw Error('企业责任范围只允许额外选择完整附件，并保留文件位置。');r.recordIds=lines(e.recordIds?.value||'');if(!r.recordIds.length||new Set(r.recordIds).size!==r.recordIds.length)throw Error('请填写完整文件中全部且不重复的业务记录标识。');}
   result.push(r);
  }if(!result.length&&!responsibility())throw Error('请至少选择一个业务文件。');return result;
 }
 async function generate(event){
  event.preventDefault();if(busy)return;clearActive();const c=context(),form=event.currentTarget;
  try{
   if(!source||!$('cvTarget').value)throw Error('请先选择冻结来源与接收企业。');
   const body={datasetId:source.datasetId,versionId:source.versionId,indexVersionId:source.indexVersionId||null,targetOrgId:$('cvTarget').value,files:collectRules(),reason:form.elements.reason.value,scopeMode:$('cvScopeMode').value};
   setBusy(true);status('正在冻结本次明确范围…');const p=await api(proposals,{method:'POST',json:body});if(!current(c,true))return;
   proposal=p;await launch(p,c);
  }catch(e){report(e,c);}finally{if(current(c))setBusy(false);}
 }
 async function launch(p,c){
  status('范围已冻结，正在建立私有预览…');
  const value=await api(previews,{method:'POST',json:{proposalId:p.proposalId,expectedRuleSha256:p.proposal.ruleSha256}});if(!current(c))return;
  preview=value;publication=null;pending={kind:'preview',id:value.attemptId};$('cvBuilder').hidden=true;renderPreview();poll();await loadRecords(c);
 }
 async function continueProposal(id){
  if(busy)return;const c=context();try{setBusy(true);const p=await api(proposals+'/'+id);if(!current(c))return;proposal=p;await launch(p,c);}catch(e){report(e,c);}finally{if(current(c))setBusy(false);}
 }
 function frozenDetails(){
  const root=$('cvFrozenScope');root.replaceChildren();if(!proposal)return;const p=proposal.proposal,s=sources.find(x=>x.datasetId===p.source.datasetId&&x.versionId===p.source.versionId),t=targets.find(x=>x.orgId===p.target.orgId);
  line(root,'冻结来源',s?s.datasetName+' / '+s.versionName:'来源版本 '+p.source.versionId);
  line(root,'接收企业',(t?.orgName?t.orgName+' · ':'')+p.target.enterpriseName);line(root,'范围依据',p.rules.reason);
  if(p.rules.schema==='ENTERPRISE_RESPONSIBILITY_RULES_V1')line(root,'范围方式','按接收企业业务责任筛选；完整附件 '+p.rules.files.length+' 个，仍待本次审核。');
  for(const f of p.rules.files){
   let text=f.kind==='COMPLETE_FILE'?'完整文件（含全部隐藏内容）':f.kind==='TEXT_LINES'?'行段 '+f.ranges.map(r=>r.join('-')).join('；'):f.kind==='XLSX_SHEETS'?f.sheets.map(s=>s.sheet+'：主键 '+s.key+'；字段 '+s.columns.join('、')+'；'+(s.allRows?'全部记录':'主键 '+s.rowKeys.join('、'))).join('；'):'主键 '+f.key+'；字段 '+f.columns.join('、')+'；'+(f.allRows?'全部记录':'主键 '+f.rowKeys.join('、'));
   line(root,f.outputPath,text);
  }
 }
 function renderPreview(){
  if(!preview)return;$('cvPreviewPanel').hidden=false;frozenDetails();$('cvPreviewState').textContent=(states[preview.state]||preview.state)+(preview.jobStatus?' · 任务 '+preview.jobStatus:'');
  const ready=preview.state==='AWAITING_REVIEW'&&preview.preview?.actualStoredBytesVerified;
  $('cvPublicationForm').hidden=!ready;$('cvPreviewFiles').replaceChildren();$('cvSelections').replaceChildren();$('cvCopyNotice').hidden=!ready||!preview.preview.hasOriginalByteCopies;
  $('cvCopyNotice').textContent='此预览包含完整文件副本。接收方导出这类副本，还需要在同一条授权中明确包含完整文件下载，且账号自身具备相应业务能力。';
  if(ready){
   const table=node('table'),head=node('thead'),tr=node('tr');for(const text of ['文件','实际保留记录','来源记录'])tr.append(node('th','',text));head.append(tr);const body=node('tbody');
   for(const item of preview.preview.actualSelections||[]){const row=node('tr');row.append(node('td','',item.path),node('td','',item.selectedRecordCount??'完整文件'),node('td','',item.sourceRecordCount??(item.kind==='COMPLETE_FILE'?'完整文件':'按责任规则筛选')));body.append(row);}table.append(head,body);$('cvSelections').append(table);
   for(const file of preview.preview.publicFiles){const row=node('div','cv-file-row');const open=button('打开核对',()=>void readFile(file,false)),download=button('下载核对',()=>void readFile(file,true));open.disabled=download.disabled=!file.readOperation;row.append(node('span','',file.path+' · '+file.bytes.toLocaleString()+' 字节'+(!file.readOperation?' · 当前岗位不能读取此文件副本':'')),open,download);$('cvPreviewFiles').append(row);}
   const form=$('cvPublicationForm');if(!form.elements.name.value)form.elements.name.value=proposal.proposal.target.enterpriseName.slice(0,60)+'授权视图 '+new Date().toLocaleString().replace(/[/:]/g,'-');
   const parentEnd=proposal.proposal.source.controlledParent?.validTo;
   form.elements.validTo.max=parentEnd?localInput(Date.parse(parentEnd)):'';
   if(!form.elements.validTo.value)form.elements.validTo.value=localInput(Math.min(Date.now()+30*60*1000,parentEnd?Date.parse(parentEnd):Infinity));
   else if(parentEnd&&new Date(form.elements.validTo.value).getTime()>Date.parse(parentEnd))form.elements.validTo.value=localInput(Date.parse(parentEnd));
   if(!form.elements.reason.value)form.elements.reason.value=proposal.proposal.rules.reason;
   status('私有预览的存储字节已由服务端核验，请打开实际文件核对范围。');
  }else status(['FAILED','CANCELED'].includes(preview.state)?'本次预览未通过。请核对来源范围与任务记录后新建草稿。':'私有预览仍在运行或校验，尚未向接收企业开放。');
  $('cvReadStatus').textContent='已读取并核对校验值：'+readFiles.size+' 个预览文件。';controls();
 }
 async function readFile(file,download){
  const c=context(),id=preview?.attemptId;if(!id||preview.state!=='AWAITING_REVIEW'||!file.readOperation)return;const reading=++readRequest;fileAbort.abort();fileAbort=new AbortController();
  try{
   status('正在读取实际预览文件…');
   const result=await auth.file(previews+'/'+id+'/files/content?'+new URLSearchParams({path:file.path}),{signal:AbortSignal.any([controller.signal,fileAbort.signal])});
   if(!current(c)||preview?.attemptId!==id||reading!==readRequest)return;
   const digest=[...new Uint8Array(await crypto.subtle.digest('SHA-256',await result.blob.arrayBuffer()))].map(x=>x.toString(16).padStart(2,'0')).join('');
   if(!current(c)||preview?.attemptId!==id||reading!==readRequest)return;
   if(result.blob.size!==file.bytes||digest!==file.sha256){clearReview();throw Error('实际预览文件的字节或校验值已变化，请重新生成并核对。');}
   readFiles.add(file.path);revokeUrls();
   const ext=file.path.split('.').pop().toLowerCase(),root=$('cvFileDisplay');root.append(node('h4','',file.path));
   if(download){const url=URL.createObjectURL(new Blob([result.blob],{type:'application/octet-stream'}));urls.add(url);const a=node('a');a.href=url;a.download='授权视图预览-'+file.path.split('/').pop();root.append(a);a.click();a.remove();root.append(node('p','','已校验实际文件并交由浏览器下载，请核对后继续。'));}
   else if(['png','jpg','jpeg'].includes(ext)){const url=URL.createObjectURL(new Blob([result.blob],{type:ext==='png'?'image/png':'image/jpeg'}));urls.add(url);const image=node('img');image.alt='当前私有预览：'+file.path;image.src=url;root.append(image);}
   else if(['csv','json','jsonl','txt','svg','obj','step','stp'].includes(ext)){const part=result.blob.slice(0,131072);const text=await part.text();if(!current(c)||preview?.attemptId!==id||reading!==readRequest)return;root.append(node('pre','',text));if(result.blob.size>131072)root.append(node('p','','这里只显示前 128 KiB；完整文件可下载核对。'));}
   else root.append(node('p','','此格式请使用“下载核对”检查完整内容。'));
   $('cvReadStatus').textContent='已读取并核对校验值：'+readFiles.size+' 个预览文件。';status('文件校验值与当前私有预览一致，请继续核对行列、对象和接收企业。');controls();
  }catch(e){report(e,c);}
 }
 async function loadRecords(c){
  const requests=[api(publications+'?maximum=50')];if(canBuild())requests.push(api(proposals+'?page=1&size=25'),api(previews+'?maximum=50'));
  const values=await Promise.all(requests);if(!current(c))return;
  $('cvPublicationRows').replaceChildren();for(const p of values[0]){const row=node('div','resource-grant-row');row.append(node('b','',p.name),node('p','',(states[p.state]||p.state)+' · '+(p.targetOrgName||p.target.enterpriseName)),button('查看进度与授权',()=>void openPublication(p.publicationId)));$('cvPublicationRows').append(row);}
  if(!values[0].length)$('cvPublicationRows').append(node('p','','当前没有可管理的发布记录。'));
  $('cvDraftRows').replaceChildren();$('cvAttemptRows').replaceChildren();
  if(values[1])for(const p of values[1].items){const row=node('div','resource-grant-row');row.append(node('span','',p.target.enterpriseName+' · 范围草稿 · 到期 '+date(p.expiresAt)),button('继续生成私有预览',()=>void continueProposal(p.proposalId)));$('cvDraftRows').append(row);}
  if(values[2])for(const p of values[2]){const row=node('div','resource-grant-row');row.append(node('span','',(states[p.state]||p.state)+' · '+(p.jobStatus||'')),button(p.publicationId?'查看发布记录':'查看私有预览',()=>void(p.publicationId?openPublication(p.publicationId):openAttempt(p.attemptId))));$('cvAttemptRows').append(row);}
 }
 async function openAttempt(id,c=context()){
  try{
   const v=await api(previews+'/'+id);if(!current(c))return;
   const p=await api(proposals+'/'+v.proposalId);if(!current(c))return;
   clearActive();proposal=p;preview=v;pending={kind:'preview',id};$('cvBuilder').hidden=true;renderPreview();poll();
  }catch(e){report(e,c);}
 }
 async function reviewPublication(event){
  event.preventDefault();clearReview();const c=context(),form=event.currentTarget;
  try{
   if(!preview||preview.state!=='AWAITING_REVIEW'||!readFiles.size||!form.elements.previewChecked.checked)throw Error('请先打开实际预览，并确认已核对范围。');
   const operations=[...$('cvOperations').querySelectorAll('input:checked')].map(x=>x.value);if(!operations.length)throw Error('请逐项选择接收企业可执行的动作。');
   if(preview.preview.hasOriginalByteCopies&&operations.includes('data.view.export')&&!operations.includes('data.raw.download'))throw Error(errors.ORIGINAL_BYTE_VIEW_EXPORT_REQUIRES_RAW_OPERATION);
   if(operations.some(op=>!auth.has(op)))throw Error('当前发布岗位缺少所选消费动作的业务能力，请调整动作或由有资格的保管人处理。');
   const config={timezone:form.elements.timezone.value};if(form.elements.timeColumn.value)config.timeColumn=form.elements.timeColumn.value;const entities=lines(form.elements.entityColumns.value);if(entities.length)config.entityColumns=entities;
   setBusy(true);const result=await api(publications+'/review',{method:'POST',json:{attemptId:preview.attemptId,name:form.elements.name.value,operations,validFrom:instant(form.elements.validFrom),validTo:instant(form.elements.validTo),reason:form.elements.reason.value,indexConfig:config}});
   if(!current(c))return;review=result;const r=result.review,root=$('cvApprovalDetails');root.replaceChildren();
   line(root,'视图名称',r.name);line(root,'接收组织',r.targetOrgName||r.target.enterpriseName);line(root,'业务企业',r.target.enterpriseName);line(root,'授权动作',r.consumerOperations.map(x=>ops.find(o=>o[0]===x)?.[1]||x).join('、'));
   line(root,'开始时间',date(r.validFrom));line(root,'结束时间',date(r.validTo));line(root,'实际文件数',r.files.length);line(root,'完整文件副本',r.hasOriginalByteCopies?'包含；请核对原件下载与导出动作':'不包含');
   line(root,'本次确认有效至',date(result.expiresAt));line(root,'审核校验值',result.reviewSha256);$('cvApprovalPanel').hidden=false;$('cvConfirmForm').reset();status('请逐项核对本次发布与授权，再输入接收组织名称确认。');
  }catch(e){report(e,c);}finally{if(current(c))setBusy(false);}
 }
 async function confirmPublication(event){
  event.preventDefault();const c=context(),r=review,form=event.currentTarget;if(busy)return;
  try{
   if(!r||Date.now()>=Date.parse(r.expiresAt))throw Error('本次审核已过期，请重新核对。');
   if(!form.elements.confirmed.checked||form.elements.targetName.value!==(r.review.targetOrgName||r.review.target.enterpriseName))throw Error('请输入当前接收组织名称并确认本次范围。');
   setBusy(true);pending={kind:'publication',id:r.review.publicationId};status('已提交本次批准，正在复制并建立独立索引…');
   const result=await api(publications+'/confirm',{method:'POST',json:{reviewId:r.reviewId,expectedReviewSha256:r.reviewSha256,confirm:true}});if(!current(c))return;
   clearReview();publication=result;preview=null;$('cvPreviewPanel').hidden=true;renderPublication();poll();await loadRecords(c);
  }catch(e){report(e,c);}finally{if(current(c))setBusy(false);}
 }
 function renderPublication(){
  if(!publication)return;const p=publication,root=$('cvProgress');$('cvProgressPanel').hidden=false;root.replaceChildren();
  line(root,'视图名称',p.name);line(root,'接收企业',(p.targetOrgName?p.targetOrgName+' · ':'')+p.target.enterpriseName);line(root,'当前状态',states[p.state]||p.state);
  if(p.jobStatus)line(root,'索引任务',p.jobStatus);line(root,'授权动作',p.consumerOperations.map(x=>ops.find(o=>o[0]===x)?.[1]||x).join('、'));
  line(root,'开始时间',date(p.validFrom));line(root,'结束时间',date(p.validTo));line(root,'本次接收企业策略',p.consumerGrantCurrentlyEffective?'当前有效；具体成员还须具备相应业务能力':'当前尚未生效或已失效');
  line(root,'尚未撤销的授权记录',p.activeGrantCount);$('cvStartIndex').hidden=p.state!=='RAW_PUBLISHED';$('cvRevokeForm').hidden=p.state!=='READY';
  $('cvProgressNotice').textContent=p.state==='READY'?(p.consumerGrantCurrentlyEffective?'已完成独立版本、索引和校验，接收企业可按所选动作及成员岗位使用。':'文件与索引已就绪，当前策略未开放读取；请核对有效期、企业身份和授权记录。'):p.state==='REVOKED'?'本视图授权已撤销，冻结文件、索引和历史记录保留。':['FAILED','CANCELED'].includes(p.state)?'本次未向接收企业开放新授权。记录保留；重新发布请重新生成私有预览。':'接收企业尚不能使用本次视图。仅在任务成功、索引预热及全部文件校验通过后开放。';
  status(['RAW_PUBLISHED','INDEXING'].includes(p.state)?'发布流程仍在进行，请等待当前记录更新。':$('cvProgressNotice').textContent);controls();
 }
 async function openPublication(id,c=context()){
  try{const p=await api(publications+'/'+id);if(!current(c))return;clearActive();publication=p;pending={kind:'publication',id};$('cvBuilder').hidden=true;renderPublication();poll();}catch(e){report(e,c);}
 }
 function poll(){
  stopPolling();const c=context();if(!pending)return;
  timer=setTimeout(async()=>{
   if(!current(c)||document.visibilityState!=='visible')return;
   try{
    const ref=pending;if(!ref)return;
    const value=await api((ref.kind==='preview'?previews:publications)+'/'+ref.id);
    if(!current(c)||pending!==ref)return;
    if(ref.kind==='preview'){preview=value;renderPreview();if(value.publicationId){await openPublication(value.publicationId,c);return;}}
    else{publication=value;renderPublication();}
    if(value.state==='BUILDING'||value.state==='INDEXING'||value.state==='RAW_PUBLISHED')poll();
    else{await loadRecords(c);if(current(c))controls();}
   }catch(e){report(e,c);if(current(c))status('当前状态暂未确认，请刷新已有记录。系统不会自动重复提交发布。');}
  },2500);
 }
 async function revoke(event){
  event.preventDefault();if(busy||publication?.state!=='READY')return;const c=context(),p=publication,form=event.currentTarget;
  try{if(!form.elements.confirmed.checked)throw Error('请确认撤销本视图的全部活跃授权。');setBusy(true);const result=await api(publications+'/'+p.publicationId+'/revoke',{method:'POST',json:{expectedVersion:p.version,reason:form.elements.reason.value,confirm:true}});if(!current(c))return;
   if(result.publicationId!==p.publicationId||result.state!=='REVOKED'||result.version!==p.version+1)throw Error('撤销回执与本次请求不一致，请刷新当前记录。');
   clearActive();$('cvBuilder').hidden=true;pending={kind:'revoked-receipt',id:p.publicationId};status('分享已撤销，冻结文件、索引和历史记录保留。可查看的记录以当前授权为准。');
   await loadRecords(c);if(current(c))void auth.check({force:true});}
  catch(e){report(e,c);}finally{if(current(c))setBusy(false);}
 }
 async function refresh(){
  if(busy)return;const c=context();try{if(pending?.kind==='publication')await openPublication(pending.id,c);else if(pending?.kind==='preview')await openAttempt(pending.id,c);await loadRecords(c);}catch(e){report(e,c);}
 }
 document.addEventListener('DOMContentLoaded',()=>{
  for(const [code,label]of ops){const l=node('label'),i=node('input');i.type='checkbox';i.value=code;i.checked=['data.catalog.read','data.preview','data.search','data.qa'].includes(code);i.onchange=clearReview;l.append(i,node('span','',label));$('cvOperations').append(l);}
  for(const id of ['controlledViewsOpen','controlledViewsOpenManagement','controlledViewsOpenProvider'])$(id).onclick=()=>void open();
  $('cvClose').onclick=()=>close();$('controlledViewDialog').addEventListener('cancel',e=>{e.preventDefault();close();});$('controlledViewDialog').addEventListener('close',()=>{if(proposal||preview||publication||selected.size)reset();});
  $('cvSourceSearch').onsubmit=e=>{e.preventDefault();sourcePage=1;clearActive();void loadSources(context()).catch(x=>report(x));};
  $('cvSource').onchange=()=>void changeSource();$('cvTarget').onchange=()=>{selected.clear();$('cvRules').replaceChildren();dirty();renderFiles();status('接收企业已改变，请重新选择明确范围。');};
  $('cvScopeMode').onchange=()=>{selected.clear();$('cvRules').replaceChildren();dirty();renderFiles();status('范围方式已改变，请重新核对范围并生成私有预览。');};
  $('cvRulesForm').onsubmit=e=>void generate(e);$('cvRulesForm').addEventListener('input',e=>{if(e.target!==$('cvTarget'))dirty();});
  $('cvPublicationForm').onsubmit=e=>void reviewPublication(e);$('cvPublicationForm').addEventListener('input',clearReview);$('cvConfirmForm').onsubmit=e=>void confirmPublication(e);$('cvRevokeForm').onsubmit=e=>void revoke(e);
  $('cvSourcePrevious').onclick=()=>{if(sourcePage>1){sourcePage--;void loadSources(context()).catch(e=>report(e));}};$('cvSourceNext').onclick=()=>{sourcePage++;void loadSources(context()).catch(e=>report(e));};
  $('cvFilePrevious').onclick=()=>{if(filePage>1){filePage--;void loadFiles(context()).catch(e=>report(e));}};$('cvFileNext').onclick=()=>{filePage++;void loadFiles(context()).catch(e=>report(e));};
  $('cvNewDraft').onclick=()=>{clearActive();$('cvBuilder').hidden=false;controls();};$('cvRefresh').onclick=()=>void refresh();
  $('cvStartIndex').onclick=async()=>{const c=context();if(!publication||busy)return;try{setBusy(true);publication=await api(publications+'/'+publication.publicationId+'/start-index',{method:'POST',json:{}});if(current(c)){renderPublication();poll();}}catch(e){report(e,c);}finally{if(current(c))setBusy(false);}};
  window.addEventListener('dataspace-identity',event=>{
   const reason=event.detail?.reason;
   if(reason==='permissions-changed'){const ref=pending,wasOpen=$('controlledViewDialog').open,prior=owner;close({keepResume:true});resume=wasOpen&&ref?{ref,owner:prior}:null;}
   else if(reason==='permissions-verified'&&resume&&resume.owner===key()&&allowed()){const ref=resume.ref;resume=null;void open(ref);}
   else if(reason==='identity-verified'&&uiRevision===auth.revision&&owner===key()){controls();}
   else{resume=null;close();controls();}
  });
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState!=='visible'){stopPolling();revokeUrls();}else if($('controlledViewDialog').open)poll();});
  setInterval(()=>{if(review&&Date.now()>=Date.parse(review.expiresAt)){clearReview();status('本次审核确认已过期，请重新核对。');controls();}},1000);
  window.mdsControlledViews={open:()=>open(),refresh};controls();
 });
})();