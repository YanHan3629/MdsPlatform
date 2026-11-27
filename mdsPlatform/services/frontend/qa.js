(() => {
  let token = false;
  let scopeSets = new Map(), qaPicker, searchPicker;
  let scopeRefresh = 0;
  const downloadUrls = new Set();
  let datasets = [];
  let busy = false;
  let authRevision = 0;
  let lastAuthEventRevision = -1;
  let lastIdentityRevision = -1;
  let authAbort = new AbortController();
  let qaSequence = 0;
  let qaAbort = null;
  let qaCapabilities = null;
  let activeQa = null;
  let displayedQa = null;
  let questionHistory = {key: '', turns: []};

  function resetQuestionHistory() {
    questionHistory = {key: '', turns: []};
    $('#qaFollowUp').checked = false;
    $('#qaFollowUp').disabled = true;
  }

  function syncBudget() {
    const profile = qaCapabilities?.profiles[$('#qaProfile').value];
    const output = $('#qaMaxTokens');
    output.disabled = !profile;
    if (!profile) {
      $('#qaBudgetHelp').textContent = '问答能力暂不可用，请刷新模型状态。';
      return;
    }
    output.max = profile.maxOutputTokens;
    output.placeholder = profile.defaultOutputTokens;
    $('#qaBudgetHelp').textContent =
      '问题与补充文字最多 ' + profile.userTokens + ' token，最多 ' + qaCapabilities.maxImageCount +
      ' 张图片，每张最多 ' + qaCapabilities.maxImageBytes / (1024 * 1024) + ' MB。';
  }

  function updateBudget(capabilities) {
    const selected = $('#qaProfile').value;
    qaCapabilities = capabilities;
    const profiles = Object.keys(capabilities?.profiles || {});
    $('#qaProfile').innerHTML = (profiles.length ? profiles : ['standard'])
      .map(name => option(name, name === 'long' ? '长文与详细报告' : '常规业务')).join('');
    $('#qaProfile').value = profiles.includes(selected) ? selected : 'standard';
    syncBudget();
  }

  function stopQaProgress() {
    if (!activeQa) return;
    clearInterval(activeQa.timer);
    activeQa = null;
    $('#qaCancel').hidden = true;
  }

  async function cancelQa() {
    const pending = activeQa;
    if (!pending) return;
    const button = $('#qaCancel');
    button.disabled = true;
    try {
      for (let attempt = 0; attempt < 4 && activeQa === pending; attempt++) {
        try {
          await request('/requests/' + pending.id, {method: 'DELETE', signal: AbortSignal.timeout(4000)});
          if (activeQa !== pending) return;
          qaSequence++;
          qaAbort?.abort();
          stopQaProgress();
          $('#qaProgress').textContent = '已提交取消，问答已停止等待。';
          return;
        } catch (error) {
          if (error.status !== 404) throw error;
          await new Promise(resolve => setTimeout(resolve, 250));
        }
      }
      if (activeQa === pending) $('#qaProgress').textContent = '请求仍在传输，暂未确认取消。';
    } catch (error) {
      if (activeQa === pending && error.name !== 'AbortError') $('#qaProgress').textContent = '取消失败：' + error.message;
    } finally {
      button.disabled = false;
    }
  }
  let ingestLoaded = false;
  let ingestWork = null;
  let mappingEditRevision = 0;
  let ingestSelectionRevision = 0;
  const sourceUrls = new Map();
  function clearQaMeta() {
    const meta=$('#qaMeta');meta.textContent='';
    for(const key of ['traceId','retrievalStatus','generationStatus','httpStatus'])delete meta.dataset[key];
  }
  function releaseSourceUrls(root) {
    for(const [url,element] of sourceUrls) if(!root || root.contains(element)) { URL.revokeObjectURL(url);sourceUrls.delete(url); }
  }
  function clearSources(root) {
    releaseSourceUrls(root);root.replaceChildren();
  }
  function invalidateQaResult(message = '数据范围已更新，可重新提问。', preserveCompleted = false) {
    if(activeQa&&token)void request('/requests/'+activeQa.id,{method:'DELETE',signal:AbortSignal.timeout(4000)}).catch(()=>{});
    qaSequence++;qaAbort?.abort();stopQaProgress();resetQuestionHistory();
    if(preserveCompleted&&displayedQa) {
      $('#qaProgress').textContent='范围已更新，下方保留上次问答及其原范围来源。';
      delete $('#qaProgress').dataset.request;
      return;
    }
    displayedQa=null;
    $('#qaAnswer').textContent='';clearSources($('#qaSources'));clearQaMeta();$('#qaNotice').replaceChildren();$('#qaNotice').hidden=true;
    $('#qaProgress').textContent=message;delete $('#qaProgress').dataset.request;
  }
  const base = () => '/api/data-space';
  const mmBase = () => base().replace(/\/data-space$/, '/mm');

  // A calculation may cite several files without a single primary asset_id.
  function sourceReferences(source) {
    const references = new Map();
    for (const item of [...(source.provenance?.verificationSources || []),...(source.provenance?.sourceAssets || []),
      ...(source.asset_id ? [{ assetId: source.asset_id, logicalPath: source.logical_path }] : [])]) {
      if (item.assetId) references.set(item.assetId, {
        assetId: item.assetId, logicalPath: item.logicalPath || references.get(item.assetId)?.logicalPath,role:item.role
      });
    }
    return [...references.values()];
  }

  async function authorizedFetch(url, options = {}) {
    const revision=authRevision;
    const signal=options.signal?AbortSignal.any([options.signal,authAbort.signal]):authAbort.signal;
    const response=await window.mdsIdentity.fetch(url,{...options,signal});
    if(revision!==authRevision)throw new DOMException('账号已变化','AbortError');
    return response;
  }
  const request=(path,options={})=>authorizedFetch(base()+'/qa'+path,options);
  async function requestMm(path, options = {}) {
    const revision = authRevision;
    const response = await authorizedFetch(mmBase()+path,options);
    const result = await response.json();
    if (revision !== authRevision) throw new DOMException('账号已变化', 'AbortError');
    return result;
  }
  const mmJson = (path, value) => requestMm(path, { method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
  async function mmList(path) {
    const revision = authRevision;
    const rows = [];
    for (let page = 1; page <= 50; page++) {
      if (revision !== authRevision) throw new DOMException('账号或权限已变化', 'AbortError');
      const result = await requestMm(`${path}${path.includes('?') ? '&' : '?'}page=${page}&size=100`);
      rows.push(...(result.items || []));
      if (page >= result.totalPages) return rows;
    }
    throw new Error('目录超过页面可加载范围，请缩小数据范围');
  }
  async function loadIngestSpaces(preferred, preferredDataset) {
    const selection = ++ingestSelectionRevision;
    const spaces = await mmList('/spaces?ingestion=true');
    if (selection !== ingestSelectionRevision) return;
    const previous = preferred || $('#qaIngestSpace').value;
    $('#qaIngestSpace').innerHTML = option('', '选择已有空间') + spaces.map(s => option(s.spaceId, s.spaceName)).join('');
    $('#qaIngestSpace').value = spaces.some(s => s.spaceId === previous) ? previous : preferred ? '' : spaces[0]?.spaceId || '';
    await loadIngestDatasets(preferredDataset, selection);
  }
  async function loadIngestDatasets(preferred, selection = ++ingestSelectionRevision) {
    const spaceId = $('#qaIngestSpace').value;
    const datasetsInSpace = spaceId ? await mmList(`/spaces/${spaceId}/datasets?ingestion=true`) : [];
    if (selection !== ingestSelectionRevision) return;
    const previous = preferred || $('#qaIngestDataset').value;
    $('#qaIngestDataset').innerHTML = option('', '选择已有数据集')
      + datasetsInSpace.map(d => option(d.datasetId, d.datasetName)).join('');
    $('#qaIngestDataset').value = datasetsInSpace.some(d => d.datasetId === previous)
      ? previous : preferred ? '' : datasetsInSpace[0]?.datasetId || '';
    await loadIngestVersions(undefined, selection);
  }
  async function loadIngestVersions(preferred, selection = ++ingestSelectionRevision) {
    const editRevision = mappingEditRevision;
    const datasetId = $('#qaIngestDataset').value;
    const versions = datasetId ? await mmList(`/datasets/${datasetId}/versions?ingestion=true`) : [];
    if (selection !== ingestSelectionRevision) return;
    const previous = preferred || $('#qaIngestVersion').value;
    $('#qaIngestVersion').innerHTML = option('', '选择已有版本')
      + versions.map(v => option(v.versionId, `${v.versionName} · ${mdsLabel(v.versionStatus)}`)).join('');
    $('#qaIngestVersion').value = versions.some(v => v.versionId === previous)
      ? previous : preferred ? '' : versions[0]?.versionId || '';
    $('#qaIngestVersion').dataset.status = versions.find(v => v.versionId === $('#qaIngestVersion').value)?.versionStatus || '';
    const auth = window.mdsIdentity;
    const canReadMapping = auth.has('dataset.draft.manage') || auth.has('dataset.index.build');
    const versionId = $('#qaIngestVersion').value;
    const config = versionId && canReadMapping
      ? await requestMm(`/datasets/${datasetId}/versions/${versionId}/ingest-tasks/mapping`) : null;
    // A late response must not erase fields the user has already started editing.
    if (selection === ingestSelectionRevision && editRevision === mappingEditRevision) showIngestMapping(config);
  }
  function showIngestMapping(config) {
    $('#qaSourceTimezone').value = config?.timezone || '';
    $('#qaSourceTimeColumn').value = config?.timeColumn || '';
    $('#qaSourceEntities').value = (config?.entityColumns || []).join(',');
    $('#qaSourceUnits').value = config?.unitMapping ? JSON.stringify(config.unitMapping) : '';
    $('#qaAssetMappings').value = config?.assetMappings ? JSON.stringify(config.assetMappings) : '';
  }
  async function saveMapping(datasetId = $('#qaIngestDataset').value, versionId = $('#qaIngestVersion').value, provided) {
    if (!datasetId || !versionId) throw new Error('请先选择数据集和版本');
    return mmJson(`/datasets/${datasetId}/versions/${versionId}/ingest-tasks/mapping`, provided || mappingForm());
  }
  function mappingForm() {
    return { timezone: $('#qaSourceTimezone').value.trim(), timeColumn: $('#qaSourceTimeColumn').value.trim(),
      entityColumns: $('#qaSourceEntities').value.split(',').map(s => s.trim()).filter(Boolean),
      unitMapping: mdsParseJson($('#qaSourceUnits').value.trim() || '{}','单位映射'),
      assetMappings: mdsParseJson($('#qaAssetMappings').value.trim() || '{}','按文件覆盖映射') };
  }
  function ingestFiles() {
    const files = [...$('#qaIngestFiles').files, ...$('#qaIngestFolder').files];
    const seen = new Set();
    const skipped=files.filter(file=>/^(?:readme(?:\.[a-z0-9]+)?|数据集说明\.md)$/i.test(file.name));
    const accepted=files.filter(file=>!skipped.includes(file));
    $('#qaIngestProgress').dataset.skipped=String(skipped.length);
    return accepted.map(file => {
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
  function releaseIngest(work = ingestWork) {
    if (!work || ingestWork !== work) return;
    ingestWork = null;
    delete $('#qaIngestForm').dataset.ingestBusy;
    for (const [control, disabled] of work.controls) control.disabled = disabled;
    window.mdsIdentityUi?.applyCapabilities();
  }
  async function prepareIngestTarget(work, newSpace, newDataset) {
    const auth = window.mdsIdentity;
    const actor = auth.identity;
    const revision = authRevision;
    let spaceId = $('#qaIngestSpace').value;
    if (newSpace) {
      spaceId = (await mmJson('/spaces', { spaceName: newSpace })).spaceId;
      $('#qaNewSpace').value = '';
      await loadIngestSpaces(spaceId);
    }
    if (ingestWork !== work || revision !== authRevision) throw new DOMException('账号或权限已变化', 'AbortError');
    if (!spaceId || $('#qaIngestSpace').value !== spaceId) throw new Error('请选择或创建空间');
    const { datasetId } = await mmJson(`/spaces/${spaceId}/datasets`, {
      datasetName: newDataset, datasetType: 'GENERAL', modalityType: 'MIXED'
    });
    // The creator grant advances the policy. A pending identity check may predate it.
    let current = await auth.check();
    if (current?.policyVersion === actor.policyVersion) current = await auth.check();
    if (ingestWork !== work || current?.userId !== actor.userId || current.orgId !== actor.orgId ||
        current.permissionVersion !== actor.permissionVersion || !auth.has('dataset.create')) return;
    $('#qaNewDataset').value = '';
    await loadIngestSpaces(spaceId, datasetId);
    if (ingestWork !== work) return;
    if ($('#qaIngestSpace').value !== spaceId || $('#qaIngestDataset').value !== datasetId)
      throw new Error('已创建的数据集不在当前可用范围内，请刷新后重新选择');
    ingestLoaded = true;
    $('#qaIngestProgress').textContent = auth.has('dataset.draft.manage')
      ? '数据集已创建并选中。请选择文件并填写草稿版本名称，再提交上传。'
      : '数据集已创建并选中。请在有权限的草稿版本可用后再选择文件上传。';
  }
  async function submitIngest(event) {
    event.preventDefault();
    if (ingestWork) return;
    if (!token) return toast('请先登录数据空间账号');
    const auth = window.mdsIdentity;
    const newSpace = $('#qaNewSpace').value.trim();
    const newDataset = $('#qaNewDataset').value.trim();
    if (!newDataset && !auth.has('dataset.upload')) return toast('当前账号没有上传权限');
    if (newSpace && !newDataset) return toast('在新空间上传时，请先填写新建数据集名称');
    if (newDataset && !auth.has('dataset.create')) return toast('当前账号没有创建数据集权限');
    const form = $('#qaIngestForm');
    const work = { preparing: !!newDataset,
      controls: [...form.querySelectorAll('input,select,textarea,button')].map(e => [e, e.disabled]) };
    ingestWork = work;
    form.dataset.ingestBusy = 'true';
    for (const [control] of work.controls) control.disabled = true;
    const ingestRevision = authRevision;
    let currentPath = '', uploaded = 0, completed = false;
    try {
      if (work.preparing) {
        // Dataset creation can change authority. Do not retain selected files across it.
        $('#qaIngestFiles').value = ''; $('#qaIngestFolder').value = '';
        await prepareIngestTarget(work, newSpace, newDataset);
        return;
      }
      const configuredMapping = mappingForm();
      const files = ingestFiles();
      if (!files.length) throw new Error('没有可入库的业务文件；已跳过自述及数据集说明，请选择业务文件');
      const datasetId = $('#qaIngestDataset').value;
      if (!datasetId) throw new Error('请选择数据集或先创建新数据集');
      let versionId = $('#qaIngestVersion').value;
      const newVersion = $('#qaNewVersion').value.trim();
      if (newVersion) {
        versionId = (await mmJson(`/datasets/${datasetId}/versions`, {
          versionName: newVersion, comment: '通过真实数据入库页面创建'
        })).versionId;
        $('#qaNewVersion').value = '';
        await loadIngestVersions(versionId);
        if(ingestRevision!==authRevision)throw new DOMException('账号已变化','AbortError');
        showIngestMapping(configuredMapping);
      }
      if (!versionId || $('#qaIngestVersion').value !== versionId) throw new Error('请选择或创建版本');
      if (!['DRAFT', 'UPLOADING'].includes($('#qaIngestVersion').dataset.status)) {
        throw new Error('已发布版本不可写，请填写新版本名称后再上传');
      }
      for (const [index, { file, logicalPath }] of files.entries()) {
        if(ingestRevision!==authRevision)throw new DOMException('账号已变化','AbortError');
        currentPath=logicalPath;
        $('#qaIngestProgress').textContent = `正在上传 ${index + 1}/${files.length}：${logicalPath}`;
        await requestMm(`/datasets/${datasetId}/versions/${versionId}/files/content?path=${encodeURIComponent(logicalPath)}`,
          { method: 'PUT', headers: { 'Content-Type': file.type || 'application/octet-stream' }, body: file });
        uploaded++;
      }
      if(ingestRevision!==authRevision)throw new DOMException('账号已变化','AbortError');
      if(auth.has('dataset.draft.manage'))await saveMapping(datasetId,versionId,configuredMapping);
      if(auth.has('dataset.publish')){
        await requestMm(`/datasets/${datasetId}/versions/${versionId}/publish`,{method:'POST'});
        $('#qaIngestProgress').textContent=window.mdsIdentity.has('dataset.index.build')?
          `版本已发布，${files.length} 个业务文件已校验；索引任务状态以服务端任务列表为准。`:
          `已上传并发布 ${files.length} 个业务文件，等待有权维护者构建索引。`;
      }else $('#qaIngestProgress').textContent=`已上传 ${files.length} 个业务文件并保留为草稿，等待本企业有权维护者发布。`;
      completed = true;
      $('#qaIngestFiles').value = ''; $('#qaIngestFolder').value = '';
      await loadIngestVersions(versionId);
      await refresh();
      window.dispatchEvent(new Event('dataspace-dataset-changed'));
    } catch (error) {
      if (ingestWork !== work) return;
      if (error.name === 'AbortError') {
        if (work.preparing && token) $('#qaIngestProgress').textContent = '权限已重新核对。请在当前目录确认数据集是否已创建，再选择文件继续。';
      } else if (work.preparing) $('#qaIngestProgress').textContent = `数据集准备未完成：${error.message}`;
      else if (completed) toast(`上传已完成，刷新版本状态失败：${error.message}`);
      else $('#qaIngestProgress').textContent = `入库未完成，已上传 ${uploaded} 个文件${currentPath?'，当前文件 '+currentPath:''}：${error.message}。已上传的草稿文件保留，可纠错后续作。`;
    } finally {
      if (ingestWork === work) {
        releaseIngest(work);
        if (work.preparing && token && !ingestLoaded) void refresh();
      }
    }
  }
  async function backfillIngest() {
    const revision=authRevision;
    if (!token) return toast('请先登录数据空间账号');
    const datasetId = $('#qaIngestDataset').value, versionId = $('#qaIngestVersion').value;
    if (!datasetId || !versionId) return toast('请先选择已有数据集和版本');
    if (['DRAFT', 'UPLOADING'].includes($('#qaIngestVersion').dataset.status)) return toast('草稿版本请先上传并发布');
    const button = $('#qaBackfill'); button.disabled = true;
    try {
      await saveMapping(datasetId,versionId);
      const task = await requestMm(`/datasets/${datasetId}/versions/${versionId}/ingest-tasks/backfill`, { method: 'POST' });
      if(revision!==authRevision)return;
      $('#qaIngestProgress').textContent = `回填任务 ${mdsLabel(task.status)} · ${mdsLabel(task.stage,'后台处理')}。重复请求会复用同一任务。`;
      await refresh();
    } catch (error) { if(revision===authRevision&&error.name!=='AbortError')$('#qaIngestProgress').textContent = `回填失败：${error.message}`; }
    finally { if(revision===authRevision)button.disabled=false;window.mdsIdentityUi?.applyCapabilities(); }
  }
  function notifyAccount() {
    if(lastAuthEventRevision!==authRevision){lastAuthEventRevision=authRevision;window.dispatchEvent(new CustomEvent('dataspace-auth',{detail:{authenticated:!!token,revision:authRevision}}));}
  }
  function notifyScope() {
    window.dispatchEvent(new CustomEvent('dataspace-scope', {detail: {revision: authRevision}}));
  }
  function resetSessionView(reason) {
    // Only the metadata-only creation step can finish reselecting a freshly authorized target.
    if (!ingestWork?.preparing || !['permissions-changed','permissions-verified'].includes(reason)) releaseIngest();
    releaseSourceUrls();
    authRevision++; authAbort.abort(); authAbort = new AbortController(); qaSequence++; qaAbort?.abort();
    token=window.mdsIdentity.authenticated;stopQaProgress();updateBudget(null);resetQuestionHistory();
    datasets=[];scopeSets=new Map();qaPicker.reset();searchPicker.reset();ingestLoaded=false;busy=false;displayedQa=null;
    for(const url of downloadUrls)URL.revokeObjectURL(url);downloadUrls.clear();
    for (const key of ['qaToken','qaAccount','qaIndex']) sessionStorage.removeItem(key);
    if(!token && reason!=='page-hidden')for(const key of ['qaDraft','retrievalDraft','qaSelection','retrievalSelection'])sessionStorage.removeItem(key);
    $('#qaAnswer').textContent = ''; $('#qaSources').replaceChildren(); clearQaMeta();
    $('#qaProgress').textContent = token ? '等待提问' : '请登录后提问'; $('#qaNotice').replaceChildren(); $('#qaNotice').hidden = true; $('#qaForm').reset();
    delete $('#qaProgress').dataset.request;
    $('#qaIngestForm').reset(); ingestSelectionRevision++;
    for (const [id,label] of [['qaIngestSpace','登录后加载'],['qaIngestDataset','先选择空间'],['qaIngestVersion','先选择数据集']]) $('#'+id).innerHTML = option('',label);
    $('#qaIndexing').replaceChildren(); notifyAccount(); syncMode(); notifyScope();
    delete $('#qaIngestVersion').dataset.status;
    delete $('#qaIngestProgress').dataset.skipped;
    $('#qaIngestProgress').textContent='登录后可操作真实数据版本。';
    window.mdsIdentityUi?.applyCapabilities();
  }
  function scopesFor(operation){return scopeSets.get(operation)||[];}
  function selectedScopes(operation){return (operation==='data.search'?searchPicker:qaPicker)?.items||[];}
  function scopeFor(operation){return selectedScopes(operation)[0]||null;}
  function canUseScope(scope,operation){
    return !!scope&&window.mdsIdentity.has(operation)&&scopesFor(operation).some(d=>
      d.datasetId===scope.datasetId&&d.versionId===scope.versionId&&d.indexVersionId===scope.indexVersionId);
  }
  function selectIndex(value,operation='data.qa') {
    (operation==='data.search'?searchPicker:qaPicker).choose(value);
  }
  function storedScopes(key){
    try{return JSON.parse(sessionStorage.getItem(key)||'[]');}catch{return [];}
  }
  function savePicker(key,picker){
    sessionStorage.setItem(key,JSON.stringify(picker.items.map(window.mdsScopePicker.frozen)));
  }
  async function openProductScope(scope,target) {
    const operation=target==='#retrieval'?'data.search':'data.qa';
    if(!window.mdsIdentity.has(operation))throw new Error(operation==='data.search'?'当前账号没有检索权限。':'当前账号没有问答权限。');
    const revision=authRevision;await refresh();
    if(revision!==authRevision)throw new DOMException('账号已变化','AbortError');
    if(!scopesFor(operation).some(d=>d.datasetId===scope.datasetId&&d.versionId===scope.versionId&&d.indexVersionId===scope.indexVersionId))
      throw new Error('该冻结范围当前未获授权或尚不可用。');
    if(operation==='data.qa'){$('#qaInputMode').value='question_only';$('#qaUseSearch').checked=true;}
    selectIndex(scope.indexVersionId,operation);location.hash=target==='#retrieval'?'#retrieval':'#qa';
  }
  function syncMode() {
    const mode = $('#qaInputMode').value;
    $('#qaContext').disabled = mode === 'question_only'; $('#qaImages').disabled = mode === 'question_only';
    $('#qaClearImages').disabled = mode === 'question_only';
    qaPicker.disable(mode === 'user_data_only'); $('#qaUseSearch').disabled = mode === 'user_data_only';
    $('#qaDatasetInfo').textContent = mode === 'user_data_only' ? '本次只使用提交的资料。'
      : selectedScopes('data.qa').length ? '本次问题使用上方所选冻结范围。' : '请选择当前获授权的就绪数据集。';
  }
  async function refresh() {
    const revision=authRevision;
    const health=async()=>{
      try{
        const response=await fetch(base()+'/qa/health',{credentials:'omit',redirect:'error',signal:authAbort.signal});
        const value=await response.json();if(revision!==authRevision)return;
        const ready=response.ok&&value.model?.loaded&&value.vllm?.status==='ok'&&value.capabilities?.profiles?.standard;
        updateBudget(ready?value.capabilities:null);
        $('#qaHealth').textContent=ready?'问答模型已就绪':'模型正在启动或不可用';
        $('#qaHealth').className=ready?'badge':'badge muted';
      }catch(error){if(revision===authRevision&&error.name!=='AbortError'){
        updateBudget(null);$('#qaHealth').textContent='问答服务未就绪';
      }}
    };
    const scopes=async()=>{
      if(!token)return;
      const sequence=++scopeRefresh;
      const operations=['data.catalog.read','data.preview','data.search','data.qa','data.raw.download'];
      const values=await Promise.all(operations.map(async operation=>[operation,window.mdsIdentity.has(operation)?
        await window.mdsIdentity.api('/users/me/scopes?operation='+encodeURIComponent(operation)):[]]));
      if(revision!==authRevision||sequence!==scopeRefresh)return;
      const old=selectedScopes('data.qa');
      scopeSets=new Map(values);datasets=scopesFor('data.qa');
      qaPicker.update(datasets,storedScopes('qaSelection'));
      searchPicker.update(scopesFor('data.search'),storedScopes('retrievalSelection'));
      if(displayedQa&&(displayedQa.scopes.some(scope=>!canUseScope(scope,'data.qa'))||
        displayedQa.previews.some(scope=>!canUseScope(scope,'data.preview'))))
        invalidateQaResult('上次问答的范围授权已变化，请重新提问。');
      else if(old.some(row=>!canUseScope(row,'data.qa')))
        invalidateQaResult('部分问答范围已失效，请核对选择。');
      savePicker('qaSelection',qaPicker);savePicker('retrievalSelection',searchPicker);
      syncMode();notifyScope();
    };
    const indexing=async()=>{
      if(!token||!window.mdsIdentity.has('dataset.index.build')){updateIndexingTasks([]);return;}
      const response=await request('/indexing');const tasks=await response.json();
      if(revision===authRevision)updateIndexingTasks(tasks);
    };
    const ingestion=token&&['dataset.upload','dataset.create','dataset.index.build'].some(operation=>window.mdsIdentity.has(operation))&&!ingestLoaded&&!ingestWork?
      loadIngestSpaces().then(()=>{if(revision===authRevision){
        ingestLoaded=true;
        const progress=$('#qaIngestProgress');
        if(progress.textContent==='登录后可操作真实数据版本。')progress.textContent='已加载当前账号可维护的数据版本。';
      }}):Promise.resolve();
    const results=await Promise.allSettled([health(),scopes(),indexing(),ingestion]);
    if(revision===authRevision)results.forEach(result=>{if(result.status==='rejected'&&result.reason.name!=='AbortError')toast(result.reason.message);});
  }
  function updateIndexingTasks(tasks){
    $('#qaIndexing').innerHTML=(tasks||[]).slice(0,5).map(t=>`<div class="compact"><div><b>${esc(t.datasetName)} / ${esc(t.versionName)}</b><p>${esc(mdsLabel(t.status))} · ${esc(mdsLabel(t.stage,'后台处理'))}${t.error?' · 请查看任务详情':''}</p><p>任务 ${esc(t.taskId||t.task_id||'')}</p></div></div>`).join('');
    const selected=(tasks||[]).find(t=>t.versionId===$('#qaIngestVersion').value&&t.status==='READY');
    if(selected&&$('#qaIngestVersion').selectedOptions[0]){$('#qaIngestVersion').dataset.status='READY';$('#qaIngestVersion').selectedOptions[0].textContent=`${selected.versionName} · 已就绪`;}
  }
  function evidenceLocation(source) {
    if(source.provenance?.recordReadVersion){
      const selected=new Set(source.fact_ids||[]),locations=new Map();
      for(const fact of source.evidence_bundle?.facts||[]){
        if(!selected.has(fact.factId)||fact.evidenceNature!=='source_record')continue;
        const at=fact.locator||{};let location='';
        if(['JSON','JSONL'].includes(source.original_format)&&typeof at.jsonPointer==='string'){
          location=`JSON ${at.jsonPointer||'根节点'}`;
          if(Number.isInteger(at.jsonlLine)&&at.jsonlLine>0)location+=` · 原文件第 ${at.jsonlLine} 行`;
        }else if(source.original_format==='XLSX'&&at.sheet&&at.cell){
          location=`工作表 ${at.sheet} · 单元格 ${at.cell}`;
        }else if(source.original_format==='CSV'){
          const start=at.lineStart??at.rawRow,end=at.lineEnd??start;
          if(Number.isInteger(start)&&start>0&&Number.isInteger(end)&&end>=start)location=`原文件第 ${start}${end>start?'–'+end:''} 行`;
        }
        if(!location)continue;
        if(!locations.has(location))locations.set(location,new Set());
        locations.get(location).add(fact.field);
      }
      if(locations.size)return [...locations].map(([location,fields])=>`${[...fields].filter(Boolean).join('、')}：${location}`).join('；');
    }
    const at=source.locator||{},parts=[];
    const readRows=source.original_format==='XLSX'?(source.evidence_bundle?.content?.records||[]).filter(row=>Number.isInteger(row.rawRow)&&row.locator?.sheet===at.sheet):[];
    const recordLines=(source.provenance?.nativeRecordLocators||[]).filter(row=>Number.isInteger(row.lineStart))
      .map(row=>row.lineEnd>row.lineStart?row.lineStart+'–'+row.lineEnd:String(row.lineStart));
    if(at.page!==undefined)parts.push(`第 ${at.page} 页`);
    if(at.sheet)parts.push(`工作表 ${at.sheet}`);
    if(readRows.length){
      parts.push(`原文件第 ${[...new Set(readRows.map(row=>row.rawRow))].join('、')} 行`);
      const cells=[...new Set((source.evidence_bundle?.facts||[]).map(f=>f.locator?.cell).filter(Boolean))];
      if(cells.length)parts.push(`单元格 ${cells.join('、')}`);
    }else if(recordLines.length)parts.push(`原文件第 ${[...new Set(recordLines)].join('、')} 行`);
    else if(at.cell||at.range)parts.push(at.cell||at.range);
    if(Array.isArray(at.cells)&&at.cells.length)parts.push(`单元格 ${at.cells.join('、')}`);
    if(at.jsonlLine!==undefined)parts.push(`原文件第 ${at.jsonlLine} 行`);
    if(at.jsonPointer!==undefined)parts.push(`JSON ${at.jsonPointer||'根节点'}`);
    if(at.rawRow!==undefined)parts.push(`行 ${at.rawRow}`);
    if(!readRows.length&&!recordLines.length&&at.rawRowStart!==undefined)parts.push(`起始行 ${at.rawRowStart}`);
    if(at.instanceId)parts.push(`实例 ${at.instanceId}`);
    if(at.svgElementId)parts.push(`元素 ${at.svgElementId}`);
    if(at.view)parts.push(({isometric:'等轴视图',front:'正视图',side:'侧视图',top:'俯视图','full-svg':'完整图纸','source-2d':'图纸'})[at.view]||at.view);
    if(at.bbox)parts.push(`区域 [${at.bbox.join(', ')}]`);
    if(at.lineStart!==undefined)parts.push(`文本行 ${at.lineStart}${at.lineEnd!==undefined?'–'+at.lineEnd:''}`);
    if(at.characterIndexUnit==='unicode-codepoint'&&Number.isInteger(at.characterStart)&&Number.isInteger(at.characterEnd))parts.push(`原文第 ${at.characterStart+1}–${at.characterEnd} 个字符`);
    return parts.join(' · ');
  }
  function evidenceKind(source) {
    if(source.provenance?.renderedRegion)return '程序生成的局部/页面图';
    if(source.provenance?.registeredImage)return source.original_format==='SVG'?'图纸渲染':'三维表面渲染';
    const types=new Set((source.evidence_bundle?.facts||[]).filter(f=>(source.fact_ids||[]).includes(f.factId)).map(f=>f.evidenceNature));
    return [...types].map(t=>({source_text:'原文',source_record:'原始记录',source_declaration:'源声明',program_calculation:'程序计算',ocr_text:'OCR 识别文字'})[t]||'已读取依据').join('、');
  }
  function evidenceSummary(source, limit = 6) {
    if(source.original_format==='TXT'&&source.provenance?.textReadVersion){
      const text=source.evidence_bundle?.content?.text,at=source.locator||{},target=source.provenance.textTarget||{};
      if(typeof text==='string'&&Number.isInteger(at.characterStart)&&Number.isInteger(target.characterStart)&&Number.isInteger(target.characterEnd)){
        const chars=Array.from(text),start=Math.max(0,target.characterStart-at.characterStart),end=Math.min(chars.length,target.characterEnd-at.characterStart);
        if(start<end)return '本次目标记录在该原文片段中的摘录：\n'+chars.slice(start,end).join('');
      }
    }
    if(source.provenance?.renderedRegion||source.provenance?.registeredImage)return `${evidenceKind(source)}。${source.original_format==='SVG'?'图纸标注属于源声明。':source.original_format==='OBJ'||source.original_format==='STEP'?'显示配色与近似网格用于观察，不能替代几何测量。':'按原始页和区域定位。'}`;
    const facts=(source.evidence_bundle?.facts||[]).filter(f=>(source.fact_ids||[]).includes(f.factId));
    if(!facts.length)return source.content_preview||'';
    function sourceValue(value,pointer,numbers) {
      if(numbers[pointer]?.lexeme!==undefined)return numbers[pointer].lexeme;
      if(Array.isArray(value))return '['+value.map((item,index)=>sourceValue(item,pointer+'/'+index,numbers)).join(', ')+']';
      if(value&&typeof value==='object')return '{'+Object.entries(value).map(([key,item])=>JSON.stringify(key)+': '+sourceValue(item,pointer+'/'+key.replace(/~/g,'~0').replace(/\//g,'~1'),numbers)).join(', ')+'}';
      return JSON.stringify(value);
    }
    return facts.slice(0,limit).map(f=>{
      if(source.original_format==='XLSX'&&(f.qualityFlags||[]).includes('FORMULA_CACHE_UNVERIFIED'))return `${f.field}: 公式 ${f.sourceValue}；原有缓存 ${JSON.stringify(f.value)}（缓存未验证，本次未执行公式）`;
      const pointer=f.locator?.jsonPointer||'',record=(source.evidence_bundle?.content?.records||[]).find(row=>pointer.startsWith((row.locator?.jsonPointer||'')+'/'));
      const numbers=source.evidence_bundle?.content?.numberLexemes||record?.numberLexemes||{};
      return `${f.field}: ${f.numberSourceExpression??(typeof f.value==='string'&&!['JSON','JSONL'].includes(source.original_format)?f.value:sourceValue(f.value,pointer,numbers))}${f.unit?' '+f.unit:''}`;
    }).join('\n')
      +(facts.length>limit?`\n已读取 ${facts.length} 项，此处展示前 ${limit} 项。`:'');
  }
  async function previewRecord(button,detail,source,scope,revision) {
    if(revision!==authRevision||!button.isConnected)return;
    if(!canUseScope(scope,'data.preview'))throw new Error('当前来源没有预览权限。');
    if(!detail.hidden){
      detail.replaceChildren();detail.hidden=true;button.textContent='查看完整证据';
      return;
    }
    const identity=source.evidence_bundle?.identity;
    const coordinates=['datasetId','versionId','indexVersionId'];
    if(!identity||coordinates.some(key=>identity[key]!==scope[key])||identity.assetId!==source.asset_id)
      throw new Error('证据与引用的冻结范围不匹配。');
    const path='/evidence/'+[...coordinates,'assetId'].map(key=>encodeURIComponent(identity[key])).join('/')+'/read';
    button.disabled=true;button.textContent='正在读取证据…';
    try{
      const response=await request(path,{method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify(source.provenance.nativeReadOperation)});
      const read=await response.json();
      if([...coordinates,'assetId','rawCommitId','sourceSha256'].some(key=>identity[key]!==read.identity?.[key])||
        read.evidenceId!==source.evidence_id||read.readOutputSha256!==source.provenance.readOutputSha256)
        throw new Error('证据内容或来源已变化，请重新提问。');
      if(revision!==authRevision||!button.isConnected||!canUseScope(scope,'data.preview'))return;
      detail.textContent=evidenceSummary({...source,evidence_bundle:read},Infinity);
      detail.hidden=false;button.textContent='收起完整证据';
    }finally{
      button.disabled=false;if(detail.hidden)button.textContent='查看完整证据';
    }
  }
  async function previewEvidence(button,source,scope,revision) {
    if(revision!==authRevision||!button.isConnected)return;
    if(!canUseScope(scope,'data.preview'))throw new Error('当前来源没有预览权限。');
    const card=button.closest('article'),figure=card.querySelector('figure');
    if(!figure.hidden){clearSources(figure);figure.hidden=true;button.textContent='预览证据';return;}
    const rendered=source.provenance?.renderedRegion,registered=source.provenance?.registeredImage;
    const descriptor=rendered||registered,identity=descriptor?.identity;
    if(!scope||!identity||['datasetId','versionId','indexVersionId'].some(key=>String(scope[key])!==identity[key])||String(source.asset_id)!==identity.assetId)throw new Error('证据与当前冻结范围不匹配，请重新提问');
    const path='/evidence/'+['datasetId','versionId','indexVersionId','assetId'].map(key=>encodeURIComponent(identity[key])).join('/');
    let response;
    button.disabled=true;button.textContent='正在读取证据…';
    try{
      if(rendered){
        const engineering=['SVG','OBJ','STEP'].includes(source.original_format),allowed=engineering?['view','bbox','maxPixels','instanceIds','highlightInstanceIds']:['page','bbox','dpi','maxPixels'];
        const parameters=Object.fromEntries(allowed.filter(key=>Object.hasOwn(rendered.parameters||{},key)).map(key=>[key,rendered.parameters[key]]));
        response=await request(path+'/render',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({operation:engineering?'render_engineering_image':'render_region_image',...parameters})});
        const header=response.headers.get('X-Evidence-Read');if(!header||header.length>32768)throw new Error('证据定位信息缺失');
        const metadata=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(header),character=>character.charCodeAt(0))));
        if(['datasetId','versionId','indexVersionId','rawCommitId','assetId','sourceSha256','evidenceConfigSha256'].some(key=>metadata.identity?.[key]!==identity[key])||metadata.outputSha256!==descriptor.outputSha256)throw new Error('证据版本或图像已不匹配，请重新提问');
      }else response=await request(path+'/'+encodeURIComponent(registered.legacyRepresentationId));
      const blob=await response.blob();if(blob.size>16*1024*1024||blob.type!=='image/png')throw new Error('证据预览格式或大小不符合要求');
      const checksum=[...new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))].map(value=>value.toString(16).padStart(2,'0')).join('');
      if(checksum!==descriptor.outputSha256||checksum!==response.headers.get('X-Evidence-Sha256'))throw new Error('证据图像完整性校验未通过');
      if(revision!==authRevision||!button.isConnected)return;
      const image=document.createElement('img'),caption=document.createElement('figcaption'),url=URL.createObjectURL(blob);
      sourceUrls.set(url,image);image.src=url;image.alt=`${source.original_format} · ${evidenceLocation(source)}`;
      caption.textContent=`${evidenceKind(source)} · ${evidenceLocation(source)}`;figure.dataset.evidenceSha256=checksum;figure.replaceChildren(image,caption);figure.hidden=false;button.textContent='收起证据';
    }finally{button.disabled=false;if(figure.hidden)button.textContent='预览证据';}
  }
  function referencedScope(source,fallback) {
    const provided=source.provenance?.frozenScope||source.evidence_bundle?.identity;
    const datasetId=source.dataset_id||provided?.datasetId;
    if(datasetId){
      const result={datasetId,versionId:provided?.versionId||source.data_version,
        indexVersionId:provided?.indexVersionId||source.index_version};
      return Object.values(result).every(Boolean)?result:null;
    }
    if(!fallback || source.data_version&&fallback.versionId!==source.data_version ||
        source.index_version&&fallback.indexVersionId!==source.index_version)return null;
    return fallback;
  }
  async function renderSources(target, sources, scope, revision = authRevision) {
    if(revision!==authRevision)return;
    releaseSourceUrls(target);
    const files = new Map(), groups=new Map();
    const sourceScopes=sources.map(source=>referencedScope(source,scope));
    const scopeKey=value=>value?['datasetId','versionId','indexVersionId'].map(key=>value[key]).join('/'):'';
    sources.forEach((source,index)=>{
      const frozen=sourceScopes[index];
      if(!canUseScope(frozen,'data.preview'))return;
      const key=scopeKey(frozen), group=groups.get(key)||{frozen,ids:new Set()};
      sourceReferences(source).forEach(ref=>group.ids.add(ref.assetId));
      groups.set(key,group);
    });
    for(const [key,{frozen,ids}] of groups){
      const members=[...ids];
      for(let offset=0;offset<members.length;offset+=500){
        try {
          const rows=await(await request(`/source-files/${frozen.datasetId}/${frozen.versionId}?indexVersionId=${frozen.indexVersionId}`,
            {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(members.slice(offset,offset+500))})).json();
          if(revision!==authRevision)return;
          rows.forEach(file=>files.set(key+'/'+file.assetId,file));
        } catch(error) {
          if(error.status!==403&&error.status!==404)throw error;
        }
      }
    }
    if(revision!==authRevision) return;
    target.innerHTML=sources.map((s,i)=>{
      const frozen=sourceScopes[i], key=scopeKey(frozen);
      const canPreview=canUseScope(frozen,'data.preview'),canDownload=canUseScope(frozen,'data.raw.download');
      const display=scopesFor('data.qa').concat(scopesFor('data.search')).find(row=>scopeKey(row)===key);
      const refs=sourceReferences(s),locator=evidenceLocation(s),preview=canPreview&&!!(s.provenance?.renderedRegion||s.provenance?.registeredImage);
      const downloads=refs.map(ref=>{
        const file=files.get(key+'/'+ref.assetId)||(canDownload?{available:true,logicalPath:ref.logicalPath}:null), label=file?.logicalPath||ref.logicalPath||ref.assetId;
        if(!canDownload)return `<p>${esc(label)} · 未获原件下载授权</p>`;
        return file?.available?`<button class="btn link" type="button" data-source="${esc(ref.assetId)}" data-source-number="${i}">${ref.role==='metadata_verification'?'下载核验元数据':'下载来源'} · ${esc(label)}</button>`:`<p>${esc(label)}：${esc(file?.reason||'来源暂不可用')}</p>`;
      }).join('');
      const temporal=(s.provenance?.temporalMetadata||[]).slice(0,3).map(record=>{
        const events=Object.entries(record.eventValues||{}).map(([field,value])=>`${field}=${value??'未记录'}`).join('；');
        const labels={'context.valid_from':'有效期开始','context.valid_to':'有效期截止','context.created_at':'记录创建时间','context.due_at':'约定期限'};
        const context=Object.entries(record.contextValues||{}).map(([field,value])=>`${labels[field]||field}=${value??'未记录'}`).join('；');
        return `<p>${esc(record.businessStage||'关联记录')} ${esc(record.recordId)}：${esc(events|| (record.eventCapability==='UNVERIFIED'?'事件时间尚未核验':'无独立实际事件时间'))}${context?`；关联上下文（非实际发生/拍摄时间）：${esc(context)}`:''}</p>`;
      }).join('');
      return `<article class="compact" data-evidence-id="${esc(s.evidence_id||'')}"><div><b>来源 ${i+1} · ${esc(s.logical_path||({user_text:'用户补充文字',user_image:'用户图片',dataset_text:'数据空间文本'})[s.source_type]||'授权来源')}</b>
      ${display?`<p>${esc(mdsDatasetName(display))} · ${esc(display.versionName)}</p>`:''}<p class="source-preview">${esc(evidenceSummary(s))}</p><div class="evidence-meta">${s.original_format?`<span>${esc(s.original_format)}</span>`:''}${evidenceKind(s)?`<span>${esc(evidenceKind(s))}</span>`:''}${locator?`<span>定位 ${esc(locator)}</span>`:''}${s.time_range?`<span>${esc(JSON.stringify(s.time_range))}</span>`:''}${Object.keys(s.units||{}).length?`<span>单位 ${esc(JSON.stringify(s.units))}</span>`:''}${(s.quality_flags||[]).map(f=>`<span>${esc(mdsLabel(f,'来源质量提示'))}</span>`).join('')}</div>${temporal}${preview?`<button class="btn link" type="button" data-evidence-preview="${i}">预览证据</button><figure class="evidence-image" hidden></figure>`:''}${downloads}</div></article>`;
    }).join('');
    target.querySelectorAll('[data-evidence-preview]').forEach(button=>button.onclick=()=>previewEvidence(button,sources[Number(button.dataset.evidencePreview)],sourceScopes[Number(button.dataset.evidencePreview)],revision).catch(error=>{if(revision===authRevision&&error.name!=='AbortError')toast(error.message);}));
    sources.forEach((source,index)=>{
      const scope=sourceScopes[index], operation=source.provenance?.nativeReadOperation?.operation;
      if(!canUseScope(scope,'data.preview')||!source.provenance?.multiScopeSource||
        !['read_table','read_json_subtree','read_representation'].includes(operation))return;
      const card=target.children[index]?.firstElementChild;
      if(!card||card.querySelector('[data-evidence-preview]'))return;
      const button=document.createElement('button'), detail=document.createElement('p');
      button.type='button';button.className='btn link';button.textContent='查看完整证据';button.dataset.evidenceRecord='';
      detail.className='source-preview';detail.hidden=true;
      button.onclick=()=>void previewRecord(button,detail,source,scope,revision).catch(error=>{
        if(revision===authRevision&&button.isConnected&&error.name!=='AbortError')toast(error.message);
      });
      card.append(button,detail);
    });
    target.querySelectorAll('[data-source]').forEach(b=>b.onclick=async()=>{
      try{
        if(revision!==authRevision||!b.isConnected) return;
        const index=Number(b.dataset.sourceNumber), frozen=sourceScopes[index];
        if(!canUseScope(frozen,'data.raw.download'))throw new Error('当前来源没有原件下载权限。');
        const response=await request(`/assets/${frozen.datasetId}/${frozen.versionId}/${b.dataset.source}?indexVersionId=${frozen.indexVersionId}`);
        const blob=await response.blob(); if(revision!==authRevision||!b.isConnected) return;
        const url=URL.createObjectURL(blob), a=document.createElement('a');sourceUrls.set(url,b);a.href=url;
        a.download=(files.get(scopeKey(frozen)+'/'+b.dataset.source)?.logicalPath||sourceReferences(sources[index]).find(ref=>ref.assetId===b.dataset.source)?.logicalPath||'来源资料').split('/').pop();document.body.append(a);a.click();a.remove();setTimeout(()=>{URL.revokeObjectURL(url);sourceUrls.delete(url);},10000);
      }catch(e){if(revision===authRevision&&b.isConnected&&e.name!=='AbortError')toast(e.message);}
    });
  }
  function renderAnswer(target,answer) {
    target.replaceChildren();
    const lines=String(answer||'').split('\n');
    const cells=line=>line.trim().replace(/^\||\|$/g,'').split(/(?<!\\)\|/).map(value=>value.trim().replace(/\\\|/g,'|'));
    for(let index=0;index<lines.length;){
      if(lines[index].trim().startsWith('|') && /^\s*\|(?:\s*:?-+:?\s*\|)+\s*$/.test(lines[index+1]||'')){
        const wrapper=document.createElement('div'), table=document.createElement('table'), head=document.createElement('thead'), body=document.createElement('tbody');
        wrapper.className='qa-answer-table';
        const appendRow=(section,line,tag)=>{const row=document.createElement('tr');cells(line).forEach(value=>{const cell=document.createElement(tag);cell.textContent=value;if(tag==='th')cell.scope='col';row.append(cell);});section.append(row);};
        appendRow(head,lines[index],'th');index+=2;
        while(index<lines.length && lines[index].trim().startsWith('|'))appendRow(body,lines[index++],'td');
        table.append(head,body);wrapper.append(table);target.append(wrapper);
      }else target.append(document.createTextNode(lines[index++]+(index<lines.length?'\n':'')));
    }
  }
  async function submit(event) {
    event.preventDefault();
    if(!token) return toast('请先登录数据空间账号');
    if(activeQa) return toast('当前问答仍在处理，可先取消再提交。');
    qaAbort?.abort();
    qaAbort=new AbortController();
    const sequence=++qaSequence, revision=authRevision;
    const body=new FormData(event.currentTarget), images=$('#qaImages').disabled?[]:[...$('#qaImages').files];
    const profile = qaCapabilities?.profiles[body.get('profile')];
    if (!profile) return toast('问答能力尚未就绪，请刷新模型状态。');
    const characters = [...String(body.get('question') || '')].length + [...String(body.get('context') || '')].length;
    if (characters > qaCapabilities.maxUserCharacters) return toast('问题和补充文字合计超过字符上限。');
    if (images.length > qaCapabilities.maxImageCount || images.some(file => file.size > qaCapabilities.maxImageBytes) ||
        images.reduce((size, file) => size + file.size, 0) > qaCapabilities.maxAttachmentBytes) {
      return toast('图片数量、单张大小或附件总大小超过当前上限。');
    }
    if (!body.get('maxTokens')) body.delete('maxTokens');
    body.delete('images');images.forEach(f=>body.append('images',f));
    const userOnly=body.get('inputMode')==='user_data_only';
    const selections=userOnly?[]:selectedScopes('data.qa');
    const scopes=selections.map(window.mdsScopePicker.frozen);
    if(!userOnly&&!scopes.length)return toast('请至少选择一个获授权的数据集。');
    if(scopes.length>window.mdsScopePicker.maxDataScopes)
      return toast(`一次最多选择 ${window.mdsScopePicker.maxDataScopes} 个数据集，请先移除多余范围。`);
    const use=!userOnly&&$('#qaUseSearch').checked;
    body.set('retrievalType','auto');body.set('retrievalMode',use?'search_service':'none');body.set('useSearchService',use?'true':'false');
    body.set('scopes',JSON.stringify(scopes));
    const historyKey = JSON.stringify([revision, body.get('inputMode'),use,scopes]);
    const followUp = $('#qaFollowUp').checked && historyKey === questionHistory.key;
    const prior = followUp ? questionHistory.turns : [];
    const question = String(body.get('question') || '');
    if (prior.reduce((count, turn) => count + [...turn].length, characters) > qaCapabilities.maxUserCharacters)
      return toast('前序问题与本次资料合计超过字符上限，可取消接着问。');
    if (!followUp) resetQuestionHistory();
    body.set('priorQuestions', JSON.stringify(prior));
    $('#qaProgress').dataset.request=JSON.stringify({question:body.get('question'),inputMode:body.get('inputMode'),retrievalMode:body.get('retrievalMode'),retrievalType:body.get('retrievalType'),imageCount:images.length,
      scopes});
    displayedQa=null;
    clearSources($('#qaSources'));clearQaMeta();$('#qaProgress').textContent='正在寻找依据并生成回答…';$('#qaAnswer').textContent='';$('#qaNotice').replaceChildren();$('#qaNotice').hidden=true;
    const current=()=>sequence===qaSequence && revision===authRevision;
    const id=crypto.randomUUID();
    const controller=qaAbort;
    const waitingForAnswer=()=>activeQa?.id===id && current();
    const stageText={
      queued:'正在等待模型处理资源…',
      validating:'正在校验输入与 token 预算…',
      evidence:'正在查找并读取授权证据…',
      generating:'正在生成并核验回答…',
    };
    let polling=false;
    const timer=setInterval(async()=>{
      if(polling||!waitingForAnswer())return;
      polling=true;
      try{
        const status=await (await request('/requests/'+id,{signal:controller.signal})).json();
        if(waitingForAnswer()&&stageText[status.stage])$('#qaProgress').textContent=stageText[status.stage];
      }catch{/* The request may have completed before this poll. */}
      finally{polling=false;}
    },2000);
    activeQa={id,timer};
    $('#qaCancel').hidden=false;
    try{
      const response=await request('',{method:'POST',body,
        headers:{'X-MDS-QA-Request-Id':id},
        signal:AbortSignal.any([controller.signal,AbortSignal.timeout((profile.deadlineSeconds+8)*1000)])});
      const result=await response.json(); if(!current())return;
      if(activeQa?.id===id)stopQaProgress();
      const generationStatus = result.meta?.generation_status;
      const canClarify = generationStatus === 'not_requested' && result.meta?.retrieval_status === 'NEEDS_CLARIFICATION';
      const hasAnswer = ['complete', 'source_values_verified', 'source_interpretation_unverified'].includes(generationStatus);
      if (canClarify || hasAnswer) {
        questionHistory={key:historyKey,turns:[...prior,question].slice(-6)};
        $('#qaFollowUp').disabled=false;
      }
      renderAnswer($('#qaAnswer'),result.answer);$('#qaNotice').textContent=result.notice||'';$('#qaNotice').hidden=!result.notice;
      $('#qaProgress').textContent=result.meta?.generation_status==='validation_failed'?'模型回答未通过证据校验':result.meta?.generation_status==='output_limit'?'回答达到长度上限':result.meta?.generation_status==='not_requested'?'本次未生成回答':result.meta?.generation_status==='partial'?'本次回答尚未完成':result.meta?.generation_status==='source_values_verified'?'已核对来源字段':result.meta?.generation_status==='source_interpretation_unverified'?'模型解释尚待完整核验':'回答完成';
      $('#qaMeta').dataset.traceId=result.meta?.trace_id||'';$('#qaMeta').dataset.retrievalStatus=result.meta?.retrieval_status||'';
      $('#qaMeta').dataset.generationStatus=result.meta?.generation_status||'';$('#qaMeta').dataset.httpStatus=String(response.status);
      $('#qaMeta').textContent=`${result.model} · ${(result.processing_time_ms/1000).toFixed(1)}秒 · 图片 ${result.meta?.image_count||0} 张${scopes.length?` · ${scopes.length} 个冻结数据集`:''}`;
      const container=document.createElement('div');
      await renderSources(container,result.sources||[],use?selections[0]:null,revision);
      if(current()){
        if(scopes.some(scope=>!canUseScope(scope,'data.qa'))){
          invalidateQaResult('本次引用的范围授权已变化，请重新提问。');
          return;
        }
        displayedQa={scopes,previews:scopes.filter(scope=>canUseScope(scope,'data.preview'))};
        $('#qaSources').replaceChildren(...container.childNodes);
      }
    }catch(e){
      if(current()&&e.name!=='AbortError')$('#qaProgress').textContent=e.name==='TimeoutError'?'问答达到总处理时限。':'问答失败：'+e.message;
    }finally{
      if(activeQa?.id===id)stopQaProgress();
    }
  }
  async function requestBusiness(path,options={}) {
    const revision=authRevision;const response=await authorizedFetch(base()+path,options);const result=await response.json();
    if(revision!==authRevision)throw new DOMException('账号已变化','AbortError');return result;
  }
  async function downloadBusiness(path,name){
    const revision=authRevision;const response=await authorizedFetch(base()+path);const blob=await response.blob();
    if(revision!==authRevision)throw new DOMException('账号已变化','AbortError');
    saveBusinessBlob(blob,name);
  }
  function saveBusinessBlob(blob,name){
    const url=URL.createObjectURL(blob);downloadUrls.add(url);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>{URL.revokeObjectURL(url);downloadUrls.delete(url);},1000);
  }
  async function openIngestion(datasetId) {
    if(!token)throw new Error('请先登录数据空间');
    if($('#dashboardIngestion').hidden)throw new Error('当前账号没有上传或维护数据版本的权限');
    const revision=authRevision;
    location.hash='#dashboard';
    $('#dashboardIngestion').open=true;
    await loadIngestSpaces();
    if(revision!==authRevision)return;
    if(datasetId){
      const d=await requestMm('/datasets/'+datasetId+'?ingestion=true');
      if(revision!==authRevision)return;
      $('#qaIngestSpace').value=d.spaceId;
      await loadIngestDatasets(datasetId);
    }
    if(revision===authRevision)$('#dashboardIngestion').scrollIntoView({block:'start',behavior:'smooth'});
  }
  window.dataSpaceSession={request,requestBusiness,downloadBusiness,saveBusinessBlob,openIngestion,openProductScope,refresh,updateIndexingTasks,renderSources,clearSources,selectIndex,
    datasetsFor:scopesFor,scopeFor,selectedScopes,canUseScope,has:operation=>window.mdsIdentity.has(operation),
    get authenticated(){return !!token;},get revision(){return authRevision;},
    get datasets(){return datasets;},get scope(){return scopeFor("data.qa");}};
  function handleIdentity(reason) {
    const identity = window.mdsIdentity;
    const revision = identity.revision;
    if (reason==='identity-verified' && lastIdentityRevision===revision && token && identity.authenticated) {
      // Checking an unchanged identity refreshes granted scopes without
      // canceling an already running request that uses those same scopes.
      void refresh();
      return;
    }
    lastIdentityRevision=revision;
    resetSessionView(reason);
    if(token)void refresh();
  }
  window.addEventListener('dataspace-business-ready',()=>{
    ['qaSourceTimezone','qaSourceTimeColumn','qaSourceEntities','qaSourceUnits','qaAssetMappings'].forEach(id=>$('#'+id).addEventListener('input',()=>{mappingEditRevision++;}));
    $('#qaSaveMapping').onclick=()=>saveMapping().then(()=>toast('映射已保存；安全回填后生效')).catch(e=>toast(e.message));
    const changeInputScope = () => {
      invalidateQaResult('问答输入范围已更新，可重新提问。');
      syncMode();
    };
    $('#qaInputMode').onchange=changeInputScope;
    qaPicker=window.mdsScopePicker.mount('qaScopePicker',()=>{
      savePicker('qaSelection',qaPicker);invalidateQaResult(undefined,true);syncMode();notifyScope();
    });
    searchPicker=window.mdsScopePicker.mount('retrievalScopePicker',()=>{
      savePicker('retrievalSelection',searchPicker);notifyScope();
    });
    $('#qaUseSearch').onchange=changeInputScope;
    $('#qaIngestSpace').onchange=()=>loadIngestDatasets().catch(e=>toast(e.message));
    $('#qaIngestDataset').onchange=()=>loadIngestVersions().catch(e=>toast(e.message));
    $('#qaIngestVersion').onchange=()=>loadIngestVersions($('#qaIngestVersion').value).catch(e=>toast(e.message));
    $('#qaNewDataset').addEventListener('input',()=>window.mdsIdentityUi?.applyCapabilities());
    $('#qaIngestForm').onsubmit=submitIngest;$('#qaBackfill').onclick=backfillIngest;
    $('#qaRefresh').onclick=refresh;$('#qaForm').onsubmit=submit;
    $('#qaCancel').onclick=cancelQa;
    $('#qaProfile').onchange=()=>{$('#qaMaxTokens').value='';syncBudget();};
    const saved=JSON.parse(sessionStorage.getItem('qaDraft')||'null');
    if(saved&&token){$('#qaForm').elements.question.value=saved.question||'';$('#qaContext').value=saved.context||'';$('#qaInputMode').value=saved.inputMode||'hybrid';$('#qaUseSearch').checked=saved.useSearch!==false;if(saved.taskType)$('#qaForm').elements.taskType.value=saved.taskType;if(saved.hadFiles)$('#qaProgress').textContent='本地图片需要重新选择。';}
    const saveDraft=()=>sessionStorage.setItem('qaDraft',JSON.stringify({question:$('#qaForm').elements.question.value,context:$('#qaContext').value,inputMode:$('#qaInputMode').value,useSearch:$('#qaUseSearch').checked,taskType:$('#qaForm').elements.taskType.value,hadFiles:$('#qaImages').files.length>0}));
    $('#qaForm').addEventListener('input',saveDraft);
    $('#qaClearImages').onclick=()=>{$('#qaImages').value='';saveDraft();$('#qaProgress').textContent='已清除本次图片附件。';};
    window.addEventListener('dataspace-identity',event=>handleIdentity(event.detail.reason));
    handleIdentity('identity-verified');
  }, {once: true});
})();
