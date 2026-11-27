package com.fwdrobo.sirius.dataspace;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/data-space/dashboard")
public class DashboardController {
    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/datasets")
    public DashboardService.Page datasets(@RequestParam(defaultValue="") String keyword,
            @RequestParam(defaultValue="") String status, @RequestParam(required=false) List<String> business,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="false") boolean includeFirstPreview) {
        return dashboard.page(keyword, status, business == null ? List.of() : business, page, includeFirstPreview);
    }

    @GetMapping("/datasets/{datasetId}/versions/{versionId}/previews")
    public DashboardService.PreviewPage previews(@PathVariable UUID datasetId, @PathVariable UUID versionId,
            @RequestParam(required=false) UUID indexVersionId, @RequestParam(defaultValue="0") int offset) {
        return dashboard.previews(datasetId, versionId, indexVersionId, offset);
    }

    @GetMapping("/datasets/{datasetId}/versions/{versionId}/previews/{fileId}")
    public ResponseEntity<byte[]> thumbnail(@PathVariable UUID datasetId, @PathVariable UUID versionId,
            @PathVariable UUID fileId, @RequestParam(required=false) UUID indexVersionId,
            @RequestParam String sha256) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .body(dashboard.thumbnail(datasetId, versionId, indexVersionId, fileId, sha256));
    }

    @PutMapping("/datasets/{datasetId}/versions/{versionId}/metadata")
    public ResponseEntity<Void> metadata(@PathVariable UUID datasetId, @PathVariable UUID versionId,
            @RequestParam(required=false) UUID indexVersionId, @RequestBody DashboardService.Metadata input) {
        dashboard.updateMetadata(datasetId, versionId, indexVersionId, input);
        return ResponseEntity.noContent().build();
    }
}
