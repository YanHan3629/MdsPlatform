#!/usr/bin/env bash
set -euo pipefail
test -n "${SLURM_JOB_ID:-}"
exec python3 "$HOME/apps/mds-platform/prepare_bundle.py" "$@"
