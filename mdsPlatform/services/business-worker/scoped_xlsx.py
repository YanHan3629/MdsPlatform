"""Application-native XLSX scope projection; no original ZIP parts survive."""
import ast,io,re,zipfile,posixpath
import xml.etree.ElementTree as ET
from decimal import Decimal,ROUND_HALF_UP,localcontext
from fractions import Fraction
from datetime import date
NS='http://schemas.openxmlformats.org/spreadsheetml/2006/main'
REL='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
PKG='http://schemas.openxmlformats.org/package/2006/relationships'
def tag(name):return '{'+NS+'}'+name
def xml(raw):
    if b'<!DOCTYPE' in raw.replace(b'\x00',b'').upper() or b'<!ENTITY' in raw.replace(b'\x00',b'').upper():raise ValueError('Unsafe XML declaration')
    return ET.fromstring(raw)
def column_index(col):
    value=0
    for ch in col:value=value*26+ord(ch)-64
    return value
def column_name(value):
    result=''
    while value:value,d=divmod(value-1,26);result=chr(65+d)+result
    return result
def address(text):
    match=re.fullmatch(r'\$?([A-Z]{1,3})\$?([1-9][0-9]*)',text)
    if not match:raise ValueError('Invalid cell address')
    return column_index(match.group(1)),int(match.group(2))
def load(raw):
    sheets=[];date1904=False
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        if len(archive.namelist())!=len(set(archive.namelist())):raise ValueError('Duplicate workbook ZIP member')
        if sum(x.file_size for x in archive.infolist())>256*1024*1024:raise ValueError('Workbook expansion limit')
        if any('..' in x.filename.split('/') or x.filename.startswith('/') for x in archive.infolist()):raise ValueError('Invalid ZIP member')
        shared=[]
        if 'xl/sharedStrings.xml' in archive.namelist():
            shared=[''.join(x.itertext()) for x in xml(archive.read('xl/sharedStrings.xml')).findall(tag('si'))]
        styles=[];formats={}
        if 'xl/styles.xml' in archive.namelist():
            root=xml(archive.read('xl/styles.xml'))
            for x in root.findall(tag('numFmts')+'/'+tag('numFmt')):formats[int(x.attrib['numFmtId'])]=x.attrib.get('formatCode','')
            for x in root.findall(tag('cellXfs')+'/'+tag('xf')):
                num=int(x.attrib.get('numFmtId',0));fmt=formats.get(num,'')
                styles.append(num in list(range(14,23))+[45,46,47] or bool(re.search(r'[ymdhHsS]',re.sub(r'"[^"]*"|\[[^]]*\]','',fmt))))
        book=xml(archive.read('xl/workbook.xml'));props=book.find(tag('workbookPr'));date1904=props is not None and props.attrib.get('date1904') in ['1','true']
        relationships={x.attrib['Id']:x for x in xml(archive.read('xl/_rels/workbook.xml.rels'))}
        seen_sheets=set()
        for sheet in book.findall(tag('sheets')+'/'+tag('sheet')):
            name=sheet.attrib.get('name','')
            if not name or len(name)>31 or any(c in name for c in '[]:*?/\\') or name.casefold() in seen_sheets:raise ValueError('Ambiguous worksheet name')
            seen_sheets.add(name.casefold())
            relation=relationships[sheet.attrib['{'+REL+'}id']]
            if relation.attrib.get('TargetMode')=='External':continue
            target=relation.attrib['Target'];target=target.lstrip('/') if target.startswith('/') else posixpath.normpath('xl/'+target)
            if not target.startswith('xl/') or '..' in target.split('/'):raise ValueError('Invalid worksheet path')
            root=xml(archive.read(target));hidden_cols=set()
            for c in root.findall(tag('cols')+'/'+tag('col')):
                if c.attrib.get('hidden') in ['1','true']:hidden_cols.update(range(int(c.attrib['min']),int(c.attrib['max'])+1))
            rows={};hidden_rows=set()
            for row in root.findall(tag('sheetData')+'/'+tag('row')):
                rn=int(row.attrib['r'])
                if rn<1 or rn in rows:raise ValueError('Duplicate or invalid worksheet row')
                if row.attrib.get('hidden') in ['1','true']:hidden_rows.add(rn)
                cells={}
                for cell in row.findall(tag('c')):
                    col,num=address(cell.attrib['r'])
                    if num!=rn or col in cells:raise ValueError('Duplicate or inconsistent worksheet cell')
                    typ=cell.attrib.get('t','n');value=cell.find(tag('v'));value=value.text if value is not None and value.text is not None else ''
                    if typ=='s':value=shared[int(value)] if value else '';typ='s'
                    elif typ=='inlineStr':
                        s=cell.find(tag('is'));value=''.join(s.itertext()) if s is not None else '';typ='s'
                    elif typ=='str':typ='s'
                    formula=cell.find(tag('f'));style=int(cell.attrib.get('s',0))
                    cells[col]={'type':typ,'value':value,'formula':formula.text if formula is not None else None,'date':bool(style<len(styles) and styles[style]),'source':cell.attrib['r']}
                rows[rn]=cells
            sheets.append({'name':sheet.attrib['name'],'visible':sheet.attrib.get('state','visible')=='visible','rows':rows,'hiddenRows':hidden_rows,'hiddenColumns':hidden_cols})
        ancillary=[x for x in archive.namelist() if any(y.lower() in x.lower() for y in ['comments','externalLinks','embeddings','vbaProject','connections','customXml','drawings'])]
    return {'sheets':sheets,'date1904':date1904,'ancillarySourcePartsDiscarded':len(ancillary)}
REF=re.compile(r"(?<![A-Za-z0-9_])(?:(?:'([^']+)'|([A-Za-z_\u4e00-\u9fff][A-Za-z0-9_\u4e00-\u9fff ]*))!)?(\$?[A-Z]{1,3}\$?[1-9][0-9]*)(?::(\$?[A-Z]{1,3}\$?[1-9][0-9]*))?(?![A-Za-z0-9_])")
def iso_date_serial(text,date1904=False):
    match=re.fullmatch(r'(\d{4})-(\d{2})-(\d{2})(?:[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?)?(Z|[+-]\d{2}:\d{2})?',text)
    if not match:raise ValueError('Unsupported ISO date cell')
    year,month,day,hour,minute,second,fraction,zone=match.groups()
    stamp=date(int(year),int(month),int(day))
    days=(stamp-date(1904,1,1)).days if date1904 else (stamp-date(1899,12,31)).days+int(stamp>=date(1900,3,1))
    hh,mm,ss=int(hour or 0),int(minute or 0),int(second or 0)
    if hh>23 or mm>59 or ss>59:raise ValueError('Invalid ISO clock')
    offset=0
    if zone and zone!='Z':
        sign=1 if zone[0]=='+' else -1;zh,zm=int(zone[1:3]),int(zone[4:6])
        if zh>23 or zm>59:raise ValueError('Invalid ISO offset')
        offset=sign*(zh*3600+zm*60)
    seconds=Fraction(hh*3600+mm*60+ss-offset)+Fraction(Decimal(fraction or '0'))
    return Fraction(days)+seconds/86400
def decimal_value(value):
    if isinstance(value,Fraction):
        with localcontext() as ctx:
            ctx.prec=60
            return Decimal(value.numerator)/Decimal(value.denominator)
    return value
OMITTED=object()
def formula_value(book,sheet_name,col,row,allowed,trail=None,project_ranges=False):
    """Evaluate only a closed authorized cell dependency graph, never caches."""
    trail=set(trail or set());key=(sheet_name,col,row)
    if key in trail or key not in allowed:raise ValueError('Formula dependency outside authorized cells')
    sheet=next(x for x in book['sheets'] if x['name']==sheet_name)
    cell=sheet['rows'].get(row,{}).get(col)
    if not cell:return Decimal(0)
    formula=cell.get('formula')
    if not formula:
        if cell['type']=='d':return iso_date_serial(cell['value'],book.get('date1904',False))
        if cell['type']=='b':return cell['value']=='1'
        if cell['type']=='n':return Decimal(cell['value']) if cell['value'] else Decimal(0)
        if cell['type']=='s':return cell['value']
        raise ValueError('Unsupported formula source cell')
    if '[' in formula or ']' in formula or len(formula)>32768:raise ValueError('External or oversized formula dependency')
    trail.add(key);values={};counter=[0]
    def put(value,prefix):
        name=prefix+str(counter[0]);counter[0]+=1;values[name]=value;return name
    expression=re.sub(r'"(?:[^"]|"")*"',lambda m:put(m.group(0)[1:-1].replace('""','"'),'STRINGVALUE'),formula.lstrip('='))
    def replacement(match):
        target=match.group(1) or match.group(2) or sheet_name;c1,r1=address(match.group(3));end=match.group(4)
        if end:
            c2,r2=address(end)
            if c2<c1 or r2<r1 or (c2-c1+1)*(r2-r1+1)>100000:raise ValueError('Invalid formula range')
            value=[OMITTED if project_ranges and (target,c,r) not in allowed else formula_value(book,target,c,r,allowed,trail,project_ranges) for r in range(r1,r2+1) for c in range(c1,c2+1)]
        else:value=formula_value(book,target,c1,r1,allowed,trail,project_ranges)
        return put(value,'REFVALUE')
    expression=REF.sub(replacement,expression).replace('^','**').replace('<>','!=')
    expression=re.sub(r'(?<![<>!=])=(?!=)','==',expression)
    expression=re.sub(r'(?<![A-Za-z0-9_])(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[Ee][+-]?[0-9]+)?',lambda m:put(Decimal(m.group(0)),'NUMVALUE'),expression)
    tree=ast.parse(expression,mode='eval')
    def flatten(items):
        for x in items:
            if isinstance(x,list):
                for v in flatten(x):yield v
            else:yield x
    def number(value):
        if isinstance(value,bool):return Decimal(int(value))
        if isinstance(value,(Decimal,Fraction)):return value
        if value=='':return Decimal(0)
        raise ValueError('Non-numeric arithmetic source')
    def compare(a,b,op):
        if isinstance(op,ast.Eq):return a==b
        if isinstance(op,ast.NotEq):return a!=b
        if isinstance(op,ast.Lt):return a<b
        if isinstance(op,ast.LtE):return a<=b
        if isinstance(op,ast.Gt):return a>b
        if isinstance(op,ast.GtE):return a>=b
        raise ValueError('Unsupported comparison')
    def criterion(value,wanted):
        if not isinstance(wanted,str):return value==wanted
        match=re.fullmatch(r'(<=|>=|<>|=|<|>)(.*)',wanted)
        if not match:return str(value)==wanted
        op,right=match.groups()
        try:left=number(value);right=Decimal(right)
        except (ValueError,TypeError,ArithmeticError):left=str(value)
        return {'=':lambda:left==right,'<>':lambda:left!=right,'<':lambda:left<right,'>':lambda:left>right,'<=':lambda:left<=right,'>=':lambda:left>=right}[op]()
    def evaluate(node):
        if isinstance(node,ast.Expression):return evaluate(node.body)
        if isinstance(node,ast.Name):
            if node.id in values:return values[node.id]
            if node.id.upper()=='TRUE':return True
            if node.id.upper()=='FALSE':return False
        if isinstance(node,ast.UnaryOp) and isinstance(node.op,(ast.UAdd,ast.USub)):
            v=number(evaluate(node.operand));return v if isinstance(node.op,ast.UAdd) else -v
        if isinstance(node,ast.Compare) and len(node.ops)==1:return compare(evaluate(node.left),evaluate(node.comparators[0]),node.ops[0])
        if isinstance(node,ast.BinOp):
            a,b=Fraction(number(evaluate(node.left))),Fraction(number(evaluate(node.right)))
            if isinstance(node.op,ast.Add):return a+b
            if isinstance(node.op,ast.Sub):return a-b
            if isinstance(node.op,ast.Mult):return a*b
            if isinstance(node.op,ast.Div):return a/b
            if isinstance(node.op,ast.Pow) and b==int(b) and abs(b)<=16:return a**int(b)
        if isinstance(node,ast.Call) and isinstance(node.func,ast.Name) and not node.keywords:
            name=node.func.id.upper()
            if name=='IF' and len(node.args)==3:return evaluate(node.args[1] if evaluate(node.args[0]) else node.args[2])
            args=[evaluate(x) for x in node.args];flat=list(flatten(args))
            available=[x for x in flat if x is not OMITTED]
            if name in ['SUM','MIN','MAX'] and not available:raise ValueError('No authorized aggregate inputs')
            if name in ['OR','AND'] and len(available)!=len(flat):raise ValueError('Boolean range contains restricted cells')
            if name in ['SUM','MIN','MAX']:flat=available
            if name=='OR':return any(bool(x) for x in flat)
            if name=='AND':return all(bool(x) for x in flat)
            if name=='SUM':return sum((Fraction(number(x)) for x in flat if not isinstance(x,str) or x==''),Fraction(0))
            if name=='MIN' and flat:return min(number(x) for x in flat)
            if name=='MAX' and flat:return max(number(x) for x in flat)
            if name=='ABS' and len(args)==1:return abs(number(args[0]))
            if name=='ROUND' and len(args)==2:
                places=number(args[1])
                if places==int(places) and abs(places)<=20:return decimal_value(number(args[0])).quantize(Decimal(1).scaleb(-int(places)),rounding=ROUND_HALF_UP)
            if name=='SUMIFS' and len(args)>=3 and len(args)%2==1:
                total=args[0]
                if not isinstance(total,list):total=[total]
                ranges=[]
                for i in range(1,len(args),2):
                    cells=args[i] if isinstance(args[i],list) else [args[i]]
                    if len(cells)!=len(total):raise ValueError('SUMIFS range mismatch')
                    ranges.append((cells,args[i+1]))
                eligible=[i for i,v in enumerate(total) if v is not OMITTED and all(cells[i] is not OMITTED for cells,wanted in ranges)]
                if not eligible:raise ValueError('No authorized aggregate inputs')
                return sum((Fraction(number(total[i])) for i in eligible if all(criterion(cells[i],wanted) for cells,wanted in ranges)),Fraction(0))
        raise ValueError('Unsupported safe formula')
    result=decimal_value(evaluate(tree))
    if isinstance(result,Decimal) and not result.is_finite():raise ValueError('Invalid formula result')
    if not isinstance(result,(Decimal,str,bool)):raise ValueError('Invalid formula result type')
    return result
def write(sheets,date1904=False):
    """Cells are (column number, type, exact lexeme, date flag), no formulas."""
    def doc(root):return b'<?xml version="1.0" encoding="utf-8"?>\n'+ET.tostring(root,encoding='utf-8')
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED) as z:
        def put(name,raw):
            entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
            entry.create_system=0;entry.external_attr=0o600<<16
            z.writestr(entry,raw)
        types=ET.Element('Types',xmlns='http://schemas.openxmlformats.org/package/2006/content-types')
        ET.SubElement(types,'Default',Extension='rels',ContentType='application/vnd.openxmlformats-package.relationships+xml')
        ET.SubElement(types,'Default',Extension='xml',ContentType='application/xml')
        ET.SubElement(types,'Override',PartName='/xl/workbook.xml',ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml')
        ET.SubElement(types,'Override',PartName='/xl/styles.xml',ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml')
        rootrel=ET.Element('Relationships',xmlns=PKG);ET.SubElement(rootrel,'Relationship',Id='rId1',Type=REL+'/officeDocument',Target='xl/workbook.xml')
        put('_rels/.rels',doc(rootrel))
        wb=ET.Element(tag('workbook'));ET.SubElement(wb,tag('workbookPr'),date1904='1' if date1904 else '0');ss=ET.SubElement(wb,tag('sheets'));rels=ET.Element('Relationships',xmlns=PKG)
        for i,sheet in enumerate(sheets,1):
            ET.SubElement(ss,tag('sheet'),name=sheet['name'][:31],sheetId=str(i),attrib={'{'+REL+'}id':'rId'+str(i)})
            ET.SubElement(rels,'Relationship',Id='rId'+str(i),Type=REL+'/worksheet',Target='worksheets/sheet'+str(i)+'.xml')
            ET.SubElement(types,'Override',PartName='/xl/worksheets/sheet'+str(i)+'.xml',ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml')
            ws=ET.Element(tag('worksheet'));data=ET.SubElement(ws,tag('sheetData'))
            for rn,cells in enumerate(sheet['rows'],1):
                r=ET.SubElement(data,tag('row'),r=str(rn))
                for col,typ,value,date in cells:
                    if value is None:continue
                    attrs={'r':column_name(col)+str(rn)}
                    if typ=='s':
                        attrs['t']='inlineStr';c=ET.SubElement(r,tag('c'),attrs);s=ET.SubElement(c,tag('is'));tx=ET.SubElement(s,tag('t'),{'{http://www.w3.org/XML/1998/namespace}space':'preserve'});tx.text=str(value)
                    else:
                        attrs['t']=typ if typ in ['n','b','e','d'] else 's'
                        if date and typ=='n':attrs['s']='1'
                        c=ET.SubElement(r,tag('c'),attrs);ET.SubElement(c,tag('v')).text=str(value)
            put('xl/worksheets/sheet'+str(i)+'.xml',doc(ws))
        ET.SubElement(rels,'Relationship',Id='styles',Type=REL+'/styles',Target='styles.xml')
        put('xl/workbook.xml',doc(wb));put('xl/_rels/workbook.xml.rels',doc(rels));put('[Content_Types].xml',doc(types))
        style=ET.Element(tag('styleSheet'));nums=ET.SubElement(style,tag('numFmts'),count='1');ET.SubElement(nums,tag('numFmt'),numFmtId='164',formatCode='yyyy-mm-dd hh:mm:ss.000000')
        fonts=ET.SubElement(style,tag('fonts'),count='1');font=ET.SubElement(fonts,tag('font'));ET.SubElement(font,tag('name'),val='Arial');ET.SubElement(font,tag('sz'),val='11')
        fills=ET.SubElement(style,tag('fills'),count='2')
        for pat in ['none','gray125']:ET.SubElement(ET.SubElement(fills,tag('fill')),tag('patternFill'),patternType=pat)
        borders=ET.SubElement(style,tag('borders'),count='1');ET.SubElement(borders,tag('border'))
        base=ET.SubElement(style,tag('cellStyleXfs'),count='1');ET.SubElement(base,tag('xf'),numFmtId='0',fontId='0',fillId='0',borderId='0')
        xfs=ET.SubElement(style,tag('cellXfs'),count='2')
        ET.SubElement(xfs,tag('xf'),numFmtId='0',fontId='0',fillId='0',borderId='0',xfId='0')
        ET.SubElement(xfs,tag('xf'),numFmtId='164',fontId='0',fillId='0',borderId='0',xfId='0',applyNumberFormat='1')
        named=ET.SubElement(style,tag('cellStyles'),count='1');ET.SubElement(named,tag('cellStyle'),name='Normal',xfId='0',builtinId='0')
        put('xl/styles.xml',doc(style))
    return stream.getvalue()
