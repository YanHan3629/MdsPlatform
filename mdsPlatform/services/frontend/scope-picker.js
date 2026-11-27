(() => {
  'use strict';
  let maxDataScopes = 8;
  const displayPickers = new Set();
  void fetch(new URL('qa-capabilities.json', document.baseURI), {credentials:'same-origin'})
    .then(response => response.ok ? response.json() : null)
    .then(configuration => {
      const limit = configuration?.maxDataScopes;
      if (!Number.isInteger(limit) || limit < 1 || limit > 8) return;
      maxDataScopes = limit;
      displayPickers.forEach(show => show());
    })
    .catch(() => {});
  const identityFields = ['datasetId','versionId','indexVersionId'];
  const frozen = scope => Object.fromEntries(identityFields.map(key => [key,scope[key]]));
  const same = (a,b) => identityFields.every(key => a?.[key] === b?.[key]);

  function mount(id,onChange) {
    const root = document.getElementById(id);
    root.innerHTML = `<legend>数据集与冻结版本</legend>
      <div class="scope-picker-controls">
        <label>搜索数据集<input type="search" placeholder="名称、企业或业务" /></label>
        <label>添加可用范围<select></select></label>
      </div>
      <div class="scope-picker-tags" role="list" aria-label="已选数据集"></div>
      <div class="filters"><span data-scope-count role="status"></span>
        <button type="button" class="btn link" data-clear>清空选择</button></div>`;
    const search = $('input',root), select = $('select',root), tags = $('.scope-picker-tags',root);
    const count = $('[data-scope-count]',root), clear = $('[data-clear]',root);
    let available = [], picked = [], disabled = false;
    const label = row => [mdsDatasetName(row),row.orgName?mdsOrganizationName(row):null,row.businessCodes?.map(code=>mdsBusinessLabels[code]).filter(Boolean).join('、'),
      row.versionName].filter(Boolean).join(' · ');
    function show() {
      const keyword = search.value.trim().toLowerCase();
      const matches = available.filter(row => label(row).toLowerCase().includes(keyword) && !picked.some(item => same(item,row)));
      select.innerHTML = option('',matches.length ? '选择后添加' : '没有可添加的就绪范围') +
        matches.map(row => option(row.indexVersionId,label(row)+' · '+(row.isActiveIndex?'当前索引':'授权索引')+
          ' '+row.indexVersionId.slice(0,8))).join('');
      select.disabled = disabled || !matches.length;
      search.disabled = disabled;
      clear.disabled = disabled || !picked.length;
      tags.replaceChildren();
      for (const row of picked) {
        const item = document.createElement('div'), remove = document.createElement('button');
        item.className = 'scope-picker-tag';
        item.setAttribute('role','listitem');
        item.textContent = label(row);
        remove.type = 'button';
        remove.disabled = disabled;
        remove.textContent = '移除';
        remove.setAttribute('aria-label','移除 ' + label(row));
        remove.onclick = () => replace(picked.filter(value => !same(value,row)));
        item.append(remove);
        tags.append(item);
      }
      count.textContent = disabled ? '仅使用本次附件及补充文字' : `已选 ${picked.length}／${maxDataScopes} 个数据集`;
    }
    function replace(items,notify=true) {
      const changed = picked.length !== items.length || picked.some((item,index) => !same(item,items[index]));
      picked = items;
      show();
      if (notify && changed) onChange();
    }
    search.oninput = show;
    clear.onclick = () => replace([]);
    select.onchange = () => {
      const item = available.find(row => row.indexVersionId === select.value);
      if (!item) return;
      const prior = picked.findIndex(row => row.datasetId === item.datasetId);
      if (prior < 0 && picked.length >= maxDataScopes) {
        show();
        count.textContent = `最多选择 ${maxDataScopes} 个数据集，请先移除一项。`;
        return;
      }
      const next = [...picked];
      if (prior < 0) next.push(item);
      else next[prior] = item;
      replace(next);
    };
    displayPickers.add(show);
    show();
    return {
      get items(){ return picked.map(row => ({...row})); },
      update(rows, preferred=picked){ available=rows; replace(preferred.map(value=>rows.find(row=>same(row,value))).filter(Boolean),false); },
      choose(index){ replace(available.filter(row => row.indexVersionId === index)); },
      reset(){ search.value=''; available=[]; replace([],false); },
      disable(value){ if(disabled!==value){disabled=value;show();} }
    };
  }
  window.mdsScopePicker = {mount,frozen,get maxDataScopes(){return maxDataScopes;}};
})();
