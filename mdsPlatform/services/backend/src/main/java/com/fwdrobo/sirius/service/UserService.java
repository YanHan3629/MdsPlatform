package com.fwdrobo.sirius.service;
import com.fwdrobo.sirius.dto.user.OrgUserResp;
import com.fwdrobo.sirius.entity.FwdUser;
import com.fwdrobo.sirius.mapper.UserMapper;
import com.fwdrobo.sirius.security.*;
import com.fwdrobo.sirius.security.IdentityManagementService.*;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
/** Legacy service paths delegate to the same live identity authority. */
@Service
public class UserService {
    private final UserMapper users;private final JdbcTemplate jdbc;private final AuthorizationService auth;
    private final IdentityManagementService management;private final IdentitySessionService sessions;private final PasswordEncoder passwords;
    public UserService(UserMapper users,JdbcTemplate jdbc,AuthorizationService auth,IdentityManagementService management,
            IdentitySessionService sessions,PasswordEncoder passwords){
        this.users=users;this.jdbc=jdbc;this.auth=auth;this.management=management;this.sessions=sessions;this.passwords=passwords;
    }
    public FwdUser register(String name,String password,List<UUID> roleIds,UUID org) {
        var ids=roleIds==null?List.<UUID>of():roleIds;var u=management.createUser(new UserCreate(name,password,org,ids,ids.isEmpty()?null:ids.get(0)));
        return users.selectById((UUID)u.get("userId"));
    }
    private FwdUser visible(FwdUser user) {
        if(user==null)return null;var a=auth.actor();
        if(!a.userId().equals(user.getUserId())){auth.requireMemberManagement(user.getOrgId(),"identity.member.read");auth.requireNoManagementEscalation(a,user.getUserId());}
        return user;
    }
    public Optional<FwdUser> findByUsername(String name){return Optional.ofNullable(visible(users.selectByUsername(name)));}
    public Optional<FwdUser> findByUsernameForAuth(String name){return findByUsername(name);}
    public Optional<FwdUser> findById(UUID id){return Optional.ofNullable(visible(users.selectById(id)));}
    public List<FwdUser> list(){return management.users(null).stream().map(u->users.selectById((UUID)u.get("userId"))).toList();}
    public boolean isPasswordValid(FwdUser user,String raw){visible(user);return user!=null&&raw!=null&&passwords.matches(raw,user.getPasswordHash());}
    public void forceLogout(UUID id){management.forceLogout(id,null);}
    public int forceLogoutAllNonAdmin(){return management.forceLogoutNonAdmin();}
    public void logout(String name){
        var a=auth.requireOperation("identity.self.logout");
        if(!Objects.equals(name,a.username()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"SELF_IDENTITY_REQUIRED");
        sessions.revokeAll(a.userId(),"USER_LOGOUT_ALL_SESSIONS");
    }
    public void changePassword(String oldValue,String newValue){sessions.changePassword(auth.actor().userId(),oldValue,newValue);}
    public void updateStatus(FwdUser user,Integer status){throw new ResponseStatusException(HttpStatus.CONFLICT,"USE_EXPLICIT_IDENTITY_LIFECYCLE");}
    public void deleteUser(UUID id){management.closeMember(id,null);}
    /** Label lookup is called only after its owning resource was authorized. */
    public String resolveUsername(UUID id){
        if(id==null)return null;var rows=jdbc.queryForList("SELECT user_name FROM fwd_user WHERE user_id=?",String.class,id);return rows.isEmpty()?null:rows.get(0);
    }
    public List<OrgUserResp> getUsersByOrg(UUID org){
        return management.users(org).stream().map(u->{var x=users.selectById((UUID)u.get("userId"));return new OrgUserResp(
            x.getUserId(),x.getUserName(),x.getOrgId(),x.getStatusCode(),x.getTokenVersion(),x.getRoles()==null?List.of():x.getRoles());}).toList();
    }
}
