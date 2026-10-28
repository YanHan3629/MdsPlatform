"""Bounded worksheet selectors backed by actual native row/cell reads.

Coordinates are physical, one based and scoped by asset AND worksheet. This
does not turn a named file into proof of content, execute formulas, or use
retrieval rank as a substitute for the requested coordinate.
"""
from __future__ import annotations
import hashlib,json,re,time,uuid
from services.native_evidence_read import source_from_bundle,_native_numbers
from services.read_receipt import verify,digest

VERSION='worksheet-explicit-read-2026-10-24.1'
MAX_READS=8
MAX_FIELDS=16
MAX_BYTES=2*1024*1024
MAX_SECONDS=45
CELL=re.compile(r'(?<![A-Za-z0-9_.-])\$?([A-Za-z]{1,3})\$?([0-9]+)(?![A-Za-z0-9_.-])')
ROW=re.compile(r'第\s*(-?[0-9]+)\s*行')

def literal(question,value):
    return bool(value and re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(value)+r'(?![A-Za-z0-9_.-])',question,re.I))

def require(value,code):
    if not value:raise ValueError(code)

def _without_file(question,asset):
    for name in (asset['logicalPath'],asset['logicalPath'].rsplit('/',1)[-1]):
        question=re.sub(re.escape(name),' ',question,flags=re.I)
    return question

def named_asset(question,filters,intent):
    if not re.search(r'\.xlsx\b',question,re.I):return None
    filenames={name.casefold() for name in re.findall(r'(?<![A-Za-z0-9_.-])([\w.-]+\.xlsx)\b',question,re.I)}
    if len(filenames)>1:
        raise ValueError('NEEDS_CLARIFICATION: 当前坐标计划不能忽略另一个工作簿，请分开文件或明确跨文件计划。')
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus'
        and asset.get('originalFormat')=='XLSX' and asset.get('nativeEvidence')]
    full=[asset for asset in assets if literal(question,asset['logicalPath'])]
    selected=full or [asset for asset in assets if literal(question,asset['logicalPath'].rsplit('/',1)[-1])]
    if not selected:
        raise ValueError('NEEDS_CLARIFICATION: 指定的工作簿不在当前可读取快照中。')
    if re.search(r'\b(?:https?|file|ftp)://',question,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 外部地址不能按同名文件替换为快照内的工作簿。')
    if len(selected)!=1:
        raise ValueError('NEEDS_CLARIFICATION: 本次精确坐标读取需要唯一工作簿，请明确完整逻辑路径或分开文件。')
    asset=selected[0]
    if intent.allowed and 'XLSX' not in intent.allowed:
        raise ValueError('NEEDS_CLARIFICATION: 指定工作簿与来源格式限制不一致。')
    return asset

def selectors(question,asset,tables):
    """Resolve only explicit, unambiguous row/cell scopes; never offset a row."""
    text=_without_file(question,asset)
    if re.search(r'(?<![A-Za-z0-9_-])[A-Za-z]{2,12}-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*(?![A-Za-z0-9_-])',text):
        raise ValueError('NEEDS_CLARIFICATION: 当前坐标读取尚不能同时验证业务标识条件，请分开坐标读取与标识筛选。')
    if re.search(r'上一版|历史版本|之前版本|比较版本|排除|除外|大于|小于',text):
        raise ValueError('NEEDS_CLARIFICATION: 当前坐标读取尚不能执行版本比较或附加筛选条件。')
    known={table['locator']['sheet']:table for table in tables}
    require(len(known)==len(tables),'EVIDENCE_TABLE_AMBIGUOUS: 同一工作表存在多份表定义。')
    # Quoted and bare registered names both bind only immediately to 工作表.
    pattern=re.compile(r'[“"\'‘]([^”"\'’]+)[”"\'’]\s*工作表')
    matches=[(m.start(),m.end(),m[1]) for m in pattern.finditer(text)]
    for sheet in known:
        for m in re.finditer(re.escape(sheet)+r'\s*工作表',text):
            if not any(start<=m.start()<end for start,end,_ in matches):matches.append((m.start(),m.end(),sheet))
    matches.sort()
    if not matches:
        if len(known)!=1:
            raise ValueError('NEEDS_CLARIFICATION: 相同行号可能属于不同工作表，请明确工作表名称。')
        if re.search(r'工作表|sheet',text,re.I):
            raise ValueError('NEEDS_CLARIFICATION: 指定工作表名称未能与冻结表定义匹配。')
        matches=[(0,0,next(iter(known)))]
    require(all(sheet in known for _,_,sheet in matches),'NEEDS_CLARIFICATION: 指定工作表不在该工作簿的冻结定义中。')
    # Reject an additional, unbound sheet phrase instead of ignoring it.
    masked=text
    for start,end,_ in reversed(matches):masked=masked[:start]+' '*(end-start)+masked[end:]
    masked=re.sub(r'(?:同一行号的)?(?:其他|别的)工作表',' ',masked)
    if re.search(r'工作表',masked):
        raise ValueError('NEEDS_CLARIFICATION: 部分工作表名称未能解析，请明确每个坐标的工作表。')
    result=[]
    for index,(start,end,sheet) in enumerate(matches):
        clause=text[end:matches[index+1][0] if index+1<len(matches) else len(text)]
        row_tokens=[m[1] for m in ROW.finditer(clause)]
        if any(len(value)>7 or not value.isdecimal() or not 1<=int(value)<=1048576 for value in row_tokens):
            raise ValueError('NEEDS_CLARIFICATION: XLSX 物理行号需要在 1 到 1048576 之间。')
        rows=[int(value) for value in row_tokens]
        cells=[]
        for match in CELL.finditer(clause):
            letters=match[1].upper();digits=match[2];column=0
            for letter in letters:column=column*26+ord(letter)-ord('A')+1
            if digits.startswith('0') or len(digits)>7 or not 1<=int(digits)<=1048576 or column>16384:
                raise ValueError('NEEDS_CLARIFICATION: 单元格需要有效的一基行号及 A 到 XFD 的列号。')
            cells.append(letters+digits)
        if re.search(r'[A-Za-z]{1,3}\$?\d+\s*[:：~～-]\s*\$?[A-Za-z]{1,3}\$?\d+|第\s*\d+\s*至\s*\d+\s*行',clause):
            raise ValueError('NEEDS_CLARIFICATION: 当前精读支持单行或逐个单元格，请将范围写为明确坐标。')
        if rows and cells:
            raise ValueError('NEEDS_CLARIFICATION: 行读取与单元格读取请分别明确，不能忽略其中一个范围。')
        table=known[sheet]
        if rows:
            if len(set(rows))!=1:
                raise ValueError('NEEDS_CLARIFICATION: 每个工作表本次需要一个明确的物理行号。')
            row=rows[0];fields=table.get('fields',[])
            selected=[]
            for field in fields:
                labels=[field['name'],field.get('description',''),field.get('sourceDescription','')]
                if any(label and (len(label)>1 or label==field['name']) and literal(clause,label) for label in labels):selected.append(field['name'])
            explicit=set(re.findall(r'(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+(?![A-Za-z0-9_])',clause))
            if explicit-{field['name'] for field in fields}:
                raise ValueError('NEEDS_CLARIFICATION: 部分所问字段不在该工作表定义中，不能替换为其他工作表的同名字段。')
            if not selected:
                if not re.search(r'整行|全部字段|所有(?:字段|列)|记录内容',clause):
                    raise ValueError('NEEDS_CLARIFICATION: 请明确所问字段，或说明读取该行全部字段。')
                selected=[field['name'] for field in fields]
            if len(selected)>MAX_FIELDS:
                raise ValueError('TABLE_READ_BUDGET_EXCEEDED: 单次字段过多，请缩小字段范围。')
            result.append({'kind':'row','sheet':sheet,'rawRow':row,'fields':selected,'tableId':table['tableId']})
        elif cells:
            cells=list(dict.fromkeys(cells))
            if len(cells)>MAX_FIELDS:
                raise ValueError('TABLE_READ_BUDGET_EXCEEDED: 单次单元格过多，请缩小坐标范围。')
            header=table['locator'].get('headerRow')
            require(isinstance(header,int),'EVIDENCE_HEADER_UNRESOLVED: 工作表未登记表头行。')
            headers=list(dict.fromkeys(re.sub(r'\d+$',str(header),cell) for cell in cells))
            result.append({'kind':'cells','sheet':sheet,'cells':cells,'headerCells':headers})
        else:
            raise ValueError('NEEDS_CLARIFICATION: 请明确每个工作表的物理行号或单元格坐标。')
    require(0<len(result)<=MAX_READS,'TABLE_READ_BUDGET_EXCEEDED: 本次工作表精读数量超过上限。')
    return result

def checked_content(bundle,target):
    """Verify actual response scope; partial whole-sheet coverage is expected."""
    receipt=verify(bundle);op=receipt['operation'];content=bundle['content'];coverage=bundle['coverage']
    require(not coverage.get('truncated'),'TABLE_SCOPE_TRUNCATED')
    require(bundle['locator'].get('sheet')==target['sheet'],'TABLE_SCOPE_WRONG_SHEET')
    facts=bundle['facts'];require(bool(facts),'TABLE_SCOPE_NO_FACTS')
    if target['kind']=='row':
        row=target['rawRow'];fields=target['fields']
        require(op=={'operation':'read_table','tableId':target['tableId'],'rowStart':row,'rowEnd':row,'limit':1,'fields':fields},'TABLE_SCOPE_REQUEST_CHANGED')
        records=content.get('records',[])
        require(content.get('tableId')==target['tableId'] and len(records)==1,'TABLE_SCOPE_RECORD_NOT_FOUND')
        record=records[0];loc=record.get('locator',{})
        require(record.get('rawRow')==row and loc.get('sheet')==target['sheet'],'TABLE_SCOPE_WRONG_ROW')
        require(set(record.get('values',{}))==set(fields),'TABLE_SCOPE_FIELDS_INCOMPLETE')
        require(coverage.get('requestedRangeComplete') is True and not coverage.get('hasMore'),'TABLE_SCOPE_RANGE_INCOMPLETE')
        require(len(facts)==len(fields) and {fact['field'] for fact in facts}==set(fields),'TABLE_SCOPE_FACTS_INCOMPLETE')
        for fact in facts:
            floc=fact['locator']
            require(floc.get('sheet')==target['sheet'] and floc.get('rawRow')==row
                and floc.get('field')==fact['field'] and floc.get('cell')==loc.get('cells',{}).get(fact['field']), 'TABLE_SCOPE_FACT_COORDINATE_MISMATCH')
            require(digest(fact['value'])==digest(record['values'][fact['field']]),'TABLE_SCOPE_FACT_VALUE_MISMATCH')
            formula=record.get('formulas',{}).get(fact['field'])
            if formula:
                require(formula.get('formula')==fact.get('sourceValue') and formula.get('calculationEligible') is False
                    and fact.get('calculationEligible') is False and digest(formula.get('value'))==digest(fact['value']), 'TABLE_SCOPE_FORMULA_MISMATCH')
    else:
        cells=list(dict.fromkeys(target['headerCells']+target['cells']))
        require(op=={'operation':'read_workbook_cells','sheet':target['sheet'],'cells':cells},'TABLE_SCOPE_REQUEST_CHANGED')
        require(bundle['locator'].get('cells')==cells,'TABLE_SCOPE_CELL_REQUEST_MISMATCH')
        require(not content.get('unrecordedRequestedCells') and coverage.get('unrecordedCellCount')==0,'TABLE_SCOPE_CELLS_NOT_RECORDED')
        require({cell['cell'] for cell in content.get('cells',[])}==set(cells)
            and len(facts)==len(cells) and {fact['locator'].get('cell') for fact in facts}==set(cells),'TABLE_SCOPE_CELLS_INCOMPLETE')
        by_cell={cell['cell']:cell for cell in content['cells']}
        for fact in facts:
            require(fact['locator'].get('sheet')==target['sheet'],'TABLE_SCOPE_WRONG_SHEET')
            cell=by_cell[fact['locator']['cell']]
            require(digest(fact['value'])==digest(cell.get('value')),'TABLE_SCOPE_CELL_VALUE_MISMATCH')
    return facts

def model_context(bundle,target):
    facts=checked_content(bundle,target)
    rows=[]
    for fact in facts:
        value=_native_numbers(fact['value'],'',{})
        if fact.get('numberSourceExpression') is not None:value={'sourceNumber':fact['numberSourceExpression'],'type':'number'}
        if not fact.get('calculationEligible',True):
            value={'sourceExpression':fact.get('sourceValue'),'cachedValue':fact['value'],'usableForCalculation':False,'qualityFlags':fact.get('qualityFlags',[])}
        row={'factId':fact['factId'],'field':fact['field'],'cell':fact['locator']['cell'],'value':value,'unit':fact.get('unit')}
        if not fact.get('calculationEligible',True):
            if target['kind']=='row':
                formula=bundle['content']['records'][0].get('formulas',{}).get(fact['field'],{})
                row.update(cacheSourceRaw=formula.get('rawValue'),cacheStatus=formula.get('cacheStatus'),formulaExecution='none')
            else:
                cell=next(cell for cell in bundle['content']['cells'] if cell['cell']==fact['locator']['cell'])
                row.update(cacheSourceRaw=cell.get('rawValue'),cacheStatus=cell.get('cacheStatus'),formulaExecution=bundle['content'].get('formulaExecution'))
        rows.append(row)
    return {'evidenceId':bundle['evidenceId'],'sheet':target['sheet'],
        **({'rawRow':target['rawRow']} if target['kind']=='row' else {'requestedCells':target['cells'],'headerCells':target['headerCells']}),
        'facts':rows}

def validate_item(question,item):
    provenance=item.get('provenance',{});bundle=item.get('nativeBundle',{});identity=bundle.get('identity',{})
    require(provenance.get('tableReadVersion')==VERSION and provenance.get('validatedTableQuestion')==question,'TABLE_READ_NOT_VERIFIED')
    require(identity.get('originalFormat')=='XLSX' and identity.get('assetId')==item.get('assetId')
        and identity.get('versionId')==item.get('dataVersion') and identity.get('indexVersionId')==item.get('indexVersion')
        and identity.get('logicalPath')==provenance.get('explicitSourcePath')
        and identity.get('sourceSha256')==provenance.get('sourceSha256')
        and literal(question,identity.get('logicalPath','').rsplit('/',1)[-1]),'TABLE_SOURCE_IDENTITY_MISMATCH')
    require(digest(json.loads(item['nativeModelContext']))==digest(model_context(bundle,provenance['tableTarget'])),'TABLE_MODEL_CONTEXT_CHANGED')
    return True

def direct_table_question(client,scope,question,filters,intent):
    # Natural complete-range calculations run before this coordinate reader.
    if not (ROW.search(question) or '单元格' in question or CELL.search(re.sub(r'[\w.-]+\.xlsx',' ',question,flags=re.I))):return None
    asset=named_asset(question,filters,intent)
    if asset is None:return None
    tables=[table for table in filters.get('tables',[]) if str(table['assetId'])==str(asset['assetId'])]
    require(bool(tables),'EVIDENCE_TABLE_UNAVAILABLE: 当前快照未登记工作簿表结构。')
    targets=selectors(question,asset,tables)
    metadata=client.evidence_metadata(*scope,asset['assetId'])
    require(metadata.get('nativeEvidenceStatus')=='AVAILABLE','EVIDENCE_MANIFEST_UNAVAILABLE')
    started=time.monotonic();items=[];reads=[];size=0
    for target in targets:
        require(time.monotonic()-started<MAX_SECONDS,'TABLE_READ_TIME_BUDGET_EXCEEDED')
        operation=({'operation':'read_table','tableId':target['tableId'],'rowStart':target['rawRow'],'rowEnd':target['rawRow'],'limit':1,'fields':target['fields']}
            if target['kind']=='row' else {'operation':'read_workbook_cells','sheet':target['sheet'],'cells':list(dict.fromkeys(target['headerCells']+target['cells']))})
        bundle=client.read_evidence(*scope,asset['assetId'],operation)
        size+=len(json.dumps(bundle,ensure_ascii=False).encode())
        require(size<=MAX_BYTES and time.monotonic()-started<=MAX_SECONDS,'TABLE_READ_BUDGET_EXCEEDED')
        context=model_context(bundle,target)
        candidate={'assetId':asset['assetId'],'dataVersion':str(scope[1]),'indexVersion':str(scope[2]),
            'evidenceId':str(uuid.uuid5(uuid.NAMESPACE_URL,json.dumps([list(map(str,scope)),asset['assetId'],target],ensure_ascii=False,sort_keys=True))),
            'semanticSpace':'native.worksheet.read','locator':bundle['locator'],'facts':{},'scoreType':'verified_table_read',
            'rawScore':None,'rank':len(items)+1,'evidenceType':'constraint','qualityFlags':bundle['qualityFlags'],
            'preview':{'logicalPath':asset['logicalPath']},'provenance':{'scope':asset['scope'],'originalFormat':'XLSX'}}
        item,read=source_from_bundle(scope,candidate,bundle,metadata,operation)
        item['nativeModelContext']=json.dumps(context,ensure_ascii=False,separators=(',',':'))
        item['nativeHumanContext']=item['nativeModelContext']
        item['provenance'].update(tableReadVersion=VERSION,validatedTableQuestion=question,tableTarget=target,explicitSourcePath=asset['logicalPath'])
        validate_item(question,item)
        read['contextTextSha256']=hashlib.sha256(item['nativeModelContext'].encode()).hexdigest()
        items.append(item);reads.append(read)
    return {'evidence':items,'nativeReads':reads,'status':'OK','planningMethod':'bounded-explicit-worksheet-selector',
        'tablePlan':{'version':VERSION,'targets':targets,'requiredEvidenceIds':[item['evidenceId'] for item in items],
            'readBytes':size,'readCount':len(reads),'physicalRowNumberingBase':1,'formulaExecution':'none'}}
