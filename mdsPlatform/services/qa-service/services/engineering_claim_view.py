"""Human-readable exact field views for authenticated engineering properties."""
from decimal import Decimal
import re

from services.answer_lineage import LineageError,require
from services.engineering_evidence_plan import (
    READ_VERSIONS, _field_selected, checks_geometry_applicability, engineering_question_scope, geometry_applicability_fields,
    step_representation_requested, svg_graphic_fact_ids, svg_graphic_model_value, svg_graphic_coordinate_model_value, SVG_GRAPHIC_RECORD_RECIPE,
    SVG_GRAPHIC_MODEL_PROJECTION, svg_graphic_primitive_register, SVG_PRIMITIVE_REGISTER_RECIPE,
)

VERSION='engineering-property-presentation-2026-11-13.1'
LABELS={'bounds':'包围盒','surfaceArea':'表面积','volume':'体积','sourceVertexCount':'原始顶点数','sourceFaceCount':'原始面数',
    'closed':'网格闭合','orientationConsistent':'面方向一致','volumeValidation':'体积有效性与限制','measurementAlgorithm':'计算方法',
    'unitSource':'单位来源','physicalUnitVerified':'物理单位已确认','sourceUnits':'原始单位声明',
    'worldTransform':'世界变换矩阵','localTransform':'局部变换矩阵','leafInstanceCount':'叶实例数',
    'uniquePartDefinitionCount':'不同零件定义数','assemblyNodeCount':'装配节点数',
    'lengthUnit':'几何长度单位','shapeRepresentation':'正文几何表示',
    'flange_d':'法兰外径','bore_d':'主孔口径','flange_t':'法兰厚度',
    'radial_port_d':'径向接口口径','port_count':'径向接口数量','revision':'图纸版本',
    'simplification':'图纸适用边界','unit':'图纸声明单位','coordinate_system':'图纸坐标方向'}


def _validate_step_representation(source, bundle, operation):
    require(source['original_format']=='STEP' and operation=={'operation':'read_step_representation'}
            and bundle['clientReadReceipt']['operation']==operation
            and bundle.get('representationKind')=='step_representation_declarations'
            and step_representation_requested(source['provenance']['validatedEngineeringQuestion']),
            'CLAIM_STEP_REPRESENTATION_READ_CHANGED')
    content=bundle['content']; coverage=bundle['coverage']; facts=bundle['facts']
    representations=content['representations']
    require(1<=len(representations)<=16 and coverage['readEntityCount']==len(representations)
            and coverage['truncated'] is False and len(facts)==len(representations)+1
            and set(source['fact_ids'])=={fact['factId'] for fact in facts}
            and all(fact['evidenceNature']=='source_declaration' and fact['calculationEligible'] is False for fact in facts),
            'CLAIM_STEP_REPRESENTATION_READ_INCOMPLETE')
    unit, *shapes=facts
    require(unit['field']=='lengthUnit' and unit['value']==content['lengthUnit'] and unit['locator']['entityIds']
            and all(shape['field']=='shapeRepresentation' and shape['value']==entry['type']
                    and shape['locator']=={'entityId':entry['entityId'],'line':entry['line']}
                    for shape,entry in zip(shapes,representations)), 'CLAIM_STEP_REPRESENTATION_FACT_CHANGED')

def _validate_svg_graphics(source,bundle,operation):
    provenance=source['provenance']
    require(source['original_format']=='SVG'
            and operation=={'operation':'read_representation','representationId':bundle.get('representationId')}
            and operation==bundle['clientReadReceipt']['operation']
            and provenance.get('svgGraphicRecordRecipe') in {'svg-graphic-record-context-1','svg-graphic-record-context-2','svg-graphic-record-context-3','svg-graphic-record-context-4','svg-graphic-record-context-5','svg-graphic-record-context-6',SVG_GRAPHIC_RECORD_RECIPE}
            and provenance.get('requestedPropertyFields')==['element'], 'CLAIM_SVG_GRAPHIC_READ_CHANGED')
    expected=svg_graphic_fact_ids(bundle)
    recipe=provenance['svgGraphicRecordRecipe']
    limit=32 if recipe=='svg-graphic-record-context-1' else 128
    require(1<=len(expected)<=limit and source['fact_ids']==expected, 'CLAIM_SVG_GRAPHIC_SELECTION_CHANGED')
    facts={fact['factId']:fact for fact in bundle['facts']}
    require(provenance.get('svgGraphicElementIds')==[facts[key]['value']['elementId'] for key in expected],
            'CLAIM_SVG_GRAPHIC_LOCATORS_CHANGED')
    import json
    context=json.loads(source['model_context'])
    require(context.get('svgGraphicRecordRecipe')==recipe,
            'CLAIM_SVG_GRAPHIC_CONTEXT_CHANGED')
    if recipe in {'svg-graphic-record-context-3','svg-graphic-record-context-4','svg-graphic-record-context-5','svg-graphic-record-context-6',SVG_GRAPHIC_RECORD_RECIPE}:
        coordinate=recipe in {'svg-graphic-record-context-4','svg-graphic-record-context-5','svg-graphic-record-context-6',SVG_GRAPHIC_RECORD_RECIPE}
        project=svg_graphic_coordinate_model_value if coordinate else svg_graphic_model_value
        marker=SVG_GRAPHIC_MODEL_PROJECTION if coordinate else 'svg-type-and-style-projection-1'
        rows=[{'factId':key,'field':'element','value':project(facts[key]),'nature':'source_record'} for key in expected]
        require(context.get('svgGraphicModelProjection')==marker and context.get('facts')==rows,
                'CLAIM_SVG_GRAPHIC_PROJECTION_CHANGED')
    if recipe in {'svg-graphic-record-context-5','svg-graphic-record-context-6',SVG_GRAPHIC_RECORD_RECIPE}:
        register_recipe={'svg-graphic-record-context-5':'svg-primitive-declaration-index-1',
                         'svg-graphic-record-context-6':'svg-primitive-declaration-index-2'}.get(recipe,SVG_PRIMITIVE_REGISTER_RECIPE)
        expected_register=svg_graphic_primitive_register([facts[key] for key in expected],recipe=register_recipe)
        require(provenance.get('svgPrimitiveRegisterRecipe')==register_recipe
                and json.dumps(context.get('svgPrimitiveRegister'),sort_keys=True,separators=(',',':'),allow_nan=False)
                    ==json.dumps(expected_register,sort_keys=True,separators=(',',':'),allow_nan=False),
                'CLAIM_SVG_PRIMITIVE_REGISTER_CHANGED')


def validate_source(source):
    bundle=source['evidence_bundle'];provenance=source['provenance'];operation=provenance.get('nativeReadOperation',{})
    require(provenance.get('engineeringReadVersion') in READ_VERSIONS and not provenance.get('engineeringVisualRead'),
        'CLAIM_ENGINEERING_PROPERTY_RECIPE_UNSUPPORTED')
    if bundle.get('representationKind')=='svg_elements':
        _validate_svg_graphics(source,bundle,operation)
        return
    if bundle.get('recipeVersion')=='step-part21-representation-declarations-1':
        _validate_step_representation(source,bundle,operation)
        return
    require(bundle.get('recipeVersion')=='native-evidence-2026-10-22.2' and bundle.get('representationKind') in {'svg_metadata_projection','geometry_properties_projection','geometry_summary_projection'},
        'CLAIM_ENGINEERING_PROPERTY_REPRESENTATION_UNSUPPORTED')
    require(source['original_format'] in {'SVG','OBJ','STEP'} and operation.get('operation')=='read_properties'
        and operation.get('representationId')==bundle.get('sourceRepresentationId')
        and operation==bundle['clientReadReceipt']['operation'],'CLAIM_ENGINEERING_PROPERTY_READ_CHANGED')
    fields=provenance.get('requestedPropertyFields',[])
    require(fields and len(fields)==len(set(fields)) and fields==operation.get('fields'),'CLAIM_ENGINEERING_PROPERTY_FIELDS_CHANGED')
    coverage=bundle.get('coverage',{})
    require(coverage.get('fields')==fields and coverage.get('readPropertyCount')==len(fields) and not coverage.get('truncated'),
        'CLAIM_ENGINEERING_PROPERTY_READ_INCOMPLETE')
    question=provenance.get('validatedEngineeringQuestion','')
    require(isinstance(question,str) and question.strip(),'CLAIM_ENGINEERING_QUESTION_REQUIRED')
    residual = engineering_question_scope(question, source['logical_path'])
    source_format = source['original_format']
    context = {'unit', 'simplification'} if source_format == 'SVG' else {'unitSource', 'physicalUnitVerified'}
    if checks_geometry_applicability(source_format, residual):
        kind = bundle['representationKind'].removesuffix('_projection')
        context.update(geometry_applicability_fields(source_format, kind, bundle['locator']))
    require(all(field in context or _field_selected(field,residual) for field in fields),'CLAIM_ENGINEERING_UNREQUESTED_PROPERTY')
    facts=bundle.get('facts',[])
    require(facts and len(facts)<=32 and set(source['fact_ids'])=={fact['factId'] for fact in facts},'CLAIM_ENGINEERING_PROPERTY_FACTS_INCOMPLETE')
    for fact in facts:
        field=fact['field']
        roots=['/'+name.replace('~','~0').replace('/','~1') for name in fields]
        require(field in fields or any(field==root or field.startswith(root+'/') for root in roots),
            'CLAIM_ENGINEERING_PROPERTY_FIELD_OUTSIDE_READ')
        require(fact['evidenceNature'] in ({'source_declaration'} if source['original_format']=='SVG' else {'program_calculation','source_declaration'}),
            'CLAIM_ENGINEERING_PROPERTY_NATURE_CHANGED')

def view(source,fact):
    bundle=source['evidence_bundle'];kind=source['original_format'];locator=fact['locator'];field=fact['field']
    question=source['provenance'].get('validatedEngineeringQuestion','')
    context={row['field']:row for row in bundle['facts']}
    support=[];unknown_units=False
    if kind in {'OBJ','STEP'} and field in {'bounds','surfaceArea','volume'}:
        confirmation=context.get('physicalUnitVerified');origin=context.get('unitSource')
        require(confirmation is not None and type(confirmation['value']) is bool and origin is not None,'CLAIM_ENGINEERING_UNIT_CONTEXT_REQUIRED')
        support=[confirmation['factId'],origin['factId']]
        unknown_units=not confirmation['value']
        require((fact.get('unit') is None)==unknown_units,'CLAIM_ENGINEERING_UNIT_CONTEXT_CONFLICT')
    location=('图纸元数据 '+str(locator.get('svgElementId') or 'geometry')) if kind=='SVG' else (
        '实例 '+str(locator['instanceId']) if locator.get('instanceId') else '整文件统计')
    if bundle.get('representationKind')=='step_representation_declarations':
        location=('DATA 段 '+locator['entityId']+' 第 '+str(locator['line'])+' 行' if locator.get('entityId')
                  else '长度单位声明 '+ '、'.join(locator['entityIds']))
    if bundle.get('representationKind')=='svg_elements':
        location='SVG图元记录 '+str(locator['svgElementId'])
    display_field=field.lstrip('/') if kind=='SVG' else field
    return {'version':VERSION,'fileName':source['logical_path'].rsplit('/',1)[-1],'location':location,
        'fieldLabel':('图元类型及绘制属性投影' if source['provenance'].get('svgGraphicRecordRecipe') in {'svg-graphic-record-context-4','svg-graphic-record-context-5',SVG_GRAPHIC_RECORD_RECIPE}
                      else '图元原始声明') if bundle.get('representationKind')=='svg_elements' else LABELS.get(display_field,display_field),
        'unknownPhysicalUnits':unknown_units,'supportFactIds':support,
        'zEnvelopeRequested':kind=='OBJ' and field=='bounds' and fact['evidenceNature']=='program_calculation'
            and bool(re.search(r'(?<![A-Za-z])Z\s*(?:轴|向|方向)',question,re.I))
            and bool(re.search(r'包络|包围盒',question))}


def _z_envelope(value):
    if not isinstance(value,dict):
        return None
    vectors=[value.get(key) for key in ('minimum','maximum','size')]
    if any(not isinstance(vector,list) or len(vector)!=3 for vector in vectors):
        return None
    lower,upper,height=(vector[2] for vector in vectors)
    if any(type(term) not in {int,float} for term in (lower,upper,height)):
        return None
    low,high,span=(Decimal(str(term)) for term in (lower,upper,height))
    if not all(term.is_finite() for term in (low,high,span)) or low>high or span<0:
        return None
    # The native geometry tool computes size in float coordinates. Allow its
    # subtraction roundoff at the scale of those inputs, not a unit conversion.
    if abs((high-low)-span)>max(abs(low),abs(high),abs(span))*Decimal('2e-15'):
        return None
    return lower,upper,height

def render_text(node,display):
    info=node['engineeringView'];nature={'source_declaration':'源声明','source_record':'源记录'}.get(node['evidenceNature'],'程序计算')
    envelope = _z_envelope(node['value']) if info.get('zEnvelopeRequested') else None
    if envelope is not None:
        lower,upper,height=envelope
        unit=' '+str(node['unit']) if node['unit'] is not None else ''
        text=(f"程序计算 · {info['fileName']} · {info['location']} · Z 方向下界 {display(lower)}{unit}，"
              f"上界 {display(upper)}{unit}，包络高 {display(height)}{unit}（上界减下界，按网格源坐标轴对齐）")
        if info['unknownPhysicalUnits']:
            text+='。物理单位未确认，入库尺寸需核对单位和摆放方向'
        return text
    text=nature+' · '+info['fileName']+' · '+info['location']+' · '+info['fieldLabel']+' = '+display(node['value'])
    if node['unit'] is not None:text+=' '+{'mm2':'mm²','mm3':'mm³'}.get(node['unit'],str(node['unit']))
    if info['unknownPhysicalUnits']:text+='（几何工具坐标值，物理单位未确认）'
    return text
