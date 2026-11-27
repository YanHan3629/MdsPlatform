(() => {
  'use strict';
  const auth = window.mdsIdentity;
  const endpoint = '/data-space/dashboard/datasets';
  const businessLabels = window.mdsBusinessLabels;
  let filters = new Set(), page = 1, nodes = [], user = null, request = 0, signature = '';
  let userRevision, openGallery = null;
  let directoryRequest, shown = new AbortController(), observer, pendingImages = [], activeImages = 0, keywordTimer;
  const objectUrls = new Set();
  const rowList = () => $('#dashboardDatasets');
  const visible = () => location.hash === '#dashboard' && document.visibilityState === 'visible';
  const scopePath = (row, choice) => `${endpoint}/${row.datasetId}/versions/${choice.versionId}`;
  const scopeQuery = choice => new URLSearchParams(choice.indexVersionId ? {indexVersionId:choice.indexVersionId} : {});
  const keyFor = identity => 'mds.dashboard.business.' + identity.userId + '.' + identity.orgId;

  function clearImageDialog() {
    openGallery = null;
    const dialog = $('#dashboardImageDialog');
    if (dialog.open) dialog.close();
    const image = $('#dashboardImage');
    image.removeAttribute('src');
    image.removeAttribute('alt');
    $('#dashboardImageLabel').textContent = '内容预览';
  }

  function clearGallery(gallery) {
    if (openGallery === gallery) clearImageDialog();
    for (const image of $$('img',gallery)) {
      URL.revokeObjectURL(image.src);
      objectUrls.delete(image.src);
    }
    gallery.replaceChildren();
  }

  function clearPreviews() {
    shown.abort();
    shown = new AbortController();
    observer?.disconnect();
    pendingImages = [];
    for (const url of objectUrls) URL.revokeObjectURL(url);
    objectUrls.clear();
    clearImageDialog();
    rowList().replaceChildren();
    signature = '';
  }

  function cancelDirectory() {
    directoryRequest?.abort();
    request++;
    directoryRequest = null;
    clearPreviews();
  }

  function renderBusinessTree() {
    const identity = auth.identity;
    $('#currentOrgName').textContent = mdsOrganizationName(identity);
    $('#businessTree').innerHTML = nodes.map(node => `
      <button class="tree-node ${filters.has(node.code)?'active':''}" type="button"
        data-dashboard-business="${esc(node.code)}" aria-pressed="${filters.has(node.code)}">
        <span class="name">${esc(node.name)}</span>
      </button>`).join('') || '<small>暂无可见业务数据</small>';
    if (filters.size) {
      const clear = document.createElement('button');
      clear.type = 'button';
      clear.className = 'btn link';
      clear.textContent = '清除业务筛选';
      clear.onclick = () => { filters.clear(); changeFilter(); };
      $('#businessTree').append(clear);
    }
    for (const button of $$('[data-dashboard-business]')) {
      button.onclick = () => {
        const code = button.dataset.dashboardBusiness;
        if (filters.has(code)) filters.delete(code);
        else filters.add(code);
        changeFilter();
      };
    }
  }

  function changeFilter() {
    page = 1;
    if (user) sessionStorage.setItem(keyFor(user), JSON.stringify([...filters]));
    renderBusinessTree();
    if (location.hash !== '#dashboard') location.hash = '#dashboard';
    else void load();
  }

  function previewMessage(choice) {
    if (!choice.versionId) return '版本尚未上传';
    return choice.canPreview ? '正在准备内容预览…' : '无内容预览权限';
  }

  function chooseLabel(choice) {
    return `${choice.versionName || '暂无版本'} · ${choice.viewId?'授权视图':'数据版本'}` +
      (choice.indexVersionId?' · 索引 '+choice.indexVersionId.slice(0,8):'');
  }

  function drawRows(result) {
    clearPreviews();
    signature = JSON.stringify(result.items);
    const current = shown;
    observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        observer.unobserve(entry.target);
        entry.target.dispatchEvent(new Event('preview-visible'));
      }
    }, {rootMargin:'32px 0px'});
    for (const row of result.items) {
      const element = document.createElement('article');
      element.className = 'dashboard-dataset';
      element.innerHTML = `
        <div>
          <h3 title="${esc([row.datasetName,row.originalDatasetName,row.catalogName].filter(Boolean).filter((name,i,names)=>names.indexOf(name)===i).join(' · '))}">${esc(row.datasetName)}</h3>
          <p>${esc(mdsOrganizationName(row))} · <span data-business-labels></span></p>
          <p data-version-status></p>
          <select aria-label="${esc(row.datasetName)}的版本与授权范围"></select>
          <button class="btn link" type="button" data-edit hidden>维护业务资料</button>
        </div>
        <div class="dashboard-preview">
          <div class="dashboard-preview-images" aria-label="${esc(row.datasetName)}的内容预览" tabindex="0"></div>
          <div class="dashboard-preview-summary" role="status"></div>
        </div>`;
      rowList().append(element);
      const choices = row.versions;
      const select = $('select', element);
      select.innerHTML = choices.map((choice, index) => option(index, chooseLabel(choice))).join('');
      select.value = String(choices.findIndex(choice => choice.versionId === row.selected.versionId &&
        choice.indexVersionId === row.selected.indexVersionId));
      select.disabled = choices.length < 2;
      const images = $('.dashboard-preview-images', element), summary = $('.dashboard-preview-summary', element);
      const editor = $('[data-edit]', element);
      let rowRequest = new AbortController();
      let firstPage = row===result.items[0] ? result.firstPreview : null;

      function start(choice) {
        const prepared = firstPage;
        firstPage = null;
        rowRequest.abort();
        clearGallery(images);
        rowRequest = new AbortController();
        const signal = AbortSignal.any([rowRequest.signal, current.signal]);
        $('[data-business-labels]', element).textContent =
          choice.businessCodes.map(code => businessLabels[code]).filter(Boolean).join('、') || '待补充业务资料';
        $('[data-version-status]', element).textContent = mdsLabel(choice.status) + ' · 索引' + mdsLabel(choice.indexStatus);
        editor.hidden = !choice.versionId || row.orgId !== auth.identity?.orgId || !auth.has('dataset.draft.manage');
        editor.onclick = () => editMetadata(row, choice);
        summary.textContent = previewMessage(choice);
        if (choice.canPreview) {
          if (prepared) void imagePage(row, choice, images, summary, 0, signal, prepared);
          else {
            element.addEventListener('preview-visible', () => void imagePage(row, choice, images, summary, 0, signal),
              {once:true, signal});
            observer.observe(element);
          }
        }
      }
      select.onchange = () => start(choices[Number(select.value)]);
      start(choices[Number(select.value)] || row.selected);
    }
  }

  function addControl(target, title, action) {
    const button = document.createElement('button');
    button.className = 'btn link';
    button.type = 'button';
    button.textContent = title;
    button.onclick = action;
    target.append(button);
    return button;
  }

  function runImages() {
    while (activeImages < 4 && pendingImages.length) {
      const {tile,path,item,signal} = pendingImages.shift();
      if (signal.aborted || !tile.isConnected) continue;
      activeImages++;
      void fillThumbnail(tile,path,item,signal).finally(() => {
        activeImages--;
        runImages();
      });
    }
  }

  async function imagePage(row, choice, gallery, summary, offset, signal, prepared = null) {
    if (signal.aborted) return;
    clearGallery(gallery);
    summary.textContent = '正在读取当前版本文件…';
    const params = scopeQuery(choice);
    params.set('offset', offset);
    const path = scopePath(row, choice) + '/previews';
    try {
      const data = prepared || await auth.api(path + '?' + params, {signal});
      if (signal.aborted) return;
      if (!data.items.length) {
        summary.textContent = data.status === 'UPLOADING' ? '上传完成后可预览' : '当前版本暂无已上传文件';
        return;
      }
      summary.textContent = `${data.offset+1}–${data.offset+data.items.length}／${data.total} 份`;
      if (offset) addControl(summary, '上一组', () => imagePage(row, choice, gallery, summary, Math.max(0,offset-4), signal));
      if (data.offset + data.items.length < data.total)
        addControl(summary, '下一组', () => imagePage(row, choice, gallery, summary, data.offset+data.items.length, signal));
      for (const item of data.items) {
        const tile = document.createElement('figure');
        tile.className = 'dashboard-preview-image';
        tile.textContent = '等待生成预览…';
        gallery.append(tile);
        if (item.cachedImage && item.cachedImage.length <= Math.ceil(200*1024/3)*4) {
          try {
            const raw = Uint8Array.from(atob(item.cachedImage), ch => ch.charCodeAt(0));
            if (raw.length<=200*1024 && raw[0]===255 && raw[1]===216) {
              presentThumbnail(tile,new Blob([raw],{type:'image/jpeg'}),item,signal);
              continue;
            }
          } catch (error) {
            if (error.name !== 'InvalidCharacterError') throw error;
          }
        }
        const imageParams = scopeQuery(choice);
        imageParams.set('sha256', item.sha256 || '');
        const imagePath = `${path}/${item.fileId}?${imageParams}`;
        pendingImages.push({tile,path:imagePath,item,signal});
      }
      runImages();
    } catch (error) {
      if (signal.aborted || error.name === 'AbortError') return;
      summary.textContent = error.message;
      addControl(summary, '重试', () => imagePage(row, choice, gallery, summary, offset, signal));
    }
  }

  function presentThumbnail(tile, blob, item, signal) {
    if (signal.aborted || !tile.isConnected) return;
    const url = URL.createObjectURL(blob);
    objectUrls.add(url);
    const img = document.createElement('img');
    img.alt = item.logicalPath + ' · ' + item.locator;
    img.title = img.alt;
    img.src = url;
    tile.replaceChildren();
    const zoom=addControl(tile,'',()=>{
      if(signal.aborted || !tile.isConnected)return;
      openGallery = tile.closest('.dashboard-preview-images');
      $('#dashboardImageLabel').textContent=img.alt;
      $('#dashboardImage').src=url;
      $('#dashboardImage').alt=img.alt;
      $('#dashboardImageDialog').showModal();
    });
    zoom.setAttribute('aria-label','放大预览 '+img.alt);
    zoom.append(img);
  }

  async function fillThumbnail(tile, path, item, signal) {
    if (signal.aborted || !tile.isConnected) return;
    tile.textContent = '正在生成预览…';
    try {
      if (!item.sha256) throw new Error('文件尚未完成校验');
      const result = await auth.file(path, {signal:AbortSignal.any([signal,AbortSignal.timeout(29000)])});
      if (signal.aborted || !tile.isConnected) return;
      if (result.contentType !== 'image/jpeg' || !result.blob.size || result.blob.size > 200*1024)
        throw new Error('预览图片校验失败');
      presentThumbnail(tile,result.blob,item,signal);
    } catch (error) {
      if (signal.aborted || !tile.isConnected || error.name === 'AbortError') return;
      if (error.status === 403 || error.status === 404) {
        const gallery = tile.closest('.dashboard-preview-images');
        clearGallery(gallery);
        gallery.nextElementSibling.textContent = '当前文件或授权范围已变化，请刷新查看。';
        void load({quiet:true});
        return;
      }
      tile.replaceChildren();
      const label=error.status===422?'文件无法解析 · 重试':error.status===415?'格式暂无预览':'重试预览';
      const button = addControl(tile, label, () => {
        button.disabled = true;
        pendingImages.push({tile,path,item,signal});
        runImages();
      });
      button.title = error.message;
      button.setAttribute('aria-label', item.logicalPath + '。' + error.message + '。重试预览');
    }
  }

  async function load({quiet = false} = {}) {
    if (quiet && directoryRequest) return;
    const seq = ++request;
    directoryRequest?.abort();
    const controller = new AbortController();
    directoryRequest = controller;
    const signal = controller.signal;
    const allowed = auth.authenticated && auth.has('data.catalog.read');
    $('#dashboardDirectory').hidden = !allowed;
    if (!allowed) { clearPreviews(); directoryRequest = null; return; }
    if (!quiet) {
      clearPreviews();
      $('#dashboardStatus').textContent = '正在读取可见数据集…';
    }
    const params = new URLSearchParams({keyword:$('#catalogKeyword').value.trim(),status:$('#catalogStatus').value,page});
    if(!quiet&&visible()&&$('#dashboardDirectory').getBoundingClientRect().top < innerHeight-110)
      params.set('includeFirstPreview','true');
    for (const code of filters) params.append('business', code);
    try {
      const result = await auth.api(endpoint + '?' + params, {signal});
      if (seq !== request) return;
      nodes = result.business;
      const valid = new Set(nodes.map(node => node.code));
      const filtered = [...filters].filter(code => valid.has(code));
      if (filtered.length !== filters.size) {
        filters = new Set(filtered);
        if(user) sessionStorage.setItem(keyFor(user), JSON.stringify(filtered));
        page = 1;
        return void load();
      }
      renderBusinessTree();
      if (visible() && JSON.stringify(result.items) !== signature) drawRows(result);
      page = result.page;
      $('#dashboardStatus').textContent = result.total ? `共 ${result.total} 个数据集` :
        filters.size || params.get('keyword') || params.get('status') ? '没有符合筛选条件的数据集' : '当前账号暂无可见数据集';
      $('#dashboardPagination').hidden = result.totalPages < 2;
      $('#dashboardPage').textContent = `第 ${page}／${result.totalPages} 页`;
      $('#dashboardPrev').disabled = page <= 1;
      $('#dashboardNext').disabled = page >= result.totalPages;
    } catch (error) {
      if (seq !== request || error.name === 'AbortError') return;
      clearPreviews();
      nodes = [];
      renderBusinessTree();
      $('#dashboardStatus').textContent = error.message;
    } finally {
      if (directoryRequest === controller) directoryRequest = null;
    }
  }

  function editMetadata(row, choice) {
    const dialog = $('#dashboardMetadata'), form = $('form',dialog);
    form.elements.displayName.value = row.datasetName;
    form.elements.evidenceReference.value = '';
    $('#dashboardMetadataCodes').innerHTML = Object.entries(businessLabels).map(([code,name]) =>
      `<label><input type="checkbox" name="business" value="${code}" ${choice.businessCodes.includes(code)?'checked':''} />${name}</label>`).join('');
    $('#dashboardMetadataStatus').textContent = '';
    form.onsubmit = async event => {
      event.preventDefault();
      const values = new FormData(form);
      const revision = auth.revision;
      try {
        await auth.api(scopePath(row,choice) + '/metadata?' + scopeQuery(choice), {method:'PUT',json:{
          displayName:values.get('displayName'), businessCodes:values.getAll('business'),
          evidenceReference:values.get('evidenceReference')
        }});
        if (revision !== auth.revision) return;
        dialog.close();
        await load();
      } catch (error) {
        if(revision === auth.revision && error.name !== 'AbortError') $('#dashboardMetadataStatus').textContent = error.message;
      }
    };
    dialog.showModal();
  }

  function reset() {
    cancelDirectory();
    const identity = auth.identity;
    const accountChanged = !identity || user?.userId !== identity.userId || user?.orgId !== identity.orgId;
    if (accountChanged || userRevision !== auth.revision) {
      const editor = $('#dashboardMetadata'), form = $('form',editor);
      if (editor.open) editor.close();
      form.onsubmit = null;
      form.reset();
      $('#dashboardMetadataCodes').replaceChildren();
      $('#dashboardMetadataStatus').textContent = '';
      $('#dashboardStatus').textContent = '';
      $('#dashboardPage').textContent = '';
      $('#dashboardPagination').hidden = true;
    }
    if (accountChanged) {
      filters.clear();
      if (identity) {
        try { filters = new Set(JSON.parse(sessionStorage.getItem(keyFor(identity)) || '[]')); } catch {}
      }
      $('#catalogKeyword').value = '';
      $('#catalogStatus').value = '';
      page = 1;
    }
    user = identity;
    userRevision = auth.revision;
    nodes = [];
    renderBusinessTree();
    if (identity) void load();
    else $('#dashboardDirectory').hidden = true;
  }

  window.addEventListener('dataspace-business-ready', () => {
    window.addEventListener('dataspace-identity', reset);
    window.addEventListener('dataspace-dataset-changed', () => { if (visible()) void load(); });
    window.addEventListener('hashchange', () => { if (visible()) void load(); else cancelDirectory(); });
    window.addEventListener('pagehide', cancelDirectory);
    window.addEventListener('pageshow', () => { if (visible()) void load(); });
    $('#dashboardPrev').onclick = () => { page--; void load(); };
    $('#dashboardNext').onclick = () => { page++; void load(); };
    $('#catalogKeyword').oninput = () => {
      clearTimeout(keywordTimer);
      keywordTimer = setTimeout(() => { page = 1; void load(); }, 220);
    };
    $('#catalogStatus').onchange = () => { page = 1; void load(); };
    $('#dashboardMetadataClose').onclick = () => $('#dashboardMetadata').close();
    $('#dashboardImageClose').onclick = clearImageDialog;
    $('#dashboardImageDialog').addEventListener('close', () => {
      if (!$('#dashboardImageDialog').open) clearImageDialog();
    });
    $('#demoDispatchBtn').addEventListener('click', () => void load());
    $('#reloadBtn').addEventListener('click', () => void load());
    document.addEventListener('visibilitychange', () => { if (visible()) void load(); else cancelDirectory(); });
    setInterval(() => { if (auth.authenticated && !document.hidden) void load({quiet:true}); }, 8000);
    reset();
  }, {once:true});
})();
