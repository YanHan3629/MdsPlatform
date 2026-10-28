const $ = (s, c = document) => c.querySelector(s);
const $$ = (s, c = document) => [...c.querySelectorAll(s)];
const state = {
  api: localStorage.getItem('dataSpaceApi') || '/api/data-space',
  snapshot: null,
  products: [],
  activeBusiness: localStorage.getItem('activeBusiness') || '',
  loadSequence:0,searchSequence:0,marketSearchActive:false,marketPage:1
};
const fmt = new Intl.NumberFormat('zh-CN');

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function status(v) { return `<span class="status ${esc(v)}">${esc(v)}</span>`; }
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('show'), 2600);
}
function api(path, options = {}) {
  return window.dataSpaceSession.requestBusiness(path,options);
}
function json(path, body) {
  return api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}
function formData(form) { return Object.fromEntries(new FormData(form).entries()); }
function option(value, label) { return `<option value="${esc(value)}">${esc(label || value)}</option>`; }

function setDefaultTimes() {
  const d = new Date(Date.now() + 2 * 60 * 1000);
  const pad = n => String(n).padStart(2, '0');
  const parts=Object.fromEntries(new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(d).map(p=>[p.type,p.value]));
  const local=`${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
  $('#sendTime').value ||= local;
  $('#expectedSendTime').value ||= local;
  $('#contractValidFrom').value ||= beijingInput(new Date(Date.now()-60000));
  $('#contractValidTo').value ||= beijingInput(new Date(Date.now()+24*60*60*1000));
}
function shanghaiTime(value){if(!/^\d{4}-\d\d-\d\dT\d\d:\d\d(?::\d\d)?$/.test(value))throw new Error('请填写有效的北京时间');return value+(value.length===16?':00':'')+'+08:00';}

async function load(showToast = false) {
  if(state.loading&&!showToast)return;
  const sequence=++state.loadSequence;
  if(!window.dataSpaceSession?.authenticated){state.snapshot=null;state.products=[];renderAll();$('#health').textContent='请登录数据空间';return;}
  try {
    state.loading=true;
    const [snapshot,endpoints]=await Promise.all([api('/snapshot'),api('/receiver-endpoints')]);if(sequence!==state.loadSequence)return;
    snapshot.receiverEndpoints=endpoints;
    state.snapshot=snapshot;
    window.dataSpaceSession.updateIndexingTasks(snapshot.tasks||[]);
    if(!state.marketSearchActive){state.products=(snapshot.products||[]).filter(p=>p.status==='PUBLISHED');$('#marketSearchStatus').textContent=`已读取 ${state.products.length} 个当前可见产品摘要。`;}
    $('#health').textContent = '后端已连接';
    $('#health').className = 'badge';
    renderAll();
    if (showToast) toast('已同步产业链数据空间真实接口');
  } catch (e) {
    if(sequence!==state.loadSequence||e.name==='AbortError')return;
    $('#health').textContent = '后端未连接';
    $('#health').className = 'badge muted';
    if(showToast)toast('无法读取数据空间：' + e.message.slice(0, 120));
  } finally {if(sequence===state.loadSequence)state.loading=false;}
}

function renderAll() {
  renderBusinessTree();
  renderKpis();
  renderFlow();
  renderEvents();
  renderSpaces();
  renderSelects();
  renderCatalogTable();
  renderProducts(state.products || []);
  renderContracts();
  renderApplications();
  renderDeliveries();
}

function businessDomains() { return state.snapshot?.businessDomains || []; }
function chainOwner() { return state.snapshot?.chainOwner || { name: '未登录', title: '当前账号组织' }; }

function renderBusinessTree() {
  const rows = businessDomains();
  $('#currentOrgName').textContent=chainOwner().name;
  $('#businessTree').innerHTML = rows.map(x => `
    <button class="tree-node ${state.activeBusiness === x.code ? 'active' : ''}" data-business="${esc(x.code)}">
      <span class="branch">├</span><span class="caret">▸</span><span class="level">分类</span>
      <span class="name">${esc(x.name)}</span>
    </button>
  `).join('');
  $$('[data-business]').forEach(btn => {
    btn.onclick = () => {
      state.activeBusiness = state.activeBusiness === btn.dataset.business ? '' : btn.dataset.business;
      localStorage.setItem('activeBusiness', state.activeBusiness);
      renderBusinessTree();
      renderSelects();
      if ($('#productBusiness')) $('#productBusiness').value = state.activeBusiness;
      searchProducts(false).catch(e=>{if(e.name!=='AbortError')toast(e.message);});
    };
  });
}

function renderKpis() {
  const k = state.snapshot?.kpis || {};
  const owner = chainOwner();
  const cards = [
    ['当前组织', owner.name || '待绑定', '身份来自登录账号'],
    ['数据集', k.datasets ?? 0, '默认有效版本，不累计历史副本'],
    ['原始文件', fmt.format(k.fileCount ?? 0), '业务记录数：未统计'],
    ['索引 READY', k.readyDatasets ?? 0, '以真实发布任务为准'],
    ['关联数据源', k.sources ?? 0, '已有数据无需重传'],
    ['可见已发布产品', k.products ?? 0, `质量已测 ${k.qualityMeasuredDatasets??0}/${k.datasets??0}，${k.qualityBlockedDatasets??0} 个范围受阻`]
  ];
  $('#kpis').innerHTML = cards.map(i => `<div class="kpi"><span>${esc(i[0])}</span><b>${esc(i[1])}</b><small>${esc(i[2])}</small></div>`).join('');
}

function renderFlow() {
  const items = [
    ['账号与组织', '使用当前平台组织；业务域是分类，不代表已认证企业。'],
    ['数据关联', '复用已有数据集，或通过共享上传发布入口接入新版本。'],
    ['版本与目录', '目录描述真实数据，原始快照发布后保持不可变。'],
    ['质量检查', '记录实际检查、覆盖率和问题定位，不接受人工填分。'],
    ['真实索引', '后台构建、校验、预热后切换，失败保留旧索引。'],
    ['产品与申请', '产品冻结版本范围；消费主体来自当前账号。'],
    ['授权与交付', '双方确认技术授权，真实接收核验通过后才算已交付。']
  ];
  $('#flow').innerHTML = items.map((x, i) => `<div class="step"><em>${i + 1}</em><b>${x[0]}</b><p>${x[1]}</p></div>`).join('');
}

function renderEvents() {
  const events = state.snapshot?.events || [];
  $('#events').innerHTML = events.slice(0, 10).map(e => `
    <div class="event"><div><b>${esc(e.title)}</b><p>${esc(typeof e.detail==='object'?JSON.stringify(e.detail):e.detail)}</p><small>${esc(e.actor||'后台任务')}</small></div><div><p>${esc(e.createdAt)}</p></div></div>
  `).join('') || '<div class="empty">暂无事件</div>';
  const tasks=state.snapshot?.tasks||[];
  $('#events').insertAdjacentHTML('beforeend',tasks.slice(0,10).map(t=>`<div class="event"><div><button class="btn link" data-task-dataset="${esc(t.datasetId)}">索引任务 ${esc(t.taskId)}</button><p>${esc(t.stage)}${t.error?' · '+esc(t.error):''}</p></div><div>${status(t.status)}<p>${esc(t.updatedAt)}</p></div></div>`).join(''));
  $$('[data-task-dataset]').forEach(b=>b.onclick=()=>{location.hash='#provider';$('#indexDatasetSelect').value=b.dataset.taskDataset;$('#indexForm').scrollIntoView({block:'center'});});
}

function renderSpaces() {
  const spaces = state.snapshot?.spaces || [];
  $('#spaces').innerHTML = spaces.map(s => `
    <div class="compact">
      <div><b>${esc(s.orgName)}</b><p>${esc(s.role)} · ${esc(s.businessName || '未绑定业务')} · ${esc(s.spaceId)}</p></div>
      <div>${status(s.authStatus)}${s.authStatus === 'PENDING_AUTH' ? `<button class="btn link" data-verify="${esc(s.spaceId)}">认证</button>` : ''}</div>
    </div>
  `).join('');
  $$('[data-verify]').forEach(b => b.onclick = async () => { await api(`/spaces/${b.dataset.verify}/verify`, { method: 'POST' }); toast('认证通过'); load(); });
}

function renderSelects() {
  const s = state.snapshot || {};
  const domains = businessDomains();
  const spaces = s.spaces || [];
  const sources = s.sources || [];
  const datasets = s.datasets || [];
  const products = (s.products || []).filter(x => x.status === 'PUBLISHED');
  const intents = (s.intents || []).filter(x => x.status !== 'CONTRACTED');

  const domainOptions = domains.map(x => option(x.code, x.name)).join('');
  const setOptions=(id,html)=>{const element=$('#'+id);if(!element)return;const previous=element.value;element.innerHTML=html;if([...element.options].some(o=>o.value===previous))element.value=previous;};
  setOptions('sourceBusinessSelect',option('','待补充业务域')+domainOptions);
  setOptions('productBusiness',option('','全部业务域')+domainOptions);
  setOptions('sourceDatasetSelect',option('','选择已有真实数据集')+datasets.filter(d=>d.accessMode==='OWNER').map(d=>option(d.datasetId,`${d.datasetName} / ${d.versionName||'无版本'}`)).join(''));

  ['uploadSourceSelect', 'catalogSourceSelect', 'governSourceSelect'].forEach(id => {
    setOptions(id,option('','选择已关联数据源')+sources.map(x => option(x.sourceId, `${x.businessName || '-'} / ${x.sourceName}`)).join(''));
  });
  ['indexDatasetSelect', 'publishDatasetSelect'].forEach(id => {
    setOptions(id,option('','选择真实数据集')+datasets.filter(d=>d.accessMode==='OWNER').map(x => option(x.datasetId, `${x.datasetName} / ${x.versionName||'无版本'} / ${x.indexStatus}`)).join(''));
  });
  setOptions('intentProductSelect',option('','选择数据产品及冻结版本')+products.filter(x=>x.providerOrgId!==chainOwner().orgId).map(x => option(x.productVersionId, `${x.productName} / ${x.versionName} / 产品修订 ${x.revision}`)).join(''));
  setOptions('contractIntentSelect',option('','选择已批准申请')+intents.filter(x=>x.status==='APPROVED'&&x.providerOrgId===chainOwner().orgId).map(x => option(x.intentId, `${x.consumerName} → ${x.productName}`)).join(''));
  setOptions('publishProductSelect',option('','创建新产品')+products.filter(x=>x.providerOrgId===chainOwner().orgId).map(x=>option(x.productId,`${x.productName} / 产品修订 ${x.revision}`)).join(''));
  if(state.endpointApplication!==$('#contractIntentSelect').value)loadContractEndpoints().catch(e=>{if(e.name!=='AbortError')toast(e.message);});
  const kinds=[...new Set(products.flatMap(p=>[...(p.contentTypes||[]),...(p.formats||[])]))].sort();
  setOptions('productModality',option('','全部类型/格式')+kinds.map(k=>option(k,k)).join(''));
}

function syncSpaceByBusiness() {
  // Ownership comes from the selected real dataset, never from a business label.
}

function renderCatalogTable() {
  const kw = ($('#catalogKeyword').value || '').toLowerCase();
  const st = $('#catalogStatus').value;
  const sources = state.snapshot?.sources || [];
  const catalogs = state.snapshot?.catalogs || [];
  const datasets = state.snapshot?.datasets || [];
  const rows = [...catalogs.map(c => ({ ...c, kind: '目录' })), ...datasets.map(d => ({ ...d, kind: '数据集' }))].filter(x => {
    const source = sources.find(s => s.sourceId === x.sourceId);
    const text = `${x.catalogName || x.datasetName} ${x.businessName || ''} ${source?.sourceName || ''} ${x.modalityType || ''}`.toLowerCase();
    const okKw = !kw || text.includes(kw);
    const okSt = !st || x.indexStatus === st || x.status === st || x.governanceStatus === st;
    return okKw && okSt;
  });
  $('#catalogTable').innerHTML = rows.map(x => {
    const source = sources.find(s => s.sourceId === x.sourceId) || {};
    const name = x.catalogName || x.datasetName;
    const sample = x.fileCount==null?'—':`${fmt.format(x.fileCount)} 份文件`;
    const quality = `${esc(x.qualityStatus||'NOT_ASSESSED')} ${x.qualityScore==null?'':esc(x.qualityScore)+'%'}${x.qualityReportId?`<p><button class="btn link" data-quality="${esc(x.qualityReportId)}">查看质量报告</button></p>`:''}`;
    const idx = x.indexStatus || '-';
    const stt = x.status || x.governanceStatus || '-';
    return `<tr><td><b>${esc(name)}</b><p>${esc(x.kind)} · ${esc(x.versionName||'')} · ${esc(x.catalogId || x.datasetId)}</p></td><td><span class="pill">${esc(x.businessName || '待补充')}</span></td><td>${esc(source.sourceName || '待关联目录')}</td><td><span class="pill">${esc(x.modalityType||'见实际数据集')}</span><p>${esc((x.formats||[]).join(' / '))}</p></td><td>${sample}<p>业务记录未统计</p></td><td>${quality}</td><td>${esc(idx)}</td><td>${status(stt)}<p>${x.kind==='数据集'?esc(x.productStatus==='PUBLISHED'?'存在已发布产品':'产品未发布'):''}</p></td></tr>`;
  }).join('') || '<tr><td colspan="8"><div class="empty">暂无匹配目录或数据集</div></td></tr>';
  $$('[data-quality]').forEach(b=>b.onclick=()=>showQuality(b.dataset.quality).catch(e=>toast(e.message)));
}

async function showQuality(id) {
  const report=await api(`/quality/${encodeURIComponent(id)}`);
  if(!window.dataSpaceSession.authenticated)return;
  const coverage=report.coverage||{};
  $('#qualityReport').innerHTML=`<p><b>${esc(report.status)} · ${esc(report.jobStatus)}</b> · 任务 ${esc(report.reportId)}</p><p>版本 ${esc(report.versionId)} · 规则 ${esc(report.rulesVersion)}</p><p>已测断言 ${esc(report.measuredCount)}，通过 ${esc(report.passedCount)}，关键阻断 ${esc(report.blockingCount)}，未评估规则范围 ${esc(coverage.unassessedRuleScopes??'尚未完成')}。</p><p>${esc(coverage.formula||'后台正在读取真实快照')}</p>${report.error?`<p>${esc(report.error)}</p>`:''}<button class="btn" id="qualityRefresh">刷新报告</button> <button class="btn" id="qualityDownload">下载完整 JSON 报告</button><details><summary>问题定位（前 100 项）</summary><pre style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(JSON.stringify((report.checks||[]).filter(c=>c.status!=='PASS'&&c.status!=='NOT_APPLICABLE').slice(0,100),null,2))}</pre></details>`;
  $('#qualityRefresh').onclick=()=>showQuality(id).catch(e=>toast(e.message));
  $('#qualityDownload').onclick=()=>{const url=URL.createObjectURL(new Blob([JSON.stringify(report,null,2)],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download=`quality-${id}.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);};
  location.hash='#provider';
}

function renderProducts(products) {
  const rows=products||[],pageCount=Math.max(1,Math.ceil(rows.length/6));state.marketPage=Math.min(pageCount,Math.max(1,state.marketPage||1));
  $('#productPage').textContent=`共 ${rows.length} 个真实产品 · 第 ${state.marketPage} / ${pageCount} 页`;
  $('#productPrev').disabled=state.marketPage<=1;$('#productNext').disabled=state.marketPage>=pageCount;
  $('#productGrid').innerHTML = rows.slice((state.marketPage-1)*6,state.marketPage*6).map(p => `
    <article class="product">
      <header><div><p class="eyebrow">${esc(p.businessName || '业务域')} · ${esc(p.providerName)}</p><h3>${esc(p.productName)}</h3></div>${status(p.status)}</header>
      <p>${esc(p.description)}</p>
      <div class="meta">
        <div><span>业务</span><b>${esc(p.businessName || '-')}</b></div>
        <div><span>类型 / 格式</span><b>${esc([...(p.contentTypes||[]),...(p.formats||[])].join(' / '))}</b></div>
        <div><span>质量</span><b>${p.qualityScore==null?'未评估':esc(p.qualityScore)+'%'}</b></div>
        <div><span>权益</span><b>${esc(p.rights)}</b></div>
      </div>
      <p>版本 ${esc(p.versionName)} · ${esc(p.fileCount)} 份文件 · 索引 ${esc(p.indexStatus)}</p>
      <p>质量 ${esc(p.qualityStatus)} · 已测 ${esc(p.qualityCoverage?.measuredAssertions??'—')}，通过 ${esc(p.qualityCoverage?.passedAssertions??'—')}，未评估规则范围 ${esc(p.qualityCoverage?.unassessedRuleScopes??'—')} · 关键阻断 ${esc(p.blockingCount??'—')}</p>
      ${p.hasAccess?`<button class="btn" data-open-product="${esc(p.productId)}" data-version="${esc(p.productVersionId)}" data-target="#retrieval">进入多模态检索</button> <button class="btn" data-open-product="${esc(p.productId)}" data-version="${esc(p.productVersionId)}" data-target="#qa">进入多模态问答</button> <button class="btn link" data-open-product="${esc(p.productId)}" data-version="${esc(p.productVersionId)}" data-target="sources">查看来源 / 版本</button>`:`<button class="btn primary" data-product="${esc(p.productVersionId)}">以当前账号申请消费</button>`}
    </article>
  `).join('') || '<div class="empty">暂无匹配的已发布产品。已有数据集不会自动上架；供给方须在工作台完成检查与产品发布。</div>';
  $$('[data-product]').forEach(b => b.onclick = () => { location.hash = '#market'; $('#intentProductSelect').value = b.dataset.product; toast('已选中数据产品，可提交消费意向'); });
  $$('[data-open-product]').forEach(b=>b.onclick=async()=>{try{const scope=await api(`/products/${b.dataset.openProduct}/versions/${b.dataset.version}/scope`);if(b.dataset.target==='sources'){$('#marketVersionDetails').innerHTML=`<h3>冻结版本与来源清单</h3><pre style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(JSON.stringify(scope,null,2))}</pre>`;}else await window.dataSpaceSession.openProductScope(scope,b.dataset.target);}catch(e){if(e.name!=='AbortError')toast(e.message);}});
}

function renderApplications(){
  const rows=state.snapshot?.intents||[],signature=JSON.stringify(rows);
  if(signature===state.applicationSignature)return;state.applicationSignature=signature;
  const drafts=Object.fromEntries($$('[data-decision-reason]').map(e=>[e.dataset.decisionReason,e.value]));
  $('#applications').innerHTML=rows.map(a=>`<div class="compact"><div><b>${esc(a.productName)} · ${esc(a.consumerName)}</b><p>数据版本 ${esc(a.versionName)} · 产品修订 ${esc(a.productRevision)}</p><p>${esc(a.intentId)} · ${esc(a.status)} · ${esc(a.expectedSendTime)}</p><p>${esc(a.purpose)}</p><p>${esc(a.decisionReason||'')}${a.decisionActor?' · '+esc(a.decisionActor):''}</p>${a.status==='PENDING'&&a.providerOrgId===chainOwner().orgId?`<textarea data-decision-reason="${esc(a.intentId)}" placeholder="审核说明；拒绝时必填">${esc(drafts[a.intentId]||'')}</textarea><button class="btn" data-decide="${esc(a.intentId)}" data-decision="APPROVED">批准申请</button> <button class="btn" data-decide="${esc(a.intentId)}" data-decision="REJECTED">拒绝申请</button>`:''}</div></div>`).join('')||'<div class="empty">暂无消费申请。批准申请后仍需双方确认技术合约，才可能生效授权。</div>';
  $$('[data-decide]').forEach(b=>b.onclick=async()=>{try{await json(`/intents/${b.dataset.decide}/decision`,{decision:b.dataset.decision,reason:$(`[data-decision-reason="${b.dataset.decide}"]`).value});toast('申请审核已持久化；尚未授予内容读取权');await load(true);}catch(e){if(e.name!=='AbortError')toast(e.message);}});
}

function renderContracts() {
  const rows = state.snapshot?.contracts || [];
  const signature=JSON.stringify(rows);if(signature===state.contractSignature)return;state.contractSignature=signature;
  const drafts=Object.fromEntries($$('[data-revoke-reason]').map(e=>[e.dataset.revokeReason,e.value]));
  $('#contractTable').innerHTML = rows.map(c => `
    <tr>
      <td><b>${esc(c.contractName)} · 修订 ${esc(c.revision)}</b><p>${esc(c.contractId)}</p><p>${esc(c.status)} · 授权 ${esc(c.authorizationStatus)}</p></td>
      <td>${esc(c.businessName || '-')}<p>${esc(c.productName)} / ${esc(c.versionName)} / 产品修订 ${esc(c.productRevision)}</p><p>${esc(c.scopeType)} · ${esc(c.fileCount)} 文件</p></td>
      <td>${esc(c.providerName)}<p>→ ${esc(c.consumerName)}</p></td>
      <td>${esc(c.sendTime)}<p>有效期 ${esc(c.validFrom)} 至 ${esc(c.validTo)}</p><p>${esc(c.receiverEndpoint)}</p></td><td>${c.qualityThreshold==null?'不另设数值门槛':'≥ '+esc(c.qualityThreshold)+'%'}<p>实测 ${esc(c.measuredQuality)}% · ${esc(c.qualityRulesVersion)}</p></td><td>${status(c.pushStatus)}<p>${esc(c.error||c.revokeReason||'')}</p></td>
      <td><button class="btn link" data-contract-history="${esc(c.contractId)}">条款记录</button>${c.hasAccess?`<button class="btn link" data-open-product="${esc(c.productId)}" data-version="${esc(c.productVersionId)}" data-target="#retrieval">进入冻结版本检索</button>`:''}${c.status==='PENDING_CONFIRMATION'&&c.consumerOrgId===chainOwner().orgId?`<button class="btn" data-confirm-contract="${esc(c.contractId)}" data-revision="${esc(c.revision)}">确认修订 ${esc(c.revision)} 并启用约定只读授权</button>`:''}${['PENDING_CONFIRMATION','CONFIRMED'].includes(c.status)?`<textarea data-revoke-reason="${esc(c.contractId)}" placeholder="撤销原因">${esc(drafts[c.contractId]||'')}</textarea><button class="btn link" data-revoke-contract="${esc(c.contractId)}">显式撤销授权及未完成交付</button>${c.providerOrgId===chainOwner().orgId?`<button class="btn link" data-revise-contract="${esc(c.contractId)}">修订计划并重新确认</button>${c.status==='CONFIRMED'?`<button class="btn link" data-dispatch="${esc(c.contractId)}">重试 / 查看后台交付计划</button>`:''}`:''}`:''}</td>
    </tr>
  `).join('') || '<tr><td colspan="7"><div class="empty">暂无合约</div></td></tr>';
  const act=fn=>async()=>{try{await fn();}catch(e){if(e.name!=='AbortError')toast(e.message);}};
  $$('[data-dispatch]').forEach(b => b.onclick = act(async()=>{const r=await api(`/contracts/${b.dataset.dispatch}/dispatch`,{method:'POST'});toast(`后台任务 ${r.taskId} · ${r.status}`);await load(true);}));
  $$('[data-confirm-contract]').forEach(b=>b.onclick=act(async()=>{await json(`/contracts/${b.dataset.confirmContract}/confirm`,{revision:Number(b.dataset.revision)});toast('已确认当前技术条款；读取权限及交付分别按约定时间生效');await load(true);await window.dataSpaceSession.refresh();}));
  $$('[data-revoke-contract]').forEach(b=>b.onclick=act(async()=>{await json(`/contracts/${b.dataset.revokeContract}/revoke`,{reason:$(`[data-revoke-reason="${b.dataset.revokeContract}"]`).value});toast('已撤销；旧平台下载入口会重新鉴权');await load(true);await window.dataSpaceSession.refresh();}));
  $$('[data-contract-history]').forEach(b=>b.onclick=act(async()=>{$('#deliveryDetails').textContent=JSON.stringify(await api(`/contracts/${b.dataset.contractHistory}/history`),null,2);}));
  $$('[data-revise-contract]').forEach(b=>b.onclick=act(async()=>{const c=rows.find(x=>x.contractId===b.dataset.reviseContract),form=$('#contractForm');form.elements.contractId.value=c.contractId;form.elements.revision.value=c.revision;$('#contractIntentSelect').value=c.intentId;$('#contractIntentSelect').disabled=true;for(const [key,value] of Object.entries({validFrom:c.validFrom,validTo:c.validTo,sendTime:c.sendTime}))form.elements[key].value=beijingInput(new Date(value));form.elements.minimumQuality.value=c.qualityThreshold??'';form.elements.purpose.value=c.purpose;await loadContractEndpoints(true);$('#contractEndpointSelect').value=c.endpointId;toast(`正在修订 ${c.contractId}；提交后消费方需要重新确认`);}));
  $$('[data-open-product]',$('#contractTable')).forEach(b=>b.onclick=act(async()=>{const scope=await api(`/products/${b.dataset.openProduct}/versions/${b.dataset.version}/scope`);await window.dataSpaceSession.openProductScope(scope,b.dataset.target);}));
}

function renderDeliveries() {
  const rows = state.snapshot?.deliveries || [];
  $('#deliveries').innerHTML = rows.map(d => `
    <div class="compact"><div><b>${esc(d.productName)} · 合约修订 ${esc(d.contractRevision)}</b><p>任务 ${esc(d.taskId)} · ${esc(d.receiverEndpoint)}</p><p>尝试 ${esc(d.attemptCount)} / ${esc(d.maxAttempts)}，下次 ${esc(d.nextAttemptAt)}</p><p>${esc(d.error||'')}</p>${d.receiptId?`<p>已核对 ${esc(d.fileCount)} 文件 / ${esc(d.sizeBytes)} bytes · ${esc(d.manifestDigest)}</p><p>${esc(d.receiverIdentity)}</p>`:''}<button class="btn link" data-delivery-json="${esc(d.taskId)}" data-kind="attempts">尝试记录</button><button class="btn link" data-delivery-json="${esc(d.taskId)}" data-kind="manifest">冻结交付清单</button>${d.receiptId?`<button class="btn link" data-delivery-json="${esc(d.taskId)}" data-kind="receipt">签名接收回执</button><button class="btn link" data-delivery-download="${esc(d.taskId)}" data-kind="receipt">下载回执 JSON</button>${d.deliveryStatus==='DELIVERED'&&d.hasPackageAccess?`<button class="btn" data-delivery-download="${esc(d.taskId)}" data-kind="package">鉴权下载实际交付包</button>`:''}`:''}</div><div>${status(d.deliveryStatus)}<p>${esc(d.deliveredAt||'尚未接收')}</p></div></div>
  `).join('') || '<div class="empty">暂无实际交付记录；只有独立接收回执核验通过后才显示已交付。</div>';
  $$('[data-delivery-json]').forEach(b=>b.onclick=async()=>{try{$('#deliveryDetails').textContent=JSON.stringify(await api(`/deliveries/${b.dataset.deliveryJson}/${b.dataset.kind}`),null,2);}catch(e){if(e.name!=='AbortError')toast(e.message);}});
  $$('[data-delivery-download]').forEach(b=>b.onclick=async()=>{try{await window.dataSpaceSession.downloadBusiness(`/deliveries/${b.dataset.deliveryDownload}/${b.dataset.kind}`,`delivery-${b.dataset.deliveryDownload}.${b.dataset.kind==='package'?'zip':'json'}`);}catch(e){if(e.name!=='AbortError')toast(e.message);}});
  $('#receiverConfigurations').innerHTML=(state.snapshot?.receiverEndpoints||[]).map(e=>`<p>${esc(e.endpointName)} · ${esc(e.adapterType)} · ${esc(e.receiverIdentity)} · ${e.enabled?'已配置':'已停用'}</p>`).join('')||'<p>当前组织尚未配置接收端。</p>';
}

function beijingInput(date){const p=Object.fromEntries(new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(date).map(p=>[p.type,p.value]));return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;}
async function loadContractEndpoints(force=false){
  const application=$('#contractIntentSelect').value;if(!force&&state.endpointApplication===application)return;
  state.endpointApplication=application;const seq=(state.endpointSequence||0)+1;state.endpointSequence=seq;
  if(!application){$('#contractEndpointSelect').innerHTML=option('','先选择已批准申请');return;}
  $('#contractEndpointSelect').innerHTML=option('','正在读取该消费组织接收配置');
  const rows=await api(`/receiver-endpoints?applicationId=${encodeURIComponent(application)}`);
  if(state.endpointSequence!==seq||$('#contractIntentSelect').value!==application)return;
  $('#contractEndpointSelect').innerHTML=option('',rows.length?'选择该消费组织的接收端':'该消费组织尚未配置本地接收端')+rows.filter(e=>e.enabled).map(e=>option(e.endpointId,e.endpointName+' / '+e.adapterType)).join('');
}

async function searchProducts(showToast = true) {
  const sequence=++state.searchSequence;state.marketSearchActive=true;
  const kw = encodeURIComponent($('#productKeyword').value || '');
  const modality = encodeURIComponent($('#productModality').value || '');
  const businessCode = encodeURIComponent($('#productBusiness').value || state.activeBusiness || '');
  $('#marketSearchStatus').textContent='正在查询已发布产品…';
  let rows;
  try{rows=await api(`/products/search?keyword=${kw}&modalityType=${modality}&businessCode=${businessCode}`);}
  catch(error){if(sequence===state.searchSequence&&error.name!=='AbortError')$('#marketSearchStatus').textContent=`查询失败：${error.message}；保留上次结果，请重试。`;throw error;}
  if(sequence!==state.searchSequence)return;
  state.products=rows;
  state.marketPage=1;$('#marketSearchStatus').textContent=`已读取 ${rows.length} 个真实产品摘要。`;
  renderProducts(rows);
  if (showToast) toast(`找到 ${rows.length} 个数据产品`);
}

function bindNav() {
  function go() {
    let id = (location.hash || '#dashboard').slice(1);
    if (!document.getElementById(id)?.classList.contains('page')) id = 'dashboard';
    const shared = $('#multimodalShared');
    if (shared) {shared.hidden=false;const first=$('.page');if(first)first.parentNode.insertBefore(shared,first);}
    $$('.page').forEach(p => p.classList.toggle('show', p.id === id));
    $$('nav a').forEach(a => a.classList.toggle('active', a.hash === '#' + id));
  }
  window.addEventListener('hashchange', go);
  go();
}

function bindForms() {
  $('#apiBase').value = state.api;
  $('#apiBase').onchange = e => { state.api = e.target.value || '/api/data-space'; localStorage.setItem('dataSpaceApi', state.api); load(true); };
  $('#reloadBtn').onclick = () => load(true);
  $('#demoDispatchBtn').onclick = () => load(true);
  $('#spaceForm').onsubmit = async e => { e.preventDefault(); const r = await json('/spaces/register', formData(e.currentTarget)); toast(`平台空间已创建：${r.spaceId}；不代表企业认证`); load(); };
  $('#sourceForm').onsubmit = async e => { e.preventDefault(); const r = await json('/sources', formData(e.currentTarget)); toast(`已关联真实数据集：${r.sourceName}，未重复上传`); load(); };
  $('#uploadForm').onsubmit=async e=>{e.preventDefault();const selected=(state.snapshot?.sources||[]).find(s=>s.sourceId===$('#uploadSourceSelect').value);await window.dataSpaceSession.openIngestion(selected?.datasetId);};
  $('#catalogForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const sourceId = v.sourceId; delete v.sourceId; await json(`/sources/${sourceId}/catalogs`, v); toast('资源目录已创建'); load(); };
  $('#governForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const sourceId = v.sourceId; delete v.sourceId;if(!v.idempotencyKey)delete v.idempotencyKey; v.rules=v.rules.trim()?JSON.parse(v.rules):{};const r=await json(`/sources/${sourceId}/govern`, v);toast(`质量任务 ${r.reportId} · ${r.jobStatus}`);await showQuality(r.reportId);load(); };
  $('#indexForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const datasetId = v.datasetId; delete v.datasetId; const r=await json(`/datasets/${datasetId}/build-index`, v); toast(`真实任务 ${r.taskId} · ${r.status} · ${r.stage}`); load(); };
  $('#publishForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const datasetId = v.datasetId; delete v.datasetId;if(!v.productId)delete v.productId;if(!v.description)delete v.description;await json(`/datasets/${datasetId}/publish`, v); toast('数据产品版本已冻结并发布'); load(); };
  const runSearch=()=>searchProducts(true).catch(error=>{if(error.name!=='AbortError')toast(error.message);});
  $('#searchBtn').onclick = runSearch;
  $('#productPrev').onclick=()=>{state.marketPage--;renderProducts(state.products);};
  $('#productNext').onclick=()=>{state.marketPage++;renderProducts(state.products);};
  $('#productKeyword').onkeydown = e => { if (e.key === 'Enter') {e.preventDefault();searchProducts(true).catch(error=>{if(error.name!=='AbortError')toast(error.message);});} };
  $('#productModality').onchange = runSearch;
  $('#productBusiness').onchange = e => { state.activeBusiness = e.target.value; localStorage.setItem('activeBusiness', state.activeBusiness); renderBusinessTree(); runSearch(); };
  $('#intentForm').onsubmit = async e => { e.preventDefault();const v=formData(e.currentTarget);v.expectedSendTime=shanghaiTime(v.expectedSendTime);const r = await json('/intents',v); toast(`消费申请已提交：${r.intentId} · ${r.status}`); load(); location.hash = '#contracts'; };
  $('#contractForm').onsubmit = async e => { e.preventDefault();const v=formData(e.currentTarget);for(const field of ['validFrom','validTo','sendTime'])v[field]=shanghaiTime(v[field]);if(!v.purpose)delete v.purpose;const id=v.contractId;delete v.contractId;if(!id)delete v.revision;else v.revision=Number(v.revision);const r=await json(id?`/contracts/${id}/revise`:'/contracts',v);toast(`技术条款修订 ${r.revision} 已提交，等待消费方确认`);await load(true); };
  $('#receiverForm').onsubmit=async e=>{e.preventDefault();const r=await json('/receiver-endpoints',formData(e.currentTarget));toast(`真实本地接收端已配置：${r.endpointName}`);await load(true);await loadContractEndpoints(true);};
  $('#contractIntentSelect').onchange=()=>loadContractEndpoints(true).catch(e=>{if(e.name!=='AbortError')toast(e.message);});
  $('#contractNew').onclick=()=>{$('#contractForm').reset();$('#contractIntentSelect').disabled=false;state.endpointApplication=null;setDefaultTimes();loadContractEndpoints(true).catch(e=>toast(e.message));};
  $('#catalogKeyword').oninput = renderCatalogTable;
  $('#catalogStatus').onchange = renderCatalogTable;
  for(const form of $$('#provider form,#market form,#contracts form')){const action=form.onsubmit;if(action)form.onsubmit=e=>Promise.resolve(action(e)).catch(error=>{if(error.name!=='AbortError')toast(error.message);});}
  for(const id of ['searchBtn','productModality','productBusiness']){const element=$('#'+id);const key=id==='searchBtn'?'onclick':'onchange';const action=element[key];if(action)element[key]=e=>Promise.resolve(action(e)).catch(error=>{if(error.name!=='AbortError')toast(error.message);});}
}

window.addEventListener('dataspace-auth',()=>{
  state.loadSequence++;state.searchSequence++;state.snapshot=null;state.products=[];state.marketSearchActive=false;
  state.marketPage=1;$('#marketSearchStatus').textContent='账号范围已切换，正在读取当前可见产品。';
  state.loading=false;
  state.applicationSignature=null;state.contractSignature=null;state.endpointApplication=null;state.endpointSequence=(state.endpointSequence||0)+1;$('#contractIntentSelect').disabled=false;$('#qualityReport').textContent='登录后可查看有权访问的质量报告。';$('#marketVersionDetails').replaceChildren();$('#deliveryDetails').textContent='选择当前账号有权查看的交付任务。';
  for(const form of $$('#provider form,#market form,#contracts form'))form.reset();
  $('#productKeyword').value='';setDefaultTimes();load();
});

document.addEventListener('DOMContentLoaded', () => {
  setDefaultTimes();
  bindNav();
  bindForms();
  load();
  setInterval(() => load(), 12000);
});
