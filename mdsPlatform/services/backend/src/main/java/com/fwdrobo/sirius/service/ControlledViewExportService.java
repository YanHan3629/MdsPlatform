package com.fwdrobo.sirius.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.security.AuthorizationService;
import com.fwdrobo.sirius.security.AuthorizationAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
/** Exports only the already materialized, frozen files of a currently authorized view. */
@Service
public final class ControlledViewExportService {
 private static final String OP="data.view.export";
 private static final Set<String> PROJECTION_EXPORT=Set.of(OP);
 private static final Set<String> ORIGINAL_COPY_EXPORT=Set.of(OP,"data.raw.download");
 private static final int MAX_FILES=10000;
 private final AuthorizationService auth;private final AuthorizationAuditService audit;private final JdbcTemplate db;
 private final ManifestService manifests;private final FileService files;private final ObjectMapper json;
 private final Path temporaryRoot;private final long maximumBytes;
 private final Set<Archive> active=ConcurrentHashMap.newKeySet();private final Semaphore slots=new Semaphore(4);private volatile boolean stopped;
 public ControlledViewExportService(AuthorizationService auth,AuthorizationAuditService audit,JdbcTemplate db,
   ManifestService manifests,FileService files,ObjectMapper json,
   @Value("${mm.export.maximum-bytes:1073741824}") long maximumBytes,@Value("${mm.export.temporary-directory:${java.io.tmpdir}/mds-authorized-exports}") String temporaryRoot) {
  if(maximumBytes<=0||maximumBytes>4L*1024*1024*1024)throw new IllegalArgumentException("Invalid bounded export size");
  this.auth=auth;this.audit=audit;this.db=db;this.manifests=manifests;this.files=files;this.json=json;
  this.maximumBytes=maximumBytes;this.temporaryRoot=Path.of(temporaryRoot).toAbsolutePath().normalize();
 }
 public record ExportableView(UUID viewId,UUID datasetId,String datasetName,UUID versionId,String versionName,
    UUID indexVersionId,long authorizationVersion,String ruleVersion,JsonNode scopeSummary) {}
 private record Bound(ExportableView publicView,UUID owner,UUID rawRepo,UUID rawCommit,String ruleSha,Set<String> operations) {}
 private static final String SELECT="""
  SELECT cv.view_id,cv.dataset_id,cv.version_id,cv.index_version_id,cv.authorization_version,cv.rule_version,
    cv.rule_manifest_sha256,cv.private_rule_manifest,cv.owner_org_id,cv.public_scope_summary,d.dataset_name,d.raw_repo_id,
    v.version_name,v.raw_commit_id
  FROM ds_controlled_view cv JOIN mm_dataset d ON d.dataset_id=cv.dataset_id
  JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id AND v.version_id=cv.version_id
  JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_version_id=cv.index_version_id
  JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id
  JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id
  WHERE cv.state='READY' AND d.status='ACTIVE' AND cv.owner_org_id=d.org_id
    AND i.index_status='READY' AND raw.commit_status='PUBLISHED' AND idx.commit_status='PUBLISHED'
  """;
 private static ResponseStatusException fail(HttpStatus status,String code){return new ResponseStatusException(status,code);}
 private Bound bind(Map<String,Object> r) {
  try{
   var view=new ExportableView((UUID)r.get("view_id"),(UUID)r.get("dataset_id"),(String)r.get("dataset_name"),
     (UUID)r.get("version_id"),(String)r.get("version_name"),(UUID)r.get("index_version_id"),
     ((Number)r.get("authorization_version")).longValue(),(String)r.get("rule_version"),
     json.readTree(Objects.toString(r.get("public_scope_summary"),"{}")));
   String ruleSha=Objects.toString(r.get("rule_manifest_sha256"),"").trim();
   JsonNode rules=json.readTree(Objects.toString(r.get("private_rule_manifest"),"{}"));
   var projections=com.fwdrobo.sirius.security.ControlledViewPublicProjection.describe(rules,view.scopeSummary().path("selections"));
   JsonNode originalCopies=view.scopeSummary().get("hasOriginalByteCopies");
   if(originalCopies!=null&&!originalCopies.isBoolean())throw fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_METADATA_INVALID");
   // Legacy views without a verified projection or copy flag require both operations.
   boolean needsRaw=originalCopies==null?projections==null:originalCopies.booleanValue();
   if(projections!=null){
    if(!ruleSha.equals(com.fwdrobo.sirius.security.ControlledViewPreviewAuthority.hash(rules))||!view.scopeSummary().isObject())
     throw fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_RULE_IDENTITY_CHANGED");
    if(view.scopeSummary().has("projections")&&!com.fwdrobo.sirius.security.ControlledViewPreviewAuthority.hash(view.scopeSummary().path("projections")).equals(com.fwdrobo.sirius.security.ControlledViewPreviewAuthority.hash(projections)))
     throw fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PUBLIC_PROJECTION_CHANGED");
    ((com.fasterxml.jackson.databind.node.ObjectNode)view.scopeSummary()).set("projections",projections);
    for(JsonNode projection:projections)needsRaw|="COMPLETE_FILE".equals(projection.path("kind").asText());
   }
   return new Bound(view,(UUID)r.get("owner_org_id"),(UUID)r.get("raw_repo_id"),(UUID)r.get("raw_commit_id"),ruleSha,
     needsRaw?ORIGINAL_COPY_EXPORT:PROJECTION_EXPORT);
  }catch(Exception invalid){throw fail(HttpStatus.CONFLICT,"CONTROLLED_VIEW_METADATA_INVALID");}
 }
 public List<ExportableView> list() {
  var actor=auth.requireOperation(OP);
  var rows=db.queryForList(SELECT+"""
    AND EXISTS(SELECT 1 FROM ds_resource_grant g WHERE g.dataset_id=cv.dataset_id AND g.grantee_org_id=?
      AND ((g.subject_kind='USER' AND g.subject_id=?) OR (g.subject_kind='ORG' AND g.subject_id=?)))
    ORDER BY d.dataset_name,cv.view_id
   """,actor.orgId(),actor.userId(),actor.orgId());
  var result=new ArrayList<ExportableView>();
  for(var row:rows){var b=bind(row);var v=b.publicView();
   if(auth.canUseDataset(actor,v.datasetId(),v.versionId(),v.indexVersionId(),b.operations(),"GENERAL_READ"))result.add(v);
   if(result.size()>10000)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"CONTROLLED_VIEW_LIST_LIMIT");}
  return result;
 }
 private Bound authorized(UUID view,UUID version,UUID index) {
  var actor=auth.requireOperation(OP);
  var rows=db.queryForList(SELECT+" AND cv.view_id=? AND cv.version_id=? AND cv.index_version_id=?",view,version,index);
  if(rows.size()!=1)throw fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
  var b=bind(rows.get(0));var v=b.publicView();
  auth.requireDataset(actor,v.datasetId(),v.versionId(),v.indexVersionId(),b.operations(),"GENERAL_READ");return b;
 }
 private void check(Bound b,AuthorizationService.ResourceLease lease) {
  if(stopped)throw fail(HttpStatus.SERVICE_UNAVAILABLE,"EXPORT_SERVICE_STOPPING");
  auth.revalidate(lease);var v=b.publicView();
  boolean unchanged=Boolean.TRUE.equals(db.queryForObject("""
   SELECT EXISTS(SELECT 1 FROM ds_controlled_view cv JOIN mm_dataset d ON d.dataset_id=cv.dataset_id
    JOIN mm_dataset_version ver ON ver.version_id=cv.version_id AND ver.dataset_id=d.dataset_id
    WHERE cv.view_id=? AND cv.dataset_id=? AND cv.version_id=? AND cv.index_version_id=?
     AND cv.state='READY' AND cv.authorization_version=? AND cv.rule_manifest_sha256=?
     AND cv.owner_org_id=? AND d.raw_repo_id=? AND ver.raw_commit_id=?)
   """,Boolean.class,v.viewId(),v.datasetId(),v.versionId(),v.indexVersionId(),v.authorizationVersion(),b.ruleSha(),b.owner(),b.rawRepo(),b.rawCommit()));
  if(!unchanged)throw fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
 }
 private Map<String,Object> scope(Bound b) {var v=b.publicView();return Map.of("viewId",v.viewId(),"datasetId",v.datasetId(),
   "versionId",v.versionId(),"indexVersionId",v.indexVersionId(),"authorizationVersion",v.authorizationVersion());}
 private void event(AuthorizationService.Actor actor,Bound b,String outcome,String reason,Map<String,?> after) {
  audit.record(actor.userId(),actor.orgId(),OP,"CONTROLLED_VIEW",b.publicView().viewId().toString(),outcome,reason,scope(b),null,after);
 }
 private static String relative(String logical) {
  if(logical==null||logical.length()>2048||!logical.startsWith("/corpus/")&&!logical.startsWith("/catalog/")||
    logical.indexOf('\\')>=0||logical.chars().anyMatch(c->c<32)||logical.contains("//"))throw fail(HttpStatus.CONFLICT,"VIEW_FILE_PATH_INVALID");
  String relative=logical.substring(1);
  if(Arrays.stream(relative.split("/",-1)).anyMatch(p->p.isBlank()||p.equals(".")||p.equals("..")))throw fail(HttpStatus.CONFLICT,"VIEW_FILE_PATH_INVALID");
  return relative;
 }
 private record Entry(String path,String sha,long bytes) {}
 private List<Entry> entries(Bound b) throws Exception {
  var listed=manifests.getManifest(b.rawRepo(),b.rawCommit()).getFiles();
  if(listed==null||listed.isEmpty()||listed.size()>MAX_FILES)throw fail(HttpStatus.CONFLICT,"VIEW_FILE_MANIFEST_INVALID");
  var ids=new LinkedHashMap<UUID,String>();var paths=new HashSet<String>();
  for(var f:listed){
   if(f==null||f.getFileId()==null)throw fail(HttpStatus.CONFLICT,"VIEW_FILE_MANIFEST_INVALID");
   relative(f.getPath());
   if(ids.putIfAbsent(f.getFileId(),f.getPath())!=null||!paths.add(f.getPath()))throw fail(HttpStatus.CONFLICT,"VIEW_FILE_MANIFEST_INVALID");
  }
  String array="{"+String.join(",",ids.keySet().stream().map(UUID::toString).toList())+"}";
  var rows=db.queryForList("""
   SELECT f.file_id,f.commit_id,f.logical_path,f.sha256,f.size_bytes,f.upload_state FROM artifact_file f
    WHERE f.file_id=ANY(?::uuid[]) AND EXISTS(SELECT 1 FROM mm_asset a
      WHERE a.file_id=f.file_id AND a.dataset_version_id=? AND a.status='ACTIVE')
   """,array,b.publicView().versionId());
  if(rows.size()!=ids.size())throw fail(HttpStatus.CONFLICT,"VIEW_FILE_BINDING_INVALID");
  var result=new ArrayList<Entry>();long total=0;
  for(var row:rows){
   UUID id=(UUID)row.get("file_id");String path=(String)row.get("logical_path");
   String sha=Objects.toString(row.get("sha256"),"").trim();long bytes=((Number)row.get("size_bytes")).longValue();
   // A controlled materialization never borrows an ancestor/main-table file ID.
   if(!b.rawCommit().equals(row.get("commit_id"))||!Objects.equals(ids.get(id),path)||
      !"READY".equals(row.get("upload_state"))||!sha.matches("[a-f0-9]{64}")||bytes<0)throw fail(HttpStatus.CONFLICT,"VIEW_FILE_BINDING_INVALID");
   total=Math.addExact(total,bytes);if(total>maximumBytes)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"VIEW_EXPORT_SIZE_LIMIT");
   result.add(new Entry(path,sha,bytes));
  }
  result.sort(Comparator.comparing(Entry::path));return result;
 }
 private Path directory()throws IOException {
  Files.createDirectories(temporaryRoot);
  if(Files.getFileStore(temporaryRoot).supportsFileAttributeView("posix")){
   Files.setPosixFilePermissions(temporaryRoot,PosixFilePermissions.fromString("rwx------"));
   return Files.createTempDirectory(temporaryRoot,"export-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }
  return Files.createTempDirectory(temporaryRoot,"export-");
 }
 private static String sha(Path path)throws Exception{
  var digest=MessageDigest.getInstance("SHA-256");
  try(var in=Files.newInputStream(path)){byte[] block=new byte[65536];int count;while((count=in.read(block))!=-1)digest.update(block,0,count);}
  return HexFormat.of().formatHex(digest.digest());
 }
 public record Prepared(InputStream input,long bytes,String sha256,UUID viewId,long authorizationVersion,int fileCount,AutoCloseable headersOnlyClose,Consumer<String> transferFailed) {}
 public Prepared prepare(UUID view,UUID version,UUID index,long expectedVersion)throws Exception{
  Bound b=authorized(view,version,index);var v=b.publicView();
  if(expectedVersion!=v.authorizationVersion())throw fail(HttpStatus.CONFLICT,"VIEW_AUTHORIZATION_VERSION_CONFLICT");
  var lease=auth.lease(v.datasetId(),v.versionId(),v.indexVersionId(),b.operations(),"GENERAL_READ");var actor=lease.actor();
  Path dir=null;Archive archive=null;
  if(!slots.tryAcquire())throw fail(HttpStatus.TOO_MANY_REQUESTS,"VIEW_EXPORT_BUSY");
  try(var operation=auth.enterOperation(OP,"GENERAL_READ")){
   check(b,lease);var entries=entries(b);check(b,lease);dir=directory();
   Path output=dir.resolve("authorized-view.zip");archive=new Archive(dir,output,b,lease,actor);active.add(archive);
   try(var zip=new ZipOutputStream(Files.newOutputStream(output,StandardOpenOption.CREATE_NEW),StandardCharsets.UTF_8)){
    byte[] block=new byte[65536];
    for(var entry:entries){
     check(b,lease);ZipEntry part=new ZipEntry(relative(entry.path()));part.setTime(0);zip.putNextEntry(part);
     var digest=MessageDigest.getInstance("SHA-256");long copied=0;
     var stream=files.downloadContent(b.rawRepo(),b.rawCommit(),entry.path());
     try(var in=stream.inputStream()){
      if(stream.size()!=entry.bytes())throw fail(HttpStatus.CONFLICT,"VIEW_SOURCE_SIZE_MISMATCH");
      int count;while(true){
       check(b,lease);count=in.read(block);if(count==-1)break;
       copied=Math.addExact(copied,count);if(copied>entry.bytes())throw fail(HttpStatus.CONFLICT,"VIEW_SOURCE_SIZE_MISMATCH");
       digest.update(block,0,count);check(b,lease);zip.write(block,0,count);
      }
     }
     if(copied!=entry.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(entry.sha()))throw fail(HttpStatus.CONFLICT,"VIEW_SOURCE_HASH_MISMATCH");
     zip.closeEntry();check(b,lease);
    }
    var metadata=new LinkedHashMap<String,Object>();
    metadata.put("schemaVersion","mds-authorized-view-export-1");metadata.put("kind","AUTHORIZED_VIEW_EXPORT");metadata.put("label","授权视图导出");
    metadata.put("viewId",v.viewId());metadata.put("datasetId",v.datasetId());metadata.put("versionId",v.versionId());metadata.put("indexVersionId",v.indexVersionId());
    metadata.put("authorizationVersion",v.authorizationVersion());metadata.put("ruleVersion",v.ruleVersion());metadata.put("scopeSummary",v.scopeSummary());metadata.put("createdAt",Instant.now().toString());
    metadata.put("files",entries.stream().map(e->Map.of("path",e.path(),"sha256",e.sha(),"sizeBytes",e.bytes())).toList());
    ZipEntry manifest=new ZipEntry("authorized-view-export.json");manifest.setTime(0);zip.putNextEntry(manifest);zip.write(json.writeValueAsBytes(metadata));zip.closeEntry();
   }
   check(b,lease);long size=Files.size(output);String checksum=sha(output);check(b,lease);
   event(actor,b,"SUCCESS","EXPORT_ARCHIVE_PREPARED",Map.of("files",entries.size(),"bytes",size,"sha256",checksum));
   Archive owned=archive;owned.input=Files.newInputStream(output);
   return new Prepared(owned,size,checksum,v.viewId(),v.authorizationVersion(),entries.size(),()->{owned.headersOnly=true;owned.close();},reason->{if(owned.stopReason==null)owned.stopReason=reason;});
  }catch(Exception failure){
   if(archive!=null){try{archive.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}}
   else {slots.release();if(dir!=null){Files.deleteIfExists(dir.resolve("authorized-view.zip"));Files.deleteIfExists(dir);}}
   throw failure;
  }
 }
 private final class Archive extends InputStream {
  final Path directory,path;final Bound binding;final AuthorizationService.ResourceLease lease;final AuthorizationService.Actor actor;
  final AtomicBoolean closed=new AtomicBoolean();InputStream input;boolean complete,headersOnly,authorizationStopped;long bytes,checkedBytes,lastCheck;String stopReason;
  Archive(Path directory,Path path,Bound binding,AuthorizationService.ResourceLease lease,AuthorizationService.Actor actor){this.directory=directory;this.path=path;this.binding=binding;this.lease=lease;this.actor=actor;}
  private void validate(boolean force)throws IOException{
   if(closed.get())throw new IOException("Export is closed");
   if(!force&&lastCheck!=0&&bytes-checkedBytes<65536&&System.nanoTime()-lastCheck<1_000_000_000L)return;
   try{check(binding,lease);checkedBytes=bytes;lastCheck=System.nanoTime();}
   catch(ResponseStatusException denied){stopReason="EXPORT_AUTHORIZATION_CHANGED";authorizationStopped=true;throw new IOException("Export authorization is no longer valid",denied);}
  }
  @Override public int read()throws IOException{byte[] one=new byte[1];int count=read(one,0,1);return count<0?-1:one[0]&255;}
  @Override public int read(byte[] buffer,int offset,int length)throws IOException{
   Objects.checkFromIndexSize(offset,length,buffer.length);if(length==0)return 0;
   validate(false);int count=input.read(buffer,offset,Math.min(length,65536));if(count>0)bytes+=count;
   if(count==-1){validate(true);complete=true;}return count;
  }
  @Override public void close()throws IOException{
   if(!closed.compareAndSet(false,true))return;IOException failed=null;
   try{if(input!=null)input.close();}catch(IOException e){failed=e;}
   try{Files.deleteIfExists(path);Files.deleteIfExists(directory);}catch(IOException e){if(failed==null)failed=e;}
   active.remove(this);slots.release();String reason=stopReason!=null?stopReason:headersOnly?"EXPORT_HEADERS_ONLY":complete?"EXPORT_BODY_SENT":"EXPORT_BODY_NOT_COMPLETED";
   try{event(actor,binding,authorizationStopped?"DENY":stopReason!=null?"FAILURE":headersOnly||complete?"SUCCESS":"FAILURE",reason,Map.of("bytesRead",bytes));}
   catch(RuntimeException auditFailure){if(failed==null)failed=new IOException("Export audit could not be persisted",auditFailure);}
   if(failed!=null)throw failed;
  }
 }
 @PreDestroy public void stop(){stopped=true;for(var archive:List.copyOf(active)){try{archive.close();}catch(IOException e){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Export cleanup failed: "+e.getClass().getSimpleName());}}}
}
