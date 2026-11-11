package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.IndexCacheScopeService;
import com.fwdrobo.sirius.service.MmIndexService;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController
@RequestMapping("/api/mm/index-cache")
public class IndexCacheWorkerController {
 private final IndexCacheScopeService scope;private final MmIndexService indexes;
 public IndexCacheWorkerController(IndexCacheScopeService scope,MmIndexService indexes){this.scope=scope;this.indexes=indexes;}
 @GetMapping("/authority") public Object authority(){return scope.authority();}
 @GetMapping("/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}/bundle")
 public Object bundle(@PathVariable UUID datasetId,@PathVariable UUID versionId,@PathVariable UUID indexVersionId)throws Exception{
  var current=scope.current();if(!IndexCacheScopeService.matches(current,datasetId,versionId,indexVersionId))
   throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"INDEX_CACHE_SCOPE_NOT_AVAILABLE");
  return indexes.resolveBundle(datasetId,versionId,indexVersionId);
 }
}
