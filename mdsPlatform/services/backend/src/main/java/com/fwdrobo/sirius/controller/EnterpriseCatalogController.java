package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.EnterpriseCatalogManagement;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.UUID;
/** Human administrative identity configuration; no anonymous self-asserted entity IDs. */
@RestController
@RequestMapping("/api/identity/enterprises")
public class EnterpriseCatalogController {
 private final EnterpriseCatalogManagement management;
 public EnterpriseCatalogController(EnterpriseCatalogManagement management){this.management=management;}
 @GetMapping public Object organizations(){return management.organizations();}
 @GetMapping("/catalogs") public Object catalogs(){return management.catalogs();}
 @GetMapping("/catalog-sources") public Object sources(){return management.sources();}
 @PostMapping("/catalogs/import") public Object importCatalog(@RequestBody EnterpriseCatalogManagement.ImportRequest req)throws Exception{return management.importCatalog(req);}
 @PostMapping("/{orgId}/mapping/preview") public Object preview(@PathVariable UUID orgId,@RequestBody EnterpriseCatalogManagement.MappingRequest req){return management.preview(orgId,req);}
 @PostMapping("/{orgId}/mapping/disable-preview") public Object disable(@PathVariable UUID orgId,@RequestBody EnterpriseCatalogManagement.DisableRequest req){return management.previewDisable(orgId,req);}
 @PostMapping("/{orgId}/mapping/confirm") public ResponseEntity<Void> confirm(@PathVariable UUID orgId,@RequestBody EnterpriseCatalogManagement.ConfirmRequest req){
  management.apply(orgId,req);return ResponseEntity.noContent().build();
 }
}
