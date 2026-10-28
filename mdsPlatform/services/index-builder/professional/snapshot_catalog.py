"""Snapshot-local catalog adaptation. Catalog rows describe evidence; they are not facts.

Every binding is resolved against a parsed immutable asset and a real record. No
filename similarity, external paths, or inferred graph edges are used here.
"""
from __future__ import annotations

import json
import re
from copy import deepcopy
from collections import defaultdict
from pathlib import PurePosixPath

from .contracts import normalized_path
from .json_native import is_record_number,number_metadata

CATALOG_VERSION = "snapshot-facets-2026-10-23.2"
TABLE_LOCATOR_KEYS = ('sheet', 'jsonPath', 'jsonPointer', 'jsonlLine', 'page', 'table', 'tableId')


def table_scope(locator):
    """A physical row number alone never identifies a table inside an asset."""
    return tuple((key, json.dumps(locator[key], ensure_ascii=False, sort_keys=True))
        for key in TABLE_LOCATOR_KEYS if locator.get(key) is not None)


def path_key(value):
    value = str(value or "").replace("\\", "/").lstrip("/")
    path = PurePosixPath(value)
    if not value or ".." in path.parts or ":" in value:
        return None
    return normalized_path(value)


def nested_cells(values):
    result = dict(values)
    for key, value in values.items():
        if key.endswith("_json") and isinstance(value, str):
            try:
                parsed = json.loads(value)
                if isinstance(parsed, (dict, list)):
                    result[key] = parsed
            except (ValueError, TypeError):
                pass
    return result


def _strings(value):
    if isinstance(value, dict):
        return [s for v in value.values() for s in _strings(v)]
    if isinstance(value, list):
        return [s for v in value for s in _strings(v)]
    return [str(value)] if value is not None else []


def capability_attributes(attributes):
    """Capabilities describe available reads; they do not transmit source trees.

    The complete native data stays in the hash-bound professional asset shard
    and must be read through a separate authorized, budgeted evidence operation.
    """
    result={key:value for key,value in attributes.items() if key not in {'nativeDocument','nativeDocuments','numberLexemes','workbook','documentLayout',
        'meshSource','assemblyInstances','surfaceViews','displayMesh','svgNative','svgViews'}}
    if 'svgNative' in attributes:
        svg=attributes['svgNative'];result['svgContent']={'elementCount':len(svg['elements']),'textElementCount':len(svg['texts']),
            'metadataCount':len(svg['metadata']),'security':svg['security'],'access':'authorized-evidence-read-required'}
        result['svgViews']=[{key:view[key] for key in ('width','height','recipeVersion','outputSha256','outputBytes')} for view in attributes['svgViews']]
    if 'meshSource' in attributes:
        result['meshContent']={'vertexCount':len(attributes['meshSource']['vertexRecords']),
            'faceCount':len(attributes['meshSource']['faceRecords']),'access':'authorized-evidence-read-required'}
    if 'assemblyInstances' in attributes:
        result['assemblyContent']={'instanceCount':len(attributes['assemblyInstances']),
            'access':'authorized-evidence-read-required'}
    if 'surfaceViews' in attributes:
        result['surfaceViews']=[{key:view[key] for key in ('view','width','height','recipeVersion','outputSha256','outputBytes')} for view in attributes['surfaceViews']]
    if 'documentLayout' in attributes:
        pages=attributes['documentLayout'];result['documentContent']={'pageCount':len(pages),
            'nativeWordCount':sum(len(page['nativeWords']) for page in pages),
            'ocrWordCount':sum(sum(not word.get('exclusionReason') for word in page['ocr']['words']) for page in pages),
            'pages':[{'page':page['page'],'bounds':page['bbox'],'coordinateSystem':page['coordinateSystem'],
                'rotation':page['rotation'],'ocrStatus':page['ocr']['status'],'tableStatus':page.get('tableStatus'),
                'nativeTableCount':len(page.get('tables',[]))} for page in pages],
            'access':'authorized-evidence-read-required','ocrConfidenceMeaning':'recognition-score-not-probability'}
    if 'nativeDocument' in attributes or 'nativeDocuments' in attributes:
        documents=attributes.get('nativeDocuments')
        result['nativeContent']={'available':True,'documentCount':len(documents) if documents is not None else 1,
            'exactNumberTokenCount':sum(len(doc.get('numberLexemes',{})) for doc in documents) if documents is not None else len(attributes.get('numberLexemes',{})),
            'access':'authorized-evidence-read-required'}
    if 'workbook' in attributes:
        book=attributes['workbook']
        result['workbook']={key:value for key,value in book.items() if key not in {'sheets','charts'}}
        result['workbook']['sheets']=[{**{key:value for key,value in sheet.items() if key!='cells'},
            'nativeCellCount':len(sheet.get('cells',{}))} for sheet in book.get('sheets',[])]
        result['workbook']['charts']=[{key:chart[key] for key in ('chartId','title','chartTypes') if key in chart}
            for chart in book.get('charts',[])]
        result['workbook']['nativeCellAccess']='authorized-evidence-read-required'
    return result


class SnapshotCatalog:
    def __init__(self, root, assets, units):
        self.assets = {asset.logical_path: asset for asset in assets}
        self.units = units
        self.rows = {}
        self.source_rows = {}
        self.row_locations = defaultdict(list)
        self.row_locators = {}
        self.ambiguous_rows = set()
        self.fields = defaultdict(list)
        self.sheet_names = defaultdict(set)
        self.binding_issues = defaultdict(list)
        self.entities = {}
        self.entity_rows = defaultdict(list)
        self.relations = []
        self.by_asset = defaultdict(list)
        for unit in units:
            self.by_asset[unit.asset_id].append(unit)
            if unit.unit_type == "TableUnit":
                if unit.locator.get('sheet') is not None:self.sheet_names[unit.logical_path].add(unit.locator['sheet'])
                for row in unit.payload.get("records", []):
                    # Decoding a declared JSON cell is an interpretation. Keep
                    # its original string and transformation separately.
                    row.setdefault('sourceValues',deepcopy(row['values']))
                    row["values"] = nested_cells(row["values"])
                    row['valueTransforms']=row.get('valueTransforms',[])+[{'field':name,'operation':'decode-json-cell','sourceType':'string'}
                        for name,value in row['sourceValues'].items() if name.endswith('_json') and isinstance(value,str)
                        and isinstance(row['values'].get(name),(dict,list))]
                    locator={**unit.locator, **row.get('locator', {}), 'rawRow':row.get('rawRow')}
                    if row.get('jsonPointer') is not None:locator['jsonPointer']=row['jsonPointer']
                    key=(unit.asset_id,table_scope(locator),row.get('rawRow'))
                    if key in self.rows:
                        if self.rows[key]!=row['values']:self.ambiguous_rows.add(key)
                    else:self.row_locations[(unit.logical_path,row.get('rawRow'))].append(key)
                    self.rows[key]=row['values'];self.source_rows[key]=row['sourceValues'];self.row_locators[key]=locator

        def read(name):
            path = root / "catalog" / name
            if path_key("catalog/" + name) not in self.assets:
                return []
            data = json.loads(path.read_text(encoding="utf-8-sig"))
            return data if isinstance(data, list) else []

        for entry in read("field_dictionary.json"):
            path = path_key(entry.get("path"))
            if path in self.assets:
                self.fields[path].append(entry)
        for entry in read("entities.json"):
            path = path_key(entry.get("path"))
            resolved = self._resolve_row(path,entry)
            row_key,values,locator=resolved if resolved else (None,{},None)
            entity, primary_key = entry.get("entity_id"), entry.get("primary_key")
            if entity and primary_key and str(values.get(primary_key)) == str(entity):
                row_key=resolved[0]
                self.entities[str(entity)] = {**entry, "values": values, "sourceValues":self.source_rows[row_key], "sourceLocator":locator}
                self.entity_rows[row_key].append(entry)
        for edge in read("relations.json"):
            path = path_key(edge.get("path"))
            resolved=self._resolve_row(path,edge)
            row_key,values,locator=resolved if resolved else (None,{},None)
            source, target = str(edge.get("source_id")), str(edge.get("target_id"))
            owners = self.entity_rows.get(row_key, [])
            if (source in self.entities and target in self.entities
                    and any(str(owner["entity_id"]) == source for owner in owners)
                    and target in _strings(values.get(edge.get("field")))):
                self.relations.append({"sourceEntityId": source, "targetEntityId": target,
                    "relationType": edge.get("meaning"), "field": edge.get("field"),
                    "assetId": self.assets[path].asset_id, "logicalPath": path,
                    "locator": locator, "evidenceValue": values.get(edge.get("field")),
                    "sourceValue":self.source_rows[row_key].get(edge.get('field'))})
        entries = {path_key(item.get("path")): item for item in read("assets.json")}
        self.descriptions = {}
        for path, asset in self.assets.items():
            entry = entries.get(path, {})
            if entry.get("sha256") and entry["sha256"] != asset.content_sha256:
                entry = {}  # A stale catalog cannot authorize bindings to changed content.
            valid_bindings = []
            for binding in entry.get("bindings", []):
                ids = [str(v) for v in binding.get("entity_ids", []) if str(v) in self.entities]
                sku = binding.get("sku_id")
                if sku and sku not in self.entities:
                    continue
                location=binding.get('location')
                if isinstance(location,dict) and any(key in location for key in ('row','rawRow','jsonPointer')):
                    resolved=self._resolve_row(path,binding)
                    if not resolved:continue
                    binding={**binding,'sourceLocator':resolved[2]}
                valid_bindings.append({**binding, "entity_ids": ids})
            derived = [p for p in (path_key(p) for p in entry.get("derived_from_paths", [])) if p in self.assets]
            self.descriptions[asset.asset_id] = {
                "assetId": asset.asset_id, "logicalPath": path,
                "originalFormat": "JPG" if asset.source_format == "JPEG" else asset.source_format,
                "catalogAssetId": entry.get("asset_id"), "title": entry.get("title") or path.rsplit("/", 1)[-1],
                "purpose": entry.get("content_type"), "summary": entry.get("summary"),
                "bindings": valid_bindings, "recordMetadata": entry.get("record_metadata", []),
                "derivedFromPaths": derived, "attributes": capability_attributes(asset.attributes),
                "qualityFlags": asset.quality_flags, "scope": "catalog" if path.startswith("/catalog/") else "corpus",
                "bindingIssues":self.binding_issues.get(path,[])[:100],
                "contentTypes": sorted({{"TextUnit": "text", "VisualUnit": "visual", "TableUnit": "structured",
                    "TimeSeriesUnit": "timeseries", "GeometryUnit": "geometry"}[u.unit_type]
                    for u in self.by_asset[asset.asset_id]}),
            }
            for unit in self.by_asset[asset.asset_id]:
                unit.metadata = {**unit.metadata, **self.unit_metadata(unit)}
                names=[column['name'] for column in unit.payload.get('columns',[])] if unit.unit_type=='TableUnit' else []
                if unit.unit_type=='TextUnit':
                    try:
                        record=json.loads(unit.payload)
                        if isinstance(record,dict):names=list(record)
                    except (ValueError,TypeError):pass
                for name in names:
                    field=self._field_for(unit,name)
                    if field.get('unit'):unit.units[name]=field['unit']

    def _resolve_row(self,path,entry):
        location=entry.get('location') or {}
        if not isinstance(location,dict):return None
        row=location.get('row',location.get('rawRow'))
        constraints={key:location.get(key,entry.get(key)) for key in TABLE_LOCATOR_KEYS
            if location.get(key,entry.get(key)) is not None}
        if row is None:
            if 'jsonPointer' not in constraints or path not in self.assets:return None
            keys=[key for key in self.rows if key[0]==self.assets[path].asset_id]
        else:keys=self.row_locations.get((path,row),[])
        candidates=[key for key in keys if key not in self.ambiguous_rows
            and all(self.row_locators[key].get(name)==value for name,value in constraints.items())]
        if len(candidates)!=1:
            self.binding_issues[path].append({'code':'AMBIGUOUS_OR_MISSING_SOURCE_ROW','location':location,'candidateCount':len(candidates)})
            return None
        key=candidates[0]
        return key,self.rows[key],self.row_locators[key]

    def _field_for(self,unit,name):
        specific=[];generic=[]
        for entry in self.fields[unit.logical_path]:
            field=entry.get('field');selector={key:entry[key] for key in TABLE_LOCATOR_KEYS if entry.get(key) is not None}
            if isinstance(field,str) and '.' in field:
                prefix,rest=field.split('.',1)
                if prefix in self.sheet_names[unit.logical_path]:selector.setdefault('sheet',prefix);field=rest
            # JSON-array declarations retain their source hierarchy. They apply
            # only to the matching parsed array, never to another same-name key.
            json_path=unit.locator.get('jsonPath','')
            if isinstance(field,str) and json_path.startswith('$.'):
                prefix=json_path[2:]+'[].'
                if field.startswith(prefix):field=field[len(prefix):]
            field_locator={**unit.locator}
            if unit.unit_type!='TableUnit' and 'arrayPointer' in field_locator:
                field_locator['jsonPointer']=field_locator['arrayPointer']
            if field!=name or any(field_locator.get(key)!=value for key,value in selector.items()):continue
            (specific if selector else generic).append(entry)
        eligible=specific or generic
        # Repeated unqualified declarations with incompatible semantics are not
        # resolved by whichever metadata entry happened to be read last.
        identities={json.dumps({k:v for k,v in entry.items() if k not in {'path','sheet','jsonPath','jsonPointer'}},sort_keys=True,ensure_ascii=False) for entry in eligible}
        return eligible[0] if len(identities)==1 else {}

    def unit_metadata(self, unit):
        description = self.descriptions.get(unit.asset_id, {})
        content = str(unit.payload)
        bindings = []
        for binding in description.get("bindings", []):
            ids = binding.get("entity_ids", [])
            # A page match alone is not sufficient for a multi-record page.
            matched = [entity for entity in ids if re.search(r'(?<![A-Za-z0-9_-])'+re.escape(entity)+r'(?![A-Za-z0-9_-])',content,re.I)]
            if unit.unit_type in {"VisualUnit", "GeometryUnit"} and len(description.get("bindings", [])) == 1:
                matched = ids
            if matched:
                bindings.append({**binding, "entity_ids": matched})
        return {"originalFormat": description.get("originalFormat"), "purpose": description.get("purpose"),
                "scope": description.get("scope"), "bindings": bindings,
                "derivedFromPaths": description.get("derivedFromPaths", []),
                "representationId": unit.unit_id, "representationKind": unit.unit_type}

    def describe(self):
        tables, series, geometry = [], [], []
        for unit in self.units:
            if unit.unit_type == "TableUnit":
                records = unit.payload.get("records", [])
                fields = []
                for column in unit.payload.get("columns", []):
                    name = column["name"]
                    info = self._field_for(unit,name)
                    values = [row["values"].get(name) for row in records]
                    present = [v for v in values if v is not None and v != ""]
                    type_counts=defaultdict(int);typed_enum={};legacy_enum=set()
                    for row in records:
                        value=row['values'].get(name)
                        if value is None or value=='':continue
                        numeric=is_record_number(row,name)
                        observed='number' if numeric else 'boolean' if isinstance(value,bool) else 'array' if isinstance(value,list) else 'object' if isinstance(value,dict) else 'string'
                        type_counts[observed]+=1
                        if observed in {'array','object'}:continue
                        number=number_metadata(row,name) if numeric else None
                        expression=number['lexeme'] if number else str(value) if numeric else None
                        entry={'sourceType':observed,'sourceNumber':expression} if numeric else {'sourceType':observed,'value':value}
                        serialized=json.dumps(entry,ensure_ascii=False,sort_keys=True)
                        typed_enum[serialized]=entry
                        # Encoded numbers and rounded binary conveniences are
                        # never exposed as ordinary source-string enum values.
                        if not numeric:legacy_enum.add(json.dumps(value,ensure_ascii=False,sort_keys=True))
                        elif not isinstance(value,str) and (not number or number.get('binaryValueExact')):legacy_enum.add(json.dumps(value,ensure_ascii=False,sort_keys=True))
                    source_types=sorted(type_counts)
                    kind=source_types[0] if len(source_types)==1 else 'mixed' if source_types else 'string'
                    if kind=='string' and info.get("type") in {"date", "datetime", "timestamp"}:
                        kind = info["type"]
                    all_enum = legacy_enum
                    enum = sorted(value for value in all_enum if len(value)<=256)
                    typed_values=[typed_enum[key] for key in sorted(typed_enum) if len(key)<=512]
                    numeric_field='number' in source_types
                    fields.append({"name": name, "description": info.get("description") or name,
                        "type": kind, "unit": info.get("unit") or unit.units.get(name),
                        'sourceTypes':source_types,'sourceTypeCounts':dict(type_counts),
                        'typedEnum':typed_values[:200],'typedEnumDistinctCount':len(typed_enum),'typedEnumTruncated':len(typed_enum)>min(200,len(typed_values)),
                        'numericPredicateEncoding':'source-number-lexeme-v1' if numeric_field else None,
                        'numericInputPolicy':'source-number-types-only;other-types-excluded-and-counted' if numeric_field else None,
                        "nullable": len(present) < len(values), "nullRule": info.get("null_rule") or "空值表示未记录或不适用；不补0",
                        "enum": [json.loads(v) for v in enum[:200]], "enumTruncated": len(all_enum)>min(200,len(enum)),
                        "enumDistinctCount":len(all_enum), "enumPreviewLimit":200,"enumValueCharacterLimit":256,
                        "operators": ["eq", "in", "is_null", "not_null"] +
                            (["gt", "gte", "lt", "lte", "between"] if numeric_field or kind in {"date", "datetime", "timestamp"} else []) +
                            (["contains"] if kind == "array" else []),
                        "aggregations": ["sum", "min", "max", "average"] if numeric_field else []})
                tables.append({"tableId": unit.unit_id, "assetId": unit.asset_id, "logicalPath": unit.logical_path,
                    "locator": unit.locator, "rowCount": len(records), "fields": fields,
                    "sourceTimePolicy":unit.payload.get('sourceTimePolicy',{}),"timeInterpretation":unit.metadata.get('timeInterpretation',{}),
                    "originalFormat": self.descriptions[unit.asset_id]["originalFormat"],
                    "scope": self.descriptions[unit.asset_id]["scope"], "operations": ["search", "filter", "sort", "count", "sum", "min", "max", "average"]})
            elif unit.unit_type == "TimeSeriesUnit":
                groups = defaultdict(list)
                for fact in unit.payload.get("facts", []):
                    groups[(fact["entity"], fact["metric"], fact.get("unit"))].append(fact)
                for (entity, metric, measure_unit), facts in sorted(groups.items(), key=lambda pair: str(pair[0])):
                    series.append({"assetId": unit.asset_id, "logicalPath": unit.logical_path, "seriesId": entity,
                        "metric": metric, "unit": measure_unit, "sampleCount": len(facts),
                        'calculationEligibleSampleCount':sum(f.get('calculationEligible',True) for f in facts),
                        'uncalculableSampleCount':sum(f.get('calculationEligible') is False for f in facts),
                        'calculationAvailability':'COMPLETE' if all(f.get('calculationEligible',True) for f in facts) else 'NONE' if all(f.get('calculationEligible') is False for f in facts) else 'PARTIAL',
                        "sourceTimezone": unit.payload.get("sourceTimezone"),
                        "sourceTimePolicy":unit.payload.get("sourceTimePolicy",{}),
                        "start": min(f["normalizedTime"] for f in facts), "end": max(f["normalizedTime"] for f in facts),
                        "originalFormat": self.descriptions[unit.asset_id]["originalFormat"],
                        "qualityFlags": sorted(set(unit.quality_flags)|{flag for fact in facts for flag in fact.get('qualityFlags',[])})})
            elif unit.unit_type == "GeometryUnit":
                geometry.append({"assetId": unit.asset_id, "logicalPath": unit.logical_path,
                    "attributes": {k: v for k, v in unit.payload.items() if k != "featureVector"},
                    "units": unit.units, "qualityFlags": unit.quality_flags})
        return {"schemaVersion": CATALOG_VERSION, "assets": list(self.descriptions.values()),
                "tables": tables, "series": series, "geometry": geometry,
                "entities": list(self.entities.values()), "relations": self.relations,
                "nullSemantics": "未记录或不适用；不补0", "defaultScope": "corpus"}
