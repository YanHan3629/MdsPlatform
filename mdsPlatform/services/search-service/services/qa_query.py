"""Separate source/output instructions from the semantic recall question."""
import re

FORMATS=r'(?:JSONL|JPEG|XLSX|WEBP|JSON|STEP|PDF|CSV|PNG|JPG|SVG|STP|OBJ|TXT|表格|图片|照片|图像|图纸)'


def requested_fields(question,available,aliases):
    fields=[name for name,labels in aliases.items() if name in available and any(label in question for label in labels)]
    fields.extend(name for name in sorted(available) if name not in fields and re.search(r'(?<![A-Za-z0-9_])'+re.escape(name)+r'(?![A-Za-z0-9_])',question,re.I))
    return fields

def content_query(question):
    text=re.sub(r'(?:请)?(?:用|以|输出为?|回答用|回答采用)\s*'+FORMATS+r'(?:形式)?\s*(?:来)?(?:回答|输出|呈现|展示|格式)',' ',question,flags=re.I)
    text=re.sub(r'(?:只|仅)?(?:依据|参考|基于|使用|用)\s*'+FORMATS+r'(?:\s*(?:格式)?(?:图片|图像|照片|文件|资料))?',' ',text,flags=re.I)
    text=re.sub(r'(?:优先|尽量)(?:使用)?\s*'+FORMATS,' ',text,flags=re.I)
    text=re.sub(r'结合\s*'+FORMATS+r'(?:\s*[和与、及]\s*'+FORMATS+r')*',' ',text,flags=re.I)
    return re.sub(r'\s+',' ',text).strip(' ，,。；;')

def visual_subjects(entities,known):
    """Verified business labels aid recall but never become pixel facts."""
    values=[]
    for identifier in entities:
        record=known[identifier].get('values',{})
        for field in ['form_factor','part_class']:
            value=record.get(field)
            if isinstance(value,str) and value and value not in values:values.append(value)
    return values


def requests_product_picture(question,entities,known):
    """Explicit product pictures have a different target from related scenes."""
    return (any(known[entity].get('entity_type')=='products' for entity in entities)
        and bool(re.search(r'产品(?:图片|图像|照片|图(?!纸)|外观)',question))
        and not re.search(r'仓储|物流|检验|检修|服务|包装|场景|现场|运输',question))


def named_sources(question,assets,known):
    """Resolve explicitly named files inside this authorized snapshot only."""
    selected={};spans=[]
    for identifier,asset in assets.items():
        name=asset['logicalPath'].replace('\\','/').rsplit('/',1)[-1]
        matches=list(re.finditer(r'(?<![A-Za-z0-9_.-])'+re.escape(name)+r'(?![A-Za-z0-9_.-])',question,re.I))
        if not matches:continue
        bound={entity for binding in asset.get('bindings',[]) for entity in [*binding.get('entity_ids',[]),binding.get('sku_id')] if entity in known}
        identities=[]
        for entity in sorted(bound):
            aliases=[entity,known[entity].get('values',{}).get('model')]
            if any(alias and re.search(r'(?:^|[_.])'+re.escape(alias)+r'(?=[_.]|$)',name,re.I) for alias in aliases):identities.append(entity)
        selected[identifier]=identities
        spans.extend(match.span() for match in matches)
    return selected,spans


def temporal_role_comparison(question,file_spans,entities):
    if not file_spans or not re.search(r'业务发生时间|实际事件时间|合同有效期',question):return False
    if not re.search(r'是否|是不是|就是|相同|同一|等同|区别|区分|算作|等于',question):return False
    residual=question
    for start,end in sorted(file_spans,reverse=True):residual=residual[:start]+' '+residual[end:]
    for entity in sorted(entities,key=len,reverse=True):residual=re.sub(re.escape(entity),' ',residual,flags=re.I)
    residual=re.sub(r'产品|资料|日期|文档|文件|合同|业务|实际|发生|事件|时间|有效期|制作|拍摄|中的|是否|是不是|就是|相同|同一|等同|区别|区分|算作|等于|请|和|与|及|的|中|是|就|吗|呢','',residual)
    return not re.search(r'[A-Za-z0-9\u4e00-\u9fff]',residual)

def aspect_queries(question,requested_fields,aliases):
    text=question;visual=question
    if requested_fields:
        visual_phrase=r'(?:(?:产品)?(?:图片|图像|照片)|产品图|图(?=中|里|上))(?:中|里|上)?(?:的)?(?:实际)?(?:可见)?(?:的)?(?:外观|颜色|特征|结构|形状|布局)?'
        text=re.sub(r'(?:[，,]?\s*(?:并|和|与|及|、))?(?:描述|介绍|说明|展示)?'+visual_phrase,' ',text)
        text=re.sub(r'(?:和|与|及|、)(?:实际)?(?:可见)?(?:外观|颜色|特征|结构|形状|布局)',' ',text)
        words=sorted({word for name in requested_fields for word in [name,*aliases.get(name,[])]},key=len,reverse=True)
        for word in words:visual=re.sub(re.escape(word),' ',visual,flags=re.I)
    return re.sub(r'\s+',' ',text).strip(),re.sub(r'\s+',' ',visual).strip()
