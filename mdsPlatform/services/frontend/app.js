const $ = (s, c = document) => c.querySelector(s);
const $$ = (s, c = document) => [...c.querySelectorAll(s)];
const state = {
  api: localStorage.getItem('dataSpaceApi') || '/api/data-space',
  snapshot: null,
  products: [],
  activeBusiness: localStorage.getItem('activeBusiness') || ''
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
  return fetch(state.api.replace(/\/$/, '') + path, options).then(async r => {
    if (!r.ok) throw new Error(await r.text() || `HTTP ${r.status}`);
    return r.json();
  });
}
function json(path, body) {
  return api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}
function formData(form) { return Object.fromEntries(new FormData(form).entries()); }
function option(value, label) { return `<option value="${esc(value)}">${esc(label || value)}</option>`; }

function setDefaultTimes() {
  const d = new Date(Date.now() + 2 * 60 * 1000);
  const pad = n => String(n).padStart(2, '0');
  const local = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  $('#sendTime').value ||= local;
  $('#expectedSendTime').value ||= local.replace('T', ' ');
}

async function load(showToast = false) {
  try {
    state.snapshot = await api('/snapshot');
    state.products = state.snapshot.products || [];
    $('#health').textContent = '后端已连接';
    $('#health').className = 'badge';
    renderAll();
    if (showToast) toast('已同步产业链数据空间真实接口');
  } catch (e) {
    $('#health').textContent = '后端未连接';
    $('#health').className = 'badge muted';
    toast('无法连接后端：' + e.message.slice(0, 120));
  }
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
  renderDeliveries();
}

function businessDomains() { return state.snapshot?.businessDomains || []; }
function chainOwner() { return state.snapshot?.chainOwner || { name: '海尔智家', count: 43, title: '链主企业 / 数据消费方' }; }

function renderBusinessTree() {
  const rows = businessDomains();
  $('#businessTree').innerHTML = rows.map(x => `
    <button class="tree-node ${state.activeBusiness === x.code ? 'active' : ''}" data-business="${esc(x.code)}">
      <span class="branch">├</span><span class="caret">▸</span><span class="check">✓</span><span class="level">L1</span>
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
      searchProducts(false);
    };
  });
}

function renderKpis() {
  const k = state.snapshot?.kpis || {};
  const owner = chainOwner();
  const cards = [
    ['链主企业', owner.name || '海尔智家', '数据消费方'],
    ['业务供给域', k.businessDomains || businessDomains().length || 0, 'PROVIDER'],
    ['数据资源', k.sources || 0, '已接入'],
    ['资源目录', k.catalogs || 0, '可检索'],
    ['索引 READY', k.readyDatasets || 0, '治理数据集'],
    ['样本总量', fmt.format(k.totalSamples || 0), `质量 ${k.avgQuality || 0}%`]
  ];
  $('#kpis').innerHTML = cards.map(i => `<div class="kpi"><span>${i[0]}</span><b>${i[1]}</b><small>${i[2]}</small></div>`).join('');
}

function renderFlow() {
  const items = [
    ['注册认证', '海尔智家作为链主消费方，各业务域完成数据空间注册与认证。'],
    ['业务域选择', '营销、服务、制造、质量、供应链等供给方创建资源时必须选择业务域。'],
    ['数据资源接入', '支持冰箱图片、文本、语音转写、质检记录、供应链订单等多模态数据。'],
    ['资源目录', '以家电产业链目录、标签和可见范围进入统一检索。'],
    ['治理与索引', '对原始数据清洗、去重、标准化，形成治理数据集并构建索引。'],
    ['产品发布', '将冰箱质量、供应链、售后服务等数据集注册为数据产品。'],
    ['合约推送', '海尔智家发起消费意向，双方确认数据源和发送时间后自动推送。']
  ];
  $('#flow').innerHTML = items.map((x, i) => `<div class="step"><em>${i + 1}</em><b>${x[0]}</b><p>${x[1]}</p></div>`).join('');
}

function renderEvents() {
  const events = state.snapshot?.events || [];
  $('#events').innerHTML = events.slice(0, 10).map(e => `
    <div class="event"><div><b>${esc(e.title)}</b><p>${esc(e.detail)}</p></div><div>${status(e.status)}<p>${esc(e.createdAt)}</p></div></div>
  `).join('') || '<div class="empty">暂无事件</div>';
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
  const spaces = (s.spaces || []).filter(x => x.authStatus === 'VERIFIED' && x.role === 'PROVIDER');
  const sources = s.sources || [];
  const datasets = s.datasets || [];
  const products = (s.products || []).filter(x => x.status === 'PUBLISHED');
  const intents = (s.intents || []).filter(x => x.status !== 'CONTRACTED');

  const domainOptions = domains.map(x => option(x.code, x.name)).join('');
  $('#sourceBusinessSelect').innerHTML = domainOptions;
  $('#spaceBusinessSelect').innerHTML = domainOptions;
  $('#productBusiness').innerHTML = '<option value="">全部业务域</option>' + domainOptions;
  if (state.activeBusiness) {
    $('#sourceBusinessSelect').value = state.activeBusiness;
    $('#spaceBusinessSelect').value = state.activeBusiness;
    $('#productBusiness').value = state.activeBusiness;
  }

  $('#sourceSpaceSelect').innerHTML = spaces.map(x => option(x.spaceId, `${x.businessName || '业务域'} / ${x.orgName}`)).join('');
  syncSpaceByBusiness();
  $('#sourceBusinessSelect').onchange = () => syncSpaceByBusiness();
  $('#sourceSpaceSelect').onchange = () => {
    const sp = spaces.find(x => x.spaceId === $('#sourceSpaceSelect').value);
    if (sp?.businessCode) $('#sourceBusinessSelect').value = sp.businessCode;
  };

  ['uploadSourceSelect', 'catalogSourceSelect', 'governSourceSelect'].forEach(id => {
    $('#' + id).innerHTML = sources.map(x => option(x.sourceId, `${x.businessName || '-'} / ${x.sourceName} / ${x.modalityType}`)).join('');
  });
  ['indexDatasetSelect', 'publishDatasetSelect'].forEach(id => {
    $('#' + id).innerHTML = datasets.map(x => option(x.datasetId, `${x.businessName || '-'} / ${x.datasetName} / 索引 ${x.indexStatus}`)).join('');
  });
  $('#intentProductSelect').innerHTML = products.map(x => option(x.productId, `${x.businessName || '-'} / ${x.productName}`)).join('');
  $('#contractIntentSelect').innerHTML = intents.map(x => option(x.intentId, `${x.consumerName} → ${x.businessName || '-'} / ${x.productName}`)).join('') || '<option value="">暂无待确认意向</option>';
}

function syncSpaceByBusiness() {
  const businessCode = $('#sourceBusinessSelect')?.value;
  const spaces = (state.snapshot?.spaces || []).filter(x => x.authStatus === 'VERIFIED' && x.role === 'PROVIDER');
  const matched = spaces.find(x => x.businessCode === businessCode);
  if (matched) $('#sourceSpaceSelect').value = matched.spaceId;
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
    const sample = x.sampleCount ? fmt.format(x.sampleCount) : '-';
    const quality = x.qualityScore ? `${x.qualityScore}%` : '-';
    const idx = x.indexStatus || '-';
    const stt = x.status || x.governanceStatus || '-';
    return `<tr><td><b>${esc(name)}</b><p>${esc(x.kind)} · ${esc(x.catalogId || x.datasetId)}</p></td><td><span class="pill">${esc(x.businessName || '-')}</span></td><td>${esc(source.sourceName || x.sourceId)}</td><td><span class="pill">${esc(x.modalityType)}</span></td><td>${sample}</td><td>${quality}</td><td>${esc(idx)}</td><td>${status(stt)}</td></tr>`;
  }).join('') || '<tr><td colspan="8"><div class="empty">暂无匹配目录或数据集</div></td></tr>';
}

function renderProducts(products) {
  $('#productGrid').innerHTML = (products || []).map(p => `
    <article class="product">
      <header><div><p class="eyebrow">${esc(p.businessName || '业务域')} · ${esc(p.providerName)}</p><h3>${esc(p.productName)}</h3></div>${status(p.status)}</header>
      <p>${esc(p.description)}</p>
      <div class="meta">
        <div><span>业务</span><b>${esc(p.businessName || '-')}</b></div>
        <div><span>模态</span><b>${esc(p.modalityType)}</b></div>
        <div><span>质量</span><b>${esc(p.qualityScore)}%</b></div>
        <div><span>权益</span><b>${esc(p.rights)}</b></div>
      </div>
      <button class="btn primary" data-product="${esc(p.productId)}">海尔智家申请消费</button>
    </article>
  `).join('') || '<div class="empty">暂无匹配产品</div>';
  $$('[data-product]').forEach(b => b.onclick = () => { location.hash = '#market'; $('#intentProductSelect').value = b.dataset.product; toast('已选中数据产品，可提交消费意向'); });
}

function renderContracts() {
  const rows = state.snapshot?.contracts || [];
  $('#contractTable').innerHTML = rows.map(c => `
    <tr>
      <td><b>${esc(c.contractName)}</b><p>${esc(c.contractId)}</p></td>
      <td>${esc(c.businessName || '-')}<p>${esc(c.productName)}</p></td>
      <td>${esc(c.providerName)}<p>→ ${esc(c.consumerName)}</p></td>
      <td>${esc(c.sendTime)}</td><td>≥ ${esc(c.qualityThreshold)}%</td><td>${status(c.pushStatus)}</td>
      <td>${c.pushStatus !== 'DELIVERED' ? `<button class="btn link" data-dispatch="${esc(c.contractId)}">立即推送</button>` : '已完成'}</td>
    </tr>
  `).join('') || '<tr><td colspan="7"><div class="empty">暂无合约</div></td></tr>';
  $$('[data-dispatch]').forEach(b => b.onclick = async () => { await api(`/contracts/${b.dataset.dispatch}/dispatch`, { method: 'POST' }); toast('已触发合约推送检查'); load(); });
}

function renderDeliveries() {
  const rows = state.snapshot?.deliveries || [];
  $('#deliveries').innerHTML = rows.map(d => `
    <div class="compact"><div><b>${esc(d.businessName || '-')} / ${esc(d.productName)}</b><p>${esc(d.packageName)} · ${esc(d.receiverEndpoint)}</p></div><div>${status(d.deliveryStatus)}<p>${esc(d.deliveredAt)}</p></div></div>
  `).join('') || '<div class="empty">暂无交付记录；到达发送时间后会自动生成。</div>';
}

async function searchProducts(showToast = true) {
  const kw = encodeURIComponent($('#productKeyword').value || '');
  const modality = encodeURIComponent($('#productModality').value || '');
  const businessCode = encodeURIComponent($('#productBusiness').value || state.activeBusiness || '');
  const rows = await api(`/products/search?keyword=${kw}&modalityType=${modality}&businessCode=${businessCode}`);
  renderProducts(rows);
  if (showToast) toast(`找到 ${rows.length} 个数据产品`);
}

function bindNav() {
  function go() {
    const id = (location.hash || '#dashboard').slice(1);
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
  $('#demoDispatchBtn').onclick = async () => { await api('/contracts'); toast('已检查到期合约'); load(); };
  $('#spaceForm').onsubmit = async e => { e.preventDefault(); const r = await json('/spaces/register', formData(e.currentTarget)); toast(`空间已注册：${r.spaceId}，可点击认证`); load(); };
  $('#sourceForm').onsubmit = async e => { e.preventDefault(); syncSpaceByBusiness(); const r = await json('/sources', formData(e.currentTarget)); toast(`数据资源已接入：${r.businessName} / ${r.sourceName}`); load(); };
  $('#uploadForm').onsubmit = async e => {
    e.preventDefault();
    const fd = new FormData(e.currentTarget);
    const sourceId = fd.get('sourceId');
    const files = e.currentTarget.elements.files.files;
    if (!files.length) return toast('请选择至少一个文件');
    const payload = new FormData();
    [...files].forEach(f => payload.append('files', f));
    const r = await api(`/sources/${sourceId}/files`, { method: 'POST', body: payload });
    const formats = Object.entries(r.source?.formatCounts || {}).map(([k, v]) => `${k}:${v}`).join('、');
    toast(formats ? `${r.message}（${formats}）` : r.message);
    load();
  };
  $('#catalogForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const sourceId = v.sourceId; delete v.sourceId; await json(`/sources/${sourceId}/catalogs`, v); toast('资源目录已创建'); load(); };
  $('#governForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const sourceId = v.sourceId; delete v.sourceId; await json(`/sources/${sourceId}/govern`, v); toast('治理完成，已形成数据集'); load(); };
  $('#indexForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const datasetId = v.datasetId; delete v.datasetId; await json(`/datasets/${datasetId}/build-index`, v); toast('索引构建完成，状态已 READY'); load(); };
  $('#publishForm').onsubmit = async e => { e.preventDefault(); const v = formData(e.currentTarget); const datasetId = v.datasetId; delete v.datasetId; await json(`/datasets/${datasetId}/publish`, v); toast('数据产品已发布并注册到产业链数据空间'); load(); };
  $('#searchBtn').onclick = () => searchProducts(true);
  $('#productKeyword').onkeydown = e => { if (e.key === 'Enter') searchProducts(true); };
  $('#productModality').onchange = () => searchProducts(true);
  $('#productBusiness').onchange = e => { state.activeBusiness = e.target.value; localStorage.setItem('activeBusiness', state.activeBusiness); renderBusinessTree(); searchProducts(true); };
  $('#intentForm').onsubmit = async e => { e.preventDefault(); const r = await json('/intents', formData(e.currentTarget)); toast(`消费意向已提交：${r.intentId}`); load(); location.hash = '#contracts'; };
  $('#contractForm').onsubmit = async e => { e.preventDefault(); const r = await json('/contracts', formData(e.currentTarget)); toast(`合约已生成：${r.contractId}，系统将按发送时间自动推送`); load(); };
  $('#catalogKeyword').oninput = renderCatalogTable;
  $('#catalogStatus').onchange = renderCatalogTable;
}

document.addEventListener('DOMContentLoaded', () => {
  setDefaultTimes();
  bindNav();
  bindForms();
  load();
  setInterval(() => load(), 12000);
});
