"""Request-local evidence/model audit. Never persist credentials or image URLs."""
from __future__ import annotations
import base64,copy,hashlib,json,logging,os,time,uuid
from contextvars import ContextVar
from datetime import datetime,timezone
from functools import wraps
from pathlib import Path

_current=ContextVar('qa_evidence_trace',default=None)
logger=logging.getLogger(__name__)


class TraceStorageError(RuntimeError):
    """A response cannot claim an audit trail that was not durably written."""

def current():return _current.get()
def _sha(value):return hashlib.sha256(value).hexdigest()

class EvidenceTrace:
    def __init__(self,kwargs):
        self.id=str(uuid.uuid4());self.contexts=[];self.inputs=[]
        self.data={'schemaVersion':'qa-actual-evidence-trace-1','traceId':self.id,'startedAt':datetime.now(timezone.utc).isoformat(),
            'questionSha256':_sha(str(kwargs.get('question','')).encode()),
            'snapshot':{key:str(kwargs[key]) for key in ('dataset_id','version_id','index_version_id') if kwargs.get(key)},
            'events':[],'modelCalls':[],'tokenizerCalls':[]}
    def event(self,kind,value):
        self.data['events'].append({'kind':kind,'value':copy.deepcopy(value)});self.checkpoint()
    def generation_inputs(self,sources,images):
        self.contexts=[];self.inputs=[]
        for source in sources:
            text=source.model_context or source.content_preview
            value={'sourceId':source.source_id,'evidenceId':source.evidence_id,'representationId':source.representation_id,
                'factIds':source.fact_ids,'assetId':str(source.asset_id) if source.asset_id else None,'locator':source.locator,
                'contextTextSha256':_sha(text.encode()),'readId':source.provenance.get('nativeReadId'),
                'readOutputSha256':source.provenance.get('readOutputSha256'),'sourceSha256':source.provenance.get('sourceSha256')}
            if source.calculation_lineage:
                value['calculationLineage']=source.calculation_lineage
            receipt=(source.evidence_bundle or {}).get('clientReadReceipt')
            if receipt:
                from services.read_receipt import digest
                value['clientReadReceiptSha256']=digest(receipt)
                value['clientDecodedBodySha256']=receipt.get('decodedBodySha256')
            parents=(source.evidence_bundle or {}).get('clientReadParents',[])
            if parents:
                from services.read_receipt import digest
                value['clientParentReadReceiptSha256']=[digest(parent) for parent in parents]
            self.contexts.append(text);self.inputs.append(copy.deepcopy(value))
        self.event('generationInputs',{'sources':self.inputs,'images':[{key:value for key,value in image.items() if key!='url'} for image in images]})
    def begin(self,path,payload,raw):
        call={'callId':str(uuid.uuid4()),'requestBodySha256':_sha(raw),'startedAt':datetime.now(timezone.utc).isoformat(),'startedClock':time.perf_counter()}
        safe=copy.deepcopy({key:value for key,value in payload.items() if key!='messages'});safe_messages=[];actual_text=[]
        for message in payload.get('messages',[]):
            content=message.get('content','');parts=content if isinstance(content,list) else [{'type':'text','text':content}];safe_parts=[]
            for part in parts:
                if part.get('type')=='text':
                    text=part.get('text','');actual_text.append(text);entry={'type':'text','textSha256':_sha(text.encode()),'characters':len(text)}
                    if os.environ.get('QA_TRACE_INCLUDE_TEXT')=='1':entry['text']=text
                    safe_parts.append(entry)
                elif part.get('type')=='image_url':
                    url=part.get('image_url',{}).get('url','')
                    if url.startswith('data:') and ';base64,' in url:
                        raw_image=base64.b64decode(url.split(';base64,',1)[1],validate=True);safe_parts.append({'type':'image_url','imageSha256':_sha(raw_image),'bytes':len(raw_image)})
                    else:safe_parts.append({'type':'image_url','urlSha256':_sha(url.encode()),'externalUrlOmitted':True})
            safe_messages.append({'role':message.get('role'),'content':safe_parts})
        safe['messages']=safe_messages;call['request']=safe;joined='\n'.join(actual_text)
        call['actualEvidence']=[{**copy.deepcopy(source),'contextMatchedActualRequest':True,
            'factIdStatus':'present' if source['factIds'] else 'not_available',
            'factIdsPresentInActualRequest':bool(source['factIds']) and all(fact in joined for fact in source['factIds'])}
            for source,text in zip(self.inputs,self.contexts) if text and text in joined]
        call['status']='request_prepared'
        target='modelCalls' if path=='/v1/chat/completions' else 'tokenizerCalls';self.data[target].append(call);self.checkpoint()
        return call
    def end(self,call,result=None,error=None):
        if 'elapsedMs' in call:return
        call['elapsedMs']=round((time.perf_counter()-call.pop('startedClock'))*1000,3)
        if error is not None:call.update(status='failed-or-transmission-unconfirmed',errorType=type(error).__name__)
        else:
            call['status']='response_received'
            if 'choices' in result:call['response']=copy.deepcopy({key:result.get(key) for key in ('id','model','choices','usage')})
            else:call['response']={key:result.get(key) for key in ('count','max_model_len')}
        self.checkpoint()
    def _write(self,final=False):
        try:
            directory=Path(os.environ.get('QA_EVIDENCE_TRACE_DIR','/tmp/qa-evidence-traces'));directory.mkdir(parents=True,exist_ok=True)
            target=directory/(self.id+('.json' if final else '.pending'));temporary=directory/(self.id+'.writing')
            temporary.write_text(json.dumps(self.data,ensure_ascii=False,indent=2,default=str),encoding='utf-8');temporary.replace(target)
            if final:(directory/(self.id+'.pending')).unlink(missing_ok=True)
        except OSError as error:raise TraceStorageError('EVIDENCE_TRACE_UNAVAILABLE') from error
    def checkpoint(self):self._write()
    def save(self):
        self.data['completedAt']=datetime.now(timezone.utc).isoformat();self._write(final=True)

def traced_qa(function):
    @wraps(function)
    def run(*args,**kwargs):
        trace=EvidenceTrace(kwargs);token=_current.set(trace)
        try:
            result=function(*args,**kwargs);result.meta.trace_id=trace.id
            trace.data['result']={'status':result.meta.retrieval_status,'generationStatus':result.meta.generation_status,
                'answer':result.answer,'sourceEvidenceIds':[source.evidence_id for source in result.sources],
                'factIds':[fact for source in result.sources for fact in source.fact_ids]}
            trace.data['result']['answerClaimCount']=len(result.answer_claims)
            trace.data['result']['answerClaimsSha256']=_sha(json.dumps(result.answer_claims,ensure_ascii=False,sort_keys=True,separators=(',',':'),default=str).encode())
            trace.save();return result
        except Exception as error:
            trace.data['failure']={'errorType':type(error).__name__,'modelCallCount':len(trace.data['modelCalls'])}
            try:trace.save()
            except TraceStorageError:
                # Preserve a primary authorization/read failure. No content or
                # credential is logged if audit storage also failed.
                logger.error('Evidence trace storage failed: traceId=%s primaryError=%s',trace.id,type(error).__name__)
            raise
        finally:
            _current.reset(token)
    return run
