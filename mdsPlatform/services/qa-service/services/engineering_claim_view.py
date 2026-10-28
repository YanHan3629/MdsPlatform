"""Human-readable exact field views for authenticated engineering properties."""
import re
from services.answer_lineage import LineageError,require
from services.engineering_evidence_plan import READ_VERSIONS

VERSION='engineering-property-presentation-2026-10-25.2'
LABELS={'bounds':'包围盒','surfaceArea':'表面积','volume':'体积','sourceVertexCount':'原始顶点数','sourceFaceCount':'原始面数',
    'closed':'网格闭合','orientationConsistent':'面方向一致','volumeValidation':'体积有效性与限制','measurementAlgorithm':'计算方法',
    'unitSource':'单位来源','physicalUnitVerified':'物理单位已确认','sourceUnits':'原始单位声明',
    'worldTransform':'世界变换矩阵','localTransform':'局部变换矩阵','leafInstanceCount':'叶实例数',
    'uniquePartDefinitionCount':'不同零件定义数','assemblyNodeCount':'装配节点数'}

def validate_source(source):
    from services.engineering_evidence_plan import _field_selected
    bundle=source['evidence_bundle'];provenance=source['provenance'];operation=provenance.get('nativeReadOperation',{})
    require(provenance.get('engineeringReadVersion') in READ_VERSIONS and not provenance.get('engineeringVisualRead'),
        'CLAIM_ENGINEERING_PROPERTY_RECIPE_UNSUPPORTED')
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
    residual=re.sub(re.escape(source['logical_path'].rsplit('/',1)[-1]),' ',question,flags=re.I)
    residual=re.sub(r'(?:不要|不用|不能|无需|不应|请勿|而非|而不是)[^，。；;！？?\n]*',' ',residual)
    context={'unit','simplification'} if source['original_format']=='SVG' else {'unitSource','physicalUnitVerified'}
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
    context={row['field']:row for row in bundle['facts']}
    support=[];unknown_units=False
    if kind in {'OBJ','STEP'} and field in {'bounds','surfaceArea','volume'}:
        confirmation=context.get('physicalUnitVerified');origin=context.get('unitSource')
        require(confirmation is not None and type(confirmation['value']) is bool and origin is not None,'CLAIM_ENGINEERING_UNIT_CONTEXT_REQUIRED')
        support=[confirmation['factId'],origin['factId']]
        unknown_units=not confirmation['value']
        require((fact.get('unit') is None)==unknown_units,'CLAIM_ENGINEERING_UNIT_CONTEXT_CONFLICT')
    location=('metadata '+str(locator.get('svgElementId') or 'geometry')) if kind=='SVG' else (
        '实例 '+str(locator['instanceId']) if locator.get('instanceId') else '整文件统计')
    return {'version':VERSION,'fileName':source['logical_path'].rsplit('/',1)[-1],'location':location,
        'fieldLabel':LABELS.get(field,field.lstrip('/') if kind=='SVG' else field),'unknownPhysicalUnits':unknown_units,'supportFactIds':support}

def render_text(node,display):
    info=node['engineeringView'];nature='源声明' if node['evidenceNature']=='source_declaration' else '程序计算'
    text=nature+' · '+info['fileName']+' · '+info['location']+' · '+info['fieldLabel']+' = '+display(node['value'])
    if node['unit'] is not None:text+=' '+{'mm2':'mm²','mm3':'mm³'}.get(node['unit'],str(node['unit']))
    if info['unknownPhysicalUnits']:text+='（几何工具坐标值，物理单位未确认）'
    return text
