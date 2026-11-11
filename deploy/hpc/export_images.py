"""Export pinned existing local images without rebuilding or changing tags."""
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

IMAGES = {
    'postgres': 'postgres:16', 'minio': 'minio/minio:latest',
    'mc': 'minio/mc:latest', 'qa': 'sirius-mm-qa-service:latest',
    'search': 'sirius-mm-search-service:latest',
    'index': 'sirius-mm-index-builder:latest', 'vllm': 'vllm/vllm-openai:v0.17.0',
}


def main():
    root = Path(sys.argv[1]).resolve()
    root.mkdir(parents=True, exist_ok=True)
    for name, ref in IMAGES.items():
        info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', ref]))[0]
        image_id = info['Id']
        dest = root / (name + '.tar.gz')
        meta = root / (name + '.json')
        if dest.exists() and meta.exists() and json.loads(meta.read_text())['image_id'] == image_id:
            print('Already exported ' + name, flush=True)
            continue
        partial = dest.with_suffix(dest.suffix + '.partial')
        print('Exporting ' + name + ' ' + image_id, flush=True)
        process = subprocess.Popen(['docker', 'save', image_id], stdout=subprocess.PIPE)
        with gzip.open(partial, 'wb', compresslevel=1) as output:
            shutil.copyfileobj(process.stdout, output, 4 * 1024 * 1024)
        if process.wait() != 0:
            raise RuntimeError('Image export failed: ' + name)
        partial.replace(dest)
        digest = hashlib.sha256()
        with dest.open('rb') as stream:
            for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
                digest.update(block)
        meta.write_text(json.dumps({'reference': ref, 'image_id': image_id,
            'sha256': digest.hexdigest(), 'bytes': dest.stat().st_size}, indent=2))
        print('Verified ' + name + ' bytes=' + str(dest.stat().st_size), flush=True)


if __name__ == '__main__':
    main()
