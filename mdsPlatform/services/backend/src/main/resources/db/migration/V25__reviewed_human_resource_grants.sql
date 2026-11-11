-- Human resource policy is explicit and reviewed. Old grants/contracts are retained.
INSERT INTO permission(per_id,per_code,per_name,category,is_builtin,created_by)
 SELECT gen_random_uuid(),code,label,'operation',true,(SELECT user_id FROM fwd_user ORDER BY created_at,user_id LIMIT 1)
 FROM (VALUES('identity.resource.manage','Manage explicit human resource grants'),('dataset.share.manage','Review explicit own-enterprise resource sharing')) AS op(code,label)
 ON CONFLICT(per_code) DO NOTHING;
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation,permission_id)
 SELECT p.per_code,CASE WHEN p.per_code='identity.resource.manage' THEN 'platform' ELSE 'supply' END,p.per_name,true,p.per_id
 FROM permission p WHERE p.per_code IN('identity.resource.manage','dataset.share.manage');
CREATE TABLE ds_human_grant_review(
 review_id uuid PRIMARY KEY,
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 parent_session_id uuid NOT NULL REFERENCES ds_auth_session(session_id),
 actor_token_version integer NOT NULL,
 action varchar(16) NOT NULL CHECK(action IN('CREATE','REVOKE')),
 grant_id uuid NOT NULL,
 payload_sha256 char(64) NOT NULL CHECK(payload_sha256~'^[0-9a-f]{64}$'),
 payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 applied_at timestamptz,
 CHECK(expires_at>created_at)
);
CREATE FUNCTION guard_human_grant_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' OR (to_jsonb(NEW)-'applied_at') IS DISTINCT FROM (to_jsonb(OLD)-'applied_at')
  OR OLD.applied_at IS NOT NULL OR NEW.applied_at IS NULL THEN
  RAISE EXCEPTION 'HUMAN_GRANT_REVIEW_IMMUTABLE' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER human_grant_review_guard BEFORE UPDATE OR DELETE ON ds_human_grant_review
 FOR EACH ROW EXECUTE FUNCTION guard_human_grant_review();
CREATE TABLE ds_human_grant_origin(
 grant_id uuid PRIMARY KEY REFERENCES ds_resource_grant(grant_id) ON DELETE RESTRICT,
 review_id uuid NOT NULL UNIQUE REFERENCES ds_human_grant_review(review_id),
 source_authority jsonb NOT NULL,
 confirmed_by uuid NOT NULL REFERENCES fwd_user(user_id),
 confirmed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER human_grant_origin_immutable BEFORE UPDATE OR DELETE ON ds_human_grant_origin
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
CREATE FUNCTION guard_reviewed_human_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_human_grant_origin WHERE grant_id=OLD.grant_id) THEN
  IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'REVIEWED_HUMAN_GRANT_IMMUTABLE' USING ERRCODE='42501';
  END IF;
  IF (to_jsonb(NEW)-'state'-'version'-'revoked_at'-'revoke_reason') IS DISTINCT FROM
     (to_jsonb(OLD)-'state'-'version'-'revoked_at'-'revoke_reason')
   OR OLD.state<>'ACTIVE' OR NEW.state<>'REVOKED' OR NEW.version<>OLD.version+1
   OR NEW.revoked_at IS NULL OR NULLIF(btrim(NEW.revoke_reason),'') IS NULL THEN
   RAISE EXCEPTION 'REVIEWED_HUMAN_GRANT_IMMUTABLE' USING ERRCODE='42501';
  END IF;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER reviewed_human_grant_guard BEFORE UPDATE OR DELETE ON ds_resource_grant
 FOR EACH ROW EXECUTE FUNCTION guard_reviewed_human_grant();
CREATE TABLE ds_human_grant_event(
 event_id uuid PRIMARY KEY,
 review_id uuid NOT NULL UNIQUE REFERENCES ds_human_grant_review(review_id),
 grant_id uuid NOT NULL REFERENCES ds_resource_grant(grant_id),
 actor_user_id uuid NOT NULL REFERENCES fwd_user(user_id),
 actor_org_id uuid NOT NULL REFERENCES org(org_id),
 action varchar(16) NOT NULL CHECK(action IN('CREATE','REVOKE')),
 reason_code varchar(80) NOT NULL,
 payload jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER human_grant_event_immutable BEFORE UPDATE OR DELETE ON ds_human_grant_event
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
