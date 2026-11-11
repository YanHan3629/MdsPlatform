CREATE TABLE ds_index_execution_origin(
 run_id uuid PRIMARY KEY REFERENCES job_run(run_id),
 job_id uuid NOT NULL REFERENCES job(job_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 token_version integer NOT NULL,
 dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id),
 version_id uuid NOT NULL REFERENCES mm_dataset_version(version_id),
 index_version_id uuid NOT NULL UNIQUE REFERENCES mm_index_version(index_version_id),
 input_repo_id uuid NOT NULL REFERENCES artifact_repo(repo_id),
 input_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id),
 output_repo_id uuid NOT NULL REFERENCES artifact_repo(repo_id),
 output_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id),
 previous_index_commit_id uuid REFERENCES artifact_commit(commit_id),
 grant_id uuid NOT NULL REFERENCES ds_resource_grant(grant_id),
 grant_version bigint NOT NULL CHECK(grant_version>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ds_index_callback_receipt(
 run_id uuid PRIMARY KEY REFERENCES ds_index_execution_origin(run_id),
 callback_kind varchar(16) NOT NULL CHECK(callback_kind IN('READY','FAILED')),
 payload_sha256 varchar(64) NOT NULL CHECK(payload_sha256 ~ '^[0-9a-f]{64}$'),
 accepted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION reject_index_execution_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'index execution origins and callback receipts are append only' USING ERRCODE='42501';
END
$$;
CREATE TRIGGER immutable_index_execution_origin BEFORE UPDATE OR DELETE ON ds_index_execution_origin
 FOR EACH ROW EXECUTE FUNCTION reject_index_execution_rewrite();
CREATE TRIGGER immutable_index_callback_receipt BEFORE UPDATE OR DELETE ON ds_index_callback_receipt
 FOR EACH ROW EXECUTE FUNCTION reject_index_execution_rewrite();
