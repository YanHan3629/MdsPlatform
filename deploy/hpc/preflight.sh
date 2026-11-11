#!/usr/bin/env bash
# Run only as a short Slurm job. Does not start persistent services.
set -euo pipefail
test -n "${SLURM_JOB_ID:-}" || { echo 'Submit this script with sbatch/srun.' >&2; exit 2; }
module load Singularity/4.2.1
root="${MDS_HPC_ROOT:-$HOME/packages/mds-platform}"
image="$root/redis.sif"
printf 'job=%s node=%s\n' "$SLURM_JOB_ID" "$(hostname)"
singularity --version
if [[ ! -f "$image" ]]; then
  test -f "$root/redis.tar"
  export SINGULARITY_TMPDIR="${SLURM_TMPDIR:-/tmp}/mds-preflight-$SLURM_JOB_ID"
  mkdir -p "$SINGULARITY_TMPDIR"
  # Keep failed conversion evidence. Never alter other users' caches or images.
  singularity build "$image" "docker-archive://$root/redis.tar"
fi
singularity exec --cleanenv --no-home --no-eval "$image" redis-server --version
printf '\nTesting resource controls required by the current index runner:\n'
set +e
singularity exec --cleanenv --no-home --no-eval --cpus 1 --memory 256m --memory-swap 256m --pids-limit 64 "$image" sh -c 'echo resource-controls-ok'
limits_rc=$?
set -e
printf 'resource_controls_exit_code=%s\n' "$limits_rc"
printf '\nSlurm cgroup placement:\n'
cat /proc/self/cgroup
exit "$limits_rc"
