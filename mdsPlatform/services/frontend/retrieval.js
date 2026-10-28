(() => {
  const session = window.dataSpaceSession;
  let capabilities = null, capabilityKey = '', loadSequence = 0, requestSequence = 0, controller = null;
  let conditions = [], conditionFields = [], authRevision = session.revision;
  const operationLabels = {search:'浏览 / 检索',filter:'条件筛选',sort:'排序',count:'计数',sum:'求和',min:'最小值',max:'最大值',average:'样本平均',time_weighted_average:'时间加权平均',duration_above:'超阈值时长',compare_periods:'时段比较',trend_start_end:'起止趋势',trend_regression:'回归趋势',trend_monotonic:'单调趋势',similar_shape:'同空间近似相似'};
  const operators = {eq:'等于',in:'集合（多个值 OR）',gt:'大于',gte:'大于等于',lt:'小于',lte:'小于等于',between:'闭区间',is_null:'已记录为 null',not_null:'已记录且非 null',contains:'数组包含成员'};
  const ops = {all:['search'],text:['search','filter'],visual:['search','filter'],structured:['search','filter','sort','count','sum','min','max','average'],timeseries:['search','count','sum','min','max','average','time_weighted_average','duration_above','compare_periods','trend_start_end','trend_regression','trend_monotonic','similar_shape'],geometry:['search','filter','similar_shape']};
  function select(id, items, placeholder, previous) {
    const element = $('#'+id), old = previous ?? element.value;
    element.innerHTML = (placeholder === null ? '' : option('',placeholder)) + items.map(x=>option(x.value,x.label)).join('');
    if([...element.options].some(o=>o.value===old))element.value=old;
  }
  function label(id,title,content,full=false) {return `<label${full?' class="full"':''}>${esc(title)}${content.replace('{id}',id)}</label>`;}
  const selectHtml = id => `<select id="${id}"></select>`;
  function invalidate(){controller?.abort();requestSequence++;}
  function type(){return $('#retrievalType').value||'all';}
  function assets(){return(capabilities?.assets||[]).filter(a=>a.scope===$('#retrievalScope').value&&(type()==='all'||a.contentTypes.includes(type()))&&(!$('#retrievalFormat').value||a.originalFormat===$('#retrievalFormat').value));}
  function table(){return(capabilities?.tables||[]).find(t=>t.tableId===$('#retrievalTable')?.value);}
  const valueTypeLabels={number:'数值',string:'文字',boolean:'布尔值',array:'数组',object:'对象'};
  function valueType(c,f){
    if(f.type!=='mixed')return f.type;
    const available=['gt','gte','lt','lte','between'].includes(c.op)?['number']:(f.sourceTypes||[]);
    if(!available.includes(c.valueType))c.valueType=available.includes('number')?'number':available[0];
    return c.valueType;
  }
  function conditionEnums(f,kind){
    if(f.typedEnum)return f.typedEnum.filter(e=>e.sourceType===(['date','datetime','timestamp'].includes(kind)?'string':kind)).map(e=>e.sourceType==='number'?e.sourceNumber:String(e.value));
    return (f.enum||[]).map(String);
  }
  function fields(){
    const stage=$('#retrievalDocumentStage')?.value;
    const metadata=type()==='all'?[]:(capabilities?.objectFields||[]).filter(f=>f.contentTypes.includes(type())&&!(stage&&f.name==='business_stage')&&(!f.applicableStages||(stage&&f.applicableStages.includes(stage)))).map(f=>({...f,source:'object'}));
    if(type()==='structured')return[...(table()?.fields||[]).map(f=>({...f,source:'record'})),...metadata];
    if(type()==='timeseries'){
      const asset=$('#retrievalSeries')?.value.split('|')[0];
      const table=(capabilities?.tables||[]).find(t=>t.assetId===asset);
      return[...(table?.fields||[]).filter(f=>['phase','serial_id'].includes(f.name)||f.type==='boolean').map(f=>({...f,source:'record'})),...metadata];
    }
    if(type()==='geometry'){
      const propertyFields=new Map();
      function walk(value,prefix,units){
        if(typeof value==='number'&&Number.isFinite(value)){
          const unit=units[prefix]||(prefix.startsWith('bounds.')?units.length:prefix==='surfaceArea'?units.area:prefix==='volume'?units.volume:null);
          if(unit)propertyFields.set(prefix,{name:prefix,description:prefix,type:'number',unit,operators:['eq','gte','lte','between'],source:'record'});
        }else if(value&&typeof value==='object')Object.entries(value).forEach(([key,child])=>walk(child,prefix?prefix+'.'+key:key,units));
      }
      const ids=new Set(assets().map(a=>a.assetId));
      (capabilities?.geometry||[]).filter(g=>ids.has(g.assetId)).forEach(g=>walk(g.attributes,'',g.units));
      return[...metadata,...propertyFields.values()];
    }
    return metadata;
  }
  function renderConditions(){
    conditionFields=fields();
    conditions=conditions.filter(c=>conditionFields.some(f=>f.name===c.field&&f.source===c.source&&f.operators.includes(c.op)));
    $('#retrievalConditions').hidden=!conditionFields.length;
    $('#retrievalConditionRows').innerHTML=conditions.map((c,i)=>{
      const f=conditionFields.find(f=>f.name===c.field&&f.source===c.source);
      const kind=valueType(c,f);
      if(kind==='boolean'&&c.op==='eq'&&!['true','false'].includes(c.value))c.value='true';
      const needsUnit=f.unitField&&kind==='number'&&!['is_null','not_null'].includes(c.op);
      if(needsUnit&&!f.unitOptions.includes(c.unit))c.unit=f.unitOptions[0]||'';
      const available=['gt','gte','lt','lte','between'].includes(c.op)?['number']:(f.sourceTypes||[]);
      const typeControl=f.type==='mixed'&&!['is_null','not_null'].includes(c.op)?`<label>条件值类型<select data-value-type>${available.map(t=>option(t,valueTypeLabels[t]||t)).join('')}</select></label>`:'';
      const placeholder=c.op==='between'?'下界,上界':['array','object'].includes(kind)?c.op==='in'?'JSON 数组形式的候选集合':'填写完整 JSON 值':c.op==='in'?'值1,值2':f.type==='array'&&c.op==='contains'?'JSON 成员，例如对象或字符串':f.unit||'输入实际值';
      return `<div class="retrieval-condition form-grid" data-condition="${i}"><label>条件字段<select data-field>${conditionFields.map((field,index)=>option(String(index),`${field.description} (${field.name})${field.unit?' · '+field.unit:''}${field.source==='object'?' · 绑定对象':''}`)).join('')}</select></label><label>条件操作<select data-op>${f.operators.map(op=>option(op,operators[op])).join('')}</select></label>${typeControl}<label class="full">条件值${kind==='boolean'&&c.op==='eq'?'<select data-value><option value="true">true</option><option value="false">false</option></select>':`<input data-value list="condition-values-${i}" placeholder="${esc(placeholder)}" value="${esc(c.value||'')}" ${['is_null','not_null'].includes(c.op)?'disabled':''}/><datalist id="condition-values-${i}">${conditionEnums(f,kind).slice(0,200).map(v=>option(v,f.enumLabels?.[v]||v)).join('')}</datalist>`}</label>${needsUnit?`<label>条件单位（记录单位来自 ${esc(f.unitField)}）<select data-unit>${f.unitOptions.map(unit=>option(unit,unit)).join('')}</select></label>`:''}<button data-remove type="button" class="btn link">移除此条件</button></div>`;
    }).join('');
    $('#retrievalConditionRows').querySelectorAll('[data-condition]').forEach(row=>{
      const i=Number(row.dataset.condition), c=conditions[i];
      row.querySelector('[data-field]').value=String(conditionFields.findIndex(f=>f.name===c.field&&f.source===c.source));
      row.querySelector('[data-op]').value=c.op;
      row.querySelector('[data-value]').value=c.value;
      row.querySelector('[data-field]').onchange=e=>{const f=conditionFields[Number(e.target.value)];conditions[i]={field:f.name,source:f.source,op:f.operators[0],value:''};renderConditions();saveDraft();};
      row.querySelector('[data-op]').onchange=e=>{c.op=e.target.value;c.value='';renderConditions();saveDraft();};
      row.querySelector('[data-value]').oninput=e=>{c.value=e.target.value;saveDraft();};
      const valueKind=row.querySelector('[data-value-type]');if(valueKind){valueKind.value=c.valueType;valueKind.onchange=e=>{c.valueType=e.target.value;c.value='';renderConditions();saveDraft();};}
      const unit=row.querySelector('[data-unit]');if(unit){unit.value=c.unit;unit.onchange=e=>{c.unit=e.target.value;saveDraft();};}
      row.querySelector('[data-remove]').onclick=()=>{conditions.splice(i,1);renderConditions();saveDraft();};
    });
  }
  function renderSpecific(){
    const kind=type(), operation=$('#retrievalOperation').value, target=$('#retrievalSpecific');
    const hadFile=!!$('#retrievalQueryImage')?.files.length;
    const saved=Object.fromEntries([...target.querySelectorAll('input,select,textarea')].filter(e=>e.type!=='file').map(e=>[e.id,e.type==='checkbox'?e.checked:e.value]));
    let html='';
    if(['text','visual'].includes(kind))html+=label('retrievalDocumentStage','文档 / 图像关联业务类型',selectHtml('retrievalDocumentStage'),true)+'<p class="full" id="retrievalDocumentTimeInfo"></p>';
    if(kind==='text')html+=label('retrievalQueryImage','可选查询图片（图搜文，最多10 MB）','<input id="retrievalQueryImage" type="file" accept="image/png,image/jpeg,image/webp" />',true);
    if(kind==='structured'){
      html+=label('retrievalTable','业务表 / 工作表',selectHtml('retrievalTable'),true);
      if(['sum','min','max','average','sort'].includes(operation))html+=label('retrievalField',operation==='sort'?'排序字段':'统计字段',selectHtml('retrievalField'));
      if(['sum','min','max','average'].includes(operation))html+='<div id="retrievalAggregateUnitControl"></div>';
      if(operation==='sort')html+=label('retrievalDescending','排序方向','<select id="retrievalDescending"><option value="false">升序（空值最后）</option><option value="true">降序（空值最后）</option></select>');
    }
    if(kind==='timeseries'){
      html+=label('retrievalSeries','序列与来源文件',selectHtml('retrievalSeries'),true)+label('retrievalMetric','实际测量指标',selectHtml('retrievalMetric'));
      html+='<p id="retrievalSeriesInfo" class="full"></p>';
      if(operation!=='similar_shape')html+=label('retrievalTimeStart','开始时间（来源时区）','<input id="retrievalTimeStart" type="datetime-local" step="1" />')+label('retrievalTimeEnd','结束时间（不包含端点）','<input id="retrievalTimeEnd" type="datetime-local" step="1" />');
      if(operation==='compare_periods')html+=label('retrievalCompareStart','比较时段开始','<input id="retrievalCompareStart" type="datetime-local" step="1" />')+label('retrievalCompareEnd','比较时段结束（不包含）','<input id="retrievalCompareEnd" type="datetime-local" step="1" />');
      if(operation==='duration_above')html+=label('retrievalThreshold','数值阈值（所选指标单位）','<input id="retrievalThreshold" type="number" step="any" />');
      if(operation==='similar_shape')html+=label('retrievalCurve','查询曲线（有限数值 JSON 数组）','<textarea id="retrievalCurve"></textarea>',true);
    }
    if(kind==='geometry'&&operation==='similar_shape')html+=label('retrievalSample','当前快照模型样本',selectHtml('retrievalSample'),true)+label('retrievalTolerance','近似容差','<input id="retrievalTolerance" type="number" min="0" max="1" step="0.01" value="0.01" />')+label('retrievalScaleInvariant','尺度口径','<select id="retrievalScaleInvariant"><option value="false">同时核对绝对尺寸</option><option value="true">忽略绝对尺寸</option></select>')+label('retrievalRotationInvariant','旋转口径','<select id="retrievalRotationInvariant"><option value="false">按当前空间特征</option><option value="true">仅返回旋转不变特征</option></select>')+'<p class="full">OBJ 网格与 STEP CAD 分别比较。近似相似不能证明工程可替换性。无法验证的旋转或绝对尺寸会在结果标明。</p>';
    target.innerHTML=html;
    if(hadFile)$('#retrievalProgress').textContent='条件范围已变化；本地查询图片需要重新选择。';
    if($('#retrievalDocumentStage')){
      const stages=[...new Set(assets().flatMap(asset=>asset.verifiedBusinessStages||[]))].sort();
      select('retrievalDocumentStage',stages.map(value=>({value,label:value})),'全部业务类型（不启用关联时间）',saved.retrievalDocumentStage);
      const stage=$('#retrievalDocumentStage').value;
      const dates=(capabilities?.objectFields||[]).filter(field=>field.applicableStages?.includes(stage)&&field.contentTypes.includes(kind));
      $('#retrievalDocumentTimeInfo').textContent=!stage?'选择具体业务类型后，才提供已核验的事件时间或关联上下文条件。图像关联时间不代表拍摄时间。':dates.some(field=>field.timeRole==='actual_business_event')?'时间字段来自已核验的同一业务记录；有效期/约定期限另标为关联上下文，均不代表图片拍摄时间。':'此类型未登记独立实际事件时间。有效期、记录创建时间等如有登记，只作为关联上下文。';
      $('#retrievalDocumentStage').onchange=()=>{invalidate();renderConditions();saveDraft();renderSpecific();};
    }
    if(kind==='structured'){
      const ids=new Set(assets().map(a=>a.assetId));
      select('retrievalTable',(capabilities?.tables||[]).filter(t=>ids.has(t.assetId)).map(t=>({value:t.tableId,label:`${t.logicalPath} · ${JSON.stringify(t.locator)} · ${t.rowCount}行`})),null,saved.retrievalTable);
      if($('#retrievalField'))select('retrievalField',(table()?.fields||[]).filter(f=>operation==='sort'||f.aggregations.includes(operation)).map(f=>({value:f.name,label:`${f.description} (${f.name})${f.unit?' · '+f.unit:''}${f.type==='mixed'&&operation!=='sort'?' · 仅数值参与计算':''}`})),null,saved.retrievalField);
      if($('#retrievalAggregateUnitControl')){
        const field=table()?.fields.find(f=>f.name===$('#retrievalField').value);
        if(field?.unitField){$('#retrievalAggregateUnitControl').innerHTML=label('retrievalAggregateUnit','输出单位（须限定同一记录单位和测量项目）',selectHtml('retrievalAggregateUnit'));select('retrievalAggregateUnit',field.unitOptions.map(unit=>({value:unit,label:unit})),null,saved.retrievalAggregateUnit);}
        $('#retrievalField').onchange=()=>{invalidate();renderSpecific();saveDraft();};
      }
      $('#retrievalTable').onchange=()=>{invalidate();renderSpecific();saveDraft();};
    }
    if(kind==='timeseries'){
      const ids=new Set(assets().map(a=>a.assetId)), unique=new Map();
      (capabilities?.series||[]).filter(s=>ids.has(s.assetId)).forEach(s=>unique.set(s.assetId+'|'+s.seriesId,s));
      select('retrievalSeries',[...unique].map(([key,s])=>({value:key,label:`${s.seriesId} · ${s.logicalPath}`})),null,saved.retrievalSeries);
      const entries=(capabilities?.series||[]).filter(s=>s.assetId+'|'+s.seriesId===$('#retrievalSeries').value);
      select('retrievalMetric',entries.map(s=>({value:s.metric,label:`${s.description||s.metric} (${s.metric}) · ${s.unit||'单位未登记'}`})),null,saved.retrievalMetric);
      const metric=entries.find(s=>s.metric===$('#retrievalMetric').value);
      $('#retrievalSeriesInfo').textContent=metric?`${metric.sourceTimezone||'来源时区未登记'} · ${metric.sampleCount}个采样 · ${metric.start} — ${metric.end} · ${metric.unit||'单位未登记'}`:'当前选择无已登记的采样能力';
      $('#retrievalSeries').onchange=$('#retrievalMetric').onchange=()=>{invalidate();renderSpecific();saveDraft();};
    }
    if($('#retrievalSample'))select('retrievalSample',assets().filter(a=>a.contentTypes.includes('geometry')).map(a=>({value:a.assetId,label:a.logicalPath})),null,saved.retrievalSample);
    // Data-dependent selects above have already been restored and validated.
    for(const [id,value]of Object.entries(saved)){const e=$('#'+id);if(e&&(e.tagName!=='SELECT'||['retrievalDescending','retrievalScaleInvariant','retrievalRotationInvariant'].includes(id)))e.value=value;}
    renderConditions();
  }
  function renderType(reset=false){
    invalidate();if(reset)conditions=[];
    const kind=type();select('retrievalOperation',ops[kind].map(op=>({value:op,label:operationLabels[op]})),null);
    const formats=kind==='all'?[...new Set((capabilities?.assets||[]).filter(a=>a.scope===$('#retrievalScope').value).map(a=>a.originalFormat))]:(capabilities?.dataTypes||[]).find(t=>t.id===kind)?.formats||[];
    select('retrievalFormat',formats.sort().map(f=>({value:f,label:f})),'全部适用格式');
    renderSpecific();saveDraft();
  }
  function saveDraft(){
    const values=Object.fromEntries([...$('#retrievalForm').querySelectorAll('[id]')].filter(e=>['INPUT','SELECT','TEXTAREA'].includes(e.tagName)&&e.type!=='file').map(e=>[e.id,e.value]));
    sessionStorage.setItem('retrievalDraft',JSON.stringify({scope:session.scope?.indexVersionId,values,conditions,hadFile:!!$('#retrievalQueryImage')?.files.length}));
  }
  function clearScopeForm(){
    conditions=[];conditionFields=[];$('#retrievalForm').reset();
    $('#retrievalSpecific').replaceChildren();$('#retrievalConditionRows').replaceChildren();$('#retrievalConditions').hidden=true;
    select('retrievalType',[{value:'all',label:'综合检索'}],null,'all');
    select('retrievalOperation',[{value:'search',label:operationLabels.search}],null,'search');
    select('retrievalFormat',[],'全部适用格式','');
  }
  async function load(){
    const scope=session.scope, seq=++loadSequence;
    if(authRevision!==session.revision){authRevision=session.revision;capabilities=null;capabilityKey='';clearScopeForm();invalidate();$('#retrievalResults').replaceChildren();$('#retrievalApplied').textContent='';delete $('#retrievalApplied').dataset.request;$('#retrievalProgress').textContent='等待检索';}
    select('retrievalDataset',session.datasets.map(d=>({value:d.indexVersionId,label:`${d.datasetName} / ${d.versionName} / ${d.isOwnerCandidate?'私有候选（未启用）':d.isActiveIndex?'当前索引':'产品冻结索引'} ${d.indexVersionId.slice(0,8)}`})),'选择数据集',scope?.indexVersionId||'');
    if(!scope){capabilities=null;capabilityKey='';clearScopeForm();$('#retrievalCapabilityStatus').textContent='请登录并选择可用索引。';$('#retrievalSubmit').disabled=true;return;}
    if(capabilityKey===scope.indexVersionId)return;
    $('#retrievalSubmit').disabled=true;$('#retrievalCapabilityStatus').textContent='正在核对当前快照的实际能力…';
    try{
      const result=await(await session.request(`/capabilities/${scope.datasetId}/${scope.versionId}/${scope.indexVersionId}`)).json();
      if(seq!==loadSequence||session.scope?.indexVersionId!==scope.indexVersionId)return;
      capabilities=result.filters;capabilityKey=scope.indexVersionId;conditions=[];
      select('retrievalType',[{value:'all',label:'综合检索'},...capabilities.dataTypes.filter(t=>t.assetCount).map(t=>({value:t.id,label:t.label}))],null);
      $('#retrievalCapabilityStatus').textContent=`${scope.versionName} · ${capabilities.assets.length}个原始文件 · 枚举来自当前快照`;
      const draft=JSON.parse(sessionStorage.getItem('retrievalDraft')||'null');
      if(draft?.scope===scope.indexVersionId){for(const id of ['retrievalType','retrievalScope','retrievalFormat','retrievalOperation'])if($('#'+id))$('#'+id).value=draft.values[id]||$('#'+id).value;conditions=draft.conditions||[];}
      renderType();
      if(draft?.scope===scope.indexVersionId){
        for(const [id,value]of Object.entries(draft.values)){const e=$('#'+id);if(e)e.value=value;}
        renderSpecific();for(const[id,value]of Object.entries(draft.values)){const e=$('#'+id);if(e)e.value=value;}
        conditions=draft.conditions||[];renderConditions();
        if(draft.hadFile)$('#retrievalProgress').textContent='本地查询图片需要重新选择。';
      }
      $('#retrievalSubmit').disabled=false;
    }catch(e){if(seq===loadSequence&&e.name!=='AbortError')$('#retrievalCapabilityStatus').textContent=`能力未就绪：${e.message}`;}
  }
  function parseValue(c,f){
    if(['is_null','not_null'].includes(c.op))return {value:null};
    const kind=valueType(c,f);
    if(c.op==='contains'&&['array','object'].includes(f.type)){
      if(!c.value.trim())throw new Error(`请填写${f.name}的成员`);
      try{return {value:JSON.parse(c.value)};}catch(e){if(/^[\[{]/.test(c.value.trim()))throw new Error('成员对象需要有效 JSON');return {value:c.value.trim()};}
    }
    const exact=kind==='number'&&f.numericPredicateEncoding==='source-number-lexeme-v1';
    const scalar=raw=>{const v=raw.trim();if(kind==='number'){
      if(!/^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/.test(v)||v.length>32768)throw new Error(`${f.name}需要有效数值`);
      if(exact)return v;
      const n=Number(v);if(!Number.isFinite(n)||(n===0&&/[1-9]/.test(v.split(/[eE]/)[0]))||(Number.isInteger(n)&&!Number.isSafeInteger(n)))throw new Error(`${f.name}超出该快照支持的条件精度范围`);return n;
    }if(kind==='boolean'){if(!['true','false'].includes(v))throw new Error('布尔值须为true或false');return v==='true';}
      if(kind==='string'&&v.startsWith('"')){let parsed;try{parsed=JSON.parse(v);}catch(e){throw new Error('带引号的文字条件需要完整的 JSON 字符串');}if(typeof parsed!=='string')throw new Error('条件值需要文字');return parsed;}
      return v;};
    let value;
    if(['array','object'].includes(kind)){
      try{value=JSON.parse(c.value);}catch(e){throw new Error('条件值需要有效 JSON');}
      const candidates=c.op==='in'?value:[value];
      if(!Array.isArray(candidates)||!candidates.length||!candidates.every(v=>kind==='array'?Array.isArray(v):v&&typeof v==='object'&&!Array.isArray(v)))throw new Error('条件值与所选类型不一致');
    }else if(c.op==='in'||c.op==='between'){
      value=c.value.split(/[,，]/).map(scalar);if(c.op==='between'&&value.length!==2)throw new Error('区间需要两个端点');
    }else{if(!c.value.trim())throw new Error(`请填写${f.name}条件值`);value=scalar(c.value);}
    return {value,...(exact?{valueEncoding:f.numericPredicateEncoding}:{})};
  }
  function plan(){
    const kind=type(), op=$('#retrievalOperation').value;
    const value={taskIntent:`${kind}_${op}`,retrievers:kind==='all'?['text','visual']:[kind],contentType:kind,operation:op,
      queryText:$('#retrievalQuery').value.trim(),corpusScope:$('#retrievalScope').value,topK:Number($('#retrievalTopK').value),formats:$('#retrievalFormat').value?[$('#retrievalFormat').value]:[]};
    if(['text','visual'].includes(kind)&&$('#retrievalDocumentStage').value)value.objectConditions=[{field:'business_stage',op:'eq',value:$('#retrievalDocumentStage').value}];
    if(kind==='structured'){
      value.tableId=$('#retrievalTable').value;
      if(!value.tableId)throw new Error('请选择实际业务表/工作表');
      if(op==='sort'){value.sortField=$('#retrievalField').value;value.sortDescending=$('#retrievalDescending').value==='true';}
      if(['sum','min','max','average'].includes(op)){value.aggregateField=$('#retrievalField').value;const f=table().fields.find(f=>f.name===value.aggregateField);if(f?.unit)value.unit=f.unit;else if(f?.unitField)value.unit=$('#retrievalAggregateUnit').value;}
    }
    if(kind==='timeseries'){
      const [asset,series]=$('#retrievalSeries').value.split('|');value.assetIds=[asset];value.targetEntity=series;value.metric=$('#retrievalMetric').value;
      const metric=capabilities.series.find(s=>s.assetId===asset&&s.seriesId===series&&s.metric===value.metric);
      if(metric?.unit)value.unit=metric.unit;
      function range(start,end){const a=$('#'+start).value,b=$('#'+end).value;if(!a&&!b)return null;if(!a||!b)throw new Error('时间范围必须提供完整起止时间');if(metric.sourceTimezone!=='Asia/Shanghai')throw new Error('当前界面只支持已登记Asia/Shanghai来源时区的时间计算');return{start:a+'+08:00',end:b+'+08:00',boundary:'[start,end)'};}
      if(op!=='similar_shape'){const r=range('retrievalTimeStart','retrievalTimeEnd');if(r)value.timeRange=r;if(op!=='search'&&!r)throw new Error('精确计算需要明确时间范围');}
      if(op==='compare_periods')value.compareTimeRange=range('retrievalCompareStart','retrievalCompareEnd');
      if(op==='duration_above'){if(!$('#retrievalThreshold').value)throw new Error('请输入阈值');value.threshold=Number($('#retrievalThreshold').value);}
      if(op==='similar_shape')value.querySeries=JSON.parse($('#retrievalCurve').value);
    }
    if(kind==='geometry'&&op==='similar_shape'){value.queryAssetId=$('#retrievalSample').value;value.tolerance=Number($('#retrievalTolerance').value);value.scaleInvariant=$('#retrievalScaleInvariant').value==='true';value.rotationInvariant=$('#retrievalRotationInvariant').value==='true';}
    for(const c of conditions){const f=conditionFields.find(f=>f.name===c.field&&f.source===c.source);if(!f)continue;const key=c.source==='object'?'objectConditions':'hardConditions';const unit=!['is_null','not_null'].includes(c.op)&&(f.type!=='mixed'||valueType(c,f)==='number')&&(f.unitField?c.unit:f.unit);(value[key]||=[]).push({field:c.field,op:c.op,...parseValue(c,f),...(unit?{unit}:{})});}
    return value;
  }
  function professionalSummary(e){
    if(e.facts.text)return e.facts.text;
    if(e.evidenceType==='calculation'&&e.facts.arithmetic==='exact-rational-source-lexemes-v1'){
      const facts=e.facts,exact=facts.exactValue??(facts.exactRatio?`${facts.exactRatio.numerator}/${facts.exactRatio.denominator}`:'未得到可计算结果');
      const scope=facts.operation==='count'?`匹配 ${facts.matchedRowCount} 条记录；计数包含全部匹配记录`:`参与计算 ${facts.numericInputCount} 项；未参与的空值或非数值 ${facts.excludedNullOrNonNumericCount} 项`;
      return `${operationLabels[facts.operation]||facts.operation} · ${facts.field||''}：精确值 ${exact}${facts.decimalRounded?`\n十进制近似 ${facts.decimalValue}`:''}\n${scope}`;
    }
    const record=e.provenance?.sourceRecord;if(!record?.numberLexemes)return JSON.stringify(e.facts);
    function present(value,pointer,field){
      const info=record.numberLexemes[field]||record.numberLexemes[pointer];
      if(info?.lexeme!==undefined&&(typeof value==='number'||(info.sourceType==='number'&&info.decodedValueStatus==='SOURCE_LEXEME_STRING'&&value===info.lexeme)))return info.lexeme;
      if(Array.isArray(value))return '['+value.map((item,index)=>present(item,pointer+'/'+index)).join(',')+']';
      if(value&&typeof value==='object')return '{'+Object.entries(value).map(([key,item])=>JSON.stringify(key)+':'+present(item,pointer+'/'+key.replace(/~/g,'~0').replace(/\//g,'~1'))).join(',')+'}';
      return JSON.stringify(value);
    }
    const pointer=record.locator?.jsonPointer??e.locator?.jsonPointer??'';
    return '{'+Object.entries(e.facts).map(([key,value])=>JSON.stringify(key)+':'+present(value,pointer+'/'+key.replace(/~/g,'~0').replace(/\//g,'~1'),key)).join(',')+'}';
  }
  async function submit(event){
    event.preventDefault();if(!session.authenticated)return toast('请先登录数据空间账号');
    invalidate();$('#retrievalResults').replaceChildren();$('#retrievalApplied').textContent='';delete $('#retrievalApplied').dataset.request;
    let query;try{query=plan();}catch(e){$('#retrievalProgress').textContent=`条件无效：${e.message}`;return;}
    const scope=session.scope;if(!scope)return toast('请选择数据集');controller=new AbortController();const seq=requestSequence,revision=session.revision;
    const image=$('#retrievalQueryImage')?.files[0];
    let path='/retrieval/execute', body=JSON.stringify({datasetId:scope.datasetId,versionId:scope.versionId,indexVersionId:scope.indexVersionId,plan:query}),headers={'Content-Type':'application/json'};
    if(image){
      if(image.size>10*1024*1024||query.operation!=='search'){$('#retrievalProgress').textContent='图搜文需要检索操作，查询图片不得超过10 MB';return;}
      path='/retrieval/image-to-text';body=new FormData();headers={};
      ['datasetId','versionId','indexVersionId'].forEach(k=>body.set(k,scope[k]));body.set('plan',JSON.stringify(query));body.set('file',image);
    }
    $('#retrievalProgress').textContent='正在执行检索…';$('#retrievalResults').replaceChildren();
    const described=conditions.map(c=>{const f=conditionFields.find(f=>f.name===c.field&&f.source===c.source);return `${f?.description||c.field}${f?.type==='mixed'&&!['is_null','not_null'].includes(c.op)?'（'+(valueTypeLabels[valueType(c,f)]||valueType(c,f))+'）':''} ${operators[c.op]} ${c.value||'空值'}`;});
    $('#retrievalApplied').textContent=`${scope.datasetName} / ${scope.versionName} · ${operationLabels[query.operation]}${query.formats.length?' · '+query.formats.join('、'):''}${table()?' · '+table().logicalPath:''} · 生效条件：${described.join('；')||'无额外硬条件'}${query.timeRange?' · 左闭右开窗口 '+query.timeRange.start+' 至 '+query.timeRange.end:''}`;
    $('#retrievalApplied').dataset.request=JSON.stringify({datasetId:scope.datasetId,versionId:scope.versionId,indexVersionId:scope.indexVersionId,plan:query});
    try{
      const result=await(await session.request(path,{method:'POST',headers,body,signal:controller.signal})).json();
      if(seq!==requestSequence||revision!==session.revision)return;
      const evidence=result.evidence||[];
      const incomplete=evidence.find(e=>e.evidenceType==='calculation'&&e.facts.status);
      $('#retrievalProgress').textContent=incomplete?`计算未完成：${incomplete.facts.status}`:evidence.length?`已返回${evidence.length}条结果${result.limitations?.length?'；部分能力有说明':''}`:'零条匹配结果（条件已执行）';
      const sources=evidence.map(e=>({asset_id:e.assetId,logical_path:e.preview?.logicalPath,source_type:e.semanticSpace,
        content_preview:(e.semanticSpace?.startsWith('geometry.')?'三维模型近似相似不能证明工程可替换性。\n':'')+professionalSummary(e),locator:e.locator,time_range:e.timeRange,units:e.units,quality_flags:e.qualityFlags,
        original_format:e.provenance?.originalFormat,provenance:e.provenance}));
      const output=document.createElement('div');await session.renderSources(output,sources,scope,revision);
      if(seq!==requestSequence||revision!==session.revision)return;
      $('#retrievalResults').replaceChildren(...output.childNodes);
      for(const message of result.limitations||[]){const p=document.createElement('p');p.textContent=message;$('#retrievalResults').append(p);}
    }catch(e){if(seq===requestSequence&&revision===session.revision&&e.name!=='AbortError')$('#retrievalProgress').textContent=`检索失败：${e.message}`;}
  }
  document.addEventListener('DOMContentLoaded',()=>{
    window.addEventListener('dataspace-scope',load);$('#retrievalDataset').onchange=()=>{invalidate();session.selectIndex($('#retrievalDataset').value);};
    $('#retrievalType').onchange=()=>renderType(true);$('#retrievalScope').onchange=()=>renderType(true);$('#retrievalFormat').onchange=()=>{invalidate();conditions=[];renderSpecific();saveDraft();};
    $('#retrievalOperation').onchange=()=>{invalidate();renderSpecific();saveDraft();};
    $('#retrievalForm').oninput=saveDraft;$('#retrievalForm').onsubmit=submit;
    $('#retrievalAddCondition').onclick=()=>{const f=conditionFields[0];if(f){conditions.push({field:f.name,source:f.source,op:f.operators[0],value:''});renderConditions();saveDraft();}};
    load();
  });
})();
