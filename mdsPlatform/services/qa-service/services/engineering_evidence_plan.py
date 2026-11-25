"""Explicit, authorized source reads for static drawings and registered geometry."""
from __future__ import annotations
import hashlib,json,re,time
from decimal import Decimal, localcontext
from math import isfinite
from core.request_budget import CAPABILITIES

VERSION='engineering-explicit-read-2026-11-21.1'
READ_VERSIONS={'engineering-explicit-read-2026-10-22.1','engineering-explicit-read-2026-10-25.1',
               'engineering-explicit-read-2026-10-25.2','engineering-explicit-read-2026-10-25.3',
               'engineering-explicit-read-2026-11-13.1','engineering-explicit-read-2026-11-19.1',
               'engineering-explicit-read-2026-11-19.2','engineering-explicit-read-2026-11-19.3',
               'engineering-explicit-read-2026-11-19.4','engineering-explicit-read-2026-11-20.1',
               'engineering-explicit-read-2026-11-20.2',VERSION}
SVG_GRAPHIC_RECORD_RECIPE='svg-graphic-record-context-7'
SVG_GRAPHIC_MODEL_PROJECTION='svg-coordinate-declaration-projection-1'
SVG_PRIMITIVE_REGISTER_RECIPE='svg-primitive-declaration-index-3'
SVG_PAINT_ATTRIBUTES={'stroke','stroke-width','stroke-linecap','stroke-linejoin','stroke-dasharray','stroke-dashoffset',
    'stroke-opacity','fill','fill-opacity','fill-rule','opacity','display','visibility','vector-effect','color',
    'marker-start','marker-mid','marker-end','clip-path','clip-rule','mask','filter','class'}
SVG_GEOMETRY_ATTRIBUTES={'d','points','x','y','x1','x2','y1','y2','cx','cy','r','rx','ry','width','height','viewBox','transform'}
SVG_GRAPHIC_TAGS={'svg','g','circle','ellipse','rect','line','polyline','polygon','path','use'}
FIELDS={
    'bounds':r'包围盒|包络|长宽高|几何尺寸|bounding|\bbounds\b',
    'surfaceArea':r'表面积|表面面积|\bsurfaceArea\b',
    'volume':r'体积|\bvolume\b',
    'sourceVertexCount':r'顶点(?:数|数量)|\bsourceVertexCount\b',
    'sourceFaceCount':r'面(?:数|数量)|\bsourceFaceCount\b',
    'faceCount':r'面(?:数|数量)|\bfaceCount\b',
    'solidCount':r'实体(?:数|数量)|\bsolidCount\b',
    'closed':r'闭合|封闭|\bclosed\b',
    'orientationConsistent':r'方向(?:是否)?一致|面(?:的)?朝向|\borientationConsistent\b',
    'measurementAlgorithm':r'测量算法|计算方法|几何计算还是|显示网格估计|\bmeasurementAlgorithm\b',
    'volumeValidation':r'体积|闭合|封闭|有效性|\bvolumeValidation\b',
    'unitSource':r'单位|\bunitSource\b',
    'physicalUnitVerified':r'单位|\bphysicalUnitVerified\b',
    'sourceUnits':r'单位|\bsourceUnits\b',
    'localTransform':r'局部变换|\blocalTransform\b',
    'worldTransform':r'世界变换|全局变换|\bworldTransform\b',
    'leafInstanceCount':r'实例(?:数|数量)|装配.*(?:几|多少)|\bleafInstanceCount\b',
    'uniquePartDefinitionCount':r'不同零件|唯一零件|零件定义|\buniquePartDefinitionCount\b',
    'assemblyNodeCount':r'装配节点|\bassemblyNodeCount\b',
    'flange_d':r'法兰(?:外径|直径|[、，,]孔)|\bflange_d\b',
    'bore_d':r'内孔(?:孔径|直径)|主孔|法兰[、，,]孔|\bbore_d\b',
    'flange_t':r'法兰(?:的)?厚度|\bflange_t\b',
    'radial_port_d':r'径向(?:孔|接口|端口).{0,10}(?:直径|口径|孔径)|\bradial_port_d\b',
    'port_count':r'支路口数量|端口数量|(?:径向)?(?:接口|孔)数量|\bport_count\b',
    'revision':r'修订|版本|\brevision\b',
    'unit':r'单位|\bunit\b',
    'coordinate_system':r'坐标系|坐标(?:轴)?方向|\bcoordinate_system\b',
    'obj_scale':r'OBJ.*(?:比例|缩放)|\bobj_scale\b',
    'simplification':r'简化|限制|适用边界|生产放行|\bsimplification\b',
}
APPLICABILITY_FIELDS = ('bounds', 'sourceVertexCount', 'sourceFaceCount', 'solidCount', 'faceCount',
                        'leafInstanceCount', 'uniquePartDefinitionCount', 'measurementAlgorithm')
SVG_NOTE_REQUEST = re.compile(r'小字|细字|(?:备注|说明|注释)区|限制说明|放行说明')
SVG_LIMITATION_REQUEST = re.compile(r'简化|放行|适用边界|限制说明|仅供审阅')
SVG_LIMITATION_NOTE = re.compile(r'简化|生产放行|生产批准|仅供审阅|simplif|production release|for review only', re.I)
SVG_POSITION = re.compile(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?')
DRAWING_REVIEW_RECORD = re.compile(r'(?:填入|写入|录入|列入|填写|记入|用于)\s*(?:这份|一份|本次)?'
                                   r'(?:工程|图纸)?(?:审阅|审查|检验)记录')


def _field_selected(field,question):
    return bool(re.search(FIELDS.get(field,r'(?<![A-Za-z0-9_])'+re.escape(field)+r'(?![A-Za-z0-9_])'),question,re.I))


def engineering_question_scope(question, logical_path):
    residual = re.sub(re.escape(logical_path.rsplit('/', 1)[-1]), ' ', question, flags=re.I)
    return re.sub(r'(?:不要|不用|(?<!能)(?<!为何)(?<!为什么)不能|无需|不应|请勿|而非|而不是)[^，。；;！？?\n]*', ' ', residual)


def checks_geometry_applicability(source_format, question):
    return (source_format in {'OBJ', 'STEP'} and bool(re.search(r'能否|能不能|是否(?:可|能)|可否|能直接|可以直接', question)
            and re.search(r'作为|当作|依据|放行', question) and re.search(r'制造|加工|工艺|公差|检验', question)))


def geometry_applicability_fields(source_format, representation_kind, locator):
    whole_geometry = representation_kind == 'geometry_summary' or (
        source_format == 'OBJ' and representation_kind == 'geometry_properties' and locator.get('instanceId') in {None, 'root'}
    )
    return APPLICABILITY_FIELDS if whole_geometry else ()


def _step_cylinder_request(question):
    if not re.search(r'圆柱(?:曲)?面|cylindrical[_ ]surface', question, re.I):
        return None
    dimensions = [name for name, pattern in [('radius', r'半径|\bradius\b'), ('diameter', r'直径|\bdiameter\b')]
                  if re.search(pattern, question, re.I)]
    if not dimensions:
        return None
    labels = list(dict.fromkeys('#' + label for label in re.findall(r'#\s*([1-9][0-9]{0,17})(?![0-9])', question)))
    if not 1 <= len(labels) <= 8:
        raise ValueError('NEEDS_CLARIFICATION: 请提供一至八个明确的 STEP 圆柱面实体号。')
    return labels, dimensions


def step_representation_requested(question):
    return bool(re.search(r'几何表示|形状表示|(?:正文|原文).{0,12}表示(?:类型|方式)|\bshape representation\b', question, re.I))


def verify_step_cylinder_context(bundle, model_context, fact_ids):
    context = json.loads(model_context)
    facts = {fact['factId']: fact for fact in bundle['facts']}
    observed = {fact['factId']: fact for fact in context['facts']}
    if (context['locator'] != bundle['locator'] or set(facts) != set(fact_ids) or set(observed) != set(facts)
            or len(facts) != len(bundle['facts']) or len(observed) != len(context['facts'])
            or context['lengthUnitEntityIds'] != bundle['content']['lengthUnitEntityIds']):
        raise ValueError('STEP_CYLINDER_CONTEXT_CHANGED')
    for identifier, fact in facts.items():
        frame = observed[identifier]
        expected = format(Decimal(str(fact['numberSourceExpression'])), 'f')
        if (frame['field'], frame['value'], frame.get('measurementUnit'), frame['nature']) != (
                fact['field'], expected, fact['unit'], fact['evidenceNature']):
            raise ValueError('STEP_CYLINDER_CONTEXT_CHANGED')
    return facts


def _instance_targets(question,available):
    selected=[value for value in available if re.search(r'(?<![A-Za-z0-9:/])'+re.escape(value)+r'(?![A-Za-z0-9:/])',question)]
    ordinal=re.search(r'第\s*(\d+|一|二|三|四|五|六|七|八|九|十)\s*个实例',question)
    if ordinal:
        small=dict(zip('一二三四五六七八九十',range(1,11)));position=int(ordinal[1]) if ordinal[1].isdigit() else small[ordinal[1]]
        if not 1<=position<=len(available):raise ValueError('NEEDS_CLARIFICATION: 指定实例序号不在冻结实例清单中。')
        selected=[available[position-1]]
    elif re.search(r'第[^，。；;？?\n]{1,12}个实例',question):raise ValueError('NEEDS_CLARIFICATION: 请明确一个可解析的实例序号。')
    return selected


def _region_parameters(question,source_format,descriptor,view):
    selector=descriptor.get('selector',{})
    if selector.get('renderRecipeVersion')!='engineering-region-render-2026-10-22.1':raise ValueError('EVIDENCE_INDEX_REBUILD_REQUIRED: 该冻结索引未注册工程局部渲染。')
    region=[.2,.2,.8,.8];region_name='中央选区'
    for pattern,box,name in [(r'右侧|右部',[.5,0,1,1],'右侧'),(r'左侧|左部',[0,0,.5,1],'左侧'),
        (r'上部|上半',[0,0,1,.6],'上部'),(r'下部|下半',[0,.4,1,1],'下部')]:
        if re.search(pattern,question):region=box;region_name=name;break
    if source_format=='SVG':
        x0,y0,x1,y1=selector['bounds'];region=[x0+region[0]*(x1-x0),y0+region[1]*(y1-y0),x0+region[2]*(x1-x0),y0+region[3]*(y1-y0)]
    available=selector.get('allowedInstanceIds',[]);selected=_instance_targets(question,available)
    highlight=bool(re.search(r'高亮',question))
    if highlight and not selected:
        if len(available)==1:selected=available[:]
        else:raise ValueError('NEEDS_CLARIFICATION: 高亮需要明确实例或冻结清单中的实例序号。')
    if len(selected)>8:raise ValueError('EVIDENCE_ENGINEERING_INSTANCE_LIMIT')
    parameters={'view':view.get('view','source-2d'),'bbox':region,'maxPixels':1_000_000,
        'instanceIds':selected if not highlight else [],'highlightInstanceIds':selected if highlight else []}
    return parameters,region_name


def _svg_note_region(read, representations, bounds, remaining_reads):
    """Locate limitation notes in registered visible text and root coordinates."""
    passages = [rep for rep in representations if rep['kind'] == 'text_passage'
                and rep.get('locator', {}).get('svgElementId')]
    pages = [rep for rep in representations if rep['kind'] == 'svg_elements']
    if not passages or not pages or len(passages) + len(pages) > remaining_reads:
        raise ValueError('EVIDENCE_ENGINEERING_NOTE_REGION_UNAVAILABLE: 请明确图纸说明所在的方向或区域。')

    notes = {}
    for descriptor in passages:
        bundle = read({'operation': 'read_representation', 'representationId': descriptor['representationId']})
        text = bundle['content']['text']
        if len(text) <= 512 and SVG_LIMITATION_NOTE.search(text):
            if bundle['locator'] != descriptor['locator'] or bundle['representationId'] != descriptor['representationId']:
                raise ValueError('EVIDENCE_ENGINEERING_NOTE_SOURCE_MISMATCH')
            notes.setdefault(bundle['locator']['svgElementId'], []).append(bundle)

    left, top, right, bottom = bounds
    positions, inputs, excerpts = [], [], []
    for descriptor in pages if notes else ():
        page = read({'operation': 'read_representation', 'representationId': descriptor['representationId']})
        if page['representationId'] != descriptor['representationId']:
            raise ValueError('EVIDENCE_ENGINEERING_NOTE_SOURCE_MISMATCH')
        for element in page['content']['elements']:
            matching = notes.get(element['elementId'])
            if not matching or element.get('tag') != 'text':
                continue
            attributes = element['attributes']
            x, y = (attributes.get(axis, '') for axis in ('x', 'y'))
            if not SVG_POSITION.fullmatch(x) or not SVG_POSITION.fullmatch(y):
                continue
            transform = element['worldTransform']
            x, y = [row[0] * float(x) + row[1] * float(y) + row[2] for row in transform[:2]]
            if not (isfinite(x) and isfinite(y) and left <= x <= right and top <= y <= bottom):
                continue
            positions.append(y)
            inputs.extend([page, *matching])
            excerpts.extend(bundle['content']['text'] for bundle in matching)

    if not positions:
        raise ValueError('EVIDENCE_ENGINEERING_NOTE_REGION_UNAVAILABLE: 当前图纸没有可定位的限制说明，请明确所需区域。')
    height = bottom - top
    low, high = max(top, min(positions) - .13 * height), min(bottom, max(positions) + .08 * height)
    if high - low > .45 * height or sum(map(len, excerpts)) > 1024:
        raise ValueError('EVIDENCE_ENGINEERING_NOTE_REGION_UNAVAILABLE: 图纸说明跨越多个区域，请明确所需区域。')
    return [left, low, right, high], list(dict.fromkeys(excerpts)), inputs


def svg_graphic_fact_ids(bundle):
    """Bind declared graphic records to native element facts, without interpreting them."""
    if (bundle.get('recipeVersion')!='native-evidence-2026-10-22.2'
            or bundle.get('representationKind')!='svg_elements'
            or bundle.get('identity',{}).get('originalFormat')!='SVG'):
        raise ValueError('EVIDENCE_SVG_GRAPHIC_REPRESENTATION_CHANGED')
    records=bundle.get('content',{}).get('elements',[])
    coverage=bundle.get('coverage',{})
    if (not isinstance(records,list) or not 1<=len(records)<=128
            or any(not isinstance(record,dict) for record in records)
            or type(coverage.get('readElementCount')) is not int or coverage['readElementCount']!=len(records)
            or type(coverage.get('totalElementCount')) is not int
            or coverage['totalElementCount']<len(records) or coverage.get('truncated') is not False):
        raise ValueError('EVIDENCE_SVG_GRAPHIC_COVERAGE_CHANGED')
    by_element={}
    for fact in bundle.get('facts',[]):
        if fact.get('field')!='element':continue
        element_id=fact.get('locator',{}).get('svgElementId')
        if not element_id or element_id in by_element:
            raise ValueError('EVIDENCE_SVG_GRAPHIC_FACT_DUPLICATE')
        by_element[element_id]=fact
    identifiers=[record.get('elementId') for record in records]
    if not all(identifiers) or len(set(identifiers))!=len(records):
        raise ValueError('EVIDENCE_SVG_GRAPHIC_RECORD_DUPLICATE')
    selected=[]
    keys=('elementId','sourceElementId','elementPath','parentElementId','tag','attributes','references')
    for record in records:
        if (record.get('coordinateSpace') not in {'definition','root-user-space'}
                or not isinstance(record.get('attributes'),dict) or not isinstance(record.get('references'),list)):
            raise ValueError('EVIDENCE_SVG_GRAPHIC_RECORD_CHANGED')
        if record.get('tag') not in SVG_GRAPHIC_TAGS or record['coordinateSpace']=='definition':continue
        fact=by_element.get(record['elementId'])
        if (not fact or any(key not in record for key in keys)
                or fact.get('evidenceNature')!='source_record' or fact.get('calculationEligible') is not False
                or fact.get('value')!={key:record[key] for key in keys}
                or fact.get('sourceValue')!=record['attributes']
                or fact.get('locator')!={'svgElementId':record['elementId'],'elementPath':record['elementPath']}):
            raise ValueError('EVIDENCE_SVG_GRAPHIC_FACT_CHANGED')
        selected.append(fact['factId'])
    return selected


def svg_graphic_model_value(fact):
    """Project declarations used with the actual image; retain the native body separately."""
    record=fact['value'];attributes=record['attributes']
    if (not isinstance(record.get('elementId'),str) or not isinstance(record.get('tag'),str)
            or record.get('parentElementId') is not None and not isinstance(record['parentElementId'],str)
            or record.get('sourceElementId') is not None and not isinstance(record['sourceElementId'],str)
            or any(not isinstance(k,str) or not isinstance(v,str) for k,v in attributes.items())
            or not isinstance(record.get('references'),list) or any(not isinstance(ref,str) for ref in record['references'])):
        raise ValueError('EVIDENCE_SVG_GRAPHIC_PROJECTION_TYPES_CHANGED')
    key=lambda value:'g:'+hashlib.sha256(value.encode()).hexdigest()[:24] if value is not None else None
    paint={name:value for name,value in attributes.items()
           if name in SVG_PAINT_ATTRIBUTES and isinstance(value,str) and len(value)<=256}
    styles=[];long_styles=0
    for declaration in attributes.get('style','').split(';'):
        if not declaration.strip():continue
        name,separator,value=declaration.partition(':');name=name.strip();value=value.strip()
        if separator and len(name)<=64 and len(value)<=256 and len(styles)<32:styles.append({'property':name,'value':value})
        else:long_styles+=1
    references=record['references'];source_id=record['sourceElementId']
    return {'recordKey':key(record['elementId']),'parentKey':key(record['parentElementId']),'tag':record['tag'],
            'sourceId':source_id if source_id is None or len(source_id)<=256 else None,
            'attributes':paint,'inlineStyle':styles,'referenceKeys':[key(ref) for ref in references[:16]],
            'omitted':{'geometryAttributes':sorted(SVG_GEOMETRY_ATTRIBUTES.intersection(attributes)),
                       'otherAttributesOmitted':bool(set(attributes)-set(paint)-SVG_GEOMETRY_ATTRIBUTES-{'style'}),
                       'styleDeclarationsOmitted':bool(long_styles),'referencesOmitted':len(references)>16,
                       'sourceLocatorKeptInNativeReceipt':True}}


def svg_graphic_coordinate_model_value(fact):
    """Add bounded, complete source declarations, without deriving physical shapes."""
    projected=svg_graphic_model_value(fact)
    attributes=fact['value']['attributes'];geometry={};remaining=512
    # Scalar coordinates take precedence over potentially large path/point data.
    names=sorted(SVG_GEOMETRY_ATTRIBUTES-{'d','points'})+['d','points']
    for name in names:
        if name not in attributes:continue
        value=attributes[name];limit=512 if name in {'d','points'} else 128
        if len(value)<=limit and len(value)<=remaining:
            geometry[name]=value;remaining-=len(value)
    projected['geometryDeclarations']=geometry
    projected['omitted']['geometryAttributes']=sorted(SVG_GEOMETRY_ATTRIBUTES.intersection(attributes)-set(geometry))
    # This is a lexical inventory, not a path validator or a contour classifier.
    # Numbers are tokenized first so exponent letters are never drawing commands.
    if 'd' in attributes:
        path=attributes['d']
        if len(path)>65536:
            projected['pathCommandInventory']={'state':'OMITTED_SIZE_LIMIT'}
        else:
            tokens=re.findall(r'[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?|[MmZzLlHhVvCcSsQqTtAa]|[^\s,]',path)
            commands=sorted({token for token in tokens if re.fullmatch(r'[MmZzLlHhVvCcSsQqTtAa]',token)})
            invalid=any(not re.fullmatch(r'[MmZzLlHhVvCcSsQqTtAa]|[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?',token) for token in tokens)
            projected['pathCommandInventory']=({'state':'UNAVAILABLE_INVALID_TOKEN'} if invalid else
                {'state':'COMPLETE_LEXICAL_INVENTORY','letters':commands,'syntaxValidated':False})
    return projected


def svg_single_straight_path_declaration(path):
    """Decode only a fully bounded moveto plus one straight segment (SVG 2)."""
    if not isinstance(path,str) or not 1<=len(path)<=512:
        return None
    # Longest-match numeric tokens prevent backtracking from splitting 123 into
    # several coordinates. Only SVG whitespace and numeric comma separators fit.
    # Put the decimal alternative first so 0.5 is one maximal numeric token.
    number=r'[+-]?(?:[0-9]*\.[0-9]+|[0-9]+)(?:[eE][+-]?[0-9]+)?'
    tokens=[];position=0;previous_number=False
    for match in re.finditer(number+r'|[MmLlHhVv]',path):
        token=match.group();numeric=bool(re.fullmatch(number,token))
        gap=path[position:match.start()]
        if not re.fullmatch(r'[ \t\r\n\f]*',gap):
            if not (previous_number and numeric and re.fullmatch(r'[ \t\r\n\f]*,[ \t\r\n\f]*',gap)):
                return None
        tokens.append(token);position=match.end();previous_number=numeric
    if not re.fullmatch(r'[ \t\r\n\f]*',path[position:]) or not tokens or tokens[0] not in {'M','m'}:
        return None
    if len(tokens)==6 and tokens[3] in {'L','l'}:
        command=tokens[3];lexemes=tokens[1:3]+tokens[4:]
    elif len(tokens)==5 and tokens[3] in {'H','h','V','v'}:
        command=tokens[3];lexemes=tokens[1:3]+tokens[4:]
    elif len(tokens)==5 and all(re.fullmatch(number,t) for t in tokens[1:]):
        command='L' if tokens[0]=='M' else 'l';lexemes=tokens[1:]
    else:
        return None
    if any(not re.fullmatch(number,t) or len(t)>128 for t in lexemes):
        return None
    for token in lexemes:
        exponent=re.search(r'[eE]([+-]?[0-9]+)$',token)
        if exponent and abs(int(exponent.group(1)))>128:
            return None
    with localcontext() as context:
        context.prec=512
        values=[Decimal(t) for t in lexemes]
        if any(not value.is_finite() or abs(value)>Decimal('1e12') for value in values):
            return None
        x,y=values[:2]
        if command in {'L','l'}:
            ex,ey=values[2:]
            if command=='l':ex+=x;ey+=y
        elif command in {'H','h'}:
            ex=values[2]+(x if command=='h' else 0);ey=y
        else:
            ex=x;ey=values[2]+(y if command=='v' else 0)
        def coordinate(value):
            return '0' if not value else format(value.normalize(),'f')
        endpoints={'x1':coordinate(x),'y1':coordinate(y),'x2':coordinate(ex),'y2':coordinate(ey)}
    return {'pathDataSha256':hashlib.sha256(path.encode()).hexdigest(),
            'syntax':'complete-moveto-plus-single-straight-segment-svg2',
            'segmentCommand':command,'sourceLocalEndpoints':endpoints,
            'endpointBasis':'program-decoded-full-source-path; initial-moveto-origin-zero; inherited-transforms-not-expanded'}


def svg_graphic_primitive_register(facts, *, recipe=SVG_PRIMITIVE_REGISTER_RECIPE):
    """Index native element types, without classifying visible/business objects."""
    if (recipe not in {'svg-primitive-declaration-index-1','svg-primitive-declaration-index-2',SVG_PRIMITIVE_REGISTER_RECIPE}
            or not isinstance(facts,list) or not 1<=len(facts)<=128):
        raise ValueError('EVIDENCE_SVG_PRIMITIVE_REGISTER_FACTS_CHANGED')
    # SVG 2 basic shapes and paths. These are source-format meanings, not a
    # vocabulary imposed on the model answer or an inventory of visible shapes.
    meanings={
        'line':'两端点间的单段直线声明，含退化情况；标签本身不证明可见闭合轮廓。',
        'polyline':'相邻点连接成的折线声明，不自动执行closepath。',
        'polygon':'首尾闭合的折线声明，不保证轮廓不自交、实际可见或对应实物边界。',
        'rect':'矩形声明，可有圆角；须核对完整几何属性和实际图像。',
        'circle':'圆形基础图形声明；可见性及其对应部位仍须核对图像。',
        'ellipse':'椭圆基础图形声明；可见性及其对应部位仍须核对图像。',
        'path':'通用路径，可含直线、曲线及多子路径；命令字母清单不证明具体轮廓或闭合情况。',
    }
    grouped={tag:[] for tag in meanings};other=[];seen=set()
    for fact in facts:
        if not isinstance(fact,dict):
            raise ValueError('EVIDENCE_SVG_PRIMITIVE_REGISTER_FACTS_CHANGED')
        identifier=fact.get('factId');value=fact.get('value')
        if not isinstance(value,dict):
            raise ValueError('EVIDENCE_SVG_PRIMITIVE_REGISTER_FACTS_CHANGED')
        tag=value.get('tag')
        if (fact.get('field')!='element' or not isinstance(identifier,str) or not identifier
                or identifier in seen or not isinstance(tag,str) or tag not in SVG_GRAPHIC_TAGS):
            raise ValueError('EVIDENCE_SVG_PRIMITIVE_REGISTER_FACTS_CHANGED')
        seen.add(identifier)
        (grouped[tag] if tag in grouped else other).append(identifier)
    result={'recipe':recipe,
            'scope':'selected-source-declarations-not-visible-object-census',
            'groups':[{'elementType':tag,'declarationMeaning':meaning,'factIds':sorted(grouped[tag])}
                      for tag,meaning in meanings.items() if grouped[tag]],
            'otherDeclarationFactIds':sorted(other),
            'pathContoursClassified':False,'crossRecordRelationshipsInferred':False,
            'visibilityVerified':False,'physicalShapesInferred':False}
    if recipe=='svg-primitive-declaration-index-1':
        return result
    by_id={fact['factId']:fact for fact in facts}
    for group in result['groups']:
        group['declarationCount']=len(group['factIds'])
    lines=[]
    for identifier in sorted(grouped['line']):
        projected=svg_graphic_coordinate_model_value(by_id[identifier])
        geometry=projected['geometryDeclarations']
        lines.append({'factId':identifier,'parentKey':projected['parentKey'],
                      'endpointAttributes':{key:geometry[key] for key in ('x1','y1','x2','y2') if key in geometry},
                      'localTransformDeclaration':geometry.get('transform')})
    straight_paths=[]
    for identifier in sorted(grouped['path']):
        projected=svg_graphic_coordinate_model_value(by_id[identifier])
        geometry=projected['geometryDeclarations']
        decoded=svg_single_straight_path_declaration(geometry.get('d'))
        if decoded is not None:
            straight_paths.append({'factId':identifier,'parentKey':projected['parentKey'],
                'localTransformDeclaration':geometry.get('transform'),**decoded})
    result.update(countEvidenceNature='program-count-of-authenticated-selected-records',
                  countCalculation='len(factIds selected by original element tag)',
                  countScope='element-declarations-not-visible-objects-groups-or-painted-dash-marks',
                  lineDeclarations=lines,
                  singleStraightPathDeclarations=straight_paths,
                  singleStraightPathDeclarationCount=len(straight_paths),
                  pathDecodingScope='recognized-complete-bounded-single-segment-path-subset; unrecognized-is-not-absent-or-invalid',
                  endpointCoordinateScope='bounded-source-local-attributes; missing-is-not-zero; inherited-transforms-not-expanded',
                  globalEndpointCoordinatesDerived=False,paintedDashMarksCounted=False)
    if recipe == 'svg-primitive-declaration-index-2':
        return result
    projected_by_key={svg_graphic_model_value(fact)['recordKey']:(fact,svg_graphic_model_value(fact)) for fact in facts}
    guides=[]
    for declaration in [*lines,*straight_paths]:
        identifier=declaration['factId'];projected=svg_graphic_coordinate_model_value(by_id[identifier])
        chain=[];seen_keys=set();key=projected['recordKey']
        while key in projected_by_key and key not in seen_keys and len(chain)<16:
            fact,value=projected_by_key[key];seen_keys.add(key)
            chain.append({'factId':fact['factId'],'tag':value['tag'],
                          'paintAttributes':value['attributes'],'inlineStyle':value['inlineStyle']})
            key=value['parentKey']
        decoded=declaration.get('syntax')=='complete-moveto-plus-single-straight-segment-svg2'
        guides.append({'factId':identifier,'sourceCenterlineKind':'single-straight-segment',
            'classificationBasis':'complete-bounded-path-decoding' if decoded else 'native-line-element',
            'closedCenterline':False,
            'declarationMeaning':'源几何中心线为单段直线；不是闭合圆形中心线。退化、端帽及虚线绘制外形仍须对照原图。',
            'paintDeclarationChainFromElementToAncestors':chain,
            'ancestorDeclarationsComplete':key is None,
            'effectivePaintOrVisibilityComputed':False})
    result.update(straightCenterlineGuides=guides,
        straightCenterlineGuideScope='source-path-geometry-and-linked-ancestor-declarations-only; not-visible-shape-or-physical-part-classification',
        paintChainScope='bounded-registered-element-to-ancestor-declarations; CSS-cascade-defaults-references-clipping-and-rendering-not-expanded')
    return result


def _svg_graphic_items(read,scope,asset,metadata,question,remaining_reads):
    pages=[rep for rep in metadata['representations'] if rep['kind']=='svg_elements']
    report={'registeredPages':len(pages),'readPages':0,'omittedPages':0,'selectedElements':0}
    if not pages:return [],{**report,'state':'UNAVAILABLE'}
    if len(pages)>min(4,remaining_reads):return [],{**report,'state':'READ_BUDGET_LIMIT','omittedPages':len(pages)}
    items=[]
    for descriptor in pages:
        operation={'operation':'read_representation','representationId':descriptor['representationId']}
        bundle=read(operation)
        if bundle.get('representationId')!=descriptor['representationId']:
            raise ValueError('EVIDENCE_SVG_GRAPHIC_SOURCE_CHANGED')
        report['readPages']+=1
        fact_ids=svg_graphic_fact_ids(bundle)
        if not fact_ids:continue
        item=_item(scope,asset,bundle,question,operation,fields=['element'],fact_ids=fact_ids,svg_graphic_page=True)
        originals={fact['factId']:fact for fact in bundle['facts']}
        item['provenance'].update(svgGraphicRecordRecipe=SVG_GRAPHIC_RECORD_RECIPE,
            svgGraphicElementIds=[originals[key]['value']['elementId'] for key in fact_ids])
        context=json.loads(item['nativeModelContext'])
        context.update(svgGraphicRecordRecipe=SVG_GRAPHIC_RECORD_RECIPE)
        context['facts']=[{'factId':key,'field':'element','value':svg_graphic_coordinate_model_value(originals[key]),'nature':'source_record'}
                          for key in fact_ids]
        # Put the compact type-to-fact index before the detailed record frames.
        # Every link is subsequently checked against the authenticated native read.
        register=svg_graphic_primitive_register([originals[key] for key in fact_ids])
        context={'svgPrimitiveRegister':register,**context}
        item['provenance']['svgPrimitiveRegisterRecipe']=SVG_PRIMITIVE_REGISTER_RECIPE
        context['svgGraphicModelProjection']=SVG_GRAPHIC_MODEL_PROJECTION
        context['图元记录范围']='所列是每个登记图元的类型、绘制属性及有界原文坐标声明；geometryDeclarations只含完整属性值，超限值整项省略，完整原文保留在读取证据中。'
        context['图元解释边界']='渲染可见性仍须核对实际图像；图元数不等于实物孔数，图纸用户坐标不构成物理尺寸。'
        context['投影说明']='recordKey和parentKey是定位摘要；它们与sourceId只用于关联原记录，不能作为部件身份或实物编号。omitted说明未入模字段。pathCommandInventory仅列原文命令字母，未验证路径语法或推导形状。结合坐标、变换和线型逐项核对实际图像中的轮廓，不能从元数据数量或接口名称推断轮廓形状；无法对应时说明不确定性。图纸坐标不得当作物理尺寸。'
        context['基础图形索引边界']='svgPrimitiveRegister按原始标签关联factId，不统计可见对象或实物。先定位基础图形声明，再核对所链接记录的端点、曲线、变换和绘制属性及实际图像。索引说明的是几何路径类别，不是端帽、虚线段、填充或标记的最终可见外形；不得据此断言path中没有其他形状。'
        context['计数对象边界']='declarationCount程序清点对应原始标签的登记条目；lineDeclarations列出line局部端点声明；singleStraightPathDeclarations仅列完整有界路径中已验证的单段直线及程序解码的局部端点，其计数只覆盖该子集，其他path不能推断为不存在或无效。这些不是原文实物数量或图像可见数量，不清点虚线短划、分组、方向或接口。可见边界须逐条对应原记录与图像，重合、隐藏、裁剪及继承变换仍需核对；分组不能改写成条或段，未被原问要求的可见计数不要额外补出。'
        item['nativeModelContext']=json.dumps(context,ensure_ascii=False,separators=(',',':'))
        item['nativeHumanContext']=json.dumps({'svgPrimitiveRegister':register,'facts':context['facts'],'scope':'图元类型、绘制属性及有界坐标声明投影'},ensure_ascii=False,separators=(',',':'))
        from services.evidence_trace import current
        if current():
            current().event('svgGraphicModelProjection',{'recipe':SVG_GRAPHIC_MODEL_PROJECTION,
                'readId':bundle['readId'],'representationId':bundle['representationId'],'selectedRecords':len(fact_ids),
                'primitiveRegisterRecipe':SVG_PRIMITIVE_REGISTER_RECIPE,'primitiveRegisterFactLinks':len(fact_ids),
                'nativeValueCharacters':sum(len(json.dumps(originals[key]['value'],ensure_ascii=False)) for key in fact_ids),
                'projectedValueCharacters':sum(len(json.dumps(row['value'],ensure_ascii=False)) for row in context['facts']),
                'contextCharacters':len(item['nativeModelContext'])})
        items.append(item);report['selectedElements']+=len(fact_ids)
    return items,{**report,'state':'PARTIAL' if report['omittedPages'] else 'AVAILABLE'}


def _item(scope,asset,bundle,question,operation,*,fields=(),view=None,metadata=None,fact_ids=None,svg_graphic_page=False):
    roots=['/'+field.replace('~','~0').replace('/','~1') for field in fields]
    facts=[fact for fact in bundle['facts'] if not fields or fact['field'] in fields or any(fact['field']==root or fact['field'].startswith(root+'/') for root in roots)] if view is None else []
    if fact_ids is not None:
        if view is not None or not fact_ids or len(fact_ids)!=len(set(fact_ids)) or not set(fact_ids)<={fact['factId'] for fact in facts}:
            raise ValueError('EVIDENCE_ENGINEERING_FACT_SELECTION_CHANGED')
        facts=[fact for fact in facts if fact['factId'] in set(fact_ids)]
    if svg_graphic_page and (view is not None or fields!=['element']
            or bundle.get('representationKind')!='svg_elements' or bundle['identity']['originalFormat']!='SVG'
            or fact_ids!=svg_graphic_fact_ids(bundle)):
        raise ValueError('EVIDENCE_SVG_GRAPHIC_PAGE_SELECTION_CHANGED')
    # A registered SVG page has at most128 records. Other engineering reads keep
    # their32-fact bound; neither native registration nor request budgets expand.
    if len(facts)>(128 if svg_graphic_page else 32):
        raise ValueError('EVIDENCE_ENGINEERING_FACT_LIMIT: 请缩小所问属性或实例范围。')
    model=[{'factId':fact['factId'],'field':fact['field'],'value':fact.get('numberSourceExpression') if fact.get('numberSourceExpression') is not None else fact['value'],
        **({'measurementUnit':fact['unit']} if fact.get('unit') is not None else {}),'nature':fact['evidenceNature']} for fact in facts]
    context={'facts':model,'locator':bundle['locator']}
    if view is not None:context['定位说明']='视角和选区坐标用于定位，不是物理尺寸。'
    if bundle['identity']['originalFormat']=='OBJ':context['单位说明']='仅采用冻结的单位声明；未声明时单位未知，不可默认为毫米。网格自交尚未认证。'
    if bundle['identity']['originalFormat']=='SVG':context['性质说明']='图纸文字与metadata是源声明，不能充当三维几何实测。'
    if view is not None:
        context.update(visualObservationRequired=True,view=view.get('view','full-svg'),displayColoursNotSourceMaterial=True)
        if bundle['identity']['originalFormat']=='SVG':
            context.update(sourceLabelsAreDeclarations=True)
            context['图像说明']='可以读取图纸明确标注的文字和数值，其性质是源声明，不是三维实测；不能据此推断真实材质或性能。'
        else:
            context.update(exactDimensionsMustComeFromGeometryFacts=True)
            context['图像说明']='显示配色由程序生成，不能据此推断源材质或真实颜色；图像本身不能给出精确几何测量。'
    return {'assetId':asset['assetId'],'dataVersion':str(scope[1]),'indexVersion':str(scope[2]),'evidenceId':bundle['evidenceId'],
        'semanticSpace':'native.engineering.read','locator':{**bundle['locator'],**({'view':view.get('view','full-svg')} if view is not None else {})},
        'facts':{'text':json.dumps(bundle['content'],ensure_ascii=False,separators=(',',':'))},'scoreType':'verified_engineering_read',
        'evidenceType':'constraint','qualityFlags':bundle['qualityFlags'],'preview':{'logicalPath':asset['logicalPath']},
        'nativeBundle':bundle,'modelFactIds':[fact['factId'] for fact in facts],
        'nativeHumanContext':json.dumps({'facts':model,'view':view.get('view','full-svg') if view is not None else None},ensure_ascii=False,separators=(',',':')),
        'nativeModelContext':json.dumps(context,ensure_ascii=False,separators=(',',':')),
        **({'nativeRegisteredView':view,'nativeImageMetadata':metadata} if view is not None else {}),
        'provenance':{'scope':asset['scope'],'originalFormat':asset['originalFormat'],'engineeringReadVersion':VERSION,
            'validatedEngineeringQuestion':question,'explicitSourcePath':asset['logicalPath'],'sourceSha256':bundle['identity']['sourceSha256'],
            'rawCommitId':bundle['identity']['rawCommitId'],'nativeReadId':bundle['readId'],'nativeReadOperation':operation,
            'readOutputSha256':bundle['readOutputSha256'],'representationId':bundle['representationId'],'representationKind':bundle['representationKind'],
            'engineeringVisualRead':view is not None,'requestedPropertyFields':list(fields),
            'modelFactSelection':{'selectedFactIds':[fact['factId'] for fact in facts],'readFactIds':[fact['factId'] for fact in bundle['facts']]}}}


def requested_engineering_format(question):
    question = re.sub(r'\bstep[- ]by[- ]step\b|(?:输出|导出|保存)(?:为)?\s*(?:SVG|OBJ|STEP|STP)\b'
                      r'|\b(?:export|save|output)\s+(?:to|as)\s+(?:SVG|OBJ|STEP|STP)\b', ' ', question, flags=re.I)
    words = set(match.upper() for match in re.findall(r'(?<![A-Za-z0-9_.-])(SVG|OBJ|STEP|STP)(?![A-Za-z0-9_.-])', question, re.I))
    words = {'STEP' if word == 'STP' else word for word in words}
    if words:
        return next(iter(words)) if len(words) == 1 else None
    if re.search(r'图纸|这张图|图上|总图', question) and re.search(r'法兰|主孔|径向(?:孔|接口)', question):
        return 'SVG'
    if re.search(r'这个网格|这份网格', question) and re.search(r'包络|包围盒|顶点|网格拓扑', question):
        return 'OBJ'
    return None


def requests_visual_observation(question):
    evidence_request = DRAWING_REVIEW_RECORD.sub(' ', question)
    return bool(SVG_NOTE_REQUEST.search(evidence_request) or re.search(
        r'看图|图中|视图|形状|外观|结构|开口|布局|可见|几何表面|图纸|总图|标注|绘制|局部|放大|高亮', evidence_request))


def direct_engineering_question(client,scope,question,filters,intent):
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus' and asset.get('nativeEvidence')
        and asset.get('originalFormat') in {'SVG','OBJ','STEP'} and re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(asset['logicalPath'].rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question,re.I)]
    if (assets or re.search(r'\.(?:svg|obj|stp|step)\b',question,re.I)) and re.search(r'\b(?:https?|file|ftp)://',question,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 外部地址不能替代已授权图纸或几何来源。')
    from services.calculation_source_scope import named_source
    scope_selected = False
    if assets:
        named = named_source(question, filters)
        if named is None or named['assetId'] not in {asset['assetId'] for asset in assets}:
            raise ValueError('NEEDS_CLARIFICATION: 指定工程文件未能唯一匹配当前授权来源。')
        assets = [asset for asset in assets if asset['assetId'] == named['assetId']]
    elif re.search(r'\.(?:svg|obj|stp|step)\b', question, re.I):
        named = named_source(question, filters)
        if named and named.get('originalFormat') in {'SVG', 'OBJ', 'STEP'}:
            raise ValueError('EVIDENCE_INDEX_REBUILD_REQUIRED: 所选工程来源尚无可用的冻结原生证据，请使用已验证的候选或重建版本。')
        return None
    elif not re.search(r'\b(?:https?|file|ftp)://|(?<![A-Za-z0-9_.-])[A-Za-z]{2,12}-[A-Za-z0-9-]+', question, re.I):
        source_format = requested_engineering_format(question)
        if not source_format or intent.allowed and source_format not in intent.allowed:
            return None
        assets = [asset for asset in filters.get('assets', []) if asset.get('scope') == 'corpus'
                  and asset.get('originalFormat') == source_format and asset.get('nativeEvidence')]
        if not assets:
            return None
        if len(assets) > 1:
            raise ValueError('NEEDS_CLARIFICATION: 当前授权范围有多个同类型工程文件，请明确文件名。')
        scope_selected = True
    else:
        return None
    if len(assets)!=1:raise ValueError('NEEDS_CLARIFICATION: 需要一个明确的冻结图纸或模型来源。')
    asset=assets[0];source_format=asset['originalFormat']
    if intent.allowed and source_format not in intent.allowed:raise ValueError('NEEDS_CLARIFICATION: 指定来源与格式限制冲突。')
    residual = engineering_question_scope(question, asset['logicalPath'])
    geometry = source_format in {'OBJ', 'STEP'}
    checks_applicability = checks_geometry_applicability(source_format, residual)
    if geometry and (re.search(r'孔径|孔的?直径|壁厚|间隙|干涉|密封|强度|互换|可替换', residual)
                     or (not checks_applicability and '放行' in residual)):
        raise ValueError('EVIDENCE_GEOMETRY_OPERATION_UNSUPPORTED: 当前未注册孔径、壁厚、间隙、干涉或制造判定工具。')
    drawing_review = (source_format == 'SVG' and bool(DRAWING_REVIEW_RECORD.search(residual))
                      and bool(re.search(r'这[张幅]图|该图纸|图[中上]', residual)))
    if drawing_review:
        visual = not re.search(r'(?:仅|只)(?:按|依据|根据|用|从|读取?)\s*(?:图纸)?'
                               r'(?:原生字段|结构化字段|元数据|metadata)', residual, re.I)
    else:
        visual = requests_visual_observation(residual)
    if source_format == 'SVG' and not re.search(
            r'(?:仅|只)(?:按|依据|根据|用|从|读取?)\s*(?:图纸)?'
            r'(?:原生字段|结构化字段|元数据|metadata)', residual, re.I):
        visual = True
    requests_note = source_format == 'SVG' and bool(SVG_NOTE_REQUEST.search(residual))
    svg_note = requests_note and not re.search(
        r'右侧|右部|左侧|左部|上部|上半|下部|下半', residual)
    if svg_note and not SVG_LIMITATION_REQUEST.search(residual):
        raise ValueError('NEEDS_CLARIFICATION: 请明确要读的图纸说明或所在区域。')
    local=requests_note or bool(re.search(r'局部|放大|高亮',residual))
    all_views = bool(re.search(r'全部.*视图|四.*视图', residual))
    started=time.monotonic();reads=[];evidence=[];metadata=client.evidence_metadata(*scope,asset['assetId'])
    if metadata.get('nativeEvidenceStatus')!='AVAILABLE':raise ValueError('EVIDENCE_ENGINEERING_MANIFEST_UNAVAILABLE')
    def read(operation):
        if len(reads)>=16 or time.monotonic()-started>45:raise ValueError('EVIDENCE_ENGINEERING_READ_LIMIT')
        bundle=client.read_evidence(*scope,asset['assetId'],operation);identity=bundle['identity']
        if any(identity[key]!=str(value) for key,value in zip(('datasetId','versionId','indexVersionId'),scope)) or identity['assetId']!=asset['assetId'] or identity['sourceSha256']!=metadata['sourceSha256'] or identity['rawCommitId']!=metadata['rawCommitId']:
            raise ValueError('EVIDENCE_ENGINEERING_SOURCE_MISMATCH')
        if time.monotonic()-started>45:raise ValueError('EVIDENCE_ENGINEERING_READ_LIMIT')
        reads.append({'state':'READ','readId':bundle['readId'],'evidenceId':bundle['evidenceId'],'representationId':bundle['representationId'],
            'operation':operation,'readOutputSha256':bundle['readOutputSha256'],'factIds':[f['factId'] for f in bundle['facts']],'coverage':bundle['coverage']})
        return bundle
    cylinders = _step_cylinder_request(residual) if source_format == 'STEP' else None
    if cylinders:
        labels, dimensions = cylinders
        operation = {'operation': 'read_step_cylinders', 'entityIds': labels}
        bundle = read(operation)
        if (bundle.get('representationKind') != 'step_cylindrical_surface_declarations'
                or bundle.get('locator', {}).get('entityIds') != labels):
            raise ValueError('EVIDENCE_STEP_ENTITY_READ_MISMATCH')
        item = _item(scope, asset, bundle, question, operation)
        item['scoreType'] = 'verified_step_cylinder_read'
        item['provenance'].pop('engineeringReadVersion')
        item['provenance']['stepCylinderDimensions'] = dimensions
        if scope_selected:
            item['provenance']['engineeringTargetSelection'] = 'single-authorized-source-of-requested-format'
        context = json.loads(item['nativeModelContext'])
        for fact in context['facts']:
            fact['value'] = format(Decimal(str(fact['value'])), 'f')
        context['lengthUnitEntityIds'] = bundle['content']['lengthUnitEntityIds']
        context['说明'] = ('每个实体单独一行，按“#实体号 半径 <数值> <单位>，直径 <数值> <单位>。[来源编号]”'
                         '填写用户所问的尺寸。另用自己的话说明换算关系并引用。'
                         '半径是 STEP 原文声明，直径由半径乘 2 得到。它们不能作为现场实测或公差。')
        item['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',', ':'))
        return {'evidence': [item], 'nativeReads': reads, 'status': 'OK', 'planningMethod': VERSION,
                'engineeringPlan': {'sourceFormat': 'STEP', 'assetId': asset['assetId'], 'reads': len(reads),
                                    'requiredEvidenceIds': [item['evidenceId']]}}
    if source_format == 'STEP' and not visual and step_representation_requested(residual) and not re.search(
            r'包围盒|包络|尺寸|表面积|体积|面数|实体数|装配|实例|视角|公差|半径|直径', residual):
        operation = {'operation': 'read_step_representation'}
        bundle = read(operation)
        item = _item(scope, asset, bundle, question, operation, fields=['lengthUnit', 'shapeRepresentation'])
        if scope_selected:
            item['provenance']['engineeringTargetSelection'] = 'single-authorized-source-of-requested-format'
        return {'evidence': [item], 'nativeReads': reads, 'status': 'OK', 'planningMethod': VERSION,
                'engineeringPlan': {'sourceFormat': 'STEP', 'assetId': asset['assetId'], 'reads': len(reads),
                                    'requiredEvidenceIds': [item['evidenceId']]}}
    representations=metadata['representations']
    instance_order=[rep['locator']['instanceId'] for rep in representations if rep['kind']=='geometry_properties' and rep.get('locator',{}).get('instanceId')]
    target_instances=_instance_targets(residual,instance_order) if source_format in {'OBJ','STEP'} else []
    kinds={'svg_metadata'} if source_format=='SVG' else {'geometry_summary','geometry_properties'}
    if target_instances:
        instance_descriptors=[rep for rep in representations if rep['kind']=='geometry_properties'
            and rep.get('locator',{}).get('instanceId') in target_instances]
        for descriptor in representations:
            if descriptor['kind']!='geometry_summary':continue
            fields={field for field in descriptor.get('selector',{}).get('fields',[]) if _field_selected(field,residual)}
            if not fields:continue
            if not fields<={'unitSource','physicalUnitVerified','sourceUnits'}:
                raise ValueError('NEEDS_CLARIFICATION: 指定实例与整文件统计口径不同，请明确所需范围。')
            if not instance_descriptors or any(not fields<=set(rep.get('selector',{}).get('fields',[])) for rep in instance_descriptors):
                raise ValueError('EVIDENCE_ENGINEERING_PROPERTY_UNAVAILABLE: 指定实例缺少所问单位属性，不能用整文件统计替代。')
    for descriptor in representations:
        if descriptor['kind'] not in kinds:continue
        names=descriptor.get('selector',{}).get('fields',[])
        fields=[field for field in names if _field_selected(field,residual)]
        if checks_applicability:
            implicit_fields = geometry_applicability_fields(source_format, descriptor['kind'], descriptor.get('locator', {}))
            fields.extend(field for field in implicit_fields if field in names and field not in fields)
        if not fields:continue
        if target_instances:
            if descriptor['kind']=='geometry_summary':continue
            if descriptor.get('locator',{}).get('instanceId') not in target_instances:continue
        if source_format=='SVG':context_fields=['unit','simplification']
        else:context_fields=['unitSource','physicalUnitVerified']
        fields+= [field for field in context_fields if field in names and field not in fields]
        operation={'operation':'read_properties','representationId':descriptor['representationId'],'fields':fields}
        bundle=read(operation);evidence.append(_item(scope,asset,bundle,question,operation,fields=fields))
    if visual:
        view_reps=[rep for rep in representations if rep['kind']==('svg_views' if source_format=='SVG' else 'geometry_views')]
        if len(view_reps)!=1:raise ValueError('EVIDENCE_ENGINEERING_VIEWS_UNAVAILABLE')
        operation={'operation':'read_representation','representationId':view_reps[0]['representationId']};bundle=read(operation)
        desired=[]
        for name,pattern in [('front',r'前视|正视|\bfront\b'),('side',r'侧视|\bside\b'),('top',r'俯视|顶视|\btop\b'),('isometric',r'等轴|\bisometric\b')]:
            if re.search(pattern,residual,re.I):desired.append(name)
        if all_views:
            if source_format == 'SVG':
                raise ValueError('EVIDENCE_ENGINEERING_VIEWS_UNAVAILABLE: 该 SVG 只注册了原图。')
            desired = ['front', 'side', 'top', 'isometric']
        if len(desired) > CAPABILITIES['maxImageCount']:
            raise ValueError('EVIDENCE_MULTIBATCH_REQUIRED: 明确请求的视角超过当前图片预算。')
        if local and len(desired)>1:raise ValueError('EVIDENCE_MULTIBATCH_REQUIRED: 多视角与局部图需分批读取，请先明确一个视角。')
        if source_format=='SVG':views=bundle['content']['views'][:1]
        else:
            requested = set(desired or ['isometric'])
            views = [view for view in bundle['content']['views'] if view['view'] in requested]
            if {view['view'] for view in views} != requested:
                raise ValueError('EVIDENCE_ENGINEERING_VIEWS_UNAVAILABLE: 当前冻结来源缺少请求的已注册视图。')
        for view in views:
            # A single representation can contain multiple registered images;
            # derive an evidence identity per view so image/source mapping stays stable.
            import uuid
            selected=json.loads(json.dumps(bundle))
            from services.read_receipt import mark_derivation
            mark_derivation(bundle,selected,VERSION,{'operation':'registered-engineering-view-selection','imageId':view['imageId']})
            selected['evidenceId']=str(uuid.uuid5(uuid.NAMESPACE_URL,bundle['evidenceId']+':'+view['imageId']))
            selected['sourceRepresentationId']=bundle['representationId']
            selected['representationId']=str(uuid.uuid5(uuid.NAMESPACE_URL,bundle['representationId']+':'+view['imageId']))
            selected['representationKind']='registered_engineering_view_selection'
            selected['content']={'view':view,'parentReadId':bundle['readId']}
            selected['locator']={**bundle['locator'],'view':view.get('view','full-svg')}
            selected['parentEvidenceIds']=[bundle['evidenceId']]
            selected['transforms'].append({'operation':'registered-engineering-view-selection','version':VERSION,
                'parameters':{'imageId':view['imageId'],'sourceRepresentationId':bundle['representationId'],'sourceReadOutputSha256':bundle['readOutputSha256']}})
            selected['selectionContentSha256']=hashlib.sha256(json.dumps(selected['content'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
            full=_item(scope,asset,selected,question,operation,view=view,metadata=metadata);full['provenance']['engineeringFullView']=True
            if drawing_review and evidence and not requests_note:
                full['provenance']['svgDrawingReviewRecord'] = True
            evidence.insert(0,full)
            if local:
                parameters,region_name=_region_parameters(residual,source_format,view_reps[0],view)
                note_texts, note_inputs = [], []
                if svg_note:
                    bbox, note_texts, note_inputs = _svg_note_region(
                        read, representations, view_reps[0]['selector']['bounds'], 16 - len(reads))
                    parameters['bbox'], region_name = bbox, '图纸限制说明'
                child=json.loads(json.dumps(selected));signature=json.dumps(parameters,sort_keys=True,separators=(',',':'))
                from services.read_receipt import digest, mark_derivation, verify
                note_receipts = {receipt['readId']: receipt for receipt in map(verify, note_inputs)}
                derivation_parameters = parameters
                if note_receipts:
                    derivation_parameters = {**parameters, 'noteReadReceiptSha256': sorted(map(digest, note_receipts.values())),
                                             'noteTextSha256': digest(note_texts)}
                mark_derivation(selected,child,VERSION,derivation_parameters)
                if note_receipts:
                    child['clientReadParents'].extend(note_receipts.values())
                child['evidenceId']=str(uuid.uuid5(uuid.NAMESPACE_URL,selected['evidenceId']+':region:'+signature))
                child['representationId']=str(uuid.uuid5(uuid.NAMESPACE_URL,selected['representationId']+':region:'+signature))
                child['parentEvidenceIds']=[selected['evidenceId']];child['locator'].update(bbox=parameters['bbox'],regionName=region_name)
                child['coverage']={'partial':True,'truncated':False,'requestedRenderParameters':parameters,'fullContextImageRetained':True}
                child['transforms'].append({'operation':'registered-engineering-region-selection','version':VERSION,'parameters':derivation_parameters})
                crop=_item(scope,asset,child,question,operation,view=view,metadata=metadata);crop['nativeEngineeringRegionRequest']=parameters
                crop['provenance'].update(engineeringLocalView=True,requestedEngineeringRegion=parameters)
                context=json.loads(crop['nativeModelContext']);context.update(coverage='局部选区，不能代表完整模型或图纸',region=region_name,bbox=parameters['bbox'])
                if note_texts:
                    crop['provenance']['svgLimitationNoteRegion'] = True
                    context['原图限制说明'] = note_texts
                crop['nativeModelContext']=json.dumps(context,ensure_ascii=False,separators=(',',':'));evidence.append(crop)
    if (source_format == 'SVG' and visual and not local and not drawing_review
            and sum(bool(item['provenance'].get('engineeringVisualRead')) for item in evidence) == 1
            and any(not item['provenance'].get('engineeringVisualRead') and item['modelFactIds']
                    for item in evidence)):
        graphic_items,graphic_coverage=_svg_graphic_items(read,scope,asset,metadata,question,16-len(reads))
        evidence.extend(graphic_items)
        for item in evidence:
            item['provenance']['engineeringVisualPropertyRecipe'] = 'svg-properties-and-view-1'
            context = json.loads(item['nativeModelContext'])
            context['engineeringVisualPropertyRecipe'] = 'svg-properties-and-view-1'
            context['svgGraphicRecordCoverage'] = graphic_coverage
            if graphic_coverage['state']!='AVAILABLE':
                context['图元补充读取限制']='原生图元补充未覆盖全部注册页或超出单页字段预算；不能据此断言未列图形不存在，仍须核对实际图像。'
            item['nativeModelContext'] = json.dumps(context, ensure_ascii=False, separators=(',', ':'))
    if scope_selected:
        for item in evidence:
            item['provenance']['engineeringTargetSelection'] = 'single-authorized-source-of-requested-format'
    if not evidence:raise ValueError('EVIDENCE_ENGINEERING_OPERATION_UNRESOLVED: 当前未能将问题绑定到已注册几何属性、图纸源声明或表面视图。')
    return {'evidence':evidence,'nativeReads':reads,'status':'OK','planningMethod':VERSION,
        'engineeringPlan':{'sourceFormat':source_format,'assetId':asset['assetId'],'reads':len(reads),'visual':visual,'localRegionRequested':local,'partial':local,
            'requiredEvidenceIds':[item['evidenceId'] for item in evidence]}}
