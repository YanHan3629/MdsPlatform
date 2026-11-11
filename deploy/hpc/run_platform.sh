#!/usr/bin/env bash
set -euo pipefail
test -n "${SLURM_JOB_ID:-}"
module load Singularity/4.2.1
umask 077
# Slurm copies this script into its spool directory. Select the deployment from
# the explicit argument, rather than from the copied script location.
launcher_workspace=''
launcher_workspace_seen=0
launcher_arguments=("$@")
for ((launcher_index=0; launcher_index<${#launcher_arguments[@]}; launcher_index++)); do
    launcher_argument=${launcher_arguments[launcher_index]}
    if [[ "$launcher_argument" == --workspace-root ]]; then
        (( launcher_workspace_seen == 0 )) || { echo 'Duplicate workspace root' >&2; exit 2; }
        (( launcher_index + 1 < ${#launcher_arguments[@]} )) || { echo 'Missing workspace root' >&2; exit 2; }
        launcher_index=$((launcher_index + 1))
        launcher_workspace=${launcher_arguments[launcher_index]}
        launcher_workspace_seen=1
        [[ -n "$launcher_workspace" ]] || { echo 'Missing workspace root' >&2; exit 2; }
    elif [[ "$launcher_argument" == --workspace-root=* ]]; then
        (( launcher_workspace_seen == 0 )) || { echo 'Duplicate workspace root' >&2; exit 2; }
        launcher_workspace=${launcher_argument#--workspace-root=}
        launcher_workspace_seen=1
        [[ -n "$launcher_workspace" ]] || { echo 'Missing workspace root' >&2; exit 2; }
    fi
done
launcher_app=$(python3 - "$launcher_workspace" <<'PY'
import os,stat,sys
from pathlib import Path
root=Path(sys.argv[1]) if sys.argv[1] else Path.home()
if sys.argv[1]:
    if not root.is_absolute() or '..' in root.parts or root.is_symlink():
        raise SystemExit('Workspace root must be an absolute canonical private directory')
    resolved=root.resolve(strict=True);metadata=resolved.stat()
    if resolved!=root or not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid!=os.getuid() or metadata.st_mode & 0o077:
        raise SystemExit('Workspace root must be owned by this UID with mode 0700')
print(str(root/'apps/mds-platform'))
PY
)
cd -- "$launcher_app"
chmod u+x runtime_cli.py
exec python3 serve.py "$@"
