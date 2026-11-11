"""Bounded persistent quality worker. Credentials and raw URLs never enter logs."""
import os
import sys
import time
import threading
import tempfile
from pathlib import Path
from urllib.parse import urlencode
import requests
from quality import inspect_snapshot,RULES_VERSION

BASE=os.environ.get("BACKEND_BASE_URL","http://sirius:8888").rstrip("/")+"/api/mm/service/quality"
SESSION=requests.Session()
def credential_headers():
    token=Path(os.environ["DS_QUALITY_TOKEN_FILE"]).read_text().strip()
    if not token or len(token)>16384 or any(c.isspace() for c in token):
        raise RuntimeError("quality worker credential unavailable")
    return {"Authorization":"Bearer "+token}

def post(path,body=None):
    response=SESSION.post(BASE+path,json=body or {},headers=credential_headers(),timeout=45)
    response.raise_for_status()
    return response.json() if response.content else {}

def execute(job):
    prefix="/"+job["reportId"]
    query="?"+urlencode(dict(leaseToken=job["leaseToken"]))
    stop=threading.Event();lost=threading.Event()
    def renew():
        while not stop.wait(20):
            try:post(prefix+"/heartbeat"+query)
            except Exception:lost.set();return
    thread=threading.Thread(target=renew,daemon=True);thread.start()
    def progress():
        if lost.is_set():raise RuntimeError("quality lease lost")
    def download(path,target):
        progress()
        # The only allowed upstream is the configured backend, not user supplied URLs.
        headers=credential_headers()
        headers["Accept-Encoding"]="identity"
        with SESSION.get(BASE+prefix+"/content"+query+"&"+urlencode(dict(path=path)),
                         headers=headers,stream=True,timeout=(10,90)) as response:
            if response.status_code in (401,403,409):
                lost.set()
            response.raise_for_status()
            received=0
            try:
                with target.open("wb") as stream:
                    for block in response.iter_content(1024*1024):
                        progress()
                        stream.write(block)
                        received+=len(block)
            except requests.exceptions.RequestException:
                lost.set()
                raise
            declared=response.headers.get("Content-Length")
            if declared is not None and (not declared.isdecimal() or received!=int(declared)):
                lost.set()
                raise RuntimeError("quality source transfer interrupted")
    try:
        if job["rulesVersion"]!=RULES_VERSION:raise ValueError("quality rules version mismatch; request a new run")
        with tempfile.TemporaryDirectory(prefix="dataspace-quality-") as temp:
            result=inspect_snapshot(job,Path(temp)/"raw",download,progress)
            progress()
            post(prefix+"/heartbeat"+query)
            progress()
            post(prefix+"/complete"+query,result)
        print("quality completed",job["reportId"],flush=True)
    except Exception as exc:
        print("quality attempt failed",job["reportId"],type(exc).__name__,flush=True)
        try:post(prefix+"/fail"+query)
        except Exception:pass
    finally:stop.set();thread.join(timeout=1)

if __name__=="__main__":
    while True:
        try:
            job=post("/claim")
            if job:execute(job)
            else:time.sleep(5)
        except Exception as exc:
            print("quality backend unavailable",type(exc).__name__,flush=True);time.sleep(10)
