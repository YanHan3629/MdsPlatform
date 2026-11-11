#!/usr/bin/env bash
set -euo pipefail
test -n "${SLURM_JOB_ID:-}"
module load Singularity/4.2.1
umask 077
cd "$HOME/packages/mds-platform"
export SINGULARITY_TMPDIR="${SLURM_TMPDIR:-/tmp}/mds-convert-$SLURM_JOB_ID"
mkdir -p "$SINGULARITY_TMPDIR"
for name in "$@"; do
  case "$name" in postgres|minio|mc|qa|search|index|vllm) ;; *) exit 2;; esac
  test -s "$name.tar.gz"
  python3 "$HOME/apps/mds-platform/verify_archive.py" "$name.tar.gz"
  if [[ ! -f "$name.sif" ]]; then
    gzip -dc "$name.tar.gz" > "$SINGULARITY_TMPDIR/$name.tar"
    singularity build "$name.partial.sif" "docker-archive://$SINGULARITY_TMPDIR/$name.tar"
    mv "$name.partial.sif" "$name.sif"
    rm -- "$SINGULARITY_TMPDIR/$name.tar"
  fi
  sha256sum "$name.sif"
done
if [[ -f java17.tar.gz && ! -d "$HOME/apps/mds-platform/java17" ]]; then
  python3 "$HOME/apps/mds-platform/verify_archive.py" java17.tar.gz
  mkdir "$HOME/apps/mds-platform/java17"
  tar -xzf java17.tar.gz -C "$HOME/apps/mds-platform/java17"
  "$HOME"/apps/mds-platform/java17/*/bin/java -version
fi
