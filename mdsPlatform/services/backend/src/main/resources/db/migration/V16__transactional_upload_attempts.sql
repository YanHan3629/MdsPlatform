SET search_path TO public;
-- An immutable manifest revision is selected in the same transaction as file metadata.
ALTER TABLE public.artifact_commit ADD COLUMN manifest_object_key TEXT;
ALTER TABLE public.artifact_commit ADD COLUMN manifest_sha256 CHAR(64);
ALTER TABLE public.artifact_commit ADD CONSTRAINT artifact_manifest_identity_complete CHECK (
  (manifest_object_key IS NULL AND manifest_sha256 IS NULL) OR
  (manifest_object_key LIKE 'manifest/artifacts/' || repo_id::text || '/commits/' || commit_id::text || '/revisions/%'
    AND manifest_sha256 ~ '^[a-f0-9]{64}$'));

CREATE TABLE public.artifact_upload_attempt (
 attempt_id UUID PRIMARY KEY,
 repo_id UUID NOT NULL,
 commit_id UUID NOT NULL,
 actor_user_id UUID NOT NULL,
 actor_org_id UUID NOT NULL,
 logical_path TEXT NOT NULL,
 object_key TEXT NOT NULL UNIQUE,
 operation_code VARCHAR(96) NOT NULL,
 purpose_code VARCHAR(32) NOT NULL,
 expected_size BIGINT NOT NULL CHECK(expected_size>=-1),
 reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK(reserved_bytes>=0),
 quota_released BOOLEAN NOT NULL DEFAULT false,
 state VARCHAR(24) NOT NULL DEFAULT 'PREPARED'
   CHECK(state IN('PREPARED','COMMITTED','ABORTED','CLEANUP_REQUIRED')),
 final_file_id UUID,
 failure_code VARCHAR(96),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 expires_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 cleanup_completed_at TIMESTAMPTZ,
 CHECK(expires_at>created_at),
 CHECK(object_key LIKE 'workspace/'||repo_id::text||'/'||commit_id::text||'/attempts/'||attempt_id::text||'/%'),
 CHECK(state<>'COMMITTED' OR final_file_id IS NOT NULL)
);
CREATE UNIQUE INDEX one_prepared_upload_per_path ON public.artifact_upload_attempt(commit_id,logical_path) WHERE state='PREPARED';
CREATE INDEX artifact_upload_cleanup_due ON public.artifact_upload_attempt(expires_at) WHERE state IN('PREPARED','ABORTED','CLEANUP_REQUIRED');

CREATE OR REPLACE FUNCTION public.guard_upload_attempt_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.attempt_id,NEW.repo_id,NEW.commit_id,NEW.actor_user_id,NEW.actor_org_id,NEW.logical_path,NEW.object_key,
        NEW.operation_code,NEW.purpose_code,NEW.expected_size,NEW.reserved_bytes,NEW.created_at,NEW.expires_at)
    IS DISTINCT FROM
    ROW(OLD.attempt_id,OLD.repo_id,OLD.commit_id,OLD.actor_user_id,OLD.actor_org_id,OLD.logical_path,OLD.object_key,
        OLD.operation_code,OLD.purpose_code,OLD.expected_size,OLD.reserved_bytes,OLD.created_at,OLD.expires_at) THEN
  RAISE EXCEPTION 'upload attempt identity is immutable';
 END IF;
 IF OLD.state='COMMITTED' AND NEW IS DISTINCT FROM OLD THEN
  RAISE EXCEPTION 'committed upload receipt is immutable';
 END IF;
 IF OLD.state<>'PREPARED' AND NEW.state='PREPARED' THEN
  RAISE EXCEPTION 'an ended upload attempt cannot be resumed';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER guard_upload_attempt_identity BEFORE UPDATE ON public.artifact_upload_attempt
 FOR EACH ROW EXECUTE FUNCTION public.guard_upload_attempt_identity();

-- Final file publication takes this shared transaction lock. Authority mutations
-- take the exclusive side before changing authoritative rows, so a revocation
-- commits entirely before or entirely after the final protected metadata switch.
CREATE OR REPLACE FUNCTION public.lock_authority_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(805014630400001::bigint);
 RETURN NULL;
END;
$$;
DO $$
DECLARE target TEXT;
BEGIN
 FOREACH target IN ARRAY ARRAY['fwd_user','org','role','user_role','role_permission','ds_operation_deny',
  'ds_resource_grant','ds_contract','ds_contract_operation','ds_controlled_view','ds_enterprise_identity',
  'ds_management_membership','ds_auth_session','ds_auth_initialization','mm_dataset','mm_dataset_version','mm_index_version'] LOOP
  EXECUTE format('CREATE TRIGGER lock_authority_mutation BEFORE INSERT OR UPDATE OR DELETE ON public.%I FOR EACH STATEMENT EXECUTE FUNCTION public.lock_authority_mutation()',target);
 END LOOP;
END;
$$;
CREATE TRIGGER lock_artifact_scope_mutation BEFORE UPDATE OF commit_status,repo_id ON public.artifact_commit
 FOR EACH STATEMENT EXECUTE FUNCTION public.lock_authority_mutation();

CREATE OR REPLACE FUNCTION public.guard_artifact_publication() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.commit_status='PUBLISHED' AND (
    NEW.commit_status IS DISTINCT FROM OLD.commit_status OR NEW.repo_id IS DISTINCT FROM OLD.repo_id OR
    NEW.manifest_object_key IS DISTINCT FROM OLD.manifest_object_key OR NEW.manifest_sha256 IS DISTINCT FROM OLD.manifest_sha256) THEN
  RAISE EXCEPTION 'published artifact snapshot is immutable';
 END IF;
 IF OLD.commit_status='DRAFT' AND NEW.commit_status='PUBLISHED' THEN
  IF EXISTS(SELECT 1 FROM public.artifact_upload_attempt WHERE commit_id=NEW.commit_id AND state='PREPARED')
    OR EXISTS(SELECT 1 FROM public.artifact_file WHERE commit_id=NEW.commit_id AND (upload_state IS DISTINCT FROM 'READY' OR sha256 IS NULL OR sha256 !~ '^[a-fA-F0-9]{64}$')) THEN
   RAISE EXCEPTION 'unfinished uploads prevent publication';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER guard_artifact_publication BEFORE UPDATE ON public.artifact_commit
 FOR EACH ROW EXECUTE FUNCTION public.guard_artifact_publication();
