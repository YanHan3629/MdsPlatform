-- Quality runs are measured against immutable raw snapshots. No raw data is changed.
ALTER TABLE ds_quality_report ADD COLUMN rule_config JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE ds_quality_report ADD COLUMN request_key VARCHAR(128);
CREATE UNIQUE INDEX ds_quality_request ON ds_quality_report(dataset_id,request_key) WHERE request_key IS NOT NULL;
CREATE TABLE ds_quality_job (
    report_id UUID PRIMARY KEY REFERENCES ds_quality_report(report_id),
    status VARCHAR(16) NOT NULL CHECK(status IN ('QUEUED','RUNNING','COMPLETED','FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ds_quality_job_due ON ds_quality_job(status,next_attempt_at,lease_until);
ALTER TABLE ds_product ADD COLUMN request_key VARCHAR(128);
CREATE UNIQUE INDEX ds_product_request ON ds_product(provider_org_id,request_key) WHERE request_key IS NOT NULL;
CREATE FUNCTION ds_quality_keep_completed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.completed_at IS NOT NULL THEN
        RAISE EXCEPTION 'Completed quality reports are immutable; request a new run';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ds_quality_completed BEFORE UPDATE OR DELETE ON ds_quality_report
    FOR EACH ROW EXECUTE FUNCTION ds_quality_keep_completed();
