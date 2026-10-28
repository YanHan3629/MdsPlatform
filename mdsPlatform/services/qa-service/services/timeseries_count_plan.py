"""Resolve count objects and one actual source table without reading metric values."""
import json,re
from services.calculation_source_scope import series_source
from services.question_time_boundary import boundary

TIME_NAMES={'timestamp','datetime','time','date','recorded_at','时间','日期','时间戳','采样时间'}

def plan_count(question,filters,series_ids,intent):
    record_intent=bool(re.search(r'多少条(?:记录)?|多少个记录|记录数(?:量)?|原始记录计数|物理记录',question))
    sample_intent=bool(re.search(r'有效(?:数值)?(?:采样|样本)(?:点)?(?:数|数量)|多少个有效(?:数值)?(?:采样|样本)',question))
    if record_intent==sample_intent:
        raise ValueError('NEEDS_CLARIFICATION: 请明确要计算物理记录数，还是去重后的有效数值样本数；两者对空值和重复时刻的处理不同。')
    mode='records' if record_intent else 'numeric_samples'
    if len(series_ids)!=1:raise ValueError('NEEDS_CLARIFICATION: 本次计数需要明确一个实际序列。')
    entity=next(iter(series_ids));entries=[entry for entry in filters.get('series',[]) if entry['seriesId']==entity]
    named_metrics={entry['metric'] for entry in entries if re.search(r'(?<![A-Za-z0-9_])'+re.escape(entry['metric'])+r'(?![A-Za-z0-9_])',question)}
    if len(named_metrics)>1 or (mode=='numeric_samples' and len(named_metrics)!=1):
        raise ValueError('NEEDS_CLARIFICATION: 有效数值样本计数需要一个实际指标；记录计数可不指定指标。')
    metric=next(iter(named_metrics),None)
    if metric:entries=[entry for entry in entries if entry['metric']==metric]
    if mode=='records':
        # Only source/time identities participate in choosing a carrier. The
        # many numeric metrics of the same series do not create new sources.
        keys=('assetId','logicalPath','originalFormat','sourceTimezone','sourceTimePolicy')
        entries=list({json.dumps({key:entry.get(key) for key in keys},ensure_ascii=False,sort_keys=True):
            {key:entry.get(key) for key in keys} for entry in entries}.values())
    primary=series_source(question,filters,entries,intent)
    candidates=[]
    for table in filters.get('tables',[]):
        fields={field['name']:field for field in table['fields']}
        if table['assetId']!=primary['assetId'] or metric and metric not in fields:continue
        if len([name for name in fields if name.lower() in TIME_NAMES])!=1:continue
        selector=fields.get('series_id')
        if selector:
            if not selector.get('enumTruncated',True) and entity not in selector.get('enum',[]):continue
        elif entity!=primary['assetId']:continue
        candidates.append(table)
    named_sheets=[table for table in candidates if table.get('locator',{}).get('sheet') and table['locator']['sheet'] in question]
    if named_sheets:candidates=named_sheets
    if len(candidates)!=1:
        raise ValueError('NEEDS_CLARIFICATION: 计数需要唯一的原始表或工作表，请说明工作表名称；不能合并不同表。')
    table=candidates[0]
    timestamps=re.findall(r'\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?(?:[+-]\d{2}:\d{2}|Z)?',question)
    if len(timestamps)!=2:raise ValueError('NEEDS_CLARIFICATION: 请明确两个 ISO 起止时刻。')
    def stamp(value):
        if re.search(r'Z$|[+-]\d\d:\d\d$',value):return value.replace(' ','T')
        if primary.get('sourceTimezone')!='Asia/Shanghai':raise ValueError('NEEDS_CLARIFICATION: 所选来源未登记可直接采用的时区，请在时间中明确偏移。')
        return value.replace(' ','T')+'+08:00'
    residual=question
    for value in [primary['logicalPath'],primary['logicalPath'].rsplit('/',1)[-1],entity,metric or '',*timestamps,table.get('locator',{}).get('sheet','')]:
        if value:residual=re.sub(r'(?<![A-Za-z0-9_])'+re.escape(value)+r'(?![A-Za-z0-9_])',' ',residual,flags=re.I)
    unknown_words=set(re.findall(r'[A-Za-z_][A-Za-z0-9_]*',residual))-{'CSV','TXT','XLSX','csv','txt','xlsx','ISO','JSON','json'}
    if unknown_words or re.search(r'\d|且|同时|并且|只算|只统计|剔除|排除|除外|不包含|大于|小于|超过|非空记录|有效记录|业务键|唯一记录',residual):
        raise ValueError('NEEDS_CLARIFICATION: 本次仅支持明确序列和窗口的记录数或有效数值样本数；附加过滤或业务去重口径需要另行说明。')
    return {'taskIntent':'natural_exact_measurement','retrievers':['timeseries'],'contentType':'timeseries','operation':'count',
        'countMode':mode,'targetEntity':entity,'assetIds':[primary['assetId']],'formats':[primary['originalFormat']],
        'tableId':table['tableId'],'metric':metric,'unit':None,'queryText':question,
        'timeRange':{'start':stamp(timestamps[0]),'end':stamp(timestamps[1]),'boundary':boundary(question)}}
