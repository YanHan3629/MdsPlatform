SET search_path TO public;

-- Job runtime reservations are distinct from durable artifact storage usage.
-- The service already enforces these counters, so every supported schema must
-- persist them atomically with the existing organisation usage row.
ALTER TABLE public.org_storage_usage
    ADD COLUMN IF NOT EXISTS used_gpu INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_cpu NUMERIC(12, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS used_memory_mb INTEGER NOT NULL DEFAULT 0;

ALTER TABLE public.org_storage_usage DROP CONSTRAINT IF EXISTS chk_org_storage_usage_runtime;
ALTER TABLE public.org_storage_usage ADD CONSTRAINT chk_org_storage_usage_runtime
    CHECK (used_gpu >= 0 AND used_cpu >= 0 AND used_memory_mb >= 0);
