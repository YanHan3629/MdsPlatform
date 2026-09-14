(() => {
  let token = sessionStorage.getItem('qaToken') || '';
  let account = sessionStorage.getItem('qaAccount') || '';
  let datasets = [];
  let busy = false;
  let mappingEditRevision = 0;
  let mappingLoadRevision = 0;
  const base = () => state.api.replace(/\/$/, '');
  const authBase = () => base().replace(/\/data-space$/, '/auth');
  const mmBase = () => base().replace(/\/data-space$/, '/mm');

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
  async function requestMm(path, options = {}) {
    const response = await fetch(mmBase() + path, {
      ...options, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...options.headers }
    });
    if (!response.ok) {
      if (response.status === 401) clearSession();
      const raw = await response.text();
      let error; try { error = JSON.parse(raw); } catch (_) { /* plain error */ }
      throw new Error(error?.message || error?.detail || raw || `HTTP ${response.status}`);
    }
    return response.json();
  }
  const mmJson = (path, value) => requestMm(path, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
  async function mmList(path) {
    const rows = [];
    for (let page = 1; page <= 50; page++) {
      const result = await requestMm(`${path}${path.includes('?') ? '&' : '?'}page=${page}&size=100`);
      rows.push(...(result.items || []));
      if (page >= result.totalPages) return rows;
    }
    throw new Error('目录超过页面可加载范围，请缩小数据范围');
  }
  async function loadIngestSpaces(preferred) {
    const spaces = await mmList('/spaces');
    const previous = preferred || $('#qaIngestSpace').value;
    $('#qaIngestSpace').innerHTML = option('', '选择已有空间') + spaces.map(s => option(s.spaceId, s.spaceName)).join('');
    $('#qaIngestSpace').value = spaces.some(s => s.spaceId === previous) ? previous : spaces[0]?.spaceId || '';
    await loadIngestDatasets();
  }
  async function loadIngestDatasets(preferred) {
    const spaceId = $('#qaIngestSpace').value;
    const datasetsInSpace = spaceId ? await mmList(`/spaces/${spaceId}/datasets`) : [];
    const previous = preferred || $('#qaIngestDataset').value;
    $('#qaIngestDataset').innerHTML = option('', '选择已有数据集')
      + datasetsInSpace.map(d => option(d.datasetId, d.datasetName)).join('');
    $('#qaIngestDataset').value = datasetsInSpace.some(d => d.datasetId === previous)
      ? previous : datasetsInSpace[0]?.datasetId || '';
    await loadIngestVersions();
  }
  async function loadIngestVersions(preferred) {
    const loadRevision = ++mappingLoadRevision;
    const editRevision = mappingEditRevision;
    const datasetId = $('#qaIngestDataset').value;
    const versions = datasetId ? await mmList(`/datasets/${datasetId}/versions`) : [];
    const previous = preferred || $('#qaIngestVersion').value;
    $('#qaIngestVersion').innerHTML = option('', '选择已有版本')
      + versions.map(v => option(v.versionId, `${v.versionName} · ${v.versionStatus}`)).join('');
    $('#qaIngestVersion').value = versions.some(v => v.versionId === previous)
      ? previous : versions[0]?.versionId || '';
    $('#qaIngestVersion').dataset.status = versions.find(v => v.versionId === $('#qaIngestVersion').value)?.versionStatus || '';
    if ($('#qaIngestVersion').value) {
      const config = await requestMm(`/datasets/${datasetId}/versions/${$('#qaIngestVersion').value}/ingest-tasks/mapping`);
      // A late response must not erase fields the user has already started editing.
      if (loadRevision !== mappingLoadRevision || editRevision !== mappingEditRevision) return;
      $('#qaSourceTimezone').value = config?.timezone || '';
      $('#qaSourceTimeColumn').value = config?.timeColumn || '';
      $('#qaSourceEntities').value = (config?.entityColumns || []).join(',');
      $('#qaSourceUnits').value = config?.unitMapping ? JSON.stringify(config.unitMapping) : '';
      $('#qaAssetMappings').value = config?.assetMappings ? JSON.stringify(config.assetMappings) : '';
    }
  }
  async function saveMapping(datasetId = $('#qaIngestDataset').value, versionId = $('#qaIngestVersion').value, provided) {
    if (!datasetId || !versionId) throw new Error('请先选择数据集和版本');
    return mmJson(`/datasets/${datasetId}/versions/${versionId}/ingest-tasks/mapping`, provided || mappingForm());
  }
  function mappingForm() {
    return { timezone: $('#qaSourceTimezone').value.trim(), timeColumn: $('#qaSourceTimeColumn').value.trim(),
      entityColumns: $('#qaSourceEntities').value.split(',').map(s => s.trim()).filter(Boolean),
      unitMapping: JSON.parse($('#qaSourceUnits').value.trim() || '{}'),
      assetMappings: JSON.parse($('#qaAssetMappings').value.trim() || '{}') };
  }
  function ingestFiles() {
    const files = [...$('#qaIngestFiles').files, ...$('#qaIngestFolder').files];
    const seen = new Set();
    return files.map(file => {
      const relative = (file.webkitRelativePath || file.name).replace(/\\/g, '/');
      const parts = relative.split('/');
      if (!parts.length || parts.some(part => !part || part === '.' || part === '..' || /[\x00-\x1f]/.test(part))) {
        throw new Error(`无效上传路径：${relative}`);
      }
      const logicalPath = '/' + parts.join('/');
      if (seen.has(logicalPath)) throw new Error(`本批次有重复路径：${logicalPath}`);
      seen.add(logicalPath);
      return { file, logicalPath };
    });
  }
  async function submitIngest(event) {
    event.preventDefault();
    if (!token) return toast('请先登录数据空间账号');
    const button = $('#qaIngestSubmit');
    button.disabled = true;
    try {
      const configuredMapping = mappingForm();
      const files = ingestFiles();
      if (!files.length) throw new Error('请先选择文件或文件夹');
      let spaceId = $('#qaIngestSpace').value;
      const newSpace = $('#qaNewSpace').value.trim();
      if (newSpace) {
        spaceId = (await mmJson('/spaces', { spaceName: newSpace })).spaceId;
        $('#qaNewSpace').value = '';
        await loadIngestSpaces(spaceId);
      }
      if (!spaceId) throw new Error('请选择或创建空间');
      let datasetId = $('#qaIngestDataset').value;
      const newDataset = $('#qaNewDataset').value.trim();
      if (newDataset) {
        datasetId = (await mmJson(`/spaces/${spaceId}/datasets`, {
          datasetName: newDataset, datasetType: 'GENERAL', modalityType: 'MIXED'
        })).datasetId;
        $('#qaNewDataset').value = '';
        await loadIngestDatasets(datasetId);
      }
      if (!datasetId) throw new Error('请选择或创建数据集');
      let versionId = $('#qaIngestVersion').value;
      const newVersion = $('#qaNewVersion').value.trim();
      if (newVersion) {
        versionId = (await mmJson(`/datasets/${datasetId}/versions`, {
          versionName: newVersion, comment: '通过真实数据入库页面创建'
        })).versionId;
        $('#qaNewVersion').value = '';
        await loadIngestVersions(versionId);
      }
      if (!versionId) throw new Error('请选择或创建版本');
      if (!['DRAFT', 'UPLOADING'].includes($('#qaIngestVersion').dataset.status)) {
        throw new Error('已发布版本不可写，请填写新版本名称后再上传');
      }
      for (const [index, { file, logicalPath }] of files.entries()) {
        $('#qaIngestProgress').textContent = `正在上传 ${index + 1}/${files.length}：${logicalPath}`;
        await requestMm(`/datasets/${datasetId}/versions/${versionId}/files/content?path=${encodeURIComponent(logicalPath)}`,
          { method: 'PUT', headers: { 'Content-Type': file.type || 'application/octet-stream' }, body: file });
      }
      $('#qaIngestProgress').textContent = '上传完成，正在发布版本并创建索引任务…';
      await saveMapping(datasetId,versionId,configuredMapping);
      await requestMm(`/datasets/${datasetId}/versions/${versionId}/publish`, { method: 'POST' });
      $('#qaIngestProgress').textContent = `版本已发布，${files.length} 个文件已校验；后台索引任务已自动创建。`;
      $('#qaIngestFiles').value = ''; $('#qaIngestFolder').value = '';
      await loadIngestVersions(versionId);
      await refresh();
    } catch (error) {
      $('#qaIngestProgress').textContent = `入库未完成：${error.message}`;
    } finally { button.disabled = false; }
  }
  async function backfillIngest() {
    if (!token) return toast('请先登录数据空间账号');
    const datasetId = $('#qaIngestDataset').value, versionId = $('#qaIngestVersion').value;
    if (!datasetId || !versionId) return toast('请先选择已有数据集和版本');
    if (['DRAFT', 'UPLOADING'].includes($('#qaIngestVersion').dataset.status)) return toast('草稿版本请先上传并发布');
    const button = $('#qaBackfill'); button.disabled = true;
    try {
      await saveMapping(datasetId,versionId);
      const task = await requestMm(`/datasets/${datasetId}/versions/${versionId}/ingest-tasks/backfill`, { method: 'POST' });
      $('#qaIngestProgress').textContent = `回填任务 ${task.status} · ${task.stage}；重复请求会复用同一任务。`;
      await refresh();
    } catch (error) { $('#qaIngestProgress').textContent = `回填失败：${error.message}`; }
    finally { button.disabled = false; }
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
    $('#qaIngestSpace').innerHTML = option('', '登录后加载');
    $('#qaIngestDataset').innerHTML = option('', '先选择空间');
    $('#qaIngestVersion').innerHTML = option('', '先选择数据集');
    $('#qaIngestProgress').textContent = '登录后可操作真实数据版本。';
    showAccount(); syncMode();
  }
  function selectedDataset() { return datasets.find(d => d.indexVersionId === $('#qaDataset').value); }
  const capabilityName = value => ({
    'text.lexical.zh': '中文词法', 'text.semantic.zh': '中文语义', 'visual.clip': '视觉 CLIP',
    'structured.typed': '结构化', 'timeseries.facts': '时序精确计算',
    'timeseries.shape': '曲线相似', 'geometry.properties': '几何属性', 'geometry.shape': '几何形状'
  }[value] || value);
  function syncMode() {
    const mode = $('#qaInputMode').value;
    $('#qaContext').disabled = mode === 'question_only';
    $('#qaImages').disabled = mode === 'question_only';
    $('#qaDataset').disabled = mode === 'user_data_only';
    $('#qaRetrievalType').disabled = mode === 'user_data_only';
    $('#qaProfessional').hidden = mode === 'user_data_only' || $('#qaRetrievalType').value !== 'professional';
    const d = selectedDataset();
    const assetSelect = $('#qaQueryAsset');
    if (assetSelect.dataset.scope !== (d?.versionId || '')) {
      assetSelect.dataset.scope = d?.versionId || '';
      assetSelect.innerHTML = option('', '选择当前数据版本中的三维文件');
      if (d) request(`/query-assets/${d.datasetId}/${d.versionId}`).then(r => r.json()).then(rows => {
        if (assetSelect.dataset.scope === d.versionId) assetSelect.innerHTML += rows.map(a => option(a.assetId,a.logicalPath)).join('');
      }).catch(e => toast(e.message));
    }
    const capabilities = (d?.capabilities || []).filter(c => c.status === 'READY')
      .map(c => capabilityName(c.semanticSpace)).join('、');
    $('#qaDatasetInfo').textContent = mode === 'user_data_only' ? '本次只使用提交的资料。'
      : d ? `${d.datasetName} · ${d.versionName} · ${d.sampleCount} 个样本 · 索引 READY${capabilities ? ` · 能力：${capabilities}` : ' · 仅兼容索引'}`
      : '未选择数据集，本次回答不会检索数据空间。';
  }
  function professionalPlan(body) {
    const operation = $('#qaOperation').value;
    const metric = $('#qaMetric').value.trim();
    const start = $('#qaTimeStart').value, end = $('#qaTimeEnd').value;
    const plan = {
      taskIntent: `${$('#qaRetriever').value}_${operation}`,
      retrievers: [$('#qaRetriever').value], queryText: String(body.get('question') || ''),
      operation, topK: Number(body.get('topK') || 8)
    };
    if ($('#qaEntity').value.trim()) plan.targetEntity = $('#qaEntity').value.trim();
    if ($('#qaQueryAsset').value) plan.queryAssetId = $('#qaQueryAsset').value;
    if ($('#qaQuerySeries').value.trim()) {
      const series = JSON.parse($('#qaQuerySeries').value);
      if (!Array.isArray(series) || !series.length || series.length > 4096 || series.some(x => typeof x !== 'number' || !Number.isFinite(x))) throw new Error('查询曲线必须是 1～4096 个有限数值的数组');
      plan.querySeries = series;
    }
    if (operation === 'similar_shape' && plan.retrievers[0] === 'geometry' && !plan.queryAssetId) throw new Error('请选择几何查询样本');
    if (operation === 'similar_shape' && plan.retrievers[0] === 'timeseries' && !plan.querySeries) throw new Error('请填写查询曲线');
    plan.rotationInvariant = $('#qaRotationInvariant').checked;
    plan.scaleInvariant = $('#qaScaleInvariant').checked;
    if (metric) plan.metric = metric;
    if ($('#qaAggregateField').value.trim()) plan.aggregateField = $('#qaAggregateField').value.trim();
    if ($('#qaSortField').value.trim()) plan.sortField = $('#qaSortField').value.trim();
    plan.sortDescending = $('#qaSortDescending').checked;
    if ($('#qaUnit').value.trim()) plan.unit = $('#qaUnit').value.trim();
    if (start && end) plan.timeRange = { start: new Date(start).toISOString(), end: new Date(end).toISOString(), boundary: '[start,end)' };
    const compareStart = $('#qaCompareStart').value, compareEnd = $('#qaCompareEnd').value;
    if (compareStart && compareEnd) plan.compareTimeRange = { start: new Date(compareStart).toISOString(), end: new Date(compareEnd).toISOString(), boundary: '[start,end)' };
    if ($('#qaThreshold').value !== '') plan.threshold = Number($('#qaThreshold').value);
    const field = $('#qaConditionField').value.trim(), rawValue = $('#qaConditionValue').value.trim();
    if (field && rawValue !== '') {
      const numeric = Number(rawValue);
      plan.hardConditions = [{ field, op: $('#qaConditionOp').value, value: Number.isFinite(numeric) && rawValue !== '' ? numeric : rawValue, unit: plan.unit || undefined }];
    }
    const exactTimeOps = new Set(['count','sum','min','max','average','time_weighted_average','duration_above','compare_periods','trend_start_end','trend_regression','trend_monotonic']);
    if (plan.retrievers.includes('timeseries') && exactTimeOps.has(operation) && (!metric || !plan.timeRange)) {
      throw new Error('精确时序计算需要填写指标字段、开始时间和结束时间');
    }
    if (operation === 'duration_above' && plan.threshold === undefined) throw new Error('超过阈值时长需要填写阈值');
    if (operation === 'compare_periods' && !plan.compareTimeRange) throw new Error('比较时间段需要填写第二个比较时段');
    if (plan.retrievers.includes('structured') && operation === 'sort' && !plan.sortField) {
      throw new Error('结构化排序需要填写排序字段');
    }
    return plan;
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
      const [datasetResponse, indexingResponse] = await Promise.all([request('/datasets'), request('/indexing')]);
      datasets = await datasetResponse.json();
      const tasks = await indexingResponse.json();
      $('#qaIndexing').innerHTML = tasks.slice(0, 5).map(t => `<div class="compact"><div><b>${esc(t.datasetName)} / ${esc(t.versionName)}</b><p>${esc(t.status)} · ${esc(t.stage)} · 尝试 ${esc(t.attemptCount)}/${esc(t.maxAttempts)}${t.error ? ` · ${esc(t.error)}` : ''}</p></div></div>`).join('');
      $('#qaDataset').innerHTML = option('', '不使用数据空间检索') + datasets.map(d => option(d.indexVersionId, `${d.datasetName} / ${d.versionName}`)).join('');
      $('#qaDataset').value = datasets.some(d => d.indexVersionId === previous) ? previous : datasets[0]?.indexVersionId || '';
      syncMode();
      if (!datasets.length) $('#qaDatasetInfo').textContent = '当前组织暂无可用 READY 索引，请先构建真实数据集索引。';
    };
    const results = await Promise.allSettled([health(), scopes(), token ? loadIngestSpaces() : Promise.resolve()]);
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
      if (body.get('retrievalType') === 'professional') {
        try { body.set('queryPlan', JSON.stringify(professionalPlan(body))); }
        catch (error) { return toast(error.message); }
      }
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
      $('#qaMeta').textContent = `${result.model} · ${(result.processing_time_ms / 1000).toFixed(1)} 秒 · 检索：${result.meta?.retrieval_type || 'none'} · 支持度 ${Number(result.meta?.retrieval_support || 0).toFixed(2)} · 数据质量 ${Number(result.meta?.data_quality || 0).toFixed(2)} · 生成置信来源：${result.meta?.confidence_source || 'unavailable'}（不代表事实正确率） · ${result.meta?.intent_analysis_used ? '模型分析意图' : '规则路由'}`;
      $('#qaSources').innerHTML = (result.sources || []).map((s, i) => {
        const locator = s.locator && Object.keys(s.locator).length ? JSON.stringify(s.locator) : '';
        const timeRange = s.time_range ? `${s.time_range.start || ''} — ${s.time_range.end || ''}` : '';
        const flags = (s.quality_flags || []).join('、');
        return `<div class="compact"><div><b>来源 ${i + 1} · ${esc(s.logical_path || s.source_type)}</b><p>${esc(s.content_preview)}</p><div class="evidence-meta">${s.evidence_type ? `<span>${esc(s.evidence_type)}</span>` : ''}${s.semantic_space ? `<span>${esc(s.semantic_space)}</span>` : ''}${s.score_type ? `<span>${esc(s.score_type)}</span>` : ''}${locator ? `<span>定位 ${esc(locator)}</span>` : ''}${timeRange ? `<span>${esc(timeRange)}</span>` : ''}${flags ? `<span>质量：${esc(flags)}</span>` : ''}</div>${scope && s.asset_id ? `<button type="button" class="btn link" data-qa-asset="${esc(s.asset_id)}">下载来源文件</button>` : ''}</div></div>`;
      }).join('');
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
    ['qaSourceTimezone','qaSourceTimeColumn','qaSourceEntities','qaSourceUnits','qaAssetMappings']
      .forEach(id => $('#'+id).addEventListener('input', () => { mappingEditRevision += 1; }));
    $('#qaOperation').add(new Option('形状／曲线相似','similar_shape'));
    $('#qaSaveMapping').onclick = () => saveMapping().then(() => toast('映射已保存；旧索引不变，回填后生效')).catch(e => toast(e.message));
    showAccount(); syncMode(); refresh();
    $('#qaInputMode').onchange = syncMode; $('#qaDataset').onchange = syncMode; $('#qaRetrievalType').onchange = syncMode;
    $('#qaIngestSpace').onchange = () => loadIngestDatasets().catch(e => toast(e.message));
    $('#qaIngestDataset').onchange = () => loadIngestVersions().catch(e => toast(e.message));
    $('#qaIngestVersion').onchange = () => loadIngestVersions($('#qaIngestVersion').value).catch(e => toast(e.message));
    $('#qaIngestForm').onsubmit = submitIngest; $('#qaBackfill').onclick = backfillIngest;
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
