#!/usr/bin/env bash

# 下载、转换/训练、上传任务脚本
set -e
if (set -o pipefail) 2>/dev/null; then
  set -o pipefail
fi
log() { printf '%s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

###############################################################################
# Args / Config
###############################################################################
INPUT_DIR="/tmp/in"
[ -n "$INPUT_DIR" ] || die "missing input_dir argument"
mkdir -p "$INPUT_DIR"

FILES_URL="$HOST/api/artifacts/$ARTIFACT_ID/commits/$COMMIT_ID/files"
DOWNLOAD_URL="$HOST/api/artifacts/$ARTIFACT_ID/commits/$COMMIT_ID/files/content"
PAGE_SIZE="${FILE_LIST_PAGE_SIZE:-1000}"

need_cmd curl
need_cmd python3

if [ -n "${MDS_JOB_IO_MANIFEST_SHA256:-}" ]; then
  python3 - <<'PY_FROZEN_MULTI_IO_V1'
"""Embedded in the frozen generic-job bootstrap; all authority stays server-side."""
import hashlib,json,mimetypes,os,pathlib,re,stat,subprocess,sys,urllib.request,urllib.parse,urllib.error,uuid,unicodedata

class IoFailure(Exception):pass
def require(value,code):
 if not value:raise IoFailure(code)
def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode('utf-8')
def identifier(value):
 require(isinstance(value,str),'MULTI_IO_IDENTIFIER_INVALID')
 try:require(str(uuid.UUID(value))==value,'MULTI_IO_IDENTIFIER_INVALID')
 except ValueError:raise IoFailure('MULTI_IO_IDENTIFIER_INVALID')
 return value
def logical(value):
 require(isinstance(value,str) and value.startswith('/') and value!='/' and '\\' not in value and not any(ord(c)<32 or ord(c)==127 for c in value),'MULTI_IO_PATH_INVALID')
 parts=value[1:].split('/');require(all(p not in ('','.','..') for p in parts),'MULTI_IO_PATH_INVALID')
 require(all(c in '._-' or unicodedata.category(c)[0] in 'LMN' for part in parts for c in part),'MULTI_IO_PATH_INVALID')
 require(len(value.encode('utf-16-le'))//2<=513 and all(len(p.encode('utf-16-le'))//2<=255 for p in parts),'MULTI_IO_PATH_INVALID');return value
def digest_file(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
class Worker:
 def __init__(self):
  self.host=os.environ.get('HOST','').rstrip('/');url=urllib.parse.urlsplit(self.host)
  require(url.scheme in ('http','https') and url.netloc and not url.username and not url.password and not url.query and not url.fragment and not url.path,'MULTI_IO_BACKEND_INVALID')
  raw=os.environ.get('AUTHHEADER','');require('\r' not in raw and '\n' not in raw and ':' in raw,'MULTI_IO_CREDENTIAL_REQUIRED');name,value=raw.split(':',1)
  require(name.lower()=='authorization' and value.strip().startswith('Bearer '),'MULTI_IO_CREDENTIAL_REQUIRED');self.auth=value.strip();self.opener=urllib.request.build_opener(NoRedirect())
  self.job=identifier(os.environ.get('JOB_ID'));self.expected=os.environ.get('MDS_JOB_IO_MANIFEST_SHA256','');require(re.fullmatch('[a-f0-9]{64}',self.expected),'MULTI_IO_MANIFEST_IDENTITY_REQUIRED')
  self.control=pathlib.Path('/tmp/job-io');require(not self.control.exists(),'MULTI_IO_CONTROL_DIRECTORY_EXISTS');self.control.mkdir(mode=0o700)
 def request(self,path,method='GET',data=None,headers=None):
  require(path.startswith('/api/') and not path.startswith('//'),'MULTI_IO_REQUEST_PATH_INVALID')
  values={'Authorization':self.auth};values.update(headers or {})
  try:return self.opener.open(urllib.request.Request(self.host+path,data=data,method=method,headers=values),timeout=120)
  except urllib.error.HTTPError as error:raise IoFailure('MULTI_IO_HTTP_REJECTED_'+str(error.code))
  except (OSError,urllib.error.URLError):raise IoFailure('MULTI_IO_HTTP_UNAVAILABLE')
 def json(self,path,limit=32*1024*1024):
  with self.request(path) as response:raw=response.read(limit+1)
  require(len(raw)<=limit,'MULTI_IO_METADATA_TOO_LARGE')
  try:return json.loads(raw.decode('utf-8'))
  except (ValueError,UnicodeError):raise IoFailure('MULTI_IO_METADATA_INVALID')
 def route(self,item):return '/api/artifacts/'+item['repoId']+'/commits/'+item['commitId']+'/files'
 def manifest(self):
  value=self.json('/api/jobs/'+self.job+'/io-manifest',8*1024*1024)
  require(hashlib.sha256(canonical(value)).hexdigest()==self.expected,'MULTI_IO_MANIFEST_IDENTITY_MISMATCH')
  require(value.get('schema')=='job-io-manifest-v1' and value.get('jobId')==self.job,'MULTI_IO_MANIFEST_SCHEMA_INVALID');identifier(value.get('runId'))
  for kind,primary,secondary in [('inputs','/tmp/in','/tmp/inputs'),('outputs','/tmp/out','/tmp/outputs')]:
   rows=value.get(kind);require(isinstance(rows,list) and len(rows)>0,'MULTI_IO_EMPTY_DECLARATION');seen=set();primaries=0
   for item in rows:
    require(isinstance(item,dict),'MULTI_IO_DECLARATION_INVALID');repo=identifier(item.get('repoId'));identifier(item.get('commitId'));require(repo not in seen,'MULTI_IO_DUPLICATE_DECLARATION');seen.add(repo)
    require(type(item.get('primary')) is bool,'MULTI_IO_PRIMARY_INVALID');primaries+=int(item['primary']);expected=primary if item['primary'] else secondary+'/'+repo
    require(item.get('localPath')==expected,'MULTI_IO_LOCAL_PATH_INVALID')
    if kind=='inputs':require(isinstance(item.get('paths'),list) and all(isinstance(p,str) for p in item['paths']),'MULTI_IO_SELECTORS_INVALID')
    root=pathlib.Path(expected);require(not root.is_symlink(),'MULTI_IO_ROOT_SYMLINK')
    if root.exists():require(root.is_dir() and not any(root.iterdir()),'MULTI_IO_ROOT_NOT_EMPTY')
    else:root.mkdir(parents=True,mode=0o700)
   require(primaries==1,'MULTI_IO_PRIMARY_INVALID')
  (self.control/'manifest.json').write_bytes(canonical(value));(self.control/'manifest.json').chmod(0o400);return value
 def cache(self,manifest):
  item=manifest.get('previousIndexCache')
  if item is None:return
  require(identifier(item.get('repoId')) and identifier(item.get('commitId')) and item.get('localPath')=='/tmp/previous/professional/assets' and item.get('paths')==[],'MULTI_IO_CACHE_DECLARATION_INVALID')
  try:
   rows=self.listing(item);root=pathlib.Path(item['localPath']);root.mkdir(parents=True,exist_ok=True)
   for row in rows:
    prefix='/professional/assets/';name=row['logicalPath']
    if not name.startswith(prefix) or not name.endswith('.json'):continue
    require(row['sizeBytes']<=16*1024*1024,'MULTI_IO_CACHE_ENTRY_TOO_LARGE');path=root/name[len(prefix):];path.parent.mkdir(parents=True,exist_ok=True)
    with self.request(self.route(item)+'/content?'+urllib.parse.urlencode({'path':name})) as response:raw=response.read(row['sizeBytes']+1)
    require(len(raw)==row['sizeBytes'] and hashlib.sha256(raw).hexdigest()==row['sha256'],'MULTI_IO_CACHE_IDENTITY_MISMATCH');path.write_bytes(raw)
  except IoFailure:print('MULTI_IO_OPTIONAL_CACHE_UNAVAILABLE',file=sys.stderr)
 def listing(self,item):
  result=[];total=None;page=1;size=int(os.environ.get('FILE_LIST_PAGE_SIZE','1000'));require(1<=size<=10000,'MULTI_IO_PAGE_SIZE_INVALID')
  while True:
   value=self.json(self.route(item)+'?'+urllib.parse.urlencode({'pageIdx':page,'pageSize':size}));rows=value.get('fileRespList');count=value.get('total')
   require(isinstance(rows,list) and type(count) is int and count>=0,'MULTI_IO_LISTING_INVALID')
   if total is None:total=count
   require(count==total and len(result)+len(rows)<=total and (rows or len(result)==total),'MULTI_IO_LISTING_INCOMPLETE')
   result.extend(rows)
   if len(result)==total:break
   page+=1
  names=set()
  for row in result:
   require(isinstance(row,dict),'MULTI_IO_FILE_METADATA_INVALID');name=logical(row.get('logicalPath'));require(name not in names,'MULTI_IO_DUPLICATE_INPUT_PATH');names.add(name)
   require(type(row.get('sizeBytes')) is int and row['sizeBytes']>=0 and isinstance(row.get('sha256'),str) and re.fullmatch('[a-f0-9]{64}',row['sha256']),'MULTI_IO_FILE_IDENTITY_REQUIRED')
  # Match the server's file-or-directory selector semantics independently.
  selected=set()
  for selector in item['paths']:
   path='/' if selector=='/' else logical(selector);prefix='/' if path=='/' else path+'/'
   exact=path in names;children={name for name in names if name.startswith(prefix)}
   require(not (exact and children),'MULTI_IO_AMBIGUOUS_SELECTOR')
   require(exact or children,'MULTI_IO_SELECTED_INPUT_MISSING');selected.update({path} if exact else children)
  require(not item['paths'] or selected==names,'MULTI_IO_UNSELECTED_INPUT_RETURNED');return result
 def inputs(self,manifest):
  folder=self.control/'input-manifests';folder.mkdir(mode=0o700)
  count=0
  for item in manifest['inputs']:
   rows=self.listing(item);root=pathlib.Path(item['localPath'])
   for row in rows:
    target=root/row['logicalPath'][1:];target.parent.mkdir(parents=True,exist_ok=True);require(not target.exists() and not target.is_symlink(),'MULTI_IO_INPUT_COLLISION')
    h=hashlib.sha256();length=0
    with self.request(self.route(item)+'/content?'+urllib.parse.urlencode({'path':row['logicalPath']})) as response,target.open('xb') as stream:
     while True:
      block=response.read(1024*1024)
      if not block:break
      length+=len(block);require(length<=row['sizeBytes'],'MULTI_IO_INPUT_SIZE_MISMATCH');h.update(block);stream.write(block)
    require(length==row['sizeBytes'] and h.hexdigest()==row['sha256'],'MULTI_IO_INPUT_IDENTITY_MISMATCH');target.chmod(0o400);count+=1
   (folder/(item['repoId']+'.json')).write_bytes(canonical({'fileRespList':rows,'total':len(rows)}))
   # Preserve the previous generic metadata alias only if it cannot overwrite a business file.
   if not (root/'files.json').exists():(root/'files.json').write_bytes(canonical({'fileRespList':rows,'total':len(rows)}))
   if not (root/'files.tsv').exists():
    lines=[]
    for row in rows:
     fields=[str(row.get(key) or '') for key in ['logicalPath','contentType','bucketName','fileKey']];require(not any(any(c in value for c in '\t\r\n') for value in fields),'MULTI_IO_LEGACY_METADATA_INVALID');lines.append('\t'.join(fields))
    (root/'files.tsv').write_text('\n'.join(lines)+('\n' if lines else ''),encoding='utf-8')
  return count
 def execute(self):
  expected=os.environ.get('MDS_ENTRYPOINT_SHA256','');require(re.fullmatch('[a-f0-9]{64}',expected),'MULTI_IO_ENTRYPOINT_IDENTITY_REQUIRED');target=self.control/'command.sh'
  with self.request('/api/jobs/'+self.job+'/entrypoint/content?filePath=%2Fcommand.sh') as response:raw=response.read(16*1024*1024+1)
  require(0<len(raw)<=16*1024*1024 and hashlib.sha256(raw).hexdigest()==expected,'MULTI_IO_ENTRYPOINT_IDENTITY_MISMATCH');target.write_bytes(raw);target.chmod(0o500)
  env=dict(os.environ,MDS_JOB_IO_MANIFEST=str(self.control/'manifest.json'),INPUT_DIR='/tmp/in',OUTPUT_DIR='/tmp/out',FILES_JSON='/tmp/in/files.json',LIST_TSV='/tmp/in/files.tsv',CMD_PATH=str(target))
  require(subprocess.run(['bash',str(target)],env=env).returncode==0,'MULTI_IO_USER_COMMAND_FAILED')
 def outputs(self,manifest):
  files=[]
  for item in manifest['outputs']:
   root=pathlib.Path(item['localPath']);require(root.is_dir() and not root.is_symlink(),'MULTI_IO_OUTPUT_ROOT_INVALID')
   for directory,dirs,names in os.walk(str(root),followlinks=False):
    for name in dirs:require(not (pathlib.Path(directory)/name).is_symlink(),'MULTI_IO_OUTPUT_SYMLINK')
    for name in sorted(names):
     path=pathlib.Path(directory)/name;info=path.lstat();require(stat.S_ISREG(info.st_mode) and not path.is_symlink(),'MULTI_IO_OUTPUT_NOT_REGULAR');relative=logical('/'+str(path.relative_to(root)));files.append((item,path,relative,info.st_size,digest_file(path),info.st_ino,info.st_mtime_ns))
  # All output trees are validated before the first write to any repository.
  receipts=[]
  for item,path,relative,length,digest,inode,mtime in files:
   flags=os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)
   with os.fdopen(os.open(str(path),flags),'rb') as stream:
    info=os.fstat(stream.fileno());require(stat.S_ISREG(info.st_mode) and info.st_size==length and info.st_ino==inode and info.st_mtime_ns==mtime,'MULTI_IO_OUTPUT_CHANGED')
    content_type=mimetypes.guess_type(relative)[0] or 'application/octet-stream'
    with self.request(self.route(item)+'?'+urllib.parse.urlencode({'path':relative,'contentType':content_type}),'PUT',stream,{'Content-Type':content_type,'Content-Length':str(length)}) as response:body=response.read(1024*1024+1)
    require(len(body)<=1024*1024,'MULTI_IO_UPLOAD_RECEIPT_INVALID')
    after=os.fstat(stream.fileno());require(after.st_size==length and after.st_mtime_ns==mtime and digest_file(path)==digest,'MULTI_IO_OUTPUT_CHANGED')
   receipts.append({'repoId':item['repoId'],'commitId':item['commitId'],'path':relative,'bytes':length,'sha256':digest})
  (self.control/'upload-receipts.json').write_bytes(canonical(receipts));return len(receipts)
 def run(self):
  manifest=self.manifest();inputs=self.inputs(manifest);self.cache(manifest);self.execute();outputs=self.outputs(manifest)
  print(json.dumps({'schema':'job-multi-io-execution-v1','inputRepositories':len(manifest['inputs']),'outputRepositories':len(manifest['outputs']),'verifiedInputFiles':inputs,'uploadedOutputFiles':outputs}),flush=True)
if __name__=='__main__':
 try:Worker().run()
 except IoFailure as error:print(str(error),file=sys.stderr);raise SystemExit(1)
 except Exception:print('MULTI_IO_EXECUTION_FAILED',file=sys.stderr);raise SystemExit(1)

PY_FROZEN_MULTI_IO_V1
  exit $?
fi

###############################################################################
# Helpers
###############################################################################
json_get_field() {
  _json="$1"
  _field="$2"

  [ -n "${_json:-}" ] || die "json_get_field got empty json for field=${_field}"

  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$_json" | jq -r ".${_field} // empty"
    return 0
  fi

  python3 - "$_field" "$_json" <<'PY'
import json,sys
field=sys.argv[1]
raw=sys.argv[2]
data=json.loads(raw)
v=data.get(field,"")
if v is None: v=""
print(v)
PY
}


###############################################################################
# 1) List files
###############################################################################
log "[1/5] 列出所有的文件 -> $INPUT_DIR/files.json"
log "FILES_URL==========================>$FILES_URL"
python3 - "$FILES_URL" "$AUTHHEADER" "$PAGE_SIZE" "$INPUT_DIR/files.json" <<'PY'
import json
import sys
import urllib.parse
import urllib.request

base_url, auth_header, page_size, output_path = sys.argv[1:5]
page_size = int(page_size)
page_idx = 1
all_files = []
last_payload = {}
expected_total = None

while True:
    sep = "&" if "?" in base_url else "?"
    url = f"{base_url}{sep}{urllib.parse.urlencode({'pageIdx': page_idx, 'pageSize': page_size})}"
    req = urllib.request.Request(url)
    if auth_header:
        name, value = auth_header.split(":", 1)
        req.add_header(name.strip(), value.strip())
    with urllib.request.urlopen(req, timeout=120) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    files = payload.get("fileRespList") or []
    total = int(payload.get("total") if payload.get("total") is not None else len(all_files) + len(files))
    if expected_total is None:
        expected_total = total
    if total != expected_total or (not files and len(all_files) < total):
        raise ValueError("mandatory input listing is incomplete or changed")
    all_files.extend(files)
    last_payload = payload
    print(f"listed page={page_idx} files={len(files)} accumulated={len(all_files)} total={total}", file=sys.stderr)
    if len(all_files) > total:
        raise ValueError("mandatory input listing exceeds declared total")
    if len(all_files) == total:
        break
    page_idx += 1

last_payload["fileRespList"] = all_files
last_payload["pageIdx"] = 1
last_payload["pageSize"] = page_size
last_payload["total"] = len(all_files)
with open(output_path, "w", encoding="utf-8") as f:
    json.dump(last_payload, f, ensure_ascii=False)
PY
log "GOT FILES ==================="

FILES_JSON="$INPUT_DIR/files.json"

###############################################################################
# 2) Download all files: ?path=<logicalPath from fileRespList>
###############################################################################
log "[2/5] 下载所有的文件 -> $INPUT_DIR"
log "FILES_URL: $FILES_URL"
log "DOWNLOAD_URL: $DOWNLOAD_URL"

LIST_TSV="$INPUT_DIR/files.tsv"

python3 - "$FILES_JSON" >"$LIST_TSV" <<'PY'
import json,pathlib,sys
p=sys.argv[1]
data=json.load(open(p,'r',encoding='utf-8'))

lst=data.get("fileRespList") or []
seen=set()
for x in lst:
    lp=x.get("logicalPath","") or ""
    parts=pathlib.PurePosixPath(lp).parts
    if not lp.startswith('/') or any(c in lp for c in '\t\r\n') or '\\' in lp or '..' in parts or lp in seen:
        raise ValueError("invalid or duplicate mandatory input path")
    seen.add(lp)
    ct=x.get("contentType","") or ""
    bn=x.get("bucketName","") or ""
    fk=x.get("fileKey","") or ""
    print(f"{lp}\t{ct}\t{bn}\t{fk}")
PY

while IFS="$(printf '\t')" read -r logicalPath contentType bucketName fileKey; do
  [ -n "$logicalPath" ] || continue

  rel="${logicalPath#/}"
  out="$INPUT_DIR/$rel"
  mkdir -p "$(dirname "$out")"

  log "downloading: path=$logicalPath -> $out"

  http_code="$(
    curl -sS -X GET -G \
      -H "$AUTHHEADER" \
      --data-urlencode "path=$logicalPath" \
      -o "$out" \
      -w "%{http_code}" \
      "$DOWNLOAD_URL" || printf '%s' "000"
  )"

  case "$http_code" in
    2??) : ;;
    *) die "mandatory input download failed http=$http_code path=$logicalPath";;
  esac
done <"$LIST_TSV"

python3 - "$FILES_JSON" "$INPUT_DIR" <<'PY'
import hashlib,json,pathlib,sys
manifest=json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
root=pathlib.Path(sys.argv[2]).resolve()
for item in manifest.get('fileRespList') or []:
    path=(root/item['logicalPath'].lstrip('/')).resolve()
    if root not in path.parents or not path.is_file():
        raise ValueError('mandatory input file is missing')
    expected_size=item.get('sizeBytes')
    if expected_size is not None and path.stat().st_size != int(expected_size):
        raise ValueError('mandatory input byte count mismatch')
    expected_sha=item.get('sha256')
    if expected_sha:
        digest=hashlib.sha256()
        with path.open('rb') as stream:
            for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
        if digest.hexdigest().lower()!=str(expected_sha).lower():
            raise ValueError('mandatory input checksum mismatch')
print('[download] mandatory input files verified='+str(len(manifest.get('fileRespList') or [])),file=sys.stderr)
PY

log "[download] done -> $INPUT_DIR"

# Reuse immutable per-asset professional shards from the preceding published
# index. Failure to fetch this optimization is safe: the builder reparses the
# current raw snapshot and never uses a partial cache as authoritative input.
if [ -n "${PREVIOUS_INDEX_COMMIT_ID:-}" ] && [ -n "${OUTPUT_ID:-}" ]; then
  PREVIOUS_ASSET_DIR="${PREVIOUS_ASSET_DIR:-/tmp/previous/professional/assets}"
  mkdir -p "$PREVIOUS_ASSET_DIR"
  python3 - "$HOST" "$OUTPUT_ID" "$PREVIOUS_INDEX_COMMIT_ID" "$AUTHHEADER" "$PREVIOUS_ASSET_DIR" <<'PY' || \
    log "[incremental] previous shard fetch unavailable; full professional parse will be used"
import json
import pathlib
import sys
import urllib.parse
import urllib.request

host, repo_id, commit_id, auth_header, output_dir = sys.argv[1:6]
base = f"{host}/api/artifacts/{repo_id}/commits/{commit_id}/files"
headers = {}
if auth_header:
    name, value = auth_header.split(":", 1)
    headers[name.strip()] = value.strip()
assets = []
page = 1
while True:
    url = base + "?" + urllib.parse.urlencode({"pageIdx": page, "pageSize": 1000})
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as response:
        payload = json.loads(response.read().decode("utf-8"))
    files = payload.get("fileRespList") or []
    assets.extend(item for item in files if str(item.get("logicalPath") or "").startswith(("/professional/assets/", "/professional/derived/")))
    if not files or page * 1000 >= int(payload.get("total") or 0):
        break
    page += 1
target = pathlib.Path(output_dir)
for item in assets:
    logical_path = str(item["logicalPath"])
    url = base + "/content?" + urllib.parse.urlencode({"path": logical_path})
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as response:
        content = response.read()
        if item.get("sha256"):
            import hashlib
            if hashlib.sha256(content).hexdigest().lower() != str(item["sha256"]).lower():
                raise ValueError("previous derived artifact checksum mismatch")
        directory = target.parent / "derived" if logical_path.startswith("/professional/derived/") else target
        directory.mkdir(parents=True, exist_ok=True)
        (directory / pathlib.Path(logical_path).name).write_bytes(content)
print(f"[incremental] downloaded previous asset shards={len(assets)}", file=sys.stderr)
PY
fi


log "[3/5]  开始下载训练脚本==================================="

CMD_PATH="/root/userCommand.sh"

http_code="$(
    curl -sS -X GET -G \
      -H "$AUTHHEADER" \
      --data-urlencode "filePath=/command.sh" \
      -o "$CMD_PATH" \
      -w "%{http_code}" \
      "$SCRIPT_URL" || printf '%s' "000"
  )"

if [ "$http_code" != "200" ]; then
  log "[boot] curl failed: url=$url http_code=$http_code" >&2
  exit 1
fi

if [ ! -s "$CMD_PATH" ]; then
  log "[boot] downloaded file is empty: $CMD_PATH" >&2
  exit 1
fi

python3 - "$CMD_PATH" "${MDS_ENTRYPOINT_SHA256:-}" <<'PY'
import hashlib,pathlib,re,sys
expected=sys.argv[2]
if not re.fullmatch(r'[0-9a-f]{64}',expected):
    raise ValueError('frozen entrypoint checksum is required')
if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest()!=expected:
    raise ValueError('frozen entrypoint checksum mismatch')
PY

log "[3/5] 完成下载训练脚本==================================="

chmod +x "$CMD_PATH"

log "[4/5] 开始训练文件===================================="
if ! ( source "$CMD_PATH" ); then
  die "job command failed; generated files will not be uploaded"
fi

log "[4/5]  结束训练文件==================================="

UPLOAD_URL="${UPLOAD_URL:-$HOST/api/artifacts/$OUTPUT_ID/commits/$OUTPUT_COMMIT_ID/files}"
OUTPUT_DIR="/tmp/out"
UPLOAD_RESP_DIR="/tmp/upload-responses"
mkdir -p "$UPLOAD_RESP_DIR"
log "UPLOAD URL =================> $UPLOAD_URL"

# URL encode（避免文件名带空格/中文/特殊字符导致 query 失败）
urlencode() {
  python3 - <<'PY' "$1"
import sys, urllib.parse
print(urllib.parse.quote(sys.argv[1], safe=""))
PY
}

# 获取 MIME Type；拿不到就用 application/octet-stream
get_mime() {
  local fp="$1"
  local mt
  mt="$(file -b --mime-type "$fp" 2>/dev/null || true)"
  [[ -n "$mt" ]] && echo "$mt" || echo "application/octet-stream"
}

# 生成输出文件路径：按相对路径映射到 OUTPUT_DIR 下，避免重名覆盖
resp_path_for() {
  local fp="$1"
  local rel="${fp#${OUTPUT_DIR%/}/}"     # 相对路径
  local out="${UPLOAD_RESP_DIR%/}/${rel}"
  mkdir -p "$(dirname "$out")"
  echo "${out}.json"
}

echo "Uploading files under: ${OUTPUT_DIR}"
echo "UPLOAD_URL: ${UPLOAD_URL}"
echo "OUTPUT_DIR: ${OUTPUT_DIR}"

log "[5/5]  上传文件下所有的文件"
# -print0 安全处理空格/换行等
if ! find "${OUTPUT_DIR}" -type f ! -name '*.upload_resp.json' -print0 | while IFS= read -r -d '' FILE_PATH; do
  FILE_NAME="$(basename "$FILE_PATH")"
  CONTENT_TYPE="$(get_mime "$FILE_PATH")"
  RESP_FILE="$(resp_path_for "$FILE_PATH")"
  UPLOAD_PATH="/${FILE_PATH#/tmp/out/}"
log "FILEPATH==========>$FILE_PATH"
log "CONTENT_TYPE==========>$CONTENT_TYPE"
log "RESP_FILE==========>$RESP_FILE"
log "UPLOAD_PATH==========>$UPLOAD_PATH"

  # fileName 和 contentType 放 query 里，注意编码
  FILE_NAME_ENC="$(urlencode "$FILE_NAME")"
  CONTENT_TYPE_ENC="$(urlencode "$CONTENT_TYPE")"

  # curl 形式：-o resp.json -w http_code
  echo "==> Uploading: $FILE_PATH"
  echo "=====> FILENAME=======: $FILE_NAME"
  echo "    name: ${FILE_NAME} | content-type: ${CONTENT_TYPE}"

  HTTP_CODE="$(
    curl -sS -X PUT \
      -H "$AUTHHEADER" \
      -H "Content-Type: ${CONTENT_TYPE}" \
      -T "${FILE_PATH}" \
      -o "${RESP_FILE}" \
      -w "%{http_code}" \
      "${UPLOAD_URL}?path=${UPLOAD_PATH}&contentType=${CONTENT_TYPE_ENC}" \
    || true
  )"

  echo "    http_code: ${HTTP_CODE}"
  echo "    resp: ${RESP_FILE}"

  if [[ ! "$HTTP_CODE" =~ ^2[0-9]{2}$ ]]; then
    echo "!! Upload failed (http ${HTTP_CODE}): ${FILE_PATH}" >&2
    exit 1
  fi
done; then
  die "one or more generated files failed to upload"
fi

echo "Done."

log "[upload] done"

# Index publication is a two-phase operation.  Generated files are uploaded
# first; only then does the index-specific callback verify, preload and switch
# the active snapshot.  Other generic jobs do not carry this callback helper.
if [ -n "${BACKEND_CALLBACK_READY:-${backendCallbackReady:-}}" ]; then
  INDEX_CALLBACK_PROGRAM=/app/index_callback.py
  case "${MDS_NATIVE_INDEX_CALLBACK_KIND:-LEGACY_REGISTERED_SIF}" in
    LEGACY_REGISTERED_SIF) ;;
    CURRENT_JAR_CAPSULE_V1)
      INDEX_CALLBACK_PROGRAM=/tmp/approved-native-index/index_callback.py
      INDEX_CALLBACK_EXPECTED_SHA256="${MDS_NATIVE_INDEX_CALLBACK_SHA256:-}"
      [[ "$INDEX_CALLBACK_EXPECTED_SHA256" =~ ^[a-f0-9]{64}$ ]] || die "approved native callback digest is missing"
      [ -f "$INDEX_CALLBACK_PROGRAM" ] && [ ! -L "$INDEX_CALLBACK_PROGRAM" ] || die "approved native callback program is missing"
      need_cmd sha256sum
      INDEX_CALLBACK_ACTUAL_SHA256="$(sha256sum -- "$INDEX_CALLBACK_PROGRAM")"
      INDEX_CALLBACK_ACTUAL_SHA256="${INDEX_CALLBACK_ACTUAL_SHA256%% *}"
      [ "$INDEX_CALLBACK_ACTUAL_SHA256" = "$INDEX_CALLBACK_EXPECTED_SHA256" ] || die "approved native callback program changed"
      ;;
    *) die "unknown index callback source" ;;
  esac
  [ -f "$INDEX_CALLBACK_PROGRAM" ] || die "index publication callback helper is missing"
  python3 "$INDEX_CALLBACK_PROGRAM" || die "index publication callback failed"
  if [ "${MDS_NATIVE_INDEX_CALLBACK_KIND:-}" = "CURRENT_JAR_CAPSULE_V1" ]; then
    (umask 077; printf '{"schema":"NATIVE_INDEX_CALLBACK_EXECUTION_V1","source":"CURRENT_JAR_CAPSULE_V1","path":"/tmp/approved-native-index/index_callback.py","sha256":"%s","completedSuccessfully":true}\n' "$INDEX_CALLBACK_ACTUAL_SHA256" > /tmp/controlled-native-callback-execution.json)
  fi
fi
