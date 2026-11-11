#!/usr/bin/env python3
"""Finite, single-node test deployment. Persistent data survives allocation exit."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import socket
import shutil
import secrets
import shlex
import subprocess
import time
import threading
import uuid
import urllib.request
from identity_maintenance_binding import create_binding, clear_binding, instance_id
from runtime_source_binding import RuntimeBindings


class Deployment:
    def __init__(self, args):
        if not os.environ.get('SLURM_JOB_ID', '').isdigit():
            raise RuntimeError('Slurm allocation required')
        os.umask(0o077)
        self.args = args
        if args.workspace_root is None:
            self.home = Path.home()
        else:
            selected_root = Path(args.workspace_root)
            if not selected_root.is_absolute() or selected_root.is_symlink():
                raise RuntimeError('Workspace root must be an absolute private directory')
            self.home = selected_root.resolve(strict=True)
            metadata = self.home.stat()
            if not self.home.is_dir() or metadata.st_uid != os.getuid() or metadata.st_mode & 0o077:
                raise RuntimeError('Workspace root must be owned by this UID with mode 0700')
        self.app = self.home / 'apps/mds-platform'
        self.packages = self.home / 'packages/mds-platform'
        self.data = self.home / 'data/mds-platform'
        self.snapshot = Path(args.snapshot).resolve(strict=True)
        if not (self.snapshot / '.verified').is_file() or not (self.app / 'source/.verified').is_file():
            raise RuntimeError('Run prepare_bundle.py successfully before starting services')
        self.secrets = json.loads((self.snapshot / 'secrets.json').read_text())
        self.run = self.data / 'runs' / os.environ['SLURM_JOB_ID']
        self.run.mkdir(parents=True)
        self.children = []
        self.lifecycle_lock = threading.RLock()
        self.quality_stop = threading.Event()
        self.quality_thread = None
        self.quality_workers = {}
        self.stopping = False
        self.identity_maintenance = None
        self.lock = (self.data / 'deployment.lock').open('a')
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.hosts = self.run / 'hosts'
        self.hosts.write_text('127.0.0.1 localhost db minio redis sirius search-service qa-service vllm local-receiver evidence-worker\n')
        for name in ('minio', 'pgdata', 'receiver-data', 'qa-traces', 'evidence-cache', 'evidence-locks', 'runtime', 'search-cache'):
            (self.data / name).mkdir(exist_ok=True)
        self.java = next((self.app / 'java17').glob('*/bin/java'))
        self.jar = self.app / 'sirius-backend.jar'
        self.bindings = None
        if not args.storage_only:
            self.bindings = RuntimeBindings(self.app / 'runtime-bindings.json')
            if self.bindings.app != self.app.resolve() or self.bindings.packages != self.packages.resolve():
                raise RuntimeError('Runtime binding does not match this application and package root')
            proof = self.bindings.verify_all(require_model=not args.cpu_only)
            (self.run / 'runtime-bindings-verified.json').write_text(json.dumps(proof, indent=2))
        required = ('postgres', 'minio', 'mc', 'redis')
        if not args.storage_only:
            required += ('index', 'search', 'qa') + (() if args.cpu_only else ('vllm',))
        for name in required:
            if not (self.packages / (name + '.sif')).is_file():
                raise RuntimeError('Missing SIF: ' + name)
        ports = [5432, 19000, 9001, 6379, 8888, 18080, 18081, 18082, 18083]
        if not args.cpu_only and not args.storage_only:
            if not os.environ.get('CUDA_VISIBLE_DEVICES'):
                raise RuntimeError('No allocated GPU')
            ports.append(8000)
        for port in ports:
            with socket.socket() as probe:
                # Rapid on-demand restarts can leave TCP TIME_WAIT entries.
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                try:
                    probe.bind(('127.0.0.1', port))
                except OSError as exc:
                    raise RuntimeError('Required loopback port unavailable: ' + str(port)) from exc
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, self.request_stop)

    def request_stop(self, *_):
        self.stopping = True

    def check(self):
        if self.stopping:
            raise InterruptedError('Stop requested')
        with self.lifecycle_lock:
            children = list(self.children)
        for name, proc, log in children:
            if proc.poll() is not None:
                with self.lifecycle_lock:
                    still_owned = any(record[1] is proc for record in self.children)
                if still_owned:
                    raise RuntimeError('Service exited: ' + name + ' code=' + str(proc.returncode))

    def env(self, **values):
        base = {'MM_INTERNAL_TOKEN': self.secrets['MM_INTERNAL_TOKEN']}
        base.update({
            'BACKEND_BASE_URL': 'http://127.0.0.1:8888',
            'PYTHONPATH': '/candidate', 'OMP_NUM_THREADS': '1', 'OPENBLAS_NUM_THREADS': '1',
            'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1', 'HF_HOME': '/tmp/hf',
            'TRANSFORMERS_CACHE': '/tmp/hf/transformers', 'XDG_CACHE_HOME': '/tmp/cache',
            'MKL_NUM_THREADS': '1', 'PYTHONDONTWRITEBYTECODE': '1',
        })
        base.update(values)
        return {k: str(v) for k, v in base.items()}

    def sif(self, name, payload, env=None, binds=(), gpu=False):
        binds = list(binds)
        if self.bindings is not None:
            self.bindings.verify_image(name)
            if name in ('search', 'qa'):
                binds.extend(self.bindings.binds(name))
        if name == 'mc':
            # The image defaults to /root/.mc, which an unprivileged HPC UID
            # cannot write. This private container /tmp is discarded on exit.
            env = dict(env or {}, MC_CONFIG_DIR='/tmp/mc-config')
        args = ['singularity', 'exec', '--cleanenv', '--no-eval', '--containall', '--no-home',
                '--no-privs', '--no-mount', 'cwd,home,hostfs,bind-paths', '--pwd', '/tmp', '--bind', str(self.hosts) + ':/etc/hosts:ro']
        # Images without /app use /tmp as a universal working directory.
        if name in ('postgres', 'minio', 'mc', 'redis'):
            pass
        elif name == 'vllm':
            args[args.index('/tmp')] = '/vllm-workspace'
        if env:
            envfile = self.run / (name + '-' + str(time.time_ns() if hasattr(time, 'time_ns') else int(time.time()*1e9)) + '.env')
            if any('\n' in str(v) or '\r' in str(v) for v in env.values()):
                raise ValueError('Invalid environment value')
            envfile.write_text(''.join(k + '=' + shlex.quote(str(v)) + '\n' for k, v in env.items()))
            args.extend(['--env-file', str(envfile)])
        for source, target in binds:
            args.extend(['--bind', str(source) + ':' + target])
        if gpu:
            args.extend(['--nv', '--env', 'CUDA_VISIBLE_DEVICES=' + os.environ['CUDA_VISIBLE_DEVICES']])
        return args + [str(self.packages / (name + '.sif'))] + payload

    def start(self, name, command, env=None):
        log = (self.run / (name + '.log')).open('ab', buffering=0)
        if name.startswith('quality-worker-') and self.quality_stop.is_set():
            log.close();raise InterruptedError('Quality worker start cancelled')
        proc = subprocess.Popen(command, env=env, stdout=log, stderr=log, start_new_session=True)
        with self.lifecycle_lock:
            self.children.append((name, proc, log))
        return proc

    def execute(self, name, command, timeout=600):
        with (self.run / (name + '.log')).open('ab') as log:
            subprocess.run(command, stdout=log, stderr=log, check=True, timeout=timeout)

    def wait_url(self, url, seconds=180, headers=None):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            self.check()
            try:
                request = urllib.request.Request(url, headers=headers or {})
                with urllib.request.urlopen(request, timeout=3) as response:
                    if response.status == 200:
                        return
            except Exception:
                time.sleep(2)
        raise TimeoutError('Health check timed out: ' + url)

    def boot(self):
        pg = self.data / 'pgdata'
        pg_bind = [(pg, '/pgdata'), (self.snapshot, '/snapshot:ro')]
        fresh = not (pg / 'PG_VERSION').exists()
        if fresh:
            password = self.run / 'pg-password'
            password.write_text(self.secrets['SPRING_DATASOURCE_PASSWORD'] + '\n')
            self.execute('initdb', self.sif('postgres', ['initdb', '-D', '/pgdata', '-U', 'sirius',
                '--auth-local=trust', '--auth-host=scram-sha-256', '--pwfile=/password'],
                binds=pg_bind + [(password, '/password:ro')]))
        self.start('postgres', self.sif('postgres', ['postgres', '-D', '/pgdata', '-h', '127.0.0.1', '-k', '/tmp'], binds=pg_bind))
        pg_env = {'PGPASSWORD': self.secrets['SPRING_DATASOURCE_PASSWORD']}
        for attempt in range(60):
            self.check()
            test = subprocess.run(self.sif('postgres', ['pg_isready', '-h', '127.0.0.1', '-U', 'sirius']), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            if test.returncode == 0:
                break
            time.sleep(1)
        else:
            raise TimeoutError('PostgreSQL startup')
        if fresh:
            self.execute('createdb', self.sif('postgres', ['createdb', '-h', '127.0.0.1', '-U', 'sirius', 'siriusdb'], pg_env))
            self.execute('restore-db', self.sif('postgres', ['pg_restore', '--exit-on-error', '--no-owner', '-h', '127.0.0.1', '-U', 'sirius', '-d', 'siriusdb', '/snapshot/postgres.dump'], pg_env, pg_bind))
            (self.data / 'database-restored').write_text(str(self.snapshot))
        elif not (self.data / 'database-restored').exists():
            raise RuntimeError('Partial database restore detected; preserve and diagnose before retrying')
        self.start('minio', self.sif('minio', ['minio', 'server', '/data', '--address', '127.0.0.1:19000', '--console-address', '127.0.0.1:9001'],
            {'MINIO_ROOT_USER': self.secrets['MINIO_ACCESS_KEY'], 'MINIO_ROOT_PASSWORD': self.secrets['MINIO_SECRET_KEY']}, [(self.data / 'minio', '/data')]))
        self.wait_url('http://127.0.0.1:19000/minio/health/live')
        init = self.bindings.resource_path() if self.bindings is not None else self.app / 'source/backend/src/main/resources/script/minio'
        minio_env = self.env(MINIO_ENDPOINT='http://127.0.0.1:19000', MINIO_ROOT_USER=self.secrets['MINIO_ACCESS_KEY'], MINIO_ROOT_PASSWORD=self.secrets['MINIO_SECRET_KEY'])
        minio_env.update({k: v for k, v in self.secrets.items() if k.startswith('MINIO_')})
        self.execute('minio-init', self.sif('mc', ['sh', '/scripts/init-minio.sh'], minio_env, [(init, '/scripts:ro')]))
        from urllib.parse import quote
        host = 'http://' + quote(self.secrets['MINIO_ACCESS_KEY'], safe='') + ':' + quote(self.secrets['MINIO_SECRET_KEY'], safe='') + '@127.0.0.1:19000'
        if not (self.data / 'objects-restored').exists():
            self.execute('restore-objects', self.sif('mc', ['mc', 'mirror', '--quiet', '/snapshot/objects/' + self.secrets['MINIO_BUCKET'], 'target/' + self.secrets['MINIO_BUCKET']],
                {'MC_HOST_target': host}, [(self.snapshot, '/snapshot:ro')]), timeout=1800)
            (self.data / 'objects-restored').write_text(str(self.snapshot))
        self.start('redis', self.sif('redis', ['redis-server', '--bind', '127.0.0.1', '--save', '', '--appendonly', 'no']))
        if self.args.storage_only:
            self.execute('object-list', self.sif('mc', ['mc', 'ls', '--recursive', '--json', 'target/' + self.secrets['MINIO_BUCKET']], {'MC_HOST_target': host}), timeout=1800)
            prefix = 'objects/' + self.secrets['MINIO_BUCKET'] + '/'
            expected = {entry['path'][len(prefix):]: entry['bytes']
                        for entry in json.loads((self.snapshot / 'manifest.json').read_text())
                        if entry['path'].startswith(prefix)}
            actual = {}
            for line in (self.run / 'object-list.log').read_text().splitlines():
                if not line.startswith('{'):
                    continue
                entry = json.loads(line)
                if entry.get('status') != 'success':
                    raise RuntimeError('Object listing returned an error')
                if entry.get('type') == 'file':
                    if entry['key'] in actual:
                        raise RuntimeError('Duplicate object listing key')
                    actual[entry['key']] = entry['size']
            if actual != expected:
                raise RuntimeError('Restored object key/size mismatch: expected=%d actual=%d' % (len(expected), len(actual)))
            (self.run / 'object-verification.json').write_text(json.dumps({'objects': len(actual), 'bytes': sum(actual.values()), 'verified': 'S3 object key and size against snapshot manifest'}))
            self.execute('storage-verification', self.sif('postgres', ['psql', '-h', '127.0.0.1', '-U', 'sirius', '-d', 'siriusdb', '-At', '-c',
                "select 'users',count(*) from fwd_user; select 'datasets',count(*) from mm_dataset; select 'indexes',count(*) from mm_index_version; select 'artifact_files',count(*) from artifact_file; select 'assets',count(*) from mm_asset;"], pg_env))
            print('STORAGE_RESTORE_VERIFIED', flush=True)
            return
        manifest = self.run / 'images.json'
        identity = json.loads((self.packages / 'image-identities.json').read_text())['index']
        # Explicit legacy image identities retain their baked program. New native
        # jobs freeze an alias derived from both SIF and complete source bytes.
        entries = {name: {'path': str(self.packages / 'index.sif'),
                         'sha256': self.bindings.value['sifSha256']['index']}
                   for name in (identity['reference'], identity['image_id'])}
        entries[self.bindings.index_reference] = self.bindings.image_entry()
        manifest.write_text(json.dumps(entries, indent=2))
        common = self.env(MINIO_ENDPOINT='http://127.0.0.1:19000', MINIO_PUBLIC_ENDPOINT='http://127.0.0.1:19000',
            SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:5432/siriusdb', SPRING_DATA_REDIS_HOST='127.0.0.1',
            MM_SEARCH_BASE_URL='http://127.0.0.1:18080', MM_EVIDENCE_WORKER_BASE_URL='http://127.0.0.1:18083',
            QA_SERVICE_BASE_URL='http://127.0.0.1:18081', APP_DOCKER_HOST='http://127.0.0.1:8888',
            APP_DOCKER_BINARY=str(self.app / 'runtime_cli.py'), SERVER_ADDRESS='127.0.0.1',
            APP_JOB_SCRIPT_PATH=str(self.app / 'command.sh'),
            MDS_IMAGE_MANIFEST=str(manifest), MDS_RUNTIME_STATE=str(self.data / 'runtime'),
            MDS_HOSTS_FILE=str(self.hosts),
            MDS_EVIDENCE_LOCK_DIR=str(self.data / 'evidence-locks'),
            MM_INDEX_BUILDER_IMAGE=self.bindings.index_reference,
            MM_INDEX_BUILDER_IMAGE_SHA256=self.bindings.value['sifSha256']['index'],
            MM_CONTROLLED_VIEW_PREVIEW_IMAGE=self.bindings.index_reference,
            MM_CONTROLLED_VIEW_PREVIEW_IMAGE_SHA256=self.bindings.value['sifSha256']['index'],
            MM_INDEX_BUILDER_CMD='env MODEL_PATH=/app/clip_model_chinese PYTHONPATH=/candidate HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 PYTHONDONTWRITEBYTECODE=1 python /candidate/build_index.py',
            LOG_PATH=str(self.run))
        common.update(self.secrets)
        # Bind the same verified JAR and private DB target used by this backend.
        common['MDS_INSTANCE_ID'] = instance_id(self.data, self.secrets.get('MDS_INSTANCE_ID'))
        self.identity_maintenance = create_binding(self.run, common['MDS_INSTANCE_ID'], 'siriusdb', self.java, self.jar, common, workspace_root=self.args.workspace_root)
        hostenv = dict(os.environ, **common)
        hostenv.setdefault('JAVA_TOOL_OPTIONS', '-XX:ActiveProcessorCount=2 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1')
        self.start('backend', [str(self.java), '-Djdk.net.hosts.file=' + str(self.hosts), '-jar', str(self.jar)], hostenv)
        if not (self.data / 'receipts-restored').exists():
            for item in (self.snapshot / 'receiver-data').iterdir():
                target = self.data / 'receiver-data' / item.name
                if target.exists():
                    raise RuntimeError('Partial receipt restore; preserve and diagnose')
                if item.is_dir():
                    shutil.copytree(str(item), str(target))
                else:
                    shutil.copy2(str(item), str(target))
            (self.data / 'receipts-restored').write_text(str(self.snapshot))
        receiver_env = dict(hostenv, DS_RECEIVER_TOKEN=self.secrets['MM_INTERNAL_TOKEN'], DS_RECEIVER_ROOT=str(self.data / 'receiver-data'), DS_RECEIVER_BIND='127.0.0.1')
        self.start('receiver', [str(self.java), '-Dloader.main=com.fwdrobo.sirius.dataspace.ReceiverMain', '-cp', str(self.jar), 'org.springframework.boot.loader.launch.PropertiesLauncher'], receiver_env)
        self.start('search', self.sif('search', ['uvicorn', 'app:app', '--app-dir', '/candidate', '--host', '127.0.0.1', '--port', '18080'], self.env(MAX_LOADED_INDEXES='1', MODEL_PATH='/app/app/clip_model_chinese', LOCAL_CACHE_DIR='/cache'), [(self.data / 'search-cache', '/cache')]))
        self.wait_url('http://127.0.0.1:18080/healthz', 300)
        self.start('evidence-worker', self.sif('index', ['python', '/evidence-worker/worker.py'], self.env(PYTHONPATH='/evidence-worker:/candidate', EVIDENCE_BIND_HOST='127.0.0.1', MDS_EVIDENCE_CPU_LOCK_DIR='/var/lock/mds-evidence', EVIDENCE_CACHE_DIR='/cache'),
            self.bindings.binds('evidence') + [(self.data / 'evidence-cache', '/cache'), (self.data / 'evidence-locks', '/var/lock/mds-evidence')]))
        self.wait_url('http://127.0.0.1:18083/healthz')
        if not self.args.cpu_only:
            if not (self.data / 'traces-restored').exists():
                self.execute('restore-traces', ['tar', '-xf', str(self.snapshot / 'qa-traces.tar'), '-C', str(self.data / 'qa-traces')])
                (self.data / 'traces-restored').write_text(str(self.snapshot))
            model = self.bindings.model
            vllm_key = secrets.token_hex(32)
            self.start('vllm', self.sif('vllm', ['python3', '-m', 'vllm.entrypoints.openai.api_server', '--host', '127.0.0.1',
                '--model', '/models/Qwen3.5-0.8B', '--served-model-name', 'Qwen/Qwen3.5-0.8B', '--max-model-len', '2048',
                '--gpu-memory-utilization', '0.78', '--max-num-seqs', '2', '--limit-mm-per-prompt', '{"image":2}',
                '--enforce-eager', '--skip-mm-profiling', '--mm-processor-cache-gb', '0'],
                self.env(MM_INTERNAL_TOKEN='', VLLM_API_KEY=vllm_key, HOME='/tmp', FLASHINFER_WORKSPACE_BASE='/tmp', XDG_CONFIG_HOME='/tmp/config', HF_HOME='/tmp/huggingface', XDG_CACHE_HOME='/tmp/cache',
                         VLLM_CACHE_ROOT='/tmp/vllm', TRITON_CACHE_DIR='/tmp/triton'),
                [(model, '/models/Qwen3.5-0.8B:ro')], gpu=True))
            self.wait_url('http://127.0.0.1:8000/v1/models', 600, {'Authorization': 'Bearer ' + vllm_key})
            self.start('qa', self.sif('qa', ['uvicorn', 'app:app', '--app-dir', '/candidate', '--host', '127.0.0.1', '--port', '18081'],
                self.env(VLLM_BASE_URL='http://127.0.0.1:8000', VLLM_API_KEY=vllm_key, MODEL_NAME='Qwen/Qwen3.5-0.8B', LLM_BACKEND='vllm',
                         SEARCH_SERVICE_BASE_URL='http://127.0.0.1:18080', QA_EVIDENCE_TRACE_DIR='/traces', MAX_IMAGE_PIXELS='524288'), [(self.data / 'qa-traces', '/traces')]))
            self.wait_url('http://127.0.0.1:18081/healthz')
        self.wait_url('http://127.0.0.1:8888/data-space/index.html')
        self.start_quality_workers(hostenv)
        (self.run / 'ready.json').write_text(json.dumps({'job': os.environ['SLURM_JOB_ID'], 'node': socket.gethostname(), 'cpu_only': self.args.cpu_only, 'identityMaintenance': self.identity_maintenance, 'runtimeBinding': self.bindings.proof()}))
        print('READY node=' + socket.gethostname() + ' port=8888 cpu_only=' + str(self.args.cpu_only), flush=True)
        if self.args.functional_check:
            from functional_probe import run
            run(self)
            return
        if self.args.http_check:
            from http_probe import run
            run(self)
            return
        deadline = time.monotonic() + self.args.duration
        while time.monotonic() < deadline and not self.stopping:
            self.check()
            time.sleep(1)

    def start_quality_workers(self, hostenv):
        # The trusted issuer reads server credentials locally; only its scoped
        # worker tokens cross the private pipe and per-worker directory bind.
        def refresh():
            while not self.quality_stop.is_set():
                try:
                    command = [str(self.java), '-Xmx256m', '-XX:ActiveProcessorCount=1',
                        '-Dloader.main=com.fwdrobo.sirius.security.QualityWorkerCredentialMain',
                        '-cp', str(self.jar), 'org.springframework.boot.loader.launch.PropertiesLauncher']
                    issued = subprocess.run(command, input=json.dumps({'action':'IssueQualityWorkers'}),
                        env=hostenv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True, timeout=20)
                    if self.quality_stop.is_set():
                        break
                    if issued.returncode != 0:
                        raise RuntimeError('QUALITY_CREDENTIAL_ISSUER_UNAVAILABLE')
                    result = json.loads(issued.stdout)
                    if result.get('success') is not True or not isinstance(result.get('workers'), list) or len(result['workers']) > 64:
                        raise RuntimeError('QUALITY_CREDENTIAL_ISSUER_INVALID')
                    selected = {}
                    for worker in result['workers']:
                        sid = str(uuid.UUID(worker['serviceSubjectId']))
                        token = worker['token']
                        if sid in selected or worker.get('expiresIn') != 120 or not isinstance(token,str) or not token or len(token)>16384 or any(c.isspace() for c in token):
                            raise RuntimeError('QUALITY_CREDENTIAL_ISSUER_INVALID')
                        selected[sid] = token
                    for sid in list(self.quality_workers):
                        if sid in selected:
                            continue
                        directory, proc = self.quality_workers.pop(sid)
                        with self.lifecycle_lock:
                            records = [record for record in self.children if record[1] is proc]
                            self.children = [record for record in self.children if record[1] is not proc]
                        if proc.poll() is None:
                            os.killpg(proc.pid, signal.SIGTERM)
                            try: proc.wait(timeout=5)
                            except subprocess.TimeoutExpired:
                                os.killpg(proc.pid,signal.SIGKILL);proc.wait()
                        for record in records: record[2].close()
                        for name in ('credential','credential.tmp'):
                            path = directory / name
                            if path.exists(): path.unlink()
                    for sid, token in selected.items():
                        if self.quality_stop.is_set(): break
                        directory = self.run / ('quality-worker-'+sid)
                        directory.mkdir(mode=0o700,exist_ok=True)
                        pending = directory / 'credential.tmp'
                        pending.write_text(token);pending.chmod(0o600);pending.replace(directory/'credential')
                        if sid not in self.quality_workers:
                            env = self.env(PYTHONPATH='/business-worker:/candidate',MM_INTERNAL_TOKEN='',DS_QUALITY_TOKEN_FILE='/quality-credential/credential')
                            proc = self.start('quality-worker-'+sid,self.sif('index',['python','/business-worker/worker.py'],env,
                                self.bindings.binds('business')+[(directory,'/quality-credential:ro')]))
                            self.quality_workers[sid] = (directory,proc)
                    (self.run/'quality-credential-state.json').write_text(json.dumps({'state':'SCOPED_WORKERS_READY','workers':len(selected),'credentialSeconds':120}))
                    delay = 30
                except Exception as error:
                    # stdout/stderr and tokens are deliberately excluded.
                    (self.run/'quality-credential-state.json').write_text(json.dumps({'state':'AUTHORITY_UNAVAILABLE','errorType':type(error).__name__}))
                    delay = 5
                self.quality_stop.wait(delay)
        self.quality_thread = threading.Thread(target=refresh,name='quality-credential-refresh',daemon=True)
        self.quality_thread.start()

    def close(self):
        self.quality_stop.set()
        if self.quality_thread is not None:
            self.quality_thread.join(timeout=25)
            if self.quality_thread.is_alive():
                self.quality_thread.join(timeout=25)
        task_root = self.data / 'runtime' / os.environ['SLURM_JOB_ID']
        if task_root.exists():
            for task in task_root.iterdir():
                if task.is_dir() and (task / 'state.json').exists():
                    (task / 'stop').touch()
            end = time.monotonic() + 20
            while time.monotonic() < end:
                active = [p for p in task_root.glob('*/state.json') if json.loads(p.read_text())['State']['Status'] != 'exited']
                if not active:
                    break
                time.sleep(0.2)
        # Producers first; then database and object storage. Never remove data.
        for name, proc, log in reversed(self.children):
            if proc.poll() is None:
                try:
                    os.killpg(proc.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                try:
                    proc.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(proc.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    proc.wait()
            log.close()
        for directory, proc in self.quality_workers.values():
            for name in ('credential','credential.tmp'):
                path = directory / name
                if path.exists(): path.unlink()
        clear_binding(self.run, workspace_root=self.args.workspace_root)
        for credential in list(self.run.glob('*.env')) + [self.run / 'pg-password']:
            if credential.exists():
                credential.unlink()
        self.lock.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--snapshot', required=True)
    parser.add_argument('--workspace-root', help='Absolute private directory containing apps, packages and data; defaults to the current user home')
    parser.add_argument('--duration', type=int, default=1800)
    parser.add_argument('--cpu-only', action='store_true')
    parser.add_argument('--storage-only', action='store_true')
    parser.add_argument('--http-check', action='store_true')
    parser.add_argument('--functional-check', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.duration <= 7200:
        parser.error('duration must be between 1 and 7200 seconds')
    deployment = Deployment(args)
    try:
        deployment.boot()
    finally:
        deployment.close()
