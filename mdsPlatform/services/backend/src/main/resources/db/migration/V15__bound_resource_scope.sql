-- Candidate-only follow-on: grants bind one reviewed contract revision and scope.
-- Does not modify reviewed historic contract terms or broaden their action set.
ALTER TABLE ds_contract_operation ADD COLUMN contract_revision integer;
UPDATE ds_contract_operation a SET contract_revision=c.revision FROM ds_contract c WHERE c.contract_id=a.contract_id;
ALTER TABLE ds_contract_operation ALTER COLUMN contract_revision SET NOT NULL;
ALTER TABLE ds_contract_operation DROP CONSTRAINT ds_contract_operation_pkey;
ALTER TABLE ds_contract_operation ADD PRIMARY KEY(contract_id,contract_revision,operation_code);
ALTER TABLE ds_contract_operation ADD CHECK(contract_revision>0);
ALTER TABLE ds_resource_grant ADD COLUMN contract_revision integer;
UPDATE ds_resource_grant g SET contract_revision=c.revision FROM ds_contract c WHERE c.contract_id=g.contract_id;
ALTER TABLE ds_resource_grant ADD CHECK((contract_id IS NULL AND contract_revision IS NULL) OR (contract_id IS NOT NULL AND contract_revision>0));
ALTER TABLE ds_resource_grant ADD CHECK(scope_kind<>'DATASET_CUSTODY' OR (version_id IS NULL AND index_version_id IS NULL AND grantee_org_id=owner_org_id));
ALTER TABLE ds_resource_grant ADD CHECK(cardinality(purpose_codes)>0 AND purpose_codes<@ARRAY['GENERAL_READ','CONTROLLED_DELIVERY','VIEW_BUILD']::varchar[]);
CREATE FUNCTION ds_validate_resource_grant() RETURNS trigger LANGUAGE plpgsql AS $f$
DECLARE code text; target_org uuid; target_dataset uuid; target_version uuid;
BEGIN
 IF NEW.state<>'ACTIVE' THEN RETURN NEW; END IF;
 SELECT org_id INTO target_org FROM mm_dataset WHERE dataset_id=NEW.dataset_id;
 IF target_org IS DISTINCT FROM NEW.owner_org_id THEN RAISE EXCEPTION 'RESOURCE_OWNER_MISMATCH' USING ERRCODE='23514'; END IF;
 FOREACH code IN ARRAY NEW.operation_codes LOOP
  IF NOT EXISTS(SELECT 1 FROM ds_operation WHERE operation_code=code AND group_code NOT IN('identity','platform')) THEN
   RAISE EXCEPTION 'INVALID_RESOURCE_OPERATION' USING ERRCODE='23514';
  END IF;
 END LOOP;
 IF NEW.subject_kind='USER' AND NOT EXISTS(SELECT 1 FROM fwd_user WHERE user_id=NEW.subject_id AND org_id=NEW.grantee_org_id) THEN
  RAISE EXCEPTION 'GRANTEE_IDENTITY_MISMATCH' USING ERRCODE='23514';
 ELSIF NEW.subject_kind='ORG' AND NEW.subject_id<>NEW.grantee_org_id THEN
  RAISE EXCEPTION 'GRANTEE_ORGANIZATION_MISMATCH' USING ERRCODE='23514';
 ELSIF NEW.subject_kind='SERVICE' AND NOT EXISTS(SELECT 1 FROM ds_service_subject WHERE service_subject_id=NEW.subject_id AND org_id=NEW.grantee_org_id) THEN
  RAISE EXCEPTION 'GRANTEE_SERVICE_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF NEW.version_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM mm_dataset_version WHERE dataset_id=NEW.dataset_id AND version_id=NEW.version_id) THEN
  RAISE EXCEPTION 'GRANT_VERSION_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF NEW.index_version_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM mm_index_version WHERE dataset_id=NEW.dataset_id AND dataset_version_id=NEW.version_id AND index_version_id=NEW.index_version_id) THEN
  RAISE EXCEPTION 'GRANT_INDEX_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF NEW.scope_kind='CONTROLLED_VIEW' AND NOT EXISTS(SELECT 1 FROM ds_controlled_view v WHERE v.dataset_id=NEW.dataset_id AND v.version_id=NEW.version_id AND v.index_version_id=NEW.index_version_id AND v.authorization_version=NEW.view_authorization_version AND v.state='READY') THEN
  RAISE EXCEPTION 'CONTROLLED_VIEW_NOT_READY' USING ERRCODE='23514';
 END IF;
 IF NEW.contract_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM ds_contract c JOIN ds_product_version p ON p.product_version_id=c.product_version_id
   WHERE c.contract_id=NEW.contract_id AND c.revision=NEW.contract_revision
     AND c.provider_org_id=NEW.owner_org_id AND c.consumer_org_id=NEW.grantee_org_id
     AND p.dataset_id=NEW.dataset_id AND p.version_id=NEW.version_id AND p.index_version_id=NEW.index_version_id) THEN
  RAISE EXCEPTION 'GRANT_CONTRACT_SCOPE_MISMATCH' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $f$;
CREATE TRIGGER ds_resource_grant_scope BEFORE INSERT OR UPDATE ON ds_resource_grant FOR EACH ROW EXECUTE FUNCTION ds_validate_resource_grant();
CREATE FUNCTION ds_resource_policy_changed() RETURNS trigger LANGUAGE plpgsql AS $f$
BEGIN UPDATE ds_auth_initialization SET policy_version=policy_version+1 WHERE singleton; RETURN NULL; END $f$;
CREATE TRIGGER ds_grant_policy AFTER INSERT OR UPDATE OR DELETE ON ds_resource_grant FOR EACH STATEMENT EXECUTE FUNCTION ds_resource_policy_changed();
CREATE TRIGGER ds_view_policy AFTER INSERT OR UPDATE OR DELETE ON ds_controlled_view FOR EACH STATEMENT EXECUTE FUNCTION ds_resource_policy_changed();
CREATE TRIGGER ds_contract_action_policy AFTER INSERT OR UPDATE OR DELETE ON ds_contract_operation FOR EACH STATEMENT EXECUTE FUNCTION ds_resource_policy_changed();
