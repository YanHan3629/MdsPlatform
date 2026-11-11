-- Institutional subjects remain separate from interactive user identities.
ALTER TABLE ds_service_subject ADD COLUMN display_name varchar(160) NOT NULL DEFAULT 'Institutional service subject';
ALTER TABLE ds_service_subject ADD COLUMN valid_until timestamptz;
ALTER TABLE ds_service_subject ADD COLUMN closed_at timestamptz;
ALTER TABLE ds_service_subject ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE ds_service_subject DROP CONSTRAINT ds_service_subject_purpose_check;
ALTER TABLE ds_service_subject ADD CHECK(purpose IN('SCHEDULED_DELIVERY','VIEW_BUILD','QUALITY_CHECK'));
ALTER TABLE ds_service_subject ADD CHECK(version<=2147483647);

CREATE OR REPLACE FUNCTION guard_service_subject_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.service_subject_id<>OLD.service_subject_id OR NEW.org_id<>OLD.org_id OR NEW.purpose<>OLD.purpose OR NEW.created_by<>OLD.created_by OR NEW.created_at<>OLD.created_at THEN
  RAISE EXCEPTION 'SERVICE_SUBJECT_IDENTITY_IMMUTABLE' USING ERRCODE='23514';
 END IF;
 IF OLD.closed_at IS NOT NULL AND (NEW.active OR NEW.closed_at IS NULL) THEN
  RAISE EXCEPTION 'CLOSED_SERVICE_SUBJECT_IMMUTABLE' USING ERRCODE='23514';
 END IF;
 IF NEW.closed_at IS NOT NULL AND NEW.active THEN
  RAISE EXCEPTION 'CLOSED_SERVICE_SUBJECT_MUST_BE_DISABLED' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER guard_service_subject_identity BEFORE UPDATE ON ds_service_subject
 FOR EACH ROW EXECUTE FUNCTION guard_service_subject_identity();
DROP TRIGGER IF EXISTS lock_authority_mutation ON ds_service_subject;
CREATE TRIGGER lock_authority_mutation BEFORE INSERT OR UPDATE OR DELETE ON ds_service_subject
 FOR EACH STATEMENT EXECUTE FUNCTION public.lock_authority_mutation();

INSERT INTO permission(per_id,per_code,per_name,category,is_builtin,created_by)
 VALUES(gen_random_uuid(),'identity.service.manage','Manage institutional service subjects','operation',true,
        (SELECT user_id FROM fwd_user ORDER BY created_at,user_id LIMIT 1))
 ON CONFLICT(per_code) DO NOTHING;
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation,permission_id)
 VALUES('identity.service.manage','platform','Manage institutional service subjects',true,
        (SELECT per_id FROM permission WHERE per_code='identity.service.manage' AND category='operation'));
UPDATE ds_auth_initialization SET policy_version=policy_version+1 WHERE singleton;
