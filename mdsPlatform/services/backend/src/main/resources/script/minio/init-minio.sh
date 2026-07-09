#!/usr/bin/env sh
set -eu

: "${MINIO_ENDPOINT:?MINIO_ENDPOINT is required}"
: "${MINIO_ROOT_USER:?MINIO_ROOT_USER is required}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD is required}"
: "${MINIO_BUCKET:?MINIO_BUCKET is required}"
: "${MINIO_PRESIGN_ACCESS_KEY:?MINIO_PRESIGN_ACCESS_KEY is required}"
: "${MINIO_PRESIGN_SECRET_KEY:?MINIO_PRESIGN_SECRET_KEY is required}"

POLICY_NAME="${MINIO_POLICY_NAME:-sirius-presign-readonly-policy}"
POLICY_FILE="/tmp/${POLICY_NAME}.json"
WAIT_TIMEOUT_SECONDS="${MINIO_INIT_WAIT_TIMEOUT_SECONDS:-30}"
WAIT_INTERVAL_SECONDS=2

echo "[minio-init] endpoint=${MINIO_ENDPOINT}, bucket=${MINIO_BUCKET}, user=${MINIO_PRESIGN_ACCESS_KEY}"

# MinIO may be "started" but not ready yet; retry alias init to avoid startup race.
waited=0
until mc alias set local "${MINIO_ENDPOINT}" "${MINIO_ROOT_USER}" "${MINIO_ROOT_PASSWORD}" >/dev/null 2>&1; do
  echo "[minio-init] waiting for MinIO alias..."
  sleep "${WAIT_INTERVAL_SECONDS}"
  waited=$((waited + WAIT_INTERVAL_SECONDS))
  if [ "${waited}" -ge "${WAIT_TIMEOUT_SECONDS}" ]; then
    echo "[minio-init] timeout waiting for MinIO alias" >&2
    exit 1
  fi
done

waited=0
until mc admin info local >/dev/null 2>&1; do
  echo "[minio-init] waiting for MinIO..."
  sleep "${WAIT_INTERVAL_SECONDS}"
  waited=$((waited + WAIT_INTERVAL_SECONDS))
  if [ "${waited}" -ge "${WAIT_TIMEOUT_SECONDS}" ]; then
    echo "[minio-init] timeout waiting for MinIO admin API" >&2
    exit 1
  fi
done

# Ensure target bucket exists (idempotent).
mc mb --ignore-existing "local/${MINIO_BUCKET}"

cat > "${POLICY_FILE}" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:GetBucketLocation"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}"]
    },
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": ["arn:aws:s3:::${MINIO_BUCKET}/*"]
    }
  ]
}
EOF

# Rebuild policy and user every run to avoid stale permissions/credentials.
# Remove user first so policy can be safely recreated across MinIO versions.
mc admin user remove local "${MINIO_PRESIGN_ACCESS_KEY}" >/dev/null 2>&1 || true
mc admin policy remove local "${POLICY_NAME}" >/dev/null 2>&1 || true
mc admin policy create local "${POLICY_NAME}" "${POLICY_FILE}"
mc admin user add local "${MINIO_PRESIGN_ACCESS_KEY}" "${MINIO_PRESIGN_SECRET_KEY}"
mc admin policy attach local "${POLICY_NAME}" --user "${MINIO_PRESIGN_ACCESS_KEY}"

echo "[minio-init] done"
