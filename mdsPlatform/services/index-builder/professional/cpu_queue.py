"""One CPU conversion at a time across workers sharing the queue volume."""
from contextlib import contextmanager
import os,time
from pathlib import Path

@contextmanager
def cpu_evidence_slot(timeout=120):
    import fcntl
    directory=Path(os.environ.get('MDS_EVIDENCE_CPU_LOCK_DIR','/tmp/mds-evidence-cpu'))
    directory.mkdir(parents=True,exist_ok=True)
    started=time.monotonic()
    with (directory/'conversion.lock').open('a+b') as stream:
        while True:
            try:fcntl.flock(stream.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB);break
            except BlockingIOError:
                if time.monotonic()-started>timeout:raise TimeoutError('EVIDENCE_CPU_QUEUE_TIMEOUT')
                time.sleep(.05)
        try:yield {'queueWaitMs':round((time.monotonic()-started)*1000,3)}
        finally:fcntl.flock(stream.fileno(),fcntl.LOCK_UN)
