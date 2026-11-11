package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

/** Resolves only a managed parent's materialized snapshot. Root coordinates are provenance. */
@Service
public class ControlledViewSourceLineage {
 private final JdbcTemplate db;private final ObjectMapper json;
 public ControlledViewSourceLineage(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 private static UUID id(Object x){return x==null?null:UUID.fromString(x.toString());}
 private static void deny(){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"CONTROLLED_VIEW_PARENT_NOT_AVAILABLE");}
 private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"CONTROLLED_VIEW_PARENT_METADATA_INVALID");}}
 private String canonical(Object value){return new String(ControlledViewWorkerScriptFactory.canonical(value instanceof JsonNode n?n:json.valueToTree(value)),java.nio.charset.StandardCharsets.UTF_8);}
 public Map<String,Object> ownedParent(UUID org,UUID dataset,UUID version,UUID requestedIndex){
  if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_controlled_view WHERE dataset_id=?)",Boolean.class,dataset)))return null;
  var rows=db.queryForList("""
   SELECT cv.view_id,cv.authorization_version,btrim(cv.rule_manifest_sha256) AS rule_sha,cv.source_dataset_id,cv.source_version_id,cv.source_raw_commit_id,
    d.raw_repo_id,c.commit_id AS raw_commit_id,c.manifest_object_key,btrim(c.manifest_sha256) AS manifest_sha,o.permission_version,
    p.publication_id,i.index_version_id,r.payload,r.payload_sha256
   FROM ds_controlled_view cv JOIN ds_controlled_view_publication p ON p.view_id=cv.view_id
   JOIN ds_controlled_view_publication_review r ON r.review_id=p.review_id
   JOIN ds_controlled_view_publication_state s ON s.publication_id=p.publication_id AND s.state='READY'
   JOIN ds_controlled_view_publication_index b ON b.publication_id=p.publication_id
   JOIN mm_dataset d ON d.dataset_id=cv.dataset_id AND d.org_id=cv.owner_org_id AND d.raw_repo_id=p.raw_repo_id
   JOIN org o ON o.org_id=d.org_id AND o.org_status=1
   JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id AND v.version_id=cv.version_id AND v.raw_commit_id=p.raw_commit_id
   JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id AND c.commit_status='PUBLISHED'
   JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_repo_id=d.index_repo_id
    AND i.index_version_id=cv.index_version_id AND i.index_version_id=b.index_version_id AND i.index_status='READY'
   JOIN artifact_commit ic ON ic.repo_id=i.index_repo_id AND ic.commit_id=i.index_commit_id AND ic.commit_id=b.index_commit_id AND ic.commit_status='PUBLISHED'
   WHERE d.dataset_id=? AND v.version_id=? AND d.org_id=? AND d.status='ACTIVE' AND cv.state='READY'
    AND p.dataset_id=d.dataset_id AND p.version_id=v.version_id AND p.raw_manifest_object_key=c.manifest_object_key AND p.raw_manifest_sha256=c.manifest_sha256
    AND controlled_view_publication_policy_current(d.dataset_id)
   """,dataset,version,org);
  if(rows.size()!=1)deny();var r=rows.get(0);UUID index=id(r.get("index_version_id"));
  if(requestedIndex!=null&&!requestedIndex.equals(index))deny();
  JsonNode review=parse(r.get("payload"));
  if(!ControlledViewPreviewAuthority.hash(review).equals(r.get("payload_sha256").toString().trim()))deny();
  Instant from=Instant.parse(review.path("providerValidFrom").asText());
  Instant to=review.hasNonNull("validTo")?Instant.parse(review.path("validTo").asText()):null;
  if(from.isAfter(Instant.now())||to!=null&&!to.isAfter(Instant.now()))deny();
  UUID rootDataset,rootVersion,rootCommit;String rootSha;long depth;
  var ancestor=db.queryForList("SELECT * FROM ds_controlled_view_parent WHERE child_view_id=?",r.get("view_id"));
  if(ancestor.size()==1){
   var a=ancestor.get(0);rootDataset=id(a.get("root_dataset_id"));rootVersion=id(a.get("root_version_id"));rootCommit=id(a.get("root_raw_commit_id"));
   rootSha=a.get("root_manifest_sha256").toString().trim();depth=((Number)a.get("depth")).longValue()+1;
  }else{
   if(!ancestor.isEmpty())deny();
   rootDataset=id(r.get("source_dataset_id"));rootVersion=id(r.get("source_version_id"));rootCommit=id(r.get("source_raw_commit_id"));
   JsonNode source=review.path("source");
   if(!rootDataset.toString().equals(source.path("datasetId").asText())||!rootVersion.toString().equals(source.path("versionId").asText())||
     !rootCommit.toString().equals(source.path("rawCommitId").asText()))deny();
   rootSha=source.path("manifestSha256").asText();depth=1;
   if(!Boolean.TRUE.equals(db.queryForObject("""
    SELECT EXISTS(SELECT 1 FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
     JOIN artifact_commit c ON c.repo_id=d.raw_repo_id AND c.commit_id=v.raw_commit_id
     WHERE d.dataset_id=? AND v.version_id=? AND c.commit_id=? AND c.commit_status='PUBLISHED' AND c.manifest_sha256=?
      AND NOT EXISTS(SELECT 1 FROM ds_controlled_view cv WHERE cv.dataset_id=d.dataset_id))
    """,Boolean.class,rootDataset,rootVersion,rootCommit,rootSha)))deny();
  }
  if(depth>15||!rootSha.matches("[a-f0-9]{64}"))deny();
  ObjectNode parent=json.createObjectNode();parent.put("schema","CONTROLLED_VIEW_PARENT_V1");
  parent.put("viewId",r.get("view_id").toString());parent.put("publicationId",r.get("publication_id").toString());
  parent.put("authorizationVersion",((Number)r.get("authorization_version")).longValue());parent.put("indexVersionId",index.toString());
  parent.put("ruleSha256",r.get("rule_sha").toString());parent.put("rootDatasetId",rootDataset.toString());parent.put("rootVersionId",rootVersion.toString());
  parent.put("rootRawCommitId",rootCommit.toString());parent.put("rootManifestSha256",rootSha);parent.put("depth",depth);
  parent.set("providerValidFrom",review.path("providerValidFrom").deepCopy());parent.set("validTo",review.path("validTo").deepCopy());
  var result=new TreeMap<String,Object>();result.put("datasetId",dataset.toString());result.put("versionId",version.toString());result.put("indexVersionId",index.toString());
  result.put("ownerOrgId",org.toString());result.put("ownerOrgVersion",r.get("permission_version"));result.put("rawRepoId",r.get("raw_repo_id").toString());
  result.put("rawCommitId",r.get("raw_commit_id").toString());result.put("manifestObjectKey",r.get("manifest_object_key"));result.put("manifestSha256",r.get("manifest_sha"));
  result.put("controlledParent",parent);return result;
 }
 public void requireSameOwnedSource(UUID org,JsonNode source){
  var current=ownedParent(org,id(source.path("datasetId").asText()),id(source.path("versionId").asText()),
   source.hasNonNull("indexVersionId")?id(source.path("indexVersionId").asText()):null);
  if(source.has("controlledParent")?(current==null||!canonical(current).equals(canonical(source))):current!=null)deny();
 }
 public UUID rootDataset(JsonNode source){return id(source.has("controlledParent")?source.path("controlledParent").path("rootDatasetId").asText():source.path("datasetId").asText());}
 public UUID rootVersion(JsonNode source){return id(source.has("controlledParent")?source.path("controlledParent").path("rootVersionId").asText():source.path("versionId").asText());}
 public void requirePeriod(JsonNode source,Instant providerFrom,Instant consumerFrom,Instant to){
  if(!source.has("controlledParent"))return;JsonNode parent=source.path("controlledParent");
  Instant from=Instant.parse(parent.path("providerValidFrom").asText());
  Instant end=parent.hasNonNull("validTo")?Instant.parse(parent.path("validTo").asText()):null;
  if(providerFrom.isBefore(from)||consumerFrom.isBefore(from)||(end!=null&&(to==null||to.isAfter(end))))
   throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PERIOD_OUTSIDE_PARENT");
 }
 public void attach(JsonNode plan,Set<String> required){
  JsonNode source=plan.path("source");if(!source.has("controlledParent"))return;
  requireSameOwnedSource(id(plan.path("actor").path("orgId").asText()),source);
  JsonNode p=source.path("controlledParent"),g=plan.path("sourceAuthority");
  db.update("""
   INSERT INTO ds_controlled_view_parent(child_view_id,parent_view_id,parent_publication_id,parent_dataset_id,parent_version_id,parent_index_version_id,
    parent_raw_commit_id,parent_authorization_version,parent_rule_sha256,root_dataset_id,root_version_id,root_raw_commit_id,root_manifest_sha256,
    source_grant_id,source_grant_version,required_source_operations,depth)
   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::varchar[],?)
   """,id(plan.path("viewId").asText()),id(p.path("viewId").asText()),id(p.path("publicationId").asText()),
    id(source.path("datasetId").asText()),id(source.path("versionId").asText()),id(source.path("indexVersionId").asText()),
    id(source.path("rawCommitId").asText()),p.path("authorizationVersion").asLong(),p.path("ruleSha256").asText(),
    id(p.path("rootDatasetId").asText()),id(p.path("rootVersionId").asText()),id(p.path("rootRawCommitId").asText()),p.path("rootManifestSha256").asText(),
    id(g.path("grantId").asText()),g.path("version").asLong(),"{"+String.join(",",new TreeSet<>(required))+"}",p.path("depth").asInt());
 }
}
