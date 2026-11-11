"""Deterministic responsibility owners for the frozen appliance corpus.
No type, data producer/provider list or entity-reference union is a grant.
"""
import csv,hashlib,io,json,re,uuid
from pathlib import Path
from decimal import Decimal

INDUSTRIES={'制造':'MANUFACTURER','电子供应':'SUPPLIER','驱动供应':'SUPPLIER','机械供应':'SUPPLIER','零件供应':'SUPPLIER','仓储':'WAREHOUSE','承运':'CARRIER','客户':'CUSTOMER','服务':'SERVICE'}
PRIMARY={'products':'sku_id','parts':'part_id','purchases':'purchase_line_id','sales':'order_line_id','supplier_batches':'supplier_batch_id','production':'manufacturing_batch_id','bom':'bom_line_id','consumption':'consumption_id','allocations':'allocation_id','deliveries':'delivery_line_id','serials':'serial_id','pairings':'pair_id','supplier_work_orders':'work_order_id','supplier_operations':'operation_id','assembly_operations':'operation_id','warehouse_movements':'movement_id','shipments':'shipment_id','shipment_lines':'waybill_line_id','services':'service_id','quality':'qc_id','packaging':'package_id','sales_fulfilment':'fulfilment_event_id','contracts':'contract_id'}
ID_RE=re.compile(r'\b(?:ENT|SKU|PART|PL|SL|SB|MB|FG|BL|CON|DL|SVC|QC|SHIP|WBL|AOP|SOP|SWO|PKG|MOV|SER|PAIR|MSG|TEV|NODE)-[A-Za-z0-9]+\b')
def json_load(raw):
    def unique(items):
        out={}
        for key,value in items:
            if key in out:raise ValueError('Duplicate source JSON field')
            out[key]=value
        return out
    def invalid(value):raise ValueError('Non-finite source JSON number')
    return json.loads(raw,parse_float=Decimal,object_pairs_hook=unique,parse_constant=invalid)
def json_text(value):
    if value is None:return 'null'
    if value is True:return 'true'
    if value is False:return 'false'
    if isinstance(value,Decimal):
        if not value.is_finite():raise ValueError('Non-finite JSON number')
        return str(value)
    if isinstance(value,(int,float)):
        if isinstance(value,float):raise ValueError('Uncontrolled float conversion')
        return str(value)
    if isinstance(value,str):return json.dumps(value,ensure_ascii=False)
    if isinstance(value,(list,tuple)):return '['+','.join(json_text(x) for x in value)+']'
    if isinstance(value,dict):return '{'+','.join(json.dumps(str(k),ensure_ascii=False)+':'+json_text(v) for k,v in value.items())+'}'
    raise TypeError(type(value).__name__)
def entity_ids(value):
    if isinstance(value,(list,tuple)):
        return set(x for v in value for x in entity_ids(v))
    return set(re.findall(r'ENT-[A-Za-z0-9]+',str(value or '')))
def identifiers(value):
    if isinstance(value,dict):return set(x for v in value.values() for x in identifiers(v))
    if isinstance(value,(list,tuple)):return set(x for v in value for x in identifiers(v))
    return set(ID_RE.findall(str(value or '')))
def sha256(path):
    h=hashlib.sha256()
    with Path(path).open('rb') as stream:
        for part in iter(lambda:stream.read(4*1024*1024),b''):h.update(part)
    return h.hexdigest()
class Corpus:
    def __init__(self,manifest,cache,allow_scoped_inventory=False):
        csv.field_size_limit(16*1024*1024)
        self.manifest=json.loads(Path(manifest).read_text());self.cache=Path(cache).resolve(strict=True)
        self.files={};self.tables={};self.headers={};self.byid={};self.owners={};self.restricted=[]
        version=self.manifest.get('version',{})
        self.source_snapshot={'datasetId':str(uuid.UUID(self.manifest['datasetId'])),'versionId':str(uuid.UUID(version['version_id'])),'rawCommitId':str(uuid.UUID(version['raw_commit_id'])),'manifestSha256':sha256(manifest)}
        count=self.manifest.get('inventoryFileCount',version.get('raw_file_count'));total=version.get('raw_file_count')
        scoped=allow_scoped_inventory and self.manifest.get('inventoryScope')=='ENTERPRISE_RESPONSIBILITY_INPUTS_V1'
        if not isinstance(self.manifest.get('files'),list) or not self.manifest['files'] or isinstance(count,bool) or not isinstance(count,int) or count!=len(self.manifest['files']) or isinstance(total,bool) or not isinstance(total,int) or (total<count if scoped else total!=count):raise ValueError('Frozen manifest is incomplete')
        for f in self.manifest['files']:
            uuid.UUID(f['file_id']);path=f['logical_path'].lstrip('/')
            if '..' in Path(path).parts or path in self.files or chr(92) in path or not path.startswith(('corpus/','catalog/')):raise ValueError('Invalid or duplicated frozen path')
            data=self.cache/f['file_id']
            if data.is_symlink() or data.resolve(strict=True).parent!=self.cache or not data.is_file() or data.stat().st_size!=f['size_bytes'] or sha256(data)!=f['sha256'].strip():raise ValueError('Frozen input byte identity mismatch')
            self.files[path]=f
        for path in self.files:
            if path.startswith('corpus/structured/') and path.endswith('.csv'):
                reader=csv.DictReader(io.StringIO(self.read(path).decode('utf-8-sig')));rows=list(reader)
                if not reader.fieldnames or any(not f for f in reader.fieldnames) or len(reader.fieldnames)!=len(set(reader.fieldnames)):raise ValueError('Missing or duplicate source CSV header')
                if any(None in row or any(v is None for v in row.values()) for row in rows):raise ValueError('Malformed CSV record')
                self.tables[Path(path).stem]=rows;self.headers[path]=reader.fieldnames or []
        orgs=self.tables['organizations']
        self.companies={r['company_id']:r for r in orgs if r['record_type']=='企业'}
        self.nodes={r['node_id']:r for r in orgs if r['record_type']=='节点'}
        if not self.companies or any(r['company_id'] not in self.companies for r in self.nodes.values()):raise ValueError('Enterprise/node master is inconsistent')
        self.kinds={k:INDUSTRIES.get(v['role']) for k,v in self.companies.items()}
        for table,key in PRIMARY.items():
            index={}
            for row in self.tables.get(table,[]):
                identity=row.get(key)
                if not identity or identity in index:raise ValueError('Missing or duplicate canonical primary key '+table)
                index[identity]=row
            self.byid[table]=index
        self.fg={r['finished_batch_id']:r for r in self.tables['production']}
        self.order_lines={}
        for row in self.tables['sales']:self.order_lines.setdefault(row['order_id'],[]).append(row)
        self.part_ids=set(self.byid['parts']);self.sku_ids=set(self.byid['products'])
        assets=json_load(self.read('catalog/assets.json').decode('utf-8-sig'))
        self.assets=assets if isinstance(assets,list) else assets['assets']
        self.asset_by_path={x['path'].lstrip('/'):x for x in self.assets}
        self.extras={}
        for source in ['corpus/structured/scene_resources.json','corpus/structured/resources_expansion.json']:
            if source not in self.files:continue
            obj=json_load(self.read(source).decode('utf-8-sig'))
            keys={'stations':'station_id','equipment':'equipment_id','process_routes':'process_id','input_lots':'input_lot_id','slots':'slot_id','vehicles':'vehicle_id','trace_links':'link_id','sensors':'sensor_id','measurement_series':'series_id','service_cases':'case_id','warehouse_transfers':'handover_id','incoming_cases':'case_id','packing_cases':'case_id','appointment_cases':'case_id'}
            for collection,key in keys.items():
                for row in obj.get(collection,[]):
                    if not isinstance(row,dict) or not row.get(key):raise ValueError('Invalid resource master record')
                    self.extras.setdefault(row[key],[]).append((collection,row))
        self.extra_owner_cache={}
        for table,rows in self.tables.items():
            for i,row in enumerate(rows):
                owner=self.owner(table,row)
                if owner:self.owners[(table,i)]=owner
                elif table!='contracts':self.restricted.append({'table':table,'row':i+2,'reason':'UNPROVEN_OR_CONTRADICTORY_RESPONSIBILITY'})
    def read(self,path):return (self.cache/self.files[path.lstrip('/')]['file_id']).read_bytes()
    def company(self,value,kind=None):
        return value if value in self.companies and (kind is None or self.kinds[value]==kind) else None
    def node_owner(self,node,kind=None):
        row=self.nodes.get(node)
        return self.company(row['company_id'],kind) if row else None
    def same(self,values):
        found=[v for v in values if v]
        return found[0] if found and len(set(found))==1 else None
    def product_owner(self,sku):
        return self.company(self.byid['products'].get(sku,{}).get('manufacturer_id'),'MANUFACTURER')
    def part_owner(self,part):
        return self.company(self.byid['parts'].get(part,{}).get('supplier_id'),'SUPPLIER')
    def batch_owner(self,batch):
        row=self.byid['supplier_batches'].get(batch)
        return self.owner('supplier_batches',row) if row else None
    def manufacture_owner(self,mb=None,fg=None):
        row=self.byid['production'].get(mb) if mb else self.fg.get(fg)
        if mb and fg and (not row or row.get('finished_batch_id')!=fg):return None
        return self.owner('production',row) if row else None
    def sales_owner(self,line):
        row=self.byid['sales'].get(line)
        return self.owner('sales',row) if row else None
    def line_customer(self,line):
        row=self.byid['sales'].get(line,{})
        return self.company(row.get('customer_id'),'CUSTOMER')
    def shipment_owner(self,shipment):
        return self.company(self.byid['shipments'].get(shipment,{}).get('carrier_id'),'CARRIER')
    def service_customer(self,row):
        direct=self.company(row.get('customer_id'),'CUSTOMER')
        if row.get('customer_id') and not direct:return None
        line=row.get('delivery_line_id')
        delivery=self.byid['deliveries'].get(line,{})
        inferred=self.line_customer(delivery.get('order_line_id'))
        if not inferred and row.get('serial_id'):
            serial=self.byid['serials'].get(row['serial_id'],{})
            delivery=self.byid['deliveries'].get(serial.get('delivery_line_id'),{})
            inferred=self.line_customer(delivery.get('order_line_id'))
        return self.same([direct,inferred])
    def owner(self,table,row):
        if not row:return None
        if table=='organizations':
            return self.company(row.get('company_id'))
        if table=='products':return self.product_owner(row.get('sku_id'))
        if table=='parts':return self.part_owner(row.get('part_id'))
        if table=='production':
            node=self.node_owner(row.get('line_node_id'),'MANUFACTURER');sku=self.product_owner(row.get('sku_id'))
            return node if node and node==sku else None
        if table=='purchases' or table.startswith('procurement_ledger_'):
            direct=self.company(row.get('manufacturer_id'),'MANUFACTURER');node=self.node_owner(row.get('destination_node_id'),'MANUFACTURER')
            original=self.byid['purchases'].get(row.get('purchase_line_id'),{})
            inferred=self.company(original.get('manufacturer_id'),'MANUFACTURER') or self.node_owner(original.get('destination_node_id'),'MANUFACTURER')
            contract=self.byid['contracts'].get(row.get('contract_id'),{})
            buyers=entity_ids(contract.get('buyer_ids_json'))|entity_ids(contract.get('buyer_id'))
            buyer=self.company(next(iter(buyers)),'MANUFACTURER') if len(buyers)==1 else None
            contract_node=self.node_owner(contract.get('delivery_node_id'),'MANUFACTURER') if len(buyers)==1 else None
            if any(row.get(k) and not v for k,v in [('manufacturer_id',direct),('destination_node_id',node)]):return None
            result=self.same([direct,node,inferred,buyer,contract_node])
            return result if result and (not buyers or result in buyers) else None
        if table=='sales':
            direct=self.company(row.get('manufacturer_id'),'MANUFACTURER');sku=self.product_owner(row.get('sku_id'))
            if row.get('manufacturer_id') and not direct:return None
            result=self.same([direct,sku]);contract=self.byid['contracts'].get(row.get('contract_id'),{})
            sellers=entity_ids(contract.get('seller_ids'))
            return result if result and (not sellers or result in sellers) else None
        if table=='supplier_batches':
            direct=self.company(row.get('supplier_id'),'SUPPLIER')
            part=self.part_owner(row.get('part_id'))
            purchase=self.byid['purchases'].get(row.get('purchase_line_id'),{})
            supplier=self.company(purchase.get('supplier_id'),'SUPPLIER')
            return self.same([direct,part,supplier]) if direct and part else None
        if table=='bom':return self.product_owner(row.get('sku_id'))
        if table=='consumption':return self.manufacture_owner(mb=row.get('manufacturing_batch_id'))
        if table in ['allocations','deliveries']:
            a=self.sales_owner(row.get('order_line_id'));b=self.manufacture_owner(fg=row.get('finished_batch_id'))
            return a if a and a==b else None
        if table=='serials':
            a=self.product_owner(row.get('sku_id'));b=self.manufacture_owner(mb=row.get('manufacturing_batch_id'),fg=row.get('finished_batch_id'))
            delivery=self.byid['deliveries'].get(row.get('delivery_line_id'),{})
            c=self.sales_owner(delivery.get('order_line_id')) if delivery else None
            return self.same([a,b,c]) if a and b else None
        if table=='pairings':
            serial=self.byid['serials'].get(row.get('set_serial_id'))
            a=self.owner('serials',serial);b=self.manufacture_owner(fg=row.get('finished_batch_id'));p=self.product_owner(row.get('sku_id'))
            delivery=self.byid['deliveries'].get(row.get('delivery_line_id'),{})
            d=self.sales_owner(delivery.get('order_line_id'))
            if not serial or serial.get('finished_batch_id')!=row.get('finished_batch_id') or serial.get('delivery_line_id')!=row.get('delivery_line_id'):return None
            return a if a and a==b==p==d else None
        if table=='supplier_work_orders':
            direct=self.company(row.get('supplier_id'),'SUPPLIER');batch=self.batch_owner(row.get('supplier_batch_id'));node=self.node_owner(row.get('node_id'),'SUPPLIER')
            return self.same([direct,batch,node]) if direct and batch else None
        if table=='supplier_operations':
            a=self.owner('supplier_work_orders',self.byid['supplier_work_orders'].get(row.get('work_order_id')))
            b=self.batch_owner(row.get('supplier_batch_id'));return a if a and a==b else None
        if table=='assembly_operations':
            a=self.manufacture_owner(mb=row.get('manufacturing_batch_id'));b=self.product_owner(row.get('sku_id'))
            return a if a and a==b else None
        if table=='sales_fulfilment':
            ids=[row['order_line_id']] if row.get('order_line_id') else []
            if not ids:
                try:ids=json.loads(row.get('order_line_ids_json') or '[]')
                except ValueError:return None
            return self.same([self.sales_owner(x) for x in ids]) if ids and all(self.sales_owner(x) for x in ids) else None
        if table in ['warehouse_movements','packaging'] or table.startswith('stock_ledger_'):
            owner=self.node_owner(row.get('node_id'))
            if table=='packaging':
                producer=self.company(row.get('producer_id'))
                if not owner or owner!=producer:return None
            return owner
        if table=='shipments' or table.startswith('carriage_ledger_'):
            direct=self.company(row.get('carrier_id'),'CARRIER');ref=self.shipment_owner(row.get('shipment_id'))
            return direct if direct and direct==ref else None
        if table=='shipment_lines':
            direct=self.company(row.get('carrier_id'),'CARRIER');ref=self.shipment_owner(row.get('shipment_id'))
            return direct if direct and direct==ref else None
        if table.startswith('customer_ledger_'):
            direct=self.company(row.get('customer_id'),'CUSTOMER');ref=self.line_customer(row.get('order_line_id'))
            return direct if direct and direct==ref else None
        if table.startswith('supply_ledger_ENT-'):
            expected=table[len('supply_ledger_'):];direct=self.company(row.get('supplier_id'),'SUPPLIER')
            return direct if direct==expected and direct==self.batch_owner(row.get('supplier_batch_id')) else None
        if table=='quality':
            kind=row.get('qc_type');obj=row.get('object_id')
            if kind in ['来料','来料复检']:
                batch=self.byid['supplier_batches'].get(obj,{})
                if row.get('qc_id') not in [batch.get('qc_id'),batch.get('retest_qc_id')]:return None
                return self.owner('purchases',self.byid['purchases'].get(batch.get('purchase_line_id'))) if batch else None
            if kind=='终检':
                batch=self.fg.get(obj,{})
                return self.owner('production',batch) if batch.get('qc_id')==row.get('qc_id') else None
            return None
        if table=='services':
            direct=self.company(row.get('service_company_id'),'SERVICE');node=self.node_owner(row.get('service_node_id'),'SERVICE')
            if row.get('service_node_id') and not node:return None
            return self.same([direct,node]) if direct else None
        # Contracts are resolved per full-party scope, not by unioning participants.
        return None
    def full_contract_owners(self,row):
        owners=set();buyers=entity_ids(row.get('buyer_id'))|entity_ids(row.get('buyer_ids_json'));sellers=entity_ids(row.get('seller_ids'))
        if len(buyers)==1:owners|=buyers
        if len(sellers)==1:owners|=sellers
        return owners & set(self.companies)
    def row_allowed(self,table,row,enterprise):
        return enterprise in self.full_contract_owners(row) if table=='contracts' else self.owner(table,row)==enterprise
    def record_owner(self,identity,context=None):
        if context in self.byid and identity in self.byid[context]:
            row=self.byid[context][identity]
            return self.full_contract_owners(row) if context=='contracts' else set(filter(None,[self.owner(context,row)]))
        found=[]
        for table,index in self.byid.items():
            if identity in index:
                result=self.full_contract_owners(index[identity]) if table=='contracts' else set(filter(None,[self.owner(table,index[identity])]))
                found.append(result)
        if identity in self.fg:found.append(set(filter(None,[self.manufacture_owner(fg=identity)])))
        if identity in self.nodes:found.append(set(filter(None,[self.node_owner(identity)])))
        if identity in self.companies:found.append({identity})
        if identity in self.extras:found.append(set(filter(None,[self.extra_owner(identity)])))
        return set.intersection(*found) if found else set()
    def extra_owner(self,identity,trail=None):
        if identity in self.extra_owner_cache:return self.extra_owner_cache[identity]
        trail=set(trail or [])
        if identity in trail:return None
        trail.add(identity);found=[]
        for collection,row in self.extras.get(identity,[]):
            owner=None
            if collection in ['stations','equipment','slots']:
                node=self.node_owner(row.get('node_id'));direct=self.company(row.get('owner_id'))
                owner=node if node and (not row.get('owner_id') or node==direct) else None
            elif collection=='vehicles':
                direct=self.company(row.get('owner_id'),'CARRIER');node=self.node_owner(row.get('depot_node_id'),'CARRIER')
                owner=direct if direct and (not row.get('depot_node_id') or node==direct) else None
            elif collection=='sensors':
                shipment=self.shipment_owner(row.get('shipment_id'));direct=self.company(row.get('owner_id'),'CARRIER')
                owner=shipment if shipment and (not row.get('owner_id') or shipment==direct) else None
            elif collection=='process_routes':owner=self.part_owner(row.get('part_id'))
            elif collection=='input_lots':owner=self.owner('supplier_work_orders',self.byid['supplier_work_orders'].get(row.get('work_order_id')))
            elif collection=='trace_links':
                a=self.manufacture_owner(mb=row.get('manufacturing_batch_id'));b=self.owner('serials',self.byid['serials'].get(row.get('serial_id')))
                owner=a if a and a==b else None
            elif collection in ['measurement_series','service_cases']:
                service_id=row.get('service_id') or row.get('object_id')
                service=self.byid['services'].get(service_id)
                owner=self.owner('services',service) if service else None
                if service and row.get('serial_id') and service.get('serial_id')!=row.get('serial_id'):owner=None
                if service and row.get('delivery_line_id') and service.get('delivery_line_id')!=row.get('delivery_line_id'):owner=None
            elif collection=='incoming_cases':
                batch=self.byid['supplier_batches'].get(row.get('supplier_batch_id'),{})
                owner=self.owner('purchases',self.byid['purchases'].get(batch.get('purchase_line_id')))
            elif collection=='packing_cases':
                a=self.owner('packaging',self.byid['packaging'].get(row.get('old_package_id')))
                b=self.owner('packaging',self.byid['packaging'].get(row.get('new_package_id')))
                owner=a if a and a==b else None
            elif collection=='appointment_cases':
                customers={self.company(x.get('customer_id'),'CUSTOMER') for x in self.order_lines.get(row.get('order_id'),[])}
                direct=self.company(row.get('customer_id'),'CUSTOMER')
                owner=direct if direct and customers=={direct} else None
            elif collection=='warehouse_transfers':
                # A shared handover is not a whole-record grant to either party.
                a=self.node_owner(row.get('origin_node_id'));b=self.node_owner(row.get('destination_node_id'))
                owner=a if a and a==b else None
            found.append(owner)
        result=found[0] if found and all(x and x==found[0] for x in found) else None
        self.extra_owner_cache[identity]=result
        return result
    def json_owner(self,path,row):
        if not isinstance(row,dict):return None
        name=Path(path).stem
        if row.get('message_id') or row.get('event_id'):
            source=row.get('source_table');record=row.get('source_record_id')
            if source and record and source in self.byid:
                original=self.byid[source].get(record)
                if original:
                    if name.startswith('carrier_') and row.get('shipment_id'):return self.shipment_owner(row['shipment_id'])
                    return self.owner(source,original)
            if row.get('shipment_id'):return self.shipment_owner(row['shipment_id'])
            if row.get('service_id'):return self.owner('services',self.byid['services'].get(row['service_id']))
            if row.get('supplier_batch_id'):
                batch=self.byid['supplier_batches'].get(row['supplier_batch_id'],{})
                if 'quality_' in name:return self.owner('purchases',self.byid['purchases'].get(batch.get('purchase_line_id')))
                return self.batch_owner(row['supplier_batch_id'])
            for field in ['entity_id','reference_id']:
                if row.get(field):
                    owners=self.record_owner(row[field])
                    if len(owners)==1:return next(iter(owners))
        if row.get('relation_id'):
            source=row.get('source_id');target=row.get('target_id')
            # A BOM relationship is a manufacturer's internal engineering record.
            if row.get('bom_id') and source in self.sku_ids:return self.product_owner(source)
            owners=self.record_owner(source)&self.record_owner(target)
            return next(iter(owners)) if len(owners)==1 else None
        for identity in self.extras:
            # Only exact primary values select a resource; incidental links do not.
            if any(row.get(key)==identity for key in ['station_id','equipment_id','process_id','input_lot_id','slot_id','vehicle_id','link_id','sensor_id','series_id','case_id','handover_id']):
                return self.extra_owner(identity)
        for table,key in [('pairings','pair_id'),('services','service_id'),('supplier_operations','operation_id'),('assembly_operations','operation_id'),('supplier_work_orders','work_order_id'),('supplier_batches','supplier_batch_id'),('shipment_lines','waybill_line_id'),('shipments','shipment_id'),('sales','order_line_id'),('purchases','purchase_line_id'),('production','manufacturing_batch_id'),('production','finished_batch_id'),('quality','qc_id'),('bom','bom_line_id'),('parts','part_id')]:
            if row.get(key):
                if key=='finished_batch_id':return self.manufacture_owner(fg=row[key])
                original=self.byid[table].get(row[key])
                if original:return self.owner(table,original)
        if row.get('sku_id') and (not row.get('part_id')):return self.product_owner(row['sku_id'])
        return None
