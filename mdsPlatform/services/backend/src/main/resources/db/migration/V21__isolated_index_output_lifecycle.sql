-- No historical snapshot or binding is rewritten by this migration.
-- Fresh output bindings are unique; historical aliases may still be inspected.
CREATE FUNCTION guard_index_output_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' THEN
  IF ROW(NEW.dataset_id,NEW.dataset_version_id,NEW.index_repo_id,NEW.index_commit_id)
   IS DISTINCT FROM ROW(OLD.dataset_id,OLD.dataset_version_id,OLD.index_repo_id,OLD.index_commit_id) THEN
   RAISE EXCEPTION 'index frozen output binding is immutable' USING ERRCODE='23514';
  END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM mm_index_version WHERE index_repo_id=NEW.index_repo_id AND index_commit_id=NEW.index_commit_id) THEN
   RAISE EXCEPTION 'index output snapshot is already assigned' USING ERRCODE='23505';
  END IF;
 END IF;
 RETURN NEW;
END
$$;
CREATE TRIGGER guard_index_output_binding BEFORE INSERT OR UPDATE ON mm_index_version
 FOR EACH ROW EXECUTE FUNCTION guard_index_output_binding();

-- System state transitions have their own append-only provenance. The job's
-- creator is not presented as the actor for a failure detected by the system.
CREATE TABLE ds_index_execution_lifecycle(
 event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 index_version_id uuid NOT NULL REFERENCES mm_index_version(index_version_id),
 run_id uuid NOT NULL REFERENCES job_run(run_id),
 source_kind varchar(24) NOT NULL DEFAULT 'JOB_RUN_STATE' CHECK(source_kind='JOB_RUN_STATE'),
 run_status varchar(16) NOT NULL CHECK(run_status IN('FAILED','CANCELING','CANCELED')),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(index_version_id,run_id)
);
CREATE TRIGGER immutable_index_execution_lifecycle BEFORE UPDATE OR DELETE ON ds_index_execution_lifecycle
 FOR EACH ROW EXECUTE FUNCTION reject_index_execution_rewrite();

CREATE FUNCTION settle_failed_index_execution(target_run uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE failed record; current_status text;
BEGIN
 PERFORM pg_advisory_xact_lock(805014630400001::bigint);
 SELECT run_status::text INTO current_status FROM job_run WHERE run_id=target_run;
 IF current_status IS NULL OR current_status NOT IN('FAILED','CANCELING','CANCELED') THEN RETURN; END IF;
 FOR failed IN
  UPDATE mm_index_version i SET index_status='FAILED',error_message='INDEX_EXECUTION_'||current_status
  FROM job_run j WHERE j.run_id=target_run AND j.run_id=i.build_run_id AND j.job_id=i.build_job_id
    AND i.index_status IN('QUEUED','BUILDING','PREWARMING')
  RETURNING i.index_version_id,i.dataset_version_id
 LOOP
  UPDATE mm_index_registration SET registration_status='FAILED'
    WHERE index_version_id=failed.index_version_id AND registration_status='PREWARMING';
  UPDATE mm_dataset_version v SET version_status=CASE WHEN EXISTS(
      SELECT 1 FROM mm_index_version active WHERE active.index_version_id=v.active_index_version_id AND active.index_status='READY')
     THEN 'READY' ELSE 'FAILED' END
   WHERE v.version_id=failed.dataset_version_id AND v.version_status='INDEXING'
    AND NOT EXISTS(SELECT 1 FROM mm_index_version other WHERE other.dataset_version_id=v.version_id
      AND other.index_status IN('QUEUED','BUILDING','PREWARMING'));
  INSERT INTO ds_index_execution_lifecycle(index_version_id,run_id,run_status)
   VALUES(failed.index_version_id,target_run,current_status) ON CONFLICT(index_version_id,run_id) DO NOTHING;
 END LOOP;
END
$$;
CREATE FUNCTION settle_index_after_run_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.run_status IS DISTINCT FROM OLD.run_status AND NEW.run_status::text IN('FAILED','CANCELING','CANCELED') THEN
  PERFORM settle_failed_index_execution(NEW.run_id);
 END IF;
 RETURN NULL;
END
$$;
CREATE TRIGGER settle_index_after_run_state AFTER UPDATE OF run_status ON job_run
 FOR EACH ROW EXECUTE FUNCTION settle_index_after_run_state();
