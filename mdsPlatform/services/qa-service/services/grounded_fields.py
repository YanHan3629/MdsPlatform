"""Present source-matched fields without asking the language model to recopy them.

Values come exclusively from the final, budgeted evidence set. This is a
deterministic view of records, separate from the model's visual observations.
"""
import json
import re


QUALITY_FIELDS = ('qc_type', 'object_id', 'item', 'unit', 'sample_measurements_json',
    'quantity_checked', 'lower_limit', 'upper_limit', 'aggregation_method',
    'actual_value', 'min_value', 'max_value', 'qualified_count', 'rejected_count',
    'result', 'disposition', 'rule_source')


def temporal_rows(question,sources):
    if not re.search(r'日期|时间|有效期',question) or not any(source.provenance.get('temporalRoleVersion')=='verified-temporal-roles-1' for source in sources):return []
    rows=[];seen=set()
    for index,source in enumerate(sources,1):
        if source.provenance.get('temporalRoleVersion')!='verified-temporal-roles-1':continue
        targets=source.provenance.get('explicitEntities',[])
        for record in source.provenance.get('temporalMetadata',[]):
            if targets and record['recordId'] not in targets:continue
            if not targets and not source.provenance.get('explicitSourcePath'):continue
            key=(record['recordId'],json.dumps(record.get('eventValues',{}),sort_keys=True),json.dumps(record.get('contextValues',{}),sort_keys=True))
            if key in seen:continue
            seen.add(key)
            rows.append(dict(recordId=record['recordId'],eventValues=record.get('eventValues',{}),
                contextValues=record.get('contextValues',{}),eventCapability=record.get('eventCapability'),source=index))
    return rows


def render_temporal(rows):
    if not rows:return ''
    lines=['日期语义核验（按当前来源及原记录元数据）：']
    for row in rows:
        event='；'.join(key+'='+str(value) for key,value in row['eventValues'].items()) or '未核验独立实际事件时间'
        context='；'.join(key+'='+str(value) for key,value in row['contextValues'].items())
        lines.append(f"- {row['recordId']}：{event}。"+(f"关联上下文日期：{context}。" if context else '')+f"[来源{row['source']}]")
    lines+=['资料日期、合同有效期和实际业务事件时间具有不同含义，不能直接互相替代；关联记录时间也不等于文档制作或图片拍摄时间。']
    return '\n'.join(lines)


def field_plan(question, sources):
    rows={};conflicts=set();requested=set();observations={}
    for index,source in enumerate(sources,1):
        if source.source_type!='dataset_text' or source.provenance.get('factPresentationVersion')!='source-matched-fields-1':continue
        focus=list(dict.fromkeys(source.provenance.get('expandedFields',[])))
        for claim in source.provenance.get('factClaims',[]):
            values=claim.get('fieldValues',{})
            fields=focus
            if not fields and re.search(r'检验|不合格',question) and {'actual_value','result'}<=values.keys():
                fields=QUALITY_FIELDS
            # A literal field request is also valid for other business tables.
            if not fields:fields=[name for name in values if re.search(r'(?<![A-Za-z0-9_])'+re.escape(name)+r'(?![A-Za-z0-9_])',question)]
            explicit=source.provenance.get('explicitEntities',[])
            if explicit and claim['primaryKey'] not in explicit:continue
            requested.update((claim['primaryKey'],field) for field in focus)
            for field in fields:
                if field not in values:continue
                projected=source.provenance.get('contextProjection')
                if projected and field not in projected['fields']:continue
                key=(claim.get('businessType'),claim['primaryKey'],field)
                row=dict(subject=claim['primaryKey'],field=field,value=values[field],
                    label=claim.get('fieldLabels',{}).get(field) or field,
                    unit=claim.get('fieldUnits',{}).get(field),source=index)
                observations.setdefault(key,[]).append(row)
                if key in rows and (rows[key]['value']!=row['value'] or rows[key]['unit']!=row['unit']):conflicts.add(key)
                else:rows.setdefault(key,row)
    return dict(rows=[row for key,row in rows.items() if key not in conflicts],
        conflicts=[dict(subject=key[1],field=key[2],observations=observations[key]) for key in sorted(conflicts)],
        missingFields=[dict(subject=subject,field=field) for subject,field in sorted(requested-{(key[1],key[2]) for key in rows})])


def _display(value):
    if isinstance(value,str):
        try:
            parsed=json.loads(value)
            if isinstance(parsed,(list,dict)):value=parsed
        except (TypeError,ValueError):pass
    if isinstance(value,list) and value and all(isinstance(item,dict) and 'value' in item for item in value):
        return '；'.join(f"{item.get('sample_id',i)}：{item['value']}" for i,item in enumerate(value,1))
    return json.dumps(value,ensure_ascii=False,separators=(',',':')) if isinstance(value,(list,dict,bool)) else str(value)


def render_fields(plan):
    if not plan['rows'] and not plan.get('missingFields') and not plan['conflicts']:return ''
    def cell(value):return str(value).replace('|','\\|').replace('\n',' ')
    lines=['记录核验（按实际来源字段整理）：']
    if plan['rows']:lines+=['', '| 对象 | 字段 | 原始记录值 | 依据 |', '| --- | --- | --- | --- |']
    for row in plan['rows']:
        value=_display(row['value'])+((' '+row['unit']) if row['unit'] else '')
        label=row['label'] if row['label']==row['field'] else f"{row['label']}（{row['field']}）"
        if row['label']==row['field']+'；来源为本文件实际字段':label=row['field']
        lines.append('| '+' | '.join(cell(value) for value in [row['subject'],label,value,f"[来源{row['source']}]"])+' |')
    for conflict in plan['conflicts']:
        lines+=['',conflict['subject']+'.'+conflict['field']+' 存在来源冲突：'+
            '；'.join(_display(row['value'])+((' '+row['unit']) if row['unit'] else '')+f"[来源{row['source']}]" for row in conflict['observations'])+
            '。无法确定唯一值，不取平均或选择其中一项。']
    if plan.get('missingFields'):lines+=['','当前采用的来源尚未核验以下字段，无法确定其值或适用性：'+
        '；'.join(row['subject']+'.'+row['field'] for row in plan['missingFields'])+'。']
    return '\n'.join(lines)


def visual_only_question(question, plan, sources):
    if not plan['rows']:return None
    if not any(source.source_type in {'dataset_visual','user_image'} for source in sources):return ''
    if not re.search(r'外观|图片|图像|照片|标注图|颜色|门板|显示窗',question):return ''
    if re.search(r'标注图',question):
        return '请观察图片中的物件与标注排布，简述实际可见的物件、标签或颜色。不要复述文字资料中的数值或结论。每句引用对应图片的[来源n]。'
    return '请只描述实际图片中可见的外形、颜色、部件及开合状态。不要推断材质，不复述文字资料中的型号或物理参数。每句引用对应图片的[来源n]。'
