package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
/** No issuing endpoint: workers can only validate a backend-issued frozen credential. */
@RestController
@RequestMapping("/api/mm/internal/evidence")
public class WorkerEvidenceController {
 @GetMapping("/authorize")
 public Map<String,Object> authorize(HttpServletRequest request){
  if(!(request.getAttribute(WorkerEvidenceScopeService.ATTRIBUTE) instanceof WorkerEvidenceScopeService.Scope))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN,"EVIDENCE_CREDENTIAL_REQUIRED");
  var scope=(WorkerEvidenceScopeService.Scope)request.getAttribute(WorkerEvidenceScopeService.ATTRIBUTE);
  if(scope.userInputOnly())return Map.of("operation",scope.operation(),"purpose","USER_INPUT_ONLY",
    "userId",scope.actor().userId(),"orgId",scope.actor().orgId(),"permissionVersion",scope.actor().permissionVersion(),"sessionId",scope.session());
  return Map.of("datasetId",scope.dataset(),"versionId",scope.version(),"indexVersionId",scope.index(),
    "operation",scope.operation(),"purpose","GENERAL_READ","userId",scope.actor().userId(),"orgId",scope.actor().orgId(),
    "permissionVersion",scope.actor().permissionVersion(),"sessionId",scope.session());
 }
}
