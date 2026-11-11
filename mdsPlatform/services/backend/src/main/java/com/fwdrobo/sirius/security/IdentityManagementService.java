package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.entity.permission.Role;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.util.*;

/** All identity mutations share one transaction lock and durable revocation. */
@Service
public class IdentityManagementService {
    private final JdbcTemplate jdbc;private final AuthorizationService auth;private final IdentitySessionService sessions;
    private final IdentityChangeReviewService reviews;private final PasswordHashService hashes;private final AuthorizationAuditService audit;private final ObjectMapper mapper;
    public IdentityManagementService(JdbcTemplate jdbc,AuthorizationService auth,IdentitySessionService sessions,
            PasswordHashService hashes,AuthorizationAuditService audit,ObjectMapper mapper,IdentityChangeReviewService reviews) {
        this.jdbc=jdbc;this.auth=auth;this.sessions=sessions;this.hashes=hashes;this.audit=audit;this.mapper=mapper;this.reviews=reviews;
    }
    public record TypeDeletion(Long expectedVersion,UUID replacementTypeId,Long replacementTypeVersion) {}
    public record AccountUpdate(String username,UUID orgId,List<UUID> typeIds,UUID primaryTypeId,Long expectedVersion) {}
    public record UserCreate(String username,String temporaryPassword,UUID orgId,List<UUID> typeIds,UUID primaryTypeId) {}
    public record TypeAssignment(List<UUID> typeIds,UUID primaryTypeId,Long expectedVersion) {}
    public record StateChange(String state,Long expectedVersion) {}
    public record PasswordReset(String temporaryPassword,Long expectedVersion) {}
    public record QualificationChange(String qualification,boolean active,List<UUID> delegatedTypeIds,Long expectedVersion) {}
    public record TypeChange(UUID orgId,String code,String name,String description,String scope,List<String> industryTags,
            List<UUID> applicableOrgIds,boolean readOnly,List<String> operations,String state,Long expectedVersion) {}
    private static void fail(HttpStatus status,String code){throw new ResponseStatusException(status,code);}
    private static long number(Object n){return ((Number)n).longValue();}
    private static void cas(Long expected,Object actual) {
        if(expected==null)fail(HttpStatus.PRECONDITION_REQUIRED,"EXPECTED_VERSION_REQUIRED");
        if(expected.longValue()!=number(actual))fail(HttpStatus.CONFLICT,"IDENTITY_VERSION_CONFLICT");
    }
    private String json(Object value) {
        try{return mapper.writeValueAsString(value);}catch(Exception invalid){throw new IllegalArgumentException("Invalid structured metadata");}
    }
    private static <T> List<T> distinct(List<T> list) {
        if(list==null)return List.of();if(list.stream().anyMatch(Objects::isNull))fail(HttpStatus.BAD_REQUEST,"NULL_IDENTIFIER");
        return new ArrayList<>(new LinkedHashSet<>(list));
    }
    private AuthorizationService.Actor manager(UUID org,String operation) {
        var actor=auth.actor();auth.requireMemberManagement(org,operation);return actor;
    }
    private Map<String,Object> target(UUID uid,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM fwd_user WHERE user_id=?"+(lock?" FOR NO KEY UPDATE":""),uid);
        if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"MEMBER_SCOPE_NOT_AVAILABLE");
        return rows.get(0);
    }
    private Map<String,Object> managedTarget(UUID uid,String op) {
        var row=target(uid,false);var actor=manager((UUID)row.get("org_id"),op);
        auth.requireNoManagementEscalation(actor,uid);
        row=target(uid,true);
        if("CLOSED".equals(row.get("account_state")))fail(HttpStatus.CONFLICT,"CLOSED_IDENTITY_IS_TERMINAL");
        return row;
    }
    private void liveOrg(UUID org) {
        if(org==null||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM org WHERE org_id=? AND org_status=1)",Boolean.class,org)))
            fail(HttpStatus.NOT_FOUND,"ENTERPRISE_NOT_AVAILABLE");
    }
    private void event(AuthorizationService.Actor a,String op,String kind,UUID id,String reason,Map<String,?> before,Map<String,?> after) {
        audit.record(a.userId(),a.orgId(),op,kind,id.toString(),"SUCCESS",reason,Map.of(),before,after);
    }
    private void bumpPolicy(){jdbc.update("UPDATE ds_auth_initialization SET policy_version=policy_version+1,updated_at=now() WHERE singleton");}
    private List<Map<String,Object>> normalise(List<Map<String,Object>> rows) {
        for(var row:rows)row.replaceAll((key,value)->{
            try {
                if(value instanceof org.postgresql.util.PGobject pg && Set.of("json","jsonb").contains(pg.getType()))
                    return mapper.readValue(pg.getValue(),Object.class);
                if(value instanceof java.sql.Array array)return Arrays.asList((Object[])array.getArray());
                if(value instanceof java.sql.Timestamp stamp)return stamp.toInstant().toString();
                return value;
            } catch(Exception invalid){throw new IllegalStateException("Stored identity metadata invalid");}
        });
        return rows;
    }
    private Map<String,Object> publicUser(Map<String,Object> u) {
        UUID uid=(UUID)u.get("user_id");var out=new LinkedHashMap<String,Object>();
        out.put("userId",uid);out.put("username",u.get("user_name"));out.put("orgId",u.get("org_id"));
        out.put("accountState",u.get("account_state"));out.put("online",Objects.equals(u.get("status_code"),1));
        out.put("permissionVersion",u.get("permission_version"));out.put("activationRequired",u.get("password_change_required"));
        out.put("lastLoginAt",u.get("last_login_at"));out.put("createdAt",u.get("created_at"));
        out.put("types",jdbc.queryForList("""
            SELECT r.role_id AS "typeId",r.role_code AS code,r.role_name AS name,r.type_state AS state,
                   r.type_version AS version,ur.is_primary AS "primary" FROM user_role ur JOIN role r ON r.role_id=ur.role_id
             WHERE ur.user_id=? AND r.type_state<>'DELETED' ORDER BY ur.is_primary DESC,r.role_code
            """,uid));
        out.put("qualifications",normalise(jdbc.queryForList("""
            SELECT qualification,active,version,delegated_type_ids AS "delegatedTypeIds"
              FROM ds_management_membership WHERE user_id=? ORDER BY qualification
            """,uid)));
        return out;
    }
    public List<Map<String,Object>> users(UUID org) {
        var a=auth.requireOperation("identity.member.read");
        if(!a.platformAdmin()){if(org!=null&&!org.equals(a.orgId()))fail(HttpStatus.NOT_FOUND,"MEMBER_SCOPE_NOT_AVAILABLE");org=a.orgId();}
        auth.requireMemberManagement(org,"identity.member.read");
        List<Map<String,Object>> rows=org==null?jdbc.queryForList("SELECT * FROM fwd_user ORDER BY created_at,user_id"):
            jdbc.queryForList("SELECT * FROM fwd_user WHERE org_id=? ORDER BY created_at,user_id",org);
        return rows.stream().filter(u->a.platformAdmin()||!Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM ds_management_membership WHERE user_id=? AND active)",Boolean.class,u.get("user_id")))).map(this::publicUser).toList();
    }
    public Map<String,Object> ownAccount(){return publicUser(target(auth.actor().userId(),false));}
    private List<UUID> validateTypes(AuthorizationService.Actor actor,UUID org,List<UUID> ids,UUID primary) {
        var unique=distinct(ids);
        if(primary!=null&&!unique.contains(primary))fail(HttpStatus.BAD_REQUEST,"PRIMARY_TYPE_MUST_BE_ASSIGNED");
        if(!unique.isEmpty()&&primary==null)fail(HttpStatus.BAD_REQUEST,"PRIMARY_TYPE_REQUIRED");
        Set<UUID> delegated=new HashSet<>();
        if(!actor.platformAdmin())delegated.addAll(jdbc.queryForList(
            "SELECT unnest(delegated_type_ids) FROM ds_management_membership WHERE user_id=? AND qualification='ENTERPRISE_MANAGER' AND active",UUID.class,actor.userId()));
        for(UUID id:unique) {
            if(!actor.platformAdmin()&&!delegated.contains(id))fail(HttpStatus.FORBIDDEN,"TYPE_NOT_DELEGATED");
            var rows=jdbc.queryForList("""
                SELECT r.* FROM role r WHERE role_id=? AND type_state='ACTIVE'
                 AND (type_scope='GLOBAL' OR org_id=?)
                 AND (cardinality(applicable_org_ids)=0 OR ?=ANY(applicable_org_ids))
                 AND (jsonb_array_length(industry_tags)=0 OR EXISTS(SELECT 1 FROM ds_enterprise_identity e
                      WHERE e.org_id=? AND e.state='CONFIRMED' AND jsonb_exists(r.industry_tags,e.industry_kind)))
                """,id,org,org,org);
            if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"TYPE_NOT_APPLICABLE_TO_ENTERPRISE");
            var bad=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM role_permission rp JOIN ds_operation o ON o.permission_id=rp.per_id
                 WHERE rp.role_id=? AND o.group_code IN('identity','platform'))
                """,Boolean.class,id);
            if(Boolean.TRUE.equals(bad))fail(HttpStatus.FORBIDDEN,"MANAGEMENT_OPERATIONS_ARE_PROTECTED");
        }
        if(primary!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT read_only_profile FROM role WHERE role_id=?",Boolean.class,primary))) {
            for(UUID id:unique)if(!id.equals(primary)) {
                var codes=jdbc.queryForList("SELECT o.operation_code FROM role_permission rp JOIN ds_operation o ON o.permission_id=rp.per_id WHERE rp.role_id=?",String.class,id);
                if(codes.stream().anyMatch(c->OperationCatalog.isBusiness(c)&&!OperationCatalog.isReadOnlyBusinessOperation(c)))
                    fail(HttpStatus.CONFLICT,"READ_ONLY_CONFLICTING_ADDITIONAL_TYPE");
            }
        }
        return unique;
    }
    private void replaceTypes(UUID uid,List<UUID> ids,UUID primary) {
        jdbc.update("DELETE FROM user_role WHERE user_id=?",uid);
        for(UUID id:ids)jdbc.update("INSERT INTO user_role(user_id,role_id,is_primary) VALUES(?,?,?)",uid,id,id.equals(primary));
    }
    @Transactional
    public Map<String,Object> createUser(UserCreate req) {
        auth.lockIdentityMutation();var a=manager(req.orgId(),"identity.member.create");liveOrg(req.orgId());
        String username=req.username()==null?"":Normalizer.normalize(req.username().strip(),Normalizer.Form.NFKC);
        if(!username.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{2,63}"))fail(HttpStatus.BAD_REQUEST,"USERNAME_INVALID");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_reserved_username WHERE normalized_name=lower(?))",Boolean.class,username)))
            fail(HttpStatus.CONFLICT,"USERNAME_PERMANENTLY_RESERVED");
        sessions.validateNewPassword(req.temporaryPassword());var types=validateTypes(a,req.orgId(),req.typeIds(),req.primaryTypeId());
        UUID uid=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fwd_user(user_id,user_name,password_hash,org_id,status_code,account_state,password_change_required)
             VALUES(?,?,?,?,0,'PENDING',true)
            """,uid,username,hashes.encode(req.temporaryPassword()),req.orgId());
        jdbc.update("INSERT INTO ds_reserved_username(normalized_name,user_id) VALUES(lower(?),?)",username,uid);
        replaceTypes(uid,types,req.primaryTypeId());bumpPolicy();
        event(a,"identity.member.create","IDENTITY",uid,"MEMBER_CREATED",null,Map.of("orgId",req.orgId(),"accountState","PENDING","typeIds",types));
        return publicUser(target(uid,false));
    }
    private record PreparedAccount(AuthorizationService.Actor actor,Map<String,Object> user,UUID oldOrg,UUID newOrg,String oldName,String name,boolean moved,List<UUID> types,UUID primary) {}
    private PreparedAccount prepareAccount(UUID uid,AccountUpdate req) {
        reviews.fence();var a=auth.actor();var u=managedTarget(uid,"identity.member.update");
        cas(req.expectedVersion(),u.get("permission_version"));
        UUID oldOrg=(UUID)u.get("org_id"),newOrg=req.orgId()==null?oldOrg:req.orgId();
        auth.requireMemberManagement(newOrg,"identity.member.update");liveOrg(newOrg);
        String oldName=(String)u.get("user_name");
        String name=req.username()==null?oldName:Normalizer.normalize(req.username().strip(),Normalizer.Form.NFKC);
        if(!name.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{2,63}"))fail(HttpStatus.BAD_REQUEST,"USERNAME_INVALID");
        var reserved=jdbc.queryForList("SELECT user_id FROM ds_reserved_username WHERE normalized_name=lower(?)",UUID.class,name);
        if(!reserved.isEmpty()&&!uid.equals(reserved.get(0)))fail(HttpStatus.CONFLICT,"USERNAME_PERMANENTLY_RESERVED");
        boolean moved=!oldOrg.equals(newOrg);
        if(moved&&req.typeIds()==null)fail(HttpStatus.BAD_REQUEST,"EXPLICIT_NEW_ENTERPRISE_TYPES_REQUIRED");
        List<UUID> types;UUID primary;
        if(req.typeIds()!=null){types=req.typeIds();primary=req.primaryTypeId();}
        else {
            types=jdbc.queryForList("SELECT role_id FROM user_role WHERE user_id=? ORDER BY is_primary DESC,role_id",UUID.class,uid);
            var primaries=jdbc.queryForList("SELECT role_id FROM user_role WHERE user_id=? AND is_primary",UUID.class,uid);
            primary=primaries.isEmpty()?null:primaries.get(0);
        }
        types=validateTypes(a,newOrg,types,primary);
        return new PreparedAccount(a,u,oldOrg,newOrg,oldName,name,moved,types,primary);
    }
    @Transactional
    public Map<String,Object> updateAccount(UUID uid,AccountUpdate req) {
        var prepared=prepareAccount(uid,req);var a=prepared.actor();var u=prepared.user();
        UUID oldOrg=prepared.oldOrg(),newOrg=prepared.newOrg(),primary=prepared.primary();String oldName=prepared.oldName(),name=prepared.name();boolean moved=prepared.moved();var types=prepared.types();
        var receipt=reviews.begin("ACCOUNT_UPDATE",uid,req);
        jdbc.update("INSERT INTO ds_reserved_username(normalized_name,user_id) VALUES(lower(?),?) ON CONFLICT DO NOTHING",name,uid);
        jdbc.update("UPDATE fwd_user SET user_name=?,org_id=? WHERE user_id=?",name,newOrg,uid);
        replaceTypes(uid,types,primary);
        if(moved)jdbc.update("UPDATE ds_management_membership SET delegated_type_ids='{}',version=version+1 WHERE user_id=? AND qualification='ENTERPRISE_MANAGER'",uid);
        sessions.revokeAll(uid,"ACCOUNT_IDENTITY_UPDATED");sessions.clearCachedIdentity(uid,oldName);bumpPolicy();
        event(a,"identity.member.update","IDENTITY",uid,"ACCOUNT_IDENTITY_UPDATED",
            Map.of("orgId",oldOrg,"username",oldName,"permissionVersion",u.get("permission_version")),
            Map.of("orgId",newOrg,"username",name,"typeIds",types,"permissionVersion",number(u.get("permission_version"))+1));
        reviews.finish(receipt);return publicUser(target(uid,false));
    }

    private record PreparedAssignment(AuthorizationService.Actor actor,List<UUID> types) {}
    private PreparedAssignment prepareAssignment(UUID uid,TypeAssignment req) {
        reviews.fence();var u=managedTarget(uid,"identity.member.type.assign");cas(req.expectedVersion(),u.get("permission_version"));
        var a=auth.actor();var types=validateTypes(a,(UUID)u.get("org_id"),req.typeIds(),req.primaryTypeId());
        return new PreparedAssignment(a,types);
    }
    @Transactional
    public Map<String,Object> assignTypes(UUID uid,TypeAssignment req) {
        var prepared=prepareAssignment(uid,req);var a=prepared.actor();var types=prepared.types();var receipt=reviews.begin("USER_TYPES",uid,req);
        replaceTypes(uid,types,req.primaryTypeId());sessions.revokeAll(uid,"TYPE_ASSIGNMENT_CHANGED");bumpPolicy();
        event(a,"identity.member.type.assign","IDENTITY",uid,"TYPE_ASSIGNMENT_CHANGED",null,Map.of("typeIds",types));
        reviews.finish(receipt);return publicUser(target(uid,false));
    }
    @Transactional
    public Map<String,Object> changeState(UUID uid,StateChange req) {
        auth.lockIdentityMutation();var a=auth.actor();var u=managedTarget(uid,"identity.member.disable");cas(req.expectedVersion(),u.get("permission_version"));
        String state=req.state();
        if(!Set.of("ACTIVE","DISABLED").contains(state==null?"":state))fail(HttpStatus.BAD_REQUEST,"USE_EXPLICIT_CLOSE_OPERATION");
        if("ACTIVE".equals(state)) {
            liveOrg((UUID)u.get("org_id"));
            if(Boolean.TRUE.equals(u.get("password_change_required"))||u.get("credential_activated_at")==null)state="PENDING";
        }
        jdbc.update("UPDATE fwd_user SET account_state=? WHERE user_id=?",state,uid);sessions.revokeAll(uid,"ACCOUNT_STATE_CHANGED");bumpPolicy();
        event(a,"identity.member.disable","IDENTITY",uid,"ACCOUNT_STATE_CHANGED",
            Map.of("accountState",u.get("account_state")),Map.of("accountState",state));
        return publicUser(target(uid,false));
    }
    @Transactional
    public Map<String,Object> resetPassword(UUID uid,PasswordReset req) {
        auth.lockIdentityMutation();var a=auth.actor();var u=managedTarget(uid,"identity.member.reset");cas(req.expectedVersion(),u.get("permission_version"));
        sessions.validateNewPassword(req.temporaryPassword());
        jdbc.update("""
            UPDATE fwd_user SET password_hash=?,account_state='PENDING',password_change_required=true,credential_activated_at=NULL
              WHERE user_id=?
            """,hashes.encode(req.temporaryPassword()),uid);
        sessions.revokeAll(uid,"ADMIN_PASSWORD_RESET");bumpPolicy();
        event(a,"identity.member.reset","IDENTITY",uid,"RESET_REQUIRES_ACTIVATION",null,Map.of("accountState","PENDING"));
        return publicUser(target(uid,false));
    }
    private void close(UUID uid,Map<String,Object> u,AuthorizationService.Actor actor,String operation) {
        jdbc.update("UPDATE fwd_user SET account_state='CLOSED',status_code=NULL WHERE user_id=?",uid);
        sessions.revokeAll(uid,"ACCOUNT_CLOSED");bumpPolicy();
        event(actor,operation,"IDENTITY",uid,"CLOSED_HISTORY_RETAINED",Map.of("accountState",u.get("account_state")),Map.of("accountState","CLOSED"));
    }
    @Transactional
    public void closeMember(UUID uid,Long expected) {
        auth.lockIdentityMutation();var a=auth.actor();var u=managedTarget(uid,"identity.member.close");cas(expected,u.get("permission_version"));
        close(uid,u,a,"identity.member.close");
    }
    @Transactional
    public void closeSelf(Long expected) {
        auth.lockIdentityMutation();var a=auth.requireOperation("identity.self.close");var u=target(a.userId(),true);cas(expected,u.get("permission_version"));
        close(a.userId(),u,a,"identity.self.close");
    }
    @Transactional
    public void forceLogout(UUID uid,Long expected) {
        auth.lockIdentityMutation();var a=auth.actor();var u=managedTarget(uid,"identity.member.disable");cas(expected,u.get("permission_version"));
        sessions.revokeAll(uid,"ADMIN_FORCED_LOGOUT");
        event(a,"identity.member.disable","IDENTITY",uid,"ADMIN_FORCED_LOGOUT",null,null);
    }
    @Transactional
    public int forceLogoutNonAdmin() {
        auth.lockIdentityMutation();auth.requirePlatformManagement("identity.platform-admin.manage");var a=auth.actor();
        var ids=jdbc.queryForList("""
            SELECT u.user_id FROM fwd_user u WHERE u.account_state<>'CLOSED' AND NOT EXISTS(
             SELECT 1 FROM ds_management_membership m WHERE m.user_id=u.user_id AND m.qualification='PLATFORM_ADMIN' AND m.active)
            """,UUID.class);
        for(UUID uid:ids)sessions.revokeAll(uid,"ADMIN_BULK_LOGOUT");
        event(a,"identity.platform-admin.manage","PLATFORM",a.orgId(),"BULK_LOGOUT",null,Map.of("count",ids.size()));return ids.size();
    }
    private record PreparedQualification(AuthorizationService.Actor actor,List<UUID> delegated) {}
    private PreparedQualification prepareQualification(UUID uid,QualificationChange req) {
        reviews.fence();auth.requirePlatformManagement("identity.platform-admin.manage");var a=auth.actor();var u=target(uid,true);
        if("CLOSED".equals(u.get("account_state")))fail(HttpStatus.CONFLICT,"CLOSED_IDENTITY_IS_TERMINAL");
        if(!Set.of("PLATFORM_ADMIN","ENTERPRISE_MANAGER").contains(req.qualification()==null?"":req.qualification()))fail(HttpStatus.BAD_REQUEST,"QUALIFICATION_INVALID");
        if("PLATFORM_ADMIN".equals(req.qualification())&&req.active()) {
            if(!"ACTIVE".equals(u.get("account_state"))||Boolean.TRUE.equals(u.get("password_change_required"))||
               !(u.get("last_login_at") instanceof java.sql.Timestamp login)||
               !(u.get("credential_activated_at") instanceof java.sql.Timestamp credential)||login.before(credential))
                fail(HttpStatus.CONFLICT,"ACTIVATE_AND_LOGIN_BEFORE_ADMIN_PROMOTION");
        }

        var found=jdbc.queryForList("SELECT version FROM ds_management_membership WHERE user_id=? AND qualification=?",uid,req.qualification());
        cas(req.expectedVersion(),found.isEmpty()?0L:found.get(0).get("version"));
        var delegated=distinct(req.delegatedTypeIds());
        if("PLATFORM_ADMIN".equals(req.qualification())&&!delegated.isEmpty())fail(HttpStatus.BAD_REQUEST,"PLATFORM_QUALIFICATION_HAS_NO_BUSINESS_TYPE");
        // Delegation is an explicit subset of applicable business types.
        for(UUID delegatedType:delegated)validateTypes(a,(UUID)u.get("org_id"),List.of(delegatedType),delegatedType);
        return new PreparedQualification(a,delegated);
    }
    @Transactional
    public Map<String,Object> changeQualification(UUID uid,QualificationChange req) {
        var prepared=prepareQualification(uid,req);var a=prepared.actor();var delegated=prepared.delegated();var receipt=reviews.begin("QUALIFICATION",uid,req);
        jdbc.update("""
            INSERT INTO ds_management_membership(user_id,qualification,active,delegated_type_ids,granted_by)
             VALUES(?,?,?,?::uuid[],?)
            ON CONFLICT(user_id,qualification) DO UPDATE SET active=EXCLUDED.active,delegated_type_ids=EXCLUDED.delegated_type_ids,
             version=ds_management_membership.version+1,granted_by=EXCLUDED.granted_by,granted_at=now()
            """,uid,req.qualification(),req.active(),uuidArray(delegated),a.userId());
        sessions.revokeAll(uid,"MANAGEMENT_QUALIFICATION_CHANGED");bumpPolicy();
        event(a,"identity.platform-admin.manage","IDENTITY",uid,"MANAGEMENT_QUALIFICATION_CHANGED",null,
            Map.of("qualification",req.qualification(),"active",req.active(),"typeIds",delegated));
        reviews.finish(receipt);return publicUser(target(uid,false));
    }
    private static String uuidArray(List<UUID> ids){return "{"+String.join(",",ids.stream().map(UUID::toString).toList())+"}";}
    private static String textArray(List<String> values){return "{"+String.join(",",values)+"}";}
    private void validTypeCode(String code) {
        if(code==null)fail(HttpStatus.BAD_REQUEST,"TYPE_CODE_REQUIRED");
        String canonical=Normalizer.normalize(code,Normalizer.Form.NFKC).strip().toUpperCase(Locale.ROOT);
        String compact=canonical.replaceAll("[^A-Z0-9]","");
        if(!code.equals(canonical)||!code.matches("[A-Z][A-Z0-9_]{2,63}")||Set.of("ADMIN","SUPERADMIN","ORGADMIN").contains(compact)||compact.startsWith("ROLE"))
            fail(HttpStatus.BAD_REQUEST,"RESERVED_OR_INVALID_TYPE_CODE");
    }
    private Map<String,Object> type(UUID id) {
        var rows=jdbc.queryForList("SELECT * FROM role WHERE role_id=? AND type_state<>'DELETED'",id);
        if(rows.size()!=1)fail(HttpStatus.NOT_FOUND,"TYPE_NOT_AVAILABLE");return rows.get(0);
    }
    private void validType(TypeChange req) {
        validTypeCode(req.code());
        if(req.name()==null||req.name().isBlank()||req.name().length()>64)fail(HttpStatus.BAD_REQUEST,"TYPE_NAME_INVALID");
        if(!Set.of("GLOBAL","ENTERPRISE").contains(req.scope()==null?"":req.scope()))fail(HttpStatus.BAD_REQUEST,"TYPE_SCOPE_INVALID");
        if(!Set.of("ACTIVE","DISABLED").contains(req.state()==null?"":req.state()))fail(HttpStatus.BAD_REQUEST,"TYPE_STATE_INVALID");
        liveOrg(req.orgId());
        for(String tag:distinct(req.industryTags()))if(!Set.of("MANUFACTURER","SUPPLIER","WAREHOUSE","CARRIER","CUSTOMER","SERVICE").contains(tag))
            fail(HttpStatus.BAD_REQUEST,"INDUSTRY_TAG_INVALID");
        for(UUID org:distinct(req.applicableOrgIds())){liveOrg(org);if("ENTERPRISE".equals(req.scope())&&!org.equals(req.orgId()))fail(HttpStatus.BAD_REQUEST,"ENTERPRISE_TYPE_SCOPE_MISMATCH");}
        for(String op:distinct(req.operations()))if(!OperationCatalog.isKnown(op)||!OperationCatalog.isBusiness(op))fail(HttpStatus.BAD_REQUEST,"BUSINESS_OPERATION_REQUIRED");
        if(req.readOnly()&&distinct(req.operations()).stream().anyMatch(c->!OperationCatalog.isReadOnlyBusinessOperation(c)))
            fail(HttpStatus.BAD_REQUEST,"READ_ONLY_TYPE_CONTAINS_WRITE_OR_EXPORT");
    }
    private void replaceOperations(UUID id,List<String> codes) {
        jdbc.update("DELETE FROM role_permission WHERE role_id=?",id);
        for(String code:distinct(codes))jdbc.update("INSERT INTO role_permission(role_id,per_id) SELECT ?,permission_id FROM ds_operation WHERE operation_code=?",id,code);
    }
    private Map<String,Object> typeAuditState(UUID id,Map<String,Object> role) {
        var operations=jdbc.queryForList("""
            SELECT o.operation_code FROM role_permission p JOIN ds_operation o ON o.permission_id=p.per_id
            WHERE p.role_id=? ORDER BY o.operation_code
            """,String.class,id);
        return Map.of("typeCode",role.get("role_code"),"typeName",role.get("role_name"),"orgId",role.get("org_id"),
            "state",role.get("type_state"),"typeScope",role.get("type_scope"),"typeVersion",role.get("type_version"),
            "readOnly",role.get("read_only_profile"),"operations",operations);
    }
    private void invalidateTypeMembers(UUID id) {
        for(UUID uid:reviews.affectedType(id))sessions.revokeAll(uid,"TYPE_POLICY_CHANGED");
        bumpPolicy();
    }
    public List<Map<String,Object>> organizations(){
        var actor=auth.requireOperation("identity.member.read");
        auth.requireMemberManagement(actor.platformAdmin()?null:actor.orgId(),"identity.member.read");
        return normalise(jdbc.queryForList("""
            SELECT o.org_id AS "orgId",o.org_name AS "orgName",o.org_status AS status,
                   e.entity_id AS "entityId",e.industry_kind AS "industryKind",e.state AS "mappingState",e.identity_kind AS "identityKind"
            FROM org o LEFT JOIN ds_enterprise_identity e ON e.org_id=o.org_id
            WHERE (? OR o.org_id=?) ORDER BY o.org_name,o.org_id
            """,actor.platformAdmin(),actor.orgId()));
    }
    private <T> List<T> visibleTypes(UUID id,String columns,RowMapper<T> mapper) {
        var a=auth.requireOperation("identity.member.read");
        if(!a.platformAdmin()&&!a.enterpriseManager())fail(HttpStatus.FORBIDDEN,"TYPE_MANAGEMENT_READ_DENIED");
        return jdbc.query("""
            WITH caller AS (SELECT ?::boolean AS platform,?::uuid AS org_id,?::uuid AS user_id)
            SELECT %s FROM role r CROSS JOIN caller a
            WHERE r.type_state<>'DELETED' AND (?::uuid IS NULL OR r.role_id=?) AND (a.platform OR (
             (r.type_scope='GLOBAL' OR r.org_id=a.org_id)
             AND (cardinality(r.applicable_org_ids)=0 OR a.org_id=ANY(r.applicable_org_ids))
             AND r.role_id IN(SELECT unnest(delegated_type_ids) FROM ds_management_membership
                WHERE user_id=a.user_id AND qualification='ENTERPRISE_MANAGER' AND active)))
            ORDER BY r.role_code
            """.formatted(columns),mapper,a.platformAdmin(),a.orgId(),a.userId(),id,id);
    }
    public List<Map<String,Object>> types() {
        return normalise(visibleTypes(null,"""
             r.role_id AS "typeId",
             CASE WHEN a.platform OR r.org_id=a.org_id THEN r.org_id END AS "orgId",
             r.role_code AS code,r.role_name AS name,r.description,
             r.type_scope AS scope,r.type_state AS state,r.type_version AS version,r.industry_tags AS "industryTags",
             CASE WHEN a.platform THEN r.applicable_org_ids
                  ELSE ARRAY(SELECT a.org_id WHERE a.org_id=ANY(r.applicable_org_ids))
             END AS "applicableOrgIds",r.read_only_profile AS "readOnly",
             COALESCE((SELECT jsonb_agg(o.operation_code ORDER BY o.operation_code) FROM role_permission rp JOIN ds_operation o
                ON o.permission_id=rp.per_id WHERE rp.role_id=r.role_id),'[]'::jsonb) AS operations,
             (SELECT count(*) FROM user_role ur JOIN fwd_user counted ON counted.user_id=ur.user_id
                WHERE ur.role_id=r.role_id AND (a.platform OR counted.org_id=a.org_id)) AS "assignedCount"
            """,new ColumnMapRowMapper()));
    }
    public List<Role> legacyRoles(UUID id) {
        return visibleTypes(id,"""
            r.role_id, CASE WHEN a.platform OR r.org_id=a.org_id THEN r.org_id END AS org_id,
            r.role_code,r.role_name,r.is_builtin,r.type_version,r.type_state,r.type_scope,r.read_only_profile,
            r.created_at,CASE WHEN a.platform THEN r.created_by END AS created_by,
            r.updated_at,CASE WHEN a.platform THEN r.updated_by END AS updated_by
            """,BeanPropertyRowMapper.newInstance(Role.class));
    }
    @Transactional
    public UUID createType(TypeChange req) {
        auth.lockIdentityMutation();auth.requirePlatformManagement("identity.type.manage");validType(req);var a=auth.actor();UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO role(role_id,org_id,role_code,role_name,is_builtin,created_by,type_scope,type_state,description,industry_tags,applicable_org_ids,read_only_profile)
             VALUES(?,?,?,?,false,?,?,?,?,?::jsonb,?::uuid[],?)
            """,id,req.orgId(),req.code(),req.name(),a.userId(),req.scope(),req.state(),req.description()==null?"":req.description(),
             json(distinct(req.industryTags())),uuidArray(distinct(req.applicableOrgIds())),req.readOnly());
        replaceOperations(id,req.operations());bumpPolicy();
        event(a,"identity.type.manage","TYPE",id,"TYPE_CREATED",null,typeAuditState(id,type(id)));return id;
    }
    private Map<String,Object> prepareTypeUpdate(UUID id,TypeChange req) {
        reviews.fence();auth.requirePlatformManagement("identity.type.manage");var old=type(id);cas(req.expectedVersion(),old.get("type_version"));validType(req);
        var operator=auth.actor();
        if(!Objects.equals(old.get("org_id"),req.orgId()))fail(HttpStatus.BAD_REQUEST,"TYPE_OWNER_IMMUTABLE");
        return old;
    }
    @Transactional
    public void updateType(UUID id,TypeChange req) {
        var old=prepareTypeUpdate(id,req);var operator=auth.actor();var receipt=reviews.begin("TYPE_UPDATE",id,req);
        var before=typeAuditState(id,old);
        jdbc.update("""
            UPDATE role SET role_code=?,role_name=?,description=?,type_scope=?,type_state=?,industry_tags=?::jsonb,
              applicable_org_ids=?::uuid[],read_only_profile=?,type_version=type_version+1,updated_by=? WHERE role_id=?
            """,req.code(),req.name(),req.description()==null?"":req.description(),req.scope(),req.state(),
              json(distinct(req.industryTags())),uuidArray(distinct(req.applicableOrgIds())),req.readOnly(),operator.userId(),id);
        replaceOperations(id,req.operations());invalidateTypeMembers(id);
        event(operator,"identity.type.manage","TYPE",id,"TYPE_UPDATED",before,typeAuditState(id,type(id)));
        reviews.finish(receipt);
    }
    private Map<String,Object> prepareTypeDelete(UUID id,TypeDeletion req,boolean preview) {
        reviews.fence();auth.requirePlatformManagement("identity.type.manage");var a=auth.actor();var old=type(id);cas(req.expectedVersion(),old.get("type_version"));
        var affected=reviews.affectedType(id);UUID replacement=req.replacementTypeId();
        if(!affected.isEmpty()&&replacement==null&&!preview)fail(HttpStatus.CONFLICT,"TYPE_IN_USE_REPLACEMENT_REQUIRED");
        if(replacement!=null){
            if(replacement.equals(id))fail(HttpStatus.BAD_REQUEST,"REPLACEMENT_MUST_DIFFER");var next=type(replacement);cas(req.replacementTypeVersion(),next.get("type_version"));
            for(UUID uid:affected){var u=target(uid,true);var rows=jdbc.queryForList("SELECT role_id,is_primary FROM user_role WHERE user_id=? ORDER BY role_id",uid);var ids=new LinkedHashSet<UUID>();UUID primary=null;boolean assigned=false;
                for(var row:rows){UUID rid=(UUID)row.get("role_id");if(rid.equals(id)){rid=replacement;assigned=true;}ids.add(rid);if(Boolean.TRUE.equals(row.get("is_primary")))primary=rid;}
                if(assigned)validateTypes(a,(UUID)u.get("org_id"),new ArrayList<>(ids),primary);
                if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_management_membership WHERE user_id=? AND ?=ANY(delegated_type_ids))",Boolean.class,uid,id)))validateTypes(a,(UUID)u.get("org_id"),List.of(replacement),replacement);
            }
        }return old;
    }
    @Transactional
    public Map<String,Object> preview(String action,UUID id,Object req) {
        switch(action){
            case "ACCOUNT_UPDATE"->prepareAccount(id,(AccountUpdate)req);
            case "USER_TYPES"->prepareAssignment(id,(TypeAssignment)req);
            case "QUALIFICATION"->prepareQualification(id,(QualificationChange)req);
            case "TYPE_UPDATE"->prepareTypeUpdate(id,(TypeChange)req);
            case "TYPE_DELETE"->prepareTypeDelete(id,(TypeDeletion)req,true);
            default->throw new IllegalArgumentException("Unknown identity action");
        }return reviews.create(action,id,req);
    }
    @Transactional
    public void deleteType(UUID id,Long expected,UUID replacement,Long replacementVersion) {
        var req=new TypeDeletion(expected,replacement,replacementVersion);var old=prepareTypeDelete(id,req,false);var a=auth.actor();var receipt=reviews.begin("TYPE_DELETE",id,req);var affected=reviews.affectedType(id);
        var before=typeAuditState(id,old);
        if(replacement!=null){
            var members=jdbc.queryForList("SELECT user_id,is_primary FROM user_role WHERE role_id=? ORDER BY user_id",id);
            for(var member:members){UUID uid=(UUID)member.get("user_id");boolean primary=Boolean.TRUE.equals(member.get("is_primary"));
                jdbc.update("DELETE FROM user_role WHERE user_id=? AND role_id=?",uid,id);
                jdbc.update("INSERT INTO user_role(user_id,role_id,is_primary) VALUES(?,?,?) ON CONFLICT(user_id,role_id) DO UPDATE SET is_primary=user_role.is_primary OR EXCLUDED.is_primary",uid,replacement,primary);
            }
            jdbc.update("""
                UPDATE ds_management_membership m SET delegated_type_ids=(SELECT array_agg(v ORDER BY v) FROM (SELECT DISTINCT CASE WHEN d=? THEN ? ELSE d END AS v FROM unnest(m.delegated_type_ids) d) migrated),version=version+1
                WHERE qualification='ENTERPRISE_MANAGER' AND ?=ANY(delegated_type_ids)
                """,id,replacement,id);
        }
        jdbc.update("UPDATE role SET type_state='DELETED',type_version=type_version+1,updated_by=? WHERE role_id=?",a.userId(),id);jdbc.update("DELETE FROM role_permission WHERE role_id=?",id);
        for(UUID uid:affected)sessions.revokeAll(uid,"TYPE_DELETED_AND_REPLACED");bumpPolicy();
        var after=new LinkedHashMap<String,Object>();
        after.put("state","DELETED");after.put("count",affected.size());after.put("typeVersion",number(old.get("type_version"))+1);
        if(replacement!=null)after.put("replacementTypeId",replacement);
        event(a,"identity.type.manage","TYPE",id,"TYPE_DELETED_HISTORY_RETAINED",before,after);reviews.finish(receipt);
    }

    public record CloseTarget(UUID userId,Long expectedVersion) {}
    public record BatchClose(List<CloseTarget> accounts,boolean confirm) {}

    @Transactional
    public int closeMembers(BatchClose req) {
        if(req==null||!req.confirm())fail(HttpStatus.BAD_REQUEST,"BATCH_CLOSE_CONFIRMATION_REQUIRED");
        if(req.accounts()==null||req.accounts().isEmpty()||req.accounts().size()>50)
            fail(HttpStatus.BAD_REQUEST,"BATCH_CLOSE_SIZE_INVALID");
        var targets=new TreeMap<UUID,CloseTarget>();
        for(var entry:req.accounts()) {
            if(entry==null||entry.userId()==null)fail(HttpStatus.BAD_REQUEST,"BATCH_CLOSE_TARGET_INVALID");
            if(targets.putIfAbsent(entry.userId(),entry)!=null)fail(HttpStatus.BAD_REQUEST,"BATCH_CLOSE_TARGET_DUPLICATE");
        }
        auth.lockIdentityMutation();
        var actor=auth.requireOperation("identity.member.close");
        var checked=new LinkedHashMap<UUID,Map<String,Object>>();
        for(var entry:targets.values()) {
            var account=managedTarget(entry.userId(),"identity.member.close");
            cas(entry.expectedVersion(),account.get("permission_version"));
            checked.put(entry.userId(),account);
        }
        // Validate the whole batch before changing any account. The deferred
        // last-administrator guard also protects the commit as a single unit.
        checked.forEach((uid,account)->close(uid,account,actor,"identity.member.close"));
        return checked.size();
    }
}
