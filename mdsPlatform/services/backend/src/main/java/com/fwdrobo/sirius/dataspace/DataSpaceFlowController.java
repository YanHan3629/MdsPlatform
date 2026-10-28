package com.fwdrobo.sirius.dataspace;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/data-space")
public class DataSpaceFlowController {
    private final PersistentDataSpaceService service;
    private final DataSpaceQualityService quality;
    private final DataSpaceApplicationService applications;
    private final DataSpaceContractService contracts;
    private final DataSpaceDeliveryService deliveries;

    public DataSpaceFlowController(PersistentDataSpaceService service,DataSpaceQualityService quality,DataSpaceApplicationService applications,DataSpaceContractService contracts,DataSpaceDeliveryService deliveries) {
        this.service = service;
        this.quality = quality;
        this.applications = applications;
        this.contracts=contracts;this.deliveries=deliveries;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "module", "data-space-flow");
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        return service.snapshot();
    }

    @GetMapping("/business-domains")
    public List<Map<String, Object>> listBusinessDomains() {
        return service.listBusinessDomains();
    }

    @GetMapping("/spaces")
    public List<Map<String, Object>> listSpaces() {
        return service.listSpaces();
    }

    @PostMapping("/spaces/register")
    public Map<String, Object> registerSpace(@RequestBody Map<String, Object> body) {
        return service.registerSpace(body);
    }

    @PostMapping("/spaces/{spaceId}/verify")
    public Map<String, Object> verifySpace(@PathVariable String spaceId) {
        return service.verifySpace(spaceId);
    }

    @GetMapping("/sources")
    public List<Map<String, Object>> listSources() {
        return service.listSources();
    }

    @PostMapping("/sources")
    public Map<String, Object> createSource(@RequestBody Map<String, Object> body) {
        return service.createSource(body);
    }

    @PostMapping(value = "/sources/{sourceId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadFiles(@PathVariable String sourceId,
                                           @RequestParam("files") MultipartFile[] files) {
        return service.uploadFiles(sourceId, files);
    }

    @PostMapping("/sources/{sourceId}/local-dataset")
    public Map<String, Object> attachLocalDataset(@PathVariable String sourceId,
                                                  @RequestBody Map<String, Object> body) {
        return service.attachLocalDataset(sourceId, body);
    }

    @GetMapping("/catalogs")
    public List<Map<String, Object>> listCatalogs() {
        return service.listCatalogs();
    }

    @PostMapping("/sources/{sourceId}/catalogs")
    public Map<String, Object> createCatalog(@PathVariable String sourceId,
                                             @RequestBody Map<String, Object> body) {
        return service.createCatalog(sourceId, body);
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> listDatasets() {
        return service.listDatasets();
    }

    @PostMapping("/sources/{sourceId}/govern")
    public Map<String, Object> governSource(@PathVariable java.util.UUID sourceId,
                                            @RequestBody Map<String, Object> body) {
        return quality.request(sourceId, body);
    }

    @PostMapping("/datasets/{datasetId}/build-index")
    public Map<String, Object> buildIndex(@PathVariable String datasetId,
                                          @RequestBody Map<String, Object> body) {
        return service.buildIndex(datasetId, body);
    }

    @PostMapping("/datasets/{datasetId}/publish")
    public Map<String, Object> publishProduct(@PathVariable String datasetId,
                                              @RequestBody Map<String, Object> body) {
        return service.publishProduct(datasetId, body);
    }

    @PostMapping("/demo/val2017-flow")
    public Map<String, Object> runVal2017Flow(@RequestBody(required = false) Map<String, Object> body) {
        return service.runVal2017Flow(body == null ? Map.of() : body);
    }

    @GetMapping("/products")
    public List<Map<String, Object>> listProducts() {
        return service.listProducts();
    }

    @DeleteMapping("/products/{productId}")
    public Map<String, Object> deleteProduct(@PathVariable String productId) {
        return service.deleteProduct(productId);
    }

    @GetMapping("/products/search")
    public List<Map<String, Object>> searchProducts(@RequestParam(required = false) String keyword,
                                                    @RequestParam(required = false) String modalityType,
                                                    @RequestParam(required = false) String businessCode) {
        return service.searchProducts(keyword, modalityType, businessCode);
    }

    @GetMapping("/products/{productId}/samples")
    public List<Map<String, Object>> productSamples(@PathVariable String productId,
                                                    @RequestParam(defaultValue = "12") int limit) {
        return service.productSamples(productId, limit);
    }
    @GetMapping("/products/{productId}/versions/{productVersionId}/scope")
    public Map<String,Object> productScope(@PathVariable java.util.UUID productId,@PathVariable java.util.UUID productVersionId){return service.productScope(productId,productVersionId);}

    @GetMapping("/products/{productId}/content-search")
    public List<Map<String, Object>> searchProductContent(@PathVariable String productId,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(defaultValue = "12") int limit) {
        return service.searchProductContent(productId, keyword, limit);
    }

    @GetMapping("/products/{productId}/files/{fileName:.+}")
    public ResponseEntity<InputStreamResource> productFile(@PathVariable String productId,
                                                           @PathVariable String fileName) throws Exception {
        return productFileByName(productId, fileName);
    }

    @GetMapping("/products/{productId}/files")
    public ResponseEntity<InputStreamResource> productFileByQuery(@PathVariable String productId,
                                                                  @RequestParam String fileName) throws Exception {
        return productFileByName(productId, fileName);
    }

    private ResponseEntity<InputStreamResource> productFileByName(String productId, String fileName) throws Exception {
        DataSpaceFlowService.DataSpaceFile file = service.openProductFile(productId, fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .header("Cache-Control","no-store")
                .header("Content-Disposition",org.springframework.http.ContentDisposition.attachment().filename(file.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(file.inputStream()));
    }

    @PostMapping("/products/{productId}/minio-sync")
    public Map<String, Object> syncProductToMinio(@PathVariable String productId,
                                                  @RequestBody(required = false) Map<String, Object> body) throws Exception {
        return service.syncProductToMinio(productId, body == null ? Map.of() : body);
    }

    @GetMapping("/intents")
    public List<Map<String, Object>> listIntents() {
        return service.listIntents();
    }

    @PostMapping("/intents")
    public Map<String, Object> createIntent(@RequestBody Map<String, Object> body) {
        return applications.create(body);
    }

    @PostMapping("/intents/{intentId}/decision")
    public Map<String,Object> decideIntent(@PathVariable java.util.UUID intentId,@RequestBody Map<String,Object> body){return applications.decide(intentId,body);}

    @GetMapping("/contracts")
    public List<Map<String, Object>> listContracts() {
        return service.listContracts();
    }

    @PostMapping("/contracts")
    public Map<String, Object> createContract(@RequestBody Map<String, Object> body) {
        return contracts.create(body);
    }

    @PostMapping("/contracts/{contractId}/dispatch")
    public Map<String, Object> dispatchContract(@PathVariable java.util.UUID contractId) {
        return contracts.retry(contractId);
    }

    @GetMapping("/deliveries")
    public List<Map<String, Object>> listDeliveries() {
        return service.listDeliveries();
    }
    @GetMapping("/receiver-endpoints")
    public List<Map<String,Object>> endpoints(@RequestParam(required=false)java.util.UUID applicationId){return contracts.endpoints(applicationId);}
    @PostMapping("/receiver-endpoints")
    public Map<String,Object> endpoint(@RequestBody Map<String,Object> body){return contracts.registerEndpoint(body);}
    @PostMapping("/contracts/{id}/confirm")
    public Map<String,Object> confirm(@PathVariable java.util.UUID id,@RequestBody Map<String,Object> body){return contracts.confirm(id,body);}
    @PostMapping("/contracts/{id}/revoke")
    public Map<String,Object> revoke(@PathVariable java.util.UUID id,@RequestBody Map<String,Object> body){return contracts.revoke(id,body);}
    @PostMapping("/contracts/{id}/revise")
    public Map<String,Object> revise(@PathVariable java.util.UUID id,@RequestBody Map<String,Object> body){return contracts.revise(id,body);}
    @GetMapping("/contracts/{id}/history")
    public List<Map<String,Object>> history(@PathVariable java.util.UUID id){return contracts.history(id);}
    @GetMapping("/deliveries/{id}/attempts")
    public List<Map<String,Object>> attempts(@PathVariable java.util.UUID id){return deliveries.attempts(id);}
    @GetMapping("/deliveries/{id}/manifest")
    public Map<String,Object> manifest(@PathVariable java.util.UUID id){return deliveries.manifestForUser(id);}
    @GetMapping("/deliveries/{id}/receipt")
    public Map<String,Object> receipt(@PathVariable java.util.UUID id){return deliveries.receiptForUser(id);}
    @GetMapping("/deliveries/{id}/package")
    public ResponseEntity<InputStreamResource> deliveryPackage(@PathVariable java.util.UUID id)throws Exception{
        var stream=deliveries.openPackage(id);var response=ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header("Cache-Control","no-store").header("Content-Disposition","attachment; filename=delivery-"+id+".zip");
        if(stream.size()>=0)response.contentLength(stream.size());return response.body(new InputStreamResource(stream.inputStream()));
    }
}
