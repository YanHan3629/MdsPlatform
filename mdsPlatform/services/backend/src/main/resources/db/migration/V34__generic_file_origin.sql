-- Physical objects and frozen source snapshots are append-only audit records.
CREATE TABLE ds_generic_execution_origin (
 run_id uuid PRIMARY KEY REFERENCES ds_job_execution_definition(run_id),
 definition_sha256 char(64) NOT NULL,
 actor_user_id uuid NOT NULL,
 actor_org_id uuid NOT NULL,
 snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
 snapshot_sha256 char(64) NOT NULL,
 capture_kind varchar(48) NOT NULL CHECK(capture_kind IN('NEW_FROZEN_EXECUTION','UNIQUE_COMMITTED_UPLOAD_RECONSTRUCTION')),
 captured_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ds_generic_file_origin (
 object_key text PRIMARY KEY,
 file_id uuid NOT NULL,
 repo_id uuid NOT NULL,
 commit_id uuid NOT NULL,
 file_sha256 char(64) NOT NULL,
 run_id uuid NOT NULL REFERENCES ds_generic_execution_origin(run_id),
 definition_sha256 char(64) NOT NULL,
 capture_kind varchar(48) NOT NULL CHECK(capture_kind IN('VERIFIED_WORKER_UPLOAD','UNIQUE_COMMITTED_UPLOAD_RECONSTRUCTION')),
 captured_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX generic_file_origin_file_id_idx ON ds_generic_file_origin(file_id);
CREATE INDEX generic_file_origin_run_id_idx ON ds_generic_file_origin(run_id);
CREATE FUNCTION ds_generic_origin_immutable() RETURNS trigger LANGUAGE plpgsql AS $immutable$
BEGIN
 RAISE EXCEPTION 'GENERIC_ORIGIN_AUDIT_IS_IMMUTABLE' USING ERRCODE='23514';
END $immutable$;
CREATE TRIGGER generic_execution_origin_immutable BEFORE UPDATE OR DELETE ON ds_generic_execution_origin
 FOR EACH ROW EXECUTE FUNCTION ds_generic_origin_immutable();
CREATE TRIGGER generic_file_origin_immutable BEFORE UPDATE OR DELETE ON ds_generic_file_origin
 FOR EACH ROW EXECUTE FUNCTION ds_generic_origin_immutable();
