"""Compile explicit collaboration selections from a verified immutable inventory.
Only public/ is an ingestion payload. This compiler never grants, approves,
publishes or creates an index; its receipt remains CANDIDATE_AWAITING_REVIEW.
"""
import argparse,csv,hashlib,io,json,re,uuid,unicodedata,zipfile
from pathlib import Path,PurePosixPath
from decimal import Decimal
from scope_ownership import json_text
import scoped_xlsx

csv.field_size_limit(16*1024*1024)
SCHEMA='COLLABORATION_VIEW_RULES_V1'
FORMATS={'.csv':'CSV','.xlsx':'XLSX','.json':'JSON','.jsonl':'JSONL','.txt':'TXT','.pdf':'PDF','.png':'PNG','.jpg':'JPEG','.jpeg':'JPEG','.svg':'SVG','.obj':'OBJ','.step':'STEP','.stp':'STEP'}
ACK='EXACT_SOURCE_BYTES_INCLUDING_HIDDEN_CONTENT'
class RuleError(ValueError):pass
def fail(code):raise RuleError(code)
def digest(raw):return hashlib.sha256(raw).hexdigest()
def sha(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda:f.read(4*1024*1024),b''):h.update(b)
    return h.hexdigest()
def pairs(items):
    out={}
    for key,value in items:
        if key in out:fail('DUPLICATE_JSON_FIELD')
        out[key]=value
    return out
def bad_constant(value):fail('NONFINITE_JSON_NUMBER')
def loads(raw):
    try:return json.loads(raw,object_pairs_hook=pairs,parse_float=Decimal,parse_constant=bad_constant)
    except RuleError:raise
    except (ValueError,TypeError,UnicodeError):fail('INVALID_JSON_DOCUMENT')
def keys(value,required,optional=()):
    if not isinstance(value,dict) or not set(required)<=set(value) or set(value)-set(required)-set(optional):fail('RULE_SCHEMA_FIELDS_INVALID')
def integer(value,minimum=1):
    if isinstance(value,bool) or not isinstance(value,int) or value<minimum:fail('INVALID_POSITIVE_INTEGER')
    return value
def uuid_value(value):
    if not isinstance(value,str):fail('INVALID_UUID')
    try:return str(uuid.UUID(value))
    except (ValueError,TypeError,AttributeError):fail('INVALID_UUID')
def hash_value(value):
    if not isinstance(value,str) or not re.fullmatch('[0-9a-f]{64}',value):fail('INVALID_SHA256')
    return value
def canonical(value):
    def valid(v):
        if v is None or isinstance(v,(str,bool)):return
        if isinstance(v,int) and abs(v)<=9007199254740991:return
        if isinstance(v,list):
            for x in v:valid(x)
            return
        if isinstance(v,dict) and all(isinstance(k,str) for k in v):
            for x in v.values():valid(x)
            return
        fail('RULE_NONCANONICAL_VALUE')
    valid(value)
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode('utf-8')
def safe_path(value,corpus_only=False):
    if not isinstance(value,str) or not value or len(value)>1024 or value!=unicodedata.normalize('NFC',value) or any(ord(c)<32 for c in value) or '\\' in value or ':' in value or value.startswith('/') or any(p in ('','.','..') for p in value.split('/')):fail('UNSAFE_LOGICAL_PATH')
    if not value.startswith('corpus/' if corpus_only else ('corpus/','catalog/')):fail('LOGICAL_PATH_OUTSIDE_ALLOWED_ROOT')
    return value
def string_list(value,nonempty=True):
    if not isinstance(value,list) or (nonempty and not value) or len(value)!=len(set(x for x in value if isinstance(x,str))) or any(not isinstance(x,str) or not x or '\0' in x for x in value):fail('INVALID_UNIQUE_STRING_LIST')
    return value
def pointer(value,document):
    if value=='':return document
    if not isinstance(value,str) or not value.startswith('/') or re.search('~(?![01])',value):fail('INVALID_JSON_POINTER')
    current=document
    for token in value[1:].split('/'):
        token=token.replace('~1','/').replace('~0','~')
        if isinstance(current,list):
            if not re.fullmatch('0|[1-9][0-9]*',token) or int(token)>=len(current):fail('JSON_POINTER_NOT_AVAILABLE')
            current=current[int(token)]
        elif isinstance(current,dict) and token in current:current=current[token]
        else:fail('JSON_POINTER_NOT_AVAILABLE')
    return current
def escape_pointer(value):return value.replace('~','~0').replace('/','~1')

class Snapshot:
    def __init__(self,manifest,cache):
        self.manifest_path=Path(manifest);self.cache=Path(cache).resolve(strict=True)
        inventory=self.manifest_path.read_bytes();self.inventory_sha=digest(inventory);obj=loads(inventory.decode('utf-8-sig'))
        if not isinstance(obj,dict) or not isinstance(obj.get('version'),dict) or not isinstance(obj.get('files'),list) or not obj['files'] :fail('INPUT_INVENTORY_INCOMPLETE')
        full_count=integer(obj['version'].get('raw_file_count'));inventory_count=integer(obj.get('inventoryFileCount',full_count))
        if inventory_count!=len(obj['files']) or full_count<inventory_count:fail('INPUT_INVENTORY_COUNT_MISMATCH')
        self.source={'datasetId':uuid_value(obj.get('datasetId')),'versionId':uuid_value(obj['version'].get('version_id')),'rawCommitId':uuid_value(obj['version'].get('raw_commit_id')),'inputInventorySha256':self.inventory_sha}
        self.files={};casefold=set();identities=set()
        for f in obj['files']:
            if not isinstance(f,dict):fail('INPUT_FILE_DESCRIPTOR_INVALID')
            rawpath=f.get('logical_path')
            path=safe_path(rawpath[1:] if isinstance(rawpath,str) and rawpath.startswith('/') else rawpath)
            identity=uuid_value(f.get('file_id'));size=integer(f.get('size_bytes'),0);expected=hash_value(str(f.get('sha256','')).strip())
            if path in self.files or path.casefold() in casefold or identity in identities:fail('INPUT_INVENTORY_IDENTITY_COLLISION')
            p=self.cache/identity
            if p.is_symlink() or p.resolve(strict=True).parent!=self.cache or not p.is_file() or p.stat().st_size!=size or sha(p)!=expected:fail('INPUT_FILE_BYTE_IDENTITY_MISMATCH')
            self.files[path]={'id':identity,'sha256':expected,'size':size,'path':p}
            casefold.add(path.casefold());identities.add(identity)
    def read(self,path):
        f=self.files[path]
        if f['size']>256*1024*1024:fail('SELECTED_FILE_EXCEEDS_256_MIB')
        # Recheck at each actual read; the input directory is read-only in the job.
        raw=f['path'].read_bytes()
        if len(raw)!=f['size'] or digest(raw)!=f['sha256']:fail('INPUT_CHANGED_DURING_MATERIALIZATION')
        return raw

def choose(rows,key,spec,fields):
    if key not in fields:fail('PRIMARY_KEY_FIELD_REQUIRED')
    wanted=spec.get('rowKeys')
    allrows=spec.get('allRows')
    if ('rowKeys' in spec)==('allRows' in spec):fail('EXACTLY_ONE_ROW_SELECTOR_REQUIRED')
    if 'allRows' in spec and allrows is not True:fail('ALL_ROWS_REQUIRES_EXPLICIT_TRUE')
    if wanted is not None:wanted=set(string_list(wanted))
    seen=set();selected=[]
    for position,row in rows:
        if not isinstance(row,dict) or key not in row or not isinstance(row[key],str) or not row[key] or row[key] in seen:fail('MISSING_OR_DUPLICATE_RECORD_KEY')
        identity=row[key];seen.add(identity)
        if allrows or identity in wanted:
            if any(field not in row for field in fields):fail('SELECTED_COLUMN_MISSING_FROM_RECORD')
            if any(isinstance(row[field],(dict,list)) for field in fields):fail('NESTED_COLUMN_REQUIRES_EXPLICIT_LEAF_COLLECTION')
            selected.append((position,identity,{field:row[field] for field in fields}))
    if wanted is not None and wanted-seen:fail('REQUESTED_RECORD_KEY_NOT_AVAILABLE')
    if not selected:fail('EMPTY_SELECTION_NOT_PUBLISHABLE')
    return selected,len(seen)
def read_csv(raw):
    try:
        reader=csv.DictReader(io.StringIO(raw.decode('utf-8-sig'),newline=''),strict=True);headers=reader.fieldnames
        string_list(headers)
        rows=list(reader)
        if any(None in row or any(v is None for v in row.values()) for row in rows):fail('MALFORMED_CSV_RECORD')
        return headers,rows
    except (UnicodeError,csv.Error):fail('INVALID_CSV_DOCUMENT')
def csv_bytes(fields,rows):
    stream=io.StringIO(newline='');w=csv.DictWriter(stream,fieldnames=fields,lineterminator='\n',extrasaction='raise');w.writeheader();w.writerows(rows)
    return stream.getvalue().encode('utf-8')
def row_columns(spec):
    if not isinstance(spec.get('key'),str) or not spec['key']:fail('RECORD_KEY_REQUIRED')
    return string_list(spec.get('columns'))
def records(kind,raw,spec):
    fields=row_columns(spec);key=spec['key']
    if kind=='CSV_ROWS':
        headers,rows=read_csv(raw)
        if not set(fields)<=set(headers):fail('CSV_COLUMN_NOT_AVAILABLE')
        chosen,total=choose(enumerate(rows,2),key,spec,fields)
        output=csv_bytes(fields,[x[2] for x in chosen]);locations=[{'recordId':identity,'source':{'row':at},'view':{'row':i+2}} for i,(at,identity,row) in enumerate(chosen)]
    elif kind=='JSON_RECORDS':
        obj=loads(raw.decode('utf-8-sig'));collection=pointer(spec['collectionPointer'],obj)
        if not isinstance(collection,list):fail('JSON_RECORD_COLLECTION_REQUIRED')
        chosen,total=choose(enumerate(collection),key,spec,fields)
        output=(json_text({'records':[x[2] for x in chosen]})+'\n').encode('utf-8')
        locations=[{'recordId':identity,'source':{'jsonPointer':spec['collectionPointer']+'/'+str(at)},'view':{'jsonPointer':'/records/'+str(i)}} for i,(at,identity,row) in enumerate(chosen)]
    else:
        rows=[]
        for number,line in enumerate(raw.decode('utf-8-sig').splitlines(),1):
            if line.strip():rows.append((number,loads(line)))
        chosen,total=choose(rows,key,spec,fields)
        output=''.join(json_text(x[2])+'\n' for x in chosen).encode('utf-8')
        locations=[{'recordId':identity,'source':{'line':at},'view':{'line':i+1}} for i,(at,identity,row) in enumerate(chosen)]
    return output,locations,total,len(chosen),fields
def workbook(raw,spec):
    if not isinstance(spec['sheets'],list) or not spec['sheets']:fail('WORKBOOK_SHEET_SELECTION_REQUIRED')
    book=scoped_xlsx.load(raw);available={s['name']:s for s in book['sheets']}
    names=set();selected=[];allowed=set();source_total=0
    for rule in spec['sheets']:
        keys(rule,['sheet','headerRow','key','columns'],['rowKeys','allRows']);name=rule['sheet']
        if not isinstance(name,str) or name in names or name not in available:fail('WORKBOOK_SHEET_NOT_AVAILABLE_OR_DUPLICATED')
        names.add(name);sheet=available[name]
        if not sheet['visible']:fail('HIDDEN_WORKBOOK_SHEET_FORBIDDEN')
        header_row=integer(rule['headerRow'])
        if header_row in sheet['hiddenRows'] or header_row not in sheet['rows']:fail('WORKBOOK_HEADER_NOT_VISIBLE')
        headers={}
        for col,cell in sheet['rows'][header_row].items():
            if col in sheet['hiddenColumns']:continue
            value=cell.get('value','')
            if cell.get('formula') or cell.get('type')!='s' or not isinstance(value,str) or not value or value in headers:fail('WORKBOOK_HEADER_AMBIGUOUS')
            headers[value]=col
        fields=row_columns(rule)
        if not set(fields)<=set(headers):fail('WORKBOOK_COLUMN_NOT_VISIBLE')
        chosen_rows=[]
        for rn,cells in sorted(sheet['rows'].items()):
            if rn<=header_row or rn in sheet['hiddenRows']:continue
            if not any(c.get('value') or c.get('formula') for c in cells.values()):continue
            row={field:cells.get(col,{}).get('value','') for field,col in headers.items()}
            chosen_rows.append((rn,row))
        chosen,total=choose(chosen_rows,rule['key'],rule,fields);source_total+=total
        for rn,identity,row in chosen:allowed.update((name,headers[field],rn) for field in fields)
        selected.append((name,sheet,headers,fields,chosen))
    output=[];locations=[];fields_by_sheet={}
    for name,sheet,headers,fields,chosen in selected:
        rows=[[(i+1,'s',field,False) for i,field in enumerate(fields)]]
        for rn,identity,row in chosen:
            cells=[]
            for i,field in enumerate(fields,1):
                col=headers[field];cell=sheet['rows'][rn].get(col,{'type':'s','value':'','date':False})
                value=cell['value'];typ=cell['type']
                if cell.get('formula'):
                    # A collaborative projection does not silently change a full-range
                    # formula. Only dependencies already in the explicit selection pass.
                    value=scoped_xlsx.formula_value(book,name,col,rn,allowed,project_ranges=False)
                    if isinstance(value,bool):typ='b';value='1' if value else '0'
                    elif isinstance(value,Decimal):typ='n';value=str(value)
                    else:typ='s'
                if typ=='e':fail('WORKBOOK_ERROR_CELL_NOT_MATERIALIZABLE')
                cells.append((i,typ,value,cell.get('date',False)))
            rows.append(cells);locations.append({'recordId':identity,'source':{'sheet':name,'row':rn},'view':{'sheet':name,'row':len(rows)}})
        output.append({'name':name,'rows':rows});fields_by_sheet[name]=fields
    return scoped_xlsx.write(output,book['date1904']),locations,source_total,sum(len(x[4]) for x in selected),fields_by_sheet


def json_field_values(document,field):
    """Resolve declared nested paths against the original JSON, retaining locators."""
    tokens=field.split(".")
    def walk(value,rest,location):
        if not rest:return [(dict(jsonPath=location),value,True,{})]
        # A root JSON list is a table; unprefixed fields apply to each record.
        if isinstance(value,list):
            return [result for i,child in enumerate(value) for result in walk(child,rest,f"{location}[{i}]")]
        token=rest[0];array=token.endswith("[]");key=token[:-2] if array else token
        if not isinstance(value,dict) or key not in value:return [(dict(jsonPath=location,field=field),None,False,{})]
        child=value[key];here=f"{location}.{key}"
        if array:
            if not isinstance(child,list):return [(dict(jsonPath=here),child,False,value)]
            return [result for i,item in enumerate(child) for result in walk(item,rest[1:],f"{here}[{i}]")]
        if len(rest)==1:return [(dict(jsonPath=here),child,True,value)]
        return walk(child,rest[1:],here)
    return walk(document,tokens,"$")

def projection_presence(observations):
    """Freeze the field's positions, including absent slots, without recording values."""
    slots = [(where['jsonPath'], present) for where, _, present, _ in observations]
    encoded = json.dumps(slots, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    return {
        'kind': 'SOURCE_PROJECTION_PRESENCE_V1',
        'slotCount': len(slots),
        'presentCount': sum(present for _, present in slots),
        'sha256': hashlib.sha256(encoded).hexdigest(),
    }


class FieldDeclarations:
    """Retain source constraints for the actual projection, without guessing types."""
    CONSTRAINTS=('type','nullable','required','unit','timezone','null_values','null_rule','nullRule',
                 'calculation','formulaType','time_granularity','timeGranularity')
    def __init__(self,items):
        if not isinstance(items,list):fail('FIELD_DICTIONARY_INVALID')
        self.items={}
        for item in items:
            if not isinstance(item,dict) or not isinstance(item.get('path'),str) or not isinstance(item.get('field'),str) or not item['field']:
                fail('FIELD_DICTIONARY_INVALID')
            path=safe_path(item['path'].lstrip('/'))
            sheet=item.get('sheet')
            if sheet is not None and (not isinstance(sheet,str) or not sheet):fail('FIELD_DICTIONARY_INVALID')
            value={k:item[k] for k in self.CONSTRAINTS if k in item}
            if not isinstance(value.get('type'),str) or not value['type']:fail('FIELD_DICTIONARY_INVALID')
            for constraint in ('nullable', 'required'):
                if constraint in value and type(value[constraint]) is not bool:
                    fail('FIELD_DICTIONARY_INVALID')
            key=(path,sheet,item['field'])
            if key in self.items and self.items[key]!=value:fail('AMBIGUOUS_FIELD_DICTIONARY')
            self.items[key]=value
    def field(self,source,out,name,sheet=None,source_name=None):
        source=safe_path(source.lstrip('/'));source_name=source_name or name
        keys=[(source,sheet,source_name)]
        if sheet is not None:keys.extend([(source,None,sheet+'.'+source_name),(source,sheet,sheet+'.'+source_name),(source,None,source_name)])
        values=[self.items[k] for k in dict.fromkeys(keys) if k in self.items]
        if not values:return None
        if any(v!=values[0] for v in values[1:]):fail('AMBIGUOUS_FIELD_DICTIONARY')
        result=dict(values[0],path=out,field=name)
        if sheet is not None:result['sheet']=sheet
        return result
    def whole(self,source,out):
        return [dict(value,path=out,field=name,**({'sheet':sheet} if sheet is not None else {}))
                for (path,sheet,name),value in self.items.items() if path==source.lstrip('/')]
    @staticmethod
    def root_present(document,name):
        root=name.split('.',1)[0].removesuffix('[]') if hasattr(str,'removesuffix') else name.split('.',1)[0].replace('[]','')
        if isinstance(document,list):return any(FieldDeclarations.root_present(x,name) for x in document)
        return isinstance(document,dict) and root in document
    def document(self,source,out,document):
        declarations = []
        for declaration in self.whole(source, out):
            name = declaration['field']
            required_record_field = isinstance(document, list) and declaration.get('required') is True
            if 'sheet' in declaration or (not required_record_field and not self.root_present(document, name)):
                continue
            # The document holds selected original records before serialization.
            # Explicit required declarations remain required for these records.
            if declaration.get('required') is not True:
                pattern = projection_presence(json_field_values(document, name))
                if pattern['presentCount'] == 0:
                    continue
                if pattern['presentCount'] < pattern['slotCount']:
                    declaration['projectionPresence'] = pattern
            declarations.append(declaration)
        return declarations
    def records(self,source,out,names,collection=None):
        prefix=''
        if collection:
            parts=[x.replace('~1','/').replace('~0','~') for x in collection.lstrip('/').split('/')]
            if any(not p or any(c in p for c in '.[]/') for p in parts):fail('DECLARATION_COLLECTION_PATH_AMBIGUOUS')
            prefix='.'.join(parts)+'[].'
        result=[]
        for name in names:
            decl=self.field(source,out,('records[].' if collection is not None else '')+name,source_name=prefix+name)
            if decl:result.append(decl)
        return result
    @staticmethod
    def evaluated_formula(declaration,cells,formula_used):
        if not declaration or not formula_used:return declaration
        declared=declaration['type'].lower()
        expression=declaration.get('formulaType')=='expression' or '公式' in str(declaration.get('calculation',''))
        if declared not in ('string','str','text') or not expression:return declaration
        types=set()
        for typ,value,date in cells:
            if value is None or value=='':continue
            if date or typ=='d':types.add('datetime')
            elif typ=='n':types.add('number')
            elif typ=='b':types.add('boolean')
            elif typ in ('s','inlineStr','str'):types.add('string')
            else:fail('FORMULA_RESULT_TYPE_NOT_DECLARABLE')
        if not types:fail('FORMULA_RESULT_TYPE_NOT_DECLARABLE')
        result=dict(declaration,type='|'.join(sorted(types)),formulaType='evaluated_result',
                    calculation='AUTHORIZED_FORMULA_RESULT_TYPES_V1')
        return result

class Compiler:
    def __init__(self,snapshot,rules,output):
        keys(rules,['schema','viewId','targetOrgId','targetEnterpriseId','source','files','reason'])
        if rules['schema']!=SCHEMA:fail('UNKNOWN_RULE_SCHEMA')
        self.view=uuid_value(rules['viewId']);self.target_org=uuid_value(rules['targetOrgId'])
        if not isinstance(rules['targetEnterpriseId'],str) or not re.fullmatch('ENT-[A-Za-z0-9]{1,100}',rules['targetEnterpriseId']):fail('TARGET_ENTERPRISE_ID_REQUIRED')
        if not isinstance(rules['reason'],str) or not 6<=len(rules['reason'].strip())<=500:fail('EXPLICIT_RULE_REASON_REQUIRED')
        keys(rules['source'],['datasetId','versionId','rawCommitId','inputInventorySha256'])
        if rules['source']!=snapshot.source:fail('SOURCE_SNAPSHOT_BINDING_MISMATCH')
        if not isinstance(rules['files'],list) or not 1<=len(rules['files'])<=2048:fail('EXPLICIT_FILE_SELECTION_REQUIRED')
        self.snapshot=snapshot;self.rules=rules;self.rule_sha=digest(canonical(rules));self.target=Path(output)
        if self.target.is_symlink() or self.target.exists():fail('OUTPUT_MUST_BE_NEW_PRIVATE_DIRECTORY')
        resolved=self.target.resolve()
        if resolved==snapshot.cache or snapshot.cache in resolved.parents or resolved in snapshot.cache.parents:fail('OUTPUT_OVERLAPS_SOURCE_CACHE')
        self.public=self.target/'public'
        self.assets=[];self.provenance=[];self.fields=[];self.entities=[];self.relations=[];self.outputs=set();self.output_casefold=set();self.sources=set();self.complete=False
        self.declarations=FieldDeclarations(loads(snapshot.read('catalog/field_dictionary.json').decode('utf-8-sig')) if 'catalog/field_dictionary.json' in snapshot.files else [])
        self.target.mkdir(mode=0o700,parents=True,exist_ok=False);self.public.mkdir(mode=0o700)
    def file(self,spec):
        common=['sourcePath','sourceSha256','outputPath','kind'];keys(spec,common,['key','columns','rowKeys','allRows','collectionPointer','sheets','ranges','acknowledgement'])
        source=safe_path(spec['sourcePath'],True);out=safe_path(spec['outputPath'],True)
        if source not in self.snapshot.files or self.snapshot.files[source]['sha256']!=hash_value(spec['sourceSha256']):fail('RULE_SOURCE_FILE_IDENTITY_MISMATCH')
        if out in self.outputs or out.casefold() in self.output_casefold:fail('OUTPUT_PATH_COLLISION')
        kind=spec['kind'];extras={
          'CSV_ROWS':(['key','columns'],['rowKeys','allRows']),
          'JSON_RECORDS':(['collectionPointer','key','columns'],['rowKeys','allRows']),
          'JSONL_RECORDS':(['key','columns'],['rowKeys','allRows']),
          'XLSX_SHEETS':(['sheets'],[]),'TEXT_LINES':(['ranges'],[]),'COMPLETE_FILE':(['acknowledgement'],[])}
        if not isinstance(kind,str) or kind not in extras:fail('UNIMPLEMENTED_PROJECTION_KIND')
        required,optional=extras[kind];keys(spec,common+required,optional)
        ext=PurePosixPath(source).suffix.lower();out_ext=PurePosixPath(out).suffix.lower()
        expected={'CSV_ROWS':'.csv','JSON_RECORDS':'.json','JSONL_RECORDS':'.jsonl','XLSX_SHEETS':'.xlsx','TEXT_LINES':'.txt'}
        if kind in expected and (ext!=expected[kind] or out_ext!=ext):fail('PROJECTION_FORMAT_MISMATCH')
        if kind=='COMPLETE_FILE' and (ext not in FORMATS or out_ext!=ext):fail('COMPLETE_FILE_FORMAT_MISMATCH')
        raw=self.snapshot.read(source);total=count=None;fields=[]
        if kind in ['CSV_ROWS','JSON_RECORDS','JSONL_RECORDS']:data,loc,total,count,fields=records(kind,raw,spec)
        elif kind=='XLSX_SHEETS':data,loc,total,count,fields=workbook(raw,spec)
        elif kind=='TEXT_LINES':
            lines=raw.decode('utf-8-sig').splitlines(keepends=True);ranges=spec['ranges']
            if not isinstance(ranges,list) or not ranges:fail('EXPLICIT_TEXT_LINE_RANGES_REQUIRED')
            chosen=[];end=0
            for interval in ranges:
                if not isinstance(interval,list) or len(interval)!=2:fail('TEXT_LINE_RANGE_INVALID')
                first,last=integer(interval[0]),integer(interval[1])
                if first<=end or first>last or last>len(lines):fail('TEXT_LINE_RANGE_OVERLAP_OR_UNAVAILABLE')
                chosen.extend(range(first,last+1));end=last
            data=''.join(lines[i-1] for i in chosen).encode('utf-8');total=len(lines);count=len(chosen)
            loc=[{'source':{'line':at},'view':{'line':i+1}} for i,at in enumerate(chosen)]
        else:
            if spec['acknowledgement']!=ACK:fail('EXACT_COMPLETE_FILE_ACKNOWLEDGEMENT_REQUIRED')
            data=raw;loc=[{'source':{'wholeAsset':True},'view':{'wholeAsset':True}}];self.complete=True
        target=self.public/out;target.parent.mkdir(parents=True,exist_ok=True)
        with target.open('xb') as stream:stream.write(data)
        output_sha=digest(data);asset_id=str(uuid.uuid5(uuid.UUID(self.view),out))
        asset={'asset_id':asset_id,'path':out,'format':FORMATS[out_ext],'sha256':output_sha,'bytes':len(data),'title':PurePosixPath(out).name,
          'generation_nature':'explicit_collaboration_projection','computation_scope':'materialized_selection_only','version':SCHEMA,
          'authorized_for_enterprise_ids':[self.rules['targetEnterpriseId']],'source_bytes_preserved':kind=='COMPLETE_FILE',
          'original_byte_export_requires_raw_download':kind=='COMPLETE_FILE','bindings':[],
          'record_metadata':[{'record_id':l.get('recordId'),'location':l['view'],'authorized_for_enterprise_ids':[self.rules['targetEnterpriseId']]} for l in loc if l.get('recordId')]}
        self.assets.append(asset);self.outputs.add(out);self.output_casefold.add(out.casefold());self.sources.add(source)
        self.provenance.append({'path':out,'sha256':output_sha,'sourcePath':source,'sourceFileId':self.snapshot.files[source]['id'],'sourceSha256':spec['sourceSha256'],'kind':kind,'sourceRecordCount':total,'selectedRecordCount':count,'locations':loc})
        if kind=='COMPLETE_FILE':self.fields.extend(self.declarations.whole(source,out))
        elif kind=='JSON_RECORDS':self.fields.extend(self.declarations.records(source,out,fields,spec['collectionPointer']))
        elif kind=='JSONL_RECORDS':self.fields.extend(self.declarations.records(source,out,fields))
        else:
            selections=fields.items() if isinstance(fields,dict) else [(None,fields)]
            source_book=scoped_xlsx.load(raw) if kind=='XLSX_SHEETS' else None
            output_book=scoped_xlsx.load(data) if source_book else None
            for sheet,names in selections:
                for name in names:
                    declaration=self.declarations.field(source,out,name,sheet)
                    if source_book:
                        rule=next(x for x in spec['sheets'] if x['sheet']==sheet)
                        original_sheet=next(x for x in source_book['sheets'] if x['name']==sheet)
                        column=next(c for c,v in original_sheet['rows'][rule['headerRow']].items() if v['value']==name)
                        source_rows=[x['source']['row'] for x in loc if x['source']['sheet']==sheet]
                        formula_used=any(original_sheet['rows'].get(r,{}).get(column,{}).get('formula') for r in source_rows)
                        rebuilt=next(x for x in output_book['sheets'] if x['name']==sheet)
                        outcol=next(c for c,v in rebuilt['rows'][1].items() if v['value']==name)
                        cells=[(c['type'],c['value'],c.get('date',False)) for rn,row in rebuilt['rows'].items() if rn>1 for col,c in row.items() if col==outcol]
                        declaration=self.declarations.evaluated_formula(declaration,cells,formula_used)
                    if declaration:self.fields.append(declaration)
        for l in loc:
            if l.get('recordId'):self.entities.append({'entity_id':l['recordId'],'identity_kind':'business_record','path':out,'location':l['view']})
    def run(self):
        try:
            for spec in self.rules['files']:self.file(spec)
            root=self.public/'catalog';root.mkdir()
            docs={'assets.json':self.assets,'field_dictionary.json':self.fields,'entities.json':self.entities,'relations.json':[],
              'retrieval_manifest.json':{'version':SCHEMA,'path_base':'.','scope':'materialized-collaboration-view','files':[{'path':x['path'],'sha256':x['sha256']} for x in self.assets]},
              'sources_and_assumptions.json':{'version':SCHEMA,'sources':[],'assumptions':[]},
              'view_scope.json':{'viewId':self.view,'ruleSha256':self.rule_sha,'policyCheckRequired':True,'hasOriginalByteCopies':self.complete}}
            for name,value in docs.items():(root/name).write_text(json_text(value)+'\n',encoding='utf-8')
            inventory=[{'path':str(p.relative_to(self.public)).replace('\\','/'),'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(self.public.rglob('*')) if p.is_file()]
            receipt={'schema':SCHEMA,'viewId':self.view,'targetOrgId':self.target_org,'targetEnterpriseId':self.rules['targetEnterpriseId'],'source':self.snapshot.source,'ruleSha256':self.rule_sha,'hasOriginalByteCopies':self.complete,
              'state':'CANDIDATE_AWAITING_REVIEW','files':inventory,'provenance':self.provenance,
              'moduleSha256':{n:sha(Path(__file__).parent/n) for n in (['collaboration_view.py','scope_ownership.py','scoped_xlsx.py']+(['collaboration_job.py'] if (Path(__file__).parent/'collaboration_job.py').is_file() else []))}}
            (self.target/'private-rules.json').write_bytes(canonical(self.rules)+b'\n')
            (self.target/'private-provenance.json').write_text(json_text(receipt)+'\n',encoding='utf-8')
            summary={'schema':SCHEMA,'viewId':self.view,'state':'CANDIDATE_AWAITING_REVIEW','fileCount':len(self.assets),'hasOriginalByteCopies':self.complete,'ruleSha256':self.rule_sha,'candidateReceiptSha256':sha(self.target/'private-provenance.json'),'originalObjectsModified':False,'authorizationGranted':False,'published':False}
            (self.target/'candidate-state.json').write_bytes(canonical(summary)+b'\n');return summary
        except BaseException as error:
            (self.target/'candidate-state.json').write_bytes(canonical({'state':'FAILED_NOT_PUBLISHED','errorType':type(error).__name__,'errorCode':str(error) if isinstance(error,RuleError) else 'PROJECTION_FAILED','authorizationGranted':False,'published':False})+b'\n')
            raise
def main():
    p=argparse.ArgumentParser();p.add_argument('--source-manifest',required=True);p.add_argument('--source-cache',required=True);p.add_argument('--rules',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    snapshot=Snapshot(a.source_manifest,a.source_cache);rules=loads(Path(a.rules).read_text(encoding='utf-8'))
    print(json.dumps(Compiler(snapshot,rules,a.output).run()))
if __name__=='__main__':main()
