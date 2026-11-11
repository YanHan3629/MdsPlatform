"""One live parent-session and immutable frozen scope per worker request."""
import contextvars
import hmac
import json
import os
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

_current=contextvars.ContextVar("mds_worker_authority",default=None)
class AuthorityError(RuntimeError):
    def __init__(self,status=403,code="WORKER_AUTHORIZATION_DENIED"):
        super().__init__(code);self.status=status;self.code=code

def _unique(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise AuthorityError(502,"INVALID_AUTHORITY_RESPONSE")
        result[key]=value
    return result

class ScopeGuard:
    def __init__(self,authorization,internal,backend,allowed):
        self._authorization=authorization;self._internal=internal
        self.backend=backend.rstrip("/");self.allowed=frozenset(allowed)
        self.scope=None;self._checked=0.0;self._lock=threading.RLock()
        self.revalidate(force=True)
    @property
    def headers(self):
        return {"Authorization":self._authorization,"X-MM-Internal-Token":self._internal}
    def revalidate(self,force=False):
        with self._lock:
            if not force and time.monotonic()-self._checked<0.5:return self.scope
            request=urllib.request.Request(self.backend+"/api/mm/internal/evidence/authorize",headers=self.headers,method="GET")
            # Disable redirects: credentials must never follow a different origin.
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self,*args,**kwargs):return None
            opener=urllib.request.build_opener(NoRedirect)
            try:
                with opener.open(request,timeout=2.0) as response:
                    raw=response.read(16385)
                    if response.status!=200 or len(raw)>16384:raise AuthorityError(502,"INVALID_AUTHORITY_RESPONSE")
            except urllib.error.HTTPError as error:
                status=error.code if error.code in {401,403,404,409,503} else 502
                raise AuthorityError(status,"WORKER_SCOPE_REVOKED_OR_UNAVAILABLE") from None
            except (OSError,urllib.error.URLError):
                raise AuthorityError(503,"WORKER_AUTHORITY_UNAVAILABLE") from None
            try:
                value=json.loads(raw.decode("utf-8"),object_pairs_hook=_unique)
                if not isinstance(value,dict) or value.get("purpose")!="GENERAL_READ" or value.get("operation") not in self.allowed:
                    raise AuthorityError(403,"WORKER_OPERATION_DENIED")
                scope={key:str(uuid.UUID(value[key])) for key in ("datasetId","versionId","indexVersionId","userId","orgId","sessionId")}
                scope["operation"]=value["operation"]
            except (KeyError,ValueError,TypeError,UnicodeError):
                raise AuthorityError(502,"INVALID_AUTHORITY_RESPONSE") from None
            if self.scope is not None and scope!=self.scope:raise AuthorityError(403,"WORKER_SCOPE_REBIND_DENIED")
            self.scope=scope;self._checked=time.monotonic();return dict(scope)
    def require_scope(self,dataset,version,index):
        try:actual=tuple(str(uuid.UUID(str(value))) for value in (dataset,version,index))
        except (ValueError,TypeError,AttributeError):raise AuthorityError(400,"EXPLICIT_FROZEN_SCOPE_REQUIRED") from None
        if actual!=tuple(self.scope[key] for key in ("datasetId","versionId","indexVersionId")):
            raise AuthorityError(404,"WORKER_FROZEN_SCOPE_NOT_AVAILABLE")
        self.revalidate(force=True);return self
    def bundle_path(self,dataset,version,index):
        self.require_scope(dataset,version,index)
        return f"/api/mm/internal/datasets/{dataset}/versions/{version}/indexes/{index}/bundle"
    def safe_backend_url(self,url):
        resolved=urllib.parse.urljoin(self.backend+"/",str(url));at=urllib.parse.urlsplit(resolved);base=urllib.parse.urlsplit(self.backend)
        if at.scheme not in {"http","https"} or at.scheme!=base.scheme or at.hostname!=base.hostname or at.port!=base.port or at.username or at.password or at.fragment or not at.path.startswith("/api/"):
            raise AuthorityError(403,"WORKER_DOWNLOAD_ORIGIN_DENIED")
        return resolved


class PreloadGuard(ScopeGuard):
    def __init__(self,authorization,internal,backend,authority_kind):
        if authority_kind not in {"HUMAN","SERVICE"}:raise AuthorityError(403,"INDEX_PRELOAD_AUTHORITY_KIND_REQUIRED")
        self.authority_kind=authority_kind
        super().__init__(authorization,internal,backend,{"dataset.index.build","dataset.index.activate"})
    def revalidate(self,force=False):
        with self._lock:
            if not force and time.monotonic()-self._checked<0.5:return self.scope
            path="/api/mm/index-cache/authority" if self.authority_kind=="HUMAN" else "/api/mm/service/authority"
            request=urllib.request.Request(self.backend+path,headers=self.headers,method="GET")
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self,*args,**kwargs):return None
            try:
                with urllib.request.build_opener(NoRedirect).open(request,timeout=2.0) as response:
                    raw=response.read(16385)
                    if response.status!=200 or len(raw)>16384:raise AuthorityError(502,"INVALID_PRELOAD_AUTHORITY_RESPONSE")
            except urllib.error.HTTPError as error:
                raise AuthorityError(error.code if error.code in {401,403,404,409,503} else 502,"INDEX_PRELOAD_AUTHORITY_REVOKED") from None
            except (OSError,urllib.error.URLError):raise AuthorityError(503,"INDEX_PRELOAD_AUTHORITY_UNAVAILABLE") from None
            try:
                value=json.loads(raw.decode("utf-8"),object_pairs_hook=_unique)
                if not isinstance(value,dict) or value.get("purpose")!="VIEW_BUILD":raise AuthorityError(403,"INDEX_PRELOAD_PURPOSE_DENIED")
                scope={key:str(uuid.UUID(value[key])) for key in ("datasetId","versionId","indexVersionId","orgId")}
                if self.authority_kind=="HUMAN":
                    if value.get("subjectKind")!="USER" or "serviceSubjectId" in value:raise AuthorityError(403,"INDEX_PRELOAD_HUMAN_AUTHORITY_REQUIRED")
                    operation=value.get("operation");kind=value.get("cacheKind")
                    if (kind,operation) not in {("ORIGINAL_BUILD","dataset.index.build"),("ACTIVATION","dataset.index.activate")}:
                        raise AuthorityError(403,"INDEX_PRELOAD_OPERATION_DENIED")
                    scope.update(userId=str(uuid.UUID(value["userId"])),sessionId=str(uuid.UUID(value["sessionId"])),cacheKind=kind,operation=operation)
                else:
                    if value.get("subjectKind")!="SERVICE" or value.get("operation")!="dataset.index.build" or "userId" in value or "sessionId" in value:
                        raise AuthorityError(403,"INDEX_PRELOAD_SERVICE_AUTHORITY_REQUIRED")
                    scope.update(serviceSubjectId=str(uuid.UUID(value["serviceSubjectId"])),operation="dataset.index.build")
            except (KeyError,ValueError,TypeError,UnicodeError):raise AuthorityError(502,"INVALID_PRELOAD_AUTHORITY_RESPONSE") from None
            if self.scope is not None and scope!=self.scope:raise AuthorityError(403,"INDEX_PRELOAD_SCOPE_REBIND_DENIED")
            self.scope=scope;self._checked=time.monotonic();return dict(scope)
    def bundle_path(self,dataset,version,index):
        self.require_scope(dataset,version,index)
        prefix="/api/mm/index-cache" if self.authority_kind=="HUMAN" else "/api/mm/service"
        return f"{prefix}/datasets/{dataset}/versions/{version}/indexes/{index}/bundle"

def authenticate(headers,allowed):
    expected=os.environ.get("MM_INTERNAL_TOKEN","");supplied=headers.get("X-MM-Internal-Token","")
    backend=os.environ.get("BACKEND_BASE_URL","").rstrip("/")
    if not expected or not backend:raise AuthorityError(503,"WORKER_AUTHORITY_NOT_CONFIGURED")
    if not hmac.compare_digest(expected,supplied):raise AuthorityError(401,"PRIVATE_WORKER_CREDENTIAL_REQUIRED")
    authorization=headers.get("Authorization","")
    if not authorization.startswith("Bearer ") or not 8<len(authorization)<=8192:
        raise AuthorityError(401,"SCOPED_WORKER_CREDENTIAL_REQUIRED")
    parsed=urllib.parse.urlsplit(backend)
    if parsed.scheme not in {"http","https"} or not parsed.netloc or parsed.username or parsed.password or parsed.path not in {"","/"} or parsed.query or parsed.fragment:
        raise AuthorityError(503,"WORKER_AUTHORITY_NOT_CONFIGURED")
    return ScopeGuard(authorization,expected,backend,allowed)

def authenticate_preload(headers,allowed=None):
    expected=os.environ.get("MM_INTERNAL_TOKEN","");supplied=headers.get("X-MM-Internal-Token","")
    backend=os.environ.get("BACKEND_BASE_URL","").rstrip("/")
    if not expected or not backend:raise AuthorityError(503,"WORKER_AUTHORITY_NOT_CONFIGURED")
    if not hmac.compare_digest(expected,supplied):raise AuthorityError(401,"PRIVATE_WORKER_CREDENTIAL_REQUIRED")
    authorization=headers.get("Authorization","")
    if not authorization.startswith("Bearer ") or not 8<len(authorization)<=8192:raise AuthorityError(401,"SCOPED_WORKER_CREDENTIAL_REQUIRED")
    parsed=urllib.parse.urlsplit(backend)
    if parsed.scheme not in {"http","https"} or not parsed.netloc or parsed.username or parsed.password or parsed.path not in {"","/"} or parsed.query or parsed.fragment:
        raise AuthorityError(503,"WORKER_AUTHORITY_NOT_CONFIGURED")
    return PreloadGuard(authorization,expected,backend,headers.get("X-MDS-Index-Authority",""))

def current_guard():
    guard=_current.get()
    if guard is None:raise AuthorityError(401,"SCOPED_WORKER_CREDENTIAL_REQUIRED")
    return guard

def require_scope(dataset,version,index):
    return current_guard().require_scope(dataset,version,index)

def bind(guard):return _current.set(guard)
def reset(token):_current.reset(token)

def download_atomic(url,target,timeout):
    import requests
    guard=current_guard();guard.revalidate(force=True)
    target=Path(target);target.parent.mkdir(parents=True,exist_ok=True);temporary=None
    try:
        with tempfile.NamedTemporaryFile(dir=str(target.parent),prefix=target.name+".partial.",delete=False) as output:
            temporary=Path(output.name)
            with requests.get(guard.safe_backend_url(url),headers=guard.headers,timeout=timeout,stream=True,allow_redirects=False) as response:
                if response.status_code!=200:
                    raise AuthorityError(response.status_code if response.status_code in {401,403,404,409,413,503} else 502,"WORKER_SOURCE_UNAVAILABLE")
                for block in response.iter_content(65536):
                    guard.revalidate()
                    if block:output.write(block)
        guard.revalidate(force=True);os.replace(str(temporary),str(target));temporary=None
        return target
    finally:
        if temporary is not None:temporary.unlink(missing_ok=True)

def install_guard(app,service):
    from fastapi.responses import JSONResponse
    from starlette.concurrency import run_in_threadpool
    @app.exception_handler(AuthorityError)
    async def authority_error(request,error):
        return JSONResponse(status_code=error.status,content={"detail":error.code},headers={"Cache-Control":"no-store"})
    @app.middleware("http")
    async def authority(request,call_next):
        if request.url.path in {"/health","/healthz"}:return await call_next(request)
        allowed={"data.qa"} if service=="qa" else {"data.qa","data.search"}
        preload=service=="search" and request.url.path=="/internal/v1/indexes/preload"
        if service=="search" and not (preload or request.url.path.startswith("/internal/v1/search/") or
                request.url.path.startswith("/internal/v1/indexes/") and request.url.path.endswith("/capabilities")):
            return JSONResponse(status_code=403,content={"detail":"SCOPED_SERVICE_OPERATION_REQUIRED"})
        token=None
        try:
            guard=await run_in_threadpool(authenticate_preload if preload else authenticate,request.headers,allowed);token=bind(guard)
            response=await call_next(request)
            await run_in_threadpool(guard.revalidate,True)
            iterator=response.body_iterator
            async def checked_body():
                async for block in iterator:
                    await run_in_threadpool(guard.revalidate)
                    yield block
            response.body_iterator=checked_body()
            response.headers["Cache-Control"]="no-store, private"
            return response
        except AuthorityError as error:
            return JSONResponse(status_code=error.status,content={"detail":error.code},headers={"Cache-Control":"no-store"})
        finally:
            if token is not None:reset(token)
