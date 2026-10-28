"""Strict snapshot capabilities and pre-ranking, record-local hard filtering."""
from __future__ import annotations

import copy
import json
import math
from collections import defaultdict

from fastapi import HTTPException
from schemas.numeric_operand import operand_decimal


def _fail(message):
    raise HTTPException(status_code=422, detail=message)


def _numeric_field(field):
    return field['type']=='number' or (field['type']=='mixed' and 'number' in field.get('sourceTypes',[]))


def _check_type(condition, field):
    if condition.op not in field["operators"]:
        _fail(f"字段 {condition.field} 不支持 {condition.op}")
    if condition.value_encoding and not _numeric_field(field):_fail(f"字段 {condition.field} 没有数值类型")
    operands=condition.value if condition.op in {'in','between'} else [condition.value]
    numeric_values=[isinstance(value,(int,float)) and not isinstance(value,bool) for value in operands]
    numeric_condition=field['type']=='number' or bool(condition.value_encoding) or (field['type']=='mixed' and any(numeric_values))
    if field['type']=='mixed' and field.get('unitField') and condition.op=='in' and not condition.value_encoding and any(numeric_values) and not all(numeric_values):
        _fail('单位随记录变化时，集合条件须选择同一值类型；数值和文字条件需分别明确')
    if field['type']=='mixed' and condition.unit and not numeric_condition:_fail('非数值条件不使用计量单位')
    if field.get('unitField') and condition.op not in {'is_null','not_null'} and numeric_condition:
        if not condition.unit:
            _fail(f"字段 {condition.field} 的单位随记录变化，请明确条件单位")
        from services.professional_retrievers import _convert
        compatible=False
        for unit in field.get('unitOptions',[]):
            try:
                _convert(1,unit,condition.unit);compatible=True
            except (TypeError,ValueError):
                pass
        if not compatible:
            _fail(f"字段 {condition.field} 的条件单位与当前记录单位不兼容")
    elif condition.unit and condition.unit != field.get("unit"):
        _fail(f"字段 {condition.field} 的单位为 {field.get('unit') or '未知'}，请求单位不匹配")
    if condition.op in {"is_null", "not_null"}:
        if condition.value is not None:
            _fail("空值条件的 value 必须为 null")
        return
    values = condition.value if condition.op in {"in", "between"} else [condition.value]
    for value in values:
        kind = field["type"]
        if kind=='number' or condition.value_encoding or (kind=='mixed' and condition.op in {'gt','gte','lt','lte','between'}):
            try:numeric=operand_decimal(value,condition.value_encoding)
            except ValueError:_fail(f"字段 {condition.field} 的数值超出支持的表达式或精度范围")
            if numeric is None:_fail(f"字段 {condition.field} 需要数值")
        if kind=='mixed' and not condition.value_encoding:
            actual='boolean' if isinstance(value,bool) else 'number' if isinstance(value,(int,float)) else 'string' if isinstance(value,str) else 'array' if isinstance(value,list) else 'object' if isinstance(value,dict) else 'null'
            if actual not in field.get('sourceTypes',[]):_fail(f"字段 {condition.field} 没有 {actual} 类型")
        if kind == "boolean" and not isinstance(value, bool):
            _fail(f"字段 {condition.field} 需要布尔值")
        if kind in {"string", "date", "datetime", "timestamp"} and not isinstance(value, str):
            _fail(f"字段 {condition.field} 需要文字或对应日期")
        if kind in {"date","datetime","timestamp"}:
            from datetime import datetime
            try:
                parsed=datetime.fromisoformat(value.replace('Z','+00:00'))
            except ValueError:
                _fail(f"字段 {condition.field} 需要有效ISO日期/时间")
            if kind=='date' and len(value)!=10:
                _fail(f"字段 {condition.field} 是日期，不接受带时间的条件")
            if kind!='date' and parsed.tzinfo is None:
                _fail(f"字段 {condition.field} 的时间条件需要显式时区")
    if condition.op == "between":
        bounds=condition.value
        if condition.value_encoding:bounds=[operand_decimal(value,condition.value_encoding) for value in bounds]
        if field['type'] in {'date','datetime','timestamp'}:
            from datetime import datetime
            bounds=[datetime.fromisoformat(value.replace('Z','+00:00')) for value in bounds]
        if bounds[0]>bounds[1]:
            _fail(f"字段 {condition.field} 的区间下界超过上界")


class SnapshotScope:
    def __init__(self, facets):
        self.facets = facets
        self.assets = {item["assetId"]: item for item in facets.get("assets", [])}
        self.assets_by_path={item['logicalPath'].replace('\\','/').lstrip('/'):item for item in self.assets.values()}
        self.entities = {item["entity_id"]: item for item in facets.get("entities", [])}
        self.primary_keys={entity.get('primary_key') for entity in self.entities.values()}
        self.sku_links=self._record_sku_links()
        self.rejected_sku_bindings=0
        for identifier,asset in list(self.assets.items()):
            bindings=[];seen=set()
            for binding in asset.get('bindings',[]):
                binding=dict(binding)
                if binding.get('sku_id') and not any(binding['sku_id'] in self.sku_links.get(entity,set()) for entity in binding.get('entity_ids',[])):
                    self.rejected_sku_bindings+=1
                    binding.pop('sku_id',None);binding.pop('category_id',None)
                key=json.dumps(binding,sort_keys=True,ensure_ascii=False)
                if key not in seen:bindings.append(binding);seen.add(key)
            self.assets[identifier]={**asset,'bindings':bindings}
        self.assets_by_path={item['logicalPath'].replace('\\','/').lstrip('/'):item for item in self.assets.values()}
        self.tables = {}
        for item in facets.get('tables',[]):
            by_name={field['name']:field for field in item['fields']}
            fields=[]
            for raw in item['fields']:
                field=dict(raw)
                # Dictionary phrases such as “不适用” describe unit semantics;
                # they are never conversion targets for identifiers or dates.
                if not _numeric_field(field):
                    field.update(sourceUnitMetadata=field.get('unit'),unit=None)
                elif field.get('unit') in {'不适用','无量纲','无','-','1'}:
                    field.update(sourceUnitMetadata=field['unit'],unit=None)
                elif isinstance(field.get('unit'),str) and field['unit'].startswith('见对应'):
                    field.update(sourceUnitRule=field['unit'],unit=None,unitRuleUnresolved=True,aggregations=[])
                    field['operators']=[op for op in field['operators'] if op in {'eq','in','is_null','not_null'}]
                if isinstance(field.get('unit'),str) and field['unit'].startswith('由记录'):
                    if not _numeric_field(field):
                        field.update(sourceUnitRule=field['unit'],unit=None)
                        fields.append(field)
                        continue
                    reference=next((name for name in [field['name']+'_unit'] if name in by_name),None)
                    if not reference and field['name'].endswith('_count'):
                        field.update(sourceUnitRule=field['unit'],unit='count',unitSemantic='按该字段所定义对象计数')
                        fields.append(field)
                        continue
                    if not reference and 'item' in by_name and field['name'] in {'actual_value','lower_limit','upper_limit','min_value','max_value'}:
                        reference='unit' if 'unit' in by_name else None
                    if not reference and ('quantity' in field['name'] or 'qty' in field['name']):
                        reference=next((name for name in ['quantity_unit','unit'] if name in by_name),None)
                    field.update(sourceUnitRule=field['unit'],unit=None,unitField=reference,
                        unitOptions=[v for v in by_name.get(reference,{}).get('enum',[]) if isinstance(v,str)])
                    if reference=='unit' and 'item' in by_name:
                        field['measurementField']='item'
                    if not reference:
                        field.update(unitRuleUnresolved=True,aggregations=[])
                        field['operators']=[op for op in field['operators'] if op in {'eq','in','is_null','not_null'}]
                fields.append(field)
            self.tables[item['tableId']]={**item,'fields':fields}
        self.tables_by_path=defaultdict(list)
        for table in self.tables.values():
            if table.get('logicalPath'):self.tables_by_path[table['logicalPath'].lstrip('/')].append(table)
        self.entity_tokens={identifier.casefold():identifier for identifier in self.entities}
        self.records=defaultdict(list)
        for asset in self.assets.values():
            for raw in asset.get('recordMetadata',[]):
                record=self._verified_record(raw)
                if record:self.records[(asset['assetId'],record.get('locator'))].append(record)
        canonical_dates={record['record_id']:record for records in self.records.values() for record in records}
        for asset in self.assets.values():
            for binding in asset.get('bindings',[]):
                key=(asset['assetId'],binding.get('locator'))
                existing={record['record_id'] for record in self.records[key]}
                for identifier in binding.get('entity_ids',[]):
                    entity=self.entities.get(identifier,{})
                    path=str(entity.get('path','')).lstrip('/')
                    if identifier in existing or path not in self.assets_by_path:continue
                    # A bound QC/batch may have no per-image date summary. Its
                    # verified primary record still supplies an associated
                    # business event, never a photograph capture timestamp.
                    raw={**canonical_dates.get(identifier,{}),'record_id':identifier,'locator':binding.get('locator'),
                        'event_time_source':path,'business_stage':canonical_dates.get(identifier,{}).get('business_stage') or entity.get('entity_type'),
                        'data_producer_ids':[],'data_provider_ids':[]}
                    record=self._verified_record(raw)
                    if record and (record['verifiedEventValues'] or record['verifiedContextValues']):self.records[key].append(record);existing.add(identifier)
        self.object_fields = self._object_fields()

    @staticmethod
    def _same_date(actual,declared):
        if actual==declared:return True
        from datetime import datetime
        try:return datetime.fromisoformat(actual.replace('Z','+00:00'))==datetime.fromisoformat(declared.replace('Z','+00:00'))
        except (ValueError,TypeError,AttributeError):return False

    def _verified_record(self,record):
        entity=self.entities.get(record.get('record_id'),{})
        if not entity or str(entity.get('path','')).replace('\\','/').lstrip('/')!=str(record.get('event_time_source','')).replace('\\','/').lstrip('/'):
            return None
        values=entity.get('values',{});events={};contexts={}
        context_names={'valid_from','valid_to','created_at','updated_at','modified_at','imported_at','due_at','planned_at','promised_at'}
        declared=[record.get('event_time_start'),record.get('event_time_end')]
        for field in record.get('event_time_fields',[]):
            actual=values.get(field)
            if field not in values or field in context_names or not any(self._same_date(actual,value) for value in declared):continue
            if actual is not None:
                from datetime import datetime
                try:datetime.fromisoformat(str(actual).replace('Z','+00:00'))
                except ValueError:continue
            events['event.'+field]=actual
        for field,value in record.get('context_times',{}).items():
            if field in values and self._same_date(values[field],value):contexts['context.'+field]=values[field]
        # Catalog date summaries may omit actual event columns (for example a
        # quality record's measured_at). Recognize explicit event semantics only
        # when the immutable primary table also declares a real date/time field.
        event_names={'ordered_at','occurred_at','measured_at','tested_at','inspected_at','assembled_at',
            'started_at','completed_at','produced_at','manufactured_at','released_at','consumed_at',
            'received_at','shipped_at','ship_at','arrived_at','arrival_at','departed_at','loaded_at',
            'signed_at','delivered_at','closed_at','serviced_at'}
        source_fields=self._fields_at(entity.get('path',''),entity.get('sourceLocator') or entity.get('location') or {})
        for name in context_names & set(values) & set(source_fields):
            if source_fields[name]['type'] not in {'date','datetime','timestamp'}:continue
            actual=values[name]
            if actual is not None:
                from datetime import datetime
                try:datetime.fromisoformat(str(actual).replace('Z','+00:00'))
                except ValueError:continue
            contexts['context.'+name]=actual
        for name in event_names & set(values) & set(source_fields):
            if source_fields[name]['type'] not in {'date','datetime','timestamp'}:continue
            actual=values[name]
            if actual is not None:
                from datetime import datetime
                try:datetime.fromisoformat(str(actual).replace('Z','+00:00'))
                except ValueError:continue
            events['event.'+name]=actual;contexts.pop('context.'+name,None)
        return {**record,'verifiedEventValues':events,'verifiedContextValues':contexts,'verifiedSourceLocator':entity.get('sourceLocator') or entity.get('location',{})}

    def _record_sku_links(self):
        """Use actual row keys and BOM membership, never catalog sku_ids.

        A purchase's contract may cover many unrelated products. A line's part
        or batch takes precedence over that broad document relationship.
        Supplier/company/node links cannot establish product applicability.
        """
        def identifiers(value):
            if isinstance(value,list):return {str(child) for child in value if isinstance(child,(str,int))}
            return {str(value)} if value is not None and value!='' else set()
        known=set(self.entities)
        products={key for key,entry in self.entities.items() if entry.get('primary_key')=='sku_id' and str(entry.get('values',{}).get('sku_id'))==key}
        links={key:set() for key in self.entities};part_usage=defaultdict(set)
        for key,entry in self.entities.items():
            values=entry.get('values',{})
            direct=set().union(*(identifiers(value) for name,value in values.items() if name in {'sku_id','sku_ids_json'})) if any(name in values for name in {'sku_id','sku_ids_json'}) else set()
            links[key].update(direct&products)
            if values.get('bom_id') and values.get('part_id') and values.get('sku_id') in products:
                part_usage[str(values['part_id'])].add(values['sku_id'])
        references={}
        tiers=[('part_id','part_ids_json'),('bom_id','bom_version_id'),
            ('finished_batch_id','manufacturing_batch_id'),('assembly_work_order_id','work_order_id'),
            ('supplier_batch_id','supplier_batch_ids_json'),('purchase_line_id','order_line_id','sales_line_id'),
            ('serial_id',),('qc_id','inspection_id')]
        for key,entry in self.entities.items():
            values=entry.get('values',{})
            if entry.get('primary_key')=='part_id':links[key].update(part_usage[key])
            if links[key]:continue
            for tier in tiers:
                targets=set().union(*(identifiers(values.get(name)) for name in tier))-{key}
                targets&=known
                if targets:references[key]=targets;break
        for _ in range(len(self.entities)):
            changed=False
            for key,targets in references.items():
                combined=set().union(*(links[target] for target in targets))
                if not combined<=links[key]:links[key].update(combined);changed=True
            if not changed:break
        return links

    @classmethod
    def from_suite(cls, suite):
        path = suite._path("snapshot.facets", "facets.json")
        if not path:
            raise HTTPException(status_code=409, detail="当前索引缺少快照筛选能力，需要安全构建新索引；旧索引仍可使用兼容检索")
        return suite._cached("snapshot.scope", lambda: cls(json.loads(path.read_text(encoding="utf-8"))))

    def binding_values(self, binding, asset):
        result = {k: v for k, v in binding.items() if k in {"sku_id", "category_id"}}
        if result.get('sku_id') and not any(result['sku_id'] in self.sku_links.get(entity,set()) for entity in binding.get('entity_ids',[])):
            result.pop('sku_id',None);result.pop('category_id',None)
        result["entity_id"] = binding.get("entity_ids", [])
        for entity in binding.get("entity_ids", []):
            item = self.entities.get(entity, {})
            if item.get("primary_key"):
                key = item["primary_key"]
                if key not in result:
                    result[key] = []
                if isinstance(result[key], list):
                    result[key].append(entity)
            for name in ["revision", "model"]:
                if item.get("values", {}).get(name) is not None:
                    result[name] = item["values"][name]
        result["purpose"] = asset.get("purpose")
        records = [r for r in self.records.get((asset['assetId'],binding.get('locator')),[]) if r['record_id'] in binding.get('entity_ids',[]) or r['record_id']==binding.get('sku_id')]
        for output, source in [("data_producer_id","data_producer_ids"),("data_provider_id","data_provider_ids"),("business_stage","business_stage")]:
            values = []
            for record in records:
                value = record.get(source, [])
                values.extend(value if isinstance(value,list) else [value])
            if values:
                result[output] = sorted(set(values),key=str)
        dates=defaultdict(list)
        for record in records:
            for name,value in {**record['verifiedEventValues'],**record['verifiedContextValues']}.items():
                if value not in dates[name]:dates[name].append(value)
        result.update({name:values[0] if len(values)==1 else values for name,values in dates.items()})
        for name, key in [("pixel_width", "width"), ("pixel_height", "height")]:
            value = asset.get("attributes", {}).get(key)
            if isinstance(value, (int, float)):
                result[name] = value
        return result

    def _object_fields(self):
        fields = defaultdict(lambda: {"values": set(), "types": set(),"stages":set()})
        for asset in self.assets.values():
            for binding in asset.get("bindings", []) or [{}]:
                for name, value in self.binding_values(binding, asset).items():
                    temporal=name.startswith(('event.','context.'))
                    if temporal:
                        fields[name]['types'].update(asset.get('contentTypes',[]))
                        for record in self.records.get((asset['assetId'],binding.get('locator')),[]):
                            if name in record['verifiedEventValues'] or name in record['verifiedContextValues']:
                                stages=record.get('business_stage',[])
                                fields[name]['stages'].update(stages if isinstance(stages,list) else [stages] if stages else [])
                    for child in value if isinstance(value, list) else [value]:
                        if child is not None:
                            fields[name]["values"].add(child)
                            fields[name]["types"].update(asset.get("contentTypes", []))
        labels = {"sku_id": "SKU / 型号", "part_id": "料号", "category_id": "品类", "entity_id": "业务对象",
                  "purpose": "内容用途", "pixel_width": "图片像素宽", "pixel_height": "图片像素高",
                  "data_producer_id":"数据产生方","data_provider_id":"数据提供方","business_stage":"业务阶段"}
        purpose_labels = {"engineering_geometry":"三维工程模型", "expanded_table":"业务表", "product_image":"产品图",
            "product_photo":"产品照片", "engineering_drawing":"二维工程图", "product_spec":"产品规格资料",
            "customer_visit_notes":"客户回访纪要", "provider_field_note":"提供方业务纪要",
            'business_document':'业务文档','business_table':'业务表','scene_image':'业务场景图像','timeseries':'测量序列','expanded_pdf':'业务PDF文档'}
        entity_labels = {key: str(item.get("values",{}).get("model") or item.get("values",{}).get("name") or key)
                         for key,item in self.entities.items()}
        date_labels={'valid_from':'有效期开始（关联上下文）','valid_to':'有效期截止（关联上下文）','created_at':'记录创建时间（非实际事件）',
            'due_at':'约定期限（非实际事件）','ordered_at':'下单实际时间','measured_at':'检验测量时间','started_at':'实际开始时间',
            'ended_at':'实际结束时间','shipped_at':'实际发运时间','arrived_at':'实际到达时间','signed_at':'实际签收或签署时间'}
        return [{"name": name, "description": ('业务事件：' if name.startswith('event.') else '关联上下文：')+date_labels.get(name.split('.',1)[1],name.split('.',1)[1]) if name.startswith(('event.','context.')) else labels.get(name, name),
                 "type": ('date' if item['values'] and all(len(str(v))==10 for v in item['values']) else 'datetime') if name.startswith(('event.','context.')) else 'number' if name.startswith('pixel_') else 'string',
                 "enum": sorted(item["values"], key=str), "contentTypes": sorted(item["types"]),
                 **({'applicableStages':sorted(item['stages']),'timeRole':'actual_business_event' if name.startswith('event.') else 'context_only','notCaptureTime':True} if name.startswith(('event.','context.')) else {}),
                 "enumLabels": {str(value):(purpose_labels.get(value,value) if name=="purpose" else entity_labels.get(str(value),str(value))) for value in item["values"]},
                 "operators": ["eq", "in"] + (["gte", "lte", "between","is_null","not_null"] if name.startswith(('pixel_','event.','context.')) else [])}
                for name, item in sorted(fields.items())]

    def describe(self):
        labels = {"text": "文本与文档", "structured": "表格与业务记录", "visual": "图像及二维图纸",
                  "timeseries": "时序测量", "geometry": "三维模型"}
        types = []
        for kind, label in labels.items():
            assets = [a for a in self.assets.values() if kind in a.get("contentTypes", []) and a["scope"] == "corpus"]
            if kind == "text":
                assets = [a for a in assets if a["originalFormat"] not in {"OBJ", "STEP", "STP"}]
            types.append({"id": kind, "label": label, "formats": sorted({a["originalFormat"] for a in assets}),
                          "assetCount": len(assets)})
        field_labels={'sku_id':'SKU标识','model':'型号','category_id':'品类标识','width_mm':'宽度','height_mm':'高度','depth_mm':'深度',
            'sales_price_cny_tax_included':'含税售价（CNY）','quantity_unit':'数量单位','qc_id':'检验记录标识','qc_type':'检验类型',
            'item':'检验项目','result':'检验结果','unit':'记录单位','lower_limit':'允许下限','upper_limit':'允许上限',
            'actual_value':'实测值','sample_measurements_json':'单件测量记录','measured_at':'检验测量时间','object_id':'被检业务对象',
            'series_id':'测量序列','serial_id':'样机序列号','phase':'试验阶段','cold_center_c':'冷藏中心温度',
            'control_sensor_c':'控制传感器温度','power_w':'输入功率','revision':'工程版本','part_id':'料号',
            'order_date':'订单日期','signed_at':'合同签署时间','valid_from':'合同有效期开始','valid_to':'合同有效期结束'}
        tables=[{**table,'fields':[{**field,'sourceDescription':field.get('description'),
            'description':field_labels.get(field['name'],field.get('description') or field['name'])} for field in table['fields']]}
            for table in self.tables.values()]
        series=[{**item,'description':field_labels.get(item['metric'],item['metric'])} for item in self.facets.get('series',[])]
        assets=[{**asset,'verifiedBusinessStages':sorted({stage for binding in asset.get('bindings',[]) for stage in self.binding_values(binding,asset).get('business_stage',[])})} for asset in self.assets.values()]
        return {**self.facets, "assets":assets,"tables":tables,"series":series,"dataTypes": types, "objectFields": self.object_fields,
                'temporalAdaptationVersion':'verified-record-dates-3',
                'bindingValidationVersion':'verified-row-sku-links-1','rejectedCatalogSkuBindings':self.rejected_sku_bindings,
                "formatSemantics": "原始格式；文本/图像表示不改变来源格式", "enumScope": "当前快照实际出现值"}

    def validate(self, plan):
        if plan.content_type and plan.content_type != "all" and plan.retrievers != [plan.content_type]:
            _fail("数据类型与检索器必须一致")
        if any(asset not in self.assets for asset in plan.asset_ids):
            _fail("指定的文件不属于当前快照")
        formats = {a["originalFormat"] for a in self.assets.values()}
        if any(f not in formats for f in plan.formats):
            _fail("原始格式不属于当前快照")
        for condition in plan.object_conditions:
            if 'geometry' in plan.retrievers and condition.field.startswith(('event.','context.')):
                _fail('三维模型不支持业务时间筛选；版本日期仅作来源上下文')
            field = next((f for f in self.object_fields if f["name"] == condition.field), None)
            if not field or (plan.content_type not in {None, "all"} and plan.content_type not in field["contentTypes"]):
                _fail(f"当前数据类型没有可验证的绑定字段 {condition.field}")
            if field.get('applicableStages'):
                stages={value for c in plan.object_conditions if c.field=='business_stage' for value in (c.value if c.op=='in' else [c.value])}
                if not stages or not stages<=set(field['applicableStages']):_fail('关联时间条件必须先明确适用的文档/图像业务类型；有效期不属于实际事件或拍摄时间')
            _check_type(condition, field)
        if "structured" in plan.retrievers:
            table = self.tables.get(plan.table_id)
            if not table:
                _fail("表格检索及计算必须先选择当前快照的业务表/工作表")
            fields = {field["name"]: field for field in table["fields"]}
            if table["scope"] != plan.corpus_scope:
                _fail("业务表与目录范围不匹配")
            for condition in plan.hard_conditions:
                if condition.field not in fields:
                    _fail(f"所选业务表没有字段 {condition.field}")
                _check_type(condition, fields[condition.field])
            for name in [plan.aggregate_field, plan.sort_field]:
                if name and name not in fields:
                    _fail(f"所选业务表没有字段 {name}")
            if plan.operation in {"sum", "min", "max", "average"}:
                field = fields.get(plan.aggregate_field)
                if not field or plan.operation not in field["aggregations"]:
                    _fail("统计必须选择该业务表中的数值字段")
                if plan.unit and not field.get('unitField') and plan.unit != field.get("unit"):
                    _fail("统计字段单位与请求不一致")
                if field.get('unitField') and plan.unit not in field.get('unitOptions',[]):
                    _fail("请从所选统计字段的实际记录单位中选择输出单位")
        if "timeseries" in plan.retrievers:
            count_table = self.tables.get(plan.table_id) if plan.operation == 'count' and plan.table_id else None
            if plan.operation == 'count' and plan.table_id:
                if not count_table or not self.allowed_asset(count_table['assetId'],plan):
                    _fail('时序计数工作表不属于所选授权来源或范围')
                names={field['name'] for field in count_table['fields']}
                from services.interval_statistics import TIME_NAMES
                if len([name for name in names if name.lower() in TIME_NAMES])!=1 or (plan.metric and plan.metric not in names):
                    _fail('时序计数工作表没有唯一时间字段或所选指标')
            if plan.hard_conditions and plan.operation == "similar_shape":
                _fail("曲线相似暂不支持采样行字段条件；请选择完整序列，或改用精确计算/浏览操作")
            entries = [s for s in self.facets.get("series", []) if
                       (not plan.asset_ids or s["assetId"] in plan.asset_ids) and
                       (not plan.target_entity or s["seriesId"] == plan.target_entity) and
                       (not plan.metric or s["metric"] == plan.metric)]
            if plan.operation not in {"search", "similar_shape"} and not plan.target_entity:
                _fail("精确时序计算必须明确选择序列")
            if not entries and not (plan.operation=='count' and plan.count_mode=='records' and count_table):
                _fail("当前快照中不存在所选序列/指标/文件组合")
            if plan.unit and any(s.get("unit") != plan.unit for s in entries):
                _fail("时序指标单位不匹配")
            tables = [count_table] if count_table else [table for table in self.tables.values() if any(s["assetId"]==table["assetId"] for s in entries)]
            for condition in plan.hard_conditions:
                fields = [field for table in tables for field in table["fields"] if field["name"]==condition.field]
                if not fields:
                    _fail(f"当前序列没有字段 {condition.field}")
                _check_type(condition, fields[0])
        if "geometry" in plan.retrievers:
            from services.professional_retrievers import _nested, _geometry_unit
            units = [g for g in self.facets.get("geometry", []) if self.allowed_asset(g["assetId"], plan)]
            for condition in plan.hard_conditions:
                if condition.field == "volume" and any("MESH_SELF_INTERSECTION_NOT_CERTIFIED" in g.get("qualityFlags",[]) for g in units):
                    _fail("当前网格尚未验证自相交性质，不能用其体积满足硬条件；请选择已核验的CAD实体")
                examples = [(_nested(g["attributes"], condition.field), _geometry_unit(g.get("units", {}), condition.field)) for g in units]
                examples = [(value,unit) for value,unit in examples if value is not None]
                if not examples:
                    _fail(f"当前模型没有已提取的可验证属性 {condition.field}")
                if any(not isinstance(value,(int,float)) or isinstance(value,bool) for value,_ in examples):
                    _fail(f"模型属性 {condition.field} 不支持数值条件")
                if condition.unit and any(unit!=condition.unit for _,unit in examples):
                    _fail(f"模型属性 {condition.field} 单位未知或不匹配")
                if any(not unit for _,unit in examples):
                    _fail(f"模型属性 {condition.field} 尚无可验证单位")
                _check_type(condition,{"operators":["eq","gt","gte","lt","lte","between","is_null","not_null"],
                    "type":"number","unit":condition.unit})

    def allowed_asset(self, asset_id, plan, kind=None):
        asset = self.assets.get(str(asset_id))
        return bool(asset and asset["scope"] == plan.corpus_scope
            and (not plan.asset_ids or asset_id in plan.asset_ids)
            and (not plan.formats or asset["originalFormat"] in plan.formats)
            and (not kind or kind in asset.get("contentTypes", [])))

    def allowed_unit(self, unit, plan, row_values=None, row_number=None):
        from services.professional_retrievers import _matches, _has_identifier
        asset_id = unit.get("asset_id") or unit.get("assetId")
        if not self.allowed_asset(asset_id, plan):
            return False
        if not plan.object_conditions:
            return True
        asset = self.assets[asset_id]
        bindings = unit.get("metadata", {}).get("bindings", [])
        if isinstance(unit.get('payload'),str):
            bindings=[b for b in bindings if any(_has_identifier(unit['payload'],entity) for entity in b.get('entity_ids',[]))
                      or (b.get('sku_id') and _has_identifier(unit['payload'],b['sku_id']))]
        if row_number is not None:
            bindings = [b for b in asset.get("bindings", []) if self._binding_at_row(b,asset,unit.get('locator',{}),row_number)]
        for original_binding in bindings or [{}]:
            binding=self._narrow_binding(original_binding,plan)
            values = self.binding_values(binding, asset)
            if row_values:
                values.update({k: v for k, v in row_values.items() if k in values or k in {"sku_id", "part_id"}})
            regular=[condition for condition in plan.object_conditions if not condition.field.startswith(('event.','context.'))]
            temporal=[condition for condition in plan.object_conditions if condition.field.startswith(('event.','context.'))]
            if all(any(_matches(v, c) for v in values.get(c.field, [])) if isinstance(values.get(c.field), list)
                   else _matches(values.get(c.field), c) for c in regular) and (not temporal or self._temporal_records(binding,asset,plan)):
                return True
        return False

    def _narrow_binding(self,binding,plan):
        identifiers=list(binding.get('entity_ids',[]))
        for condition in plan.object_conditions:
            if condition.op not in {'eq','in'}:continue
            same_type={identifier for identifier in identifiers if self.entities.get(identifier,{}).get('primary_key')==condition.field}
            if same_type:
                selected=set(condition.value if condition.op=='in' else [condition.value])
                identifiers=[identifier for identifier in identifiers if identifier not in same_type or identifier in selected]
        return {**binding,'entity_ids':identifiers}

    def _temporal_records(self,binding,asset,plan):
        """All date predicates must belong to one actual record, with scalar
        reference paths to any requested IDs. Sibling orders cannot borrow each
        other's dates merely because a summary or contract contains them both.
        """
        from services.professional_retrievers import _matches
        identifiers=set(binding.get('entity_ids',[]))|{binding.get('sku_id')}
        identifiers.discard(None)
        edges={identifier:{value for name,value in self.entities.get(identifier,{}).get('values',{}).items()
            if name.endswith('_id') and isinstance(value,str) and value in identifiers and value!=identifier} for identifier in identifiers}
        def reaches(start,target):
            pending=[start];seen=set()
            while pending:
                value=pending.pop()
                if value==target:return True
                if value not in seen:seen.add(value);pending.extend(edges.get(value,set())-seen)
            return False
        anchors=[condition for condition in plan.object_conditions if condition.field in self.primary_keys|{'entity_id'} and condition.op in {'eq','in'}]
        stages=[condition for condition in plan.object_conditions if condition.field=='business_stage']
        temporal=[condition for condition in plan.object_conditions if condition.field.startswith(('event.','context.'))]
        result=[]
        for record in self.records.get((asset['assetId'],binding.get('locator')),[]):
            identifier=record['record_id']
            if identifier not in identifiers:continue
            stage=record.get('business_stage',[]);stage=stage if isinstance(stage,list) else [stage]
            if not all(any(_matches(value,condition) for value in stage) for condition in stages):continue
            def related(condition):
                values=condition.value if condition.op=='in' else [condition.value]
                if condition.field=='sku_id':return any(value in self.sku_links.get(identifier,set()) for value in values)
                own_type=self.entities.get(identifier,{}).get('primary_key')
                return any((identifier==value if own_type and self.entities.get(value,{}).get('primary_key')==own_type else
                    reaches(identifier,value) or reaches(value,identifier)) for value in values)
            if not all(related(condition) for condition in anchors):continue
            values={**record['verifiedEventValues'],**record['verifiedContextValues']}
            if all(condition.field in values and _matches(values[condition.field],condition) for condition in temporal):result.append(identifier)
        return result

    def visual_bindings(self, asset_id, locator):
        import re
        asset = self.assets.get(asset_id, {})
        bindings = asset.get("bindings", [])
        if asset.get("originalFormat") in {"PNG","JPG","WEBP","SVG"}:
            return bindings
        page = locator.get("page")
        return [b for b in bindings if page is not None and (
            (b.get("location") or {}).get("page") == page or
            str(page) in re.findall(r"page\s+(\d+)", b.get("locator", ""), re.I))]

    def matches_qa_entities(self,unit,requested):
        """Co-occurrence in a multi-record passage is insufficient for a joint
        identifier question. Use one declared binding and actual scalar keys.
        """
        bindings=unit.get('metadata',{}).get('bindings',[])
        if not bindings:return len(set(requested))==1
        for binding in bindings:
            ids=set(binding.get('entity_ids',[]));valid=True
            for target in requested:
                kind=self.entities[target].get('primary_key')
                owners={value for value in ids if self.entities[value].get('primary_key')==kind}
                if owners and target not in owners:valid=False;break
                if owners:ids-=owners-{target}
            if not valid:continue
            supported=set(ids)
            for identifier in ids:
                supported.update(value for name,value in self.entities[identifier].get('values',{}).items()
                    if name.endswith('_id') and isinstance(value,str) and value in self.entities)
                supported.update(self.sku_links.get(identifier,set()))
            if not set(requested)<=supported:continue
            # A QC's object_id or a line's named foreign key cannot point to a
            # different object of the requested type, even if a later paragraph
            # happens to mention a replacement record.
            chosen=defaultdict(set)
            for identifier in ids:chosen[self.entities[identifier].get('primary_key')].add(identifier)
            for identifier in ids:
                values=self.entities[identifier].get('values',{})
                for key,targets in chosen.items():
                    if len(targets)!=1 or key==self.entities[identifier].get('primary_key'):continue
                    explicit=values.get(key)
                    object_id=values.get('object_id')
                    if explicit in self.entities and explicit not in targets:valid=False
                    if object_id in self.entities and self.entities[object_id].get('primary_key')==key and object_id not in targets:valid=False
            if valid:return True
        return False

    def is_product_picture(self,asset_id,locator,entities):
        """Use declared visual purpose and direct identity, never a filename."""
        import re
        asset=self.assets.get(asset_id,{})
        if not re.search(r'产品图|产品照片|产品外观|product_(?:detail_)?photo',str(asset.get('purpose','')),re.I):return False
        targets={identifier for identifier in entities if self.entities.get(identifier,{}).get('entity_type')=='products'}
        return any((binding.get('location') or {}).get('source_entity_id') in targets or
            bool(targets.intersection(binding.get('entity_ids',[]))) for binding in self.visual_bindings(asset_id,locator))

    def temporal_role_evidence(self,named_files,entities,question,version,index_version,formats=()):
        """Exact date-role projection from verified primary rows, not PDF text.

        The primary row's actual original format is retained. A document is a
        requested target/identity attachment, never a new format of date facts.
        """
        from services.professional_retrievers import _evidence
        targets=set(entities)
        for identities in named_files.values():targets.update(identities)
        records={}
        for key in sorted(self.records,key=lambda key:str(key)):
            for record in self.records[key]:
                if record['record_id'] in targets:records.setdefault(record['record_id'],record)
        evidence=[]
        for identifier in sorted(targets):
            record=records.get(identifier);entry=self.entities.get(identifier,{})
            source=self.assets_by_path.get(entry.get('path','').lstrip('/'))
            if not record or not source or source.get('scope')!='corpus':continue
            if formats and source['originalFormat'] not in formats:continue
            documents=[self.assets[asset_id] for asset_id,identities in named_files.items() if identifier in identities]
            annotation=dict(recordId=identifier,eventValues=record['verifiedEventValues'],contextValues=record['verifiedContextValues'],
                eventCapability='VERIFIED' if record['verifiedEventValues'] else 'NONE',sourcePath=entry['path'],notCaptureTime=True)
            facts=dict(recordId=identifier,eventValues=record['verifiedEventValues'],contextValues=record['verifiedContextValues'],
                eventCapability=annotation['eventCapability'],documentTargets=[asset['logicalPath'] for asset in documents])
            item=_evidence(version=version,index_version=index_version,space='snapshot.temporal.roles',asset_id=source['assetId'],
                locator=record['verifiedSourceLocator'],facts={'text':json.dumps(facts,ensure_ascii=False)},score=None,
                score_type='verified_temporal_role',entity=identifier,preview={'logicalPath':source['logicalPath']},evidence_type='constraint')
            item.provenance.update(originalFormat=source['originalFormat'],scope='corpus',explicitEntities=[identifier],
                temporalMetadata=[annotation],temporalRoleVersion='verified-temporal-roles-1',
                validatedMetadataQuestion=question,verifiedPrimaryRow=bool(record['verifiedSourceLocator']),
                explicitDocumentTargets=[asset['logicalPath'] for asset in documents],
                verificationSources=[dict(assetId=asset['assetId'],logicalPath=asset['logicalPath'],role='document_identity') for asset in documents])
            evidence.append(item)
        return evidence

    def scoped_retriever(self, retriever, kind, plan):
        scoped = copy.copy(retriever)
        if kind == "structured":
            scoped.rows = [row for row in retriever.rows if (not plan.table_id or row["unitId"] == plan.table_id)
                and self.allowed_unit(row, plan, row["values"], row["locator"].get("rawRow"))]
            scoped.field_index = None  # Original posting offsets must never address a scoped list.
            resolved=[]
            for row in scoped.rows:
                table=self.tables.get(row['unitId'],{})
                row_units={}
                for field in table.get('fields',[]):
                    value=row['values'].get(field['name'])
                    if not isinstance(value,(int,float)) or isinstance(value,bool):
                        continue
                    if field.get('unitField'):
                        row_units[field['name']]=row['values'].get(field['unitField']) or None
                    elif field.get('unit'):
                        row_units[field['name']]=field['unit']
                resolved.append({**row,'units':row_units})
            scoped.rows=resolved
            table=self.tables.get(plan.table_id,{})
            aggregate=next((field for field in table.get('fields',[]) if field['name']==plan.aggregate_field),{})
            scoped.aggregate_measurement_field=aggregate.get('measurementField')
            scoped.aggregate_unit_unresolved=bool(aggregate.get('unitRuleUnresolved'))
            sort=next((field for field in table.get('fields',[]) if field['name']==plan.sort_field),{})
            scoped.sort_dynamic_unit=bool(sort.get('unitField'))
            scoped.sort_measurement_field=sort.get('measurementField')
        elif kind == "geometry":
            scoped.units = [unit for unit in retriever.units if self.allowed_unit(unit, plan)]
            scoped.query_units = retriever.units
            scoped.allowed_geometry = lambda unit: self.allowed_unit(unit, plan)
        elif kind == "timeseries":
            scoped.facts = [row for row in retriever.facts if self.allowed_unit(row, plan,
                row.get("recordValues"), row.get("rawRow"))]
            # Feature index offsets refer to the original list. Keep the full list
            # for approximate search and apply metadata checks before truncation.
            from services.professional_retrievers import _iso,_deduplicate
            groups=defaultdict(list)
            for row in retriever.facts:
                groups[(row['assetId'],row['entity'],row['metric'])].append(row)
            allowed={(row['assetId'],row['entity'],row['metric'],row['normalizedTime'],row.get('rawRow')) for row in scoped.facts}
            def segment_allowed(segment):
                key=(segment['assetId'],segment['entity'],segment['metric'])
                rows=[row for row in groups[key] if _iso(segment['timeStart'])<=_iso(row['normalizedTime'])<=_iso(segment['timeEnd'])]
                return bool(rows and all((*key,row['normalizedTime'],row.get('rawRow')) in allowed for row in rows)
                    and not any('DUPLICATE_SAMPLE_CONFLICT' in row.get('qualityFlags',[]) for row in _deduplicate(rows)))
            scoped.allowed_segment = segment_allowed
        elif kind == "text":
            scoped.allowed_document = lambda unit: self.allowed_unit(unit, plan)
        return scoped

    def _tables_at(self,path,locator):
        """A filename or a physical row is not a workbook/JSON table identity."""
        tables=self.tables_by_path[str(path).replace('\\','/').lstrip('/')]
        candidates=[]
        for table in tables:
            source=table.get('locator',{})
            if any(key in locator and key in source and locator[key]!=source[key]
                   for key in ('sheet','jsonPath','jsonlLine','page','table','tableId')):continue
            pointer=locator.get('arrayPointer',locator.get('jsonPointer'))
            source_pointer=source.get('jsonPointer')
            if pointer is not None and source_pointer is not None and pointer!=source_pointer and not pointer.startswith(source_pointer+'/'):continue
            candidates.append(table)
        if len(candidates)>1 and locator.get('jsonPointer') is not None:
            depth=max(len(table.get('locator',{}).get('jsonPointer','')) for table in candidates)
            candidates=[table for table in candidates if len(table.get('locator',{}).get('jsonPointer',''))==depth]
        return candidates if len(candidates)==1 else []

    def _fields_at(self,path,locator):
        return {field['name']:field for table in self._tables_at(path,locator) for field in table['fields']}

    def _binding_at_row(self,binding,asset,locator,row_number):
        location=binding.get('sourceLocator') or binding.get('location') or {}
        if not isinstance(location,dict) or location.get('row',location.get('rawRow'))!=row_number:return False
        if any(key in location and locator.get(key)!=location[key]
               for key in ('sheet','jsonPath','jsonPointer','jsonlLine','page','table','tableId')):return False
        tables=self._tables_at(asset['logicalPath'],location)
        actual=self._tables_at(asset['logicalPath'],locator)
        return bool(len(tables)==len(actual)==1 and tables[0]['tableId']==actual[0]['tableId'])

    def record_units(self,asset,record,locator=None):
        result={}
        for table in self._tables_at(asset['logicalPath'],locator or {}):
            for field in table['fields']:
                value=record.get(field['name'])
                if not isinstance(value,(int,float)) or isinstance(value,bool):continue
                unit=record.get(field['unitField']) if field.get('unitField') else field.get('unit')
                if unit and not field.get('unitRuleUnresolved'):result[field['name']]=unit
        return result

    def _fact_claims(self,text,asset,locator,time_range):
        import re
        tokens=[(match,self.entity_tokens.get(match.group(0).casefold())) for match in re.finditer(r'(?<![A-Za-z0-9_-])[A-Za-z0-9_]+(?:-[A-Za-z0-9_]+)*(?![A-Za-z0-9_-])',text)]
        mentioned={identifier for _,identifier in tokens if identifier}
        bindings=self.visual_bindings(asset['assetId'],locator)
        bound={identifier for binding in bindings for identifier in [*binding.get('entity_ids',[]),binding.get('sku_id')] if identifier}
        from services.professional_retrievers import _has_identifier
        mentioned.update(identifier for identifier in bound if self.entities.get(identifier,{}).get('values',{}).get('model') and _has_identifier(text,self.entities[identifier]['values']['model']))
        try:record=json.loads(text)
        except (ValueError,TypeError):record=None
        source_fields=self._fields_at(asset['logicalPath'],locator)
        claims=[]
        for identifier in sorted(mentioned):
            entry=self.entities[identifier];values=entry.get('values',{});primary=entry.get('primary_key')
            if isinstance(record,dict) and str(record.get(primary))!=identifier:continue
            if not isinstance(record,dict) and identifier not in bound and asset['logicalPath'].lstrip('/')!=entry.get('path','').lstrip('/'):continue
            target_fields=self._fields_at(entry.get('path',''),entry.get('sourceLocator') or entry.get('location') or {})
            start=next((match.start() for match,value in tokens if value==identifier),0)
            if not isinstance(record,dict) and values.get('model'):
                alias=re.search(r'(?<![A-Za-z0-9_-])'+re.escape(values['model'])+r'(?![A-Za-z0-9_-])',text,re.I)
                if alias:start=min(start,alias.start())
            end=next((match.start() for match,value in tokens if value and value!=identifier and match.start()>start and self.entities[value].get('entity_type')==entry.get('entity_type')),len(text))
            raw_span=text[start:end]
            span=re.sub(r'\s+','',raw_span);fields=[];observed_values={};observed_units={}
            dimensions=re.search(r'(?:宽[xX×*]深[xX×*]高|(?:产品|内机|整机)(?:外形|尺寸))[^；。]{0,20}?(\d+(?:\.\d+)?)[xX×*](\d+(?:\.\d+)?)[xX×*](\d+(?:\.\d+)?)(?:mm|毫米)',span)
            for name,value in values.items():
                if name==primary:continue
                # Nulls remain explicit in record facts. They do not establish
                # an additional positive fact merely to reward another copy.
                if (value is None or value=='') and not isinstance(record,dict):continue
                if isinstance(record,dict):
                    observed=record.get(name)
                    if name.endswith('_json') and isinstance(observed,str):
                        try:observed=json.loads(observed)
                        except (ValueError,TypeError):pass
                    canonical=value
                    if name.endswith('_json') and isinstance(canonical,str):
                        try:canonical=json.loads(canonical)
                        except (ValueError,TypeError):pass
                    if name not in record or observed is None or observed=='' or (isinstance(value,bool)!=isinstance(record[name],bool)):continue
                    if canonical is not None and not isinstance(canonical,(dict,list)) and not isinstance(observed,type(canonical)):
                        if not (isinstance(canonical,(int,float)) and isinstance(observed,(int,float))):continue
                    source_field=source_fields.get(name,{})
                    source_unit=record.get(source_field['unitField']) if source_field.get('unitField') else source_field.get('unit')
                    target_field=target_fields.get(name,{})
                    target_unit=values.get(target_field['unitField']) if target_field.get('unitField') else target_field.get('unit')
                    if isinstance(observed,(int,float)) and not isinstance(observed,bool) and target_unit and source_unit!=target_unit:continue
                    fields.append(name)
                    observed_values[name]=observed
                    if source_unit and isinstance(observed,(int,float)) and not isinstance(observed,bool):observed_units[name]=source_unit
                elif name=='model' and value and _has_identifier(raw_span,value):fields.append(name)
                elif name in {'width_mm','depth_mm','height_mm'} and dimensions and value is not None:
                    fields.append(name);observed_values[name]=float(dimensions.group({'width_mm':1,'depth_mm':2,'height_mm':3}[name]));observed_units[name]='mm'
                elif name=='total_volume_l' and value is not None:
                    found=re.search(r'总容积[:：=]?(\d+(?:\.\d+)?)(?:L|升)',span,re.I)
                    if found:fields.append(name);observed_values[name]=float(found[1]);observed_units[name]='L'
                elif isinstance(value,str) and len(value)>=4 and re.search(r'[\u4e00-\u9fff]',value) and value in span:fields.append(name)
                elif value is not None and not isinstance(value,(dict,list)) and re.search(re.escape(name)+r'[:：=]'+re.escape(str(value))+r'(?![\d.])',span):fields.append(name)
            if fields:
                # Only values actually matched above may be used for grounded
                # presentation. A binding alone never supplies an answer value.
                field_values={name:observed_values.get(name,values[name]) for name in fields}
                field_units={name:observed_units.get(name) or (values.get(target_fields[name]['unitField']) if target_fields[name].get('unitField') else target_fields[name].get('unit'))
                    for name in fields if name in target_fields and isinstance(field_values[name],(int,float)) and not isinstance(field_values[name],bool)}
                claims.append(dict(businessType=entry.get('entity_type'),primaryKey=identifier,fields=sorted(fields),timeRange=time_range,
                    fieldValues=field_values,fieldUnits={name:unit for name,unit in field_units.items() if unit},
                    fieldLabels={name:target_fields.get(name,{}).get('description') or name for name in fields}))
        return claims

    def enrich(self, evidence,plan=None):
        asset = self.assets.get(str(evidence.assetId))
        if asset:
            evidence.provenance.update({"originalFormat": asset["originalFormat"],
                "derivedFromPaths": asset.get("derivedFromPaths", []), "scope": asset["scope"]})
            annotations=[]
            from services.professional_retrievers import _has_identifier,_matches
            bindings=self.visual_bindings(str(evidence.assetId),evidence.locator)
            for binding in bindings:
                if plan:binding=self._narrow_binding(binding,plan)
                values=self.binding_values(binding,asset)
                regular=[c for c in plan.object_conditions if not c.field.startswith(('event.','context.'))] if plan else []
                temporal=[c for c in plan.object_conditions if c.field.startswith(('event.','context.'))] if plan else []
                if plan and not all(any(_matches(value,c) for value in values.get(c.field,[])) if isinstance(values.get(c.field),list) else _matches(values.get(c.field),c) for c in regular):continue
                owners=self._temporal_records(binding,asset,plan) if temporal else None
                if temporal and not owners:continue
                for record in self.records.get((asset['assetId'],binding.get('locator')),[]):
                    if record['record_id'] not in binding.get('entity_ids',[]) and record['record_id']!=binding.get('sku_id'):continue
                    if owners is not None and record['record_id'] not in owners:continue
                    if evidence.facts.get('text') and not _has_identifier(evidence.facts['text'],record['record_id']):continue
                    annotation={'recordId':record['record_id'],'businessStage':record.get('business_stage'),
                        'eventValues':record['verifiedEventValues'],'contextValues':record['verifiedContextValues'],
                        'eventTimeSemantics':record.get('event_time_semantics'),'notCaptureTime':True,
                        'sourcePath':record.get('event_time_source'),'sourceLocator':record['verifiedSourceLocator'],
                        'eventCapability':'VERIFIED' if record['verifiedEventValues'] else 'UNVERIFIED' if record.get('event_time_fields') else 'NONE'}
                    if annotation not in annotations:annotations.append(annotation)
            if annotations:
                evidence.provenance['temporalMetadata']=annotations
                verification={}
                for annotation in annotations:
                    source=self.assets_by_path.get(str(annotation['sourcePath']).replace('\\','/').lstrip('/'))
                    if source and source['assetId']!=str(evidence.assetId):verification[source['assetId']]={'assetId':source['assetId'],'logicalPath':source['logicalPath'],'role':'metadata_verification'}
                if verification:evidence.provenance['verificationSources']=list(verification.values())
            if evidence.facts.get('visualOnly'):
                identifiers=set()
                for binding in self.visual_bindings(str(evidence.assetId),evidence.locator):
                    identifiers.update(binding.get('entity_ids',[]))
                    identifiers.update(value for value in [binding.get('sku_id'),binding.get('category_id')] if value)
                descriptions=[asset.get('purpose') or '',asset.get('summary') or '']
                for identifier in sorted(identifiers):
                    descriptions.extend(value for value in self.entities.get(identifier,{}).get('values',{}).values() if isinstance(value,str))
                # Candidate topic metadata is not an observation of image pixels.
                # It is deliberately kept out of facts.text and model context.
                evidence.provenance['relevanceMetadata']=' '.join(descriptions)
                evidence.provenance['visualDescription']=' '.join(descriptions[:2])
            evidence.preview = {**(evidence.preview or {}), "logicalPath": asset["logicalPath"]}
            text = evidence.facts.get("text", "")
            if text:
                try:record=json.loads(text)
                except (ValueError,TypeError):record=None
                if isinstance(record,dict):
                    evidence.units=self.record_units(asset,record,evidence.locator)
                    evidence.provenance['unitResolutionVersion']='record-fields-1'
                claims=self._fact_claims(text,asset,evidence.locator,evidence.timeRange)
                policy=evidence.provenance.get('sourceRecord',{})
                unverified={field for field,eligible in policy.get('calculationEligibility',{}).items() if eligible is False}
                if isinstance(record,dict) and policy.get('numberLexemes'):
                    from decimal import Decimal
                    from services.exact_structured import source_decimal
                    for field,value in record.items():
                        if not isinstance(value,(int,float)) or isinstance(value,bool):continue
                        exact,from_source=source_decimal({'values':record,'numberLexemes':policy['numberLexemes'],'locator':evidence.locator},field)
                        if from_source and exact!=Decimal(str(value)):unverified.add(field)
                if unverified:
                    for claim in claims:
                        claim['fields']=[field for field in claim['fields'] if field not in unverified]
                        for key in ('fieldValues','fieldUnits','fieldLabels'):
                            claim[key]={field:value for field,value in claim.get(key,{}).items() if field not in unverified}
                    claims=[claim for claim in claims if claim['fields']]
                    evidence.provenance['decodedValuesNotVerifiedForPresentation']=sorted(unverified)
                evidence.provenance['factPresentationVersion']='source-matched-fields-1'
                if claims:
                    evidence.provenance['factClaims']=claims
        return evidence
