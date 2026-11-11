(() => {
  let token = false;
  let account = '';
  let scopeSets = new Map(), searchIndex = '';
  const downloadUrls = new Set();
  let datasets = [];
  let busy = false;
  let authRevision = 0;
  let lastAuthEventRevision = -1;
  let authAbort = new AbortController();
  let qaSequence = 0;
  let qaAbort = null;
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
  function invalidateQaResult() {
    qaSequence++;qaAbort?.abort();
    $('#qaAnswer').textContent='';clearSources($('#qaSources'));clearQaMeta();$('#qaNotice').replaceChildren();$('#qaNotice').hidden=true;
    $('#qaProgress').textContent='数据范围已更新，可重新提问。';delete $('#qaProgress').dataset.request;
  }
  const base = () => state.api.replace(/\/$/, '');
  const authBase = () => base().replace(/\/data-space$/, '/auth');
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
    const spaces = await mmList('/spaces');
    if (selection !== ingestSelectionRevision) return;
    const previous = preferred || $('#qaIngestSpace').value;
    $('#qaIngestSpace').innerHTML = option('', '选择已有空间') + spaces.map(s => option(s.spaceId, s.spaceName)).join('');
    $('#qaIngestSpace').value = spaces.some(s => s.spaceId === previous) ? previous : preferred ? '' : spaces[0]?.spaceId || '';
    await loadIngestDatasets(preferredDataset, selection);
  }
  async function loadIngestDatasets(preferred, selection = ++ingestSelectionRevision) {
    const spaceId = $('#qaIngestSpace').value;
    const datasetsInSpace = spaceId ? await mmList(`/spaces/${spaceId}/datasets`) : [];
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
    const versions = datasetId ? await mmList(`/datasets/${datasetId}/versions`) : [];
    if (selection !== ingestSelectionRevision) return;
    const previous = preferred || $('#qaIngestVersion').value;
    $('#qaIngestVersion').innerHTML = option('', '选择已有版本')
      + versions.map(v => option(v.versionId, `${v.versionName} · ${v.versionStatus}`)).join('');
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
      unitMapping: JSON.parse($('#qaSourceUnits').value.trim() || '{}'),
      assetMappings: JSON.parse($('#qaAssetMappings').value.trim() || '{}') };
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
        current.permissionVersion !== actor.permissionVersion || !auth.has('dataset.upload')) return;
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
    if (!auth.has('dataset.upload')) return toast('当前账号没有上传权限');
    const newSpace = $('#qaNewSpace').value.trim();
    const newDataset = $('#qaNewDataset').value.trim();
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
      if (!files.length) throw new Error('没有可入库的业务文件；README/数据集说明.md已排除，请选择业务文件');
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
      $('#qaIngestProgress').textContent = `回填任务 ${task.status} · ${task.stage}；重复请求会复用同一任务。`;
      await refresh();
    } catch (error) { if(revision===authRevision&&error.name!=='AbortError')$('#qaIngestProgress').textContent = `回填失败：${error.message}`; }
    finally { if(revision===authRevision)button.disabled=false;window.mdsIdentityUi?.applyCapabilities(); }
  }
  function showAccount() {
    $('#qaAccount').textContent = token ? `已登录：${account}` : '尚未登录';
    $('#qaLogin').hidden = !!token; $('#qaLogout').hidden = !token;
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
    token=window.mdsIdentity.authenticated;account=window.mdsIdentity.identity?.username||'';
    datasets=[];scopeSets=new Map();searchIndex='';ingestLoaded=false;busy=false;
    for(const url of downloadUrls)URL.revokeObjectURL(url);downloadUrls.clear();
    for (const key of ['qaToken','qaAccount','qaIndex','qaDraft','retrievalDraft']) sessionStorage.removeItem(key);
    $('#qaDataset').innerHTML = option('', '不使用数据空间检索');
    $('#qaAnswer').textContent = ''; $('#qaSources').replaceChildren(); clearQaMeta();
    $('#qaProgress').textContent = '请登录后提问'; $('#qaNotice').replaceChildren(); $('#qaNotice').hidden = true; $('#qaForm').reset();
    delete $('#qaProgress').dataset.request;
    $('#qaIngestForm').reset(); ingestSelectionRevision++;
    for (const [id,label] of [['qaIngestSpace','登录后加载'],['qaIngestDataset','先选择空间'],['qaIngestVersion','先选择数据集']]) $('#'+id).innerHTML = option('',label);
    $('#qaIndexing').replaceChildren(); showAccount(); syncMode(); notifyScope();
    delete $('#qaIngestVersion').dataset.status;
    delete $('#qaIngestProgress').dataset.skipped;
    $('#qaIngestProgress').textContent='登录后可操作真实数据版本。';
    window.mdsIdentityUi?.applyCapabilities();
  }
  function selectedDataset() { return datasets.find(d => d.indexVersionId === $('#qaDataset').value); }
  function scopesFor(operation){return scopeSets.get(operation)||[];}
  function scopeFor(operation){
    const selected=operation==='data.search'?searchIndex:$('#qaDataset').value;
    const item=scopesFor(operation).find(d=>d.indexVersionId===selected);return item?{...item}:null;
  }
  function canUseScope(scope,operation){
    return !!scope&&window.mdsIdentity.has(operation)&&scopesFor(operation).some(d=>d.datasetId===scope.datasetId&&d.versionId===scope.versionId&&
      (operation==='data.raw.download'||d.indexVersionId===scope.indexVersionId));
  }
  function selectIndex(value,operation='data.qa') {
    if(operation==='data.search'){searchIndex=scopesFor(operation).some(d=>d.indexVersionId===value)?value:'';notifyScope();return;}
    invalidateQaResult();
    $('#qaDataset').value=datasets.some(d=>d.indexVersionId===value)?value:'';
    sessionStorage.setItem('qaIndex',$('#qaDataset').value);syncMode();notifyScope();
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
    $('#qaDataset').disabled = mode === 'user_data_only'; $('#qaUseSearch').disabled = mode === 'user_data_only';
    const d = selectedDataset();
    $('#qaDatasetInfo').textContent = mode === 'user_data_only' ? '本次只使用提交的资料。'
      : d ? `${d.datasetName} · ${d.versionName} · ${d.sampleCount} 个文件 · ${d.isOwnerCandidate?'私有候选，未启用':'索引 READY'}`
      : '请选择当前获授权的数据范围。';
  }
  async function refresh() {
    const revision=authRevision;
    const health=async()=>{
      try{
        const response=await fetch(base()+'/qa/health',{credentials:'omit',redirect:'error',signal:authAbort.signal});
        const value=await response.json();if(revision!==authRevision)return;
        const ready=value.model?.loaded&&value.vllm?.status==='ok';
        $('#qaHealth').textContent=ready?'问答模型已就绪':'模型正在启动或不可用';
        $('#qaHealth').className=ready?'badge':'badge muted';
      }catch(error){if(revision===authRevision&&error.name!=='AbortError')$('#qaHealth').textContent='问答服务未就绪';}
    };
    const scopes=async()=>{
      if(!token)return;
      const operations=['data.catalog.read','data.preview','data.search','data.qa','data.raw.download'];
      const values=await Promise.all(operations.map(async operation=>[operation,window.mdsIdentity.has(operation)?
        await window.mdsIdentity.api('/users/me/scopes?operation='+encodeURIComponent(operation)):[]]));
      if(revision!==authRevision)return;
      const previous=$('#qaDataset').value||sessionStorage.getItem('qaIndex');
      scopeSets=new Map(values);datasets=scopesFor('data.qa');
      if(!scopesFor('data.search').some(d=>d.indexVersionId===searchIndex)){
        searchIndex=(scopesFor('data.search').find(d=>d.isActiveIndex)||scopesFor('data.search')[0])?.indexVersionId||'';
      }
      $('#qaDataset').innerHTML=option('','选择已授权问答范围')+datasets.map(d=>option(d.indexVersionId,
        d.datasetName+' / '+d.versionName+' / '+(d.isActiveIndex?'当前索引':'授权冻结索引')+' '+d.indexVersionId.slice(0,8))).join('');
      const fallback=datasets.find(d=>d.isActiveIndex)||datasets[0];
      $('#qaDataset').value=datasets.some(d=>d.indexVersionId===previous)?previous:previous===''?'':fallback?.indexVersionId||'';
      if(previous&&previous!==$('#qaDataset').value)invalidateQaResult();
      syncMode();notifyScope();
    };
    const indexing=async()=>{
      if(!token||!window.mdsIdentity.has('dataset.index.build')){updateIndexingTasks([]);return;}
      const response=await request('/indexing');const tasks=await response.json();
      if(revision===authRevision)updateIndexingTasks(tasks);
    };
    const ingestion=token&&window.mdsIdentity.has('data.catalog.read')&&window.mdsIdentity.has('dataset.upload')&&!ingestLoaded&&!ingestWork?
      loadIngestSpaces().then(()=>{if(revision===authRevision){
        ingestLoaded=true;
        const progress=$('#qaIngestProgress');
        if(progress.textContent==='登录后可操作真实数据版本。')progress.textContent='已加载当前账号可维护的数据版本。';
      }}):Promise.resolve();
    const results=await Promise.allSettled([health(),scopes(),indexing(),ingestion]);
    if(revision===authRevision)results.forEach(result=>{if(result.status==='rejected'&&result.reason.name!=='AbortError')toast(result.reason.message);});
  }
  function updateIndexingTasks(tasks){
    $('#qaIndexing').innerHTML=(tasks||[]).slice(0,5).map(t=>`<div class="compact"><div><b>${esc(t.datasetName)} / ${esc(t.versionName)}</b><p>${esc(t.status)} · ${esc(t.stage)}${t.error?' · '+esc(t.error):''}</p><p>任务 ${esc(t.taskId||t.task_id||'')}</p></div></div>`).join('');
    const selected=(tasks||[]).find(t=>t.versionId===$('#qaIngestVersion').value&&t.status==='READY');
    if(selected&&$('#qaIngestVersion').selectedOptions[0]){$('#qaIngestVersion').dataset.status='READY';$('#qaIngestVersion').selectedOptions[0].textContent=`${selected.versionName} · READY`;}
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
    if(at.page!==undefined)parts.push(`第 ${at.page} 页`);
    if(at.sheet)parts.push(`工作表 ${at.sheet}`);
    if(readRows.length){
      parts.push(`原文件第 ${[...new Set(readRows.map(row=>row.rawRow))].join('、')} 行`);
      const cells=[...new Set((source.evidence_bundle?.facts||[]).map(f=>f.locator?.cell).filter(Boolean))];
      if(cells.length)parts.push(`单元格 ${cells.join('、')}`);
    }else if(at.cell||at.range)parts.push(at.cell||at.range);
    if(Array.isArray(at.cells)&&at.cells.length)parts.push(`单元格 ${at.cells.join('、')}`);
    if(at.jsonlLine!==undefined)parts.push(`原文件第 ${at.jsonlLine} 行`);
    if(at.jsonPointer!==undefined)parts.push(`JSON ${at.jsonPointer||'根节点'}`);
    if(at.rawRow!==undefined)parts.push(`行 ${at.rawRow}`);
    if(!readRows.length&&at.rawRowStart!==undefined)parts.push(`起始行 ${at.rawRowStart}`);
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
  function evidenceSummary(source) {
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
    return facts.slice(0,6).map(f=>{
      if(source.original_format==='XLSX'&&(f.qualityFlags||[]).includes('FORMULA_CACHE_UNVERIFIED'))return `${f.field}: 公式 ${f.sourceValue}；原有缓存 ${JSON.stringify(f.value)}（缓存未验证，本次未执行公式）`;
      const pointer=f.locator?.jsonPointer||'',record=(source.evidence_bundle?.content?.records||[]).find(row=>pointer.startsWith((row.locator?.jsonPointer||'')+'/'));
      const numbers=source.evidence_bundle?.content?.numberLexemes||record?.numberLexemes||{};
      return `${f.field}: ${f.numberSourceExpression??(typeof f.value==='string'&&!['JSON','JSONL'].includes(source.original_format)?f.value:sourceValue(f.value,pointer,numbers))}${f.unit?' '+f.unit:''}`;
    }).join('\n')
      +(facts.length>6?`\n已读取 ${facts.length} 项，此处展示前 6 项。`:'');
  }
  async function previewEvidence(button,source,scope,revision) {
    if(revision!==authRevision||!button.isConnected)return;
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
  async function renderSources(target, sources, scope, revision = authRevision) {
    if(revision!==authRevision)return;
    releaseSourceUrls(target);
    const files = new Map();
    const canPreview=canUseScope(scope,'data.preview'),canDownload=canUseScope(scope,'data.raw.download');
    if (scope&&canPreview) {
      const ids = [...new Set(sources.flatMap(s=>sourceReferences(s).map(r=>r.assetId)))];
      for(let offset=0;offset<ids.length;offset+=500){
        const rows=await(await request(`/source-files/${scope.datasetId}/${scope.versionId}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(ids.slice(offset,offset+500))})).json();
        if(revision!==authRevision) return;
        rows.forEach(f=>files.set(f.assetId,f));
      }
    }
    if(revision!==authRevision) return;
    target.innerHTML=sources.map((s,i)=>{
      const refs=sourceReferences(s),locator=evidenceLocation(s),preview=canPreview&&!!(s.provenance?.renderedRegion||s.provenance?.registeredImage);
      const downloads=refs.map(ref=>{
        const file=files.get(ref.assetId)||(canDownload?{available:true,logicalPath:ref.logicalPath}:null), label=file?.logicalPath||ref.logicalPath||ref.assetId;
        if(!canDownload)return `<p>${esc(label)} · 未获原件下载授权</p>`;
        return file?.available?`<button class="btn link" type="button" data-source="${esc(ref.assetId)}">${ref.role==='metadata_verification'?'下载核验元数据':'下载来源'} · ${esc(label)}</button>`:`<p>${esc(label)}：${esc(file?.reason||'来源暂不可用')}</p>`;
      }).join('');
      const temporal=(s.provenance?.temporalMetadata||[]).slice(0,3).map(record=>{
        const events=Object.entries(record.eventValues||{}).map(([field,value])=>`${field}=${value??'未记录'}`).join('；');
        const labels={'context.valid_from':'有效期开始','context.valid_to':'有效期截止','context.created_at':'记录创建时间','context.due_at':'约定期限'};
        const context=Object.entries(record.contextValues||{}).map(([field,value])=>`${labels[field]||field}=${value??'未记录'}`).join('；');
        return `<p>${esc(record.businessStage||'关联记录')} ${esc(record.recordId)}：${esc(events|| (record.eventCapability==='UNVERIFIED'?'事件时间尚未核验':'无独立实际事件时间'))}${context?`；关联上下文（非实际发生/拍摄时间）：${esc(context)}`:''}</p>`;
      }).join('');
      return `<article class="compact" data-evidence-id="${esc(s.evidence_id||'')}"><div><b>来源 ${i+1} · ${esc(s.logical_path||s.source_type)}</b><p class="source-preview">${esc(evidenceSummary(s))}</p><div class="evidence-meta">${s.original_format?`<span>${esc(s.original_format)}</span>`:''}${evidenceKind(s)?`<span>${esc(evidenceKind(s))}</span>`:''}${locator?`<span>定位 ${esc(locator)}</span>`:''}${s.time_range?`<span>${esc(JSON.stringify(s.time_range))}</span>`:''}${Object.keys(s.units||{}).length?`<span>单位 ${esc(JSON.stringify(s.units))}</span>`:''}${(s.quality_flags||[]).map(f=>`<span>${esc(f)}</span>`).join('')}</div>${temporal}${preview?`<button class="btn link" type="button" data-evidence-preview="${i}">预览证据</button><figure class="evidence-image" hidden></figure>`:''}${downloads}</div></article>`;
    }).join('');
    target.querySelectorAll('[data-evidence-preview]').forEach(button=>button.onclick=()=>previewEvidence(button,sources[Number(button.dataset.evidencePreview)],scope,revision).catch(error=>{if(revision===authRevision&&error.name!=='AbortError')toast(error.message);}));
    target.querySelectorAll('[data-source]').forEach(b=>b.onclick=async()=>{
      try{
        if(revision!==authRevision||!b.isConnected) return;
        const response=await request(`/assets/${scope.datasetId}/${scope.versionId}/${b.dataset.source}`);
        const blob=await response.blob(); if(revision!==authRevision||!b.isConnected) return;
        const url=URL.createObjectURL(blob), a=document.createElement('a');sourceUrls.set(url,b);a.href=url;
        a.download=(files.get(b.dataset.source)?.logicalPath||'source').split('/').pop();document.body.append(a);a.click();a.remove();setTimeout(()=>{URL.revokeObjectURL(url);sourceUrls.delete(url);},10000);
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
    qaAbort?.abort(); qaAbort=new AbortController(); const sequence=++qaSequence, revision=authRevision;
    const body=new FormData(event.currentTarget), images=$('#qaImages').disabled?[]:[...$('#qaImages').files];
    if(images.length>2||images.some(f=>f.size>10*1024*1024)){
      $('#qaProgress').textContent='输入无效：最多上传2张图片，每张不超过10 MB';
      delete $('#qaProgress').dataset.request;
      clearSources($('#qaSources'));clearQaMeta();
      $('#qaAnswer').textContent='';$('#qaNotice').replaceChildren();$('#qaNotice').hidden=true;
      return toast('最多上传2张图片，每张不超过10 MB');
    }
    body.delete('images');images.forEach(f=>body.append('images',f));
    const userOnly=body.get('inputMode')==='user_data_only';
    const scope=!userOnly&&selectedDataset()?{...selectedDataset()}:null;
    if(!userOnly&&!scope)return toast('请选择当前获授权的数据范围。');
    const use=!userOnly&&$('#qaUseSearch').checked;
    body.set('retrievalType','auto');body.set('retrievalMode',use?'search_service':'none');body.set('useSearchService',use?'true':'false');
    ['datasetId','versionId','indexVersionId'].forEach(k=>scope?body.set(k,scope[k]):body.delete(k));
    body.set('maxTokens','512');
    $('#qaProgress').dataset.request=JSON.stringify({question:body.get('question'),inputMode:body.get('inputMode'),retrievalMode:body.get('retrievalMode'),retrievalType:body.get('retrievalType'),imageCount:images.length,
      ...(scope?{datasetId:scope.datasetId,versionId:scope.versionId,indexVersionId:scope.indexVersionId}:{})});
    clearSources($('#qaSources'));clearQaMeta();$('#qaProgress').textContent='正在寻找依据并生成回答…';$('#qaAnswer').textContent='';$('#qaNotice').replaceChildren();$('#qaNotice').hidden=true;
    const current=()=>sequence===qaSequence && revision===authRevision;
    try{
      const response=await request('',{method:'POST',body,signal:qaAbort.signal});const result=await response.json(); if(!current())return;
      renderAnswer($('#qaAnswer'),result.answer);$('#qaNotice').textContent=result.notice||'';$('#qaNotice').hidden=!result.notice;
      $('#qaProgress').textContent=result.meta?.generation_status==='validation_failed'?'模型回答未通过证据校验':result.meta?.generation_status==='output_limit'?'回答达到长度上限':result.meta?.generation_status==='not_requested'?'本次未生成回答':result.meta?.generation_status==='partial'?'本次回答尚未完成':result.meta?.generation_status==='source_values_verified'?'已核对来源字段':result.meta?.generation_status==='source_interpretation_unverified'?'模型解释尚待完整核验':'回答完成';
      $('#qaMeta').dataset.traceId=result.meta?.trace_id||'';$('#qaMeta').dataset.retrievalStatus=result.meta?.retrieval_status||'';
      $('#qaMeta').dataset.generationStatus=result.meta?.generation_status||'';$('#qaMeta').dataset.httpStatus=String(response.status);
      $('#qaMeta').textContent=`${result.model} · ${(result.processing_time_ms/1000).toFixed(1)}秒 · 图片 ${result.meta?.image_count||0} 张${scope?` · ${scope.datasetName} / ${scope.versionName}`:''}`;
      const container=document.createElement('div');
      await renderSources(container,result.sources||[],use?scope:null,revision);
      if(current())$('#qaSources').replaceChildren(...container.childNodes);
    }catch(e){if(current()&&e.name!=='AbortError')$('#qaProgress').textContent=`问答失败：${e.message}`;}
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
    const details=$('#qaIngestForm').closest('details');if(details)details.open=true;
    await loadIngestSpaces();
    if(datasetId){const d=await requestMm('/datasets/'+datasetId);$('#qaIngestSpace').value=d.spaceId;await loadIngestDatasets(datasetId);}
    $('#qaIngestForm').scrollIntoView({block:'start',behavior:'smooth'});
  }
  window.dataSpaceSession={request,requestBusiness,downloadBusiness,saveBusinessBlob,openIngestion,openProductScope,refresh,updateIndexingTasks,renderSources,clearSources,selectIndex,
    datasetsFor:scopesFor,scopeFor,canUseScope,has:operation=>window.mdsIdentity.has(operation),
    get authenticated(){return !!token;},get revision(){return authRevision;},
    get datasets(){return datasets;},get scope(){const d=selectedDataset();return d?{...d}:null;}};
  window.addEventListener('dataspace-identity',event=>{resetSessionView(event.detail.reason);if(token)void refresh();});
  document.addEventListener('DOMContentLoaded',()=>{
    ['qaSourceTimezone','qaSourceTimeColumn','qaSourceEntities','qaSourceUnits','qaAssetMappings'].forEach(id=>$('#'+id).addEventListener('input',()=>{mappingEditRevision++;}));
    $('#qaSaveMapping').onclick=()=>saveMapping().then(()=>toast('映射已保存；安全回填后生效')).catch(e=>toast(e.message));
    $('#qaInputMode').onchange=syncMode;$('#qaDataset').onchange=()=>selectIndex($('#qaDataset').value);$('#qaUseSearch').onchange=syncMode;
    $('#qaIngestSpace').onchange=()=>loadIngestDatasets().catch(e=>toast(e.message));
    $('#qaIngestDataset').onchange=()=>loadIngestVersions().catch(e=>toast(e.message));
    $('#qaIngestVersion').onchange=()=>loadIngestVersions($('#qaIngestVersion').value).catch(e=>toast(e.message));
    $('#qaNewDataset').addEventListener('input',()=>window.mdsIdentityUi?.applyCapabilities());
    $('#qaIngestForm').onsubmit=submitIngest;$('#qaBackfill').onclick=backfillIngest;
    $('#qaRefresh').onclick=refresh;$('#qaLogout').onclick=()=>window.mdsIdentity.logout();$('#qaForm').onsubmit=submit;
    const saved=JSON.parse(sessionStorage.getItem('qaDraft')||'null');
    if(saved&&token){$('#qaForm').elements.question.value=saved.question||'';$('#qaContext').value=saved.context||'';$('#qaInputMode').value=saved.inputMode||'hybrid';$('#qaUseSearch').checked=saved.useSearch!==false;if(saved.taskType)$('#qaForm').elements.taskType.value=saved.taskType;if(saved.hadFiles)$('#qaProgress').textContent='本地图片需要重新选择。';}
    const saveDraft=()=>sessionStorage.setItem('qaDraft',JSON.stringify({question:$('#qaForm').elements.question.value,context:$('#qaContext').value,inputMode:$('#qaInputMode').value,useSearch:$('#qaUseSearch').checked,taskType:$('#qaForm').elements.taskType.value,hadFiles:$('#qaImages').files.length>0}));
    $('#qaForm').addEventListener('input',saveDraft);
    $('#qaClearImages').onclick=()=>{$('#qaImages').value='';saveDraft();$('#qaProgress').textContent='已清除本次图片附件。';};
    $('#qaLogin').onsubmit=async event=>{
      event.preventDefault();const form=event.currentTarget,credentials=formData(form);
      try{const result=await window.mdsIdentity.login(credentials);form.reset();if(result.activationRequired)toast('请设置新密码完成账号激活。');}
      catch(error){if(error.name!=='AbortError')toast(error.message);}
    };
    showAccount();syncMode();refresh();
  });
})();
