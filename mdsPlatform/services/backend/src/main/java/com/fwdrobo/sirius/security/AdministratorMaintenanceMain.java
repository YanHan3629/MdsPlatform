package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** SSH-only maintenance entry; stdin JSON is never echoed, logged or accepted by HTTP. */
public final class AdministratorMaintenanceMain {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final SeedCredentialPolicy seeds=new SeedCredentialPolicy();private final PasswordHashService hashes=new PasswordHashService();
    private final Map<String,Object> request;private final String action;private final String job;
    private static final Set<String> ACTIONS=Set.of("Inspect","Initialize","Upgrade","Recover","SetMaintenance");
    private static final Set<String> FIELDS=Set.of("action","instanceId","targetDatabase","username","password","currentPassword",
        "targetUserId","reasonCode","maintenance","reactivateOrganization","confirm");
    private AdministratorMaintenanceMain(Map<String,Object> input,ObjectMapper mapper) {
        this.request=input;this.json=mapper;this.action=text("action");this.job=requiredEnv("SLURM_JOB_ID");
        if(!job.matches("[0-9]+")||!ACTIONS.contains(action))reject("MAINTENANCE_COMPUTE_JOB_REQUIRED");
        if(!FIELDS.containsAll(input.keySet()))reject("UNDECLARED_MAINTENANCE_FIELD");
        if(!Objects.equals(text("instanceId"),requiredEnv("MDS_INSTANCE_ID")))reject("TARGET_INSTANCE_MISMATCH");
        String url=requiredEnv("SPRING_DATASOURCE_URL");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/[A-Za-z0-9_]+"))reject("LOOPBACK_DATABASE_REQUIRED");
        var ds=new DriverManagerDataSource(url,requiredEnv("SPRING_DATASOURCE_USERNAME"),requiredEnv("SPRING_DATASOURCE_PASSWORD"));
        this.jdbc=new JdbcTemplate(ds);this.tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        if(!text("targetDatabase").equals(jdbc.queryForObject("SELECT current_database()",String.class)))reject("TARGET_DATABASE_MISMATCH");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(true).validateMigrationNaming(true).load().migrate();
    }
    private static void reject(String code){throw new IllegalArgumentException(code);}
    private static String requiredEnv(String key){String value=System.getenv(key);if(value==null||value.isBlank())reject("REQUIRED_PRIVATE_CONFIGURATION_MISSING");return value;}
    private String text(String key){Object value=request.get(key);return value instanceof String s?s:"";}
    private boolean flag(String key){return Boolean.TRUE.equals(request.get(key));}
    private String reason(){
        String value=text("reasonCode");if(!value.matches("[A-Z][A-Z0-9_]{7,79}"))reject("AUDIT_REASON_CODE_REQUIRED");return value;
    }
    private String password() {
        String value=text("password");
        com.fwdrobo.sirius.util.PasswordValidator.validate(value);
        if(seeds.isKnownSeedPassword(value))reject("PUBLIC_DEFAULT_PASSWORD_NOT_ALLOWED");
        return value;
    }
    private String username() {
        String value=Normalizer.normalize(text("username").strip(),Normalizer.Form.NFKC);
        if(!value.matches("[\\p{L}\\p{N}][\\p{L}\\p{N}_.-]{2,63}"))reject("USERNAME_INVALID");return value;
    }
    private UUID target() {try{return UUID.fromString(text("targetUserId"));}catch(Exception invalid){reject("EXPLICIT_TARGET_USER_REQUIRED");return null;}}
    private Map<String,Object> user(UUID id){
        var rows=jdbc.queryForList("SELECT * FROM fwd_user WHERE user_id=? FOR NO KEY UPDATE",id);
        if(rows.size()!=1)reject("TARGET_USER_NOT_AVAILABLE");return rows.get(0);
    }
    private Map<String,Object> mode(){return jdbc.queryForMap("SELECT * FROM ds_auth_initialization WHERE singleton FOR UPDATE");}
    private long liveAdmins() {
        return jdbc.queryForObject("""
            SELECT count(*) FROM ds_management_membership m JOIN fwd_user u ON u.user_id=m.user_id JOIN org o ON o.org_id=u.org_id
            WHERE m.qualification='PLATFORM_ADMIN' AND m.active AND u.account_state='ACTIVE' AND u.status_code IS NOT NULL
              AND NOT u.password_change_required AND u.credential_activated_at IS NOT NULL AND u.password_hash<>'' AND o.org_status=1
            """,Long.class);
    }
    private void revoke(UUID uid,String reason) {
        jdbc.update("UPDATE fwd_user SET token_version=token_version+1,permission_version=permission_version+1,status_code=CASE WHEN status_code IS NULL THEN NULL ELSE 0 END WHERE user_id=?",uid);
        jdbc.update("UPDATE ds_auth_session SET revoked_at=now(),revoke_reason=? WHERE user_id=? AND revoked_at IS NULL",reason,uid);
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception invalid){throw new IllegalStateException("SAFE_METADATA_ENCODING_FAILED");}}
    private void audit(UUID uid,String result) {
        var scope=Map.of("jobId",job,"instanceId",text("instanceId"),"targetDatabase",text("targetDatabase"),
            "sshOperator",requiredEnv("USER"),"action",action,"result",result);
        jdbc.update("""
            INSERT INTO ds_authorization_audit(operation_code,target_kind,target_id,outcome,request_id,scope,reason_code)
            VALUES('identity.platform-admin.manage','MAINTENANCE_IDENTITY',?,'SUCCESS',?,?::jsonb,?)
            """,uid==null?null:uid.toString(),UUID.randomUUID().toString(),encode(scope),reason());
    }
    private void qualify(UUID uid) {
        jdbc.update("""
            INSERT INTO ds_management_membership(user_id,qualification,active,granted_by) VALUES(?,'PLATFORM_ADMIN',true,?)
            ON CONFLICT(user_id,qualification) DO UPDATE SET active=true,version=ds_management_membership.version+1,
              granted_by=EXCLUDED.granted_by,granted_at=now()
            """,uid,uid);
    }
    private void activeCredential(UUID uid,String hash) {
        jdbc.update("""
            UPDATE fwd_user SET password_hash=?,account_state='ACTIVE',password_change_required=false,
              credential_activated_at=now(),status_code=0 WHERE user_id=?
            """,hash,uid);
        revoke(uid,"CONTROLLED_MAINTENANCE_CREDENTIAL");
    }
    private void quarantineOtherPublicSeeds(UUID selected) {
        for(var row:jdbc.queryForList("SELECT user_id,password_hash,account_state FROM fwd_user ORDER BY user_id")) {
            UUID uid=(UUID)row.get("user_id");
            if(!uid.equals(selected)&&!"CLOSED".equals(row.get("account_state"))&&seeds.isKnownSeedHash((String)row.get("password_hash"))) {
                jdbc.update("UPDATE fwd_user SET account_state='PENDING',password_change_required=true,credential_activated_at=NULL WHERE user_id=?",uid);
                revoke(uid,"PUBLIC_SEED_REQUIRES_CONTROLLED_ACTIVATION");
            }
        }
    }
    private void initialTypes(UUID org,UUID creator) {
        if(Boolean.TRUE.equals(mode().get("initial_templates_installed")))return;
        var base=new LinkedHashSet<>(List.of("data.catalog.read","data.preview","data.search","data.qa","data.view.export",
            "dataset.create","dataset.upload","dataset.draft.manage","dataset.quality.read","application.submit",
            "contract.read","delivery.receipt.read","job.read","job.log.read","job.cancel"));
        var names=Map.of("MANUFACTURER","制造业务成员","SUPPLIER","供应业务成员","WAREHOUSE","仓储业务成员",
            "CARRIER","承运业务成员","CUSTOMER","客户与渠道业务成员","SERVICE","安装与售后业务成员");
        for(String industry:List.of("MANUFACTURER","SUPPLIER","WAREHOUSE","CARRIER","CUSTOMER","SERVICE")) {
            var ops=new LinkedHashSet<>(base);
            if(!industry.equals("CUSTOMER"))ops.addAll(List.of("dataset.publish","dataset.quality.run","dataset.index.build","dataset.index.activate"));
            if(Set.of("MANUFACTURER","SUPPLIER").contains(industry))ops.addAll(List.of("product.publish","product.unpublish","product.summary.manage","application.review","data.raw.download"));
            template(org,creator,industry,names.get(industry),List.of(industry),false,ops);
        }
        template(org,creator,"READ_ONLY","只读使用者",List.of(),true,new LinkedHashSet<>(List.of("data.catalog.read","data.preview","data.search","data.qa")));
        jdbc.update("UPDATE ds_auth_initialization SET initial_templates_installed=true WHERE singleton");
    }
    private void template(UUID org,UUID creator,String code,String name,List<String> tags,boolean readOnly,Set<String> operations) {
        UUID id=UUID.randomUUID();
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM role WHERE org_id=? AND role_code=?)",Boolean.class,org,code)))
            reject("INITIAL_TEMPLATE_CODE_COLLISION_REVIEW_REQUIRED");
        jdbc.update("""
            INSERT INTO role(role_id,org_id,role_code,role_name,is_builtin,created_by,type_scope,industry_tags,read_only_profile,description)
            VALUES(?,?,?,?,false,?,'GLOBAL',?::jsonb,?,'Editable initial business operation configuration; resource authorization remains mandatory')
            """,id,org,code,name,creator,encode(tags),readOnly);
        for(String op:operations) {
            if(!OperationCatalog.isBusiness(op))reject("INITIAL_TEMPLATE_OPERATION_INVALID");
            jdbc.update("INSERT INTO role_permission(role_id,per_id) SELECT ?,permission_id FROM ds_operation WHERE operation_code=?",id,op);
        }
    }
    private Map<String,Object> initialize(Map<String,Object> before) {
        if(Boolean.TRUE.equals(before.get("initialized")))return Map.of("result","ALREADY_INITIALIZED","changed",false);
        if(liveAdmins()!=0)reject("EXISTING_ADMIN_REQUIRES_UPGRADE_REVIEW");
        for(var u:jdbc.queryForList("SELECT password_hash FROM fwd_user"))if(!seeds.isKnownSeedHash((String)u.get("password_hash")))reject("EXISTING_DEPLOYMENT_REQUIRES_UPGRADE");
        if(jdbc.queryForObject("SELECT (SELECT count(*) FROM mm_dataset)+(SELECT count(*) FROM ds_contract)+(SELECT count(*) FROM ds_product)",Long.class)>0)
            reject("EXISTING_DEPLOYMENT_REQUIRES_UPGRADE");
        String name=username(),hash=hashes.encode(password());UUID uid,org;
        var rows=jdbc.queryForList("SELECT user_id,org_id,password_hash FROM fwd_user WHERE lower(user_name)=lower(?)",name);
        if(rows.isEmpty()) {
            uid=UUID.randomUUID();org=UUID.randomUUID();
            jdbc.update("INSERT INTO org(org_id,org_name) VALUES(?,?)",org,"Platform management "+uid);
            jdbc.update("INSERT INTO fwd_user(user_id,user_name,password_hash,org_id,status_code,account_state,credential_activated_at) VALUES(?,?,?,?,0,'ACTIVE',now())",uid,name,hash,org);
        } else {
            if(rows.size()!=1||!seeds.isKnownSeedHash((String)rows.get(0).get("password_hash")))reject("USERNAME_PERMANENTLY_RESERVED");
            uid=(UUID)rows.get(0).get("user_id");org=(UUID)rows.get(0).get("org_id");activeCredential(uid,hash);
        }
        jdbc.update("INSERT INTO ds_reserved_username(normalized_name,user_id) SELECT lower(user_name),user_id FROM fwd_user WHERE user_id=? ON CONFLICT DO NOTHING",uid);
        qualify(uid);quarantineOtherPublicSeeds(uid);initialTypes(org,uid);
        jdbc.update("""
            UPDATE ds_auth_initialization SET initialized=true,legacy_review_completed=true,maintenance_mode=false,
                policy_version=policy_version+1,initialized_by=?,initialized_at=now(),updated_at=now() WHERE singleton
            """,uid);
        audit(uid,"INITIALIZED_ONCE");return Map.of("result","INITIALIZED","changed",true,"userId",uid,"orgId",org,"loginVerificationRequired",true);
    }
    private Map<String,Object> upgrade(Map<String,Object> before) {
        if(Boolean.TRUE.equals(before.get("initialized")))return Map.of("result","ALREADY_INITIALIZED","changed",false);
        if(!Boolean.TRUE.equals(before.get("maintenance_mode")))reject("MAINTENANCE_MODE_REQUIRED");
        UUID uid=target();var u=user(uid);
        if("CLOSED".equals(u.get("account_state")))reject("CLOSED_IDENTITY_IS_TERMINAL");
        UUID org=(UUID)u.get("org_id");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM org WHERE org_id=? AND org_status=1)",Boolean.class,org)))reject("TARGET_ORGANIZATION_INACTIVE");
        String oldHash=(String)u.get("password_hash");
        if(seeds.isKnownSeedHash(oldHash))activeCredential(uid,hashes.encode(password()));
        else {
            if(!text("password").isEmpty())reject("PRESERVE_EXISTING_NONDEFAULT_CREDENTIAL");
            boolean matches=false;
            try{matches=hashes.delegatingPasswordEncoder().matches(text("currentPassword"),oldHash);}catch(IllegalArgumentException ignored){}
            if(!matches)reject("EXISTING_CREDENTIAL_PROOF_REQUIRED");
            jdbc.update("UPDATE fwd_user SET credential_activated_at=now(),password_change_required=false,account_state='ACTIVE' WHERE user_id=?",uid);revoke(uid,"CONTROLLED_ADMIN_UPGRADE");
        }
        jdbc.update("INSERT INTO ds_reserved_username(normalized_name,user_id) SELECT lower(user_name),user_id FROM fwd_user WHERE user_id=? ON CONFLICT DO NOTHING",uid);
        qualify(uid);quarantineOtherPublicSeeds(uid);initialTypes(org,uid);
        jdbc.update("""
            UPDATE ds_auth_initialization SET initialized=true,legacy_review_completed=true,maintenance_mode=false,
               policy_version=policy_version+1,initialized_by=?,initialized_at=now(),updated_at=now() WHERE singleton
            """,uid);
        audit(uid,"LEGACY_QUALIFICATION_EXPLICITLY_REVIEWED");
        return Map.of("result","UPGRADED","changed",true,"userId",uid,"orgId",org,"legacyRoleNamesGrantedNoPrivilege",true,"loginVerificationRequired",true);
    }
    private Map<String,Object> recover(Map<String,Object> before) {
        if(!Boolean.TRUE.equals(before.get("initialized"))||!Boolean.TRUE.equals(before.get("maintenance_mode")))reject("INITIALIZED_MAINTENANCE_INSTANCE_REQUIRED");
        UUID uid=target();var u=user(uid);
        if("CLOSED".equals(u.get("account_state")))reject("CLOSED_IDENTITY_IS_TERMINAL");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_management_membership WHERE user_id=? AND qualification='PLATFORM_ADMIN')",Boolean.class,uid)))
            reject("EXISTING_REVIEWED_ADMIN_REQUIRED");
        UUID org=(UUID)u.get("org_id");
        if(flag("reactivateOrganization"))jdbc.update("UPDATE org SET org_status=1,permission_version=permission_version+1 WHERE org_id=?",org);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM org WHERE org_id=? AND org_status=1)",Boolean.class,org)))reject("EXPLICIT_ORGANIZATION_RECOVERY_REQUIRED");
        activeCredential(uid,hashes.encode(password()));qualify(uid);
        jdbc.update("UPDATE ds_auth_initialization SET policy_version=policy_version+1,updated_at=now() WHERE singleton");
        audit(uid,"RECOVERED_EXISTING_ADMIN");
        return Map.of("result","RECOVERED","userId",uid,"orgId",org,"maintenanceRemainsEnabled",true,"nextAction","SetMaintenance false then verify login");
    }
    private Map<String,Object> run() {
        return tx.execute(status->{
            var before=mode();
            if(action.equals("Inspect")) {
                var out=new LinkedHashMap<String,Object>();
                out.put("result","INSPECTED");out.put("initialized",before.get("initialized"));out.put("maintenance",before.get("maintenance_mode"));
                out.put("legacyReviewCompleted",before.get("legacy_review_completed"));out.put("liveAdminCount",liveAdmins());out.put("policyVersion",before.get("policy_version"));
                if(!text("targetUserId").isEmpty()) {
                    var target=user(target());var info=new LinkedHashMap<String,Object>();
                    info.put("userId",target.get("user_id"));info.put("username",target.get("user_name"));info.put("orgId",target.get("org_id"));
                    info.put("accountState",target.get("account_state"));info.put("knownSeedCredential",seeds.isKnownSeedHash((String)target.get("password_hash")));
                    out.put("targetInfo",info);
                }
                return out;
            }
            reason();if(!flag("confirm"))reject("EXPLICIT_MAINTENANCE_CONFIRMATION_REQUIRED");
            if(action.equals("Initialize"))return initialize(before);
            if(action.equals("Upgrade"))return upgrade(before);
            if(action.equals("Recover"))return recover(before);
            if(!request.containsKey("maintenance")||!(request.get("maintenance") instanceof Boolean))reject("EXPLICIT_MAINTENANCE_VALUE_REQUIRED");
            if(!flag("maintenance")&&(!Boolean.TRUE.equals(before.get("initialized"))||liveAdmins()==0))reject("VALID_INITIALIZED_ADMIN_REQUIRED");
            jdbc.update("UPDATE ds_auth_initialization SET maintenance_mode=?,policy_version=policy_version+1,updated_at=now() WHERE singleton",flag("maintenance"));
            audit(null,"MAINTENANCE_MODE_CHANGED");return Map.of("result","MAINTENANCE_CHANGED","maintenance",flag("maintenance"));
        });
    }
    public static void main(String[] args) {
        if(args.length!=0){System.err.println("MAINTENANCE_STDIN_ONLY");System.exit(2);}
        ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        try {
            byte[] bytes=System.in.readNBytes(32769);if(bytes.length>32768)reject("MAINTENANCE_INPUT_TOO_LARGE");
            Map<String,Object> input=mapper.readValue(bytes,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            var result=new AdministratorMaintenanceMain(input,mapper).run();
            System.out.println(mapper.writeValueAsString(result));
        } catch(Exception failure) {
            // Neither SQL details nor request values can enter terminal or Slurm logs.
            String code=failure instanceof IllegalArgumentException&&failure.getMessage()!=null&&failure.getMessage().matches("[A-Z][A-Z0-9_]{3,100}")?
                failure.getMessage():"MAINTENANCE_FAILED_REVIEW_PRIVATE_STATE";
            System.err.println("{\"success\":false,\"errorType\":\""+code+"\"}");System.exit(2);
        }
    }
}
