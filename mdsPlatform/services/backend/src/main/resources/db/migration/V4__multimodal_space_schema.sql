SET search_path TO public;

CREATE TABLE IF NOT EXISTS public.mm_space (
    space_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_name      VARCHAR(128) NOT NULL,
    description     TEXT,
    org_id          UUID NOT NULL REFERENCES public.org(org_id) ON DELETE CASCADE,
    owner_user_id   UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    visibility      VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    meta            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mm_space_org_name UNIQUE (org_id, space_name),
    CONSTRAINT chk_mm_space_visibility CHECK (visibility IN ('PRIVATE','INTERNAL','PUBLIC')),
    CONSTRAINT chk_mm_space_status CHECK (status IN ('ACTIVE','DISABLED','ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_mm_space_org_id ON public.mm_space(org_id);
DROP TRIGGER IF EXISTS trg_mm_space_updated_at ON public.mm_space;
CREATE TRIGGER trg_mm_space_updated_at BEFORE UPDATE ON public.mm_space
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.mm_dataset (
    dataset_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id         UUID NOT NULL REFERENCES public.mm_space(space_id) ON DELETE CASCADE,
    dataset_name     VARCHAR(128) NOT NULL,
    dataset_type     VARCHAR(32) NOT NULL,
    modality_type    VARCHAR(32) NOT NULL,
    description      TEXT,
    raw_repo_id      UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,
    index_repo_id    UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,
    owner_user_id    UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    org_id           UUID NOT NULL REFERENCES public.org(org_id) ON DELETE CASCADE,
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    meta             JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mm_dataset_space_name UNIQUE (space_id, dataset_name),
    CONSTRAINT chk_mm_dataset_type CHECK (dataset_type IN ('GENERAL','TRAIN','VAL','TEST','CORPUS')),
    CONSTRAINT chk_mm_dataset_modality CHECK (modality_type IN ('IMAGE','TEXT','IMAGE_TEXT','VIDEO','VIDEO_TEXT','AUDIO','AUDIO_TEXT')),
    CONSTRAINT chk_mm_dataset_status CHECK (status IN ('ACTIVE','ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_mm_dataset_space_id ON public.mm_dataset(space_id);
CREATE INDEX IF NOT EXISTS idx_mm_dataset_org_id ON public.mm_dataset(org_id);
DROP TRIGGER IF EXISTS trg_mm_dataset_updated_at ON public.mm_dataset;
CREATE TRIGGER trg_mm_dataset_updated_at BEFORE UPDATE ON public.mm_dataset
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.mm_dataset_version (
    version_id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id                UUID NOT NULL REFERENCES public.mm_dataset(dataset_id) ON DELETE CASCADE,
    version_name              VARCHAR(64) NOT NULL,
    raw_commit_id             UUID NOT NULL REFERENCES public.artifact_commit(commit_id) ON DELETE RESTRICT,
    version_status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    sample_count              BIGINT NOT NULL DEFAULT 0,
    image_count               BIGINT NOT NULL DEFAULT 0,
    text_count                BIGINT NOT NULL DEFAULT 0,
    active_index_version_id   UUID NULL,
    comment                   TEXT,
    meta                      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by                UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    published_by              UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at              TIMESTAMPTZ,
    CONSTRAINT uk_mm_dataset_version_name UNIQUE (dataset_id, version_name),
    CONSTRAINT uk_mm_dataset_raw_commit UNIQUE (raw_commit_id),
    CONSTRAINT chk_mm_dataset_version_status CHECK (version_status IN ('DRAFT','UPLOADING','RAW_READY','INDEXING','READY','PUBLISHED','FAILED','DEPRECATED'))
);

CREATE INDEX IF NOT EXISTS idx_mm_dataset_version_dataset_id ON public.mm_dataset_version(dataset_id);
CREATE INDEX IF NOT EXISTS idx_mm_dataset_version_status ON public.mm_dataset_version(version_status);
DROP TRIGGER IF EXISTS trg_mm_dataset_version_updated_at ON public.mm_dataset_version;
CREATE TRIGGER trg_mm_dataset_version_updated_at BEFORE UPDATE ON public.mm_dataset_version
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.mm_asset (
    asset_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_version_id    UUID NOT NULL REFERENCES public.mm_dataset_version(version_id) ON DELETE CASCADE,
    file_id               UUID NULL REFERENCES public.artifact_file(file_id) ON DELETE SET NULL,
    asset_type            VARCHAR(16) NOT NULL,
    logical_path          TEXT NOT NULL,
    file_name             VARCHAR(255) NOT NULL,
    source_asset_code     VARCHAR(128),
    sha256                CHAR(64),
    size_bytes            BIGINT,
    content_type          VARCHAR(255),
    width                 INTEGER,
    height                INTEGER,
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    meta                  JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mm_asset_version_path UNIQUE (dataset_version_id, logical_path),
    CONSTRAINT chk_mm_asset_type CHECK (asset_type IN ('IMAGE','TEXT','VIDEO','AUDIO','JSON','OTHER')),
    CONSTRAINT chk_mm_asset_status CHECK (status IN ('ACTIVE','DELETED','INVALID'))
);

CREATE INDEX IF NOT EXISTS idx_mm_asset_version_id ON public.mm_asset(dataset_version_id);
CREATE INDEX IF NOT EXISTS idx_mm_asset_source_code ON public.mm_asset(source_asset_code);
DROP TRIGGER IF EXISTS trg_mm_asset_updated_at ON public.mm_asset;
CREATE TRIGGER trg_mm_asset_updated_at BEFORE UPDATE ON public.mm_asset
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.mm_asset_text (
    asset_text_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id           UUID NOT NULL REFERENCES public.mm_asset(asset_id) ON DELETE CASCADE,
    text_role          VARCHAR(16) NOT NULL DEFAULT 'CAPTION',
    seq_no             INTEGER NOT NULL DEFAULT 1,
    language_code      VARCHAR(8) NOT NULL DEFAULT 'unknown',
    content            TEXT NOT NULL,
    source_type        VARCHAR(16) NOT NULL DEFAULT 'IMPORTED',
    meta               JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_mm_asset_text_role CHECK (text_role IN ('CAPTION','TITLE','OCR','TAG','DESC')),
    CONSTRAINT chk_mm_asset_text_source CHECK (source_type IN ('IMPORTED','MANUAL','GENERATED'))
);

CREATE INDEX IF NOT EXISTS idx_mm_asset_text_asset_id ON public.mm_asset_text(asset_id);
CREATE INDEX IF NOT EXISTS idx_mm_asset_text_role ON public.mm_asset_text(text_role);

CREATE TABLE IF NOT EXISTS public.mm_tag (
    tag_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id         UUID NOT NULL REFERENCES public.mm_space(space_id) ON DELETE CASCADE,
    tag_name         VARCHAR(64) NOT NULL,
    tag_color        VARCHAR(16),
    created_by       UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mm_tag_space_name UNIQUE (space_id, tag_name)
);

CREATE TABLE IF NOT EXISTS public.mm_asset_tag (
    asset_id         UUID NOT NULL REFERENCES public.mm_asset(asset_id) ON DELETE CASCADE,
    tag_id           UUID NOT NULL REFERENCES public.mm_tag(tag_id) ON DELETE CASCADE,
    source_type      VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    score            NUMERIC(6,4),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, tag_id),
    CONSTRAINT chk_mm_asset_tag_source CHECK (source_type IN ('MANUAL','AUTO','IMPORTED'))
);

CREATE TABLE IF NOT EXISTS public.mm_category (
    category_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id       UUID NOT NULL REFERENCES public.mm_dataset(dataset_id) ON DELETE CASCADE,
    parent_id        UUID NULL REFERENCES public.mm_category(category_id) ON DELETE SET NULL,
    category_code    VARCHAR(64),
    category_name    VARCHAR(128) NOT NULL,
    source_type      VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_mm_category_source CHECK (source_type IN ('MANUAL','AUTO'))
);

CREATE TABLE IF NOT EXISTS public.mm_asset_category (
    asset_id         UUID NOT NULL REFERENCES public.mm_asset(asset_id) ON DELETE CASCADE,
    category_id      UUID NOT NULL REFERENCES public.mm_category(category_id) ON DELETE CASCADE,
    score            NUMERIC(6,4),
    source_type      VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, category_id),
    CONSTRAINT chk_mm_asset_category_source CHECK (source_type IN ('MANUAL','AUTO'))
);

CREATE TABLE IF NOT EXISTS public.mm_index_version (
    index_version_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id          UUID NOT NULL REFERENCES public.mm_dataset(dataset_id) ON DELETE CASCADE,
    dataset_version_id  UUID NOT NULL REFERENCES public.mm_dataset_version(version_id) ON DELETE CASCADE,
    index_repo_id       UUID NOT NULL REFERENCES public.artifact_repo(repo_id) ON DELETE RESTRICT,
    index_commit_id     UUID NULL REFERENCES public.artifact_commit(commit_id) ON DELETE RESTRICT,
    index_status        VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    index_type          VARCHAR(32) NOT NULL,
    model_name          VARCHAR(128) NOT NULL,
    model_version       VARCHAR(64),
    embedding_dim       INTEGER,
    image_index_path    TEXT,
    text_index_path     TEXT,
    metadata_path       TEXT,
    manifest_path       TEXT,
    image_count         BIGINT NOT NULL DEFAULT 0,
    text_count          BIGINT NOT NULL DEFAULT 0,
    build_job_id        UUID NULL REFERENCES public.job(job_id) ON DELETE SET NULL,
    build_run_id        UUID NULL REFERENCES public.job_run(run_id) ON DELETE SET NULL,
    error_message       TEXT,
    meta                JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at            TIMESTAMPTZ,
    CONSTRAINT chk_mm_index_status CHECK (index_status IN ('QUEUED','BUILDING','READY','FAILED','DEPRECATED'))
);

CREATE INDEX IF NOT EXISTS idx_mm_index_version_dataset_version ON public.mm_index_version(dataset_version_id);
CREATE INDEX IF NOT EXISTS idx_mm_index_version_status ON public.mm_index_version(index_status);
DROP TRIGGER IF EXISTS trg_mm_index_version_updated_at ON public.mm_index_version;
CREATE TRIGGER trg_mm_index_version_updated_at BEFORE UPDATE ON public.mm_index_version
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DO $$ BEGIN
ALTER TABLE public.mm_dataset_version
    ADD CONSTRAINT fk_mm_dataset_version_active_index
    FOREIGN KEY (active_index_version_id) REFERENCES public.mm_index_version(index_version_id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE TABLE IF NOT EXISTS public.mm_search_log (
    search_log_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id          UUID NOT NULL REFERENCES public.mm_dataset(dataset_id) ON DELETE CASCADE,
    dataset_version_id  UUID NOT NULL REFERENCES public.mm_dataset_version(version_id) ON DELETE CASCADE,
    index_version_id    UUID NULL REFERENCES public.mm_index_version(index_version_id) ON DELETE SET NULL,
    query_type          VARCHAR(16) NOT NULL,
    query_text          TEXT,
    query_asset_id      UUID NULL REFERENCES public.mm_asset(asset_id) ON DELETE SET NULL,
    query_temp_file_key TEXT,
    top_k               INTEGER NOT NULL DEFAULT 10,
    result_count        INTEGER NOT NULL DEFAULT 0,
    latency_ms          INTEGER,
    request_user_id     UUID NULL REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    meta                JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_mm_search_log_type CHECK (query_type IN ('TEXT_TO_IMAGE','IMAGE_TO_TEXT'))
);

CREATE INDEX IF NOT EXISTS idx_mm_search_log_dataset_id ON public.mm_search_log(dataset_id);
CREATE INDEX IF NOT EXISTS idx_mm_search_log_created_at ON public.mm_search_log(created_at DESC);
