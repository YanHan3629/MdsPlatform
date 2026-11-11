-- Fixed controlled-view preview attempts are private candidates, never READY grants.
CREATE TABLE ds_controlled_view_preview_attempt(
 attempt_id uuid PRIMARY KEY,
 proposal_id uuid NOT NULL REFERENCES ds_controlled_view_proposal(proposal_id),
 owner_org_id uuid NOT NULL REFERENCES org(org_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 actor_token_version integer NOT NULL,
 job_id uuid NOT NULL UNIQUE REFERENCES job(job_id),
 input_repo_id uuid NOT NULL REFERENCES artifact_repo(repo_id),
 input_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id),
 output_repo_id uuid NOT NULL UNIQUE REFERENCES artifact_repo(repo_id),
 output_commit_id uuid NOT NULL UNIQUE REFERENCES artifact_commit(commit_id),
 proposal_sha256 char(64) NOT NULL CHECK(proposal_sha256~'^[0-9a-f]{64}$'),
 script_sha256 char(64) NOT NULL CHECK(script_sha256~'^[0-9a-f]{64}$'),
 parameters_sha256 char(64) NOT NULL CHECK(parameters_sha256~'^[0-9a-f]{64}$'),
 image text NOT NULL,
 image_sha256 char(64) NOT NULL CHECK(image_sha256~'^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(input_repo_id<>output_repo_id AND input_commit_id<>output_commit_id)
);
CREATE TRIGGER controlled_view_preview_attempt_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_preview_attempt
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE TABLE ds_controlled_view_preview_origin(
 run_id uuid PRIMARY KEY REFERENCES job_run(run_id),
 attempt_id uuid NOT NULL UNIQUE REFERENCES ds_controlled_view_preview_attempt(attempt_id),
 job_id uuid NOT NULL REFERENCES job(job_id),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER controlled_view_preview_origin_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_preview_origin
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE TABLE ds_controlled_view_preview_state(
 attempt_id uuid PRIMARY KEY REFERENCES ds_controlled_view_preview_attempt(attempt_id),
 state varchar(32) NOT NULL DEFAULT 'BUILDING' CHECK(state IN('BUILDING','AWAITING_REVIEW','FAILED','CANCELED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version IN(1,2)),
 reason_code varchar(100) NOT NULL DEFAULT 'PREVIEW_MATERIALIZATION_REQUESTED',
 verified_manifest_object_key text,
 verified_manifest_sha256 char(64) CHECK(verified_manifest_sha256~'^[0-9a-f]{64}$'),
 verification_summary jsonb,
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK((state='BUILDING' AND version=1) OR (state<>'BUILDING' AND version=2)),
 CHECK((state='AWAITING_REVIEW' AND verified_manifest_object_key IS NOT NULL AND verified_manifest_sha256 IS NOT NULL AND verification_summary IS NOT NULL)
  OR (state<>'AWAITING_REVIEW' AND verified_manifest_object_key IS NULL AND verified_manifest_sha256 IS NULL AND verification_summary IS NULL))
);
CREATE FUNCTION guard_controlled_view_preview_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'PREVIEW_STATE_HISTORY_REQUIRED' USING ERRCODE='42501'; END IF;
 IF NEW.attempt_id<>OLD.attempt_id OR OLD.state<>'BUILDING' OR NEW.state='BUILDING' OR NEW.version<>OLD.version+1 THEN
  RAISE EXCEPTION 'PREVIEW_STATE_CANNOT_REOPEN' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_preview_state_guard BEFORE UPDATE OR DELETE ON ds_controlled_view_preview_state
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_preview_state();
CREATE TABLE ds_controlled_view_preview_receipt(
 receipt_id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL UNIQUE REFERENCES ds_controlled_view_preview_attempt(attempt_id),
 run_id uuid NOT NULL UNIQUE REFERENCES ds_controlled_view_preview_origin(run_id),
 object_key text NOT NULL UNIQUE,
 sha256 char(64) NOT NULL CHECK(sha256~'^[0-9a-f]{64}$'),
 size_bytes bigint NOT NULL CHECK(size_bytes>0 AND size_bytes<=50331648),
 candidate_receipt_sha256 char(64) NOT NULL CHECK(candidate_receipt_sha256~'^[0-9a-f]{64}$'),
 public_manifest jsonb NOT NULL CHECK(jsonb_typeof(public_manifest)='array'),
 has_original_byte_copies boolean NOT NULL,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER controlled_view_preview_receipt_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_preview_receipt
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE TABLE ds_controlled_view_preview_event(
 event_id uuid PRIMARY KEY,
 attempt_id uuid NOT NULL REFERENCES ds_controlled_view_preview_attempt(attempt_id),
 event_kind varchar(32) NOT NULL CHECK(event_kind IN('REQUESTED','RECEIPT','VERIFIED','FAILED','CANCELED')),
 metadata jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER controlled_view_preview_event_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_preview_event
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE FUNCTION guard_controlled_view_preview_repository() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_preview_attempt WHERE output_repo_id=OLD.repo_id) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'MANAGED_PREVIEW_REPOSITORY_IMMUTABLE' USING ERRCODE='42501'; END IF;
  IF (to_jsonb(NEW)-'updated_at') IS DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN
   RAISE EXCEPTION 'MANAGED_PREVIEW_REPOSITORY_IMMUTABLE' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_preview_repository_guard BEFORE UPDATE OR DELETE ON artifact_repo
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_preview_repository();
CREATE FUNCTION guard_controlled_view_preview_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_preview_attempt WHERE output_commit_id=OLD.commit_id) THEN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'MANAGED_PREVIEW_COMMIT_IMMUTABLE' USING ERRCODE='42501'; END IF;
  IF (to_jsonb(NEW)-'manifest_object_key'-'manifest_sha256') IS DISTINCT FROM (to_jsonb(OLD)-'manifest_object_key'-'manifest_sha256')
    OR NOT EXISTS(
     SELECT 1 FROM ds_controlled_view_preview_attempt a
     JOIN ds_controlled_view_preview_state s ON s.attempt_id=a.attempt_id AND s.state='BUILDING'
     JOIN ds_controlled_view_preview_origin o ON o.attempt_id=a.attempt_id
     JOIN job_run r ON r.run_id=o.run_id AND r.job_id=o.job_id AND r.run_status IN('QUEUED','RUNNING')
     WHERE a.output_commit_id=OLD.commit_id AND a.output_repo_id=OLD.repo_id) THEN
   RAISE EXCEPTION 'MANAGED_PREVIEW_COMMIT_PHASE_REQUIRED' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_preview_commit_guard BEFORE UPDATE OR DELETE ON artifact_commit
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_preview_commit();
