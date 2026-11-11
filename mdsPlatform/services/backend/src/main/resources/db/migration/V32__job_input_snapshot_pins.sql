-- Optional per-input pins. Existing declarations retain launch-time HEAD semantics.
ALTER TABLE job_input ADD COLUMN input_commit_id uuid;
ALTER TABLE job_input ADD CONSTRAINT job_input_snapshot_repo_fk
 FOREIGN KEY(input_repo_id,input_commit_id) REFERENCES artifact_commit(repo_id,commit_id) ON DELETE RESTRICT;
