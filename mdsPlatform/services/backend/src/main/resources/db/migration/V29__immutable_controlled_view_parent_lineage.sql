-- Immutable parent edges for managed materialized views. No source routes are
-- broadened by this migration. A later reviewed workflow must supply the edge.
CREATE TABLE ds_controlled_view_parent(
 child_view_id uuid PRIMARY KEY REFERENCES ds_controlled_view(view_id),
 parent_view_id uuid NOT NULL REFERENCES ds_controlled_view(view_id),
 parent_publication_id uuid NOT NULL REFERENCES ds_controlled_view_publication(publication_id),
 parent_dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id),
 parent_version_id uuid NOT NULL REFERENCES mm_dataset_version(version_id),
 parent_index_version_id uuid NOT NULL REFERENCES mm_index_version(index_version_id),
 parent_raw_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id),
 parent_authorization_version bigint NOT NULL CHECK(parent_authorization_version>0),
 parent_rule_sha256 char(64) NOT NULL CHECK(parent_rule_sha256~'^[0-9a-f]{64}$'),
 root_dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id),
 root_version_id uuid NOT NULL REFERENCES mm_dataset_version(version_id),
 root_raw_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id),
 root_manifest_sha256 char(64) NOT NULL CHECK(root_manifest_sha256~'^[0-9a-f]{64}$'),
 source_grant_id uuid NOT NULL REFERENCES ds_resource_grant(grant_id),
 source_grant_version bigint NOT NULL CHECK(source_grant_version>0),
 required_source_operations varchar(80)[] NOT NULL CHECK(cardinality(required_source_operations)>0),
 depth smallint NOT NULL CHECK(depth BETWEEN 1 AND 15),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CHECK(child_view_id<>parent_view_id)
);
CREATE INDEX controlled_view_parent_ancestor ON ds_controlled_view_parent(parent_view_id);
CREATE TRIGGER controlled_view_parent_immutable BEFORE UPDATE OR DELETE ON ds_controlled_view_parent
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();

-- This checks one complete original parent grant, never a replacement or a
-- union of grants. Human session/role changes do not revoke institutional views.
CREATE FUNCTION controlled_view_parent_binding_current(edge ds_controlled_view_parent)
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

-- Bound traversal: at most sixteen managed views; cycles/missing edges fail
-- closed. Existing first-generation target mapping semantics are preserved.
CREATE FUNCTION controlled_view_lineage_policy_current(target_dataset uuid,seen uuid[])
 RETURNS boolean LANGUAGE plpgsql STABLE AS $lineage$
DECLARE p ds_controlled_view_publication; edge ds_controlled_view_parent; source_dataset uuid;
BEGIN
 IF target_dataset IS NULL OR seen IS NULL OR cardinality(seen)>=16 OR target_dataset=ANY(seen) THEN RETURN false; END IF;
 SELECT * INTO p FROM ds_controlled_view_publication WHERE dataset_id=target_dataset;
 IF NOT FOUND THEN RETURN true; END IF;
 IF NOT EXISTS(
  SELECT 1 FROM ds_controlled_view_publication_review r
  JOIN org o ON o.org_id=(r.payload#>>'{target,orgId}')::uuid AND o.org_status=1
  JOIN ds_enterprise_identity e ON e.org_id=o.org_id AND e.state='CONFIRMED'
  WHERE r.review_id=p.review_id AND o.permission_version=(r.payload#>>'{target,orgVersion}')::bigint
   AND e.entity_id=(r.payload#>>'{target,enterpriseId}') AND e.version=(r.payload#>>'{target,mappingVersion}')::bigint
   AND e.mapping_id=(r.payload#>>'{target,mappingId}')::uuid AND e.catalog_revision_id=(r.payload#>>'{target,catalogRevisionId}')::uuid
 ) THEN RETURN false; END IF;
 SELECT source_dataset_id INTO source_dataset FROM ds_controlled_view WHERE view_id=p.view_id;
 SELECT * INTO edge FROM ds_controlled_view_parent WHERE child_view_id=p.view_id;
 IF NOT FOUND THEN RETURN NOT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=source_dataset); END IF;
 RETURN controlled_view_parent_binding_current(edge)
  AND controlled_view_lineage_policy_current(edge.parent_dataset_id,array_append(seen,target_dataset));
END $lineage$;

CREATE OR REPLACE FUNCTION controlled_view_publication_policy_current(target_dataset uuid)
 RETURNS boolean LANGUAGE sql STABLE AS $lineage$
 SELECT controlled_view_lineage_policy_current(target_dataset,ARRAY[]::uuid[])
$lineage$;

CREATE FUNCTION guard_controlled_view_parent() RETURNS trigger LANGUAGE plpgsql AS $lineage$
BEGIN
 IF NOT controlled_view_parent_binding_current(NEW)
  OR NOT controlled_view_lineage_policy_current(NEW.parent_dataset_id,ARRAY[
   (SELECT dataset_id FROM ds_controlled_view WHERE view_id=NEW.child_view_id)]) THEN
  RAISE EXCEPTION 'CONTROLLED_VIEW_PARENT_BINDING_NOT_CURRENT' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $lineage$;
CREATE TRIGGER controlled_view_parent_binding BEFORE INSERT ON ds_controlled_view_parent
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_parent();

-- The publication and its edge are written in the same transaction. Older
-- programs cannot silently publish a managed child without the trusted edge.
CREATE FUNCTION guard_controlled_view_publication_parent() RETURNS trigger LANGUAGE plpgsql AS $lineage$
BEGIN
 IF EXISTS(SELECT 1 FROM ds_controlled_view child JOIN ds_controlled_view parent ON parent.dataset_id=child.source_dataset_id
   WHERE child.view_id=NEW.view_id)
  AND NOT EXISTS(SELECT 1 FROM ds_controlled_view_parent WHERE child_view_id=NEW.view_id) THEN
  RAISE EXCEPTION 'CONTROLLED_VIEW_PARENT_EDGE_REQUIRED' USING ERRCODE='42501';
 END IF;
 RETURN NULL;
END $lineage$;
CREATE CONSTRAINT TRIGGER controlled_view_publication_parent_required
 AFTER INSERT ON ds_controlled_view_publication DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION guard_controlled_view_publication_parent();

CREATE TRIGGER controlled_view_parent_policy AFTER INSERT ON ds_controlled_view_parent
 FOR EACH STATEMENT EXECUTE FUNCTION ds_resource_policy_changed();
