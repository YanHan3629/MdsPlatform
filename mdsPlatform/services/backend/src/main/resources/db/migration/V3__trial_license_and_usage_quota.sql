-- 为组织增加试用授权类型字段
ALTER TABLE public.org
    ADD COLUMN IF NOT EXISTS license_type VARCHAR(16) NOT NULL DEFAULT 'FORMAL';
ALTER TABLE public.org
    ADD COLUMN IF NOT EXISTS created_by UUID;
ALTER TABLE public.org
    ADD COLUMN IF NOT EXISTS updated_by UUID;

-- 为任务运行状态枚举补充 CANCELING
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_type WHERE typname = 'run_status'
    ) AND NOT EXISTS (
        SELECT 1
        FROM pg_enum e
        JOIN pg_type t ON t.oid = e.enumtypid
        WHERE t.typname = 'run_status'
          AND e.enumlabel = 'CANCELING'
    ) THEN
        ALTER TYPE public.run_status ADD VALUE 'CANCELING';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_org_license_type'
    ) THEN
        ALTER TABLE public.org
            ADD CONSTRAINT chk_org_license_type
            CHECK (license_type IN ('TRIAL', 'FORMAL'));
    END IF;
END $$;

-- 新增组织共享存储用量表
CREATE TABLE IF NOT EXISTS public.org_storage_usage (
    org_id UUID PRIMARY KEY REFERENCES public.org(org_id) ON DELETE CASCADE,
    used_storage_bytes BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_org_storage_usage_bytes CHECK (used_storage_bytes >= 0)
);

DROP TRIGGER IF EXISTS trg_org_storage_usage_updated_at ON public.org_storage_usage;
CREATE TRIGGER trg_org_storage_usage_updated_at
    BEFORE UPDATE ON public.org_storage_usage
    FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- 新增 License 配额策略表（全局两档：TRIAL/FORMAL）
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
