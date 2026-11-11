#!/usr/bin/env python3
"""Narrow backend CLI adapter for Singularity jobs inside one Slurm allocation.

Configure app.docker.binary to this executable. This is not a Docker daemon.
Unknown Docker options fail explicitly. State lives in a private per-job folder.
"""
import datetime
import hashlib
from runtime_source_binding import job_source_binding
import json
import os
from pathlib import Path
import re
import selectors
import shlex
import signal
import subprocess
import sys
import time
import uuid


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def redact(line):
    return re.sub(r'eyJ[A-Za-z0-9_.-]+', '[REDACTED_JWT]', line)


def root():
    job = os.environ.get('SLURM_JOB_ID', '')
    if not job.isdigit():
        raise ValueError('HPC runtime requires an active Slurm allocation')
    base = Path(os.environ['MDS_RUNTIME_STATE']).resolve() / job
    base.mkdir(mode=0o700, parents=True, exist_ok=True)
    return base


def folder(cid):
    if not re.fullmatch('[a-f0-9]{32}', cid):
        raise ValueError('Invalid task ID')
    path = root() / cid
    if not path.is_dir():
        history = list(root().parent.glob('*/' + cid))
        if len(history) != 1 or not history[0].is_dir():
            raise ValueError('No such object')
        path = history[0]
    return path


def save(path, value):
    temp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    temp.write_text(json.dumps(value))
    temp.replace(path)


def read_state(path):
    state = json.loads((path / 'state.json').read_text())
    if path.parent.name != os.environ.get('SLURM_JOB_ID') and state['State']['Status'] != 'exited':
        # serve.py holds a shared-filesystem deployment lock. A new allocation
        # can inspect earlier runs only after the previous deployment released it.
        state['State'].update(Status='exited', ExitCode=137, FinishedAt=now(),
                              Error='Previous Slurm allocation ended before task completion')
        save(path / 'state.json', state)
        credentials = path / 'payload.env'
        if credentials.exists():
            credentials.unlink()
        spec_path = path / 'spec.json'
        if spec_path.exists():
            spec = json.loads(spec_path.read_text())
            spec['env'] = {}
            save(spec_path, spec)
    return state


def parse_run(argv):
    result = {'labels': {}, 'env': {}, 'bind': [], 'cpus': 1, 'memory_mb': 256,
              'pids': 512, 'gpu': False}
    index = 0
    while index < len(argv) and argv[index].startswith('-'):
        key = argv[index]
        index += 1
        if key == '-d':
            continue
        if index >= len(argv):
            raise ValueError('Missing option value')
        value = argv[index]
        index += 1
        if key == '--name':
            result['name'] = value
        elif key == '--label':
            k, v = value.split('=', 1)
            result['labels'][k] = v
        elif key == '-e':
            k, v = value.split('=', 1)
            if not re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', k) or '\n' in v or '\r' in v:
                raise ValueError('Unsupported environment value')
            result['env'][k] = v
        elif key == '--cpus':
            if float(value) != int(float(value)):
                raise ValueError('Slurm requires integer CPU requests')
            result['cpus'] = int(float(value))
        elif key in ('--memory', '--memory-swap'):
            if not re.fullmatch('[0-9]+m', value):
                raise ValueError('HPC memory must use integer MiB')
            result['memory_mb' if key == '--memory' else 'swap_mb'] = int(value[:-1])
        elif key == '--pids-limit':
            result['pids'] = int(value)
        elif key == '--net':
            if value != 'host':
                raise ValueError('HPC runtime requires the allocated host network')
        elif key == '--ipc':
            if value != 'private':
                raise ValueError('HPC task IPC must be private; host IPC is forbidden')
        elif key == '--gpus':
            if value != 'all':
                raise ValueError('GPU selection must use the Slurm allocation')
            result['gpu'] = True
        elif key == '--mount':
            fields = dict(part.split('=', 1) for part in value.split(','))
            if fields != {'type': 'volume', 'source': 'sirius-evidence-cpu-queue',
                           'target': '/var/lock/mds-evidence'}:
                raise ValueError('Unregistered HPC shared volume')
            result['bind'].append(os.environ['MDS_EVIDENCE_LOCK_DIR'] + ':/var/lock/mds-evidence')
        else:
            raise ValueError('Unsupported HPC option: ' + key)
    if index >= len(argv):
        raise ValueError('Missing image')
    result['image'] = argv[index]
    result['payload'] = argv[index + 1:]
    if not result['payload']:
        raise ValueError('Explicit payload required')
    if 'swap_mb' in result:
        # Slurm's site-wide swap constraint must be verified before enabling this.
        if len(Path('/proc/swaps').read_text().strip().splitlines()) > 1:
            raise ValueError('This node has swap; no-swap enforcement needs site support')
        if result['swap_mb'] != result['memory_mb']:
            raise ValueError('Only no-swap jobs are supported')
    return result


def run(argv):
    spec = parse_run(argv)
    manifest = Path(os.environ['MDS_IMAGE_MANIFEST']).resolve(strict=True)
    registration = json.loads(manifest.read_text())[spec['image']]
    image = Path(registration['path']).resolve(strict=True)
    if not image.is_file() or image.suffix != '.sif':
        raise ValueError('Registered SIF image unavailable')
    # A typed managed task requires actual registered bytes, not an alias alone.
    spec['env'].pop('MDS_VERIFIED_IMAGE_SHA256', None)
    spec['env'].pop('MDS_VERIFIED_RUNTIME_SOURCE_SHA256', None)
    requested = spec['env'].get('MDS_REQUIRED_IMAGE_SHA256')
    registered = registration.get('sha256')
    if requested is not None and registered is not None and requested != registered:
        raise ValueError('Requested image digest differs from reviewed registration')
    required = requested if requested is not None else registered
    if required is not None:
        if not re.fullmatch('[a-f0-9]{64}', required):
            raise ValueError('Invalid required SIF digest')
        digest = hashlib.sha256()
        with image.open('rb') as stream:
            for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b''):
                digest.update(chunk)
        actual = digest.hexdigest()
        if actual != required:
            raise ValueError('Registered SIF image digest changed')
        spec['image_sha256'] = actual
        spec['image_path'] = str(image)
        spec['env']['MDS_VERIFIED_IMAGE_SHA256'] = actual
    source_binding = job_source_binding(registration)
    if source_binding is not None:
        if spec['image'] != source_binding['imageReference']:
            raise ValueError('Runtime source alias does not bind the registered source bytes')
        spec['runtime_source_binding'] = source_binding
        spec['env']['MDS_VERIFIED_RUNTIME_SOURCE_SHA256'] = source_binding['sourceSha256']
    if spec['cpus'] < 1 or spec['memory_mb'] < 64 or spec['pids'] < 1:
        raise ValueError('Invalid resource ceiling')
    cid = uuid.uuid4().hex
    path = root() / cid
    path.mkdir(mode=0o700)
    (path / 'home').mkdir()
    (path / 'tmp').mkdir()
    if spec.get('image_sha256'):
        save(path / 'image-proof.json', {'alias': spec['image'], 'path': spec['image_path'],
             'sha256': spec['image_sha256'], 'verifiedAt': now(), 'verificationRequired': True, 'sourceBinding': spec.get('runtime_source_binding')})
    save(path / 'spec.json', spec)
    save(path / 'state.json', {'Id': cid, 'Config': {'Labels': spec['labels']},
         'State': {'Status': 'created', 'ExitCode': 0, 'StartedAt': None, 'FinishedAt': None}})
    # The child retains the allocation's cgroup; job termination reaps it.
    with (path / 'supervisor.log').open('ab') as log:
        subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '_worker', cid],
                         stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    print(cid, flush=True)


def execute_worker(cid):
    path = folder(cid)
    if path.parent.name != os.environ.get('SLURM_JOB_ID'):
        raise ValueError('Cannot resume a worker outside its original allocation')
    spec = json.loads((path / 'spec.json').read_text())
    state = read_state(path)
    envfile = path / 'payload.env'
    envfile.write_text(''.join(k + '=' + shlex.quote(v) + '\n' for k, v in spec['env'].items()))
    args = [sys.executable, str(Path(__file__).with_name('slurm_exec.py')),
            '--manifest', os.environ['MDS_IMAGE_MANIFEST'], '--image', spec['image'],
            '--cpus', str(spec['cpus']), '--memory-mb', str(spec['memory_mb']),
            '--pids', str(spec['pids']), '--env-file', str(envfile),
            '--bind', str(path / 'home') + ':/root',
            '--bind', str(path / 'tmp') + ':/tmp']
    if spec.get('image_sha256'):
        args.extend(['--required-image-sha256', spec['image_sha256'],
                     '--registered-image-path', spec['image_path']])
    source_binding = spec.get('runtime_source_binding')
    if source_binding is not None:
        args.extend(['--runtime-binding-manifest', source_binding['manifestPath'],
                     '--runtime-binding-sha256', source_binding['manifestSha256']])
    for bind in spec['bind']:
        args.extend(['--bind', bind])
    if os.environ.get('MDS_HOSTS_FILE'):
        hosts = Path(os.environ['MDS_HOSTS_FILE']).resolve(strict=True)
        args.extend(['--bind', str(hosts) + ':/etc/hosts:ro'])
    if spec['gpu']:
        args.append('--gpu')
    args.extend(['--'] + spec['payload'])
    state['State'].update(Status='running', StartedAt=now())
    save(path / 'state.json', state)
    code = 125
    process = None
    try:
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        pending = b''
        stop_at = None
        with (path / 'output.log').open('a', buffering=1) as log:
            while True:
                if (path / 'stop').exists() and stop_at is None:
                    stop_at = time.monotonic()
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGTERM)
                if stop_at is not None and time.monotonic() - stop_at > 10 and process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                for key, _ in selector.select(0.2):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    if not chunk:
                        selector.unregister(key.fileobj)
                    pending += chunk
                    while b'\n' in pending:
                        line, pending = pending.split(b'\n', 1)
                        log.write(now() + ' ' + redact(line.decode('utf-8', 'replace')) + '\n')
                if process.poll() is not None and not selector.get_map():
                    if pending:
                        log.write(now() + ' ' + redact(pending.decode('utf-8', 'replace')) + '\n')
                    break
            code = process.wait()
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        state['State'].update(Status='exited', ExitCode=code if code >= 0 else 128 - code,
                              FinishedAt=now())
        save(path / 'state.json', state)
        envfile.unlink()
        # Remove tokens from retained specs once execution has ended.
        spec['env'] = {}
        save(path / 'spec.json', spec)


def worker(cid):
    path = folder(cid)
    if path.parent.name != os.environ.get('SLURM_JOB_ID'):
        raise ValueError('Cannot resume a worker outside its original allocation')
    try:
        execute_worker(cid)
    except Exception as exc:
        # Setup failures occur before execute_worker's process cleanup block.
        # They must still become terminal so inspect/stop do not wait forever.
        state = read_state(path)
        if state['State']['Status'] != 'exited':
            state['State'].update(Status='exited', ExitCode=125, FinishedAt=now(),
                                  Error='Supervisor failed: ' + type(exc).__name__)
            save(path / 'state.json', state)
        raise
    finally:
        credentials = path / 'payload.env'
        if credentials.exists():
            credentials.unlink()
        spec_path = path / 'spec.json'
        if spec_path.exists():
            spec = json.loads(spec_path.read_text())
            spec['env'] = {}
            save(spec_path, spec)


def main(argv):
    os.umask(0o077)
    if argv[:1] == ['-H']:
        argv = argv[2:]
    verb = argv[0]
    if verb == 'run':
        run(argv[1:])
    elif verb == '_worker':
        worker(argv[1])
    elif verb == 'inspect':
        print(json.dumps([read_state(folder(argv[-1]))]))
    elif verb in ('stop', 'rm'):
        path = folder(argv[-1])
        (path / 'stop').touch()
        deadline = time.monotonic() + 20
        while read_state(path)['State']['Status'] != 'exited':
            if time.monotonic() > deadline:
                raise ValueError('Task has not stopped; retained for diagnosis')
            time.sleep(0.2)
        print(argv[-1])  # Retain audit metadata, never recursively delete data.
    elif verb == 'volume' and argv[1] == 'rm':
        print(argv[-1])  # Per-task working directories are retained with the audit.
    elif verb == 'logs':
        path = folder(argv[-1])
        since = None
        if '--since' in argv:
            value = argv[argv.index('--since') + 1]
            if re.fullmatch('[0-9]+[smh]', value):
                delta = int(value[:-1]) * {'s': 1, 'm': 60, 'h': 3600}[value[-1]]
                since = time.time() - delta
            else:
                since = datetime.datetime.strptime(value[:19], '%Y-%m-%dT%H:%M:%S').replace(tzinfo=datetime.timezone.utc).timestamp()
        pos = 0
        while True:
            file = path / 'output.log'
            if file.exists():
                with file.open() as log:
                    log.seek(pos)
                    text = log.read()
                    pos = log.tell()
                    for line in text.splitlines(True):
                        stamp = datetime.datetime.strptime(line[:19], '%Y-%m-%dT%H:%M:%S').replace(tzinfo=datetime.timezone.utc).timestamp()
                        if since is None or stamp >= since:
                            print(line if '--timestamps' in argv else line.split(' ', 1)[-1], end='', flush=True)
            if '-f' not in argv or read_state(path)['State']['Status'] == 'exited':
                break
            time.sleep(0.2)
    elif verb == 'events':
        seen = {}
        while True:
            for path in root().iterdir():
                if not path.is_dir() or not (path / 'state.json').exists():
                    continue
                state = read_state(path)
                status = state['State']['Status']
                if seen.get(path.name) != status:
                    seen[path.name] = status
                    print(json.dumps({'id': path.name, 'Action': {'created': 'create',
                          'running': 'start', 'exited': 'die'}[status],
                          'Actor': {'Attributes': state['Config']['Labels']}}), flush=True)
            time.sleep(0.2)
    else:
        raise ValueError('Unsupported HPC operation: ' + verb)


if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except (ValueError, KeyError, OSError, IndexError) as exc:
        print('HPC runtime: ' + str(exc), file=sys.stderr)
        sys.exit(1)
