-- New work pins original authority. Existing reports and queued history are preserved.
CREATE TABLE ds_quality_authority (
 report_id uuid PRIMARY KEY REFERENCES ds_quality_report(report_id) ON DELETE RESTRICT,
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 actor_org_id uuid NOT NULL REFERENCES org(org_id) ON DELETE RESTRICT,
 actor_token_version integer NOT NULL,
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id) ON DELETE RESTRICT,
 source_grant_id uuid NOT NULL REFERENCES ds_resource_grant(grant_id) ON DELETE RESTRICT,
 source_grant_version bigint NOT NULL CHECK(source_grant_version>0),
 service_subject_id uuid NOT NULL REFERENCES ds_service_subject(service_subject_id) ON DELETE RESTRICT,
 service_version bigint NOT NULL CHECK(service_version>0),
 service_grant_id uuid NOT NULL REFERENCES ds_resource_grant(grant_id) ON DELETE RESTRICT,
 service_grant_version bigint NOT NULL CHECK(service_grant_version>0),
 dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id) ON DELETE RESTRICT,
 version_id uuid NOT NULL,
 raw_repo_id uuid NOT NULL REFERENCES artifact_repo(repo_id) ON DELETE RESTRICT,
 raw_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 FOREIGN KEY(version_id,dataset_id) REFERENCES mm_dataset_version(version_id,dataset_id) ON DELETE RESTRICT
);
CREATE INDEX ds_quality_authority_service ON ds_quality_authority(service_subject_id,report_id);
CREATE FUNCTION ds_quality_original_authority_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'QUALITY_ORIGINAL_AUTHORITY_IMMUTABLE' USING ERRCODE='23514'; END $$;
CREATE TRIGGER ds_quality_original_authority_immutable BEFORE UPDATE OR DELETE ON ds_quality_authority
 FOR EACH ROW EXECUTE FUNCTION ds_quality_original_authority_immutable();
CREATE FUNCTION ds_quality_new_job_authority_required() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM ds_quality_authority a JOIN ds_quality_report q ON q.report_id=a.report_id
   JOIN mm_dataset d ON d.dataset_id=a.dataset_id AND d.org_id=a.actor_org_id
   JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id AND v.version_id=a.version_id
   JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
   WHERE a.report_id=NEW.report_id AND q.dataset_id=a.dataset_id AND q.version_id=a.version_id
     AND a.raw_repo_id=d.raw_repo_id AND a.raw_commit_id=v.raw_commit_id)
 THEN RAISE EXCEPTION 'QUALITY_ORIGINAL_AUTHORITY_REQUIRED' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER ds_quality_new_job_authority_required BEFORE INSERT ON ds_quality_job
 FOR EACH ROW EXECUTE FUNCTION ds_quality_new_job_authority_required();
