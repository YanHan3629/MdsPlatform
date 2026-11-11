#!/usr/bin/env python3
"""Execute an allowlisted SIF payload as a step of an existing Slurm allocation.

No daemon, root privileges, container registry access, or new allocations.
Compatible with the platform's Python 3.6. Resource ceilings are mandatory.
"""
import argparse
import hashlib
import re
import json
import os
from pathlib import Path
import sys


def command(args, environ):
    job = environ.get('SLURM_JOB_ID', '')
    if not job.isdigit():
        raise ValueError('An existing Slurm allocation is required')
    if not args.payload:
        raise ValueError('A payload command is required')
    if args.cpus < 1 or args.memory_mb < 64 or args.pids < 1:
        raise ValueError('Positive CPU, memory and process ceilings are required')
    manifest = Path(args.manifest).resolve(strict=True)
    images = json.loads(manifest.read_text())
    entry = images[args.image]
    image = Path(entry['path']).resolve(strict=True)
    if not image.is_file() or image.suffix != '.sif':
        raise ValueError('An existing SIF file is required')
    required = getattr(args, 'required_image_sha256', None)
    pinned = getattr(args, 'registered_image_path', None)
    if (required is None) != (pinned is None):
        raise ValueError('A digest and registered image path must be pinned together')
    if required is not None:
        if not re.fullmatch('[a-f0-9]{64}', required) or str(image) != pinned:
            raise ValueError('Registered SIF image binding changed')
        digest = hashlib.sha256()
        with image.open('rb') as stream:
            for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b''):
                digest.update(chunk)
        if digest.hexdigest() != required:
            raise ValueError('Registered SIF image digest changed before Slurm step')
    source_binding = None
    source_manifest = getattr(args, 'runtime_binding_manifest', None)
    source_sha = getattr(args, 'runtime_binding_sha256', None)
    if (source_manifest is None) != (source_sha is None):
        raise ValueError('Runtime binding path and digest must be supplied together')
    if source_manifest is not None:
        from runtime_source_binding import job_source_binding
        source_binding = job_source_binding(entry, source_sha)
        if source_binding['manifestPath'] != str(Path(source_manifest).resolve(strict=True)) or args.image != source_binding['imageReference']:
            raise ValueError('Runtime source binding changed before Slurm step')
    elif entry.get('runtimeBinding') is not None:
        raise ValueError('Registered source-bound image requires its frozen manifest')
    # Exact local images only: never download an image during a test allocation.
    bind_args = []
    for binding in args.bind:
        source, destination = binding.split(':', 1)
        source = str(Path(source).resolve(strict=True))
        if ',' in source or ',' in destination or not destination.startswith('/'):
            raise ValueError('Invalid bind mount')
        bind_args.extend(['--bind', source + ':' + destination])
    inner = [sys.executable, str(Path(__file__).resolve()), '--limit-worker',
             str(args.pids), 'singularity', 'exec', '--cleanenv', '--no-eval',
             # PID isolation also closes /proc/<host-pid>/{environ,root}.
             # Refuse inherited administrator bind paths and automatic host cwd.
             '--containall', '--no-home', '--no-privs',
             '--no-mount', 'cwd,home,hostfs,bind-paths', '--pwd', '/tmp']
    if source_binding is not None:
        # Recheck after srun obtains its step resources, immediately before exec.
        position = inner.index('singularity')
        inner[position:position] = ['--verify-runtime-binding', source_binding['manifestPath'],
                                    source_binding['manifestSha256'], source_binding['imageReference']]
        for path, target in source_binding['binds']:
            inner.extend(['--bind', path + ':' + target])
    inner.extend(bind_args)
    if args.gpu:
        if not environ.get('CUDA_VISIBLE_DEVICES'):
            raise ValueError('GPU execution requires Slurm-assigned CUDA_VISIBLE_DEVICES')
        inner.extend(['--nv', '--env', 'CUDA_VISIBLE_DEVICES=' + environ['CUDA_VISIBLE_DEVICES']])
    if args.env_file:
        inner.extend(['--env-file', str(Path(args.env_file).resolve(strict=True))])
    inner.extend([str(image)] + args.payload)
    return ['srun', '--jobid=' + job, '--exact', '--exclusive', '--nodes=1',
            '--ntasks=1', '--cpus-per-task=' + str(args.cpus),
            '--mem=' + str(args.memory_mb) + 'M', '--kill-on-bad-exit=1'] + inner


def main():
    if len(sys.argv) > 1 and sys.argv[1] == '--limit-worker':
        import resource
        ceiling = int(sys.argv[2])
        if ceiling < 1:
            raise ValueError('Invalid process limit')
        # RLIMIT_NPROC counts this real UID's processes, including other jobs.
        # It is stricter than a per-container ceiling, never an unlimited fallback.
        old_soft, old_hard = resource.getrlimit(resource.RLIMIT_NPROC)
        hard = ceiling if old_hard == resource.RLIM_INFINITY else min(ceiling, old_hard)
        resource.setrlimit(resource.RLIMIT_NPROC, (hard, hard))
        payload = sys.argv[3:]
        if payload[:1] == ['--verify-runtime-binding']:
            if len(payload) < 5:
                raise ValueError('Incomplete runtime binding worker arguments')
            from runtime_source_binding import RuntimeBindings
            binding = RuntimeBindings(payload[1], payload[2])
            if binding.index_reference != payload[3]:
                raise ValueError('Runtime source alias changed inside Slurm step')
            binding.verify_runtime()
            binding.verify_source('index-builder')
            binding.verify_image('index')
            payload = payload[4:]
        os.execvp(payload[0], payload)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--cpus', type=int, required=True)
    parser.add_argument('--memory-mb', type=int, required=True)
    parser.add_argument('--pids', type=int, required=True)
    parser.add_argument('--gpu', action='store_true')
    parser.add_argument('--bind', action='append', default=[])
    parser.add_argument('--env-file')
    parser.add_argument('--required-image-sha256')
    parser.add_argument('--registered-image-path')
    parser.add_argument('--runtime-binding-manifest')
    parser.add_argument('--runtime-binding-sha256')
    parser.add_argument('payload', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.payload[:1] == ['--']:
        args.payload = args.payload[1:]
    cmd = command(args, os.environ)
    os.execvp(cmd[0], cmd)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError) as exc:
        print('HPC execution rejected: ' + str(exc), file=sys.stderr)
        sys.exit(2)
