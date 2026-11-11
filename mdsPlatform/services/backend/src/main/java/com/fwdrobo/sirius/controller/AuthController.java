package com.fwdrobo.sirius.controller;
import com.fwdrobo.sirius.dto.user.*;
import com.fwdrobo.sirius.security.*;
import com.fwdrobo.sirius.security.IdentityManagementService.UserCreate;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthRefreshService refresh;private final IdentitySessionService sessions;
    private final IdentityManagementService management;private final AuthorizationService auth;
    public AuthController(AuthRefreshService refresh,IdentitySessionService sessions,IdentityManagementService management,AuthorizationService auth){
        this.refresh=refresh;this.sessions=sessions;this.management=management;this.auth=auth;
    }
    public record ActivateRequest(String newPassword) {}
    @PostMapping("/login") public Object login(@Valid @RequestBody LoginReq req){return refresh.login(req.userName(),req.password());}
    @PostMapping("/refresh") public Object refresh(@Valid @RequestBody RefreshReq req){return refresh.refresh(req.refreshToken());}
    @PostMapping("/activate") public Object activate(@RequestBody ActivateRequest req,HttpServletRequest request){
        Object token=request.getAttribute("ds.activationToken");
        if(!(token instanceof String value))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"ACTIVATION_CREDENTIAL_REQUIRED");
        sessions.activate(value,req.newPassword());return Map.of("success",true,"loginRequired",true);
    }
    @PostMapping("/register") public Object register(@Valid @RequestBody RegisterReq req){
        List<UUID> ids=req.roles()==null?List.of():req.roles();
        return management.createUser(new UserCreate(req.userName(),req.password(),req.orgId(),ids,ids.isEmpty()?null:ids.get(0)));
    }
    @PostMapping("/delete/{uid}") public Object close(@PathVariable UUID uid,@RequestHeader(value="If-Match",required=false) Long version){
        management.closeMember(uid,version);return Map.of("success",true,"userId",uid,"accountState","CLOSED");
    }
    @PostMapping("/logout/{uid}") public Object logoutUser(@PathVariable UUID uid,@RequestHeader(value="If-Match",required=false) Long version){
        management.forceLogout(uid,version);return Map.of("success",true,"userId",uid);
    }
    @PostMapping("/logout/all") public Object logoutAll(){return Map.of("success",true,"affected",management.forceLogoutNonAdmin());}
    @PostMapping("/logout") public Object logout(){
        var a=auth.requireOperation("identity.self.logout");sessions.revokeAll(a.userId(),"USER_LOGOUT_ALL_SESSIONS");
        return Map.of("success",true,"allSessionsRevoked",true,"userId",a.userId());
    }
}
