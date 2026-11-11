-- Preserve every historical row; only constrain future state changes.
CREATE FUNCTION guard_terminal_job_execution() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.run_status::text IN('SUCCEEDED','FAILED','CANCELED') AND NEW.run_status IS DISTINCT FROM OLD.run_status THEN
  RAISE EXCEPTION 'terminal job execution state is immutable' USING ERRCODE='23514';
 END IF;
 IF OLD.run_status::text='CANCELING' AND NEW.run_status::text NOT IN('CANCELING','CANCELED','FAILED') THEN
  RAISE EXCEPTION 'canceling job execution cannot resume' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END
$$;
CREATE TRIGGER guard_terminal_job_execution BEFORE UPDATE OF run_status ON job_run
 FOR EACH ROW EXECUTE FUNCTION guard_terminal_job_execution();

-- A zero process exit is not a successful index publication by itself.
ALTER TABLE ds_index_execution_lifecycle DROP CONSTRAINT ds_index_execution_lifecycle_run_status_check;
ALTER TABLE ds_index_execution_lifecycle ADD CONSTRAINT ds_index_execution_lifecycle_run_status_check
 CHECK(run_status IN('FAILED','CANCELING','CANCELED','SUCCEEDED'));
CREATE OR REPLACE FUNCTION settle_failed_index_execution(target_run uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE failed record; current_status text;
BEGIN
 PERFORM pg_advisory_xact_lock(805014630400001::bigint);
 SELECT run_status::text INTO current_status FROM job_run WHERE run_id=target_run;
 IF current_status IS NULL OR current_status NOT IN('FAILED','CANCELING','CANCELED','SUCCEEDED') THEN RETURN; END IF;
 IF current_status='SUCCEEDED' AND EXISTS(SELECT 1 FROM ds_index_callback_receipt WHERE run_id=target_run AND callback_kind='READY') THEN RETURN; END IF;
 FOR failed IN
  UPDATE mm_index_version i SET index_status='FAILED',error_message=CASE WHEN current_status='SUCCEEDED' THEN 'INDEX_EXECUTION_ENDED_WITHOUT_READY_RECEIPT' ELSE 'INDEX_EXECUTION_'||current_status END
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
CREATE OR REPLACE FUNCTION settle_index_after_run_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.run_status IS DISTINCT FROM OLD.run_status AND NEW.run_status::text IN('FAILED','CANCELING','CANCELED','SUCCEEDED') THEN
  PERFORM settle_failed_index_execution(NEW.run_id);
 END IF;
 RETURN NULL;
END
$$;
