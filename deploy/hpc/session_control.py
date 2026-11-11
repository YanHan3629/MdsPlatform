#!/usr/bin/env python3
"""Lightweight login-node submission/status/control; workloads stay in Slurm."""
import argparse
import hashlib
import stat
import json
import os
from pathlib import Path
import re
import subprocess


def output(args):
    return subprocess.check_output(args, universal_newlines=True, timeout=30).strip()


def selected_workspace(value):
    if value is None:
        return Path.home()
    root = Path(value)
    if not root.is_absolute() or '..' in root.parts or root.is_symlink():
        raise RuntimeError('Workspace root must be an absolute canonical private directory')
    resolved = root.resolve(strict=True)
    metadata = resolved.stat()
    if resolved != root or not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_mode & 0o077:
        raise RuntimeError('Workspace root must be owned by this UID with mode 0700')
    return resolved


def workspace_job_name(root, explicit):
    return 'mds-' + hashlib.sha256(str(root).encode()).hexdigest()[:12] if explicit else 'mds-platform'


def jobs(expected_name=None):
    result = []
    for line in output(['squeue', '-h', '-u', str(os.getuid()), '-o', '%i|%j|%T|%N']).splitlines():
        job, name, state, node = line.split('|')
        if (name == expected_name if expected_name is not None else name.startswith('mds-')):
            result.append({'job': job, 'name': name, 'state': state, 'node': node})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['start', 'status', 'connect', 'stop'])
    parser.add_argument('--job', type=int)
    parser.add_argument('--minutes', type=int, default=30)
    parser.add_argument('--cpu-only', action='store_true')
    parser.add_argument('--workspace-root', help='Absolute private deployment directory; defaults to the current user home')
    args = parser.parse_args()
    home = selected_workspace(args.workspace_root)
    job_name = workspace_job_name(home, args.workspace_root is not None)
    app = home / 'apps/mds-platform'
    active = jobs(job_name if args.workspace_root is not None else None)
    if args.action == 'start':
        if not 1 <= args.minutes <= 120:
            parser.error('minutes must be 1 through 120')
        if active:
            raise RuntimeError('An MDS job is already queued/running; inspect or stop it first')
        wall = args.minutes + 15
        command = ['sbatch', '--parsable', '-A', 'p_p08162',
            '--qos=cpu-500_core-l40-8_card-a800-8_card', '-N1', '-n1',
            '-p', 'amd' if args.cpu_only else 'L40', '-c', '8' if args.cpu_only else '7',
            '--mem=24G' if args.cpu_only else '--mem=48G',
            '--time=%02d:%02d:00' % (wall // 60, wall % 60), '--deadline=now+12hours',
            '--signal=B:TERM@60', '-J', job_name, '-o', str(app / 'session-%j.log')]
        if not args.cpu_only:
            command.append('--gres=gpu:l40:1')
        command.extend([str(app / 'run_platform.sh'), '--snapshot', str(home / 'backup/mds-platform/snapshot-01'),
                        '--duration', str(args.minutes * 60)])
        if args.workspace_root is not None:
            command.extend(['--workspace-root', str(home)])
        if args.cpu_only:
            command.append('--cpu-only')
        job = output(command)
        print(json.dumps({'job': job, 'sessionMinutes': args.minutes, 'wallMinutes': wall, 'cpuOnly': args.cpu_only, 'workspaceRoot': str(home), 'jobName': job_name}))
        return
    if args.action == 'status' and args.job is None:
        print(json.dumps(active))
        return
    if args.job is None:
        parser.error('--job required')
    job = str(args.job)
    item = next((j for j in active if j['job'] == job), None)
    if item is None:
        if args.action == 'status':
            print(json.dumps({'job': job, 'active': False, 'accounting': output(['sacct', '-X', '-n', '-P', '-j', job, '-u', str(os.getuid()), '--name=' + job_name,
                '--format=JobID,JobName,State,Elapsed,ExitCode'])}))
            return
        raise RuntimeError('Job is not an active MDS job owned by this account')
    if args.action == 'stop':
        subprocess.check_call(['scancel', job])
        print(json.dumps({'job': job, 'cancelRequested': True}))
        return
    ready = home / 'data/mds-platform/runs' / job / 'ready.json'
    item['ready'] = ready.is_file() and item['state'] == 'RUNNING'
    if item['ready']:
        ready_value = json.loads(ready.read_text())
        if str(ready_value.get('job')) != job:
            raise RuntimeError('Readiness record does not match the selected allocation')
        item.update(ready_value)
    if args.action == 'connect':
        if not item['ready'] or not re.fullmatch(r'[a-z]+[0-9]+', item['node']):
            raise RuntimeError('Session is not ready on a single allocated node')
        # Read the host key through the authenticated login and our allocation,
        # rather than trusting an unauthenticated ssh-keyscan.
        key = output(['srun', '--jobid=' + job, '--overlap', '-N1', '-n1', '-c1', '--mem=128M',
                      'cat', '/etc/ssh/ssh_host_ed25519_key.pub'])
        if not re.fullmatch(r'ssh-ed25519 [A-Za-z0-9+/=]+(?: .*)?', key):
            raise RuntimeError('Unexpected compute host public key')
        item['hostKey'] = key.split()[0] + ' ' + key.split()[1]
    print(json.dumps(item))


if __name__ == '__main__':
    main()
