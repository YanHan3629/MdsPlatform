package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.HumanResourceGrantManagement;
import com.fwdrobo.sirius.security.HumanResourceGrantManagement.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/identity/resource-grants")
public class HumanResourceGrantController {
 private final HumanResourceGrantManagement grants;
 public HumanResourceGrantController(HumanResourceGrantManagement grants){this.grants=grants;}
 @GetMapping("/operations") public Object operations(){return grants.operations();}
 @GetMapping("/organizations") public Object organizations(){return grants.organizations();}
 @GetMapping("/members") public Object members(@RequestParam UUID orgId){return grants.members(orgId);}
 @GetMapping("/resources") public Object resources(@RequestParam(required=false) String purpose,@RequestParam(required=false) List<String> purposeCodes,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size){return grants.resources(purpose,purposeCodes,page,size);}
 @GetMapping public Object list(@RequestParam(required=false) UUID datasetId,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size){return grants.grants(datasetId,page,size);}
 @PostMapping("/preview") public Object preview(@RequestBody CreateRequest request){return grants.preview(request);}
 @PostMapping("/{grantId}/revoke-preview") public Object previewRevoke(@PathVariable UUID grantId,@RequestBody RevokeRequest request){return grants.previewRevoke(grantId,request);}
 @PostMapping("/reviews/{reviewId}/confirm") public ResponseEntity<Void> confirm(@PathVariable UUID reviewId,@RequestBody ConfirmRequest request){grants.apply(reviewId,request);return ResponseEntity.noContent().build();}
}
