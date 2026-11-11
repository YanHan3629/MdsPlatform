#!/usr/bin/env python3
"""Verify and unpack our migration archives inside an allocated CPU job."""
import json
import os
from pathlib import Path
import subprocess
import argparse
from verify_archive import digest, verify


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--application-only', action='store_true')
    args = parser.parse_args()
    if not os.environ.get('SLURM_JOB_ID', '').isdigit():
        raise RuntimeError('Slurm allocation required')
    os.umask(0o077)
    packages = Path.home() / 'packages/mds-platform'
    metadata = json.loads((packages / 'bundle-checksums.json').read_text(encoding='utf-8-sig'))
    app = Path.home() / 'apps/mds-platform'
    targets = [('application.tar.gz', app / 'source'),
               ('snapshot-01.tar.gz', Path.home() / 'backup/mds-platform/snapshot-01')]
    if args.application_only:
        targets = targets[:1]
    for name, target in targets:
        verify(packages, name)
        if target.exists():
            marker = target / '.verified'
            if not marker.exists() or marker.read_text() != metadata[name]:
                raise RuntimeError('Incomplete existing extraction: ' + str(target))
            continue
        target.mkdir(parents=True)
        subprocess.run(['tar', '--no-same-owner', '--no-same-permissions', '-xzf', str(packages / name), '-C', str(target)], check=True)
        manifest = target / 'manifest.json'
        if manifest.exists():
            for entry in json.loads(manifest.read_text()):
                path = (target / entry['path']).resolve()
                if target.resolve() not in path.parents or path.stat().st_size != entry['bytes'] or digest(path) != entry['sha256']:
                    raise RuntimeError('Snapshot manifest mismatch')
        (target / '.verified').write_text(metadata[name])
    print('Verified bundles: ' + ', '.join(name for name, _ in targets))


if __name__ == '__main__':
    main()
