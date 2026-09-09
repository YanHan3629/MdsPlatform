(() => {
  let token = sessionStorage.getItem('qaToken') || '';
  let account = sessionStorage.getItem('qaAccount') || '';
  let datasets = [];
  let busy = false;
  const base = () => state.api.replace(/\/$/, '');
  const authBase = () => base().replace(/\/data-space$/, '/auth');

  async function request(path, options = {}) {
    const response = await fetch(base() + '/qa' + path, {
      ...options, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers }
    });
    if (!response.ok) {
      if (response.status === 401) { clearSession(); throw new Error('登录已失效，请重新登录数据空间'); }
      const raw = await response.text();
      let error; try { error = JSON.parse(raw); } catch (_) { /* plain upstream error */ }
      throw new Error(error?.detail || error?.message || raw || `HTTP ${response.status}`);
    }
    return response;
  }
  function showAccount() {
    $('#qaAccount').textContent = token ? `已登录：${account}` : '尚未登录';
    $('#qaLogin').hidden = !!token;
    $('#qaLogout').hidden = !token;
  }
  function clearSession() {
    token = ''; account = ''; datasets = [];
    sessionStorage.removeItem('qaToken'); sessionStorage.removeItem('qaAccount');
    $('#qaDataset').innerHTML = option('', '不使用数据空间检索');
    $('#qaAnswer').textContent = ''; $('#qaSources').replaceChildren(); $('#qaMeta').textContent = '';
    showAccount(); syncMode();
  }
  function selectedDataset() { return datasets.find(d => d.indexVersionId === $('#qaDataset').value); }
  function syncMode() {
    const mode = $('#qaInputMode').value;
    $('#qaContext').disabled = mode === 'question_only';
    $('#qaImages').disabled = mode === 'question_only';
    $('#qaDataset').disabled = mode === 'user_data_only';
    $('#qaRetrievalType').disabled = mode === 'user_data_only';
    const d = selectedDataset();
    $('#qaDatasetInfo').textContent = mode === 'user_data_only' ? '本次只使用提交的资料。'
      : d ? `${d.datasetName} · ${d.versionName} · ${d.sampleCount} 个样本 · 索引 READY`
      : '未选择数据集，本次回答不会检索数据空间。';
  }
  async function refresh() {
    const health = async () => {
      try {
        const r = await (await request('/health')).json();
        const ready = r.model?.loaded && r.vllm?.status === 'ok';
        $('#qaHealth').textContent = ready ? '问答模型已就绪' : '模型正在启动或不可用';
        $('#qaHealth').className = ready ? 'badge' : 'badge muted';
      } catch (_) { $('#qaHealth').textContent = '问答服务未就绪'; }
    };
    const scopes = async () => {
      if (!token) return;
      const previous = $('#qaDataset').value;
      datasets = await (await request('/datasets')).json();
      $('#qaDataset').innerHTML = option('', '不使用数据空间检索') + datasets.map(d => option(d.indexVersionId, `${d.datasetName} / ${d.versionName}`)).join('');
      $('#qaDataset').value = datasets.some(d => d.indexVersionId === previous) ? previous : datasets[0]?.indexVersionId || '';
      syncMode();
      if (!datasets.length) $('#qaDatasetInfo').textContent = '当前组织暂无可用 READY 索引，请先构建真实数据集索引。';
    };
    const results = await Promise.allSettled([health(), scopes()]);
    results.forEach(r => { if (r.status === 'rejected') toast(r.reason.message); });
  }
  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    if (!token) return toast('请先登录数据空间账号');
    const body = new FormData(event.currentTarget);
    const images = $('#qaImages').disabled ? [] : [...$('#qaImages').files];
    if (images.length > 2 || images.some(f => f.size > 10 * 1024 * 1024)) return toast('最多上传 2 张图片，每张不超过 10 MB');
    body.delete('images'); images.forEach(f => body.append('images', f));
    const scope = selectedDataset();
    const useRetrieval = body.get('inputMode') !== 'user_data_only' && scope && body.get('retrievalType') !== 'none';
    if (useRetrieval) {
      ['datasetId', 'versionId', 'indexVersionId'].forEach(k => body.set(k, scope[k]));
      body.set('retrievalMode', 'search_service');
    } else body.set('retrievalMode', 'none');
    if (useRetrieval && ['image_to_text', 'dual'].includes(body.get('retrievalType')) && !images.length) return toast('所选检索类型需要上传图片');
    body.set('maxTokens', '512');
    busy = true; $('#qaSubmit').disabled = true; $('#qaLogout').disabled = true;
    $('#qaProgress').textContent = '正在检索并生成回答，请稍候…';
    $('#qaAnswer').textContent = ''; $('#qaSources').replaceChildren(); $('#qaMeta').textContent = ''; $('#qaNotice').hidden = true;
    try {
      const result = await (await request('', { method: 'POST', body })).json();
      $('#qaAnswer').textContent = result.answer;
      $('#qaNotice').textContent = result.notice || ''; $('#qaNotice').hidden = !result.notice;
      $('#qaProgress').textContent = '回答完成';
      $('#qaMeta').textContent = `${result.model} · ${(result.processing_time_ms / 1000).toFixed(1)} 秒 · 检索：${result.meta?.retrieval_type || 'none'} · ${result.meta?.intent_analysis_used ? '模型分析意图' : '规则路由'}`;
      $('#qaSources').innerHTML = (result.sources || []).map((s, i) => `<div class="compact"><div><b>来源 ${i + 1} · ${esc(s.logical_path || s.source_type)}</b><p>${esc(s.content_preview)}</p>${scope && s.asset_id ? `<button type="button" class="btn link" data-qa-asset="${esc(s.asset_id)}">下载来源文件</button>` : ''}</div></div>`).join('');
      $$('[data-qa-asset]').forEach(b => b.onclick = async () => {
        try {
          const response = await request(`/assets/${scope.datasetId}/${scope.versionId}/${b.dataset.qaAsset}`);
          const blob = await response.blob(); const url = URL.createObjectURL(blob);
          const a = document.createElement('a'); a.href = url;
          a.download = (result.sources.find(s => s.asset_id === b.dataset.qaAsset)?.logical_path || 'source').split('/').pop();
          a.click(); setTimeout(() => URL.revokeObjectURL(url), 10000);
        } catch (e) { toast(e.message); }
      });
    } catch (e) { $('#qaProgress').textContent = `问答失败：${e.message}`; }
    finally { busy = false; $('#qaSubmit').disabled = false; $('#qaLogout').disabled = false; }
  }
  document.addEventListener('DOMContentLoaded', () => {
    showAccount(); syncMode(); refresh();
    $('#qaInputMode').onchange = syncMode; $('#qaDataset').onchange = syncMode;
    $('#qaRefresh').onclick = refresh; $('#qaLogout').onclick = clearSession;
    $('#qaForm').onsubmit = submit;
    $('#qaLogin').onsubmit = async event => {
      event.preventDefault(); const form = event.currentTarget;
      try {
        const response = await fetch(authBase() + '/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(formData(form)) });
        const r = await response.json(); if (!response.ok) throw new Error(r.message || '登录失败');
        token = r.token; account = r.user?.username || form.elements.userName.value;
        sessionStorage.setItem('qaToken', token); sessionStorage.setItem('qaAccount', account);
        form.reset(); showAccount(); await refresh();
      } catch (e) { toast(e.message); }
    };
  });
})();
