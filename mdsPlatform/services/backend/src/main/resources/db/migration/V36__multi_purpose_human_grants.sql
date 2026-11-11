-- Extend reviewed human grants to exact explicit purpose sets. Existing singleton origins remain valid.
CREATE OR REPLACE FUNCTION ds_human_grant_origin_current(target uuid,needed varchar[],seen uuid[])
RETURNS boolean LANGUAGE plpgsql STABLE AS $origin$
DECLARE child ds_resource_grant; parent ds_resource_grant; origin ds_human_grant_origin;
 issuer fwd_user; required varchar[]; needed_ops varchar[]; source_purposes varchar[]; source_id uuid; source_version bigint;
BEGIN
 IF target IS NULL OR seen IS NULL OR cardinality(seen)>32 OR target=ANY(seen) THEN RETURN false; END IF;
 SELECT * INTO child FROM ds_resource_grant WHERE grant_id=target;
 IF NOT FOUND THEN RETURN false; END IF;
 needed_ops=COALESCE(needed,child.operation_codes);
 IF cardinality(needed_ops)=0 OR NOT child.operation_codes @> needed_ops THEN RETURN false; END IF;
 SELECT * INTO origin FROM ds_human_grant_origin WHERE grant_id=target;
 IF NOT FOUND THEN RETURN true; END IF;
 IF origin.source_authority->>'kind'='PLATFORM_SAME_ORG_CONFIGURATION' THEN
  RETURN child.owner_org_id=child.grantee_org_id;
 END IF;
 IF origin.source_authority->>'kind' IS DISTINCT FROM 'ORIGINAL_PROVIDER_GRANT' THEN RETURN false; END IF;
 source_id=(origin.source_authority->>'grantId')::uuid;
 source_version=(origin.source_authority->>'version')::bigint;
 IF origin.source_authority ? 'purposeCodes' THEN
  IF jsonb_typeof(origin.source_authority->'purposeCodes') IS DISTINCT FROM 'array' OR origin.source_authority ? 'purpose' THEN RETURN false; END IF;
  source_purposes=ARRAY(SELECT value FROM jsonb_array_elements_text(origin.source_authority->'purposeCodes'))::varchar[];
 ELSE
  source_purposes=ARRAY[origin.source_authority->>'purpose']::varchar[];
 END IF;
 IF source_id IS NULL OR source_version IS NULL OR source_version<1 OR cardinality(source_purposes) NOT BETWEEN 1 AND 3
  OR array_position(source_purposes,NULL) IS NOT NULL
  OR EXISTS(SELECT 1 FROM unnest(source_purposes) p(code) WHERE code NOT IN('GENERAL_READ','VIEW_BUILD','CONTROLLED_DELIVERY'))
  OR cardinality(source_purposes)<>(SELECT count(DISTINCT code) FROM unnest(source_purposes) p(code))
  OR NOT child.purpose_codes @> source_purposes OR NOT source_purposes @> child.purpose_codes
  OR cardinality(child.purpose_codes)<>cardinality(source_purposes) THEN RETURN false; END IF;
 SELECT u.* INTO issuer FROM fwd_user u JOIN org o ON o.org_id=u.org_id AND o.org_status=1
 WHERE u.user_id=origin.confirmed_by AND u.account_state='ACTIVE' AND u.status_code IS NOT NULL
  AND u.org_id=child.owner_org_id
  AND NOT EXISTS(SELECT 1 FROM ds_enterprise_identity e WHERE e.org_id=u.org_id AND e.state<>'CONFIRMED');
 IF NOT FOUND THEN RETURN false; END IF;
 required=ARRAY(SELECT DISTINCT code FROM unnest(array_append(needed_ops,'dataset.share.manage'::varchar)) requested(code) ORDER BY code);
 -- Sharing is a mutation; a primary read-only profile removes this capability.
 IF EXISTS(SELECT 1 FROM user_role ur JOIN role r ON r.role_id=ur.role_id
  WHERE ur.user_id=issuer.user_id AND ur.is_primary AND r.type_state='ACTIVE' AND r.read_only_profile) THEN RETURN false; END IF;
 IF EXISTS(SELECT 1 FROM unnest(required) requested(code) WHERE
  NOT EXISTS(SELECT 1 FROM user_role ur JOIN role r ON r.role_id=ur.role_id
   JOIN role_permission rp ON rp.role_id=r.role_id JOIN ds_operation d ON d.permission_id=rp.per_id
   WHERE ur.user_id=issuer.user_id AND r.type_state='ACTIVE' AND d.operation_code=requested.code
    AND (r.type_scope='GLOBAL' OR r.org_id=issuer.org_id)
    AND (cardinality(r.applicable_org_ids)=0 OR issuer.org_id=ANY(r.applicable_org_ids))
    AND (jsonb_array_length(r.industry_tags)=0 OR EXISTS(SELECT 1 FROM ds_enterprise_identity e
     WHERE e.org_id=issuer.org_id AND e.state='CONFIRMED' AND jsonb_exists(r.industry_tags,e.industry_kind))))
  OR EXISTS(SELECT 1 FROM ds_operation_deny d WHERE d.operation_code=requested.code
   AND ((d.subject_kind='USER' AND d.subject_id=issuer.user_id)
    OR (d.subject_kind='ROLE' AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=issuer.user_id AND ur.role_id=d.subject_id)))))
 THEN RETURN false; END IF;
 SELECT g.* INTO parent FROM ds_resource_grant g
 WHERE g.grant_id=source_id AND g.version=source_version AND g.state='ACTIVE'
  AND g.dataset_id=child.dataset_id AND g.owner_org_id=child.owner_org_id AND g.grantee_org_id=issuer.org_id
  AND ((g.subject_kind='USER' AND g.subject_id=issuer.user_id) OR (g.subject_kind='ORG' AND g.subject_id=issuer.org_id))
  AND g.valid_from<=clock_timestamp() AND (g.valid_to IS NULL OR clock_timestamp()<g.valid_to)
  AND g.valid_from<=child.valid_from AND (g.valid_to IS NULL OR (child.valid_to IS NOT NULL AND child.valid_to<=g.valid_to))
  AND g.operation_codes @> required AND g.purpose_codes @> source_purposes
  AND ((g.scope_kind='DATASET_CUSTODY' AND g.grantee_org_id=g.owner_org_id AND g.version_id IS NULL AND g.index_version_id IS NULL
    AND NOT EXISTS(SELECT 1 FROM ds_controlled_view cv WHERE cv.dataset_id=g.dataset_id))
   OR (g.scope_kind=child.scope_kind AND g.scope_kind IN('FROZEN_VERSION','CONTROLLED_VIEW')
    AND g.version_id=child.version_id AND g.index_version_id=child.index_version_id
    AND g.view_authorization_version IS NOT DISTINCT FROM child.view_authorization_version));
 IF NOT FOUND THEN RETURN false; END IF;
 IF parent.contract_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM ds_contract c JOIN ds_product_version p ON p.product_version_id=c.product_version_id
  WHERE c.contract_id=parent.contract_id AND c.revision=parent.contract_revision
   AND c.provider_org_id=parent.owner_org_id AND c.consumer_org_id=parent.grantee_org_id
   AND p.dataset_id=parent.dataset_id AND p.version_id=parent.version_id AND p.index_version_id=parent.index_version_id
   AND c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.provider_confirmed_at IS NOT NULL AND c.consumer_confirmed_at IS NOT NULL
   AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to
   AND NOT EXISTS(SELECT 1 FROM unnest(required) requested(code) WHERE NOT EXISTS(
    SELECT 1 FROM ds_contract_operation action WHERE action.contract_id=c.contract_id AND action.contract_revision=c.revision
     AND action.operation_code=requested.code AND action.purpose_codes @> source_purposes)))
 THEN RETURN false; END IF;
 RETURN ds_human_grant_origin_current(parent.grant_id,required,array_append(seen,target));
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN false;
END $origin$;
