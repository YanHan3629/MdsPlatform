package com.fwdrobo.sirius.dataspace;
import com.fwdrobo.sirius.service.ControlledViewExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
@RestController
@RequestMapping("/api/data-space/controlled-views")
public final class ControlledViewExportController {
 private final ControlledViewExportService exports;
 public ControlledViewExportController(ControlledViewExportService exports){this.exports=exports;}
 @GetMapping public ResponseEntity<List<ControlledViewExportService.ExportableView>> list(){return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store, private").body(exports.list());}
 private long expected(HttpServletRequest request){
  var values=Collections.list(request.getHeaders(HttpHeaders.IF_MATCH));
  if(values.isEmpty())throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"VIEW_AUTHORIZATION_VERSION_REQUIRED");
  if(values.size()!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"VIEW_AUTHORIZATION_VERSION_INVALID");
  String value=values.get(0);if(value.startsWith("\"")&&value.endsWith("\"")&&value.length()>=2)value=value.substring(1,value.length()-1);
  if(!value.matches("[1-9][0-9]{0,18}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"VIEW_AUTHORIZATION_VERSION_INVALID");
  try{return Long.parseLong(value);}catch(NumberFormatException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"VIEW_AUTHORIZATION_VERSION_INVALID");}
 }
 @GetMapping("/{viewId}/{versionId}/{indexVersionId}/export")
 public void export(@PathVariable UUID viewId,@PathVariable UUID versionId,@PathVariable UUID indexVersionId,
    HttpServletRequest request,HttpServletResponse response)throws Exception{
  var p=exports.prepare(viewId,versionId,indexVersionId,expected(request));
  response.setContentType("application/zip");response.setContentLengthLong(p.bytes());
  response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store, private");response.setHeader("X-Content-Type-Options","nosniff");
  response.setHeader("Accept-Ranges","none");response.setHeader("X-Export-Sha256",p.sha256());response.setHeader("X-View-Id",p.viewId().toString());
  response.setHeader("X-View-Authorization-Version",Long.toString(p.authorizationVersion()));response.setHeader("X-Export-File-Count",Integer.toString(p.fileCount()));
  response.setHeader(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("authorized-view-"+p.viewId()+".zip",StandardCharsets.UTF_8).build().toString());
  if("HEAD".equals(request.getMethod())){p.headersOnlyClose().close();return;}
  try(var in=p.input()){
   try{
    var out=response.getOutputStream();byte[] block=new byte[65536];int count;
    while((count=in.read(block))!=-1)out.write(block,0,count);
    out.flush();
   }catch(Exception stopped){p.transferFailed().accept("EXPORT_OUTPUT_STOPPED");throw stopped;}
  }
 }
}
