#!/usr/bin/env bash

# 下载、转换/训练、上传任务脚本
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
    all_files.extend(files)
    last_payload = payload
    total = int(payload.get("total") or len(all_files))
    print(f"listed page={page_idx} files={len(files)} accumulated={len(all_files)} total={total}", file=sys.stderr)
    if not files or len(all_files) >= total:
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
import json,sys
p=sys.argv[1]
data=json.load(open(p,'r',encoding='utf-8'))

lst=data.get("fileRespList") or []
for x in lst:
    lp=x.get("logicalPath","") or ""
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
    *) log "!! download failed http=$http_code path=$logicalPath (see: $out)";;
  esac
done <"$LIST_TSV"

log "[download] done -> $INPUT_DIR"


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

log "[3/5] 完成下载训练脚本==================================="

chmod +x "$CMD_PATH"

log "[4/5] 开始训练文件===================================="
( source "$CMD_PATH" )

log "[4/5]  结束训练文件==================================="

UPLOAD_URL="${UPLOAD_URL:-$HOST/api/artifacts/$OUTPUT_ID/commits/$OUTPUT_COMMIT_ID/files}"
OUTPUT_DIR="/tmp/out"
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
  local out="${OUTPUT_DIR%/}/${rel}"
  mkdir -p "$(dirname "$out")"
  echo "${out}.upload_resp.json"
}

echo "Uploading files under: ${OUTPUT_DIR}"
echo "UPLOAD_URL: ${UPLOAD_URL}"
echo "OUTPUT_DIR: ${OUTPUT_DIR}"

log "[5/5]  上传文件下所有的文件"
# -print0 安全处理空格/换行等
find "${OUTPUT_DIR}" -type f -print0 | while IFS= read -r -d '' FILE_PATH; do
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
done

echo "Done."

log "[upload] done"

