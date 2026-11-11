-- Origin constraints supplement the caller's normal complete-grant decision.
-- This function alone never authorizes a request. No login/session is borrowed.
CREATE FUNCTION ds_human_grant_origin_current(target uuid,needed varchar[],seen uuid[])
RETURNS boolean LANGUAGE plpgsql STABLE AS $origin$
DECLARE child ds_resource_grant; parent ds_resource_grant; origin ds_human_grant_origin;
 issuer fwd_user; required varchar[]; needed_ops varchar[]; source_purpose varchar; source_id uuid; source_version bigint;
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
 source_purpose=origin.source_authority->>'purpose';
 IF source_id IS NULL OR source_version IS NULL OR source_version<1 OR source_purpose IS NULL
  OR source_purpose NOT IN('GENERAL_READ','VIEW_BUILD','CONTROLLED_DELIVERY')
  OR child.purpose_codes<>ARRAY[source_purpose]::varchar[] THEN RETURN false; END IF;
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
  AND g.operation_codes @> required AND source_purpose=ANY(g.purpose_codes)
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
     AND action.operation_code=requested.code AND source_purpose=ANY(action.purpose_codes))))
 THEN RETURN false; END IF;
 RETURN ds_human_grant_origin_current(parent.grant_id,required,array_append(seen,target));
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN false;
END $origin$;

CREATE FUNCTION ds_human_grant_origin_current(target uuid,seen uuid[] DEFAULT ARRAY[]::uuid[])
RETURNS boolean LANGUAGE sql STABLE AS $requested$
 SELECT ds_human_grant_origin_current(target,NULL::varchar[],seen)
$requested$;

CREATE FUNCTION ds_human_grant_origin_depth(target uuid,seen uuid[] DEFAULT ARRAY[]::uuid[])
RETURNS integer LANGUAGE plpgsql STABLE AS $depth$
DECLARE origin ds_human_grant_origin; parent_id uuid; parent_version bigint; depth integer;
BEGIN
 IF target IS NULL OR seen IS NULL OR cardinality(seen)>32 OR target=ANY(seen)
  OR NOT EXISTS(SELECT 1 FROM ds_resource_grant WHERE grant_id=target) THEN RETURN -1; END IF;
 SELECT * INTO origin FROM ds_human_grant_origin WHERE grant_id=target;
 IF NOT FOUND OR origin.source_authority->>'kind'='PLATFORM_SAME_ORG_CONFIGURATION' THEN RETURN 0; END IF;
 IF origin.source_authority->>'kind' IS DISTINCT FROM 'ORIGINAL_PROVIDER_GRANT' THEN RETURN -1; END IF;
 parent_id=(origin.source_authority->>'grantId')::uuid;parent_version=(origin.source_authority->>'version')::bigint;
 IF NOT EXISTS(SELECT 1 FROM ds_resource_grant WHERE grant_id=parent_id AND version=parent_version) THEN RETURN -1; END IF;
 depth=ds_human_grant_origin_depth(parent_id,array_append(seen,target));
 RETURN CASE WHEN depth<0 THEN -1 ELSE depth+1 END;
EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN RETURN -1;
END $depth$;

CREATE FUNCTION ds_guard_human_grant_origin_insert() RETURNS trigger LANGUAGE plpgsql AS $guard$
BEGIN
 IF NOT ds_human_grant_origin_current(NEW.grant_id) OR ds_human_grant_origin_depth(NEW.grant_id) NOT BETWEEN 0 AND 32 THEN
  RAISE EXCEPTION 'HUMAN_GRANT_ORIGINAL_SOURCE_NOT_CURRENT' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $guard$;
CREATE TRIGGER human_grant_origin_insert_guard AFTER INSERT ON ds_human_grant_origin
 FOR EACH ROW EXECUTE FUNCTION ds_guard_human_grant_origin_insert();

CREATE INDEX ds_human_grant_origin_parent ON ds_human_grant_origin((source_authority->>'grantId')) WHERE source_authority->>'kind'='ORIGINAL_PROVIDER_GRANT';

-- Preserve the existing native parent binding and add the same origin guard.
CREATE OR REPLACE FUNCTION controlled_view_parent_binding_current(edge ds_controlled_view_parent)
 RETURNS boolean LANGUAGE sql STABLE AS $lineage$
 SELECT EXISTS(
  SELECT 1 FROM ds_controlled_view child
  JOIN ds_controlled_view_publication cp ON cp.view_id=child.view_id
  JOIN ds_controlled_view_publication_review cr ON cr.review_id=cp.review_id
  JOIN ds_controlled_view parent ON parent.view_id=edge.parent_view_id
  JOIN ds_controlled_view_publication pp ON pp.view_id=parent.view_id
  JOIN ds_controlled_view_publication_review pr ON pr.review_id=pp.review_id
  JOIN ds_controlled_view_publication_state ps ON ps.publication_id=pp.publication_id AND ps.state='READY'
  JOIN ds_controlled_view_publication_index pi ON pi.publication_id=pp.publication_id
  JOIN mm_dataset pd ON pd.dataset_id=parent.dataset_id AND pd.org_id=parent.owner_org_id AND pd.status='ACTIVE'
  JOIN org po ON po.org_id=pd.org_id AND po.org_status=1
  JOIN org co ON co.org_id=child.owner_org_id AND co.org_status=1
  JOIN mm_dataset_version pv ON pv.dataset_id=pd.dataset_id AND pv.version_id=parent.version_id AND pv.raw_commit_id=pp.raw_commit_id
  JOIN artifact_commit raw ON raw.repo_id=pd.raw_repo_id AND raw.commit_id=pv.raw_commit_id AND raw.commit_status='PUBLISHED'
  JOIN mm_index_version idx ON idx.index_version_id=parent.index_version_id AND idx.dataset_id=pd.dataset_id
    AND idx.dataset_version_id=pv.version_id AND idx.index_repo_id=pd.index_repo_id AND idx.index_status='READY'
  JOIN artifact_commit ic ON ic.repo_id=idx.index_repo_id AND ic.commit_id=idx.index_commit_id AND ic.commit_status='PUBLISHED'
  JOIN ds_resource_grant g ON g.grant_id=edge.source_grant_id AND g.version=edge.source_grant_version
  LEFT JOIN ds_controlled_view_parent ancestor ON ancestor.child_view_id=parent.view_id
  WHERE child.view_id=edge.child_view_id AND child.owner_org_id=cr.actor_org_id
   AND parent.owner_org_id=child.owner_org_id
   AND pp.publication_id=edge.parent_publication_id
   AND parent.dataset_id=edge.parent_dataset_id AND parent.version_id=edge.parent_version_id
   AND parent.index_version_id=edge.parent_index_version_id AND parent.state='READY'
   AND parent.authorization_version=edge.parent_authorization_version AND parent.rule_manifest_sha256=edge.parent_rule_sha256
   AND pp.dataset_id=parent.dataset_id AND pp.version_id=parent.version_id AND pp.raw_repo_id=pd.raw_repo_id
   AND pp.raw_commit_id=edge.parent_raw_commit_id AND raw.manifest_sha256=pp.raw_manifest_sha256
   AND raw.manifest_object_key=pp.raw_manifest_object_key AND pi.index_version_id=idx.index_version_id AND pi.index_commit_id=ic.commit_id
   AND child.source_dataset_id=parent.dataset_id AND child.source_version_id=parent.version_id
   AND child.source_raw_commit_id=edge.parent_raw_commit_id
   AND cr.payload#>>'{source,datasetId}'=parent.dataset_id::text
   AND cr.payload#>>'{source,versionId}'=parent.version_id::text
   AND cr.payload#>>'{source,indexVersionId}'=parent.index_version_id::text
   AND cr.payload#>>'{source,rawRepoId}'=pd.raw_repo_id::text
   AND cr.payload#>>'{source,rawCommitId}'=edge.parent_raw_commit_id::text
   AND cr.payload#>>'{source,manifestSha256}'=btrim(pp.raw_manifest_sha256)
   AND cr.payload#>>'{source,manifestObjectKey}'=pp.raw_manifest_object_key
   AND cr.payload#>>'{sourceAuthority,buildProtocol}'='FIXED_PRIVATE_VIEW_BUILD_V2'
   AND cr.payload#>>'{sourceAuthority,purpose}'='VIEW_BUILD'
   AND cr.payload#>>'{sourceAuthority,grantId}'=edge.source_grant_id::text
   AND (cr.payload#>>'{sourceAuthority,version}')::bigint=edge.source_grant_version
   AND edge.required_source_operations=ARRAY(
     SELECT DISTINCT operation FROM (
       SELECT 'dataset.share.manage'::varchar AS operation
       UNION ALL SELECT CASE WHEN value='data.view.export' THEN 'dataset.share.manage' ELSE value END
        FROM jsonb_array_elements_text(cr.payload->'providerOperations')
     ) operations ORDER BY operation)::varchar[]
   AND cr.payload#>'{source,controlledParent}'=jsonb_build_object(
    'schema','CONTROLLED_VIEW_PARENT_V1','viewId',parent.view_id,'publicationId',pp.publication_id,
    'authorizationVersion',parent.authorization_version,'indexVersionId',parent.index_version_id,
    'ruleSha256',btrim(parent.rule_manifest_sha256),'rootDatasetId',edge.root_dataset_id,
    'rootVersionId',edge.root_version_id,'rootRawCommitId',edge.root_raw_commit_id,
    'rootManifestSha256',btrim(edge.root_manifest_sha256),'depth',edge.depth,
    'providerValidFrom',pr.payload->'providerValidFrom','validTo',pr.payload->'validTo')
   AND (pr.payload->>'providerValidFrom')::timestamptz<=clock_timestamp()
   AND (pr.payload->>'validTo' IS NULL OR clock_timestamp()<(pr.payload->>'validTo')::timestamptz)
   AND (cr.payload->>'providerValidFrom')::timestamptz>=(pr.payload->>'providerValidFrom')::timestamptz
   AND (cr.payload->>'validFrom')::timestamptz>=(pr.payload->>'providerValidFrom')::timestamptz
   AND (pr.payload->>'validTo' IS NULL OR (
    cr.payload->>'validTo' IS NOT NULL AND (cr.payload->>'validTo')::timestamptz<=(pr.payload->>'validTo')::timestamptz))
   AND ds_human_grant_origin_current(g.grant_id,edge.required_source_operations,ARRAY[]::uuid[])
   AND g.scope_kind='CONTROLLED_VIEW' AND g.state='ACTIVE' AND g.dataset_id=parent.dataset_id
   AND g.version_id=parent.version_id AND g.index_version_id=parent.index_version_id
   AND g.owner_org_id=parent.owner_org_id AND g.grantee_org_id=child.owner_org_id
   AND g.view_authorization_version=parent.authorization_version
   AND ((g.subject_kind='ORG' AND g.subject_id=child.owner_org_id)
    OR (g.subject_kind='USER' AND g.subject_id=cr.actor_user_id))
   AND g.valid_from<=clock_timestamp() AND (g.valid_to IS NULL OR clock_timestamp()<g.valid_to)
   AND g.operation_codes @> edge.required_source_operations AND 'VIEW_BUILD'=ANY(g.purpose_codes)
   AND g.valid_from<=(cr.payload->>'providerValidFrom')::timestamptz
   AND g.valid_from<=(cr.payload->>'validFrom')::timestamptz
   AND (g.valid_to IS NULL OR (
    cr.payload->>'validTo' IS NOT NULL AND (cr.payload->>'validTo')::timestamptz<=g.valid_to))
   AND NOT EXISTS(SELECT 1 FROM ds_enterprise_identity e WHERE e.org_id IN(po.org_id,co.org_id) AND e.state<>'CONFIRMED')
   AND (g.contract_id IS NULL OR EXISTS(
    SELECT 1 FROM ds_contract c JOIN ds_product_version product ON product.product_version_id=c.product_version_id
    WHERE c.contract_id=g.contract_id AND c.revision=g.contract_revision
     AND c.provider_org_id=g.owner_org_id AND c.consumer_org_id=g.grantee_org_id
     AND c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.provider_confirmed_at IS NOT NULL AND c.consumer_confirmed_at IS NOT NULL
     AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to
     AND product.dataset_id=parent.dataset_id AND product.version_id=parent.version_id
     AND product.index_version_id=parent.index_version_id AND product.raw_commit_id=edge.parent_raw_commit_id
     AND NOT EXISTS(SELECT 1 FROM unnest(edge.required_source_operations) requested(code)
      WHERE NOT EXISTS(SELECT 1 FROM ds_contract_operation action
       WHERE action.contract_id=c.contract_id AND action.contract_revision=c.revision
        AND action.operation_code=requested.code AND 'VIEW_BUILD'=ANY(action.purpose_codes)))))
   AND ((ancestor.child_view_id IS NOT NULL AND edge.depth=ancestor.depth+1
     AND (edge.root_dataset_id,edge.root_version_id,edge.root_raw_commit_id,edge.root_manifest_sha256)
      =(ancestor.root_dataset_id,ancestor.root_version_id,ancestor.root_raw_commit_id,ancestor.root_manifest_sha256))
    OR (ancestor.child_view_id IS NULL AND edge.depth=1
     AND edge.root_dataset_id=parent.source_dataset_id AND edge.root_version_id=parent.source_version_id
     AND edge.root_raw_commit_id=parent.source_raw_commit_id
     AND pr.payload#>>'{source,datasetId}'=edge.root_dataset_id::text
     AND pr.payload#>>'{source,versionId}'=edge.root_version_id::text
     AND pr.payload#>>'{source,rawCommitId}'=edge.root_raw_commit_id::text
     AND pr.payload#>>'{source,manifestSha256}'=btrim(edge.root_manifest_sha256)
     AND NOT EXISTS(SELECT 1 FROM ds_controlled_view root_view WHERE root_view.dataset_id=edge.root_dataset_id)
     AND EXISTS(SELECT 1 FROM mm_dataset rd JOIN mm_dataset_version rv ON rv.dataset_id=rd.dataset_id
      JOIN artifact_commit rc ON rc.repo_id=rd.raw_repo_id AND rc.commit_id=rv.raw_commit_id
      WHERE rd.dataset_id=edge.root_dataset_id AND rv.version_id=edge.root_version_id
       AND rc.commit_id=edge.root_raw_commit_id AND rc.commit_status='PUBLISHED' AND rc.manifest_sha256=edge.root_manifest_sha256)))
 )
$lineage$;
