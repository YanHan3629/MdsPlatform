"""Create a consistent test migration copy; always restore paused local services."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import quote


def call(*args, **kwargs):
    return subprocess.run(list(args), check=True, **kwargs)


def inspect(cid):
    return json.loads(subprocess.check_output(['docker', 'inspect', cid]))[0]


def env(info):
    return dict(v.split('=', 1) for v in info['Config']['Env'] if '=' in v)


def main():
    dest = Path(sys.argv[1]).resolve()
    if dest.exists():
        raise RuntimeError('Snapshot destination already exists; choose a new name')
    ids = subprocess.check_output(['docker', 'compose', 'ps', '-q']).decode().split()
    containers = {inspect(cid)['Config']['Labels']['com.docker.compose.service']: inspect(cid) for cid in ids}
    active = subprocess.check_output(['docker', 'ps', '-q', '--filter', 'label=managedBy=backend']).strip()
    if active:
        raise RuntimeError('Active backend job containers exist; wait before freezing snapshot')
    dest.mkdir(parents=True)
    services = [s for s in ('sirius', 'business-worker', 'evidence-worker', 'local-receiver', 'qa-service')
                if s in containers and containers[s]['State']['Running']]
    database = containers['db']['Id']
    minio = containers['minio']
    backend = env(containers['sirius'])
    network = next(iter(minio['NetworkSettings']['Networks']))
    secrets = {k: backend[k] for k in ('MM_INTERNAL_TOKEN', 'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY',
               'MINIO_PRESIGN_ACCESS_KEY', 'MINIO_PRESIGN_SECRET_KEY', 'MINIO_BUCKET',
               'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD')}
    (dest / 'secrets.json').write_text(json.dumps(secrets))
    try:
        call('docker', 'compose', 'stop', '-t', '45', *services)
        with (dest / 'postgres.dump').open('wb') as out:
            call('docker', 'exec', database, 'pg_dump', '-U', 'sirius', '-d', 'siriusdb', '-Fc', stdout=out)
        source = 'http://' + quote(secrets['MINIO_ACCESS_KEY'], safe='') + ':' + quote(secrets['MINIO_SECRET_KEY'], safe='') + '@minio:9000'
        call('docker', 'run', '--rm', '--network', network, '-v', str(dest / 'objects') + ':/export',
             '-e', 'MC_HOST_source=' + source, 'minio/mc:latest', 'mirror', '--quiet', '--preserve',
             'source/' + secrets['MINIO_BUCKET'], '/export/' + secrets['MINIO_BUCKET'], stdout=subprocess.DEVNULL)
        call('docker', 'cp', containers['sirius']['Id'] + ':/app/sirius-backend.jar', str(dest / 'sirius-backend.jar'))
        call('docker', 'cp', containers['local-receiver']['Id'] + ':/receiver-data', str(dest / 'receiver-data'))
        # Capture the actual audit mount instead of assuming a historical path.
        mounts = containers['qa-service']['Mounts']
        trace = next(m['Source'] for m in mounts if m['Destination'] == '/var/log/qa-evidence')
        call('docker', 'run', '--rm', '-v', trace + ':/source:ro', '-v', str(dest) + ':/export',
             '--entrypoint', 'tar', 'postgres:16', '-cf', '/export/qa-traces.tar', '-C', '/source', '.')
    finally:
        if services:
            call('docker', 'compose', 'start', *services)
    manifest = []
    scan_root = Path('\\\\?\\' + str(dest)) if os.name == 'nt' else dest
    for file in sorted(scan_root.rglob('*')):
        if file.is_file() and file.name != 'secrets.json':
            digest = hashlib.sha256()
            with file.open('rb') as stream:
                for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
                    digest.update(block)
            manifest.append({'path': file.relative_to(scan_root).as_posix(), 'bytes': file.stat().st_size,
                             'sha256': digest.hexdigest()})
    (dest / 'manifest.json').write_text(json.dumps(manifest, indent=2))
    print('Snapshot complete; files=' + str(len(manifest)), flush=True)


if __name__ == '__main__':
    main()
