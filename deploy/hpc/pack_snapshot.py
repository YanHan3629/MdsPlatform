"""Validate the complete Windows long-path snapshot before making an archive."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tarfile


def receipt(target, count, source_bytes):
    h = hashlib.sha256()
    with target.open('rb') as stream:
        for block in iter(lambda: stream.read(4*1024*1024), b''):
            h.update(block)
    target.with_name(target.name + '.verified.json').write_text(json.dumps({
        'files': count, 'bytes': target.stat().st_size, 'sha256': h.hexdigest(),
        'source_bytes': source_bytes,
    }, indent=2))


def verify_existing(source, target):
    recorded = {entry['path']: entry for entry in json.loads((source / 'manifest.json').read_text())}
    seen = set()
    with tarfile.open(target, 'r|gz') as archive:
        for member in archive:
            if not member.isfile() or member.name in seen:
                raise RuntimeError('Unexpected archive member: ' + member.name)
            seen.add(member.name)
            if member.name in ('manifest.json', 'secrets.json'):
                if archive.extractfile(member).read() != (source / member.name).read_bytes():
                    raise RuntimeError('Snapshot metadata mismatch')
                continue
            expected = recorded[member.name]
            h = hashlib.sha256()
            stream = archive.extractfile(member)
            for block in iter(lambda: stream.read(4*1024*1024), b''):
                h.update(block)
            if member.size != expected['bytes'] or h.hexdigest() != expected['sha256']:
                raise RuntimeError('Archived content mismatch: ' + member.name)
            if len(seen) % 10000 == 0:
                print('Verified %d archived files' % len(seen), flush=True)
    if seen != set(recorded) | {'manifest.json', 'secrets.json'}:
        raise RuntimeError('Archive member set mismatch')
    receipt(target, len(seen), sum(entry['bytes'] for entry in recorded.values()))
    print('Archive content verified: %d files' % len(seen), flush=True)


def main():
    source = Path(sys.argv[1]).resolve()
    target = Path(sys.argv[2]).resolve()
    root = Path('\\\\?\\' + str(source)) if os.name == 'nt' else source
    if '--verify-existing' in sys.argv:
        verify_existing(root, target)
        return
    files = []
    def error(exc):
        raise exc
    for base, dirs, names in os.walk(root, onerror=error):
        for name in names:
            path = Path(base) / name
            files.append((path.relative_to(root).as_posix(), path))
    old = json.loads((root / 'manifest.json').read_text())
    recorded = {entry['path']: entry for entry in old}
    actual = {name for name, _ in files if name not in ('manifest.json', 'secrets.json')}
    if actual != set(recorded):
        if '--rebuild-manifest' not in sys.argv:
            raise RuntimeError('Snapshot manifest mismatch: missing=%d extra=%d' %
                               (len(actual-set(recorded)), len(set(recorded)-actual)))
        audit = target.parent / 'snapshot-manifest-before-longpath-fix.json'
        if not audit.exists():
            audit.write_text(json.dumps(old, indent=2))
        recorded = {}
        for name, path in files:
            if name in ('manifest.json', 'secrets.json'):
                continue
            h = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(4*1024*1024), b''):
                    h.update(block)
            recorded[name] = {'path': name, 'bytes': path.stat().st_size, 'sha256': h.hexdigest()}
            if len(recorded) % 10000 == 0:
                print('Hashed %d snapshot files' % len(recorded), flush=True)
        (root / 'manifest.json').write_text(json.dumps(list(recorded.values()), indent=2))
        print('Rebuilt complete long-path manifest: %d entries' % len(recorded), flush=True)
    for name, path in files:
        if name in recorded:
            h = hashlib.sha256()
            with path.open('rb') as stream:
                for block in iter(lambda: stream.read(4*1024*1024), b''):
                    h.update(block)
            if path.stat().st_size != recorded[name]['bytes'] or h.hexdigest() != recorded[name]['sha256']:
                raise RuntimeError('Snapshot content mismatch: ' + name)
    partial = target.with_name(target.name + '.partial')
    with tarfile.open(partial, 'w:gz', compresslevel=1) as archive:
        for name, path in sorted(files):
            archive.add(str(path), arcname=name, recursive=False)
    partial.replace(target)
    receipt(target, len(files), sum(entry['bytes'] for entry in recorded.values()))
    print('Verified and packed %d files, %d archive bytes' % (len(files), target.stat().st_size), flush=True)


if __name__ == '__main__':
    main()
