-- Align historical job_run schema with current mapper/entity fields.
ALTER TABLE public.job_run
    ADD COLUMN IF NOT EXISTS org_id UUID,
    ADD COLUMN IF NOT EXISTS reserved_gpu INTEGER,
    ADD COLUMN IF NOT EXISTS reserved_cpu NUMERIC,
    ADD COLUMN IF NOT EXISTS reserved_memory_mb INTEGER,
    ADD COLUMN IF NOT EXISTS runtime_quota_released INTEGER;

-- Backfill org_id from parent job records when possible.
UPDATE public.job_run jr
SET org_id = j.org_id
FROM public.job j
WHERE jr.job_id = j.job_id
  AND jr.org_id IS NULL
  AND j.org_id IS NOT NULL;

-- Normalize runtime quota release marker for old rows.
UPDATE public.job_run
SET runtime_quota_released = 0
WHERE runtime_quota_released IS NULL;

ALTER TABLE public.job_run
    ALTER COLUMN runtime_quota_released SET DEFAULT 0,
    ALTER COLUMN runtime_quota_released SET NOT NULL;

DO $$
BEGIN
    ALTER TABLE public.job_run
        ADD CONSTRAINT job_run_org_id_fkey
            FOREIGN KEY (org_id) REFERENCES public.org(org_id) ON DELETE SET NULL;
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_job_run_org_id ON public.job_run(org_id);
