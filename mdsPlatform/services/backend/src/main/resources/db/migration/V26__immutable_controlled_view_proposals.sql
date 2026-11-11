-- Provider proposals are immutable inputs, not approvals, view registrations or grants.
CREATE TABLE ds_controlled_view_proposal(
 proposal_id uuid PRIMARY KEY,
 owner_org_id uuid NOT NULL REFERENCES org(org_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 actor_token_version integer NOT NULL,
 source_grant_id uuid NOT NULL REFERENCES ds_resource_grant(grant_id),
 source_grant_version bigint NOT NULL CHECK(source_grant_version>0),
 source_dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id),
 source_version_id uuid NOT NULL REFERENCES mm_dataset_version(version_id),
 target_org_id uuid NOT NULL REFERENCES org(org_id),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 payload_sha256 char(64) NOT NULL CHECK(payload_sha256~'^[0-9a-f]{64}$'),
 state varchar(16) NOT NULL DEFAULT 'PROPOSED' CHECK(state IN('PROPOSED','WITHDRAWN')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 withdrawn_at timestamptz,
 CHECK(expires_at>created_at),
 CHECK((state='PROPOSED' AND version=1 AND withdrawn_at IS NULL) OR (state='WITHDRAWN' AND version=2 AND withdrawn_at IS NOT NULL))
);
CREATE INDEX controlled_view_proposal_actor ON ds_controlled_view_proposal(actor_user_id,parent_session_id,created_at DESC);
CREATE FUNCTION guard_controlled_view_proposal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  RAISE EXCEPTION 'CONTROLLED_VIEW_PROPOSAL_IMMUTABLE' USING ERRCODE='42501';
 END IF;
 IF (to_jsonb(NEW)-'state'-'version'-'withdrawn_at') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'version'-'withdrawn_at')
  OR OLD.state<>'PROPOSED' OR NEW.state<>'WITHDRAWN' OR NEW.version<>OLD.version+1 OR NEW.withdrawn_at IS NULL THEN
  RAISE EXCEPTION 'CONTROLLED_VIEW_PROPOSAL_IMMUTABLE' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER controlled_view_proposal_guard BEFORE UPDATE OR DELETE ON ds_controlled_view_proposal
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_proposal();
CREATE TABLE ds_controlled_view_proposal_event(
 event_id uuid PRIMARY KEY,
 proposal_id uuid NOT NULL REFERENCES ds_controlled_view_proposal(proposal_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 action varchar(16) NOT NULL CHECK(action IN('PROPOSE','WITHDRAW')),
 reason text NOT NULL CHECK(length(reason) BETWEEN 6 AND 500),
 proposal_version bigint NOT NULL CHECK(proposal_version IN(1,2)),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(proposal_id,proposal_version)
);
CREATE TRIGGER controlled_view_proposal_event_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_proposal_event
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
