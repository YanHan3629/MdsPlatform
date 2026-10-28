"""Explicit, authorized source reads for static drawings and registered geometry."""
from __future__ import annotations
import hashlib,json,re,time

VERSION='engineering-explicit-read-2026-10-25.3'
READ_VERSIONS={'engineering-explicit-read-2026-10-22.1','engineering-explicit-read-2026-10-25.1','engineering-explicit-read-2026-10-25.2',VERSION}
FIELDS={
    'bounds':r'包围盒|长宽高|几何尺寸|bounding|\bbounds\b',
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
    'flange_d':r'法兰(?:外径|直径)|\bflange_d\b',
    'bore_d':r'内孔(?:孔径|直径)|\bbore_d\b',
    'port_count':r'支路口数量|端口数量|\bport_count\b',
    'revision':r'修订|版本|\brevision\b',
    'unit':r'单位|\bunit\b',
    'coordinate_system':r'坐标系|\bcoordinate_system\b',
    'obj_scale':r'OBJ.*(?:比例|缩放)|\bobj_scale\b',
    'simplification':r'简化|限制|\bsimplification\b',
}


def _field_selected(field,question):
    return bool(re.search(FIELDS.get(field,r'(?<![A-Za-z0-9_])'+re.escape(field)+r'(?![A-Za-z0-9_])'),question,re.I))


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


def _item(scope,asset,bundle,question,operation,*,fields=(),view=None,metadata=None):
    roots=['/'+field.replace('~','~0').replace('/','~1') for field in fields]
    facts=[fact for fact in bundle['facts'] if not fields or fact['field'] in fields or any(fact['field']==root or fact['field'].startswith(root+'/') for root in roots)] if view is None else []
    if len(facts)>32:raise ValueError('EVIDENCE_ENGINEERING_FACT_LIMIT: 请缩小所问属性或实例范围。')
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


def direct_engineering_question(client,scope,question,filters,intent):
    assets=[asset for asset in filters.get('assets',[]) if asset.get('scope')=='corpus' and asset.get('nativeEvidence')
        and asset.get('originalFormat') in {'SVG','OBJ','STEP'} and re.search(r'(?<![A-Za-z0-9_.-])'+re.escape(asset['logicalPath'].rsplit('/',1)[-1])+r'(?![A-Za-z0-9_.-])',question,re.I)]
    if (assets or re.search(r'\.(?:svg|obj|stp|step)\b',question,re.I)) and re.search(r'\b(?:https?|file|ftp)://',question,re.I):
        raise ValueError('NEEDS_CLARIFICATION: 外部地址不能替代已授权图纸或几何来源。')
    if not assets:
        if re.search(r'\.(?:svg|obj|stp|step)\b',question,re.I):
            from services.calculation_source_scope import named_source
            named=named_source(question,filters)
            if named and named.get('originalFormat') in {'SVG','OBJ','STEP'}:
                raise ValueError('EVIDENCE_INDEX_REBUILD_REQUIRED: 所选工程来源尚无可用的冻结原生证据，请使用已验证的候选或重建版本。')
        return None
    from services.calculation_source_scope import named_source
    named=named_source(question,filters)
    if named is None or named['assetId'] not in {asset['assetId'] for asset in assets}:
        raise ValueError('NEEDS_CLARIFICATION: 指定工程文件未能唯一匹配当前授权来源。')
    assets=[asset for asset in assets if asset['assetId']==named['assetId']]
    if len(assets)!=1:raise ValueError('NEEDS_CLARIFICATION: 需要一个明确的冻结图纸或模型来源。')
    asset=assets[0];source_format=asset['originalFormat']
    if intent.allowed and source_format not in intent.allowed:raise ValueError('NEEDS_CLARIFICATION: 指定来源与格式限制冲突。')
    residual=re.sub(re.escape(asset['logicalPath'].rsplit('/',1)[-1]),' ',question,flags=re.I)
    residual=re.sub(r'(?:不要|不用|不能|无需|不应|请勿|而非|而不是)[^，。；;！？?\n]*',' ',residual)
    if source_format in {'OBJ','STEP'} and re.search(r'孔径|孔的?直径|壁厚|间隙|干涉|密封|强度|互换|可替换|放行',residual):
        raise ValueError('EVIDENCE_GEOMETRY_OPERATION_UNSUPPORTED: 当前未注册孔径、壁厚、间隙、干涉或制造判定工具。')
    visual=bool(re.search(r'看图|图中|视图|形状|外观|结构|开口|布局|可见|几何表面|图纸|标注|绘制|局部|放大|高亮',residual))
    local=bool(re.search(r'局部|放大|高亮',residual))
    if re.search(r'全部.*视图|四.*视图',residual):raise ValueError('EVIDENCE_MULTIBATCH_REQUIRED: 四视图需要分批规划，当前本次读取最多两张，请明确所需视角。')
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
        if len(desired)>2:raise ValueError('EVIDENCE_MULTIBATCH_REQUIRED: 本次明确视角超过两张，请缩小或分批读取。')
        if local and len(desired)>1:raise ValueError('EVIDENCE_MULTIBATCH_REQUIRED: 多视角与局部图需分批读取，请先明确一个视角。')
        if source_format=='SVG':views=bundle['content']['views'][:1]
        else:views=[view for view in bundle['content']['views'] if view['view'] in (desired or ['isometric'])]
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
            evidence.insert(0,full)
            if local:
                parameters,region_name=_region_parameters(residual,source_format,view_reps[0],view)
                child=json.loads(json.dumps(selected));signature=json.dumps(parameters,sort_keys=True,separators=(',',':'))
                from services.read_receipt import mark_derivation
                mark_derivation(selected,child,VERSION,parameters)
                child['evidenceId']=str(uuid.uuid5(uuid.NAMESPACE_URL,selected['evidenceId']+':region:'+signature))
                child['representationId']=str(uuid.uuid5(uuid.NAMESPACE_URL,selected['representationId']+':region:'+signature))
                child['parentEvidenceIds']=[selected['evidenceId']];child['locator'].update(bbox=parameters['bbox'],regionName=region_name)
                child['coverage']={'partial':True,'truncated':False,'requestedRenderParameters':parameters,'fullContextImageRetained':True}
                child['transforms'].append({'operation':'registered-engineering-region-selection','version':VERSION,'parameters':parameters})
                crop=_item(scope,asset,child,question,operation,view=view,metadata=metadata);crop['nativeEngineeringRegionRequest']=parameters
                crop['provenance'].update(engineeringLocalView=True,requestedEngineeringRegion=parameters)
                context=json.loads(crop['nativeModelContext']);context.update(coverage='局部选区，不能代表完整模型或图纸',region=region_name,bbox=parameters['bbox'])
                crop['nativeModelContext']=json.dumps(context,ensure_ascii=False,separators=(',',':'));evidence.append(crop)
    if not evidence:raise ValueError('EVIDENCE_ENGINEERING_OPERATION_UNRESOLVED: 当前未能将问题绑定到已注册几何属性、图纸源声明或表面视图。')
    return {'evidence':evidence,'nativeReads':reads,'status':'OK','planningMethod':VERSION,
        'engineeringPlan':{'sourceFormat':source_format,'assetId':asset['assetId'],'reads':len(reads),'visual':visual,'localRegionRequested':local,'partial':local,
            'requiredEvidenceIds':[item['evidenceId'] for item in evidence]}}
