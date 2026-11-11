package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.security.*;
import com.fwdrobo.sirius.security.IdentityManagementService.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController
@RequestMapping("/api/identity")
public class IdentityManagementController {
    private final IdentityManagementService management;private final AuthorizationService auth;private final IdentityChangeReviewService reviews;
    private final AuthorizationAuditService audit;
    public IdentityManagementController(IdentityManagementService management,AuthorizationService auth,IdentityChangeReviewService reviews,AuthorizationAuditService audit){this.management=management;this.auth=auth;this.reviews=reviews;this.audit=audit;}
    public record VersionRequest(Long expectedVersion) {}
    public record CancelReviewRequest(String reviewSha256) {}
    @GetMapping("/organizations") public Object organizations(){return management.organizations();}
    @GetMapping("/operations") public Object operations(){auth.requirePlatformManagement("identity.type.manage");return OperationCatalog.all().values();}
    @GetMapping("/users") public Object users(@RequestParam(required=false) UUID orgId){return management.users(orgId);}
    @PostMapping("/users") public Object create(@RequestBody UserCreate req){return management.createUser(req);}
    @PatchMapping("/users/{uid}") public Object updateAccount(@PathVariable UUID uid,@RequestBody AccountUpdate req){return management.updateAccount(uid,req);}
    @PutMapping("/users/{uid}/types") public Object assign(@PathVariable UUID uid,@RequestBody TypeAssignment req){return management.assignTypes(uid,req);}
    @PatchMapping("/users/{uid}/state") public Object state(@PathVariable UUID uid,@RequestBody StateChange req){return management.changeState(uid,req);}
    @PostMapping("/users/{uid}/password-reset") public Object reset(@PathVariable UUID uid,@RequestBody PasswordReset req){return management.resetPassword(uid,req);}
    @PostMapping("/users/{uid}/close") public Object close(@PathVariable UUID uid,@RequestBody VersionRequest req){management.closeMember(uid,req.expectedVersion());return Map.of("success",true);}
    @PostMapping("/users/batch-close") public Object batchClose(@RequestBody BatchClose req){return Map.of("success",true,"affected",management.closeMembers(req));}
    @PostMapping("/users/{uid}/logout") public Object logout(@PathVariable UUID uid,@RequestBody VersionRequest req){management.forceLogout(uid,req.expectedVersion());return Map.of("success",true);}
    @PostMapping("/users/logout-non-admin") public Object logoutNonAdmin(){return Map.of("success",true,"affected",management.forceLogoutNonAdmin());}
    @PutMapping("/users/{uid}/qualification") public Object qualification(@PathVariable UUID uid,@RequestBody QualificationChange req){return management.changeQualification(uid,req);}
    @GetMapping("/types") public Object types(){return management.types();}
    @PostMapping("/types") public Object createType(@RequestBody TypeChange req){return Map.of("typeId",management.createType(req));}
    @PutMapping("/types/{id}") public Object updateType(@PathVariable UUID id,@RequestBody TypeChange req){management.updateType(id,req);return Map.of("success",true);}
    @PostMapping("/types/{id}/delete") public Object deleteType(@PathVariable UUID id,@RequestBody TypeDeletion req){management.deleteType(id,req.expectedVersion(),req.replacementTypeId(),req.replacementTypeVersion());return Map.of("success",true);}

    @PostMapping("/users/{uid}/impact") public Object accountImpact(@PathVariable UUID uid,@RequestBody AccountUpdate req){return management.preview("ACCOUNT_UPDATE",uid,req);}
    @PostMapping("/users/{uid}/types-impact") public Object typesImpact(@PathVariable UUID uid,@RequestBody TypeAssignment req){return management.preview("USER_TYPES",uid,req);}
    @PostMapping("/users/{uid}/qualification-impact") public Object qualificationImpact(@PathVariable UUID uid,@RequestBody QualificationChange req){return management.preview("QUALIFICATION",uid,req);}
    @PostMapping("/types/{id}/impact") public Object typeImpact(@PathVariable UUID id,@RequestBody TypeChange req){return management.preview("TYPE_UPDATE",id,req);}
    @PostMapping("/types/{id}/delete-impact") public Object deleteImpact(@PathVariable UUID id,@RequestBody TypeDeletion req){return management.preview("TYPE_DELETE",id,req);}
    @GetMapping("/changes/{id}") public Object changeReview(@PathVariable UUID id){return reviews.get(id);}
    @PostMapping("/changes/{id}/cancel") public Object cancelReview(@PathVariable UUID id,@RequestBody CancelReviewRequest req){return reviews.cancel(id,req.reviewSha256());}

    @GetMapping("/audit")
    public ResponseEntity<AuthorizationAuditService.Page> listAudit(
            @RequestParam(required=false) String targetId,@RequestParam(required=false) UUID before,
            @RequestParam(defaultValue="25") int limit,HttpServletRequest request){
        String operation="identity.audit.read";
        auth.requirePlatformManagement(operation);
        if(limit<1||limit>100||(targetId!=null&&(targetId.isBlank()||targetId.length()>160)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"AUDIT_QUERY_INVALID");
        request.setAttribute(AuthorizationService.REQUEST_OPERATION,operation);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(audit.list(targetId,before,limit));
    }
}
