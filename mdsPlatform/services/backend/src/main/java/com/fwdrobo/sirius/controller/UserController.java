package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.dto.user.PasswordResetReq;
import com.fwdrobo.sirius.security.*;
import com.fwdrobo.sirius.security.IdentityManagementService.TypeAssignment;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api/users")
public class UserController {
    private final AuthorizationService auth;private final IdentitySessionService sessions;private final IdentityManagementService management;private final com.fwdrobo.sirius.service.DatasetAccessService access;
    public UserController(AuthorizationService auth,IdentitySessionService sessions,IdentityManagementService management,com.fwdrobo.sirius.service.DatasetAccessService access){this.auth=auth;this.sessions=sessions;this.management=management;this.access=access;}
    @GetMapping public Object list(){return management.users(null);}
    @GetMapping("/me") public Object me(){var out=new LinkedHashMap<String,Object>(auth.currentIdentity());out.put("account",management.ownAccount());return out;}
    @GetMapping("/me/scopes") public Object scopes(@RequestParam String operation,jakarta.servlet.http.HttpServletRequest request){
        var scopes=access.scopesForOperation(operation);
        request.setAttribute(AuthorizationService.REQUEST_OPERATION,operation);
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
            .header("X-Authorization-Policy-Version",Long.toString(auth.currentPolicyVersion())).body(scopes);
    }
    @PatchMapping("/me/password") public Object password(@RequestBody PasswordResetReq req){
        sessions.changePassword(auth.actor().userId(),req.oldPassword(),req.newPassword());return Map.of("success",true,"loginRequired",true);
    }
    @PostMapping("/me/close") public Object close(@RequestBody IdentityManagementController.VersionRequest req){
        management.closeSelf(req.expectedVersion());return Map.of("success",true,"accountState","CLOSED");
    }
    @PutMapping("/{uid}/roles") public Object roles(@PathVariable UUID uid,@RequestBody List<UUID> ids,
            @RequestHeader(value="If-Match",required=false) Long version){
        return management.assignTypes(uid,new TypeAssignment(ids,ids==null||ids.isEmpty()?null:ids.get(0),version));
    }
}
