"""Build private, reviewable enterprise candidates from an immutable verified corpus.

This module never creates an access grant, mutates the source, or publishes an
index. Only public/ is an ingestion payload. Provenance and pending decisions
remain outside that payload.
"""
import argparse,csv,hashlib,io,json,re,sys,uuid,collections
from pathlib import Path
from decimal import Decimal
from scope_ownership import Corpus,PRIMARY,json_load,json_text,sha256,identifiers
import scoped_xlsx
from collaboration_view import FieldDeclarations
RULE_VERSION='enterprise-responsibility-v4'
SAFE_CONTEXT={'产品':'products','销售':'sales','采购':'purchases','交付':'deliveries','采购订单':'purchases','制造批次':'production','加工工单':'supplier_work_orders','加工报工':'supplier_operations','组装过程':'assembly_operations','仓储流水':'warehouse_movements','库存流水':'warehouse_movements','运次配载':'shipments','随车明细':'shipment_lines','运单明细':'shipment_lines','订单履约':'sales','签收明细':'deliveries','检验':'quality'}
PROJECTION_FIELDS={
 'purchases':['purchase_line_id','purchase_id','supplier_id','manufacturer_id','destination_node_id','contract_id','part_id','quantity','unit','unit_price_cny','amount_cny','ordered_at','due_at','status'],
 'sales':['order_line_id','order_id','customer_id','manufacturer_id','contract_id','sku_id','quantity','unit','unit_price_cny','amount_cny','ordered_at','due_at','status','signed_quantity','outstanding_quantity','fulfilment_state'],
 'deliveries':['delivery_line_id','order_line_id','finished_batch_id','sku_id','shipped_quantity','loaded_quantity','signed_quantity','unit','loaded_at','shipped_at','signed_at','carrier_id','warehouse_id'],
 'services':['service_id','service_company_id','service_node_id','customer_id','serial_id','delivery_line_id','sku_id','service_type','status','started_at','completed_at','service_contract_id','result'],
}
def csv_bytes(headers,rows):
    stream=io.StringIO(newline='');writer=csv.DictWriter(stream,fieldnames=headers,lineterminator='\n',extrasaction='raise');writer.writeheader();writer.writerows(rows)
    return stream.getvalue().encode('utf-8')
def record_key(table,row):
    if table=='organizations':return 'node_id' if row.get('node_id') else 'company_id'
    for prefix,key in [('customer_ledger_','order_line_id'),('supply_ledger_','supplier_batch_id'),('procurement_ledger_','purchase_line_id'),('carriage_ledger_','shipment_id'),('stock_ledger_','movement_id')]:
        if table.startswith(prefix):return key
    return PRIMARY.get(table)
def record_id(table,row):
    return row.get(record_key(table,row) or '') or row.get('record_id') or row.get('series_id')
def locator(row,sheet=None,pointer=None):
    result={'row':row}
    if sheet is not None:result['sheet']=sheet
    if pointer is not None:result={'jsonPointer':pointer}
    return result
def row_permitted(c,table,row,enterprise,projection=False):
    if c.row_allowed(table,row,enterprise):return True
    if table=='sales' and c.line_customer(row.get('order_line_id'))==enterprise:return True
    if table=='purchases' and c.company(row.get('supplier_id'),'SUPPLIER')==enterprise and c.part_owner(row.get('part_id'))==enterprise:return True
    if table=='deliveries' and c.line_customer(row.get('order_line_id'))==enterprise:return True
    if table=='services' and c.service_customer(row)==enterprise:return True
    return False
class Builder:
    def __init__(self,corpus,enterprise,directory,view_id,full_files=None,review_pending=False):
        self.c=corpus;self.enterprise=enterprise;self.directory=directory;self.public=directory/'public';self.view_id=view_id;self.review_pending=review_pending
        self.public.mkdir(parents=True,exist_ok=False);self.assets=[];self.provenance=[];self.pending=[];self.rows={};self.fields=[];self.entities=[];self.edges=[];self.full_files=full_files or {}
        self.declarations=FieldDeclarations(json_load(corpus.read('catalog/field_dictionary.json').decode('utf-8-sig')))
    def source_metadata(self,path,item):
        records=getattr(self.c,'asset_by_path',{}).get(path,{}).get('record_metadata',[])
        identity=item.get('recordId');where=item['source']
        def position_matches(meta):
            at=dict(meta.get('location') or {});text=meta.get('locator') or ''
            if not at:
                cell=re.match(r'([^!]+)![A-Z]+(\d+)(?::[A-Z]+\d+)?(?:\s|$)',text)
                row=re.match(r'row\s+(\d+)(?:\s|$)',text)
                line=re.match(r'line\s+(\d+)(?:\s|$)',text)
                if cell:at={'sheet':cell.group(1),'row':int(cell.group(2))}
                elif row:at={'row':int(row.group(1))}
                elif line:at={'line':int(line.group(1))}
            if 'jsonPointer' in where:return (at.get('pointer') or at.get('jsonPointer') or at.get('json_pointer'))==where['jsonPointer']
            if 'sheet' in where and at.get('sheet')!=where['sheet']:return False
            if 'row' in where:return at.get('row')==where['row']
            if 'line' in where:return at.get('line')==where['line'] or at.get('lines')==[where['line']]
            return False
        exact=[m for m in records if position_matches(m)]
        candidates=exact or [m for m in records if identity and m.get('record_id')==identity]
        if not candidates:return {}
        safe=[{k:m[k] for k in ['record_id','data_producer_ids','data_provider_ids','business_stage'] if k in m} for m in candidates]
        if any(x!=safe[0] for x in safe[1:]):raise ValueError('Ambiguous source record attribution')
        return safe[0]
    def put(self,path,raw,source_path,locations=None,rows=None,headers=None,sheet=None,kind='ROW_PROJECTION'):
        if not path.startswith('corpus/') or '..' in Path(path).parts:raise ValueError('Unsafe derived path')
        output=self.public/path;output.parent.mkdir(parents=True,exist_ok=True)
        if output.exists():raise ValueError('Duplicated derived asset')
        output.write_bytes(raw);digest=hashlib.sha256(raw).hexdigest();asset_id=str(uuid.uuid5(self.view_id,path))
        record_meta=[];bindings=[]
        for number,item in enumerate(locations or [],2):
            identity=item.get('recordId');view_location=item['view'];source_location=item['source']
            original=self.source_metadata(source_path,item)
            if not identity:identity=original.get('record_id')
            meta={'record_id':identity,'location':view_location,'locator':item['locator'],'authorized_for_enterprise_ids':[self.enterprise],'attribution_state':'SOURCE_RECORD_PRESERVED' if original else 'SOURCE_RECORD_METADATA_UNAVAILABLE'}
            if original.get('record_id') and original['record_id']!=identity:meta['source_record_id']=original['record_id']
            for key in ['data_producer_ids','data_provider_ids','business_stage']:
                if key in original:meta[key]=original[key]
            record_meta.append(meta)
            if identity:bindings.append({'entity_ids':[identity],'location':view_location})
        asset={'asset_id':asset_id,'path':path,'format':Path(path).suffix.lstrip('.').upper(),'sha256':digest,'bytes':len(raw),'title':Path(path).name,'summary':'已物化的企业授权范围；发布和使用须经服务端当前有效授权。','language':'zh-CN','version':RULE_VERSION,'generation_nature':'derived_authorized_projection','computation_scope':'authorized_materialized_cells_only','declaration_scope':'catalog_metadata','record_metadata':record_meta,'bindings':bindings,'derived_from_paths':[],'origin':'immutable_snapshot_projection','authorized_for_enterprise_ids':[self.enterprise],'business_stages':sorted({m['business_stage'] for m in record_meta if m.get('business_stage')})}
        self.assets.append(asset)
        source=self.c.files[source_path]
        self.provenance.append({'path':path,'sha256':digest,'kind':kind,'formulaProjectionRule':'AUTHORIZED_RANGE_AGGREGATES_V1' if kind=='XLSX_VISIBLE_TYPED_CELL_REBUILD' else None,'sourcePath':source_path,'sourceFileId':source['file_id'],'sourceSha256':source['sha256'].strip(),'locations':locations or []})
        if rows is not None:self.rows[path]=rows
        if headers:
            for field in headers:
                declaration=self.declarations.field(source_path,path,field,sheet)
                if declaration:self.fields.append(declaration)
    def csv(self,path):
        table=Path(path).stem
        if table not in self.c.tables:return
        headers=self.c.headers[path];rows=[];locations=[]
        for number,row in enumerate(self.c.tables[table],2):
            if self.c.row_allowed(table,row,self.enterprise):
                rows.append(row);locations.append({'recordId':record_id(table,row),'source':locator(number),'view':locator(len(rows)+1),'locator':'row '+str(len(rows)+1)})
        if rows:self.put(path,csv_bytes(headers,rows),path,locations,rows,headers)
    def projections(self):
        for table,available in PROJECTION_FIELDS.items():
            source='corpus/structured/'+table+'.csv';headers=[x for x in available if x in self.c.headers.get(source,[])]
            rows=[];locations=[]
            for number,row in enumerate(self.c.tables[table],2):
                if self.c.row_allowed(table,row,self.enterprise) or not row_permitted(self.c,table,row,self.enterprise,True):continue
                rows.append({k:row[k] for k in headers})
                locations.append({'recordId':record_id(table,row),'source':locator(number),'view':locator(len(rows)+1),'locator':'row '+str(len(rows)+1)})
            if rows:self.put('corpus/structured/party_'+table+'.csv',csv_bytes(headers,rows),source,locations,rows,headers,kind='EXPLICIT_PARTY_COLUMN_PROJECTION')
        # Multi-party contract rows are never copied for mere participation.
        # Party transaction facts already appear above, retaining their contract ID.
    def json_file(self,path):
        obj=json_load(self.c.read(path).decode('utf-8-sig'));locations=[];projected={}
        if not isinstance(obj,dict):self.pending.append({'path':path,'reason':'UNSUPPORTED_ROOT_SCHEMA'});return
        for key,value in obj.items():
            if not isinstance(value,list) or not value or not all(isinstance(x,dict) for x in value):continue
            selected=[]
            for index,row in enumerate(value):
                if self.c.json_owner(path,row)==self.enterprise:
                    selected.append(row)
                    identity=next((row[k] for k in ['bom_line_id','pair_id','station_id','equipment_id','process_id','input_lot_id','slot_id','vehicle_id','sensor_id','series_id','case_id','link_id','sku_id'] if row.get(k)),None)
                    locations.append({'recordId':identity,'source':locator(None,pointer='/'+key+'/'+str(index)),'view':locator(None,pointer='/'+key+'/'+str(len(selected)-1)),'locator':'/'+key+'/'+str(len(selected)-1)})
            if selected:projected[key]=selected
        if not projected:return
        # Parent declarations are rebuilt from the selected children. Free prose,
        # all-SKU lists, counts and unrelated rules are not copied from the parent.
        result={'version':RULE_VERSION,'simulation':obj.get('simulation',True),'scope_enterprise_id':self.enterprise}
        if 'timezone' in obj:result['timezone']=obj['timezone']
        if 'category_id' in obj and ('products' in projected or 'bom' in projected):result['category_id']=obj['category_id']
        result.update(projected)
        if 'products' in projected:result['sku_ids']=sorted({x['sku_id'] for x in projected['products']})
        self.put(path,(json_text(result)+'\n').encode('utf-8'),path,locations,kind='JSON_POINTER_PROJECTION')
        generated={'version':('string',False),'simulation':('boolean',False),'scope_enterprise_id':('string',False)}
        if 'sku_ids' in result:generated['sku_ids']=('array',False)
        self.fields.extend(d for d in self.declarations.document(path,path,result) if d['field'] not in generated)
        self.fields.extend({'path':path,'field':name,'type':typ,'nullable':nullable} for name,(typ,nullable) in generated.items())
    def json_lines(self,path):
        selected=[];locations=[]
        for line,text in enumerate(self.c.read(path).decode('utf-8-sig').splitlines(),1):
            if not text.strip():continue
            row=json_load(text)
            if self.c.json_owner(path,row)==self.enterprise:
                selected.append(row);identity=next((row[k] for k in ['message_id','event_id','relation_id'] if row.get(k)),None)
                locations.append({'recordId':identity,'source':{'line':line},'view':{'line':len(selected)},'locator':'line '+str(len(selected))})
        if selected:self.put(path,(''.join(json_text(x)+'\n' for x in selected)).encode('utf-8'),path,locations,kind='JSONL_RECORD_PROJECTION')
        if selected:self.fields.extend(self.declarations.document(path,path,selected))
    def workbook(self,path):
        book=scoped_xlsx.load(self.c.read(path));selected={};headers_by_sheet={};all_allowed=set();locations=[]
        for sheet in book['sheets']:
            if not sheet['visible']:continue
            context=SAFE_CONTEXT.get(sheet['name']);headers=None;header_row=None
            for rn,cells in sorted(sheet['rows'].items()):
                if rn in sheet['hiddenRows']:continue
                values={col:cell['value'] for col,cell in cells.items() if col not in sheet['hiddenColumns']}
                expected=PRIMARY.get(context)
                # Original machine header cells are the schema boundary. No title
                # or pre-header commentary survives the new workbook.
                if expected and expected in values.values():
                    headers=values;header_row=rn;break
            if not headers:continue
            chosen=[]
            for rn,cells in sorted(sheet['rows'].items()):
                if rn<=header_row or rn in sheet['hiddenRows']:continue
                rawrow={field:cells.get(col,{}).get('value','') for col,field in headers.items()}
                identity=rawrow.get(PRIMARY[context]);original=self.c.byid.get(context,{}).get(identity)
                if not original:continue
                # Party workbook scopes are not inferred from a manufacturer's
                # SKU: each row uses its actual transaction primary key.
                if not row_permitted(self.c,context,original,self.enterprise,True):continue
                # Fields outside a party projection remain restricted.
                own=self.c.row_allowed(context,original,self.enterprise)
                allowed_cols={col for col,field in headers.items() if own or field in PROJECTION_FIELDS.get(context,[])}
                chosen.append((rn,cells,identity,allowed_cols))
                all_allowed.update((sheet['name'],col,rn) for col in allowed_cols)
            if chosen:selected[sheet['name']]=(sheet,chosen);headers_by_sheet[sheet['name']]=headers
        output=[];result_headers={}
        for name,(sheet,chosen) in selected.items():
            headers=headers_by_sheet[name];rows=[];safe_rows=[];column_union=set()
            # A formula with a foreign dependency is rejected rather than using a
            # stale cache or inventing an empty/zero value.
            for rn,cells,identity,allowed_cols in chosen:
                values=[];failed=False
                for col in sorted(allowed_cols):
                    cell=cells.get(col,{'type':'n','value':'','date':False})
                    value=cell['value'];typ=cell['type']
                    if cell.get('formula'):
                        try:
                            actual=scoped_xlsx.formula_value(book,name,col,rn,all_allowed,project_ranges=True)
                            if isinstance(actual,bool):typ='b';value='1' if actual else '0'
                            elif isinstance(actual,Decimal):typ='n';value=str(actual)
                            else:typ='s';value=actual
                        except Exception as exc:
                            self.pending.append({'path':path,'sheet':name,'row':rn,'cell':cell.get('source'),'reason':'FORMULA_SCOPE_OR_EVALUATION_REJECTED','errorClass':type(exc).__name__,'reasonDetail':str(exc)[:120]});failed=True;break
                    values.append((col,typ,value,cell.get('date',False)))
                if not failed:
                    safe_rows.append((rn,identity,values));column_union.update(allowed_cols)
            if not safe_rows:continue
            remap={old:i for i,old in enumerate(sorted(column_union),1)}
            rows.append([(remap[col],'s',headers[col],False) for col in sorted(column_union)])
            for rn,identity,values in safe_rows:
                rows.append([(remap[col],typ,value,date) for col,typ,value,date in values])
                locations.append({'recordId':identity,'source':{'sheet':name,'row':rn},'view':{'sheet':name,'row':len(rows)},'locator':name+'!A'+str(len(rows))})
            output.append({'name':name,'rows':rows});result_headers[name]=[headers[col] for col in sorted(column_union)]
        if output:
            raw=scoped_xlsx.write(output,book['date1904'])
            self.put(path,raw,path,locations,kind='XLSX_VISIBLE_TYPED_CELL_REBUILD')
            for name,headers in result_headers.items():
                for field in headers:
                    declaration=self.declarations.field(path,path,field,name)
                    rebuilt=next(x for x in output if x['name']==name)
                    outcol=next(col for col,typ,value,date in rebuilt['rows'][0] if value==field)
                    cells=[(typ,value,date) for row in rebuilt['rows'][1:] for col,typ,value,date in row if col==outcol]
                    original_sheet,chosen=selected[name];original_col=next(col for col,value in headers_by_sheet[name].items() if value==field)
                    source_rows={x['source']['row'] for x in locations if x['source'].get('sheet')==name}
                    formula_used=any(original_sheet['rows'][rn].get(original_col,{}).get('formula') for rn in source_rows)
                    declaration=self.declarations.evaluated_formula(declaration,cells,formula_used)
                    if declaration:self.fields.append(declaration)
    def timeseries(self,path):
        reader=csv.DictReader(io.StringIO(self.c.read(path).decode('utf-8-sig')));source=list(reader);selected=[];locations=[]
        for rn,row in enumerate(source,2):
            series=row.get('series_id')
            if series and self.c.extra_owner(series)==self.enterprise:
                selected.append(row);locations.append({'recordId':series,'source':locator(rn),'view':locator(len(selected)+1),'locator':'row '+str(len(selected)+1)})
        if selected:self.put(path,csv_bytes(reader.fieldnames,selected),path,locations,selected,reader.fieldnames,kind='SERIES_SCOPE_PROJECTION')
    def complete_file(self,path):
        grant=self.full_files.get(path)
        if not grant:return
        source=self.c.files[path]
        expected_state='PENDING_PUBLICATION_REVIEW' if self.review_pending else 'CONFIRMED_COMPLETE_FILE'
        receipt_field='request_sha256' if self.review_pending else 'review_receipt_id'
        if grant.get('enterprise_id')!=self.enterprise or grant.get('source_sha256')!=source['sha256'].strip() or grant.get('review_state')!=expected_state or not isinstance(grant.get(receipt_field),str) or not re.fullmatch(r'[0-9a-f]{64}',grant[receipt_field]):
            raise ValueError('Complete file grant is not bound to reviewed exact bytes')
        records=grant.get('record_ids' if self.review_pending else 'reviewed_record_ids')
        expected=[x.get('record_id') for x in self.c.asset_by_path.get(path,{}).get('record_metadata',[])]
        if not expected or any(not isinstance(x,str) or not x for x in expected):raise ValueError('Complete file lacks a complete canonical record inventory')
        if not isinstance(records,list) or not records or len(set(records))!=len(records) or set(records)!=set(expected) or any(self.enterprise not in self.c.record_owner(identity) for identity in records):
            raise ValueError('Complete file review lacks verified canonical records')
        positions=[{'recordId':identity,'source':{'whole_asset':True,'source_entity_id':identity},'view':{'whole_asset':True,'source_entity_id':identity},'locator':'whole_asset'} for identity in records]
        self.put(path,self.c.read(path),path,positions,kind='REQUESTED_COMPLETE_FILE' if self.review_pending else 'REVIEWED_COMPLETE_FILE')
        self.provenance[-1]['requestSha256' if self.review_pending else 'reviewReceiptId']=grant[receipt_field]
        self.assets[-1]['source_bytes_preserved']=True
        self.assets[-1]['original_byte_export_requires_raw_download']=True
    def catalogs(self):
        # Entity/edge declarations resolve to visible materialized row locations.
        identities={}
        for path,rows in self.rows.items():
            source_table=Path(path).stem.removeprefix('party_') if hasattr(str,'removeprefix') else Path(path).stem.replace('party_','',1)
            for rn,row in enumerate(rows,2):
                key=record_key(source_table,row)
                if not key:continue
                identity=row.get(key)
                if not identity:continue
                entry={'entity_id':identity,'identity_kind':'business_record','path':path,'primary_key':key,'locator':'row '+str(rn),'location':{'row':rn}}
                self.entities.append(entry);identities[identity]=entry
        for path,rows in self.rows.items():
            source_table=Path(path).stem.replace('party_','',1)
            for rn,row in enumerate(rows,2):
                key=record_key(source_table,row)
                if not key:continue
                source=row.get(key)
                if not source:continue
                for field,value in row.items():
                    if field==key:continue
                    for target in identifiers(value):
                        if target in identities and target!=source:
                            self.edges.append({'source_id':source,'target_id':target,'path':path,'field':field,'meaning':field,'locator':'row '+str(rn),'location':{'row':rn}})
        catalog=self.public/'catalog';catalog.mkdir()
        docs={'assets.json':self.assets,'entities.json':self.entities,'relations.json':self.edges,'field_dictionary.json':self.fields,
          'retrieval_manifest.json':{'version':RULE_VERSION,'path_base':'.','scope':'enterprise-controlled-view','files':[{'path':a['path'],'sha256':a['sha256']} for a in self.assets]},
          'sources_and_assumptions.json':{'version':RULE_VERSION,'dataset_version':str(self.view_id),'sources':[],'assumptions':[],'simulation_notice':'原创模拟产业链数据的企业范围派生视图；不表示真实法人认证。'}}
        for name,value in docs.items():(catalog/name).write_text(json_text(value)+'\n',encoding='utf-8')
        (catalog/'assets.csv').write_bytes(csv_bytes(['asset_id','path','format','sha256','bytes'],[{k:a[k] for k in ['asset_id','path','format','sha256','bytes']} for a in self.assets]))
    def run(self):
        for path in sorted(self.c.files):
            if not path.startswith('corpus/'):continue
            if path.startswith('corpus/structured/') and path.endswith('.csv'):self.csv(path)
            elif path.endswith('.json'):self.json_file(path)
            elif path.endswith('.jsonl'):self.json_lines(path)
            elif path.endswith('.xlsx'):self.workbook(path)
            elif path.startswith('corpus/timeseries/') and path.endswith('.csv'):self.timeseries(path)
            else:self.complete_file(path)
        self.projections();self.catalogs()
        manifest={'viewId':str(self.view_id),'enterpriseId':self.enterprise,'ruleVersion':RULE_VERSION,'state':'CANDIDATE_AWAITING_REVIEW','sourceSnapshot':self.c.source_snapshot,'ruleModuleSha256':{name:sha256(Path(__file__).parent/name) for name in ['enterprise_view.py','collaboration_view.py','scope_ownership.py','scoped_xlsx.py']},'files':self.provenance,'pending':self.pending}
        (self.directory/'private-provenance.json').write_text(json_text(manifest)+'\n',encoding='utf-8')
        result={'enterpriseId':self.enterprise,'viewId':str(self.view_id),'state':manifest['state'],'businessFiles':len(self.assets),'formats':dict(collections.Counter(a['format'] for a in self.assets)),'pendingFormulaOrSchemaDecisions':len(self.pending),'manifestSha256':sha256(self.directory/'private-provenance.json')}
        (self.directory/'candidate-state.json').write_text(json.dumps(result,indent=2)+'\n')
        return result
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--source-manifest',required=True);parser.add_argument('--source-cache',required=True);parser.add_argument('--output',required=True);parser.add_argument('--enterprise',action='append');parser.add_argument('--complete-file-grants');args=parser.parse_args()
    corpus=Corpus(args.source_manifest,args.source_cache)
    enterprises=args.enterprise or sorted(corpus.companies)
    if any(x not in corpus.companies for x in enterprises) or len(set(enterprises))!=len(enterprises):raise ValueError('Invalid enterprise scope')
    target=Path(args.output).resolve()
    if corpus.cache==target or corpus.cache in target.parents:raise ValueError('Output cannot be inside source cache')
    target.mkdir(parents=True,exist_ok=False)
    grants=json.loads(Path(args.complete_file_grants).read_text()) if args.complete_file_grants else {}
    results=[]
    for enterprise in enterprises:
        view=Builder(corpus,enterprise,target/enterprise,uuid.uuid4(),grants.get(enterprise,{}));results.append(view.run())
    report={'state':'CANDIDATES_BUILT_NOT_PUBLISHED','sourceFilesVerified':len(corpus.files),'enterpriseCount':len(results),'originalObjectsModified':False,'candidates':results,'restrictedCanonicalRows':len(corpus.restricted)}
    (target/'build-state.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'state':report['state'],'enterpriseCount':len(results),'sourceFilesVerified':len(corpus.files),'restrictedCanonicalRows':len(corpus.restricted)},ensure_ascii=False))
if __name__=='__main__':main()
