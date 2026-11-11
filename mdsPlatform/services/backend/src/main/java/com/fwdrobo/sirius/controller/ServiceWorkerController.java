package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.ServiceScopeService;
import com.fwdrobo.sirius.service.MmIndexService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/mm/service")
public class ServiceWorkerController {
 private final ServiceScopeService scope;private final MmIndexService indexes;
 public ServiceWorkerController(ServiceScopeService scope,MmIndexService indexes){this.scope=scope;this.indexes=indexes;}
 @GetMapping("/authority") public Object authority(){return scope.authority();}
 @GetMapping("/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/bundle")
 public Object bundle(@PathVariable UUID datasetId,@PathVariable UUID versionId,@PathVariable UUID indexVersionId)throws Exception{
  var s=scope.current();if(!ServiceScopeService.matches(s,datasetId,versionId,indexVersionId))
   throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"SERVICE_SCOPE_NOT_AVAILABLE");
  return indexes.resolveBundle(datasetId,versionId,indexVersionId);
 }
}
