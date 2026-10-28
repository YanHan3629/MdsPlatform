-- Business records reference the existing immutable mm/artifact snapshots.
-- No organisations, datasets, products, certifications or deliveries are seeded.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mm_version_dataset ON mm_dataset_version(version_id,dataset_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_mm_index_scope ON mm_index_version(index_version_id,dataset_id,dataset_version_id);

CREATE TABLE ds_source (
    source_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL UNIQUE REFERENCES mm_dataset(dataset_id),
    org_id UUID NOT NULL REFERENCES org(org_id),
    source_type VARCHAR(32) NOT NULL CHECK(source_type IN ('EXISTING_DATASET','LOCAL_UPLOAD')),
    description TEXT NOT NULL DEFAULT '',
    created_by UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ds_source_org ON ds_source(org_id);
CREATE TABLE ds_catalog (
    source_id UUID PRIMARY KEY REFERENCES ds_source(source_id),
    catalog_name TEXT NOT NULL,
    business_code VARCHAR(64),
    description TEXT NOT NULL DEFAULT '',
    intended_use TEXT NOT NULL DEFAULT '',
    updated_by UUID NOT NULL REFERENCES fwd_user(user_id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ds_quality_report (
    report_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dataset_id UUID NOT NULL REFERENCES mm_dataset(dataset_id),
    version_id UUID NOT NULL,
    rules_version VARCHAR(80) NOT NULL,
    manifest_digest CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK(status IN ('RUNNING','PASSED','FAILED','PARTIAL')),
    measured_count BIGINT NOT NULL DEFAULT 0 CHECK(measured_count>=0),
    passed_count BIGINT NOT NULL DEFAULT 0 CHECK(passed_count>=0 AND passed_count<=measured_count),
    coverage JSONB NOT NULL DEFAULT '{}'::jsonb,
    checks JSONB NOT NULL DEFAULT '[]'::jsonb,
    score NUMERIC(7,4) CHECK(score>=0 AND score<=100),
    blocking_count BIGINT NOT NULL DEFAULT 0 CHECK(blocking_count>=0),
    created_by UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    FOREIGN KEY(version_id,dataset_id) REFERENCES mm_dataset_version(version_id,dataset_id)
);
CREATE INDEX ds_quality_scope ON ds_quality_report(version_id,created_at DESC);
CREATE TABLE ds_product (
    product_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID NOT NULL REFERENCES ds_source(source_id),
    provider_org_id UUID NOT NULL REFERENCES org(org_id),
    product_name TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    business_code VARCHAR(64),
    visibility VARCHAR(16) NOT NULL CHECK(visibility IN ('PUBLIC','INTERNAL')),
    status VARCHAR(16) NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','OFFLINE')),
    created_by UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ds_product_visibility ON ds_product(status,visibility,provider_org_id);
CREATE TABLE ds_product_version (
    product_version_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES ds_product(product_id),
    revision INTEGER NOT NULL CHECK(revision>0),
    dataset_id UUID NOT NULL REFERENCES mm_dataset(dataset_id),
    version_id UUID NOT NULL,
    index_version_id UUID NOT NULL,
    raw_commit_id UUID NOT NULL REFERENCES artifact_commit(commit_id),
    manifest_digest CHAR(64) NOT NULL,
    file_count BIGINT NOT NULL CHECK(file_count>0),
    size_bytes BIGINT NOT NULL CHECK(size_bytes>=0),
    formats JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_types JSONB NOT NULL DEFAULT '[]'::jsonb,
    allowed_use TEXT NOT NULL,
    quality_report_id UUID REFERENCES ds_quality_report(report_id),
    created_by UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(product_id,revision),
    FOREIGN KEY(version_id,dataset_id) REFERENCES mm_dataset_version(version_id,dataset_id),
    FOREIGN KEY(index_version_id,dataset_id,version_id) REFERENCES mm_index_version(index_version_id,dataset_id,dataset_version_id)
);
CREATE INDEX ds_product_version_scope ON ds_product_version(dataset_id,version_id,index_version_id);
CREATE TABLE ds_application (
    application_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_version_id UUID NOT NULL REFERENCES ds_product_version(product_version_id),
    consumer_org_id UUID NOT NULL REFERENCES org(org_id),
    purpose TEXT NOT NULL,
    expected_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    decision_reason TEXT,
    decided_by UUID REFERENCES fwd_user(user_id),
    decided_at TIMESTAMPTZ,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(consumer_org_id,idempotency_key)
);
CREATE TABLE ds_receiver_endpoint (
    endpoint_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_org_id UUID NOT NULL REFERENCES org(org_id),
    endpoint_name TEXT NOT NULL,
    adapter_type VARCHAR(24) NOT NULL CHECK(adapter_type='LOCAL_VERIFIED'),
    receiver_identity VARCHAR(128) NOT NULL,
    storage_prefix TEXT NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_by UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ds_contract (
    contract_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL UNIQUE REFERENCES ds_application(application_id),
    product_version_id UUID NOT NULL REFERENCES ds_product_version(product_version_id),
    provider_org_id UUID NOT NULL REFERENCES org(org_id),
    consumer_org_id UUID NOT NULL REFERENCES org(org_id),
    revision INTEGER NOT NULL DEFAULT 1 CHECK(revision>0),
    purpose TEXT NOT NULL,
    scope_type VARCHAR(24) NOT NULL DEFAULT 'FULL_VERSION' CHECK(scope_type='FULL_VERSION'),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ NOT NULL CHECK(valid_to>valid_from),
    scheduled_at TIMESTAMPTZ NOT NULL,
    endpoint_id UUID NOT NULL REFERENCES ds_receiver_endpoint(endpoint_id),
    minimum_quality NUMERIC(7,4) CHECK(minimum_quality>=0 AND minimum_quality<=100),
    status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING_CONFIRMATION','CONFIRMED','REVOKED','CANCELLED')),
    provider_confirmed_at TIMESTAMPTZ,
    consumer_confirmed_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoke_reason TEXT,
    created_by UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(scheduled_at>=valid_from AND scheduled_at<valid_to),
    CHECK(status<>'CONFIRMED' OR (provider_confirmed_at IS NOT NULL AND consumer_confirmed_at IS NOT NULL))
);
CREATE INDEX ds_contract_access ON ds_contract(consumer_org_id,status,valid_from,valid_to);
CREATE TABLE ds_contract_change (
    contract_id UUID NOT NULL REFERENCES ds_contract(contract_id),
    revision INTEGER NOT NULL,
    terms JSONB NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES fwd_user(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(contract_id,revision)
);
CREATE TABLE ds_delivery_task (
    task_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id UUID NOT NULL REFERENCES ds_contract(contract_id),
    contract_revision INTEGER NOT NULL,
    delivery_key UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','RUNNING','BLOCKED','RETRY','FAILED','DELIVERED','CANCELLED')),
    manifest_digest CHAR(64),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
    max_attempts INTEGER NOT NULL DEFAULT 5 CHECK(max_attempts BETWEEN 1 AND 20),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(contract_id,contract_revision)
);
CREATE INDEX ds_delivery_due ON ds_delivery_task(status,next_attempt_at,lease_until);
CREATE TABLE ds_delivery_attempt (
    attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES ds_delivery_task(task_id),
    attempt_number INTEGER NOT NULL CHECK(attempt_number>0),
    status VARCHAR(24) NOT NULL CHECK(status IN ('RUNNING','FAILED','RECEIVED','RECONCILED','CANCELLED')),
    error_message TEXT,
    actor_user_id UUID REFERENCES fwd_user(user_id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    UNIQUE(task_id,attempt_number)
);
CREATE TABLE ds_receipt (
    receipt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_key UUID NOT NULL REFERENCES ds_delivery_task(delivery_key),
    endpoint_id UUID NOT NULL REFERENCES ds_receiver_endpoint(endpoint_id),
    consumer_org_id UUID NOT NULL REFERENCES org(org_id),
    contract_revision INTEGER NOT NULL,
    manifest_digest CHAR(64) NOT NULL,
    file_count BIGINT NOT NULL CHECK(file_count>0),
    size_bytes BIGINT NOT NULL CHECK(size_bytes>=0),
    verification JSONB NOT NULL,
    receiver_identity VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(delivery_key,endpoint_id)
);
CREATE TABLE ds_audit_event (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES org(org_id),
    actor_user_id UUID REFERENCES fwd_user(user_id),
    action VARCHAR(80) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id UUID NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ds_audit_org_time ON ds_audit_event(org_id,created_at DESC);

CREATE FUNCTION ds_reject_frozen_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Published product versions, receipts and audit records are immutable; create a new revision';
END;
$$;
CREATE TRIGGER ds_product_version_frozen BEFORE UPDATE OR DELETE ON ds_product_version
    FOR EACH ROW EXECUTE FUNCTION ds_reject_frozen_change();
CREATE TRIGGER ds_receipt_frozen BEFORE UPDATE OR DELETE ON ds_receipt
    FOR EACH ROW EXECUTE FUNCTION ds_reject_frozen_change();
CREATE TRIGGER ds_contract_change_frozen BEFORE UPDATE OR DELETE ON ds_contract_change
    FOR EACH ROW EXECUTE FUNCTION ds_reject_frozen_change();
CREATE TRIGGER ds_audit_frozen BEFORE UPDATE OR DELETE ON ds_audit_event
    FOR EACH ROW EXECUTE FUNCTION ds_reject_frozen_change();
