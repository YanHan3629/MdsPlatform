-- Run cancellation is authoritative at the same boundary as grant/session revocation.
-- Heartbeat-only updates do not acquire this fence.
CREATE TRIGGER lock_job_run_state_authority
 BEFORE UPDATE OF run_status,job_id,created_by,org_id ON public.job_run
 FOR EACH STATEMENT EXECUTE FUNCTION public.lock_authority_mutation();
CREATE TRIGGER lock_job_run_delete_authority
 BEFORE DELETE ON public.job_run
 FOR EACH STATEMENT EXECUTE FUNCTION public.lock_authority_mutation();
