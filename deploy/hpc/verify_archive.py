#!/usr/bin/env python3
"""Verify staged bytes before unpacking or converting them on a compute node."""
import hashlib
import json
import os
from pathlib import Path
import sys


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def verify(packages, name):
    if Path(name).name != name:
        raise ValueError('Archive must be a basename')
    metadata = json.loads((packages / 'bundle-checksums.json').read_text(encoding='utf-8-sig'))
    expected = metadata[name]
    if digest(packages / name) != expected:
        raise RuntimeError('SHA-256 mismatch: ' + name)
    return expected


if __name__ == '__main__':
    if not os.environ.get('SLURM_JOB_ID', '').isdigit():
        raise RuntimeError('Archive verification requires a Slurm allocation')
    packages = Path.home() / 'packages/mds-platform'
    for name in sys.argv[1:]:
        print(name + ' ' + verify(packages, name), flush=True)
