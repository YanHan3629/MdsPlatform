package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.service.IndexWorkerCallbackService;
import com.fwdrobo.sirius.dto.mm.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/mm/worker/datasets/{datasetId}/versions/{versionId}/indexes/{indexVersionId}")
public class IndexJobCallbackController {
 private final IndexWorkerCallbackService callbacks;
 public IndexJobCallbackController(IndexWorkerCallbackService callbacks){this.callbacks=callbacks;}
 @PostMapping("/ready")
 public MmIndexVersionResp ready(@PathVariable UUID datasetId,@PathVariable UUID versionId,@PathVariable UUID indexVersionId,@RequestBody MmIndexReadyReq req){
  return callbacks.ready(datasetId,versionId,indexVersionId,req);
 }
 @PostMapping("/failed")
 public MmIndexVersionResp failed(@PathVariable UUID datasetId,@PathVariable UUID versionId,@PathVariable UUID indexVersionId,@RequestBody MmIndexFailedReq req){
  return callbacks.failed(datasetId,versionId,indexVersionId,req);
 }
}
