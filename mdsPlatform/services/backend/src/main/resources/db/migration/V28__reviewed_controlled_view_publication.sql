-- A reviewed private preview may create one distinct immutable derived snapshot.
-- Existing READY-only consumer and institutional grants are not weakened.
CREATE TABLE ds_controlled_view_publication_review(
 review_id uuid PRIMARY KEY,
 publication_id uuid NOT NULL UNIQUE,
 attempt_id uuid NOT NULL REFERENCES ds_controlled_view_preview_attempt(attempt_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 actor_token_version integer NOT NULL,
 payload jsonb NOT NULL,
 payload_sha256 char(64) NOT NULL CHECK(payload_sha256~'^[0-9a-f]{64}$'),
 expires_at timestamptz NOT NULL,
 applied_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION guard_controlled_view_publication_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR OLD.applied_at IS NOT NULL OR NEW.applied_at IS NULL
    OR (to_jsonb(NEW)-'applied_at') IS DISTINCT FROM (to_jsonb(OLD)-'applied_at') THEN
  RAISE EXCEPTION 'CONTROLLED_VIEW_REVIEW_IMMUTABLE' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_review_guard BEFORE UPDATE OR DELETE ON ds_controlled_view_publication_review
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_review();

CREATE TABLE ds_controlled_view_publication(
 publication_id uuid PRIMARY KEY,
 review_id uuid NOT NULL UNIQUE REFERENCES ds_controlled_view_publication_review(review_id),
 attempt_id uuid NOT NULL UNIQUE REFERENCES ds_controlled_view_preview_attempt(attempt_id),
 view_id uuid NOT NULL UNIQUE REFERENCES ds_controlled_view(view_id),
 dataset_id uuid NOT NULL UNIQUE REFERENCES mm_dataset(dataset_id),
 version_id uuid NOT NULL UNIQUE REFERENCES mm_dataset_version(version_id),
 raw_repo_id uuid NOT NULL UNIQUE REFERENCES artifact_repo(repo_id),
 raw_commit_id uuid NOT NULL UNIQUE REFERENCES artifact_commit(commit_id),
 index_repo_id uuid NOT NULL UNIQUE REFERENCES artifact_repo(repo_id),
 raw_manifest_object_key text NOT NULL UNIQUE,
 raw_manifest_sha256 char(64) NOT NULL CHECK(raw_manifest_sha256~'^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER controlled_view_publication_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_publication
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE TABLE ds_controlled_view_publication_state(
 publication_id uuid PRIMARY KEY REFERENCES ds_controlled_view_publication(publication_id),
 state varchar(24) NOT NULL DEFAULT 'RAW_PUBLISHED' CHECK(state IN('RAW_PUBLISHED','INDEXING','READY','FAILED','CANCELED','REVOKED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 reason_code varchar(100) NOT NULL DEFAULT 'APPROVED_DERIVED_RAW_SNAPSHOT_FROZEN',
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION guard_controlled_view_publication_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR NEW.publication_id<>OLD.publication_id OR NEW.version<>OLD.version+1
  OR NOT ((OLD.state='RAW_PUBLISHED' AND NEW.state IN('INDEXING','FAILED','CANCELED'))
       OR (OLD.state='INDEXING' AND NEW.state IN('READY','FAILED','CANCELED'))
       OR (OLD.state='READY' AND NEW.state='REVOKED')) THEN
  RAISE EXCEPTION 'CONTROLLED_VIEW_PUBLICATION_CANNOT_REOPEN' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_state_guard BEFORE UPDATE OR DELETE ON ds_controlled_view_publication_state
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_state();
CREATE TABLE ds_controlled_view_publication_index(
 publication_id uuid PRIMARY KEY REFERENCES ds_controlled_view_publication(publication_id),
 index_version_id uuid NOT NULL UNIQUE REFERENCES mm_index_version(index_version_id),
 index_commit_id uuid NOT NULL UNIQUE REFERENCES artifact_commit(commit_id),
 job_id uuid NOT NULL UNIQUE REFERENCES job(job_id),
 run_id uuid NOT NULL UNIQUE REFERENCES job_run(run_id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER controlled_view_publication_index_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_publication_index
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE TABLE ds_controlled_view_publication_grant(
 grant_id uuid PRIMARY KEY REFERENCES ds_resource_grant(grant_id),
 publication_id uuid NOT NULL REFERENCES ds_controlled_view_publication(publication_id),
 grant_kind varchar(16) NOT NULL CHECK(grant_kind IN('PROVIDER','CONSUMER')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(publication_id,grant_kind)
);
CREATE TRIGGER controlled_view_publication_grant_origin_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_publication_grant
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE TABLE ds_controlled_view_publication_event(
 event_id uuid PRIMARY KEY,
 publication_id uuid NOT NULL,
 review_id uuid REFERENCES ds_controlled_view_publication_review(review_id),
 actor_user_id uuid REFERENCES fwd_user(user_id),
 event_kind varchar(32) NOT NULL CHECK(event_kind IN('REVIEWED','RAW_PUBLISHED','INDEXING','READY','FAILED','CANCELED','REVOKED')),
 metadata jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER controlled_view_publication_event_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_publication_event
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();

-- New managed views bind the reviewed target identity generation. Legacy views
-- keep their existing explicit policy; a company remapping cannot revive old views.
CREATE FUNCTION controlled_view_publication_policy_current(target_dataset uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT NOT EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE dataset_id=target_dataset)
 OR EXISTS(
  SELECT 1 FROM ds_controlled_view_publication p
  JOIN ds_controlled_view_publication_review r ON r.review_id=p.review_id
  JOIN org o ON o.org_id=(r.payload#>>'{target,orgId}')::uuid AND o.org_status=1
  JOIN ds_enterprise_identity e ON e.org_id=o.org_id AND e.state='CONFIRMED'
  WHERE p.dataset_id=target_dataset
   AND o.permission_version=(r.payload#>>'{target,orgVersion}')::bigint
   AND e.entity_id=(r.payload#>>'{target,enterpriseId}')
   AND e.version=(r.payload#>>'{target,mappingVersion}')::bigint
   AND e.mapping_id=(r.payload#>>'{target,mappingId}')::uuid
   AND e.catalog_revision_id=(r.payload#>>'{target,catalogRevisionId}')::uuid)
$$;

CREATE FUNCTION guard_controlled_view_publication_repository() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE raw_repo_id=OLD.repo_id OR index_repo_id=OLD.repo_id) THEN
  IF TG_OP='DELETE' OR (to_jsonb(NEW)-'updated_at') IS DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN
   RAISE EXCEPTION 'CONTROLLED_VIEW_REPOSITORY_IMMUTABLE' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_repository_guard BEFORE UPDATE OR DELETE ON artifact_repo
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_repository();
CREATE FUNCTION guard_controlled_view_publication_raw_commit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE raw_commit_id=OLD.commit_id) THEN
  RAISE EXCEPTION 'APPROVED_CONTROLLED_VIEW_RAW_IMMUTABLE' USING ERRCODE='42501';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_raw_guard BEFORE UPDATE OR DELETE ON artifact_commit
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_raw_commit();
CREATE FUNCTION guard_controlled_view_publication_dataset() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE dataset_id=OLD.dataset_id) THEN
  IF TG_OP='DELETE' OR (to_jsonb(NEW)-'updated_at') IS DISTINCT FROM (to_jsonb(OLD)-'updated_at') THEN
   RAISE EXCEPTION 'CONTROLLED_VIEW_DATASET_IDENTITY_IMMUTABLE' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_dataset_guard BEFORE UPDATE OR DELETE ON mm_dataset
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_dataset();
CREATE FUNCTION guard_controlled_view_publication_version() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE approved_index uuid;
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE version_id=OLD.version_id) THEN
  IF TG_OP='DELETE' OR (to_jsonb(NEW)-'updated_at'-'version_status'-'sample_count'-'image_count'-'text_count'-'active_index_version_id')
    IS DISTINCT FROM (to_jsonb(OLD)-'updated_at'-'version_status'-'sample_count'-'image_count'-'text_count'-'active_index_version_id') THEN
   RAISE EXCEPTION 'CONTROLLED_VIEW_VERSION_IDENTITY_IMMUTABLE' USING ERRCODE='42501';
  END IF;
  SELECT i.index_version_id INTO approved_index FROM ds_controlled_view_publication p
   JOIN ds_controlled_view_publication_index i ON i.publication_id=p.publication_id WHERE p.version_id=OLD.version_id;
  IF NEW.active_index_version_id IS NOT NULL AND NEW.active_index_version_id IS DISTINCT FROM approved_index THEN
   RAISE EXCEPTION 'CONTROLLED_VIEW_APPROVED_INDEX_REQUIRED' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_version_guard BEFORE UPDATE OR DELETE ON mm_dataset_version
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_version();
CREATE FUNCTION guard_controlled_view_publication_view() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_publication WHERE view_id=OLD.view_id) THEN
  IF TG_OP='DELETE' OR (to_jsonb(NEW)-'state'-'authorization_version'-'index_version_id') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'authorization_version'-'index_version_id')
   OR NOT ((OLD.state='BUILDING' AND NEW.state IN('READY','FAILED') AND NEW.authorization_version=OLD.authorization_version AND NEW.index_version_id IS NOT DISTINCT FROM OLD.index_version_id)
       OR (OLD.state='READY' AND NEW.state='REVOKED' AND NEW.authorization_version=OLD.authorization_version+1 AND NEW.index_version_id IS NOT DISTINCT FROM OLD.index_version_id)
       OR (OLD.state='BUILDING' AND NEW.state='BUILDING' AND OLD.index_version_id IS NULL AND NEW.authorization_version=OLD.authorization_version AND EXISTS(
        SELECT 1 FROM ds_controlled_view_publication p JOIN ds_controlled_view_publication_index b ON b.publication_id=p.publication_id
        WHERE p.view_id=OLD.view_id AND b.index_version_id=NEW.index_version_id))) THEN
   RAISE EXCEPTION 'CONTROLLED_VIEW_APPROVAL_IDENTITY_IMMUTABLE' USING ERRCODE='42501';
  END IF;
  IF NEW.state='READY' AND NOT EXISTS(
   SELECT 1 FROM ds_controlled_view_publication p
   JOIN ds_controlled_view_publication_index b ON b.publication_id=p.publication_id
   JOIN mm_index_version i ON i.index_version_id=b.index_version_id AND i.index_status='READY'
   JOIN job_run j ON j.run_id=b.run_id AND j.job_id=b.job_id AND j.run_status='SUCCEEDED'
   JOIN artifact_commit c ON c.commit_id=b.index_commit_id AND c.commit_status='PUBLISHED'
   WHERE p.view_id=OLD.view_id AND i.dataset_id=p.dataset_id AND i.dataset_version_id=p.version_id
    AND NEW.index_version_id=i.index_version_id AND controlled_view_publication_policy_current(p.dataset_id)) THEN
   RAISE EXCEPTION 'CONTROLLED_VIEW_NATIVE_READY_SUCCESS_REQUIRED' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_view_guard BEFORE UPDATE OR DELETE ON ds_controlled_view
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_view();
CREATE FUNCTION guard_controlled_view_publication_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view_publication_grant WHERE grant_id=OLD.grant_id) THEN
  IF TG_OP='DELETE' OR OLD.state<>'ACTIVE' OR NEW.state<>'REVOKED' OR NEW.version<>OLD.version+1
    OR (to_jsonb(NEW)-'state'-'version'-'revoked_at'-'revoke_reason') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'version'-'revoked_at'-'revoke_reason') THEN
   RAISE EXCEPTION 'CONTROLLED_VIEW_REVIEWED_GRANT_IMMUTABLE' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_publication_grant_guard BEFORE UPDATE OR DELETE ON ds_resource_grant
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_grant();
