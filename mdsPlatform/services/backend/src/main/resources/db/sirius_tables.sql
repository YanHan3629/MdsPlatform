-- =========================================================
-- sirius_tables.sql (PostgreSQL) - commit_id aligned schema
-- Multi-input (each input repo always uses LAST PUBLISHED) + Multi-output Jobs
--
-- Run:
--   psql -h localhost -p 15432 -U sirius -d siriusdb -f backend/src/main/resources/db/sirius_tables.sql
-- =========================================================

BEGIN;

SET search_path TO public;

-- =========================
-- Extensions
-- =========================
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =========================
-- ENUM Types
-- =========================
DO $$ BEGIN
CREATE TYPE device_upload_status AS ENUM ('INITIATED','UPLOADING','COMPLETED','FAILED','CANCELED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
CREATE TYPE job_category AS ENUM ('IMPORT','EXPORT','TRANSFORM');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

DO $$ BEGIN
CREATE TYPE run_status AS ENUM ('DRAFT','QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELED');
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- =========================
-- Trigger: updated_at auto-maintenance
-- =========================
CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =========================
-- Org
-- =========================
CREATE TABLE IF NOT EXISTS public.org
(
    org_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_name    VARCHAR(128) NOT NULL,
    org_status  SMALLINT     NOT NULL DEFAULT 1, -- 1启用 0禁用
    license_type VARCHAR(16) NOT NULL DEFAULT 'FORMAL',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by UUID,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by UUID,
    CONSTRAINT uk_org_name UNIQUE (org_name),
    CONSTRAINT chk_org_license_type CHECK (license_type IN ('TRIAL', 'FORMAL'))
    );

CREATE INDEX IF NOT EXISTS idx_org_status ON public.org (org_status);

DROP TRIGGER IF EXISTS trg_org_updated_at ON public.org;
CREATE TRIGGER trg_org_updated_at
    BEFORE UPDATE ON public.org
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =========================
-- Users
-- =========================
CREATE TABLE IF NOT EXISTS public.fwd_user (
    user_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_name      VARCHAR(64) UNIQUE NOT NULL,
    password_hash  TEXT NOT NULL,
    org_id         UUID REFERENCES public.org(org_id) ON DELETE SET NULL,

    status_code    INTEGER,
    token_version  INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
    );

CREATE INDEX IF NOT EXISTS idx_fwd_user_org ON public.fwd_user (org_id);

DROP TRIGGER IF EXISTS trg_fwd_user_updated_at ON public.fwd_user;
CREATE TRIGGER trg_fwd_user_updated_at
    BEFORE UPDATE ON public.fwd_user
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =========================
-- Org shared storage usage
-- =========================
CREATE TABLE IF NOT EXISTS public.org_storage_usage (
    org_id UUID PRIMARY KEY REFERENCES public.org(org_id) ON DELETE CASCADE,
    used_storage_bytes BIGINT NOT NULL DEFAULT 0,
    used_gpu INTEGER NOT NULL DEFAULT 0,
    used_cpu NUMERIC(10,2) NOT NULL DEFAULT 0,
    used_memory_mb INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_org_storage_usage_bytes CHECK (used_storage_bytes >= 0),
    CONSTRAINT chk_org_storage_usage_gpu CHECK (used_gpu >= 0),
    CONSTRAINT chk_org_storage_usage_cpu CHECK (used_cpu >= 0),
    CONSTRAINT chk_org_storage_usage_memory CHECK (used_memory_mb >= 0)
    );

DROP TRIGGER IF EXISTS trg_org_storage_usage_updated_at ON public.org_storage_usage;
CREATE TRIGGER trg_org_storage_usage_updated_at
    BEFORE UPDATE ON public.org_storage_usage
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =========================
-- License quota policy
-- =========================
CREATE TABLE IF NOT EXISTS public.license_quota_policy (
    license_type VARCHAR(16) PRIMARY KEY,
    storage_quota_bytes BIGINT NOT NULL,
    max_gpu INTEGER NOT NULL,
    max_cpu NUMERIC(10,2) NOT NULL,
    max_memory_mb INTEGER NOT NULL,
    max_runtime_seconds INTEGER NOT NULL,
    status SMALLINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_license_quota_policy_type CHECK (license_type IN ('TRIAL', 'FORMAL')),
    CONSTRAINT chk_license_quota_policy_storage CHECK (storage_quota_bytes >= 0),
    CONSTRAINT chk_license_quota_policy_gpu CHECK (max_gpu >= 0),
    CONSTRAINT chk_license_quota_policy_cpu CHECK (max_cpu > 0),
    CONSTRAINT chk_license_quota_policy_memory CHECK (max_memory_mb > 0),
    CONSTRAINT chk_license_quota_policy_runtime CHECK (max_runtime_seconds > 0),
    CONSTRAINT chk_license_quota_policy_status CHECK (status IN (0, 1))
);

DROP TRIGGER IF EXISTS trg_license_quota_policy_updated_at ON public.license_quota_policy;
CREATE TRIGGER trg_license_quota_policy_updated_at
    BEFORE UPDATE ON public.license_quota_policy
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

INSERT INTO public.license_quota_policy (
    license_type, storage_quota_bytes, max_gpu, max_cpu, max_memory_mb, max_runtime_seconds, status
)
VALUES
    ('TRIAL', 5368709120, 1, 8.00, 16384, 7200, 1),
    ('FORMAL', 1099511627776, 8, 64.00, 131072, 86400, 1)
ON CONFLICT (license_type) DO UPDATE SET
    storage_quota_bytes = EXCLUDED.storage_quota_bytes,
    max_gpu = EXCLUDED.max_gpu,
    max_cpu = EXCLUDED.max_cpu,
    max_memory_mb = EXCLUDED.max_memory_mb,
    max_runtime_seconds = EXCLUDED.max_runtime_seconds,
    status = EXCLUDED.status;

-- =========================
-- RBAC: role / permission / mappings
-- =========================
CREATE TABLE IF NOT EXISTS public.role
(
    role_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID NOT NULL REFERENCES public.org(org_id) ON DELETE CASCADE,

    role_code   VARCHAR(64) NOT NULL, -- OWNER/ADMIN/MEMBER/VIEWER...
    role_name   VARCHAR(64) NOT NULL,
    is_builtin  BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID NOT NULL REFERENCES public.fwd_user(user_id) ON DELETE RESTRICT,

    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID NULL REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,

    CONSTRAINT uk_role_org_code UNIQUE (org_id, role_code)
    );

CREATE INDEX IF NOT EXISTS idx_role_org ON public.role (org_id);

DROP TRIGGER IF EXISTS trg_role_updated_at ON public.role;
CREATE TRIGGER trg_role_updated_at
    BEFORE UPDATE ON public.role
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.permission
(
    per_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    per_code     VARCHAR(128) NOT NULL, -- robot.read / api.report.export / feature.admin_panel
    per_name     VARCHAR(64)  NOT NULL,
    category     VARCHAR(16)  NOT NULL, -- operation / api / feature
    description  VARCHAR(255),
    is_builtin   BOOLEAN      NOT NULL DEFAULT TRUE,

    parent_id    UUID NULL REFERENCES public.permission(per_id) ON DELETE SET NULL,

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID NOT NULL REFERENCES public.fwd_user(user_id) ON DELETE RESTRICT,

    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID NULL REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,

    CONSTRAINT uk_permission_code UNIQUE (per_code)
    );

DROP TRIGGER IF EXISTS trg_permission_updated_at ON public.permission;
CREATE TRIGGER trg_permission_updated_at
    BEFORE UPDATE ON public.permission
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.user_role
(
    user_id    UUID NOT NULL REFERENCES public.fwd_user(user_id) ON DELETE CASCADE,
    role_id    UUID NOT NULL REFERENCES public.role(role_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id)
    );

CREATE INDEX IF NOT EXISTS idx_user_role_role ON public.user_role (role_id);

CREATE TABLE IF NOT EXISTS public.role_permission
(
    role_id    UUID NOT NULL REFERENCES public.role(role_id) ON DELETE CASCADE,
    per_id     UUID NOT NULL REFERENCES public.permission(per_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, per_id)
    );

CREATE INDEX IF NOT EXISTS idx_role_permission_perm ON public.role_permission (per_id);

CREATE TABLE IF NOT EXISTS public.user_permission
(
    user_id    UUID NOT NULL REFERENCES public.fwd_user(user_id) ON DELETE CASCADE,
    per_id     UUID NOT NULL REFERENCES public.permission(per_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, per_id)
    );

CREATE INDEX IF NOT EXISTS idx_user_permission_perm ON public.user_permission (per_id);

-- =========================
-- Device (must exist before artifact_file due to FK)
-- =========================
CREATE TABLE IF NOT EXISTS public.device (
                                             device_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    serial_number VARCHAR(128) NOT NULL,
    device_name   VARCHAR(128) NOT NULL,
    device_type   VARCHAR(64)  NOT NULL, -- robot/camera/agent...
    urdf_repo_id  UUID,
    tag           VARCHAR(64)  NOT NULL DEFAULT 'LATEST',
    org_id        UUID REFERENCES public.org(org_id) ON DELETE SET NULL,

    updated_by    UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    created_by    UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    user_role     VARCHAR(64),
    device_status VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE/OFFLINE/DISABLED...
    last_seen_at  TIMESTAMPTZ,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_device_serial_number UNIQUE (serial_number),
    CONSTRAINT chk_device_status CHECK (device_status IN ('ACTIVE','OFFLINE','DISABLED'))
    );

CREATE INDEX IF NOT EXISTS idx_device_device_type ON public.device(device_type);
CREATE INDEX IF NOT EXISTS idx_device_device_status ON public.device(device_status);
CREATE INDEX IF NOT EXISTS idx_device_last_seen_at ON public.device(last_seen_at);
CREATE INDEX IF NOT EXISTS idx_device_urdf_repo_id ON public.device(urdf_repo_id);

DROP TRIGGER IF EXISTS trg_device_updated_at ON public.device;
CREATE TRIGGER trg_device_updated_at
    BEFORE UPDATE ON public.device
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =========================
-- Artifact Repository (logical container)
-- =========================
CREATE TABLE IF NOT EXISTS public.artifact_repo (
    repo_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_name      VARCHAR(255) NOT NULL UNIQUE,
    repo_type      VARCHAR(64)  NOT NULL,                   -- e.g. dataset/model/logs
    description    TEXT,
    org_id         UUID REFERENCES public.org(org_id) ON DELETE SET NULL,
    owner_user_id  UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    visibility     VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',   -- PRIVATE/INTERNAL/PUBLIC
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_repo_visibility CHECK (visibility IN ('PRIVATE','INTERNAL','PUBLIC'))
    );

CREATE INDEX IF NOT EXISTS idx_artifact_repo_repo_type
    ON public.artifact_repo(repo_type);

DROP TRIGGER IF EXISTS trg_artifact_repo_updated_at ON public.artifact_repo;
CREATE TRIGGER trg_artifact_repo_updated_at
    BEFORE UPDATE ON public.artifact_repo
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.device
    DROP CONSTRAINT IF EXISTS fk_device_urdf_repo,
    ADD CONSTRAINT fk_device_urdf_repo
        FOREIGN KEY (urdf_repo_id) REFERENCES public.artifact_repo(repo_id) ON DELETE SET NULL;

-- =========================
-- Artifact Commit (immutable snapshot)
-- =========================
CREATE TABLE IF NOT EXISTS public.artifact_commit (
    commit_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_id        UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE CASCADE,

    commit_status  VARCHAR(16) NOT NULL DEFAULT 'DRAFT',      -- DRAFT/PUBLISHED/DEPRECATED
    commit_source  VARCHAR(32) NULL,

    created_by     UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    published_by   UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    comment        TEXT,

    meta           JSONB NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT uq_artifact_commit_repo_commit UNIQUE (repo_id, commit_id),
    CONSTRAINT chk_artifact_commit_status CHECK (commit_status IN ('DRAFT','PUBLISHED','DEPRECATED')),
    CONSTRAINT chk_published_at_when_published CHECK ((commit_status <> 'PUBLISHED') OR (published_at IS NOT NULL))
    );

CREATE INDEX IF NOT EXISTS idx_artifact_commit_repo_id
    ON public.artifact_commit(repo_id);

CREATE INDEX IF NOT EXISTS idx_artifact_commit_status
    ON public.artifact_commit(commit_status);

CREATE INDEX IF NOT EXISTS idx_artifact_commit_repo_latest_published
    ON public.artifact_commit(repo_id, published_at DESC, created_at DESC)
    WHERE commit_status = 'PUBLISHED';

-- =========================
-- Artifact Tag (repo, tag) -> commit in same repo
-- =========================
CREATE TABLE IF NOT EXISTS public.artifact_tag (
    repo_id      UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE CASCADE,
    tag_name     VARCHAR(64) NOT NULL,
    commit_id    UUID NOT NULL,

    created_by   UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (repo_id, tag_name),

    CONSTRAINT fk_tag_commit_same_repo
    FOREIGN KEY (repo_id, commit_id)
    REFERENCES public.artifact_commit(repo_id, commit_id)
                                                                                                                       ON DELETE RESTRICT
    );

CREATE INDEX IF NOT EXISTS idx_artifact_tag_commit_id
    ON public.artifact_tag(commit_id);

DROP TRIGGER IF EXISTS trg_artifact_tag_updated_at ON public.artifact_tag;
CREATE TRIGGER trg_artifact_tag_updated_at
    BEFORE UPDATE ON public.artifact_tag
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =========================
-- Artifact file (MinIO) - files within a commit
-- =========================
CREATE TABLE IF NOT EXISTS public.artifact_file (
    file_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    commit_id        UUID NOT NULL REFERENCES public.artifact_commit(commit_id) ON DELETE CASCADE,
    device_id        UUID REFERENCES public.device(device_id) ON DELETE SET NULL,

    logical_path     TEXT NOT NULL,
    bucket_name      VARCHAR(255) NOT NULL,
    file_key         TEXT NOT NULL,
    minio_version_id TEXT,

    etag             VARCHAR(128),
    sha256           CHAR(64),
    size_bytes       BIGINT NOT NULL DEFAULT 0,
    content_type     VARCHAR(255),

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_artifact_file_commit_path UNIQUE (commit_id, logical_path),
    CONSTRAINT uq_artifact_file_bucket_key UNIQUE (bucket_name, file_key)
    );

CREATE INDEX IF NOT EXISTS idx_artifact_file_commit_id
    ON public.artifact_file(commit_id);

CREATE INDEX IF NOT EXISTS idx_artifact_file_sha256
    ON public.artifact_file(sha256);

CREATE INDEX IF NOT EXISTS idx_artifact_file_device_id
    ON public.artifact_file(device_id);

DROP TRIGGER IF EXISTS trg_artifact_file_updated_at ON public.artifact_file;
CREATE TRIGGER trg_artifact_file_updated_at
    BEFORE UPDATE ON public.artifact_file
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =========================
-- Device Upload
-- commit_id is set when upload materializes into a commit (nullable while uploading)
-- =========================
CREATE TABLE IF NOT EXISTS public.device_upload (
                                                    upload_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    device_id         UUID NOT NULL REFERENCES public.device(device_id) ON DELETE RESTRICT,
    target_repo_id    UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,

    commit_id         UUID,  -- nullable while in progress
    upload_status     device_upload_status NOT NULL DEFAULT 'INITIATED',

    original_filename TEXT,
    error_message     TEXT,

    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,

    created_by        UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    meta              JSONB NOT NULL DEFAULT '{}'::jsonb,

    -- NOTE: use RESTRICT to avoid NOT NULL violation on target_repo_id
    CONSTRAINT fk_device_upload_commit_same_repo
    FOREIGN KEY (target_repo_id, commit_id)
    REFERENCES public.artifact_commit(repo_id, commit_id)                                                                   ON DELETE RESTRICT
    );

CREATE INDEX IF NOT EXISTS idx_device_upload_device_time
    ON public.device_upload(device_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_device_upload_repo_time
    ON public.device_upload(target_repo_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_device_upload_status
    ON public.device_upload(upload_status);

CREATE INDEX IF NOT EXISTS idx_device_upload_commit_id
    ON public.device_upload(commit_id);

-- =========================
-- Job (multi-input + multi-output)
-- =========================
CREATE TABLE IF NOT EXISTS public.job (
    job_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name      TEXT,
    job_category  job_category NOT NULL DEFAULT 'TRANSFORM',
    image         TEXT NULL,
    cmd           TEXT NOT NULL,
    params        JSONB NOT NULL DEFAULT '{}'::jsonb,

    org_id        UUID REFERENCES public.org(org_id) ON DELETE SET NULL,
    created_by    UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
    );

DROP TRIGGER IF EXISTS trg_job_updated_at ON public.job;
CREATE TRIGGER trg_job_updated_at
    BEFORE UPDATE ON public.job
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- =========================
-- Job Input
-- Each input repo ALWAYS uses LAST PUBLISHED at runtime
-- =========================
CREATE TABLE IF NOT EXISTS public.job_input (
    job_id        UUID NOT NULL REFERENCES public.job(job_id) ON DELETE CASCADE,
    input_repo_id UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,

    params        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (job_id, input_repo_id)
    );

CREATE INDEX IF NOT EXISTS idx_job_input_repo
    ON public.job_input(input_repo_id);

-- =========================
-- Job Input File
-- =========================
CREATE TABLE IF NOT EXISTS public.job_input_file(
    job_id       UUID NOT NULL REFERENCES public.job (job_id) ON DELETE CASCADE,
    repo_id      UUID NOT NULL REFERENCES public.artifact_repo (repo_id) ON DELETE RESTRICT,
    logical_path TEXT NOT NULL,

    PRIMARY KEY (job_id, repo_id, logical_path)
    );

-- =========================
-- Job Output (multiple output repos)
-- =========================
CREATE TABLE IF NOT EXISTS public.job_output (
    job_id         UUID NOT NULL REFERENCES public.job(job_id) ON DELETE CASCADE,
    output_repo_id UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,

    params         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (job_id, output_repo_id)
    );

CREATE INDEX IF NOT EXISTS idx_job_output_repo
    ON public.job_output(output_repo_id);

-- =========================
-- Job Run
-- =========================
CREATE TABLE IF NOT EXISTS public.job_run (
    run_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           UUID NOT NULL REFERENCES public.job(job_id) ON DELETE CASCADE,
    org_id           UUID REFERENCES public.org(org_id) ON DELETE SET NULL,

    run_status       run_status NOT NULL DEFAULT 'QUEUED',

    started_at       TIMESTAMPTZ NULL,
    finished_at      TIMESTAMPTZ NULL,

    error_message    TEXT NULL,
    logs_uri         TEXT NULL,
    docker_host      VARCHAR(255) NULL, -- relaxed: may be null in k8s execution

    metrics          JSONB NULL,

    created_by       UUID NULL REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    reserved_gpu     INTEGER NOT NULL DEFAULT 0,
    reserved_cpu     NUMERIC(10,2) NOT NULL DEFAULT 0,
    reserved_memory_mb INTEGER NOT NULL DEFAULT 0,
    runtime_quota_released SMALLINT NOT NULL DEFAULT 1,

    -- ===== 原 job_container 融合字段 =====
    container_id     TEXT NULL,
    container_name   TEXT NULL,

    container_status TEXT NULL,
    docker_status    TEXT NULL,
    exit_code        INTEGER NULL,

    last_seen_at     TIMESTAMPTZ NULL,
    CONSTRAINT chk_job_run_reserved_gpu CHECK (reserved_gpu >= 0),
    CONSTRAINT chk_job_run_reserved_cpu CHECK (reserved_cpu >= 0),
    CONSTRAINT chk_job_run_reserved_memory CHECK (reserved_memory_mb >= 0),
    CONSTRAINT chk_job_run_runtime_quota_released CHECK (runtime_quota_released IN (0, 1))
    );

CREATE INDEX IF NOT EXISTS idx_job_run_job_id      ON public.job_run(job_id);
CREATE INDEX IF NOT EXISTS idx_job_run_org_id      ON public.job_run(org_id);
CREATE INDEX IF NOT EXISTS idx_job_run_status      ON public.job_run(run_status);
CREATE INDEX IF NOT EXISTS idx_job_run_created_at  ON public.job_run(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_job_run_containerid ON public.job_run(container_id);

DO $$ BEGIN
ALTER TABLE public.job_run
    ADD CONSTRAINT uq_job_run_runid_jobid UNIQUE (run_id, job_id);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- =========================
-- Job Run Input (snapshot of resolved commits used by a run)
-- =========================
CREATE TABLE IF NOT EXISTS public.job_run_input (
    run_id           UUID NOT NULL,
    job_id           UUID NOT NULL,

    input_repo_id    UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,
    input_commit_id  UUID NOT NULL,

    input_order      INTEGER NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (run_id, input_repo_id),

    CONSTRAINT fk_job_run_input_run
    FOREIGN KEY (run_id)
    REFERENCES public.job_run(run_id)
                                                                                                                            ON DELETE CASCADE,

    CONSTRAINT fk_job_run_input_declared
    FOREIGN KEY (job_id, input_repo_id)
    REFERENCES public.job_input(job_id, input_repo_id)
                                                                                                                            ON DELETE RESTRICT,

    CONSTRAINT fk_job_run_input_commit_same_repo
    FOREIGN KEY (input_repo_id, input_commit_id)
    REFERENCES public.artifact_commit(repo_id, commit_id)
                                                                                                                            ON DELETE RESTRICT
    );

CREATE INDEX IF NOT EXISTS idx_job_run_input_run_order
    ON public.job_run_input(run_id, input_order);

CREATE INDEX IF NOT EXISTS idx_job_run_input_commit
    ON public.job_run_input(input_commit_id);

-- =========================
-- Job Run Output (snapshot of produced commits for a run)
-- =========================
CREATE TABLE IF NOT EXISTS public.job_run_output (
    run_id            UUID NOT NULL,
    job_id            UUID NOT NULL,

    output_repo_id    UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,
    output_commit_id  UUID NOT NULL,

    output_order      INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (run_id, output_repo_id),

    CONSTRAINT fk_job_run_output_run
    FOREIGN KEY (run_id)
    REFERENCES public.job_run(run_id)
                                                                                                                              ON DELETE CASCADE,

    CONSTRAINT fk_job_run_output_declared
    FOREIGN KEY (job_id, output_repo_id)
    REFERENCES public.job_output(job_id, output_repo_id)
                                                                                                                              ON DELETE RESTRICT,

    CONSTRAINT fk_job_run_output_commit_same_repo
    FOREIGN KEY (output_repo_id, output_commit_id)
    REFERENCES public.artifact_commit(repo_id, commit_id)
                                                                                                                              ON DELETE RESTRICT
    );

CREATE INDEX IF NOT EXISTS idx_job_run_output_run_order
    ON public.job_run_output(run_id, output_order);

CREATE INDEX IF NOT EXISTS idx_job_run_output_commit
    ON public.job_run_output(output_commit_id);


COMMIT;
