package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;
import static com.fwdrobo.sirius.security.IdentityManagementService.*;

/** Metadata-only forecast, checked against live policy before and after the transaction.
 * It neither grants resources nor changes jobs. Persisted reviews contain no credentials.
 */
@Service
public class IdentityChangeReviewService {
 private final JdbcTemplate jdbc;private final AuthorizationService auth;private final ObjectMapper mapper;private final AuthorizationAuditService audit;private final IndexFileImpactProjection indexFileProjection;
 public IdentityChangeReviewService(JdbcTemplate jdbc,AuthorizationService auth,ObjectMapper mapper,AuthorizationAuditService audit,IndexFileImpactProjection indexFileProjection){this.jdbc=jdbc;this.auth=auth;this.mapper=mapper;this.audit=audit;this.indexFileProjection=indexFileProjection;}
 public record Receipt(UUID id,AuthorizationService.Actor actor,String action,UUID target,JsonNode impact){}
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 public void fence(){auth.lockIdentityMutation();jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");}
 private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Identity review encoding failed");}}
 private JsonNode node(Object value){return mapper.valueToTree(value);}
 private JsonNode parsed(Object value){try{return mapper.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("Identity review metadata invalid");}}
 private JsonNode sorted(JsonNode n){
  if(n.isObject()){var o=mapper.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String k:keys)o.set(k,sorted(n.get(k)));return o;}
  if(n.isArray()){var a=mapper.createArrayNode();n.forEach(v->a.add(sorted(v)));return a;}return n;
 }
 private String sha(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(sorted(node(value))).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Identity review hash failed");}}
 private static UUID uuid(JsonNode n,String k){return UUID.fromString(n.path(k).asText());}
 private static List<String> strings(JsonNode n){var out=new ArrayList<String>();n.forEach(v->out.add(v.asText()));return out;}
 private static String op(String action){return switch(action){case "TYPE_UPDATE","TYPE_DELETE"->"identity.type.manage";case "USER_TYPES"->"identity.member.type.assign";case "QUALIFICATION"->"identity.platform-admin.manage";case "ACCOUNT_UPDATE"->"identity.member.update";default->throw new IllegalArgumentException("Unknown identity action");};}
 public List<UUID> affectedType(UUID id){return jdbc.queryForList("""
  SELECT user_id FROM user_role WHERE role_id=? UNION
  SELECT user_id FROM ds_management_membership WHERE qualification='ENTERPRISE_MANAGER' AND ?=ANY(delegated_type_ids)
  ORDER BY user_id
  """,UUID.class,id,id);}
 private List<UUID> affected(String action,UUID id){return action.startsWith("TYPE_")?affectedType(id):List.of(id);}
 private ObjectNode role(UUID id,boolean primary){
  var rows=jdbc.queryForList("""
   SELECT role_id,org_id,role_code,role_name,type_scope,type_state,type_version,description,industry_tags,
    applicable_org_ids,read_only_profile FROM role WHERE role_id=?
   """,id);if(rows.size()!=1)throw new IllegalStateException("Missing identity type");var r=rows.get(0);var n=mapper.createObjectNode();
  for(String k:List.of("role_id","org_id","role_code","role_name","type_scope","type_state","type_version","description","read_only_profile"))n.set(k,node(r.get(k)));
  n.set("industry_tags",parsed(r.get("industry_tags")));
  try{n.set("applicable_org_ids",node(((java.sql.Array)r.get("applicable_org_ids")).getArray()));}catch(Exception e){throw new IllegalStateException("Invalid type scope");}
  var operations=jdbc.queryForList("""
   SELECT o.operation_code FROM role_permission rp JOIN ds_operation o ON o.permission_id=rp.per_id
   WHERE rp.role_id=?
   """,String.class,id);
  // Match the order used by editRole independently of the database collation.
  n.set("operations",node(new TreeSet<>(operations)));
  n.set("denies",node(jdbc.queryForList("SELECT operation_code FROM ds_operation_deny WHERE subject_kind='ROLE' AND subject_id=? ORDER BY operation_code",String.class,id)));
  n.put("primary",primary);return n;
 }
 private void organization(ObjectNode n,UUID org){
  var rows=jdbc.queryForList("SELECT org_status FROM org WHERE org_id=?",org);if(rows.size()!=1)throw new IllegalStateException("Missing organization");
  n.set("org_status",node(rows.get(0).get("org_status")));n.put("org_id",org.toString());
  var mapping=jdbc.queryForList("SELECT state,industry_kind,version FROM ds_enterprise_identity WHERE org_id=?",org);
  n.set("mapping",mapping.isEmpty()?NullNode.instance:node(mapping.get(0)));
 }
 private ObjectNode state(UUID uid){
  var rows=jdbc.queryForList("""
   SELECT user_id,user_name,org_id,account_state,status_code,token_version,permission_version
   FROM fwd_user WHERE user_id=?
   """,uid);if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"MEMBER_SCOPE_NOT_AVAILABLE");var n=(ObjectNode)node(rows.get(0));organization(n,uuid(n,"org_id"));
  var roles=mapper.createArrayNode();for(var r:jdbc.queryForList("SELECT role_id,is_primary FROM user_role WHERE user_id=? ORDER BY role_id",uid))roles.add(role((UUID)r.get("role_id"),Boolean.TRUE.equals(r.get("is_primary"))));n.set("roles",roles);
  var quals=mapper.createArrayNode();for(var q:jdbc.queryForList("SELECT qualification,active,version,delegated_type_ids FROM ds_management_membership WHERE user_id=? ORDER BY qualification",uid)){
   var v=mapper.createObjectNode();for(String k:List.of("qualification","active","version"))v.set(k,node(q.get(k)));var ds=mapper.createArrayNode();
   try{var ids=(Object[])((java.sql.Array)q.get("delegated_type_ids")).getArray();Arrays.sort(ids,Comparator.comparing(Object::toString));for(Object id:ids)ds.add(role(UUID.fromString(id.toString()),false));}catch(Exception e){throw new IllegalStateException("Invalid delegation metadata",e);}v.set("delegated",ds);quals.add(v);
  }n.set("qualifications",quals);
  n.set("userDenies",node(jdbc.queryForList("SELECT operation_code FROM ds_operation_deny WHERE subject_kind='USER' AND subject_id=? ORDER BY operation_code",String.class,uid)));
  n.put("sessions",jdbc.queryForObject("SELECT count(*) FROM ds_auth_session WHERE user_id=? AND revoked_at IS NULL AND expires_at>clock_timestamp()",Long.class,uid));
  n.set("tasks",node(jdbc.queryForList("""
   SELECT 'EXECUTION' AS "taskKind",j.run_id AS "taskId",j.run_id AS "runId",j.job_id AS "jobId",j.org_id AS "orgId",j.run_status::text AS status,
    CASE WHEN d.run_id IS NULL THEN 'LEGACY_USER_ORIGIN' ELSE 'FROZEN_USER_AUTHORITY' END AS authority
   FROM job_run j LEFT JOIN ds_job_execution_definition d ON d.run_id=j.run_id
   WHERE (d.actor_user_id=? OR (d.run_id IS NULL AND j.created_by=?)) AND j.run_status IN('QUEUED','RUNNING')
   UNION ALL
   SELECT 'QUALITY',q.report_id,NULL::uuid,NULL::uuid,d.org_id,j.status::text,
    CASE WHEN a.report_id IS NULL THEN 'LEGACY_USER_ORIGIN' ELSE 'FROZEN_USER_AUTHORITY' END
   FROM ds_quality_report q JOIN ds_quality_job j ON j.report_id=q.report_id
   JOIN mm_dataset d ON d.dataset_id=q.dataset_id
   LEFT JOIN ds_quality_authority a ON a.report_id=q.report_id
   WHERE COALESCE(a.actor_user_id,q.created_by)=? AND j.status IN('QUEUED','RUNNING')
   UNION ALL
   SELECT 'INDEX_QUEUE',t.task_id,NULL::uuid,NULL::uuid,t.org_id,t.task_status::text,
    CASE WHEN t.origin_session_id IS NULL THEN 'LEGACY_USER_ORIGIN' ELSE 'FROZEN_USER_AUTHORITY' END
   FROM mm_ingest_task t
   WHERE t.created_by=? AND t.task_status IN('QUEUED','RETRY','PROCESSING','WAITING_INDEX')
    AND NOT EXISTS(SELECT 1 FROM mm_index_version i JOIN job_run j ON j.run_id=i.build_run_id
     WHERE i.index_version_id=t.index_version_id AND j.run_status IN('QUEUED','RUNNING'))
   UNION ALL
   SELECT a.request_kind,a.request_id,NULL::uuid,NULL::uuid,a.actor_org_id,
    CASE WHEN a.state='RUNNING' AND a.lease_until>clock_timestamp() THEN 'RUNNING' ELSE 'UNCONFIRMED' END,
    'FROZEN_USER_REQUEST'
   FROM ds_interactive_request_activity a
   WHERE a.actor_user_id=? AND a.state IN('RUNNING','UNCERTAIN')
   ORDER BY "taskKind","taskId"
   """,uid,uid,uid,uid,uid)));
  return n;
 }
 private boolean usable(JsonNode n){return "ACTIVE".equals(n.path("account_state").asText())&&!n.path("status_code").isNull()&&n.path("org_status").asInt()==1;}
 private boolean eligible(JsonNode r,JsonNode n){return "ACTIVE".equals(r.path("type_state").asText())&&
  ("GLOBAL".equals(r.path("type_scope").asText())||r.path("org_id").equals(n.path("org_id")))&&
  (r.path("applicable_org_ids").isEmpty()||strings(r.path("applicable_org_ids")).contains(n.path("org_id").asText()))&&
  (r.path("industry_tags").isEmpty()||"CONFIRMED".equals(n.path("mapping").path("state").asText())&&strings(r.path("industry_tags")).contains(n.path("mapping").path("industry_kind").asText()));}
 private Set<String> configured(JsonNode n){
  var codes=new TreeSet<String>();boolean ro=false;for(JsonNode r:n.path("roles")){
   if(eligible(r,n))codes.addAll(strings(r.path("operations")));
   if(r.path("primary").asBoolean()&&"ACTIVE".equals(r.path("type_state").asText())&&r.path("read_only_profile").asBoolean())ro=true;
  }
  if(ro)codes.removeIf(c->OperationCatalog.isBusiness(c)&&!OperationCatalog.isReadOnlyBusinessOperation(c));
  codes.removeAll(strings(n.path("userDenies")));for(JsonNode r:n.path("roles"))codes.removeAll(strings(r.path("denies")));
  if(!n.path("mapping").isNull()&&!"CONFIRMED".equals(n.path("mapping").path("state").asText()))codes.removeIf(OperationCatalog::isBusiness);
  codes.addAll(OperationCatalog.SELF);for(JsonNode q:n.path("qualifications"))if(q.path("active").asBoolean()){
   if("PLATFORM_ADMIN".equals(q.path("qualification").asText()))codes.addAll(OperationCatalog.PLATFORM_MANAGEMENT);
   if("ENTERPRISE_MANAGER".equals(q.path("qualification").asText()))codes.addAll(OperationCatalog.MEMBER_MANAGEMENT);
  }codes.removeIf(c->!OperationCatalog.isKnown(c));return codes;
 }
 private ObjectNode summary(ObjectNode n){var v=n.deepCopy();v.set("configuredOperations",node(configured(n)));v.set("effectiveOperations",node(usable(n)?configured(n):Set.of()));return v;}
 private void editRole(ObjectNode r,TypeChange req){
  r.put("role_code",req.code());r.put("role_name",req.name());r.put("description",req.description()==null?"":req.description());r.put("type_scope",req.scope());r.put("type_state",req.state());r.put("read_only_profile",req.readOnly());
  r.set("operations",node(req.operations()==null?List.of():new TreeSet<>(req.operations())));r.set("industry_tags",node(req.industryTags()==null?List.of():new LinkedHashSet<>(req.industryTags())));r.set("applicable_org_ids",node(req.applicableOrgIds()==null?List.of():new LinkedHashSet<>(req.applicableOrgIds())));
 }
 private void assigned(ObjectNode n,List<UUID> ids,UUID primary){var rs=mapper.createArrayNode();if(ids!=null)for(UUID id:new TreeSet<>(ids))rs.add(role(id,id.equals(primary)));n.set("roles",rs);}
 private ObjectNode proposed(ObjectNode before,String action,UUID target,Object req){
  var n=before.deepCopy();switch(action){
   case "TYPE_UPDATE"->{for(JsonNode r:n.path("roles"))if(target.equals(uuid(r,"role_id")))editRole((ObjectNode)r,(TypeChange)req);for(JsonNode q:n.path("qualifications"))for(JsonNode r:q.path("delegated"))if(target.equals(uuid(r,"role_id")))editRole((ObjectNode)r,(TypeChange)req);}
   case "TYPE_DELETE"->{var d=(TypeDeletion)req;UUID replacement=d.replacementTypeId();
    var rs=mapper.createArrayNode();boolean primary=false,found=false;for(JsonNode r:n.path("roles")){if(target.equals(uuid(r,"role_id"))){found=true;primary=r.path("primary").asBoolean();}else rs.add(r);}
    if(found&&replacement!=null){ObjectNode existing=null;for(JsonNode r:rs)if(replacement.equals(uuid(r,"role_id")))existing=(ObjectNode)r;if(existing!=null)existing.put("primary",existing.path("primary").asBoolean()||primary);else rs.add(role(replacement,primary));}n.set("roles",rs);
    for(JsonNode q:n.path("qualifications")){var ds=mapper.createArrayNode();boolean used=false,has=false;for(JsonNode r:q.path("delegated")){if(target.equals(uuid(r,"role_id")))used=true;else{ds.add(r);if(replacement!=null&&replacement.equals(uuid(r,"role_id")))has=true;}}if(used&&replacement!=null&&!has)ds.add(role(replacement,false));((ObjectNode)q).set("delegated",ds);}
   }
   case "USER_TYPES"->{var a=(TypeAssignment)req;assigned(n,a.typeIds(),a.primaryTypeId());}
   case "ACCOUNT_UPDATE"->{var a=(AccountUpdate)req;if(a.orgId()!=null&&!uuid(n,"org_id").equals(a.orgId())){organization(n,a.orgId());for(JsonNode q:n.path("qualifications"))if("ENTERPRISE_MANAGER".equals(q.path("qualification").asText()))((ObjectNode)q).set("delegated",mapper.createArrayNode());}if(a.username()!=null)n.put("user_name",java.text.Normalizer.normalize(a.username().strip(),java.text.Normalizer.Form.NFKC));if(a.typeIds()!=null)assigned(n,a.typeIds(),a.primaryTypeId());}
   case "QUALIFICATION"->{var a=(QualificationChange)req;ObjectNode changed=null;for(JsonNode q:n.path("qualifications"))if(a.qualification().equals(q.path("qualification").asText()))changed=(ObjectNode)q;if(changed==null){changed=mapper.createObjectNode().put("qualification",a.qualification()).put("version",0);((ArrayNode)n.path("qualifications")).add(changed);}changed.put("active",a.active());var ds=mapper.createArrayNode();if(a.delegatedTypeIds()!=null)for(UUID id:new TreeSet<>(a.delegatedTypeIds()))ds.add(role(id,false));changed.set("delegated",ds);}
   default->throw new IllegalArgumentException("Unknown identity action");
  }return n;
 }
 private boolean readOnlyConflict(JsonNode n){boolean ro=false;for(JsonNode r:n.path("roles"))if(r.path("primary").asBoolean()&&r.path("read_only_profile").asBoolean()&&"ACTIVE".equals(r.path("type_state").asText()))ro=true;if(!ro)return false;
  for(JsonNode r:n.path("roles"))if(!r.path("primary").asBoolean()&&"ACTIVE".equals(r.path("type_state").asText()))for(String c:strings(r.path("operations")))if(OperationCatalog.isBusiness(c)&&!OperationCatalog.isReadOnlyBusinessOperation(c))return true;return false;}
 private Map<String,Object> fileSourceImpact(String grants,UUID issuer,AuthorizationService.Actor actor,IndexFileImpactProjection.Snapshot projection){
  String graph=grants+"""
   , source_projection AS (
    SELECT * FROM jsonb_to_recordset(?::jsonb) p(run_id uuid,source_kind text,actor_user_id uuid,
     grant_id uuid,grant_version bigint,projection_sha256 text,unresolved_count integer,legacy_origin_missing boolean)
   ), extra_file_edges AS (
    SELECT * FROM jsonb_to_recordset(?::jsonb) e(parent_run_id uuid,child_run_id uuid)
   ), file_edges AS (
    SELECT DISTINCT parent.run_id AS parent_run_id,child.run_id AS child_run_id
    FROM ds_generic_execution_origin child
    CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(child.snapshot->'files')='array'
     THEN child.snapshot->'files' ELSE '[]'::jsonb END) ref
    JOIN ds_generic_file_origin parent ON parent.object_key=ref->>'objectKey'
    UNION SELECT parent_run_id,child_run_id FROM extra_file_edges
   ), file_seeds AS (
    SELECT d.run_id FROM ds_job_execution_definition d
    WHERE jsonb_exists(d.definition,'ioManifest') AND (d.actor_user_id=? OR EXISTS(
     SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.definition->'bindings')='array'
      THEN d.definition->'bindings' ELSE '[]'::jsonb END) binding
     JOIN unique_affected grant_ref ON binding->>'grantId'=grant_ref.grant_id::text
      AND binding->>'grantVersion'=grant_ref.grant_version::text))
    UNION
    SELECT p.run_id FROM source_projection p WHERE p.source_kind='NATIVE_INDEX' AND (p.actor_user_id=? OR EXISTS(
     SELECT 1 FROM unique_affected g WHERE g.grant_id=p.grant_id AND g.grant_version=p.grant_version))
   ), file_affected(run_id) AS (
    SELECT run_id FROM file_seeds
    UNION
    SELECT edge.child_run_id FROM file_affected parent JOIN file_edges edge ON edge.parent_run_id=parent.run_id
   )
   """;
  Object[] args={issuer,projection.statesJson(),projection.edgesJson(),issuer,issuer};
  var executions=jdbc.queryForList(graph+"""
   SELECT r.run_id AS "runId",COALESCE(p.source_kind,'GENERIC') AS "sourceKind",
    d.definition_sha256 AS "definitionSha256",o.snapshot_sha256 AS "snapshotSha256",o.snapshot::text AS "sourceSnapshot",
    p.projection_sha256 AS "indexSourceProjectionSha256",COALESCE(p.unresolved_count,0) AS "unresolvedSourceCount",
    COALESCE(p.legacy_origin_missing,false) AS "legacyNativeOriginMissing",
    CASE WHEN p.source_kind='NATIVE_INDEX' OR o.run_id IS NULL THEN false ELSE
     jsonb_typeof(o.snapshot->'files') IS DISTINCT FROM 'array' OR jsonb_typeof(o.snapshot->'bindings') IS DISTINCT FROM 'array'
     END AS "invalidSnapshotShape"
   FROM file_affected a JOIN job_run r ON r.run_id=a.run_id
   LEFT JOIN ds_job_execution_definition d ON d.run_id=r.run_id
   LEFT JOIN ds_generic_execution_origin o ON o.run_id=r.run_id
   LEFT JOIN source_projection p ON p.run_id=r.run_id ORDER BY r.run_id
   """,args);
  var files=jdbc.queryForList(graph+"""
   SELECT f.object_key AS "objectKey",f.file_id AS "fileId",f.repo_id AS "repoId",f.commit_id AS "commitId",
    f.file_sha256 AS "fileSha256",f.run_id AS "runId",f.definition_sha256 AS "definitionSha256"
   FROM file_affected a JOIN ds_generic_file_origin f ON f.run_id=a.run_id ORDER BY f.object_key
   """,args);
  var tasks=jdbc.queryForList(graph+"""
   SELECT r.job_id AS "jobId",r.run_id AS "runId",r.org_id AS "orgId",r.run_status::text AS "status",
    COALESCE(p.source_kind,'GENERIC') AS "sourceKind"
   FROM file_affected a JOIN job_run r ON r.run_id=a.run_id
   LEFT JOIN source_projection p ON p.run_id=r.run_id
   WHERE r.run_status IN('QUEUED','RUNNING') ORDER BY r.run_id
   """,args);
  var visibleGeneric=new ArrayList<Map<String,Object>>();var visibleNative=new ArrayList<Map<String,Object>>();
  for(var row:tasks)if(actor.platformAdmin()||actor.orgId().equals(row.get("orgId"))){
   var safe=new LinkedHashMap<String,Object>();for(String key:List.of("jobId","runId","status","sourceKind"))safe.put(key,row.get(key));
   if("NATIVE_INDEX".equals(row.get("sourceKind")))visibleNative.add(safe);else visibleGeneric.add(safe);
  }
  long nativeCount=executions.stream().filter(row->"NATIVE_INDEX".equals(row.get("sourceKind"))).count();
  long blobCount=executions.stream().filter(row->"INDEX_BLOB".equals(row.get("sourceKind"))).count();
  long uncaptured=executions.stream().filter(row->!"NATIVE_INDEX".equals(row.get("sourceKind"))&&row.get("snapshotSha256")==null).count();
  long invalid=executions.stream().filter(row->Boolean.TRUE.equals(row.get("invalidSnapshotShape"))).count();
  long unresolved=executions.stream().filter(row->((Number)row.get("unresolvedSourceCount")).longValue()>0).count();
  long legacy=executions.stream().filter(row->Boolean.TRUE.equals(row.get("legacyNativeOriginMissing"))).count();
  boolean complete=uncaptured==0&&invalid==0&&unresolved==0&&legacy==0;
  var commits=new HashSet<String>();for(var row:files)commits.add(row.get("repoId")+":"+row.get("commitId"));
  var out=new LinkedHashMap<String,Object>();out.put("referencedExecutionCount",executions.size());out.put("genericExecutionCount",executions.size()-nativeCount);
  out.put("nativeIndexExecutionCount",nativeCount);out.put("indexedSourceBlobExecutionCount",blobCount);
  out.put("derivedFileRecordCount",files.size());out.put("derivedSnapshotCount",commits.size());
  out.put("activeGenericTaskCount",tasks.stream().filter(row->!"NATIVE_INDEX".equals(row.get("sourceKind"))).count());
  out.put("activeNativeIndexTaskCount",tasks.stream().filter(row->"NATIVE_INDEX".equals(row.get("sourceKind"))).count());
  out.put("visibleGenericTasks",visibleGeneric);out.put("visibleNativeIndexTasks",visibleNative);
  out.put("uncapturedExecutionCount",uncaptured);out.put("invalidCapturedSnapshotCount",invalid);
  out.put("unresolvedSourceExecutionCount",unresolved);out.put("unrecordedNativeOriginCount",legacy);
  out.put("dependencySnapshotSha256",sha(List.of(executions,files,tasks)));out.put("coverage","GENERIC_NATIVE_INDEX_AND_INDEX_BLOB_REFERENCES");
  out.put("nativeIndexDependenciesCovered",complete);out.put("indexedSourceBlobDependenciesCovered",complete);
  out.put("allTaskKindsCovered",false);return out;
 }
 private Map<String,Object> delegatedImpact(UUID issuer,JsonNode before,JsonNode after,IndexFileImpactProjection.Snapshot projection){
  String closure="""
   WITH RECURSIVE affected(grant_id,grant_version,path) AS (
    SELECT g.grant_id,g.version,ARRAY[g.grant_id] FROM ds_resource_grant g
    JOIN ds_human_grant_origin o ON o.grant_id=g.grant_id
    WHERE o.confirmed_by=? AND o.source_authority->>'kind'='ORIGINAL_PROVIDER_GRANT'
    UNION ALL
    SELECT child.grant_id,child.version,array_append(parent.path,child.grant_id)
    FROM affected parent JOIN ds_human_grant_origin origin
     ON origin.source_authority->>'kind'='ORIGINAL_PROVIDER_GRANT'
      AND origin.source_authority->>'grantId'=parent.grant_id::text
      AND origin.source_authority->>'version'=parent.grant_version::text
    JOIN ds_resource_grant child ON child.grant_id=origin.grant_id
    WHERE cardinality(parent.path)<33 AND NOT child.grant_id=ANY(parent.path)
   ), unique_affected AS (SELECT DISTINCT grant_id,grant_version FROM affected)
   """;
  var grants=jdbc.queryForList(closure+"""
   SELECT jsonb_build_object('grantId',g.grant_id,'version',g.version,'state',g.state,
    'subjectKind',g.subject_kind,'subjectId',g.subject_id,'granteeOrgId',g.grantee_org_id,
    'operations',g.operation_codes,'purposes',g.purpose_codes,'validFrom',g.valid_from,'validTo',g.valid_to,
    'sourceAuthority',origin.source_authority,'sourceConstraintsCurrent',ds_human_grant_origin_current(g.grant_id,ARRAY[]::uuid[]))::text
   FROM unique_affected a JOIN ds_resource_grant g ON g.grant_id=a.grant_id
   JOIN ds_human_grant_origin origin ON origin.grant_id=g.grant_id ORDER BY g.grant_id
   """,String.class,issuer);
  long subjects=jdbc.queryForObject(closure+" SELECT count(DISTINCT (g.subject_kind,g.subject_id)) FROM unique_affected a JOIN ds_resource_grant g ON g.grant_id=a.grant_id",Long.class,issuer);
  var tasks=jdbc.queryForList(closure+"""
   SELECT DISTINCT r.job_id AS "jobId",r.run_id AS "runId",r.org_id AS "orgId",r.run_status::text AS "status",
    d.definition->>'ioManifestSha256' AS "ioManifestSha256"
   FROM unique_affected a JOIN ds_job_execution_definition d ON EXISTS(
    SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.definition->'bindings')='array' THEN d.definition->'bindings' ELSE '[]'::jsonb END) b
    WHERE b->>'grantId'=a.grant_id::text AND b->>'grantVersion'=a.grant_version::text)
   JOIN job_run r ON r.run_id=d.run_id
   WHERE r.run_status IN('QUEUED','RUNNING') AND jsonb_exists(d.definition,'ioManifest') ORDER BY r.run_id
   """,issuer);
  var actor=auth.actor();var visible=new ArrayList<Map<String,Object>>();
  for(var row:tasks)if(actor.platformAdmin()||actor.orgId().equals(row.get("orgId"))){
   var safe=new LinkedHashMap<String,Object>();for(String key:List.of("jobId","runId","status"))safe.put(key,row.get(key));visible.add(safe);
  }
  var removed=new TreeSet<>(strings(before.path("effectiveOperations")));removed.removeAll(strings(after.path("effectiveOperations")));
  boolean moved=!before.path("org_id").equals(after.path("org_id"));
  var snapshots=new ArrayList<JsonNode>();for(String grant:grants)snapshots.add(parsed(grant));
  var out=new LinkedHashMap<String,Object>();out.put("referencedGrantRecordCount",grants.size());out.put("referencedSubjectCount",subjects);
  out.put("genericActiveTaskCount",tasks.size());out.put("visibleGenericTasks",visible);out.put("removedIssuerOperations",removed);
  out.put("issuerOrgChanged",moved);out.put("issuerShareCapabilityWillRemain",!moved&&strings(after.path("effectiveOperations")).contains("dataset.share.manage"));
  var fileImpact=fileSourceImpact(closure,issuer,actor,projection);out.put("fileSourceImpact",fileImpact);
  out.put("dependencySnapshotSha256",sha(List.of(snapshots,tasks,fileImpact)));out.put("allTaskKindsCovered",false);
  return out;
 }
 private ObjectNode impact(String action,UUID target,Object req){
  var result=mapper.createObjectNode();var users=mapper.createArrayNode();var blockers=new TreeSet<String>();
  var affectedUsers=affected(action,target);var dependencyProjection=indexFileProjection.capture(new HashSet<>(affectedUsers));
  for(UUID uid:affectedUsers){var before=state(uid);if(usable(before)&&!configured(before).equals(auth.actorFor(uid).operations()))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_POLICY_MISMATCH");var after=proposed(before,action,target,req);
   if(readOnlyConflict(after))blockers.add("READ_ONLY_CONFLICTING_ADDITIONAL_TYPE");
   var u=mapper.createObjectNode();var beforeSummary=summary(before);var afterSummary=summary(after);u.set("before",beforeSummary);u.set("after",afterSummary);u.set("delegatedResourceImpact",node(delegatedImpact(uid,beforeSummary,afterSummary,dependencyProjection)));users.add(u);
  }
  if(action.equals("TYPE_DELETE")&&((TypeDeletion)req).replacementTypeId()==null&&!users.isEmpty())blockers.add("TYPE_IN_USE_REPLACEMENT_REQUIRED");
  if(action.equals("QUALIFICATION")){
   long count=jdbc.queryForObject("""
    SELECT count(*) FROM ds_management_membership m JOIN fwd_user u ON u.user_id=m.user_id JOIN org o ON o.org_id=u.org_id
    WHERE m.qualification='PLATFORM_ADMIN' AND m.active AND u.account_state='ACTIVE' AND u.status_code IS NOT NULL
     AND NOT u.password_change_required AND u.credential_activated_at IS NOT NULL AND u.password_hash<>'' AND o.org_status=1
    """,Long.class);result.put("usablePlatformAdminCount",count);
   var q=(QualificationChange)req;if(q.qualification().equals("PLATFORM_ADMIN")&&!q.active()&&count==1&&jdbc.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM ds_management_membership m JOIN fwd_user u ON u.user_id=m.user_id JOIN org o ON o.org_id=u.org_id
     WHERE m.user_id=? AND m.qualification='PLATFORM_ADMIN' AND m.active AND u.account_state='ACTIVE' AND u.status_code IS NOT NULL
      AND NOT u.password_change_required AND u.credential_activated_at IS NOT NULL AND u.password_hash<>'' AND o.org_status=1)
    """,Boolean.class,target))blockers.add("LAST_PLATFORM_ADMIN_REQUIRED");
  }
  result.set("users",users);result.set("blockers",node(blockers));result.put("canApply",blockers.isEmpty());
  result.set("directTaskCoverage",node(Map.of("includedKinds",List.of("EXECUTION","INDEX_QUEUE","QUALITY","QA_REQUEST","RENDER_REQUEST"),
   "uncoveredKinds",List.of("DELIVERY","DOWNSTREAM_WORKER_AFTER_UNCONFIRMED_RESPONSE"),"allTaskKindsCovered",false)));
  if(action.startsWith("TYPE_")){var before=role(target,false);var after=before.deepCopy();if(action.equals("TYPE_UPDATE"))editRole(after,(TypeChange)req);else{after.put("type_state","DELETED");after.set("operations",mapper.createArrayNode());}result.set("typeBefore",before);result.set("typeAfter",after);if(req instanceof TypeDeletion d&&d.replacementTypeId()!=null)result.set("replacementType",role(d.replacementTypeId(),false));}
  return result;
 }
 public Map<String,Object> create(String action,UUID target,Object req){
  fence();var actor=auth.actor();var impact=impact(action,target,req);UUID id=UUID.randomUUID();String requestSha=sha(req),snapshotSha=sha(impact),reviewSha=sha(List.of(id,actor.userId(),actor.orgId(),actor.tokenVersion(),actor.permissionVersion(),auth.currentPolicyVersion(),action,target,requestSha,snapshotSha));
  jdbc.update("""
   INSERT INTO ds_identity_change_review(review_id,actor_user_id,actor_org_id,actor_token_version,actor_permission_version,policy_version,action,target_id,request_sha256,snapshot_sha256,review_sha256,request,impact)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
   """,id,actor.userId(),actor.orgId(),actor.tokenVersion(),actor.permissionVersion(),auth.currentPolicyVersion(),action,target,requestSha,snapshotSha,reviewSha,json(req),json(impact));
  event(actor,action,id,"IDENTITY_CHANGE_REVIEW_CREATED",impact);return view(row(id),actor,false);
 }
 private String header(String key){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)){fail(HttpStatus.PRECONDITION_REQUIRED,"IDENTITY_REVIEW_REQUIRED");return null;}
  var values=Collections.list(attrs.getRequest().getHeaders(key));if(values.isEmpty())fail(HttpStatus.PRECONDITION_REQUIRED,"IDENTITY_REVIEW_REQUIRED");if(values.size()!=1)fail(HttpStatus.BAD_REQUEST,"IDENTITY_REVIEW_HEADER_INVALID");return values.get(0);
 }
 public Receipt begin(String action,UUID target,Object req){
  fence();var actor=auth.actor();String value=header("X-Identity-Review-Id"),hash=header("X-Identity-Review-Sha256");UUID id;
  try{id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException();}catch(Exception e){fail(HttpStatus.BAD_REQUEST,"IDENTITY_REVIEW_HEADER_INVALID");return null;}
  if(!hash.matches("[0-9a-f]{64}")||!"true".equals(header("X-Identity-Review-Confirm")))fail(HttpStatus.BAD_REQUEST,"IDENTITY_REVIEW_HEADER_INVALID");
  var row=locked(id);owned(row,actor);if(!"REVIEWED".equals(row.get("state")))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_CLOSED");
  if(!Boolean.TRUE.equals(row.get("unexpired")))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_EXPIRED");
  if(!hash.equals(row.get("review_sha256"))||!action.equals(row.get("action"))||!target.equals(row.get("target_id"))||!sha(req).equals(row.get("request_sha256")))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_REQUEST_CHANGED");
  if(actor.tokenVersion()!=((Number)row.get("actor_token_version")).intValue()||actor.permissionVersion()!=((Number)row.get("actor_permission_version")).longValue()||auth.currentPolicyVersion()!=((Number)row.get("policy_version")).longValue())fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_STALE");
  var now=impact(action,target,req);if(!sha(now).equals(row.get("snapshot_sha256")))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_STALE");if(!now.path("canApply").asBoolean())fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_HAS_BLOCKERS");return new Receipt(id,actor,action,target,now);
 }
 private JsonNode comparable(JsonNode input){var n=((ObjectNode)input).deepCopy();for(String key:List.of("token_version","permission_version","status_code","sessions","tasks","effectiveOperations","configuredOperations"))n.remove(key);
  for(JsonNode r:n.path("roles"))((ObjectNode)r).remove("type_version");for(JsonNode q:n.path("qualifications")){((ObjectNode)q).remove("version");for(JsonNode r:q.path("delegated"))((ObjectNode)r).remove("type_version");}
  for(String key:List.of("roles","qualifications")){var values=new ArrayList<JsonNode>();n.path(key).forEach(values::add);values.sort(Comparator.comparing(v->v.path(key.equals("roles")?"role_id":"qualification").asText()));var array=mapper.createArrayNode();for(JsonNode v:values){if(key.equals("qualifications")){var ds=new ArrayList<JsonNode>();v.path("delegated").forEach(ds::add);ds.sort(Comparator.comparing(d->d.path("role_id").asText()));((ObjectNode)v).set("delegated",node(ds));}array.add(v);}n.set(key,array);}return sorted(n);
 }
 public void finish(Receipt receipt){
  for(JsonNode u:receipt.impact().path("users")){var expected=u.path("after");var before=u.path("before");UUID uid=uuid(before,"user_id");var current=state(uid);
   if(!comparable(expected).equals(comparable(current))||current.path("permission_version").asLong()<=before.path("permission_version").asLong()||current.path("token_version").asLong()<=before.path("token_version").asLong()||current.path("sessions").asLong()!=0||usable(current)&&!configured(expected).equals(auth.actorFor(uid).operations()))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_POSTCONDITION_FAILED");
  }
  if(receipt.action().startsWith("TYPE_")){var expected=((ObjectNode)receipt.impact().path("typeAfter")).deepCopy();var current=role(receipt.target(),false);expected.remove("type_version");current.remove("type_version");if(!sorted(expected).equals(sorted(current)))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_POSTCONDITION_FAILED");}
  jdbc.update("UPDATE ds_identity_change_review SET state='APPLIED',closed_at=clock_timestamp() WHERE review_id=? AND state='REVIEWED'",receipt.id());event(receipt.actor(),receipt.action(),receipt.id(),"IDENTITY_CHANGE_REVIEW_APPLIED",receipt.impact());
 }
 private Map<String,Object> row(UUID id){var rows=jdbc.queryForList("SELECT *,expires_at>clock_timestamp() AS unexpired FROM ds_identity_change_review WHERE review_id=?",id);if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"IDENTITY_REVIEW_NOT_AVAILABLE");return rows.get(0);}
 private Map<String,Object> locked(UUID id){var rows=jdbc.queryForList("SELECT *,expires_at>clock_timestamp() AS unexpired FROM ds_identity_change_review WHERE review_id=? FOR UPDATE",id);if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"IDENTITY_REVIEW_NOT_AVAILABLE");return rows.get(0);}
 private void owned(Map<String,Object> row,AuthorizationService.Actor actor){if(!actor.userId().equals(row.get("actor_user_id"))||!actor.orgId().equals(row.get("actor_org_id")))fail(HttpStatus.NOT_FOUND,"IDENTITY_REVIEW_NOT_AVAILABLE");}
 private void scopeReviewRole(ObjectNode role,String org){
  if(!org.equals(role.path("org_id").asText()))role.putNull("org_id");
  role.set("applicable_org_ids",node(strings(role.path("applicable_org_ids")).contains(org)?List.of(org):List.of()));
 }
 private JsonNode visibleImpact(JsonNode impact,AuthorizationService.Actor actor){
  if(actor.platformAdmin())return impact;
  String org=actor.orgId().toString();
  for(JsonNode user:impact.path("users")){
   for(String snapshot:List.of("before","after")){
    JsonNode account=user.path(snapshot);
    if(!org.equals(account.path("org_id").asText()))fail(HttpStatus.NOT_FOUND,"IDENTITY_REVIEW_NOT_AVAILABLE");
    var tasks=mapper.createArrayNode();for(JsonNode task:account.path("tasks"))if(org.equals(task.path("orgId").asText())){
     var visible=task.deepCopy();((ObjectNode)visible).remove("orgId");tasks.add(visible);
    }((ObjectNode)account).set("tasks",tasks);
    for(JsonNode role:account.path("roles"))scopeReviewRole((ObjectNode)role,org);
    for(JsonNode qualification:account.path("qualifications")){
     for(JsonNode role:qualification.path("delegated"))scopeReviewRole((ObjectNode)role,org);
    }
   }
  }
  return impact;
 }
 private Map<String,Object> view(Map<String,Object> row,AuthorizationService.Actor actor,boolean checkScope){owned(row,actor);if(checkScope){String action=(String)row.get("action");if(action.startsWith("TYPE_")||action.equals("QUALIFICATION"))auth.requirePlatformManagement(op(action));else{UUID target=(UUID)row.get("target_id");var orgs=jdbc.queryForList("SELECT org_id FROM fwd_user WHERE user_id=?",UUID.class,target);if(orgs.size()!=1)fail(HttpStatus.NOT_FOUND,"MEMBER_SCOPE_NOT_AVAILABLE");auth.requireMemberManagement(orgs.get(0),op(action));auth.requireNoManagementEscalation(actor,target);}}
  var out=new LinkedHashMap<String,Object>();for(String k:List.of("review_id","review_sha256","action","target_id","state"))out.put(k,row.get(k));
  var impact=visibleImpact(parsed(row.get("impact")),actor);
  out.put("expiresAt",((Timestamp)row.get("expires_at")).toInstant().toString());out.put("impact",impact);
  out.put("canApply","REVIEWED".equals(row.get("state"))&&Boolean.TRUE.equals(row.get("unexpired"))&&impact.path("canApply").asBoolean());return out;}
 @Transactional public Map<String,Object> get(UUID id){fence();return view(row(id),auth.actor(),true);}
 @Transactional public Map<String,Object> cancel(UUID id,String reviewSha){fence();var actor=auth.actor();var row=locked(id);owned(row,actor);if(reviewSha==null||!reviewSha.equals(row.get("review_sha256")))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_REQUEST_CHANGED");if(!"REVIEWED".equals(row.get("state")))fail(HttpStatus.CONFLICT,"IDENTITY_REVIEW_CLOSED");jdbc.update("UPDATE ds_identity_change_review SET state='CANCELLED',closed_at=clock_timestamp() WHERE review_id=?",id);event(actor,(String)row.get("action"),id,"IDENTITY_CHANGE_REVIEW_CANCELLED",parsed(row.get("impact")));return Map.of("state","CANCELLED");}
 private void event(AuthorizationService.Actor actor,String action,UUID id,String reason,JsonNode impact){audit.record(actor.userId(),actor.orgId(),op(action),"IDENTITY_CHANGE_REVIEW",id.toString(),"SUCCESS",reason,Map.of("reviewId",id),null,Map.of("count",impact.path("users").size(),"state",reason));}
}
