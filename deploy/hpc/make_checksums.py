"""Create the transfer manifest from completed, locally verified artifacts."""
import json
from pathlib import Path
import sys
from verify_archive import digest


def main():
    root = Path(sys.argv[1]).resolve()
    checksums = {}
    identities = {}
    for metadata in sorted((root / 'images').glob('*.json')):
        entry = json.loads(metadata.read_text())
        name = metadata.stem + '.tar.gz'
        path = root / 'images' / name
        if path.stat().st_size != entry['bytes'] or digest(path) != entry['sha256']:
            raise RuntimeError('Local exported image mismatch: ' + name)
        checksums[name] = entry['sha256']
        identities[metadata.stem] = {'reference': entry['reference'], 'image_id': entry['image_id']}
        print('Verified ' + name, flush=True)
    snapshot = root / 'snapshot-01.tar.gz'
    receipt = json.loads(snapshot.with_name(snapshot.name + '.verified.json').read_text())
    if receipt['sha256'] != digest(snapshot) or receipt['bytes'] != snapshot.stat().st_size:
        raise RuntimeError('Snapshot packaging receipt mismatch')
    checksums[snapshot.name] = receipt['sha256']
    for name in ('application.tar.gz', 'java17.tar.gz'):
        checksums[name] = digest(root / name)
    (root / 'bundle-checksums.json').write_text(json.dumps(checksums, indent=2))
    (root / 'image-identities.json').write_text(json.dumps(identities, indent=2))
    print('Wrote bundle-checksums.json', flush=True)


if __name__ == '__main__':
    main()
