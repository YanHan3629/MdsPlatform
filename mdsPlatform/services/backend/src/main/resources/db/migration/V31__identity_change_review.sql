-- Reviewed identity changes are immutable records, bound to one actor and snapshot.
CREATE TABLE ds_identity_change_review(
 review_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 actor_token_version integer NOT NULL,
 actor_permission_version bigint NOT NULL,
 policy_version bigint NOT NULL,
 action varchar(32) NOT NULL CHECK(action IN('TYPE_UPDATE','TYPE_DELETE','USER_TYPES','QUALIFICATION','ACCOUNT_UPDATE')),
 target_id uuid NOT NULL,
 request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 snapshot_sha256 char(64) NOT NULL CHECK(snapshot_sha256 ~ '^[0-9a-f]{64}$'),
 review_sha256 char(64) NOT NULL CHECK(review_sha256 ~ '^[0-9a-f]{64}$'),
 request jsonb NOT NULL,
 impact jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '10 minutes',
 state varchar(16) NOT NULL DEFAULT 'REVIEWED' CHECK(state IN('REVIEWED','APPLIED','CANCELLED')),
 closed_at timestamptz,
 CHECK(expires_at>created_at),
 CHECK((state='REVIEWED')=(closed_at IS NULL))
);
CREATE INDEX identity_change_review_owner ON ds_identity_change_review(actor_user_id,created_at);
CREATE FUNCTION guard_identity_change_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  RAISE EXCEPTION 'identity reviews are retained' USING ERRCODE='42501';
 END IF;
 IF OLD.state<>'REVIEWED' OR NEW.state NOT IN('APPLIED','CANCELLED') OR NEW.closed_at IS NULL
    OR (to_jsonb(NEW)-'state'-'closed_at') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'closed_at') THEN
  RAISE EXCEPTION 'identity reviews are immutable' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_identity_change_review BEFORE UPDATE OR DELETE ON ds_identity_change_review
 FOR EACH ROW EXECUTE FUNCTION guard_identity_change_review();
