package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fwdrobo.sirius.service.ManifestService;
import com.fwdrobo.sirius.service.MinioService;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Live, exact-file readback of a verified private projection. Never serves its original source. */
@Service
public final class ControlledViewPreviewReadService {
 private static final long MAXIMUM=671088640L;
 private final ControlledViewPreviewAuthority authority;private final JdbcTemplate db;private final MinioService objects;
 private final ManifestService manifests;private final AuthorizationAuditService audit;private final Path temporaryRoot;
 private final Semaphore slots=new Semaphore(2);private final Set<Prepared> active=ConcurrentHashMap.newKeySet();private volatile boolean stopped;
 public ControlledViewPreviewReadService(ControlledViewPreviewAuthority authority,JdbcTemplate db,MinioService objects,
  ManifestService manifests,AuthorizationAuditService audit,
  @Value("${mm.controlled-view.preview-read-temp:${java.io.tmpdir}/mds-private-preview-reads}") String temporaryRoot){
  this.authority=authority;this.db=db;this.objects=objects;this.manifests=manifests;this.audit=audit;
  this.temporaryRoot=Path.of(temporaryRoot).toAbsolutePath().normalize();
 }
 private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
 private static String path(String value){
  String p=value!=null&&value.startsWith("/")?value.substring(1):value;
  if(p==null||p.isEmpty()||p.length()>1024||!Normalizer.isNormalized(p,Normalizer.Form.NFC)||p.contains("\\")||p.contains(":")||
   p.codePoints().anyMatch(c->c<32)||!(p.startsWith("corpus/")||p.startsWith("catalog/")))fail(HttpStatus.BAD_REQUEST,"PRIVATE_PREVIEW_PATH_INVALID");
  for(String part:p.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))fail(HttpStatus.BAD_REQUEST,"PRIVATE_PREVIEW_PATH_INVALID");
  return p;
 }
 private record Bound(UUID attempt,UUID proposal,UUID repo,UUID commit,String path,String key,long bytes,String sha,String operation,String ruleSha,UUID actorUser,UUID actorOrg,Claims parent){}
 private void check(Bound b){
  if(stopped)fail(HttpStatus.SERVICE_UNAVAILABLE,"PRIVATE_PREVIEW_READ_STOPPING");
  var a=authority.revalidateRead(b.attempt(),b.parent(),b.path(),b.operation());
  if(!a.proposalId().equals(b.proposal())||!a.outputRepo().equals(b.repo())||!a.outputCommit().equals(b.commit())||
   !a.prepared().ruleSha256().equals(b.ruleSha()))fail(HttpStatus.NOT_FOUND,"PRIVATE_PREVIEW_READ_SCOPE_CHANGED");
 }
 private Bound bind(UUID attempt,String requested)throws Exception{
  var a=authority.forHuman(attempt);String p=path(requested);authority.requireVerifiedRepository(a,a.outputRepo(),a.outputCommit());
  var state=db.queryForMap("SELECT verification_summary FROM ds_controlled_view_preview_state WHERE attempt_id=?",attempt);
  JsonNode summary=new com.fasterxml.jackson.databind.ObjectMapper().readTree(state.get("verification_summary").toString());
  JsonNode expected=null;
  for(JsonNode f:summary.path("publicFiles"))if(p.equals(f.path("path").asText())){if(expected!=null)fail(HttpStatus.CONFLICT,"PRIVATE_PREVIEW_PATH_COLLISION");expected=f;}
  if(expected==null)fail(HttpStatus.NOT_FOUND,"PRIVATE_PREVIEW_FILE_NOT_AVAILABLE");
  String operation=authority.readOperation(a,p);if(operation==null)fail(HttpStatus.FORBIDDEN,"PRIVATE_PREVIEW_FILE_OPERATION_REQUIRED");
  var rows=db.queryForList("SELECT file_id,file_key,size_bytes,btrim(sha256) AS sha,upload_state FROM artifact_file WHERE commit_id=? AND logical_path=?",a.outputCommit(),"/"+p);
  if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"PRIVATE_PREVIEW_FILE_NOT_AVAILABLE");var row=rows.get(0);
  long bytes=((Number)row.get("size_bytes")).longValue();String sha=Objects.toString(row.get("sha"),"");
  if(!"READY".equals(row.get("upload_state"))||bytes<0||bytes>MAXIMUM||bytes!=expected.path("bytes").asLong(-1)||
   !sha.matches("[a-f0-9]{64}")||!sha.equals(expected.path("sha256").asText()))fail(HttpStatus.CONFLICT,"PRIVATE_PREVIEW_FILE_IDENTITY_CHANGED");
  boolean member=false;for(var f:manifests.getManifest(a.outputRepo(),a.outputCommit()).getFiles())
   if(("/"+p).equals(f.getPath())&&row.get("file_id").equals(f.getFileId())){if(member)fail(HttpStatus.CONFLICT,"PRIVATE_PREVIEW_PATH_COLLISION");member=true;}
  if(!member)fail(HttpStatus.CONFLICT,"PRIVATE_PREVIEW_FILE_NOT_IN_VERIFIED_MANIFEST");
  var b=new Bound(attempt,a.proposalId(),a.outputRepo(),a.outputCommit(),p,row.get("file_key").toString(),bytes,sha,operation,a.prepared().ruleSha256(),a.actor().userId(),a.actor().orgId(),authority.interactive());
  check(b);return b;
 }
 private void event(Bound b,String outcome,String reason,long sent){
  audit.record(b.actorUser(),b.actorOrg(),b.operation(),
   "CONTROLLED_VIEW_PRIVATE_PREVIEW",b.attempt().toString(),outcome,reason,
   Map.of("proposalId",b.proposal(),"attemptId",b.attempt(),"ruleSha256",b.ruleSha(),"path",b.path()),null,Map.of("bytesSent",sent));
 }
 private Path directory()throws IOException{
  if(Files.isSymbolicLink(temporaryRoot))throw new IOException("Private preview read root is not a directory");
  Files.createDirectories(temporaryRoot);
  if(!temporaryRoot.toRealPath().equals(temporaryRoot))throw new IOException("Private preview read root changed");
  if(Files.getFileStore(temporaryRoot).supportsFileAttributeView("posix")){
   Files.setPosixFilePermissions(temporaryRoot,PosixFilePermissions.fromString("rwx------"));
   return Files.createTempDirectory(temporaryRoot,"preview-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
  }
  return Files.createTempDirectory(temporaryRoot,"preview-");
 }
 private Prepared prepare(Bound b)throws Exception{
  if(!slots.tryAcquire())fail(HttpStatus.TOO_MANY_REQUESTS,"PRIVATE_PREVIEW_READ_BUSY");
  Path dir=null;Prepared file=null;
  try{
   check(b);dir=directory();file=new Prepared(dir,dir.resolve("verified-file"),b);active.add(file);
   var digest=MessageDigest.getInstance("SHA-256");long bytes=0,lastCheck=System.nanoTime(),checked=0;
   try(var in=objects.getObject(b.key());var out=Files.newOutputStream(file.path,StandardOpenOption.CREATE_NEW)){
    byte[] block=new byte[65536];int n;
    while((n=in.read(block))!=-1){
     bytes=Math.addExact(bytes,n);if(bytes>b.bytes())fail(HttpStatus.CONFLICT,"PRIVATE_PREVIEW_STORED_BYTES_CHANGED");
     if(bytes-checked>=4194304L||System.nanoTime()-lastCheck>=1_000_000_000L){check(b);checked=bytes;lastCheck=System.nanoTime();}
     digest.update(block,0,n);out.write(block,0,n);
    }
   }
   if(bytes!=b.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(b.sha()))fail(HttpStatus.CONFLICT,"PRIVATE_PREVIEW_STORED_BYTES_CHANGED");
   check(b);file.input=Files.newInputStream(file.path);return file;
  }catch(Exception e){
   if(file!=null)try{file.close();}catch(IOException cleanup){e.addSuppressed(cleanup);}
   else {slots.release();if(dir!=null)Files.deleteIfExists(dir);}
   throw e;
  }
 }
 private static long[] range(String value,long size){
  if(value==null)return new long[]{0,size};
  try{
   if(size==0||!value.matches("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)"))throw new IllegalArgumentException();
   String[] parts=value.substring(6).split("-",-1);long first,last;
   if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();first=Math.max(0,size-suffix);last=size-1;}
   else{first=Long.parseLong(parts[0]);last=parts[1].isEmpty()?size-1:Math.min(size-1,Long.parseLong(parts[1]));}
   if(first>=size||last<first)throw new IllegalArgumentException();return new long[]{first,last-first+1};
  }catch(RuntimeException invalid){fail(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,"PRIVATE_PREVIEW_RANGE_INVALID");return null;}
 }
 public void write(UUID attempt,String requested,HttpServletRequest request,HttpServletResponse response)throws Exception{
  if(request.getParameterValues("path")==null||request.getParameterValues("path").length!=1)fail(HttpStatus.BAD_REQUEST,"PRIVATE_PREVIEW_ONE_PATH_REQUIRED");
  Bound b=bind(attempt,requested);String value=request.getHeader("Range");long[] span=range(value,b.bytes());
  boolean head="HEAD".equals(request.getMethod());
  try(var file=head?null:prepare(b)){
   check(b);response.setStatus(value==null?200:206);response.setHeader("Cache-Control","no-store, private, max-age=0");
   response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("Content-Security-Policy","sandbox");
   response.setHeader("Accept-Ranges","bytes");response.setContentType("application/octet-stream");
   response.setHeader("Content-Disposition",ContentDisposition.attachment().filename("授权视图预览-"+b.path().substring(b.path().lastIndexOf('/')+1),StandardCharsets.UTF_8).build().toString());
   response.setHeader("ETag","\""+b.sha()+"\"");response.setHeader("X-Export-Sha256",b.sha());
   response.setHeader("X-Authorized-View-Kind","PRIVATE_PREVIEW");response.setHeader("X-Authorized-View-Id",b.proposal().toString());
   response.setHeader("X-Authorized-View-Rule-Sha256",b.ruleSha());response.setHeader("X-Authorized-View-Rule-Url","/api/controlled-view-proposals/"+b.proposal());
   response.setContentLengthLong(span[1]);
   if(value!=null)response.setHeader("Content-Range","bytes "+span[0]+"-"+(span[0]+span[1]-1)+"/"+b.bytes());
   if(head){event(b,"SUCCESS","PRIVATE_PREVIEW_HEADERS_ONLY",0);return;}
   file.skipNBytes(span[0]);long left=span[1];byte[] block=new byte[65536];
   try(var out=response.getOutputStream()){
    while(left>0){int n=file.read(block,0,(int)Math.min(left,block.length));if(n<0)throw new EOFException("Private preview read ended early");out.write(block,0,n);left-=n;file.sent+=n;}
    file.validate(true);file.complete=true;
   }
  }
 }
 private final class Prepared extends InputStream{
  final Path directory,path;final Bound bound;final AtomicBoolean closed=new AtomicBoolean();InputStream input;
  long readBytes,checkedBytes,lastCheck,sent;boolean complete;
  Prepared(Path directory,Path path,Bound bound){this.directory=directory;this.path=path;this.bound=bound;}
  private void validate(boolean force)throws IOException{
   if(closed.get())throw new IOException("Private preview read is closed");
   if(!force&&lastCheck!=0&&readBytes-checkedBytes<65536&&System.nanoTime()-lastCheck<1_000_000_000L)return;
   try{check(bound);checkedBytes=readBytes;lastCheck=System.nanoTime();}
   catch(RuntimeException denied){throw new IOException("Private preview authority is no longer valid",denied);}
  }
  public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
  public int read(byte[] block,int start,int length)throws IOException{
   Objects.checkFromIndexSize(start,length,block.length);if(length==0)return 0;validate(false);
   int n=input.read(block,start,Math.min(length,65536));if(n>0)readBytes+=n;else if(n<0)validate(true);return n;
  }
  public void close()throws IOException{
   if(!closed.compareAndSet(false,true))return;IOException error=null;
   try{if(input!=null)input.close();}catch(IOException e){error=e;}
   try{Files.deleteIfExists(path);Files.deleteIfExists(directory);}catch(IOException e){if(error==null)error=e;else error.addSuppressed(e);}
   active.remove(this);slots.release();
   try{event(bound,complete?"SUCCESS":"DENY",complete?"PRIVATE_PREVIEW_BODY_SENT":"PRIVATE_PREVIEW_BODY_NOT_COMPLETED",sent);}
   catch(RuntimeException auditFailure){if(error==null)error=new IOException("Private preview read audit failed",auditFailure);}
   if(error!=null)throw error;
  }
 }
 @PreDestroy public void stop(){stopped=true;for(var file:List.copyOf(active))try{file.close();}catch(IOException error){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Private preview cleanup failed: "+error.getClass().getSimpleName());}}
}
