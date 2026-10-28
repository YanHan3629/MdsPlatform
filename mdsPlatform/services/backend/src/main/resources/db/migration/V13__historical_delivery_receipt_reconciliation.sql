-- Receipt recovery is independent of permission to send another package.
-- Keep its attempts/lease separate so cancelled deliveries never become sendable.
CREATE TABLE ds_delivery_receipt_reconciliation (
    task_id UUID PRIMARY KEY REFERENCES ds_delivery_task(task_id),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK(status IN ('PENDING','RUNNING','RETRY','RECONCILED','EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 5),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error TEXT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ds_delivery_receipt_reconciliation_due
    ON ds_delivery_receipt_reconciliation(status,next_attempt_at,lease_until);
