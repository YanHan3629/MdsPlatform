CREATE TABLE ds_dashboard_business_metadata (
    version_id uuid PRIMARY KEY REFERENCES mm_dataset_version(version_id),
    display_name varchar(160) NOT NULL,
    business_codes text[] NOT NULL,
    evidence_reference varchar(300) NOT NULL,
    updated_by uuid NOT NULL REFERENCES fwd_user(user_id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (cardinality(business_codes) <= 9)
);
