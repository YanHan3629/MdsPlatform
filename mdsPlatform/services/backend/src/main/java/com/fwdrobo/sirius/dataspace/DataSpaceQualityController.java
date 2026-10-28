package com.fwdrobo.sirius.dataspace;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.core.io.InputStreamResource;
import java.util.*;

@RestController
public class DataSpaceQualityController {
    private final DataSpaceQualityService quality;
    public DataSpaceQualityController(DataSpaceQualityService quality){this.quality=quality;}
    @PostMapping("/api/data-space/sources/{sourceId}/quality")
    public Map<String,Object> request(@PathVariable UUID sourceId,@RequestBody Map<String,Object> body){return quality.request(sourceId,body);}
    @GetMapping("/api/data-space/quality/{reportId}")
    public Map<String,Object> get(@PathVariable UUID reportId){return quality.get(reportId);}
    @GetMapping("/api/data-space/datasets/{datasetId}/versions/{versionId}/quality")
    public List<Map<String,Object>> list(@PathVariable UUID datasetId,@PathVariable UUID versionId){return quality.list(datasetId,versionId);}
    @PostMapping("/api/mm/internal/business/quality/claim")
    public Map<String,Object> claim(){return quality.claim();}
    @PostMapping("/api/mm/internal/business/quality/{reportId}/heartbeat")
    public void heartbeat(@PathVariable UUID reportId,@RequestParam UUID leaseToken){quality.heartbeat(reportId,leaseToken);}
    @GetMapping("/api/mm/internal/business/quality/{reportId}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID reportId,@RequestParam UUID leaseToken,@RequestParam String path)throws Exception{
        var stream=quality.content(reportId,leaseToken,path);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(stream.size()).header("Cache-Control","no-store").body(new InputStreamResource(stream.inputStream()));
    }
    @PostMapping("/api/mm/internal/business/quality/{reportId}/complete")
    public Map<String,Object> complete(@PathVariable UUID reportId,@RequestParam UUID leaseToken,@RequestBody Map<String,Object> body){return quality.complete(reportId,leaseToken,body);}
    @PostMapping("/api/mm/internal/business/quality/{reportId}/fail")
    public void fail(@PathVariable UUID reportId,@RequestParam UUID leaseToken){quality.fail(reportId,leaseToken);}
}
