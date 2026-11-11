package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.ControlledViewProposalManagement;
import com.fwdrobo.sirius.security.ControlledViewProposalManagement.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/controlled-view-proposals")
public class ControlledViewProposalController {
 private final ControlledViewProposalManagement proposals;
 public ControlledViewProposalController(ControlledViewProposalManagement proposals){this.proposals=proposals;}
 @GetMapping("/sources") public Object sources(@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size,@RequestParam(required=false) String keyword){return proposals.sources(page,size,keyword);}
 @GetMapping("/sources/{datasetId}/versions/{versionId}/files") public Object files(@PathVariable UUID datasetId,@PathVariable UUID versionId,@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size)throws Exception{return proposals.files(datasetId,versionId,page,size);}
 @GetMapping("/sources/{datasetId}/versions/{versionId}/targets") public Object targets(@PathVariable UUID datasetId,@PathVariable UUID versionId){return proposals.targets(datasetId,versionId);}
 @PostMapping public Object create(@RequestBody CreateRequest request)throws Exception{return proposals.create(request);}
 @GetMapping public Object list(@RequestParam(required=false) Integer page,@RequestParam(required=false) Integer size){return proposals.list(page,size);}
 @GetMapping("/{proposalId}") public Object get(@PathVariable UUID proposalId){return proposals.get(proposalId);}
 @PostMapping("/{proposalId}/withdraw") public ResponseEntity<Void> withdraw(@PathVariable UUID proposalId,@RequestBody WithdrawRequest request){proposals.withdraw(proposalId,request);return ResponseEntity.noContent().build();}
}
