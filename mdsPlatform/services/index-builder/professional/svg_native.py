"""Static SVG source declarations and bounded, resource-free rasterization."""
from __future__ import annotations
import hashlib,io,json,math,re
from functools import lru_cache
from contextlib import nullcontext
from pathlib import Path
import numpy as np
from defusedxml import ElementTree as XML
from PIL import Image
from .cpu_queue import cpu_evidence_slot

VERSION='safe-svg-native-2026-10-22.2'
RENDER_VERSION='safe-svg-render-2026-10-22.2'
NUMBER=r'[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?'
ALLOWED_TAGS={'svg','g','path','rect','circle','ellipse','line','polyline','polygon','text','tspan','textPath',
    'title','desc','metadata','defs','clipPath','linearGradient','radialGradient','stop','use'}
STYLE_FIELDS={'fill','fill-opacity','fill-rule','stroke','stroke-width','stroke-opacity','stroke-linecap','stroke-linejoin',
    'stroke-dasharray','stroke-dashoffset','opacity','font-family','font-size','font-weight','font-style','text-anchor',
    'dominant-baseline','display','visibility','clip-path','color','stop-color','stop-opacity'}
SVG_NS='http://www.w3.org/2000/svg';XLINK_NS='http://www.w3.org/1999/xlink'


@lru_cache(maxsize=1)
def font_fingerprint():
    paths=[Path('/usr/share/fonts/opentype/noto')/name for name in ('NotoSansCJK-Regular.ttc','NotoSansCJK-Bold.ttc')]
    if not all(path.is_file() for path in paths):raise ValueError('SVG_REQUIRED_CJK_FONT_UNAVAILABLE')
    return {path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}


def _resolve_fonts(root):
    import cairocffi as cairo
    fingerprints=font_fingerprint();family='Noto Sans CJK SC';originals=set();characters=set();substitutions=0
    for node in root.iter():
        if node.tag.rsplit('}',1)[-1] not in {'text','tspan','textPath'}:continue
        originals.add(node.attrib.get('font-family','inherited-source-family'))
        # Cairo's toy text API does not provide browser-like per-character font
        # fallback. Explicitly resolve a packaged CJK font on the derived copy.
        node.set('font-family',family)
        if 'style' in node.attrib:
            declarations=[value for value in node.attrib['style'].split(';') if value.partition(':')[0].strip().lower()!='font-family']
            node.set('style',';'.join(declarations+['font-family:'+family]))
        characters.update(char for char in ''.join(node.itertext()) if not char.isspace());substitutions+=1
    context=cairo.Context(cairo.ImageSurface(cairo.FORMAT_ARGB32,1,1));context.select_font_face(family)
    missing=[f'U+{ord(char):04X}' for char in sorted(characters) if any(glyph[0]==0 for glyph in context.get_scaled_font().text_to_glyphs(0,0,char,with_clusters=False))]
    if missing:raise ValueError('SVG_FONT_GLYPH_UNSUPPORTED:'+','.join(missing[:20]))
    return {'policy':'explicit-packaged-font-substitution-on-derived-copy','family':family,'sourceFamilies':sorted(originals),
        'textElementsResolved':substitutions,'checkedUniqueCodepoints':len(characters),'missingGlyphCount':0,'fontFileSha256':fingerprints,
        'sourceFontDeclarationsPreserved':True,'pixelIdenticalBrowserTypographyClaimed':False}


def _numbers(value):
    if re.sub(NUMBER,'',value).strip(' ,\t\r\n'):raise ValueError('SVG_NUMERIC_SYNTAX_UNSUPPORTED')
    values=[float(match[0]) for match in re.finditer(NUMBER,value)]
    if any(not math.isfinite(v) or abs(v)>1e12 for v in values):raise ValueError('SVG_NUMERIC_RANGE_LIMIT')
    return values


def transform_matrix(value):
    result=np.eye(3);position=0
    for match in re.finditer(r'([A-Za-z]+)\s*\(([^()]*)\)',value):
        if value[position:match.start()].strip(' ,\t\r\n'):raise ValueError('SVG_TRANSFORM_UNSUPPORTED')
        position=match.end();name,args=match[1],_numbers(match[2]);matrix=np.eye(3)
        if name=='matrix' and len(args)==6:
            a,b,c,d,e,f=args;matrix=np.array([[a,c,e],[b,d,f],[0,0,1.]])
        elif name=='translate' and 1<=len(args)<=2:matrix[0,2]=args[0];matrix[1,2]=args[1] if len(args)==2 else 0
        elif name=='scale' and 1<=len(args)<=2:matrix[0,0]=args[0];matrix[1,1]=args[-1]
        elif name=='rotate' and len(args) in {1,3}:
            radians=math.radians(args[0]);co,si=math.cos(radians),math.sin(radians);matrix[:2,:2]=[[co,-si],[si,co]]
            if len(args)==3:
                center=np.array(args[1:]);matrix[:2,2]=center-matrix[:2,:2]@center
        elif name in {'skewX','skewY'} and len(args)==1:
            if abs(math.cos(math.radians(args[0])))<1e-10:raise ValueError('SVG_SINGULAR_SKEW')
            matrix[0,1] = math.tan(math.radians(args[0])) if name=='skewX' else 0
            matrix[1,0] = math.tan(math.radians(args[0])) if name=='skewY' else 0
        else:raise ValueError('SVG_TRANSFORM_UNSUPPORTED')
        result=result@matrix
        if not np.isfinite(result).all() or np.abs(result).max()>1e15:raise ValueError('SVG_TRANSFORM_RANGE_LIMIT')
    if value[position:].strip(' ,\t\r\n'):raise ValueError('SVG_TRANSFORM_UNSUPPORTED')
    return result


def _length(value):
    match=re.fullmatch(r'\s*('+NUMBER+r')\s*(px|mm|cm|in|pt|pc)?\s*',value)
    if not match:raise ValueError('SVG_VIEWPORT_LENGTH_UNSUPPORTED')
    number=float(match[1]);unit=match[2] or 'px';factor={'px':1,'mm':96/25.4,'cm':96/2.54,'in':96,'pt':96/72,'pc':16}[unit]
    if not math.isfinite(number) or not 0<number*factor<=100000:raise ValueError('SVG_VIEWPORT_RANGE_LIMIT')
    return number*factor


def _viewport(root):
    box=_numbers(root.attrib['viewBox']) if 'viewBox' in root.attrib else None
    if box is not None and (len(box)!=4 or box[2]<=0 or box[3]<=0):raise ValueError('SVG_VIEWBOX_INVALID')
    width=_length(root.attrib.get('width',str(box[2]) if box else '300'))
    height=_length(root.attrib.get('height',str(box[3]) if box else '150'))
    box=box or [0,0,width,height];x,y,w,h=box
    aspect=root.attrib.get('preserveAspectRatio','xMidYMid meet').split()
    if aspect and aspect[0]=='defer':aspect.pop(0)
    matrix=np.eye(3);sx,sy=width/w,height/h
    if aspect==['none']:matrix[0,0]=sx;matrix[1,1]=sy;matrix[:2,2]=[-x*sx,-y*sy]
    else:
        if len(aspect)==1:aspect.append('meet')
        if len(aspect)!=2 or not re.fullmatch(r'x(?:Min|Mid|Max)Y(?:Min|Mid|Max)',aspect[0]) or aspect[1] not in {'meet','slice'}:raise ValueError('SVG_ASPECT_RATIO_UNSUPPORTED')
        scale=min(sx,sy) if aspect[1]=='meet' else max(sx,sy);matrix[0,0]=matrix[1,1]=scale
        horizontal={'Min':0,'Mid':.5,'Max':1}[aspect[0][1:4]];vertical={'Min':0,'Mid':.5,'Max':1}[aspect[0][5:]]
        matrix[:2,2]=[-x*scale+(width-w*scale)*horizontal,-y*scale+(height-h*scale)*vertical]
    return {'widthCssPixels':width,'heightCssPixels':height,'viewBox':box,'preserveAspectRatio':' '.join(aspect),
        'sourceUserToViewportMatrix':matrix.tolist(),'cssPixelsPerInch':96,'physicalDrawingDimensionsInferred':False}


def read_svg(path):
    path=Path(path)
    if path.stat().st_size>16*1024*1024:raise ValueError('SVG_SOURCE_BYTE_LIMIT')
    raw=path.read_bytes();text=raw.decode('utf-8-sig')
    if re.search(r'<\?(?!xml\s)',text,re.I):raise ValueError('SVG_PROCESSING_INSTRUCTION_FORBIDDEN')
    root=XML.fromstring(raw,forbid_dtd=True,forbid_entities=True,forbid_external=True)
    if root.tag not in {'svg','{'+SVG_NS+'}svg'}:raise ValueError('SVG_ROOT_REQUIRED')
    viewport=_viewport(root);elements=[];texts=[];metadata=[];ids={};references=[];attribute_bytes=0
    def walk(node,parent,path,depth,parent_matrix,definition=False):
        nonlocal attribute_bytes
        if depth>64 or len(elements)>=20000:raise ValueError('SVG_ELEMENT_OR_DEPTH_LIMIT')
        tag=node.tag.rsplit('}',1)[-1]
        if node.tag.startswith('{') and not node.tag.startswith('{'+SVG_NS+'}'):raise ValueError('SVG_FOREIGN_NAMESPACE_FORBIDDEN')
        if tag not in ALLOWED_TAGS:raise ValueError('SVG_ELEMENT_UNSUPPORTED:'+tag)
        if tag=='svg' and depth:raise ValueError('SVG_NESTED_VIEWPORT_UNSUPPORTED')
        source_id=node.attrib.get('id');identifier=source_id or 'xpath:'+path
        if source_id:
            if source_id in ids or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.:-]{0,255}',source_id):raise ValueError('SVG_DUPLICATE_OR_INVALID_ID')
            ids[source_id]=identifier
        refs=[]
        for key,value in node.attrib.items():
            local=key.rsplit('}',1)[-1];attribute_bytes+=len(value.encode())
            if attribute_bytes>4*1024*1024 or len(value)>2*1024*1024:raise ValueError('SVG_ATTRIBUTE_SIZE_LIMIT')
            if local.lower().startswith('on') or local.lower()=='base':raise ValueError('SVG_ACTIVE_ATTRIBUTE_FORBIDDEN')
            if '\\' in value or re.search(r'@(?:import|font-face)|expression\s*\(',value,re.I):raise ValueError('SVG_CSS_ESCAPE_OR_ACTIVE_REFERENCE_FORBIDDEN')
            if key.startswith('{') and key not in {'{'+XLINK_NS+'}href','{http://www.w3.org/XML/1998/namespace}space','{http://www.w3.org/XML/1998/namespace}lang'}:
                raise ValueError('SVG_ATTRIBUTE_NAMESPACE_UNSUPPORTED')
            if local=='href':
                if not re.fullmatch(r'#[A-Za-z_][A-Za-z0-9_.:-]{0,255}',value):raise ValueError('SVG_EXTERNAL_RESOURCE_FORBIDDEN')
                refs.append(value[1:])
            urls=re.findall(r'url\s*\(([^)]*)\)',value,re.I)
            for url in urls:
                target=url.strip(' \t\r\n\"\'')
                if not re.fullmatch(r'#[A-Za-z_][A-Za-z0-9_.:-]{0,255}',target):raise ValueError('SVG_EXTERNAL_RESOURCE_FORBIDDEN')
                refs.append(target[1:])
            residual=re.sub(r'url\s*\([^)]*\)','',value,flags=re.I)
            if re.search(r'url\s*\(',residual,re.I):raise ValueError('SVG_RESOURCE_SYNTAX_UNSUPPORTED')
            if local=='style':
                for declaration in value.split(';'):
                    if not declaration.strip():continue
                    property,separator,_=declaration.partition(':')
                    if not separator or property.strip().lower() not in STYLE_FIELDS:raise ValueError('SVG_STYLE_PROPERTY_UNSUPPORTED')
        matrix=transform_matrix(node.attrib.get('transform',''));world=parent_matrix@matrix
        definition=definition or tag in {'defs','clipPath','linearGradient','radialGradient'}
        record={'elementId':identifier,'sourceElementId':source_id,'elementPath':path,'parentElementId':parent,
            'tag':tag,'attributes':dict(node.attrib),'localTransform':matrix.tolist(),'worldTransform':world.tolist(),
            'coordinateSpace':'definition' if definition else 'root-user-space','references':refs}
        if tag=='use':
            x,y=_numbers(node.attrib.get('x','0')),_numbers(node.attrib.get('y','0'))
            if len(x)!=1 or len(y)!=1:raise ValueError('SVG_USE_OFFSET_UNSUPPORTED')
            translate=np.eye(3);translate[:2,2]=[x[0],y[0]];record['useInstanceTransform']=(world@translate).tolist()
        elements.append(record);references.extend((identifier,ref) for ref in refs)
        if tag in {'text','title','desc'}:
            value=''.join(node.itertext())
            if value.strip():texts.append({'elementId':identifier,'elementPath':path,'text':value,'worldTransform':world.tolist()})
        if tag=='metadata':
            value=''.join(node.itertext())
            if len(value.encode())>1024*1024:raise ValueError('SVG_METADATA_SIZE_LIMIT')
            data={'elementId':identifier,'elementPath':path,'sourceText':value,'evidenceNature':'source_declaration'}
            if value.lstrip().startswith(('{','[')):
                from .json_native import decode_document
                data['value'],data['numberLexemes']=decode_document(value)
            else:data['value']=value
            metadata.append(data)
        counters={}
        for child in node:
            child_tag=child.tag.rsplit('}',1)[-1];counters[child_tag]=counters.get(child_tag,0)+1
            walk(child,identifier,path+'/'+child_tag+'['+str(counters[child_tag])+']',depth+1,world,definition)
    walk(root,None,'/svg[1]',0,np.eye(3))
    if sum(len(row['text'].encode()) for row in texts)>2*1024*1024:raise ValueError('SVG_TEXT_SIZE_LIMIT')
    graph={row['elementId']:[ids.get(ref) for ref in row['references']] for row in elements}
    if any(ref not in ids for _,ref in references):raise ValueError('SVG_FRAGMENT_REFERENCE_NOT_FOUND')
    # A use can reference a group containing another use, so containment edges
    # participate in the cycle check as well as explicit fragment links.
    for row in elements:
        if row['parentElementId']:graph[row['parentElementId']].append(row['elementId'])
    visiting=set();visited=set()
    def acyclic(identifier,depth=0):
        if depth>128:raise ValueError('SVG_REFERENCE_DEPTH_LIMIT')
        if identifier in visiting:raise ValueError('SVG_REFERENCE_CYCLE')
        if identifier in visited:return
        visiting.add(identifier)
        for child in graph[identifier]:acyclic(child,depth+1)
        visiting.remove(identifier);visited.add(identifier)
    for identifier in graph:acyclic(identifier)
    return {'root':root,'sourceSha256':hashlib.sha256(raw).hexdigest(),'viewport':viewport,'elements':elements,
        'texts':texts,'metadata':metadata,'unsupportedOperations':['three_dimensional_measurement','pixel_exact_dimension_measurement','manufacturing_approval'],
        'security':{'externalResources':'forbidden','scripts':'forbidden','xmlEntities':'forbidden','staticElementAllowlist':True}}


def render_svg(parsed,target,*,bbox=None,max_pixels=1048576,_already_queued=False):
    from cairosvg.surface import PNGSurface
    import cairosvg,cairocffi
    viewport=parsed['viewport'];x,y,w,h=viewport['viewBox'];box=bbox if bbox is not None else [x,y,x+w,y+h]
    if len(box)!=4 or not all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in box) or not (x<=box[0]<box[2]<=x+w and y<=box[1]<box[3]<=y+h):raise ValueError('SVG_REGION_OUTSIDE_SOURCE')
    if not 65536<=max_pixels<=1048576:raise ValueError('SVG_RENDER_PIXEL_LIMIT')
    width,height=viewport['widthCssPixels'],viewport['heightCssPixels']
    if bbox is not None:width,height=box[2]-box[0],box[3]-box[1]
    scale=min(math.sqrt(max_pixels/(width*height)),2048/width,2048/height)
    output_width,output_height=max(1,int(width*scale)),max(1,int(height*scale))
    # Reparse a safe serialized tree so cropping never mutates the parsed source.
    root=XML.fromstring(XML.tostring(parsed['root']),forbid_dtd=True,forbid_entities=True,forbid_external=True)
    if bbox is not None:
        root.set('viewBox',' '.join(map(str,[box[0],box[1],box[2]-box[0],box[3]-box[1]])))
        root.set('width',str(box[2]-box[0]));root.set('height',str(box[3]-box[1]));root.set('preserveAspectRatio','xMidYMid meet')
    def deny_fetch(*_args,**_kwargs):raise ValueError('SVG_RESOURCE_FETCH_FORBIDDEN')
    with (nullcontext() if _already_queued else cpu_evidence_slot()):
        fonts=_resolve_fonts(root)
        raw=PNGSurface.convert(bytestring=XML.tostring(root),url_fetcher=deny_fetch,unsafe=False,dpi=96,
            output_width=output_width,output_height=output_height,background_color='white')
    with Image.open(io.BytesIO(raw)) as image:
        if image.size!=(output_width,output_height):raise ValueError('SVG_RENDER_DIMENSIONS_MISMATCH')
    target=Path(target);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    return {'path':str(target),'recipeVersion':RENDER_VERSION,'sourceSha256':parsed['sourceSha256'],'outputSha256':hashlib.sha256(raw).hexdigest(),
        'outputBytes':len(raw),'width':output_width,'height':output_height,'bbox':box,'coordinateSystem':'root-user-space',
        'viewport':viewport,'engine':{'CairoSVG':cairosvg.__version__,'cairo':cairocffi.cairo_version_string(),'dpi':96,'fontResolution':fonts},
        'evidenceNature':'program_rendering','originalFormat':'SVG','exactGeometricMeasurement':False}
