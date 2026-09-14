SET search_path TO public;

-- Content identity belongs to the uploaded object, never to multipart ETag.
ALTER TABLE public.artifact_file ADD COLUMN IF NOT EXISTS sha256 CHAR(64);
ALTER TABLE public.artifact_file ADD COLUMN IF NOT EXISTS upload_state VARCHAR(16) NOT NULL DEFAULT 'READY';
ALTER TABLE public.artifact_file DROP CONSTRAINT IF EXISTS chk_artifact_file_upload_state;
ALTER TABLE public.artifact_file ADD CONSTRAINT chk_artifact_file_upload_state
    CHECK (upload_state IN ('PREPARED','READY','FAILED','DELETED'));

-- Existing media-only checks predated document/table/CAD ingestion.
ALTER TABLE public.mm_asset DROP CONSTRAINT IF EXISTS chk_mm_asset_type;
ALTER TABLE public.mm_asset ADD CONSTRAINT chk_mm_asset_type
    CHECK (asset_type IN ('IMAGE','TEXT','DOCUMENT','TABLE','JSON','MESH','CAD','VIDEO','AUDIO','OTHER'));
ALTER TABLE public.mm_dataset DROP CONSTRAINT IF EXISTS chk_mm_dataset_modality;
ALTER TABLE public.mm_dataset ADD CONSTRAINT chk_mm_dataset_modality
    CHECK (modality_type IN ('IMAGE','TEXT','IMAGE_TEXT','DOCUMENT','TABLE','TIMESERIES','GEOMETRY','MIXED','VIDEO','VIDEO_TEXT','AUDIO','AUDIO_TEXT'));

ALTER TABLE public.mm_index_version DROP CONSTRAINT IF EXISTS chk_mm_index_status;
ALTER TABLE public.mm_index_version ADD CONSTRAINT chk_mm_index_status
    CHECK (index_status IN ('QUEUED','BUILDING','PREWARMING','READY','FAILED','DEPRECATED'));

CREATE TABLE IF NOT EXISTS public.mm_ingest_task (
    task_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES public.org(org_id) ON DELETE CASCADE,
    dataset_id UUID NOT NULL REFERENCES public.mm_dataset(dataset_id) ON DELETE CASCADE,
    dataset_version_id UUID NOT NULL REFERENCES public.mm_dataset_version(version_id) ON DELETE CASCADE,
    raw_commit_id UUID NOT NULL REFERENCES public.artifact_commit(commit_id) ON DELETE RESTRICT,
    index_version_id UUID REFERENCES public.mm_index_version(index_version_id) ON DELETE SET NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    task_status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    stage VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until TIMESTAMPTZ,
    created_by UUID REFERENCES public.fwd_user(user_id) ON DELETE SET NULL,
    error_message TEXT,
    meta JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_mm_ingest_task_idempotency UNIQUE (org_id, idempotency_key),
    CONSTRAINT chk_mm_ingest_task_status CHECK (task_status IN ('QUEUED','PROCESSING','WAITING_INDEX','READY','RETRY','FAILED','SUPERSEDED'))
);
CREATE INDEX IF NOT EXISTS idx_mm_ingest_task_claim ON public.mm_ingest_task(task_status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_mm_ingest_task_version ON public.mm_ingest_task(dataset_version_id, created_at DESC);
DROP TRIGGER IF EXISTS trg_mm_ingest_task_updated_at ON public.mm_ingest_task;
CREATE TRIGGER trg_mm_ingest_task_updated_at BEFORE UPDATE ON public.mm_ingest_task
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TABLE IF NOT EXISTS public.mm_outbox_event (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES public.org(org_id) ON DELETE CASCADE,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    payload JSONB NOT NULL,
    event_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT uk_mm_outbox_idempotency UNIQUE (org_id, idempotency_key),
    CONSTRAINT chk_mm_outbox_status CHECK (event_status IN ('PENDING','PROCESSING','PROCESSED','FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_mm_outbox_pending ON public.mm_outbox_event(event_status, created_at);

-- One row per semantic space; the JSON bundle remains the compatibility wire format.
CREATE TABLE IF NOT EXISTS public.mm_index_registration (
    registration_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    index_version_id UUID NOT NULL REFERENCES public.mm_index_version(index_version_id) ON DELETE CASCADE,
    semantic_space VARCHAR(96) NOT NULL,
    index_type VARCHAR(48) NOT NULL,
    algorithm_version VARCHAR(128) NOT NULL,
    model_version VARCHAR(128),
    dimension INTEGER,
    normalization VARCHAR(32) NOT NULL,
    distance_definition VARCHAR(64) NOT NULL,
    build_parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    file_manifest JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_checksum CHAR(64) NOT NULL,
    coverage JSONB NOT NULL DEFAULT '{}'::jsonb,
    registration_status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at TIMESTAMPTZ,
    CONSTRAINT uk_mm_index_registration_space UNIQUE (index_version_id, semantic_space),
    CONSTRAINT chk_mm_index_registration_status CHECK (registration_status IN ('BUILDING','PREWARMING','READY','FAILED','DEPRECATED'))
);

CREATE TABLE IF NOT EXISTS public.mm_asset_relation (
    relation_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES public.org(org_id) ON DELETE CASCADE,
    dataset_version_id UUID NOT NULL REFERENCES public.mm_dataset_version(version_id) ON DELETE CASCADE,
    source_entity_id VARCHAR(256) NOT NULL,
    relation_type VARCHAR(64) NOT NULL,
    target_entity_id VARCHAR(256) NOT NULL,
    provenance JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_mm_asset_relation UNIQUE (org_id, dataset_version_id, source_entity_id, relation_type, target_entity_id)
);
