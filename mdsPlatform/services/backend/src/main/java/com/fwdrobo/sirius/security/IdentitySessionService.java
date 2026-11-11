package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.service.AuthCacheService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.PasswordValidator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Durable sessions and immutable user IDs; Redis is never refresh authority. */
@Service
public class IdentitySessionService {
    private final JdbcTemplate jdbc;private final JwtService jwt;private final PasswordHashService hashes;
    private final PasswordEncoder passwords;private final SeedCredentialPolicy seeds;
    private final AuthorizationService authorization;private final AuthorizationAuditService audit;
    private final AuthCacheService cache;
    public IdentitySessionService(JdbcTemplate jdbc,JwtService jwt,PasswordHashService hashes,PasswordEncoder passwords,
            SeedCredentialPolicy seeds,AuthorizationService authorization,AuthorizationAuditService audit,AuthCacheService cache) {
        this.jdbc=jdbc;this.jwt=jwt;this.hashes=hashes;this.passwords=passwords;this.seeds=seeds;this.authorization=authorization;this.audit=audit;this.cache=cache;
    }
    private Map<String,Object> user(UUID id,boolean lock) {
        var rows=jdbc.queryForList("""
            SELECT u.*,o.org_name,o.org_status FROM fwd_user u JOIN org o ON o.org_id=u.org_id WHERE u.user_id=?
            """+(lock?" FOR NO KEY UPDATE OF u":""),id);
        if(rows.size()!=1)throw ExceptionUtils.unauthorized("IDENTITY_NOT_AVAILABLE");return rows.get(0);
    }
    private UUID id(Claims c,String key) {
        try{return UUID.fromString(c.get(key,String.class));}catch(Exception invalid){throw ExceptionUtils.unauthorized("IMMUTABLE_TOKEN_ID_REQUIRED");}
    }
    private int version(Map<String,Object> u){return ((Number)u.get("token_version")).intValue();}
    private void validAccount(Map<String,Object> u,boolean pendingAllowed) {
        String state=(String)u.get("account_state");
        if(u.get("status_code")==null||((Number)u.get("org_status")).intValue()!=1||
                !("ACTIVE".equals(state)||(pendingAllowed&&"PENDING".equals(state))))
            throw ExceptionUtils.unauthorized("IDENTITY_DISABLED_OR_CLOSED");
        if(seeds.isKnownSeedHash((String)u.get("password_hash")))throw ExceptionUtils.unauthorized("ADMIN_CREDENTIAL_ACTIVATION_REQUIRED");
    }
    private void readyForLogin() {
        var mode=jdbc.queryForMap("SELECT initialized,maintenance_mode FROM ds_auth_initialization WHERE singleton");
        if(!Boolean.TRUE.equals(mode.get("initialized")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"ADMIN_INITIALIZATION_REQUIRED");
        if(Boolean.TRUE.equals(mode.get("maintenance_mode")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"AUTHORIZATION_MAINTENANCE_MODE");
    }
    private boolean matches(String raw,String hash) {
        try{return raw!=null&&hash!=null&&passwords.matches(raw,hash);}catch(IllegalArgumentException unsupported){return false;}
    }
    public void validateNewPassword(String value) {
        try{PasswordValidator.validate(value);}catch(IllegalArgumentException invalid){throw ExceptionUtils.badRequest("PASSWORD_POLICY_REJECTED: "+invalid.getMessage());}
        if(seeds.isKnownSeedPassword(value))throw ExceptionUtils.badRequest("PUBLIC_DEFAULT_PASSWORD_NOT_ALLOWED");
    }
    private Map<String,Object> publicIdentity(Map<String,Object> u,boolean limited) {
        UUID uid=(UUID)u.get("user_id"),org=(UUID)u.get("org_id");var identity=new LinkedHashMap<String,Object>();
        identity.put("userid",uid);identity.put("userId",uid);identity.put("username",u.get("user_name"));
        identity.put("org",Map.of("orgId",org,"orgName",u.get("org_name")));
        identity.put("accountState",u.get("account_state"));identity.put("statusCode",u.get("status_code"));
        identity.put("permissionVersion",u.get("permission_version"));identity.put("policyVersion",authorization.currentPolicyVersion());
        identity.put("role",jdbc.queryForList("SELECT r.role_code FROM role r JOIN user_role ur ON ur.role_id=r.role_id WHERE ur.user_id=? AND r.type_state='ACTIVE' ORDER BY ur.is_primary DESC,r.role_code",String.class,uid));
        identity.put("permissionTree",List.of());
        if(limited) {identity.put("operations",List.of());identity.put("platformAdmin",false);identity.put("enterpriseMemberManager",false);}
        else {var actor=authorization.actorFor(uid);identity.put("operations",actor.operations().stream().sorted().toList());identity.put("platformAdmin",actor.platformAdmin());identity.put("enterpriseMemberManager",actor.enterpriseManager());identity.put("readOnly",actor.readOnly());}
        return identity;
    }
    private Map<String,Object> issue(Map<String,Object> u,UUID session,UUID refresh,long generation,boolean limited) {
        UUID uid=(UUID)u.get("user_id"),org=(UUID)u.get("org_id");String name=(String)u.get("user_name");
        var out=new LinkedHashMap<String,Object>();out.put("user",publicIdentity(u,limited));
        if(limited) {
            out.put("activationRequired",true);out.put("activationToken",jwt.generateSessionToken(uid,org,name,version(u),session,refresh,generation,JwtService.TokenType.ACTIVATION,600));
            out.put("activationExpiresIn",600);return out;
        }
        out.put("token",jwt.generateSessionToken(uid,org,name,version(u),session,UUID.randomUUID(),generation,JwtService.TokenType.ACCESS,jwt.getAccessExpirationSeconds()));
        out.put("tokenExpiresIn",jwt.getAccessExpirationSeconds());
        out.put("refreshToken",jwt.generateSessionToken(uid,org,name,version(u),session,refresh,generation,JwtService.TokenType.REFRESH,jwt.getRefreshExpirationSeconds()));
        out.put("refreshExpiresIn",jwt.getRefreshExpirationSeconds());out.put("activationRequired",false);return out;
    }
    @Transactional
    public Map<String,Object> login(String username,String password) {
        readyForLogin();var found=jdbc.queryForList("SELECT user_id FROM fwd_user WHERE user_name=?",username);
        if(found.size()!=1)throw ExceptionUtils.unauthorized("INVALID_CREDENTIALS");
        authorization.lockIdentityMutation();
        UUID uid=(UUID)found.get(0).get("user_id");var u=user(uid,true);validAccount(u,true);
        if(!matches(password,(String)u.get("password_hash"))) {
            audit.denied(uid,(UUID)u.get("org_id"),"identity.self.read","IDENTITY",uid.toString(),"INVALID_CREDENTIALS",Map.of());
            throw ExceptionUtils.unauthorized("INVALID_CREDENTIALS");
        }
        boolean limited=Boolean.TRUE.equals(u.get("password_change_required"))||"PENDING".equals(u.get("account_state"));
        UUID session=UUID.randomUUID(),refresh=UUID.randomUUID();long expiration=limited?600:jwt.getRefreshExpirationSeconds();
        jdbc.update("""
            INSERT INTO ds_auth_session(session_id,user_id,org_id,token_version,refresh_id,purpose,expires_at)
              VALUES(?,?,?,?,?,?,now()+(? * interval '1 second'))
            """,session,uid,u.get("org_id"),version(u),refresh,limited?"ACTIVATION":"ACCESS",expiration);
        if(!limited)jdbc.update("UPDATE fwd_user SET status_code=1,last_login_at=now(),credential_activated_at=COALESCE(credential_activated_at,now()) WHERE user_id=?",uid);
        audit.record(uid,(UUID)u.get("org_id"),"identity.self.read","IDENTITY",uid.toString(),"SUCCESS",limited?"ACTIVATION_LOGIN":"LOGIN",Map.of(),null,null);
        return issue(user(uid,false),session,refresh,1,limited);
    }
    private Map<String,Object> session(Claims claims,String purpose,boolean lock) {
        UUID uid=id(claims,"uid"),sid=id(claims,"sid"),org=id(claims,"org_id");
        var u=user(uid,false);validAccount(u,purpose.equals("ACTIVATION"));
        if(!Objects.equals(u.get("user_name"),claims.getSubject())||!Objects.equals(u.get("org_id"),org)||
                !Objects.equals(version(u),claims.get("token_version",Integer.class)))throw ExceptionUtils.unauthorized("IDENTITY_TOKEN_REVOKED");
        return activeSession(uid,org,sid,version(u),purpose,lock);
    }
    private Map<String,Object> activeSession(UUID uid,UUID org,UUID sid,int version,String purpose,boolean lock) {
        var rows=jdbc.queryForList("""
            SELECT * FROM ds_auth_session WHERE session_id=? AND user_id=? AND org_id=?
              AND token_version=? AND purpose=? AND revoked_at IS NULL AND clock_timestamp()<expires_at
            """+(lock?" FOR UPDATE":""),sid,uid,org,version,purpose);
        if(rows.size()!=1)throw ExceptionUtils.unauthorized("SESSION_REVOKED");return rows.get(0);
    }
    /** Restore a queued index origin in memory without issuing another access token. */
    public Claims restoreIndexOrigin(UUID uid,UUID org,UUID sid) {
        if(uid==null||sid==null)throw ExceptionUtils.unauthorized("INDEX_ORIGIN_SESSION_REQUIRED");
        readyForLogin();
        var u=user(uid,false);validAccount(u,false);
        if(!Objects.equals(org,u.get("org_id")))throw ExceptionUtils.unauthorized("INDEX_ORIGIN_ORGANIZATION_CHANGED");
        int tokenVersion=version(u);
        activeSession(uid,org,sid,tokenVersion,"ACCESS",false);
        return Jwts.claims().subject((String)u.get("user_name"))
            .add("uid",uid.toString()).add("org_id",org.toString()).add("sid",sid.toString())
            .add("token_type","ACCESS").add("token_version",tokenVersion).build();
    }
    @Transactional
    public Map<String,Object> refresh(String token) {
        readyForLogin();Claims claims=jwt.parseClaims(token,JwtService.TokenType.REFRESH);var s=session(claims,"ACCESS",true);
        UUID prior=id(claims,"refresh_id");Number generation=claims.get("refresh_generation",Number.class);
        if(generation==null||!prior.equals(s.get("refresh_id"))||generation.longValue()!=((Number)s.get("refresh_generation")).longValue())
            throw ExceptionUtils.unauthorized("REFRESH_ROTATION_REPLAY_DENIED");
        jdbc.update("INSERT INTO ds_auth_refresh_consumed(refresh_id,session_id) VALUES(?,?)",prior,s.get("session_id"));
        UUID next=UUID.randomUUID();long nextGeneration=generation.longValue()+1;
        jdbc.update("UPDATE ds_auth_session SET refresh_id=?,refresh_generation=?,expires_at=now()+(? * interval '1 second') WHERE session_id=?",next,nextGeneration,jwt.getRefreshExpirationSeconds(),s.get("session_id"));
        return issue(user(id(claims,"uid"),false),(UUID)s.get("session_id"),next,nextGeneration,false);
    }
    public AuthorizationService.Actor authenticateAccess(Claims claims) {
        readyForLogin();session(claims,"ACCESS",false);return authorization.actorFor(id(claims,"uid"));
    }
    public AuthorizationService.Actor authenticateJobIo(Claims claims) {
        readyForLogin();UUID uid=id(claims,"uid");var u=user(uid,false);validAccount(u,false);
        if(!Objects.equals(u.get("user_name"),claims.getSubject())||!Objects.equals(version(u),claims.get("token_version",Integer.class)))
            throw ExceptionUtils.unauthorized("JOB_IDENTITY_REVOKED");
        return authorization.actorFor(uid);
    }
    @Transactional
    public void activate(String activationToken,String newPassword) {
        readyForLogin();authorization.lockIdentityMutation();Claims claims=jwt.parseClaims(activationToken,JwtService.TokenType.ACTIVATION);var s=session(claims,"ACTIVATION",true);UUID uid=id(claims,"uid");var u=user(uid,true);
        validateNewPassword(newPassword);if(matches(newPassword,(String)u.get("password_hash")))throw ExceptionUtils.badRequest("NEW_PASSWORD_MUST_DIFFER");
        authorization.lockIdentityMutation();
        jdbc.update("UPDATE fwd_user SET password_hash=?,account_state='ACTIVE',password_change_required=false,credential_activated_at=now(),token_version=token_version+1,permission_version=permission_version+1 WHERE user_id=?",hashes.encode(newPassword),uid);
        jdbc.update("UPDATE ds_auth_session SET revoked_at=now(),revoke_reason='ACTIVATED' WHERE user_id=? AND revoked_at IS NULL",uid);
        clearCachedIdentity(uid,(String)u.get("user_name"));
        audit.record(uid,(UUID)u.get("org_id"),"identity.self.password","IDENTITY",uid.toString(),"SUCCESS","ACTIVATED_NEW_LOGIN_REQUIRED",Map.of(),null,Map.of("accountState","ACTIVE"));
    }
    @Transactional
    public void changePassword(UUID uid,String oldPassword,String newPassword) {
        var actor=authorization.actor();
        if(!actor.userId().equals(uid))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"SELF_IDENTITY_REQUIRED");
        authorization.requireOperation(actor,"identity.self.password");authorization.lockIdentityMutation();var u=user(uid,true);
        if(!matches(oldPassword,(String)u.get("password_hash")))throw ExceptionUtils.unauthorized("OLD_PASSWORD_INCORRECT");
        validateNewPassword(newPassword);if(matches(newPassword,(String)u.get("password_hash")))throw ExceptionUtils.badRequest("NEW_PASSWORD_MUST_DIFFER");
        authorization.lockIdentityMutation();
        jdbc.update("UPDATE fwd_user SET password_hash=?,token_version=token_version+1,permission_version=permission_version+1,credential_activated_at=now() WHERE user_id=?",hashes.encode(newPassword),uid);
        revokeRows(uid,"PASSWORD_CHANGED");audit.record(uid,actor.orgId(),"identity.self.password","IDENTITY",uid.toString(),"SUCCESS","PASSWORD_CHANGED",Map.of(),null,null);
    }
    /** Durable DB state is authority even while the performance cache is unavailable. */
    public void clearCachedIdentity(UUID uid,String username) {
        try {cache.evictUser(username);cache.evictTokensForUser(uid);}
        catch(org.springframework.dao.DataAccessException cacheUnavailable) {
            // Auth reads never trust this cache; do not roll back a durable revocation.
        }
    }
    private void revokeRows(UUID uid,String reason) {
        jdbc.update("UPDATE ds_auth_session SET revoked_at=now(),revoke_reason=? WHERE user_id=? AND revoked_at IS NULL",reason,uid);
        var u=user(uid,false);clearCachedIdentity(uid,(String)u.get("user_name"));
    }
    @Transactional
    public void revokeAll(UUID uid,String reason) {
        authorization.lockIdentityMutation();var u=user(uid,true);
        jdbc.update("UPDATE fwd_user SET token_version=token_version+1,permission_version=permission_version+1,status_code=CASE WHEN status_code IS NULL THEN NULL ELSE 0 END WHERE user_id=?",uid);
        revokeRows(uid,reason);
        audit.record(uid,(UUID)u.get("org_id"),"identity.self.logout","IDENTITY",uid.toString(),"SUCCESS",reason,Map.of(),null,null);
    }
}
