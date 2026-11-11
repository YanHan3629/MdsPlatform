-- New execution records only. Do not reconstruct historical code from mutable jobs.
CREATE TABLE ds_job_execution_definition(
 run_id uuid PRIMARY KEY REFERENCES job_run(run_id),
 job_id uuid NOT NULL REFERENCES job(job_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 definition_sha256 char(64) NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
 entrypoint_sha256 char(64) NOT NULL CHECK(entrypoint_sha256 ~ '^[0-9a-f]{64}$'),
 bootstrap_sha256 char(64) NOT NULL CHECK(bootstrap_sha256 ~ '^[0-9a-f]{64}$'),
 expires_at timestamptz NOT NULL,
 definition jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX job_execution_definition_job ON ds_job_execution_definition(job_id,created_at);
CREATE FUNCTION reject_job_execution_definition_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'job execution definitions are append only' USING ERRCODE='42501';
END
$$;
CREATE TRIGGER immutable_job_execution_definition BEFORE UPDATE OR DELETE ON ds_job_execution_definition
 FOR EACH ROW EXECUTE FUNCTION reject_job_execution_definition_rewrite();

-- Version the existing standalone private-artifact ACL so a transfer away and
-- back cannot revive an old execution. This is not a new implicit business role.
CREATE TABLE ds_artifact_acl_generation(
 repo_id uuid PRIMARY KEY,
 version bigint NOT NULL CHECK(version>0),
 deleted boolean NOT NULL DEFAULT false
);
INSERT INTO ds_artifact_acl_generation(repo_id,version)
 SELECT repo_id,1 FROM artifact_repo;
CREATE FUNCTION advance_artifact_acl_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND
   jsonb_build_array(to_jsonb(NEW)->'owner_user_id',to_jsonb(NEW)->'org_id',to_jsonb(NEW)->'visibility',
    to_jsonb(NEW)->'status',to_jsonb(NEW)->'repo_status',to_jsonb(NEW)->'acl',to_jsonb(NEW)->'access_policy')
   IS NOT DISTINCT FROM
   jsonb_build_array(to_jsonb(OLD)->'owner_user_id',to_jsonb(OLD)->'org_id',to_jsonb(OLD)->'visibility',
    to_jsonb(OLD)->'status',to_jsonb(OLD)->'repo_status',to_jsonb(OLD)->'acl',to_jsonb(OLD)->'access_policy')
 THEN RETURN NULL;END IF;
 IF TG_OP='DELETE' THEN
  INSERT INTO ds_artifact_acl_generation(repo_id,version,deleted) VALUES(OLD.repo_id,1,true)
   ON CONFLICT(repo_id) DO UPDATE SET version=ds_artifact_acl_generation.version+1,deleted=true;
 ELSE
  INSERT INTO ds_artifact_acl_generation(repo_id,version,deleted) VALUES(NEW.repo_id,1,false)
   ON CONFLICT(repo_id) DO UPDATE SET version=ds_artifact_acl_generation.version+1,deleted=false;
 END IF;
 RETURN NULL;
END
$$;
CREATE TRIGGER advance_artifact_acl_generation AFTER INSERT OR UPDATE OR DELETE ON artifact_repo
 FOR EACH ROW EXECUTE FUNCTION advance_artifact_acl_generation();
CREATE TRIGGER lock_artifact_repo_authority BEFORE UPDATE OR DELETE ON artifact_repo
 FOR EACH STATEMENT EXECUTE FUNCTION lock_authority_mutation();
CREATE FUNCTION guard_artifact_acl_generation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  RAISE EXCEPTION 'artifact ACL generations cannot be removed' USING ERRCODE='23514';
 END IF;
 IF NEW.repo_id IS DISTINCT FROM OLD.repo_id OR NEW.version<=OLD.version THEN
  RAISE EXCEPTION 'artifact ACL generations cannot be reset' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END
$$;
CREATE TRIGGER guard_artifact_acl_generation BEFORE UPDATE OR DELETE ON ds_artifact_acl_generation
 FOR EACH ROW EXECUTE FUNCTION guard_artifact_acl_generation();
