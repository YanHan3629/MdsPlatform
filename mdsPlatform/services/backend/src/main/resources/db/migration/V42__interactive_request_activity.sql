-- Metadata only. These records confer no resource or operation authority.
CREATE TABLE ds_interactive_request_activity (
 request_id uuid PRIMARY KEY,
 runtime_id uuid NOT NULL,
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 actor_org_id uuid NOT NULL REFERENCES org(org_id) ON DELETE RESTRICT,
 origin_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id) ON DELETE RESTRICT,
 actor_token_version integer NOT NULL,
 request_kind varchar(24) NOT NULL CHECK(request_kind IN('QA_REQUEST','RENDER_REQUEST')),
 state varchar(16) NOT NULL CHECK(state IN('RUNNING','SUCCEEDED','FAILED','UNCERTAIN')),
 started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 lease_until timestamptz NOT NULL,
 finished_at timestamptz,
 CHECK((state IN('RUNNING','UNCERTAIN') AND finished_at IS NULL) OR
       (state IN('SUCCEEDED','FAILED') AND finished_at IS NOT NULL))
);
CREATE INDEX interactive_request_actor ON ds_interactive_request_activity(actor_user_id,started_at)
 WHERE state IN('RUNNING','UNCERTAIN');
CREATE INDEX interactive_request_runtime ON ds_interactive_request_activity(runtime_id)
 WHERE state='RUNNING';
CREATE FUNCTION ds_interactive_request_origin_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NOT EXISTS(SELECT 1 FROM ds_auth_session s WHERE s.session_id=NEW.origin_session_id
   AND s.user_id=NEW.actor_user_id AND s.org_id=NEW.actor_org_id
   AND s.token_version=NEW.actor_token_version AND s.purpose='ACCESS'
   AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp())
  THEN RAISE EXCEPTION 'INTERACTIVE_REQUEST_ORIGIN_INVALID' USING ERRCODE='23514'; END IF;
 ELSIF (NEW.request_id,NEW.runtime_id,NEW.actor_user_id,NEW.actor_org_id,NEW.origin_session_id,
         NEW.actor_token_version,NEW.request_kind,NEW.started_at)
       IS DISTINCT FROM
       (OLD.request_id,OLD.runtime_id,OLD.actor_user_id,OLD.actor_org_id,OLD.origin_session_id,
         OLD.actor_token_version,OLD.request_kind,OLD.started_at) THEN
  RAISE EXCEPTION 'INTERACTIVE_REQUEST_ORIGIN_IMMUTABLE' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER interactive_request_origin_guard BEFORE INSERT OR UPDATE ON ds_interactive_request_activity
 FOR EACH ROW EXECUTE FUNCTION ds_interactive_request_origin_guard();
