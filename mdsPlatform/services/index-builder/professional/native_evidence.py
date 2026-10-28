"""Immutable, bounded native evidence. Reading these shards still requires auth."""
from __future__ import annotations

import hashlib
import json
from dataclasses import asdict
from pathlib import Path

from .contracts import (EVIDENCE_RECIPE_VERSION, EVIDENCE_SCHEMA_VERSION, EvidenceBundle,
    EvidenceFact, EvidenceIdentity, organizer_version, stable_id, json_safe)
from .json_native import pointer_child

TARGET_BYTES=128*1024
MAX_SHARD_BYTES=8*1024*1024
JSON_RECORD_TABLE_VERSION='json-tree-record-table-2026-10-26.1'


def canonical(value):
    return json.dumps(json_safe(value),ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)


def identity_parts(identity):
    return (identity.datasetId,identity.versionId,identity.indexVersionId,identity.rawCommitId,
        identity.assetId,identity.sourceSha256,EVIDENCE_RECIPE_VERSION,identity.evidenceConfigSha256)


def fact_id(identity,locator,nature,field,unit=None):
    return stable_id(*identity_parts(identity),canonical(locator),nature,field,canonical(unit))


def _partition(values,limit=128):
    page=[];size=0
    for value in values:
        length=len(canonical(value).encode())
        if page and (len(page)>=limit or size+length>TARGET_BYTES):
            yield page;page=[];size=0
        page.append(value);size+=length
    if page:yield page


class NativeEvidenceWriter:
    def __init__(self,asset,units,output_dir,snapshot,*,processing_config=None):
        self.asset=asset;self.units=units;self.output=Path(output_dir)
        from .snapshot_catalog import CATALOG_VERSION
        config=processing_config or {'timezone':None,'unitMapping':{},'timeColumn':None,'entityColumns':[]}
        processing_hash=hashlib.sha256(canonical(config).encode()).hexdigest()
        self.transform={'operation':'native-parse','parser':asset.parser_name,'parserVersion':asset.parser_version,
            'parserConfigVersion':asset.parser_config_version,'organizerVersion':organizer_version(asset.source_format),
            'catalogVersion':CATALOG_VERSION,'processingConfigSha256':processing_hash,
            'sourceSha256':asset.content_sha256,'recipeVersion':EVIDENCE_RECIPE_VERSION}
        if asset.attributes.get('documentAdapters'):self.transform['contentAdapters']=asset.attributes['documentAdapters']
        if asset.attributes.get('engineeringAdapters'):self.transform['engineeringAdapters']=asset.attributes['engineeringAdapters']
        if asset.source_format in {'JSON','JSONL'}:
            self.transform['jsonRecordTablesVersion']=JSON_RECORD_TABLE_VERSION
        if asset.source_format in {'SVG','OBJ','STEP'} and asset.attributes.get('engineeringAdapters'):
            from .engineering_render import engine_binding
            self.transform['engineeringTools']=engine_binding(asset.source_format)
            self.transform['engineeringFactNatureVersion']='engineering-fact-nature-2026-10-22.1'
        config_hash=hashlib.sha256(canonical({'transform':self.transform,
            'unitBindings':[{'unitId':unit.unit_id,'locator':unit.locator,'units':unit.units} for unit in units]}).encode()).hexdigest()
        self.identity=EvidenceIdentity(datasetId=str(snapshot['datasetId']),versionId=asset.dataset_version_id,
            indexVersionId=str(snapshot['indexVersionId']),rawCommitId=str(snapshot['rawCommitId']),
            assetId=asset.asset_id,logicalPath=asset.logical_path,sourceSha256=asset.content_sha256,originalFormat=asset.source_format,
            evidenceConfigSha256=config_hash)
        self.descriptors=[];self.empty_json_tables=[]

    def evidence_id(self,kind,locator):
        return stable_id(*identity_parts(self.identity),kind,canonical(locator))

    def emit(self,kind,locator,content,facts=(),*,coverage=None,parents=(),operations=(),nature='source_record',selector=None,quality_flags=()):
        signature=hashlib.sha256(canonical([kind,locator,content]).encode()).hexdigest()
        evidence_id=self.evidence_id(kind,locator)
        identifier=stable_id(evidence_id,signature)
        bundle=EvidenceBundle(identity=self.identity,evidenceId=evidence_id,representationId=identifier,
            representationKind=kind,evidenceNature=nature,locator=locator,content=content,facts=list(facts),
            coverage=coverage or {'partial':False,'truncated':False},capabilities=list(operations),unsupportedReasons=[],
            parentEvidenceIds=list(parents),qualityFlags=sorted(set(self.asset.quality_flags)|set(quality_flags)),
            transforms=[dict(self.transform)])
        raw=canonical(bundle.to_dict()).encode()
        if len(raw)>MAX_SHARD_BYTES:raise ValueError('EVIDENCE_SHARD_SIZE_LIMIT:'+self.asset.logical_path)
        checksum=hashlib.sha256(raw).hexdigest()
        path=self.output/'evidence'/self.asset.asset_id/'content'/f'{checksum}.json'
        path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
        descriptor={'representationId':identifier,'evidenceId':evidence_id,'kind':kind,'locator':locator,
            'outputPath':'/'+path.relative_to(self.output).as_posix(),'outputSha256':checksum,'sizeBytes':len(raw),
            'recipeVersion':EVIDENCE_RECIPE_VERSION,'schemaVersion':EVIDENCE_SCHEMA_VERSION,
            'evidenceNature':nature,'coverage':bundle.coverage,'capabilities':list(operations),
            'unsupportedReasons':[],'selector':selector or {}}
        self.descriptors.append(descriptor)
        return identifier

    def table(self,unit):
        records=unit.payload.get('records',[])
        pages=list(_partition(records)) or [[]]
        columns=unit.payload.get('columns',[]);column_by_name={column['name']:column for column in columns}
        parent=self.evidence_id('table_schema',unit.locator);children=[]
        for page_index,page in enumerate(pages):
            facts=[]
            for record in page:
                row_locator={**unit.locator,**record.get('locator',{}),'rawRow':record.get('rawRow')}
                for field,value in record['values'].items():
                    locator={**row_locator,'field':field}
                    if field in row_locator.get('cells',{}):locator['cell']=row_locator['cells'][field]
                    if 'jsonPointer' in row_locator:locator['jsonPointer']=pointer_child(row_locator['jsonPointer'],field)
                    if self.asset.source_format=='CSV':locator['column']=column_by_name.get(field,{}).get('sourceColumn')
                    number=record.get('numberLexemes',{}).get(field) or record.get('numberLexemes',{}).get(locator.get('jsonPointer'))
                    formula=record.get('formulas',{}).get(field)
                    nature='source_declaration' if formula else 'source_record'
                    facts.append(EvidenceFact(factId=fact_id(self.identity,locator,nature,field,unit.units.get(field)),field=field,value=value,
                        evidenceNature=nature,locator=locator,sourceValue=record.get('sourceValues',record['values']).get(field),
                        unit=unit.units.get(field),numberSourceExpression=number.get('lexeme') if number else None,
                        calculationEligible=record.get('calculationEligibility',{}).get(field,True),
                        qualityFlags=['FORMULA_CACHE_UNVERIFIED'] if formula else []))
            rows=[record['rawRow'] for record in page]
            children.append(self.emit('table_records',{**unit.locator,'recordPageIndex':page_index},{'tableId':unit.unit_id,'columns':columns,'records':page,
                'nullSemantics':unit.payload.get('nullSemantics','native null and missing keys remain distinct')},facts,
                parents=[parent],
                coverage={'partial':len(pages)>1,'truncated':False,'pageIndex':page_index,'pageCount':len(pages),
                    'readRowCount':len(page),'totalRowCount':len(records),'rowStart':min(rows) if rows else None,'rowEnd':max(rows) if rows else None},
                operations=['read_table','read_representation'],selector={'tableId':unit.unit_id,'rowStart':min(rows) if rows else None,'rowEnd':max(rows) if rows else None},quality_flags=unit.quality_flags))
        self.emit('table_schema',unit.locator,{'tableId':unit.unit_id,'columns':columns,'recordPages':children,
            'sourceTimePolicy':unit.payload.get('sourceTimePolicy',{}),'timeInterpretation':unit.metadata.get('timeInterpretation',{})},
            coverage={'partial':True,'truncated':False,'totalRowCount':len(records)},operations=['read_table','read_representation'],selector={'tableId':unit.unit_id},quality_flags=unit.quality_flags)

    def json_table(self,unit):
        """Register field/row metadata; values remain in the authoritative tree."""
        records=unit.payload.get('records',[]);bindings=[];seen=set()
        for record in records:
            row=record.get('rawRow');locator=record.get('locator',{})
            if type(row) is not int or row<1 or row in seen:raise ValueError('JSON_RECORD_TABLE_ROW_IDENTITY_INVALID')
            seen.add(row)
            pointer=locator.get('jsonPointer','')
            if not isinstance(pointer,str) or pointer and not pointer.startswith('/'):
                raise ValueError('JSON_RECORD_TABLE_POINTER_INVALID')
            binding={'rawRow':row,'jsonPointer':pointer,'fields':sorted(record['values'])}
            line=locator.get('jsonlLine',unit.locator.get('jsonlLine'))
            if self.asset.source_format=='JSONL':
                if type(line) is not int or line<1:raise ValueError('JSON_RECORD_TABLE_PHYSICAL_LINE_REQUIRED')
                binding['jsonlLine']=line
            elif line is not None:raise ValueError('JSON_RECORD_TABLE_UNEXPECTED_PHYSICAL_LINE')
            bindings.append(binding)
        bindings.sort(key=lambda record:record['rawRow'])
        parent=self.evidence_id('table_schema',unit.locator);pages=[]
        for number,page in enumerate(_partition(bindings)):
            pages.append(self.emit('json_record_row_map',{**unit.locator,'recordMapPageIndex':number},
                {'tableId':unit.unit_id,'version':JSON_RECORD_TABLE_VERSION,'records':page},parents=[parent],
                coverage={'partial':True,'truncated':False,'readRowCount':len(page),'totalRowCount':len(bindings)},
                operations=['read_representation'],selector={'tableId':unit.unit_id,'rowStart':page[0]['rawRow'],
                    'rowEnd':page[-1]['rawRow'],'rowCount':len(page)},quality_flags=unit.quality_flags))
        columns=[{key:value for key,value in column.items() if key!='sample'} for column in unit.payload.get('columns',[])]
        content={'tableId':unit.unit_id,'columns':columns,'fieldUnits':dict(unit.units),'recordPages':[],
            'jsonRecordTable':{'version':JSON_RECORD_TABLE_VERSION,'rowMapPages':pages,'rowCount':len(bindings),
                'valueBacking':'authoritative-native-json-tree','missingFieldPolicy':'not-recorded; never synthesize null or zero'},
            'sourceTimePolicy':unit.payload.get('sourceTimePolicy',{}),'timeInterpretation':unit.metadata.get('timeInterpretation',{})}
        if not bindings:
            self.empty_json_tables.append({'locator':unit.locator,'content':content,'qualityFlags':unit.quality_flags})
            return
        self.emit('table_schema',unit.locator,content,
            coverage={'partial':True,'truncated':False,'totalRowCount':len(bindings)},operations=['read_table','read_representation'],
            selector={'tableId':unit.unit_id,'jsonRecordTableVersion':JSON_RECORD_TABLE_VERSION},quality_flags=unit.quality_flags)

    def json_tree(self,value,numbers,pointer='',line=None,parents=()):
        locator={'jsonPointer':pointer,'arrayIndexBase':0}
        if line is not None:locator['jsonlLine']=line
        if len(canonical([value,numbers]).encode())<=TARGET_BYTES:
            fact=EvidenceFact(factId=fact_id(self.identity,locator,'source_record','value'),field='value',value=value,
                evidenceNature='source_record',locator=locator,sourceValue=value,
                numberSourceExpression=numbers.get(pointer,{}).get('lexeme'),calculationEligible=numbers.get(pointer,{}).get('calculationEligible',True))
            return self.emit('json_subtree',locator,{'value':value,'numberLexemes':numbers},[fact],
                parents=parents,
                coverage={'partial':False,'truncated':False,'completeSubtree':True},operations=['read_json_subtree','read_representation'])
        if isinstance(value,(dict,list)):
            children=[];parent=self.evidence_id('json_subtree',locator)
            grouped={}
            prefix=pointer+'/'
            for path,info in numbers.items():
                if path.startswith(prefix):grouped.setdefault(path[len(prefix):].split('/',1)[0],{})[path]=info
            for key,child in (value.items() if isinstance(value,dict) else enumerate(value)):
                child_pointer=pointer_child(pointer,key);token=child_pointer.rsplit('/',1)[-1]
                child_numbers=grouped.get(token,{})
                if len(canonical([child,child_numbers]).encode())<=TARGET_BYTES:
                    children.append({'key':key,'jsonPointer':child_pointer,'value':child,'numberLexemes':child_numbers})
                else:
                    child_id=self.json_tree(child,child_numbers,child_pointer,line,[parent])
                    children.append({'key':key,'jsonPointer':child_pointer,'representationId':child_id})
            branches=[]
            for index,page in enumerate(_partition(children)):
                facts=[]
                for child in page:
                    if 'value' not in child:continue
                    region={**locator,'jsonPointer':child['jsonPointer']}
                    facts.append(EvidenceFact(factId=fact_id(self.identity,region,'source_record','value'),field='value',value=child['value'],
                        evidenceNature='source_record',locator=region,sourceValue=child['value'],
                        numberSourceExpression=child.get('numberLexemes',{}).get(child['jsonPointer'],{}).get('lexeme'),
                        calculationEligible=child.get('numberLexemes',{}).get(child['jsonPointer'],{}).get('calculationEligible',True)))
                branches.append(self.emit('json_children_page',{**locator,'childPageIndex':index},{'jsonType':'object' if isinstance(value,dict) else 'array',
                    'children':page,'childCount':len(value),'branchIndex':index},facts,
                    selector={'jsonPointer':pointer,'jsonlLine':line,'inlinePointers':[child['jsonPointer'] for child in page if 'value' in child]},
                    parents=[parent],
                    coverage={'partial':True,'truncated':False,'completeSubtree':False,'readChildCount':len(page),'totalChildCount':len(value)},
                    operations=['read_representation']))
            return self.emit('json_subtree',locator,{'jsonType':'object' if isinstance(value,dict) else 'array',
                'childCount':len(value),'childPages':branches},parents=parents,
                coverage={'partial':True,'truncated':False,'completeSubtree':False},operations=['read_json_subtree','read_representation'])
        if not isinstance(value,str):raise ValueError('EVIDENCE_PRIMITIVE_TOO_LARGE')
        segments=[]
        for start in range(0,len(value),16000):
            text=value[start:start+16000];region={**locator,'characterStart':start,'characterEnd':start+len(text)}
            fact=EvidenceFact(factId=fact_id(self.identity,region,'source_text','text'),field='text',value=text,evidenceNature='source_text',locator=region)
            segments.append(self.emit('json_string_segment',region,{'text':text},[fact],parents=[self.evidence_id('json_subtree',locator)],
                coverage={'partial':True,'truncated':False,'totalCharacters':len(value),'readCharacters':len(text)},operations=['read_representation'],nature='source_text'))
        return self.emit('json_subtree',locator,{'jsonType':'string','characterCount':len(value),'segments':segments},
            parents=parents,
            coverage={'partial':True,'truncated':False,'completeSubtree':False},operations=['read_json_subtree','read_representation'])

    def workbook(self,book):
        for sheet in book.get('sheets',[]):
            summary={key:value for key,value in sheet.items() if key!='cells'}
            for page in _partition([dict(info) for info in sheet.get('cells',{}).values()]):
                facts=[]
                for cell in page:
                    locator={'sheet':sheet['sheet'],'cell':cell['cell']}
                    nature='source_declaration' if cell.get('formula') is not None else 'source_record'
                    facts.append(EvidenceFact(factId=fact_id(self.identity,locator,nature,cell['cell']),field=cell['cell'],
                        value=cell.get('value'),evidenceNature=nature,locator=locator,
                        sourceValue=cell.get('formula') if cell.get('formula') is not None else cell.get('rawValue'),
                        numberSourceExpression=cell.get('rawValue') if cell.get('ooxmlType')=='n' and not cell.get('isDate') else None,
                        calculationEligible=cell.get('calculationEligible',True),
                        qualityFlags=['FORMULA_CACHE_UNVERIFIED'] if cell.get('formula') is not None else []))
                self.emit('workbook_cells',{'sheet':sheet['sheet']},{'cells':page,'sheet':summary,
                    'dateSystem':book['dateSystem'],'formulaExecution':book['formulaExecution'],'cacheTrust':book['cacheTrust']},facts,
                    selector={'sheet':sheet['sheet'],'cells':[cell['cell'] for cell in page]},
                    operations=['read_workbook_cells','read_representation'],
                    coverage={'partial':True,'truncated':False,'readCellCount':len(page),'totalCellCount':len(sheet.get('cells',{}))})
        for chart in book.get('charts',[]):
            self.emit('workbook_chart',{'chartId':chart['chartId']},chart,
                operations=['read_representation'],nature='source_declaration')

    def document(self,pages):
        from .document_native import bbox_union
        for page in pages:
            location={key:page[key] for key in ('page','bbox','coordinateSystem')}
            parent=self.evidence_id('document_page',location)
            words=[*page['nativeWords'],*(word for word in page['ocr']['words'] if not word.get('exclusionReason'))]
            children=[]
            for index,chunk in enumerate(_partition(words)):
                facts=[];content=[]
                for word in chunk:
                    nature='ocr_text' if word['extractionMethod']=='tesseract-ocr' else 'source_text'
                    locator={**location,'bbox':word['bbox'],'extractionMethod':word['extractionMethod'],
                        'wordIndex':word['wordIndex']}
                    identifier=fact_id(self.identity,locator,nature,'text')
                    facts.append(EvidenceFact(factId=identifier,field='text',value=word['text'],sourceValue=word['text'],
                        evidenceNature=nature,locator=locator,calculationEligible=nature!='ocr_text',qualityFlags=word.get('qualityFlags',[])))
                    content.append({**word,'factId':identifier})
                children.append(self.emit('document_words',{**location,'wordPageIndex':index},
                    {'words':content,'wordOrder':'native PDF words followed by deduplicated OCR block/paragraph/line words'},facts,
                    parents=[parent],operations=['read_region_text','read_representation'],nature='text_observation',
                    selector={'page':page['page'],'pageBounds':page['bbox'],'coordinateSystem':page['coordinateSystem'],
                        'bbox':bbox_union(word['bbox'] for word in chunk)},
                    coverage={'partial':len(chunk)<len(words),'truncated':False,'readWordCount':len(chunk),'totalWordCount':len(words)},
                    quality_flags={flag for word in chunk for flag in word.get('qualityFlags',[])}))
            summary={key:value for key,value in page.items() if key not in {'nativeWords','ocr'}}
            summary['ocr']={key:value for key,value in page['ocr'].items() if key not in {'words','elapsedMs'}}
            summary['ocr']['excludedWords']=[word for word in page['ocr']['words'] if word.get('exclusionReason')]
            summary['wordPages']=children
            self.emit('document_page',location,summary,operations=['read_region_text','read_representation','render_region_image'],
                nature='source_layout',selector={'page':page['page'],'pageBounds':page['bbox'],'coordinateSystem':page['coordinateSystem'],
                    'renderRecipeVersion':self.asset.attributes['documentAdapters']['regionRenderVersion']},
                coverage={'partial':True,'truncated':False,'totalWordCount':len(words),'totalPageCount':len(pages),
                    'ocrStatus':page['ocr']['status'],'semanticReadingOrderVerified':False})

    def write(self):
        for unit in self.units:
            # JSON table schemas point into the original tree. They do not
            # duplicate every nested record value into another data store.
            if unit.unit_type=='TableUnit':
                if self.asset.source_format in {'JSON','JSONL'}:self.json_table(unit)
                else:self.table(unit)
            elif unit.unit_type=='TextUnit' and 'rawRow' not in unit.locator and self.asset.source_format not in {'JSON','JSONL'} and unit.locator.get('source')!='filename':
                nature='ocr_text' if unit.locator.get('extractionMethod')=='tesseract-ocr' else 'source_text'
                fact=EvidenceFact(factId=fact_id(self.identity,unit.locator,nature,'text'),field='text',value=unit.payload,
                    evidenceNature=nature,locator=unit.locator,sourceValue=unit.payload,calculationEligible=nature!='ocr_text',qualityFlags=unit.quality_flags)
                self.emit('text_passage',unit.locator,{'text':unit.payload},[fact],operations=['read_representation'],nature=nature,quality_flags=unit.quality_flags,
                    coverage={'partial':True,'truncated':False,'note':'A locatable passage, not a claim that the entire asset entered the model.'})
            elif unit.unit_type=='GeometryUnit':
                facts=[]
                for field,value in unit.payload.items():
                    if field in {'featureVector','surfaceViews','displayMesh'}:continue
                    locator={**unit.locator,'property':field}
                    measurement_unit=unit.units.get({'bounds':'length','surfaceArea':'area','volume':'volume'}.get(field,''))
                    nature='source_declaration' if field in {'sourceUnits','name','materialDeclarations'} else 'program_calculation'
                    facts.append(EvidenceFact(factId=fact_id(self.identity,locator,nature,field,measurement_unit),
                        field=field,value=value,evidenceNature=nature,locator=locator,unit=measurement_unit,sourceValue=value if nature=='source_declaration' else None,
                        calculationEligible=nature=='program_calculation' and value is not None and field in {'bounds','surfaceArea','volume','vertexCount','sourceFaceCount','faceCount','solidCount','triangleCount'},
                        qualityFlags=unit.quality_flags))
                self.emit('geometry_properties',unit.locator,{'properties':unit.payload,'units':unit.units},facts,
                    operations=['read_properties','read_representation'],nature='program_calculation',
                    selector={'fields':[fact.field for fact in facts]},
                    coverage={'partial':False,'truncated':False,'instanceId':unit.locator.get('instanceId'),
                        'geometryPropertiesOnly':True,'validationModel':'mesh topology; self-intersection not certified' if self.asset.source_format=='OBJ' else 'OCCT BRepCheck; manufacturing suitability not certified'})
        # An empty array remains a registered table, but thousands of empty
        # catalog enums must not each require an independent storage object.
        for page_index,page in enumerate(_partition(self.empty_json_tables)):
            self.emit('json_empty_table_schemas',{'emptyTableSchemaPageIndex':page_index},
                {'version':JSON_RECORD_TABLE_VERSION,'tables':page},
                operations=['read_table','read_representation'],selector={'tableIds':[entry['content']['tableId'] for entry in page]},
                coverage={'partial':True,'truncated':False,'registeredTableCount':len(page),'totalEmptyTableCount':len(self.empty_json_tables)})
        attributes=self.asset.attributes
        if 'svgNative' in attributes:
            svg=attributes['svgNative']
            for page_index,page in enumerate(_partition(svg['elements'])):
                facts=[]
                for record in page:
                    locator={'svgElementId':record['elementId'],'elementPath':record['elementPath']}
                    source={key:record[key] for key in ('elementId','sourceElementId','elementPath','parentElementId','tag','attributes','references')}
                    facts.append(EvidenceFact(factId=fact_id(self.identity,locator,'source_record','element'),field='element',value=source,
                        evidenceNature='source_record',locator=locator,sourceValue=record['attributes'],calculationEligible=False))
                    for field in ('localTransform','worldTransform'):
                        facts.append(EvidenceFact(factId=fact_id(self.identity,locator,'program_calculation',field),field=field,
                            value=record[field],evidenceNature='program_calculation',locator=locator,calculationEligible=False))
                self.emit('svg_elements',{'elementPageIndex':page_index},{'elements':page,'viewport':svg['viewport']},facts,
                    operations=['read_representation'],coverage={'partial':len(page)<len(svg['elements']),'truncated':False,
                        'readElementCount':len(page),'totalElementCount':len(svg['elements'])})
            for record in svg['metadata']:
                facts=[]
                def declarations(value,pointer=''):
                    if isinstance(value,dict):
                        for key,child in value.items():declarations(child,pointer_child(pointer,key))
                    elif isinstance(value,list):
                        for index,child in enumerate(value):declarations(child,pointer_child(pointer,index))
                    else:
                        locator={'svgElementId':record['elementId'],'elementPath':record['elementPath'],'jsonPointer':pointer}
                        number=record.get('numberLexemes',{}).get(pointer,{})
                        facts.append(EvidenceFact(factId=fact_id(self.identity,locator,'source_declaration',pointer),field=pointer,value=value,
                            evidenceNature='source_declaration',locator=locator,sourceValue=value,numberSourceExpression=number.get('lexeme'),
                            calculationEligible=False,qualityFlags=['SOURCE_DECLARATION_NOT_GEOMETRIC_MEASUREMENT']))
                declarations(record['value'])
                self.emit('svg_metadata',{'svgElementId':record['elementId'],'elementPath':record['elementPath']},record,facts,
                    operations=['read_properties','read_representation'],nature='source_declaration',quality_flags=['SOURCE_DECLARATION_NOT_GEOMETRIC_MEASUREMENT'],
                    selector={'fields':list(record['value']) if isinstance(record['value'],dict) else []})
            views=[]
            for index,view in enumerate(attributes['svgViews'],1):
                views.append({**{key:value for key,value in view.items() if key!='path'},'outputPath':'/'+Path(view['path']).relative_to(self.output).as_posix(),
                    'imageId':stable_id(*identity_parts(self.identity),'svg-view',view['outputSha256']),
                    'legacyRepresentationId':f'{self.asset.asset_id}:visual:{index:05d}'})
            from .engineering_render import RECIPE,engine_binding
            x,y,w,h=svg['viewport']['viewBox']
            self.emit('svg_views',{'scope':'whole-source'},{'views':views,'viewport':svg['viewport'],'displayOnly':True},
                operations=['read_representation','render_engineering_image'],nature='program_rendering',
                selector={'renderRecipeVersion':RECIPE,'engineBinding':engine_binding('SVG'),'bounds':[x,y,x+w,y+h],
                    'coordinateSystem':'svg-root-user-space','allowedViews':['source-2d'],'allowedInstanceIds':[]})
        if 'meshSource' in attributes:
            for collection,kind,key in [('vertexRecords','mesh_vertices','vertexId'),('faceRecords','mesh_faces','faceId')]:
                records=attributes['meshSource'][collection]
                for page in _partition(records):
                    facts=[]
                    for record in page:
                        locator={'partId':'root','instanceId':'root',key:record[key],'line':record['line'],'indexBase':1}
                        facts.append(EvidenceFact(factId=fact_id(self.identity,locator,'source_record',kind),field=kind,
                            value=record,evidenceNature='source_record',locator=locator,sourceValue=record,calculationEligible=False))
                    self.emit(kind,{key+'Start':page[0][key],key+'End':page[-1][key],'indexBase':1}, {'records':page},facts,
                        operations=['read_representation'],coverage={'partial':len(page)<len(records),'truncated':False,
                            'readCount':len(page),'totalCount':len(records)},nature='source_record')
        if 'assemblyInstances' in attributes:
            records=attributes['assemblyInstances']
            properties={field:attributes[field] for field in ('leafInstanceCount','uniquePartDefinitionCount','assemblyNodeCount','assemblyUnionVolumeAvailable','sourceUnits','physicalUnitVerified')}
            facts=[]
            for field,value in properties.items():
                nature='source_declaration' if field=='sourceUnits' else 'program_calculation'
                facts.append(EvidenceFact(factId=fact_id(self.identity,{'scope':'whole-source','property':field},nature,field),field=field,value=value,
                    evidenceNature=nature,locator={'scope':'whole-source','property':field},sourceValue=value if nature=='source_declaration' else None,
                    calculationEligible=nature=='program_calculation' and isinstance(value,int) and not isinstance(value,bool)))
            self.emit('geometry_summary',{'scope':'whole-source'},{'properties':properties},facts,operations=['read_properties','read_representation'],nature='program_calculation',selector={'fields':list(properties)})
            for page_index,page in enumerate(_partition(records)):
                facts=[]
                for record in page:
                    locator={'instanceId':record['instanceId'],'assemblyPath':record['assemblyPath']}
                    facts.append(EvidenceFact(factId=fact_id(self.identity,locator,'program_calculation','instance'),field='instance',
                        value=record,evidenceNature='program_calculation',locator=locator,calculationEligible=False))
                self.emit('geometry_assembly',{'instancePageIndex':page_index},{'instances':page,
                    'leafInstanceCount':attributes['leafInstanceCount'],'uniquePartDefinitionCount':attributes['uniquePartDefinitionCount'],
                    'assemblyNodeCount':attributes['assemblyNodeCount'],'transformConvention':'world = parentWorld * local; column vectors; target millimetres'},
                    facts,operations=['read_representation'],nature='program_calculation',
                    coverage={'partial':len(page)<len(records),'truncated':False,'readInstanceCount':len(page),'totalInstanceCount':len(records)})
        if 'surfaceViews' in attributes:
            views=[]
            for index,view in enumerate(attributes['surfaceViews'],1):
                views.append({**{key:value for key,value in view.items() if key!='path'},
                    'outputPath':'/'+Path(view['path']).relative_to(self.output).as_posix(),
                    'imageId':stable_id(*identity_parts(self.identity),'surface-view',view['view'],view['outputSha256']),
                    'legacyRepresentationId':f'{self.asset.asset_id}:visual:{index:05d}',
                    'originalFormat':self.asset.source_format,'evidenceNature':'program_rendering'})
            from .engineering_render import RECIPE,engine_binding
            instance_ids=[row['instanceId'] for row in attributes.get('assemblyInstances',[]) if row['nodeType']=='leaf-instance'] if self.asset.source_format=='STEP' else ['root']
            self.emit('geometry_views',{'scope':'whole-source'},{'views':views,'displayOnly':True},operations=['read_representation','render_engineering_image'],nature='program_rendering',
                selector={'renderRecipeVersion':RECIPE,'engineBinding':engine_binding(self.asset.source_format),'bounds':[0,0,1,1],
                    'coordinateSystem':'registered-orthographic-reference-image-normalized','allowedViews':['isometric','front','side','top'],'allowedInstanceIds':instance_ids})
        if 'documentLayout' in attributes:self.document(attributes['documentLayout'])
        if 'nativeDocument' in attributes:self.json_tree(attributes['nativeDocument'],attributes.get('numberLexemes',{}))
        small=[]
        for doc in attributes.get('nativeDocuments',[]):
            if len(canonical(doc).encode())<=TARGET_BYTES:small.append(doc)
            else:self.json_tree(doc['value'],doc.get('numberLexemes',{}),line=doc['physicalLine'])
        for page in _partition(small):
            facts=[]
            for doc in page:
                locator={'jsonPointer':'','arrayIndexBase':0,'jsonlLine':doc['physicalLine']}
                facts.append(EvidenceFact(factId=fact_id(self.identity,locator,'source_record','value'),field='value',value=doc['value'],evidenceNature='source_record',locator=locator,sourceValue=doc['value']))
            self.emit('json_documents_page',{'jsonlLineStart':page[0]['physicalLine'],'jsonlLineEnd':page[-1]['physicalLine']},
                {'documents':page},facts,selector={'jsonlLines':[doc['physicalLine'] for doc in page]},
                coverage={'partial':len(page)<len(attributes['nativeDocuments']),'truncated':False,'documentCount':len(page),'totalDocumentCount':len(attributes['nativeDocuments'])},
                operations=['read_json_subtree','read_representation'])
        if 'workbook' in attributes:self.workbook(attributes['workbook'])
        manifest={'schemaVersion':EVIDENCE_SCHEMA_VERSION,'recipeVersion':EVIDENCE_RECIPE_VERSION,
            'identity':asdict(self.identity),'transforms':[dict(self.transform)],'representations':self.descriptors,
            'coverage':{'representationCount':len(self.descriptors),'nativeTreeComplete':attributes.get('nativeTreeComplete'),
                'nativeCellsComplete':attributes.get('nativeCellsComplete'),'documentCount':attributes.get('documentCount'),
                'physicalLineCount':attributes.get('physicalLineCount')},
            'unsupportedReasons':(['NATIVE_READ_REQUIRES_NEW_FORMAT_ADAPTER'] if not self.descriptors else [])}
        path=self.output/'evidence'/self.asset.asset_id/'manifest.json';path.parent.mkdir(parents=True,exist_ok=True)
        raw=canonical(manifest).encode();path.write_bytes(raw)
        return {'path':'/'+path.relative_to(self.output).as_posix(),'sha256':hashlib.sha256(raw).hexdigest(),
            'sizeBytes':len(raw),'representationCount':len(self.descriptors),'schemaVersion':EVIDENCE_SCHEMA_VERSION}
