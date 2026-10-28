"""Resolve one calculation source from authorized frozen capabilities only."""
import json,re

VERSION='calculation-source-scope-2026-10-24.1'
EXT=r'\.(?:jsonl?|csv|xlsx|xls|txt|pdf|png|jpe?g|webp|svg|obj|stp|step)\b'

def matches(text,value):
    return list(re.finditer(r'(?<![A-Za-z0-9_.-])'+re.escape(value)+r'(?![A-Za-z0-9_.-])',text,re.I))

def named_source(question,filters):
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus']
    remaining=question;selected={};spans=[]
    for asset in assets:
        found=matches(question,asset['logicalPath'])
        if found:
            selected[asset['assetId']]=asset;spans.extend(match.span() for match in found)
    for start,end in sorted(spans,reverse=True):remaining=remaining[:start]+' '*(end-start)+remaining[end:]
    spans=[]
    for asset in assets:
        name=asset['logicalPath'].rsplit('/',1)[-1];found=matches(remaining,name)
        if any(match.start() and remaining[match.start()-1] in '/\\' for match in found):
            raise ValueError('NEEDS_CLARIFICATION: 指定完整路径不属于当前冻结快照，不能按同名文件替换。')
        if found:
            selected[asset['assetId']]=asset;spans.extend(match.span() for match in found)
    for start,end in sorted(set(spans),reverse=True):remaining=remaining[:start]+' '*(end-start)+remaining[end:]
    if re.search(EXT,remaining,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 部分指定文件未能匹配当前授权快照，不能忽略来源条件。')
    if len(selected)>1:
        raise ValueError('NEEDS_CLARIFICATION: 本次计算需要唯一原始来源，请明确文件或分开计算；同一序列的不同来源不能自动合并。')
    return next(iter(selected.values()),None)

def series_source(question,filters,entries,intent):
    asset=named_source(question,filters)
    if asset:entries=[entry for entry in entries if entry['assetId']==asset['assetId'] and entry['logicalPath']==asset['logicalPath']]
    if intent.allowed:entries=[entry for entry in entries if entry['originalFormat'] in intent.allowed]
    if not entries:
        raise ValueError('NEEDS_CLARIFICATION: 指定文件或来源格式没有所问序列和指标的已登记计算能力，不能替换成其他来源。')
    preferred=[entry for entry in entries if entry['originalFormat'] in intent.preferred]
    if preferred:entries=preferred
    unique={json.dumps(entry,ensure_ascii=False,sort_keys=True,separators=(',',':')):entry for entry in entries}
    if len(unique)!=1:
        raise ValueError('NEEDS_CLARIFICATION: 该序列和指标对应多个原始来源，请明确文件或来源格式，不能默认选用CSV。')
    return next(iter(unique.values()))
