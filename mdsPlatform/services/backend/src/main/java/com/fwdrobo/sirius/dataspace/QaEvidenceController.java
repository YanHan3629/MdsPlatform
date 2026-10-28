package com.fwdrobo.sirius.dataspace;

import com.fwdrobo.sirius.service.QaEvidenceService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/data-space/qa/evidence/{datasetId}/{versionId}/{indexVersionId}/{assetId}")
public class QaEvidenceController {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final com.fasterxml.jackson.databind.ObjectReader READER=JSON.reader()
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final QaEvidenceService evidence;
    public QaEvidenceController(QaEvidenceService evidence){this.evidence=evidence;}
    @PostMapping("/read")
    public ResponseEntity<JsonNode> read(@PathVariable UUID datasetId,@PathVariable UUID versionId,
            @PathVariable UUID indexVersionId,@PathVariable UUID assetId,@RequestBody(required=false) String body)throws Exception{
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .body(evidence.read(datasetId,versionId,indexVersionId,assetId,selector(body)));
    }
    private JsonNode selector(String body)throws Exception{
        if(body==null||body.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID: 需要 JSON 精读参数");
        if(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID: 精读参数过大");
        JsonNode request;
        try{request=READER.readTree(body);}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID: JSON 参数不能含重复键或尾随内容");}
        if(request==null||!request.isObject())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID: 精读参数必须是 JSON 对象");
        return request;
    }
    @PostMapping("/render")
    public ResponseEntity<byte[]> render(@PathVariable UUID datasetId,@PathVariable UUID versionId,
            @PathVariable UUID indexVersionId,@PathVariable UUID assetId,@RequestBody(required=false) String body)throws Exception{
        var image=evidence.render(datasetId,versionId,indexVersionId,assetId,selector(body));
        String metadata=Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(image.metadata()));
        if(metadata.length()>32768)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_METADATA_LIMIT: 区域证据定位信息过大");
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(image.bytes().length).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header("X-Evidence-Read",metadata)
            .header("X-Evidence-Sha256",image.metadata().path("outputSha256").asText()).body(image.bytes());
    }
    @GetMapping
    public ResponseEntity<Map<String,Object>> describe(@PathVariable UUID datasetId,@PathVariable UUID versionId,
            @PathVariable UUID indexVersionId,@PathVariable UUID assetId)throws Exception{
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evidence.describe(datasetId,versionId,indexVersionId,assetId));
    }
    @GetMapping("/{representationId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID datasetId,@PathVariable UUID versionId,
            @PathVariable UUID indexVersionId,@PathVariable UUID assetId,@PathVariable String representationId)throws Exception{
        var result=evidence.open(datasetId,versionId,indexVersionId,assetId,representationId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.contentType())).contentLength(result.bytes().length)
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
            .header("X-Evidence-Sha256",result.sha256()).header("X-Evidence-Width",String.valueOf(result.width()))
            .header("X-Evidence-Height",String.valueOf(result.height())).body(result.bytes());
    }
}
