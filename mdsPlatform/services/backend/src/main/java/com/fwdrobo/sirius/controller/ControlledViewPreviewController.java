package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.ControlledViewPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/controlled-view-previews")
public class ControlledViewPreviewController {
 @org.springframework.beans.factory.annotation.Autowired private com.fwdrobo.sirius.security.ControlledViewPreviewReadService reads;
 private final ControlledViewPreviewService previews;
 public ControlledViewPreviewController(ControlledViewPreviewService previews){this.previews=previews;}
 @PostMapping public Object create(@RequestBody ControlledViewPreviewService.CreateRequest body,HttpServletRequest request)throws Exception{
  String value=request.getHeader("Authorization");return previews.create(body,value!=null&&value.startsWith("Bearer ")?value.substring(7).trim():"");
 }
 @GetMapping public Object list(@RequestParam(required=false) UUID proposalId,@RequestParam(required=false) Integer maximum){return previews.list(proposalId,maximum);}
 @GetMapping("/{attemptId}") public Object get(@PathVariable UUID attemptId){return previews.get(attemptId);}
 @GetMapping("/{attemptId}/files/content") public void file(@PathVariable UUID attemptId,@RequestParam String path,HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws Exception{reads.write(attemptId,path,request,response);}
 @PostMapping("/{attemptId}/reconcile") public Object reconcile(@PathVariable UUID attemptId)throws Exception{return previews.reconcile(attemptId);}
 @PutMapping("/{attemptId}/receipt") public ResponseEntity<Void> receipt(@PathVariable UUID attemptId,@RequestBody byte[] body)throws Exception{
  previews.receipt(attemptId,body);return ResponseEntity.noContent().build();
 }
}
