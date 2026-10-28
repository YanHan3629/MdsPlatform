"""Bounded source OBJ parsing and orthographic CPU triangle depth rendering.

Display meshes never supply BRep measurements. Colours are display annotations,
and one common world scale is used on all axes of each registered camera.
"""
from __future__ import annotations
import hashlib,json,math,time
from contextlib import nullcontext
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,__version__ as pillow_version

VERSION='cpu-surface-depth-2026-10-22.3'
MAX_SOURCE_BYTES=32*1024*1024
MAX_VERTICES=400000
MAX_TRIANGLES=200000
VIEWS={'isometric':([1,-1,1],[0,0,1]),'front':([0,-1,0],[0,0,1]),
       'side':([1,0,0],[0,0,1]),'top':([0,0,1],[0,1,0])}
PALETTE=[(70,122,160),(184,109,67),(84,146,113),(141,107,161),(171,146,61),(67,143,153)]


def _triangulate(vertices,face):
    if len(face)==3:return [face]
    if len(face)>256 or len(set(face))!=len(face):raise ValueError('OBJ_POLYGON_UNSUPPORTED')
    points=vertices[np.asarray(face)];normal=np.sum(np.cross(points,np.roll(points,-1,axis=0)),axis=0)
    length=float(np.linalg.norm(normal));span=float(np.linalg.norm(np.ptp(points,axis=0)))
    if length<=1e-14*max(1,span*span):raise ValueError('OBJ_DEGENERATE_POLYGON')
    normal/=length
    if np.max(np.abs((points-points[0])@normal))>max(1e-9,span*1e-7):raise ValueError('OBJ_NONPLANAR_POLYGON_UNSUPPORTED')
    projected=np.delete(points,int(np.argmax(np.abs(normal))),axis=1)
    area=np.sum(projected[:,0]*np.roll(projected[:,1],-1)-projected[:,1]*np.roll(projected[:,0],-1))
    sign=1 if area>0 else -1;remaining=list(range(len(face)));triangles=[];epsilon=max(1e-14,span*span*1e-12)
    def cross(a,b,c):return float((b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]))
    # Ear clipping assumes a simple polygon. Refuse crossing edges rather than
    # returning overlapping triangles and a fabricated surface area.
    for i in range(len(face)):
        for j in range(i+1,len(face)):
            if j in {i,(i+1)%len(face)} or (j+1)%len(face)==i:continue
            a,b=projected[i],projected[(i+1)%len(face)];c,d=projected[j],projected[(j+1)%len(face)]
            if cross(a,b,c)*cross(a,b,d)<-epsilon**2 and cross(c,d,a)*cross(c,d,b)<-epsilon**2:
                raise ValueError('OBJ_SELF_CROSSING_POLYGON_UNSUPPORTED')
    while len(remaining)>3:
        found=False
        for index,b in enumerate(remaining):
            a=remaining[index-1];c=remaining[(index+1)%len(remaining)]
            if sign*cross(projected[a],projected[b],projected[c])<=epsilon:continue
            if any(all(sign*cross(projected[x],projected[y],projected[p])>=-epsilon for x,y in ((a,b),(b,c),(c,a))) for p in remaining if p not in (a,b,c)):continue
            triangles.append([face[a],face[b],face[c]]);remaining.pop(index);found=True;break
        if not found:raise ValueError('OBJ_POLYGON_TRIANGULATION_UNSUPPORTED')
    triangles.append([face[index] for index in remaining]);return triangles


def read_obj(path):
    path=Path(path)
    if path.stat().st_size>MAX_SOURCE_BYTES:raise ValueError('OBJ_SOURCE_BYTE_LIMIT')
    raw=path.read_bytes();text=raw.decode('utf-8-sig')
    vertices=[];vertex_records=[];face_records=[];materials=[];object_name=None;groups=[];material=None
    for line_number,line in enumerate(text.splitlines(),1):
        parts=line.partition('#')[0].split()
        if not parts:continue
        kind=parts[0]
        if kind=='v':
            if len(parts)<4:raise ValueError(f'OBJ_INVALID_VERTEX_LINE:{line_number}')
            try:point=[float(value) for value in parts[1:4]]
            except ValueError as error:raise ValueError(f'OBJ_INVALID_VERTEX_LINE:{line_number}') from error
            if not all(math.isfinite(value) for value in point):raise ValueError(f'OBJ_NONFINITE_VERTEX:{line_number}')
            if len(parts) not in {4,5,7,8}:raise ValueError(f'OBJ_VERTEX_LAYOUT_UNSUPPORTED:{line_number}')
            if len(parts) in {5,8} and float(parts[4])!=1:raise ValueError('OBJ_HOMOGENEOUS_VERTEX_UNSUPPORTED')
            vertices.append(point);vertex_records.append({'vertexId':len(vertices),'line':line_number,'coordinates':point,'numberLexemes':parts[1:4]})
            if len(vertices)>MAX_VERTICES:raise ValueError('OBJ_VERTEX_LIMIT')
        elif kind=='f':
            if not 4<=len(parts)<=257:raise ValueError(f'OBJ_INVALID_FACE_LINE:{line_number}')
            try:indices=[int(value.split('/')[0]) for value in parts[1:]]
            except ValueError as error:raise ValueError(f'OBJ_INVALID_FACE_LINE:{line_number}') from error
            if 0 in indices:raise ValueError(f'OBJ_INVALID_FACE_INDEX:{line_number}')
            indices=[index-1 if index>0 else len(vertices)+index for index in indices]
            face_records.append({'faceId':len(face_records)+1,'line':line_number,'vertexIndices':indices,
                'vertexIndexBase':0,'vertexIds':[index+1 for index in indices],'vertexIdBase':1,
                'sourceIndexExpressions':parts[1:],'object':object_name,'groups':list(groups),'material':material})
            if len(face_records)>MAX_TRIANGLES:raise ValueError('OBJ_FACE_LIMIT')
        elif kind=='o':object_name=' '.join(parts[1:])
        elif kind=='g':groups=parts[1:]
        elif kind=='usemtl':material=' '.join(parts[1:])
        elif kind=='mtllib':materials.append({'line':line_number,'declaredReference':' '.join(parts[1:]),'loaded':False})
        elif kind in {'curv','curv2','surf','cstype','parm','trim','hole','scrv','sp'}:
            raise ValueError('OBJ_FREEFORM_GEOMETRY_UNSUPPORTED')
    if not vertices:raise ValueError('OBJ_NO_VERTICES')
    array=np.asarray(vertices,dtype=np.float64);triangles=[];triangle_faces=[]
    for record in face_records:
        if any(index<0 or index>=len(array) for index in record['vertexIndices']):raise ValueError(f"OBJ_INVALID_FACE_INDEX:{record['line']}")
        parts=_triangulate(array,record['vertexIndices']);triangles.extend(parts);triangle_faces.extend([record['faceId']]*len(parts))
        if len(triangles)>MAX_TRIANGLES:raise ValueError('OBJ_TRIANGLE_LIMIT')
    return {'vertices':array,'triangles':np.asarray(triangles,dtype=np.int32).reshape((-1,3)),
        'triangleSourceFaceIds':triangle_faces,'vertexRecords':vertex_records,'faceRecords':face_records,
        'externalMaterialReferences':materials,'sourceSha256':hashlib.sha256(raw).hexdigest()}


def _camera(view,vertices,size):
    towards,up=map(lambda value:np.asarray(value,dtype=float),VIEWS[view]);towards/=np.linalg.norm(towards)
    right=np.cross(up,towards);right/=np.linalg.norm(right);up=np.cross(towards,right)
    minimum=vertices.min(axis=0);maximum=vertices.max(axis=0);center=(minimum+maximum)/2
    radius=float(np.max(np.linalg.norm(vertices-center,axis=1)))
    if not radius or not math.isfinite(radius):raise ValueError('GEOMETRY_RENDER_ZERO_EXTENT')
    scale=(size-96)/(2*radius)
    matrix=np.vstack([np.r_[right*scale,size/2-np.dot(center,right)*scale],np.r_[-up*scale,size/2+np.dot(center,up)*scale],np.r_[towards,-np.dot(center,towards)]])
    return matrix,{'projection':'orthographic','view':view,'cameraTowards':towards.tolist(),'cameraUp':up.tolist(),'cameraRight':right.tolist(),
        'worldCenter':center.tolist(),'pixelsPerCoordinateUnit':scale,'worldToImageDepthMatrix':matrix.tolist(),
        'worldBounds':{'minimum':minimum.tolist(),'maximum':maximum.tolist()},'axisScalePolicy':'one-uniform-scale-all-axes'}


def _render(instances,view,target,size,started,*,normalized_bbox=None,max_pixels=None,highlight_ids=()):
    vertices=np.concatenate([item['vertices'] for item in instances]);matrix,metadata=_camera(view,vertices,size)
    width=height=size;colours=[]
    if normalized_bbox is not None:
        left,top,right,bottom=normalized_bbox;span_x,span_y=right-left,bottom-top
        scale=min(math.sqrt(max_pixels/(span_x*span_y)),2048/span_x,2048/span_y)
        width,height=max(1,int(span_x*scale)),max(1,int(span_y*scale))
        reference=matrix.copy();factors=np.asarray([width/(size*span_x),height/(size*span_y)])
        matrix[:2,:3]*=factors[:,None];matrix[:2,3]=(matrix[:2,3]-np.asarray([left,top])*size)*factors
        metadata.update(referenceImageSize=[size,size],worldToReferenceImageDepthMatrix=reference.tolist(),
            normalizedReferenceBBox=list(normalized_bbox),worldToImageDepthMatrix=matrix.tolist(),
            pixelToReferenceScale=[size*span_x/width,size*span_y/height],pixelToReferenceOffset=[left*size,top*size],
            pixelsPerCoordinateUnitXY=(metadata['pixelsPerCoordinateUnit']*factors).tolist(),
            aspectRoundingTolerancePixels=1,localView=True)
        metadata['referencePixelsPerCoordinateUnit']=metadata.pop('pixelsPerCoordinateUnit')
    background=np.asarray([248,250,252],dtype=np.uint8);pixels=np.tile(background,(height,width,1));depth=np.full((height,width),-np.inf)
    labels=np.full((height,width),-1,dtype=np.int32);light=np.asarray([.35,-.4,.85]);light/=np.linalg.norm(light);degenerate=0
    for instance_index,item in enumerate(instances):
        points=item['vertices'];projected=points@matrix[:,:3].T+matrix[:,3]
        colour=((216,127,41) if item['instanceId'] in highlight_ids else (155,164,174)) if highlight_ids else PALETTE[instance_index%len(PALETTE)]
        colours.append(colour);base=np.asarray(colour,dtype=float)
        for index,triangle in enumerate(item['triangles']):
            if index%128==0 and time.monotonic()-started>120:raise TimeoutError('GEOMETRY_RENDER_TIME_LIMIT')
            p=projected[triangle];world=points[triangle];normal=np.cross(world[1]-world[0],world[2]-world[0]);normal_size=float(np.linalg.norm(normal))
            a,b,c=p[:,:2];den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(den)<1e-12 or not normal_size:degenerate+=1;continue
            lo=np.maximum(np.floor(p[:,:2].min(axis=0)).astype(int),0);hi=np.minimum(np.ceil(p[:,:2].max(axis=0)).astype(int),[width-1,height-1])
            if np.any(hi<lo):continue
            x,y=np.meshgrid(np.arange(lo[0],hi[0]+1)+.5,np.arange(lo[1],hi[1]+1)+.5)
            w0=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/den
            w1=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/den;w2=1-w0-w1
            z=w0*p[0,2]+w1*p[1,2]+w2*p[2,2];region=np.s_[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
            visible=(w0>=-1e-10)&(w1>=-1e-10)&(w2>=-1e-10)&(z>depth[region])
            shade=.40+.60*abs(float(np.dot(normal/normal_size,light)));colour=np.clip(base*shade+12,0,255).astype(np.uint8)
            depth[region][visible]=z[visible];pixels[region][visible]=colour;labels[region][visible]=instance_index
    image=Image.fromarray(pixels);draw=ImageDraw.Draw(image);draw.text((16,12),('LOCAL SURFACE / ' if normalized_bbox is not None else 'SURFACE / ')+view+' / orthographic',fill=(35,45,60))
    draw.text((16,height-24),'Display colours; source coordinates; no physical units inferred',fill=(65,75,90));image.save(target,format='PNG')
    metadata.update(width=width,height=height,occupiedPixels=int(np.sum(labels>=0)),edgeOnOrDegenerateTrianglesNotRasterized=degenerate,
        visibleInstancePixels={item['instanceId']:int(np.sum(labels==index)) for index,item in enumerate(instances)},
        displayColours={item['instanceId']:list(colours[index]) for index,item in enumerate(instances)},
        highlightInstanceIds=list(highlight_ids),displayMode='instance-highlight' if highlight_ids else 'opaque-source-surface',
        shading='two-sided-flat-lighting; no source material inferred',depthPolicy='per-pixel-linear-depth-maximum-wins')
    return metadata


def render_views(instances,output_dir,source_sha256,*,scope='whole-source',engine=None,size=768):
    from .cpu_queue import cpu_evidence_slot
    with cpu_evidence_slot():
        return _render_views(instances,output_dir,source_sha256,scope=scope,engine=engine,size=size)


def _prepare_instances(instances):
    if not instances or len(instances)>4096:raise ValueError('GEOMETRY_RENDER_INSTANCE_LIMIT')
    total_vertices=sum(len(item['vertices']) for item in instances);total_triangles=sum(len(item['triangles']) for item in instances)
    if not 0<total_vertices<=MAX_VERTICES or not 0<total_triangles<=MAX_TRIANGLES:raise ValueError('GEOMETRY_RENDER_MESH_LIMIT')
    geometry_hash=hashlib.sha256()
    identifiers=[item['instanceId'] for item in instances]
    if len(set(identifiers))!=len(identifiers):raise ValueError('GEOMETRY_RENDER_DUPLICATE_INSTANCE_ID')
    for item in instances:
        points=np.asarray(item['vertices'],dtype=np.float64);triangles=np.asarray(item['triangles'])
        if points.ndim!=2 or points.shape[1]!=3 or not np.isfinite(points).all():raise ValueError('GEOMETRY_RENDER_NONFINITE_OR_INVALID_VERTICES')
        if triangles.ndim!=2 or triangles.shape[1]!=3 or triangles.dtype.kind not in 'iu' or (triangles.size and (triangles.min()<0 or triangles.max()>=len(points))):raise ValueError('GEOMETRY_RENDER_INVALID_TRIANGLES')
        item['vertices']=points;item['triangles']=triangles
        geometry_hash.update(item['instanceId'].encode());geometry_hash.update(points.astype('<f8').tobytes());geometry_hash.update(triangles.astype('<i4').tobytes())
    return geometry_hash.hexdigest()


def render_view(instances,target,source_sha256,*,view,normalized_bbox=(0,0,1,1),max_pixels=1048576,highlight_ids=(),_already_queued=False):
    from .cpu_queue import cpu_evidence_slot
    if view not in VIEWS or not isinstance(max_pixels,int) or isinstance(max_pixels,bool) or not 65536<=max_pixels<=1048576:raise ValueError('GEOMETRY_RENDER_VIEW_OR_PIXEL_LIMIT')
    box=normalized_bbox
    if len(box)!=4 or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in box) or not (0<=box[0]<box[2]<=1 and 0<=box[1]<box[3]<=1) or min(box[2]-box[0],box[3]-box[1])<.001:raise ValueError('GEOMETRY_RENDER_REGION_INVALID')
    geometry_hash=_prepare_instances(instances);identifiers=[item['instanceId'] for item in instances]
    if len(highlight_ids)>8 or len(set(highlight_ids))!=len(highlight_ids) or not set(highlight_ids)<=set(identifiers):raise ValueError('GEOMETRY_RENDER_HIGHLIGHT_INVALID')
    target=Path(target);target.parent.mkdir(parents=True,exist_ok=True)
    with (nullcontext() if _already_queued else cpu_evidence_slot()):
        metadata=_render(instances,view,target,768,time.monotonic(),normalized_bbox=box,max_pixels=max_pixels,highlight_ids=highlight_ids)
    metadata.update(path=str(target),recipeVersion=VERSION,sourceSha256=source_sha256,geometrySha256=geometry_hash,
        instanceIds=identifiers,outputSha256=hashlib.sha256(target.read_bytes()).hexdigest(),outputBytes=target.stat().st_size,
        measurementsAvailableFromImage=False)
    return metadata


def _render_views(instances,output_dir,source_sha256,*,scope,engine,size):
    if not 128<=size<=1024 or size*size>1048576:raise ValueError('GEOMETRY_RENDER_PIXEL_LIMIT')
    geometry_hash=_prepare_instances(instances);identifiers=[item['instanceId'] for item in instances]
    output_dir=Path(output_dir);output_dir.mkdir(parents=True,exist_ok=True);cache=output_dir/'surface-views.json'
    signature={'recipeVersion':VERSION,'sourceSha256':source_sha256,'scope':scope,'engine':engine,'size':size,
        'instances':identifiers,'geometrySha256':geometry_hash,'numpyVersion':np.__version__,'pillowVersion':pillow_version,'viewOrder':list(VIEWS)}
    if cache.exists():
        try:
            previous=json.loads(cache.read_text())
            if previous.get('signature')==signature and [row['view'] for row in previous['views']]==list(VIEWS) and all(Path(row['path']).name==f"surface-{row['view']}.png" and Path(row['path']).parent==output_dir
                    and Path(row['path']).is_file() and hashlib.sha256(Path(row['path']).read_bytes()).hexdigest()==row['outputSha256'] for row in previous['views']):return previous['views']
        except (ValueError,KeyError,TypeError,OSError):pass
    started=time.monotonic();views=[]
    for view in VIEWS:
        target=output_dir/f'surface-{view}.png';metadata=_render(instances,view,target,size,started)
        metadata.update(path=str(target),recipeVersion=VERSION,sourceSha256=source_sha256,scope=scope,engine=engine,
            geometrySha256=geometry_hash,instanceIds=identifiers,outputSha256=hashlib.sha256(target.read_bytes()).hexdigest(),outputBytes=target.stat().st_size)
        views.append(metadata)
    cache.write_text(json.dumps({'signature':signature,'views':views},ensure_ascii=False,sort_keys=True),encoding='utf-8')
    return views
