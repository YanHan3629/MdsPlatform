"""Reviewed HPC code/image bindings. Python 3.6, no service or credential access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat

SCHEMA = 'MDS_RUNTIME_BINDINGS_V2'
LEGACY_SCHEMA = 'MDS_RUNTIME_BINDINGS_V1'
GROUPS = ('index-builder', 'search-service', 'qa-service', 'evidence-worker', 'business-worker')
IMAGES = ('index', 'search', 'qa', 'vllm', 'postgres', 'redis', 'minio', 'mc')
LEGACY_RUNTIME_FILES = ('serve.py', 'runtime_cli.py', 'slurm_exec.py', 'runtime_source_binding.py',
                 'identity_maintenance_binding.py', 'accounts.py', 'accounts_control.py',
                 'accounts_compute.py', 'command.sh')
OPERATOR_FILES = ('run_platform.sh', 'session_control.py', 'session.ps1')
RUNTIME_FILES = LEGACY_RUNTIME_FILES + OPERATOR_FILES
RESOURCE_ROOT = 'backend/src/main/resources/script/minio'


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':'), allow_nan=False).encode('utf-8')).hexdigest()


def checked(path, directory=False, private=False):
    path = Path(path)
    if not path.is_absolute() or path.is_symlink():
        raise ValueError('RUNTIME_BINDING_PATH_NOT_ABSOLUTE_OR_IS_SYMLINK')
    resolved = path.resolve(strict=True)
    info = resolved.stat()
    if info.st_uid != os.getuid() or info.st_mode & 0o022:
        raise ValueError('RUNTIME_BINDING_OWNER_OR_WRITE_PERMISSIONS_INVALID')
    if private and info.st_mode & 0o077:
        raise ValueError('RUNTIME_BINDING_MANIFEST_MUST_BE_PRIVATE')
    if directory != stat.S_ISDIR(info.st_mode) or not directory and not stat.S_ISREG(info.st_mode):
        raise ValueError('RUNTIME_BINDING_PATH_TYPE_INVALID')
    return resolved


def safe_relative(name):
    if not isinstance(name, str) or not name or any(c in name for c in ('\\', ':', '\0')):
        raise ValueError('RUNTIME_BINDING_RELATIVE_PATH_INVALID')
    path = Path(name)
    if path.is_absolute() or any(p in ('', '.', '..') for p in name.split('/')):
        raise ValueError('RUNTIME_BINDING_RELATIVE_PATH_INVALID')
    return path


def sha_value(value):
    if not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value):
        raise ValueError('RUNTIME_BINDING_SHA256_INVALID')
    return value


class RuntimeBindings:
    def __init__(self, manifest, expected_sha256=None):
        self.path = checked(manifest, private=True)
        self.sha256 = digest(self.path)
        if expected_sha256 is not None and self.sha256 != sha_value(expected_sha256):
            raise ValueError('RUNTIME_BINDING_MANIFEST_CHANGED')
        self.value = json.loads(self.path.read_text())
        required = {'schema', 'appRoot', 'sourceRoot', 'packagesRoot', 'modelRoot', 'jarSha256',
                    'runtimeFiles', 'sourceFilesSha256', 'sourceResourceSha256', 'sifSha256', 'modelFiles'}
        if set(self.value) != required or self.value['schema'] not in {SCHEMA, LEGACY_SCHEMA}:
            raise ValueError('RUNTIME_BINDING_SCHEMA_INVALID')
        self.app = checked(self.value['appRoot'], directory=True)
        self.source = checked(self.value['sourceRoot'], directory=True)
        self.packages = checked(self.value['packagesRoot'], directory=True)
        self.model = checked(self.value['modelRoot'], directory=True)
        if set(self.value['sourceFilesSha256']) != set(GROUPS) or set(self.value['sifSha256']) != set(IMAGES):
            raise ValueError('RUNTIME_BINDING_COMPONENT_SET_INVALID')
        required_runtime = RUNTIME_FILES if self.value['schema'] == SCHEMA else LEGACY_RUNTIME_FILES
        if set(self.value['runtimeFiles']) != set(required_runtime):
            raise ValueError('RUNTIME_BINDING_LAUNCHER_FILE_SET_INVALID')
        sha_value(self.value['jarSha256'])
        for h in self.value['sifSha256'].values():
            sha_value(h)
        self.index_source_sha256 = canonical_hash({'sifSha256': self.value['sifSha256']['index'],
                                                   'sourceFilesSha256': self.value['sourceFilesSha256']['index-builder']})
        self.index_reference = 'mds-mm-index-runtime:' + self.index_source_sha256
        self.image_stats = {}

    def verify_source(self, group):
        if group not in GROUPS:
            raise ValueError('RUNTIME_BINDING_SOURCE_GROUP_INVALID')
        base = checked(self.source / group, directory=True)
        expected = self.value['sourceFilesSha256'][group]
        paths = list(base.rglob('*'))
        if any(p.name == '__pycache__' or p.suffix == '.pyc' for p in paths):
            raise ValueError('RUNTIME_BINDING_SOURCE_BYTECODE_CACHE_FORBIDDEN')
        actual = {str(p.relative_to(base)).replace('\\', '/'): p for p in paths if p.is_file()}
        if not expected or set(actual) != set(expected):
            raise ValueError('RUNTIME_BINDING_PYTHON_FILE_SET_CHANGED:' + group)
        for name, h in expected.items():
            safe_relative(name)
            path = checked(actual[name])
            if base not in path.parents or digest(path) != sha_value(h):
                raise ValueError('RUNTIME_BINDING_PYTHON_BYTES_CHANGED:' + group)
        return base

    def resource_path(self):
        base = checked(self.source / RESOURCE_ROOT, directory=True)
        expected = self.value['sourceResourceSha256']
        actual = {str(p.relative_to(base)).replace('\\', '/'): p for p in base.rglob('*') if p.is_file()}
        if not expected or set(actual) != set(expected):
            raise ValueError('RUNTIME_BINDING_RESOURCE_FILE_SET_CHANGED')
        for name, h in expected.items():
            safe_relative(name)
            path = checked(actual[name])
            if base not in path.parents or digest(path) != sha_value(h):
                raise ValueError('RUNTIME_BINDING_RESOURCE_BYTES_CHANGED')
        return base

    def verify_image(self, name):
        if name not in IMAGES:
            raise ValueError('RUNTIME_BINDING_IMAGE_NAME_INVALID')
        path = checked(self.packages / (name + '.sif'))
        info = path.stat()
        signature = (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        if self.image_stats.get(name) != signature:
            if digest(path) != self.value['sifSha256'][name]:
                raise ValueError('RUNTIME_BINDING_SIF_BYTES_CHANGED:' + name)
            self.image_stats[name] = signature
        return path

    def verify_runtime(self):
        if digest(checked(self.app / 'sirius-backend.jar')) != self.value['jarSha256']:
            raise ValueError('RUNTIME_BINDING_JAR_BYTES_CHANGED')
        for name, h in self.value['runtimeFiles'].items():
            if digest(checked(self.app / safe_relative(name))) != sha_value(h):
                raise ValueError('RUNTIME_BINDING_LAUNCHER_BYTES_CHANGED:' + name)

    def verify_all(self, require_model=True):
        self.verify_runtime()
        for group in GROUPS:
            self.verify_source(group)
        self.resource_path()
        for image in IMAGES:
            self.verify_image(image)
        if require_model:
            expected = self.value['modelFiles']
            actual = {p.name: p for p in self.model.iterdir() if p.is_file() and
                      p.name.endswith(('.safetensors', '.json', '.jinja', '.txt'))}
            if not expected or set(actual) != set(expected):
                raise ValueError('RUNTIME_BINDING_MODEL_FILE_SET_CHANGED')
            for name, item in expected.items():
                safe_relative(name)
                path = checked(actual[name])
                if path.stat().st_size != item['bytes'] or digest(path) != sha_value(item['sha256']):
                    raise ValueError('RUNTIME_BINDING_MODEL_BYTES_CHANGED')
        return self.proof()

    def binds(self, service):
        if service in ('search', 'qa', 'index'):
            group = {'search': 'search-service', 'qa': 'qa-service', 'index': 'index-builder'}[service]
            return [(self.verify_source(group), '/candidate:ro')]
        if service in ('business', 'evidence'):
            group = service + '-worker'
            base = self.verify_source(group)
            index = self.verify_source('index-builder')
            return [(base, '/' + group + ':ro'), (index, '/candidate:ro'),
                    (index / 'professional', '/app/professional:ro')]
        raise ValueError('RUNTIME_BINDING_SERVICE_INVALID')

    def image_entry(self):
        return {'path': str(self.packages / 'index.sif'), 'sha256': self.value['sifSha256']['index'],
                'runtimeBinding': {'path': str(self.path), 'sha256': self.sha256, 'group': 'index-builder'}}

    def proof(self):
        return {'manifestSha256': self.sha256, 'sourceRoot': str(self.source),
                'jarSha256': self.value['jarSha256'], 'sifSha256': self.value['sifSha256'],
                'indexSourceSha256': self.index_source_sha256, 'indexImageReference': self.index_reference,
                'bindingSchema': self.value['schema'], 'operatorControlsCovered': self.value['schema'] == SCHEMA,
                'operatorControlFilesSha256': {name: self.value['runtimeFiles'][name] for name in OPERATOR_FILES
                                               if name in self.value['runtimeFiles']}}


def job_source_binding(entry, expected_sha256=None):
    """Only the registered index source is mounted; request values cannot choose paths."""
    item = entry.get('runtimeBinding')
    if item is None:
        if expected_sha256 is not None:
            raise ValueError('RUNTIME_BINDING_REGISTRATION_REMOVED')
        return None
    if not isinstance(item, dict) or set(item) != {'path', 'sha256', 'group'} or item['group'] != 'index-builder':
        raise ValueError('RUNTIME_BINDING_JOB_REGISTRATION_INVALID')
    if expected_sha256 is not None and item['sha256'] != expected_sha256:
        raise ValueError('RUNTIME_BINDING_JOB_REGISTRATION_CHANGED')
    binding = RuntimeBindings(item['path'], item['sha256'])
    binding.verify_runtime()
    if Path(entry['path']).resolve(strict=True) != binding.packages / 'index.sif' or entry.get('sha256') != binding.value['sifSha256']['index']:
        raise ValueError('RUNTIME_BINDING_JOB_IMAGE_MISMATCH')
    binds = binding.binds('index')
    return {'manifestPath': str(binding.path), 'manifestSha256': binding.sha256,
            'sourceRoot': str(binding.source), 'sourceSha256': binding.index_source_sha256,
            'imageReference': binding.index_reference,
            'binds': [[str(p), target] for p, target in binds]}


def prepare(app, source, packages, model, output):
    if not os.environ.get('SLURM_JOB_ID', '').isdigit():
        raise ValueError('FINITE_SLURM_ALLOCATION_REQUIRED')
    app, source, packages, model = [checked(p, directory=True) for p in (app, source, packages, model)]
    output = Path(output)
    if output.exists() or output.is_symlink() or output.parent.resolve(strict=True) != app:
        raise ValueError('RUNTIME_BINDING_OUTPUT_MUST_BE_NEW_IN_APP_ROOT')
    value = {'schema': SCHEMA, 'appRoot': str(app), 'sourceRoot': str(source),
             'packagesRoot': str(packages), 'modelRoot': str(model),
             'jarSha256': digest(checked(app / 'sirius-backend.jar')),
             'runtimeFiles': {n: digest(checked(app / n)) for n in RUNTIME_FILES},
             'sourceFilesSha256': {g: {str(p.relative_to(source / g)).replace('\\', '/'): digest(checked(p))
                 for p in sorted((source / g).rglob('*')) if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc'} for g in GROUPS},
             'sourceResourceSha256': {str(p.relative_to(source / RESOURCE_ROOT)).replace('\\', '/'): digest(checked(p))
                 for p in sorted((source / RESOURCE_ROOT).rglob('*')) if p.is_file()},
             'sifSha256': {n: digest(checked(packages / (n + '.sif'))) for n in IMAGES},
             'modelFiles': {p.name: {'bytes': p.stat().st_size, 'sha256': digest(checked(p))}
                 for p in sorted(model.iterdir()) if p.is_file() and p.name.endswith(('.safetensors', '.json', '.jinja', '.txt'))}}
    os.umask(0o077)
    with output.open('x') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    output.chmod(0o400)
    return RuntimeBindings(output).verify_all()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Prepare or verify a private reviewed runtime binding in a finite Slurm job.')
    parser.add_argument('action', choices=('prepare', 'verify'))
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--app-root')
    parser.add_argument('--source-root')
    parser.add_argument('--packages-root')
    parser.add_argument('--model-root')
    args = parser.parse_args()
    if not os.environ.get('SLURM_JOB_ID', '').isdigit():
        parser.error('finite Slurm allocation required')
    if args.action == 'prepare':
        if any(v is None for v in (args.app_root, args.source_root, args.packages_root, args.model_root)):
            parser.error('prepare requires all four roots')
        proof = prepare(args.app_root, args.source_root, args.packages_root, args.model_root, args.manifest)
    else:
        proof = RuntimeBindings(args.manifest).verify_all()
    print(json.dumps(proof, sort_keys=True))
