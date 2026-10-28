"""Internal bounded image-region renderer. Authorization stays in the backend."""
from __future__ import annotations
import base64,hashlib,hmac,importlib.util,json,math,multiprocessing,os,re,resource,tempfile,threading,time,traceback,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path

for _thread_variable in ('OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS'):os.environ[_thread_variable]='1'

RECIPE='native-region-render-2026-10-22.1'
ENGINEERING_RECIPE='engineering-region-render-2026-10-22.1'
MAX_SOURCE=16*1024*1024
MAX_BODY=24*1024*1024
MAX_OUTPUT=16*1024*1024
QUEUE_FILE='/app/professional/cpu_queue.py'
spec=importlib.util.spec_from_file_location('evidence_cpu_queue',QUEUE_FILE)
queue_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(queue_module)
metrics_lock=threading.Lock();metrics={'acceptedRequests':0,'cacheHits':0,'rendered':0,'failures':0}

class Invalid(Exception):
    def __init__(self,code,status=400):self.code,self.status=code,status

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def digest(raw):return hashlib.sha256(raw).hexdigest()
def duplicate(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise Invalid('DUPLICATE_JSON_KEY')
        result[key]=value
    return result
def integer(value,low,high):
    if isinstance(value,bool) or not isinstance(value,int) or not low<=value<=high:raise Invalid('RENDER_PARAMETER_INVALID')
    return value
def box(value):
    if not isinstance(value,list) or len(value)!=4 or any(isinstance(n,bool) or not isinstance(n,(int,float)) or not math.isfinite(n) for n in value):raise Invalid('RENDER_REGION_INVALID')
    if value[0]>=value[2] or value[1]>=value[3]:raise Invalid('RENDER_REGION_INVALID')
    return value

def validate(body):
    if not isinstance(body,dict) or set(body)!={'identity','sourceBase64','parameters','recipeVersion'}:raise Invalid('RENDER_REQUEST_INVALID')
    if body['recipeVersion'] not in {RECIPE,ENGINEERING_RECIPE}:raise Invalid('RENDER_RECIPE_UNSUPPORTED',422)
    identity=body['identity'];keys={'datasetId','versionId','indexVersionId','rawCommitId','assetId','logicalPath','sourceSha256','originalFormat','evidenceConfigSha256'}
    if not isinstance(identity,dict) or set(identity)!=keys:raise Invalid('RENDER_IDENTITY_INVALID')
    try:
        for key in ('datasetId','versionId','indexVersionId','rawCommitId','assetId'):uuid.UUID(identity[key])
    except (ValueError,TypeError,AttributeError):raise Invalid('RENDER_IDENTITY_INVALID')
    for key in ('sourceSha256','evidenceConfigSha256'):
        if not isinstance(identity[key],str) or not re.fullmatch('[a-f0-9]{64}',identity[key]):raise Invalid('RENDER_IDENTITY_INVALID')
    path=identity['logicalPath']
    if not isinstance(path,str) or not path.startswith('/') or '..' in path.split('/') or '\\' in path:raise Invalid('RENDER_IDENTITY_INVALID')
    parameters=body['parameters']
    if body['recipeVersion']==ENGINEERING_RECIPE:
        if identity['originalFormat'] not in {'SVG','OBJ','STEP'}:raise Invalid('RENDER_FORMAT_UNSUPPORTED',422)
        if not isinstance(parameters,dict) or set(parameters)!={'view','bbox','maxPixels','instanceIds','highlightInstanceIds','engineBinding'}:raise Invalid('RENDER_PARAMETER_INVALID')
        integer(parameters['maxPixels'],65536,1048576);box(parameters['bbox'])
        if parameters['view'] not in ({'source-2d'} if identity['originalFormat']=='SVG' else {'isometric','front','side','top'}):raise Invalid('RENDER_VIEW_UNREGISTERED')
        for name in ('instanceIds','highlightInstanceIds'):
            values=parameters[name]
            if not isinstance(values,list) or len(values)>8 or any(not isinstance(value,str) or not 0<len(value)<=512 for value in values) or len(set(values))!=len(values):raise Invalid('RENDER_INSTANCE_SELECTOR_INVALID')
        if not isinstance(parameters['engineBinding'],dict) or len(canonical(parameters['engineBinding']))>4096:raise Invalid('RENDER_ENGINE_BINDING_INVALID')
        if identity['originalFormat']=='SVG' and (parameters['instanceIds'] or parameters['highlightInstanceIds']):raise Invalid('SVG_INSTANCE_UNSUPPORTED')
        if identity['originalFormat']!='SVG':
            b=parameters['bbox']
            if not (0<=b[0]<b[2]<=1 and 0<=b[1]<b[3]<=1) or min(b[2]-b[0],b[3]-b[1])<.001:raise Invalid('RENDER_REGION_INVALID')
    else:
        if identity['originalFormat'] not in {'PDF','JPG','PNG'}:raise Invalid('RENDER_FORMAT_UNSUPPORTED',422)
        if not isinstance(parameters,dict) or set(parameters)!={'page','bbox','dpi','maxPixels'}:raise Invalid('RENDER_PARAMETER_INVALID')
        integer(parameters['page'],1,256);integer(parameters['dpi'],72,300);integer(parameters['maxPixels'],32768,4_000_000);box(parameters['bbox'])
    if not isinstance(body['sourceBase64'],str) or len(body['sourceBase64'])>(MAX_SOURCE+2)//3*4:raise Invalid('RENDER_SOURCE_BYTE_LIMIT',413)
    try:raw=base64.b64decode(body['sourceBase64'],validate=True)
    except (ValueError,TypeError):raise Invalid('RENDER_SOURCE_ENCODING_INVALID')
    if len(raw)>MAX_SOURCE:raise Invalid('RENDER_SOURCE_BYTE_LIMIT',413)
    if digest(raw)!=identity['sourceSha256']:raise Invalid('RENDER_SOURCE_HASH_MISMATCH',409)
    return identity,parameters,raw

def _child(connection,raw,format,parameters):
    # No application credentials are passed to conversion code.
    for key in list(os.environ):
        if any(word in key.upper() for word in ('TOKEN','SECRET','PASSWORD','AUTHHEADER','API_KEY')):os.environ.pop(key,None)
    limit=(1536 if format in {'SVG','OBJ','STEP'} else 768)*1024*1024
    resource.setrlimit(resource.RLIMIT_AS,(limit,limit))
    resource.setrlimit(resource.RLIMIT_CPU,(30,30))
    resource.setrlimit(resource.RLIMIT_NOFILE,(64,64))
    try:
        if format in {'SVG','OBJ','STEP'}:
            for name in ('OMP_NUM_THREADS','OPENBLAS_NUM_THREADS','MKL_NUM_THREADS'):os.environ[name]='1'
            from professional.engineering_render import render_source
            result=render_source(raw,format,parameters)
            if len(result['png'])>MAX_OUTPUT:raise Invalid('RENDER_OUTPUT_BYTE_LIMIT',413)
            connection.send(result);return
        import fitz
        from PIL import Image,__version__ as pillow_version
        from io import BytesIO
        region=parameters['bbox'];maximum=parameters['maxPixels']
        if format=='PDF':
            with fitz.open(stream=raw,filetype='pdf') as document:
                if document.needs_pass:raise Invalid('PDF_PASSWORD_REQUIRED',422)
                if not 0<document.page_count<=256:raise Invalid('PDF_PAGE_LIMIT',413)
                if parameters['page']>len(document):raise Invalid('RENDER_PAGE_NOT_FOUND',404)
                page=document[parameters['page']-1];rotation=page.rotation;page.set_rotation(0)
                bounds=[0,0,page.rect.width,page.rect.height]
                if region[0]<bounds[0] or region[1]<bounds[1] or region[2]>bounds[2] or region[3]>bounds[3]:raise Invalid('RENDER_REGION_OUTSIDE_SOURCE')
                for info in page.get_image_info():
                    if info['width']*info['height']>32_000_000:raise Invalid('PDF_EMBEDDED_IMAGE_PIXEL_LIMIT',413)
                scale=min(parameters['dpi']/72,math.sqrt(maximum/((region[2]-region[0])*(region[3]-region[1]))))
                pix=page.get_pixmap(matrix=fitz.Matrix(scale,scale),clip=fitz.Rect(region),alpha=False)
                while pix.width*pix.height>maximum:
                    scale*=.99;pix=page.get_pixmap(matrix=fitz.Matrix(scale,scale),clip=fitz.Rect(region),alpha=False)
                png=pix.tobytes('png');width,height=pix.width,pix.height
                transform={'coordinateSystem':'pymupdf-unrotated-top-left-pt','originalPageRotation':rotation,
                    'outputOrientation':'unrotated-source-region','pixelToSourceScale':[1/scale,1/scale],
                    'pixelToSourceOffset':[pix.x/scale,pix.y/scale],'requestedBBox':region,'sourceBounds':bounds,'renderScale':scale}
        else:
            if parameters['page']!=1:raise Invalid('RENDER_PAGE_NOT_FOUND',404)
            with Image.open(BytesIO(raw)) as original:
                if original.width*original.height>32_000_000:raise Invalid('IMAGE_SOURCE_PIXEL_LIMIT',413)
                if getattr(original,'n_frames',1)!=1:raise Invalid('IMAGE_MULTIFRAME_UNSUPPORTED',422)
                expected='JPEG' if format=='JPG' else 'PNG'
                if original.format!=expected:raise Invalid('RENDER_FORMAT_MISMATCH',409)
                bounds=[0,0,original.width,original.height]
                if region[0]<0 or region[1]<0 or region[2]>bounds[2] or region[3]>bounds[3]:raise Invalid('RENDER_REGION_OUTSIDE_SOURCE')
                crop=[math.floor(region[0]),math.floor(region[1]),math.ceil(region[2]),math.ceil(region[3])]
                image=original.convert('RGB').crop(crop);factor=min(1,math.sqrt(maximum/(image.width*image.height)))
                if factor<1:image=image.resize((max(1,int(image.width*factor)),max(1,int(image.height*factor))))
                width,height=image.size;output=BytesIO();image.save(output,format='PNG');png=output.getvalue()
                transform={'coordinateSystem':'source-image-pixels-top-left','sourceExifOrientation':original.getexif().get(274,1),
                    'outputOrientation':'raw-source-pixels','pixelToSourceScale':[(crop[2]-crop[0])/width,(crop[3]-crop[1])/height],
                    'pixelToSourceOffset':crop[:2],'requestedBBox':region,'renderedSourceBBox':crop,'sourceBounds':bounds}
        if len(png)>MAX_OUTPUT:raise Invalid('RENDER_OUTPUT_BYTE_LIMIT',413)
        connection.send({'png':png,'width':width,'height':height,'transform':transform,
            'engine':{'pymupdf':fitz.VersionBind,'pillow':pillow_version}})
    except Invalid as error:connection.send({'error':error.code,'status':error.status})
    except Exception as error:
        code=str(error) if isinstance(error,ValueError) and re.fullmatch('[A-Z0-9_]{1,100}',str(error)) else 'RENDER_FAILED_'+type(error).__name__
        connection.send({'error':code,'status':409 if code=='ENGINEERING_ENGINE_BINDING_MISMATCH' else 422})
    finally:connection.close()

def execute(raw,format,parameters):
    context=multiprocessing.get_context('spawn');receiver,sender=context.Pipe(duplex=False)
    process=context.Process(target=_child,args=(sender,raw,format,parameters));process.start();sender.close()
    try:
        if not receiver.poll(40):raise Invalid('RENDER_TIMEOUT',504)
        try:result=receiver.recv()
        except EOFError:raise Invalid('RENDER_PROCESS_TERMINATED',503)
        process.join(2)
        if 'error' in result:raise Invalid(result['error'],result['status'])
        return result
    finally:
        if process.is_alive():process.terminate();process.join(5)
        receiver.close()

def render(body):
    import fitz
    from PIL import __version__ as pillow_version
    identity,parameters,raw=validate(body)
    recipe=body['recipeVersion'];engines={'pymupdf':fitz.VersionBind,'pillow':pillow_version}
    if recipe==ENGINEERING_RECIPE:
        from professional.engineering_render import engine_binding
        engines=engine_binding(identity['originalFormat'])
        if engines!=parameters['engineBinding']:raise Invalid('ENGINEERING_ENGINE_BINDING_MISMATCH',409)
    key=digest(canonical({'identity':identity,'parameters':parameters,'recipeVersion':recipe,'engines':engines}))
    root=Path(os.environ.get('EVIDENCE_CACHE_DIR','/cache'));root.mkdir(parents=True,exist_ok=True)
    def cached():
        try:
            if (root/(key+'.json')).stat().st_size>1024*1024 or (root/(key+'.png')).stat().st_size>MAX_OUTPUT:return None
            wrapper=json.loads((root/(key+'.json')).read_text());metadata=wrapper['payload'];png=(root/(key+'.png')).read_bytes()
            if digest(canonical(metadata))!=wrapper['sha256']:return None
            if metadata['engine']!=engines:return None
            if len(png)>MAX_OUTPUT or digest(png)!=metadata['outputSha256'] or metadata['identity']!=identity or metadata['parameters']!=parameters or metadata['recipeVersion']!=recipe:return None
            return metadata,png
        except (OSError,ValueError,KeyError,TypeError):return None
    pair=cached();status='HIT';started=time.perf_counter();queue_wait=0
    if pair is None:
        with queue_module.cpu_evidence_slot(timeout=30) as queue:
            pair=cached();queue_wait=queue['queueWaitMs']
            if pair is None:
                rendered=execute(raw,identity['originalFormat'],parameters);png=rendered.pop('png')
                if rendered['engine']!=engines:raise Invalid('ENGINEERING_ENGINE_BINDING_MISMATCH',409)
                metadata={'schemaVersion':'qa-region-image-1','identity':identity,'parameters':parameters,'recipeVersion':recipe,
                    **rendered,'outputSha256':digest(png),'outputBytes':len(png)}
                metadata['representationId']=str(uuid.uuid5(uuid.NAMESPACE_URL,key+':'+metadata['outputSha256']))
                wrapper={'payload':metadata,'sha256':digest(canonical(metadata))}
                for suffix,content in [('.png',png),('.json',canonical(wrapper))]:
                    with tempfile.NamedTemporaryFile(dir=root,prefix=key+'.',delete=False) as stream:temporary=Path(stream.name);stream.write(content)
                    temporary.replace(root/(key+suffix))
                pair=metadata,png;status='MISS'
    with metrics_lock:metrics['cacheHits' if status=='HIT' else 'rendered']+=1
    metadata,png=pair
    return {**metadata,'imageBase64':base64.b64encode(png).decode(),'cacheStatus':status,'queueWaitMs':queue_wait,'elapsedMs':round((time.perf_counter()-started)*1000,3)}

class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def reply(self,status,value):
        raw=canonical(value);self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.send_header('Connection','close');self.end_headers();self.wfile.write(raw);self.close_connection=True
    def authorized(self):
        token=os.environ.get('MM_INTERNAL_TOKEN','')
        return bool(token) and hmac.compare_digest(self.headers.get('X-MM-Internal-Token',''),token)
    def do_GET(self):
        if self.path=='/healthz':return self.reply(200,{'status':'ok','recipeVersion':RECIPE})
        if self.path=='/internal/v1/evidence/stats' and self.authorized():
            with metrics_lock:return self.reply(200,dict(metrics))
        self.reply(404,{'error':'NOT_FOUND'})
    def do_POST(self):
        if not self.authorized():return self.reply(401,{'error':'INTERNAL_IDENTITY_REQUIRED'})
        if self.path!='/internal/v1/evidence/render':return self.reply(404,{'error':'NOT_FOUND'})
        try:
            if self.headers.get('Transfer-Encoding'):raise Invalid('CHUNKED_BODY_UNSUPPORTED')
            if not self.headers.get('Content-Type','').startswith('application/json'):raise Invalid('JSON_REQUIRED')
            try:length=int(self.headers.get('Content-Length','-1'))
            except ValueError:raise Invalid('BODY_LENGTH_INVALID')
            if not 0<length<=MAX_BODY:raise Invalid('RENDER_REQUEST_BYTE_LIMIT',413)
            self.connection.settimeout(15);raw=self.rfile.read(length)
            if len(raw)!=length:raise Invalid('INCOMPLETE_BODY')
            body=json.loads(raw,object_pairs_hook=duplicate,parse_constant=lambda value:(_ for _ in ()).throw(Invalid('NONFINITE_JSON_NUMBER')))
            with metrics_lock:metrics['acceptedRequests']+=1
            self.reply(200,render(body))
        except Invalid as error:
            with metrics_lock:metrics['failures']+=1
            self.reply(error.status,{'error':error.code})
        except TimeoutError:self.reply(504,{'error':'EVIDENCE_CPU_QUEUE_TIMEOUT'})
        except (ValueError,UnicodeError):self.reply(400,{'error':'INVALID_JSON_BODY'})
        except Exception as error:
            diagnostic={'event':'render_unavailable','errorType':type(error).__name__,
                'frames':[{'module':Path(frame.filename).name,'function':frame.name,'line':frame.lineno} for frame in traceback.extract_tb(error.__traceback__)]}
            with metrics_lock:metrics['failures']+=1;metrics['lastFailure']=diagnostic
            print(json.dumps(diagnostic),flush=True)
            self.reply(503,{'error':'EVIDENCE_RENDER_UNAVAILABLE'})

class Server(ThreadingHTTPServer):
    daemon_threads=True
    request_queue_size=8
    slots=threading.BoundedSemaphore(2)
    def process_request(self,request,address):
        if not self.slots.acquire(False):
            request.sendall(b'HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');self.shutdown_request(request);return
        try:super().process_request(request,address)
        except Exception:self.slots.release();raise
    def process_request_thread(self,request,address):
        try:super().process_request_thread(request,address)
        finally:self.slots.release()

if __name__=='__main__':Server(('0.0.0.0',18083),Handler).serve_forever()
