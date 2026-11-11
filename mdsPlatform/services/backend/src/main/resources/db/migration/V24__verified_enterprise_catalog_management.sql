-- Verified enterprise directories and reviewed mappings. No historical business
-- row, organization ownership, account qualification or published snapshot is rewritten.
CREATE TABLE ds_enterprise_catalog_revision(
 catalog_revision_id uuid PRIMARY KEY,
 source_dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id),
 source_version_id uuid NOT NULL REFERENCES mm_dataset_version(version_id),
 source_raw_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id),
 source_file_id uuid NOT NULL REFERENCES artifact_file(file_id),
 source_logical_path text NOT NULL,
 source_sha256 char(64) NOT NULL CHECK(source_sha256~'^[0-9a-f]{64}$'),
 source_size_bytes bigint NOT NULL CHECK(source_size_bytes>0 AND source_size_bytes<=1048576),
 entries_sha256 char(64) NOT NULL CHECK(entries_sha256~'^[0-9a-f]{64}$'),
 company_count integer NOT NULL CHECK(company_count>0 AND company_count<=10000),
 node_count integer NOT NULL CHECK(node_count>=0),
 parser_version varchar(64) NOT NULL CHECK(parser_version='enterprise-master-v1'),
 source_authority jsonb NOT NULL,
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 imported_by uuid NOT NULL REFERENCES fwd_user(user_id),
 imported_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(source_dataset_id,source_version_id,source_logical_path,source_sha256)
);
CREATE TABLE ds_enterprise_catalog_entry(
 catalog_revision_id uuid NOT NULL REFERENCES ds_enterprise_catalog_revision(catalog_revision_id),
 entity_id varchar(128) NOT NULL,
 enterprise_name varchar(256) NOT NULL,
 industry_kind varchar(32) NOT NULL CHECK(industry_kind IN('MANUFACTURER','SUPPLIER','WAREHOUSE','CARRIER','CUSTOMER','SERVICE')),
 source_row integer NOT NULL CHECK(source_row>1),
 PRIMARY KEY(catalog_revision_id,entity_id)
);
CREATE TABLE ds_enterprise_catalog_custodian(
 org_id uuid PRIMARY KEY REFERENCES org(org_id),
 catalog_revision_id uuid NOT NULL REFERENCES ds_enterprise_catalog_revision(catalog_revision_id),
 source_dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id),
 protected_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION reject_enterprise_catalog_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'ENTERPRISE_CATALOG_IMMUTABLE' USING ERRCODE='42501'; END
$$;
CREATE TRIGGER enterprise_catalog_immutable BEFORE UPDATE OR DELETE ON ds_enterprise_catalog_revision
 FOR EACH ROW EXECUTE FUNCTION reject_enterprise_catalog_rewrite();
CREATE TRIGGER enterprise_catalog_entry_immutable BEFORE UPDATE OR DELETE ON ds_enterprise_catalog_entry
 FOR EACH ROW EXECUTE FUNCTION reject_enterprise_catalog_rewrite();
CREATE TRIGGER enterprise_catalog_custodian_immutable BEFORE UPDATE OR DELETE ON ds_enterprise_catalog_custodian
 FOR EACH ROW EXECUTE FUNCTION reject_enterprise_catalog_rewrite();

ALTER TABLE ds_enterprise_identity ADD COLUMN catalog_revision_id uuid REFERENCES ds_enterprise_catalog_revision(catalog_revision_id);
CREATE TABLE ds_enterprise_mapping_review(
 review_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 actor_token_version integer NOT NULL,
 target_org_id uuid NOT NULL REFERENCES org(org_id),
 action varchar(16) NOT NULL CHECK(action IN('CONFIRM','DISABLE')),
 payload_sha256 char(64) NOT NULL CHECK(payload_sha256~'^[0-9a-f]{64}$'),
 payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 applied_at timestamptz,
 result_mapping_version bigint,
 CHECK(expires_at>created_at),
 CHECK((applied_at IS NULL AND result_mapping_version IS NULL) OR
       (applied_at IS NOT NULL AND result_mapping_version>0))
);
CREATE FUNCTION guard_enterprise_mapping_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  RAISE EXCEPTION 'ENTERPRISE_MAPPING_REVIEW_IMMUTABLE' USING ERRCODE='42501';
 END IF;
 IF (to_jsonb(NEW)-'applied_at'-'result_mapping_version') IS DISTINCT FROM
    (to_jsonb(OLD)-'applied_at'-'result_mapping_version') OR OLD.applied_at IS NOT NULL OR NEW.applied_at IS NULL OR NEW.result_mapping_version IS NULL THEN
  RAISE EXCEPTION 'ENTERPRISE_MAPPING_REVIEW_IMMUTABLE' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END
$$;
CREATE TRIGGER enterprise_mapping_review_guard BEFORE UPDATE OR DELETE ON ds_enterprise_mapping_review
 FOR EACH ROW EXECUTE FUNCTION guard_enterprise_mapping_review();
CREATE TABLE ds_enterprise_mapping_event(
 event_id uuid PRIMARY KEY,
 review_id uuid NOT NULL UNIQUE REFERENCES ds_enterprise_mapping_review(review_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 target_org_id uuid NOT NULL REFERENCES org(org_id),
 mapping_id uuid NOT NULL REFERENCES ds_enterprise_identity(mapping_id),
 mapping_version bigint NOT NULL,
 reason_code varchar(80) NOT NULL,
 before_value jsonb,
 after_value jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER enterprise_mapping_event_immutable BEFORE UPDATE OR DELETE ON ds_enterprise_mapping_event
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
