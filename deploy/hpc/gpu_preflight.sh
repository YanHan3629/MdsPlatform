#!/usr/bin/env bash
set -euo pipefail
test -n "${SLURM_JOB_ID:-}"
module load Singularity/4.2.1
printf 'job=%s node=%s devices=%s\n' "$SLURM_JOB_ID" "$(hostname)" "${CUDA_VISIBLE_DEVICES:-unset}"
test -n "${CUDA_VISIBLE_DEVICES:-}"
nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv
singularity exec --cleanenv --contain --no-home --nv \
  --env "CUDA_VISIBLE_DEVICES=$CUDA_VISIBLE_DEVICES" \
  "$HOME/packages/mds-platform/redis.sif" nvidia-smi -L
python3 - <<'PY'
import ctypes
cuda = ctypes.CDLL('libcuda.so.1')
rc = cuda.cuInit(0)
assert rc == 0, ('cuInit', rc)
count = ctypes.c_int()
assert cuda.cuDeviceGetCount(ctypes.byref(count)) == 0
print('CUDA device count:', count.value)
assert count.value > 0
PY
echo GPU_PREFLIGHT_PASSED
