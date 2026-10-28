package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.service.DatasetAccessService;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.fwdrobo.sirius.dataspace.PersistentDataSpaceService.map;

/** Technical full-version grants; application approval alone never grants content access. */
@Service
public class DataSpaceContractService {
    private final JdbcTemplate jdbc;private final DatasetAccessService access;private final DataSpaceReceiverClient receiver;
    private final ObjectMapper json;private final PersistentDataSpaceService business;
    public DataSpaceContractService(JdbcTemplate jdbc,DatasetAccessService access,DataSpaceReceiverClient receiver,ObjectMapper json,PersistentDataSpaceService business){this.jdbc=jdbc;this.access=access;this.receiver=receiver;this.json=json;this.business=business;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private UUID uuid(Object value){try{return UUID.fromString(String.valueOf(value));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"需要有效UUID");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String text(Map<String,Object> body,String key,String fallback){String value=String.valueOf(body.getOrDefault(key,fallback)).trim();if(value.length()>4000)throw error(HttpStatus.BAD_REQUEST,key+"过长");return value;}
    private OffsetDateTime time(Map<String,Object> body,String field){try{return OffsetDateTime.parse(String.valueOf(body.get(field)));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,field+"需要包含时区的ISO8601时间，不能默认立即执行");}}
    private Map<String,Object> one(String sql,Object... args){var rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"对象不存在或无权操作");return rows.get(0);}
    private Map<String,Object> application(UUID id,boolean lock){return one("""
        SELECT a.*,p.provider_org_id,v.dataset_id,v.version_id,v.index_version_id,v.quality_report_id,v.manifest_digest
        FROM ds_application a JOIN ds_product_version v ON v.product_version_id=a.product_version_id JOIN ds_product p ON p.product_id=v.product_id
        WHERE a.application_id=?
        """+(lock?" FOR UPDATE OF a":""),id);}
    private void consumerManager(Map<String,Object> app){
        if(!access.actorOrg().equals(app.get("consumer_org_id")))throw error(HttpStatus.FORBIDDEN,"只能由申请的消费组织确认");
        List<String> roles=SecurityUtils.getUserRoles();
        if(!SecurityUtils.getUserId().equals(app.get("created_by"))&&(roles==null||roles.stream().noneMatch(r->Set.of("ROLE_SUPER_ADMIN","ROLE_ADMIN","ROLE_ORG_ADMIN","ROLE_DATA_MANAGER").contains(r))))throw error(HttpStatus.FORBIDDEN,"需要原申请人或当前组织管理角色");
    }
    private Map<String,Object> contract(UUID id,boolean lock){
        var c=one("SELECT c.*,v.dataset_id,v.version_id,v.index_version_id FROM ds_contract c JOIN ds_product_version v ON v.product_version_id=c.product_version_id WHERE c.contract_id=?"+(lock?" FOR UPDATE OF c":""),id);
        UUID org=access.actorOrg();if(!org.equals(c.get("provider_org_id"))&&!org.equals(c.get("consumer_org_id")))throw error(HttpStatus.FORBIDDEN,"合约不属于当前组织");return c;
    }
    private Map<String,Object> view(UUID id){return business.listContracts().stream().filter(c->id.equals(c.get("contractId"))).findFirst().orElseThrow();}
    public List<Map<String,Object>> endpoints(UUID applicationId){
        UUID org=access.actorOrg();
        if(applicationId!=null){var app=application(applicationId,false);if(!org.equals(app.get("consumer_org_id"))){access.requireDatasetWrite((UUID)app.get("dataset_id"));if(!"APPROVED".equals(app.get("status")))throw error(HttpStatus.FORBIDDEN,"只有已批准申请可选择消费组织的接收配置");}org=(UUID)app.get("consumer_org_id");}
        return jdbc.queryForList("SELECT endpoint_id AS \"endpointId\",consumer_org_id AS \"consumerOrgId\",endpoint_name AS \"endpointName\",adapter_type AS \"adapterType\",receiver_identity AS \"receiverIdentity\",enabled FROM ds_receiver_endpoint WHERE consumer_org_id=? ORDER BY created_at",org);
    }
    @Transactional public Map<String,Object> registerEndpoint(Map<String,Object> body){
        UUID org=access.actorOrg();String name=text(body,"endpointName","");if(name.isBlank()||name.length()>128)throw error(HttpStatus.BAD_REQUEST,"请填写1至128字符的接收端名称");
        if(!"LOCAL_VERIFIED".equals(body.getOrDefault("adapterType","LOCAL_VERIFIED"))||body.containsKey("url")||body.containsKey("receiverEndpoint"))throw error(HttpStatus.CONFLICT,"仅支持部署配置的本地真实接收端；外部S3/HTTP未配置");
        UUID id=UUID.nameUUIDFromBytes((org+":local-receiver:"+name).getBytes(StandardCharsets.UTF_8));
        var identity=receiver.identity();var existing=jdbc.queryForList("SELECT receiver_identity,receiver_public_key FROM ds_receiver_endpoint WHERE endpoint_id=?",id);
        if(!existing.isEmpty()&&(!identity.get("receiverIdentity").equals(existing.get(0).get("receiver_identity"))||!identity.get("publicKey").equals(existing.get(0).get("receiver_public_key"))))throw error(HttpStatus.CONFLICT,"接收端身份与已固定配置不同，不能静默替换回执验证密钥");
        receiver.register(id,org,identity.get("receiverIdentity").toString());
        int changed=jdbc.update("INSERT INTO ds_receiver_endpoint(endpoint_id,consumer_org_id,endpoint_name,adapter_type,receiver_identity,receiver_public_key,storage_prefix,created_by) VALUES (?,?,?,'LOCAL_VERIFIED',?,?,?,?) ON CONFLICT(endpoint_id) DO NOTHING",id,org,name,identity.get("receiverIdentity"),identity.get("publicKey"),"local-receiver/"+org+"/"+id,SecurityUtils.getUserId());
        if(changed>0)audit("CONFIGURE_LOCAL_RECEIVER","RECEIVER_ENDPOINT",id,org,org,map("adapterType","LOCAL_VERIFIED","receiverIdentity",identity.get("receiverIdentity")));
        return endpoints(null).stream().filter(e->id.equals(e.get("endpointId"))).findFirst().orElseThrow();
    }
    private Map<String,Object> terms(Map<String,Object> body,Map<String,Object> app){
        OffsetDateTime from=time(body,"validFrom"),to=time(body,"validTo"),send=time(body,"sendTime");
        if(!to.isAfter(from)||send.isBefore(from)||!send.isBefore(to)||!send.isAfter(OffsetDateTime.now()))throw error(HttpStatus.BAD_REQUEST,"有效期结束需晚于开始，发送时间必须在有效期内且晚于当前时间");
        UUID endpoint=uuid(body.get("endpointId"));one("SELECT endpoint_id FROM ds_receiver_endpoint WHERE endpoint_id=? AND consumer_org_id=? AND enabled=true AND adapter_type='LOCAL_VERIFIED'",endpoint,app.get("consumer_org_id"));
        Double threshold=null;Object raw=body.get("minimumQuality");if(raw==null)raw=body.get("qualityThreshold");
        if(raw!=null&&!raw.toString().isBlank())try{threshold=Double.parseDouble(raw.toString());if(!Double.isFinite(threshold)||threshold<0||threshold>100)throw new IllegalArgumentException();}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"质量门槛必须是0至100的数值");}
        var q=one("SELECT score,status,blocking_count,completed_at,version_id,manifest_digest FROM ds_quality_report WHERE report_id=?",app.get("quality_report_id"));
        if(!Set.of("PASSED","PARTIAL").contains(q.get("status"))||q.get("completed_at")==null||q.get("score")==null||((Number)q.get("blocking_count")).longValue()!=0||!app.get("version_id").equals(q.get("version_id"))||!app.get("manifest_digest").equals(q.get("manifest_digest"))||(threshold!=null&&((Number)q.get("score")).doubleValue()<threshold))throw error(HttpStatus.CONFLICT,"冻结产品的同版本真实质量报告未满足门槛");
        access.requireVersionRead((UUID)app.get("dataset_id"),(UUID)app.get("version_id"),(UUID)app.get("index_version_id"));
        String purpose=text(body,"purpose",app.get("purpose").toString());if(purpose.isBlank())throw error(HttpStatus.BAD_REQUEST,"技术合约必须说明用途");
        return map("purpose",purpose,"scopeType","FULL_VERSION","validFrom",from.toString(),"validTo",to.toString(),"sendTime",send.toString(),"endpointId",endpoint,"minimumQuality",threshold,"productVersionId",app.get("product_version_id"),"qualityReportId",app.get("quality_report_id"));
    }
    @Transactional public Map<String,Object> create(Map<String,Object> body){
        UUID application=uuid(body.getOrDefault("applicationId",body.get("intentId")));var app=application(application,true);access.requireDatasetWrite((UUID)app.get("dataset_id"));
        if(!"APPROVED".equals(app.get("status")))throw error(HttpStatus.CONFLICT,"申请需经供给方批准后才能提交技术合约");
        var existing=jdbc.queryForList("SELECT * FROM ds_contract WHERE application_id=?",application);
        if(!existing.isEmpty()){
            var old=existing.get(0);Object minimum=body.getOrDefault("minimumQuality",body.get("qualityThreshold"));
            boolean same=uuid(body.get("endpointId")).equals(old.get("endpoint_id"))&&time(body,"validFrom").toInstant().equals(((java.sql.Timestamp)old.get("valid_from")).toInstant())&&time(body,"validTo").toInstant().equals(((java.sql.Timestamp)old.get("valid_to")).toInstant())&&time(body,"sendTime").toInstant().equals(((java.sql.Timestamp)old.get("scheduled_at")).toInstant())&&text(body,"purpose",app.get("purpose").toString()).equals(old.get("purpose"));
            try{same&=(minimum==null||minimum.toString().isBlank())?old.get("minimum_quality")==null:old.get("minimum_quality")!=null&&Double.parseDouble(minimum.toString())==((Number)old.get("minimum_quality")).doubleValue();}catch(Exception e){same=false;}
            if(!same)throw error(HttpStatus.CONFLICT,"该申请已有技术合约；条款变更请显式修订并重新确认");return view((UUID)old.get("contract_id"));
        }
        var terms=terms(body,app);UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ds_contract(contract_id,application_id,product_version_id,provider_org_id,consumer_org_id,purpose,valid_from,valid_to,scheduled_at,endpoint_id,minimum_quality,status,provider_confirmed_at,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'PENDING_CONFIRMATION',now(),?)
            """,id,application,app.get("product_version_id"),app.get("provider_org_id"),app.get("consumer_org_id"),terms.get("purpose"),OffsetDateTime.parse(terms.get("validFrom").toString()),OffsetDateTime.parse(terms.get("validTo").toString()),OffsetDateTime.parse(terms.get("sendTime").toString()),terms.get("endpointId"),terms.get("minimumQuality"),SecurityUtils.getUserId());
        change(id,1,terms);audit("PROPOSE_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)app.get("consumer_org_id"),(UUID)app.get("provider_org_id"),map("revision",1,"state","PENDING_CONFIRMATION"));return view(id);
    }
    private void change(UUID id,int revision,Map<String,Object> terms){jdbc.update("INSERT INTO ds_contract_change(contract_id,revision,terms,actor_user_id) VALUES (?,?,?::jsonb,?)",id,revision,encode(terms),SecurityUtils.getUserId());}
    @Transactional public Map<String,Object> confirm(UUID id,Map<String,Object> body){
        var c=contract(id,true);var app=application((UUID)c.get("application_id"),false);
        if(!String.valueOf(c.get("revision")).equals(String.valueOf(body.get("revision"))))throw error(HttpStatus.CONFLICT,"条款已变更，请读取当前修订后确认");
        if(!Set.of("PENDING_CONFIRMATION","CONFIRMED").contains(c.get("status"))||!"APPROVED".equals(app.get("status")))throw error(HttpStatus.CONFLICT,"合约或申请状态不允许确认");
        if(access.actorOrg().equals(c.get("provider_org_id")))access.requireDatasetWrite((UUID)c.get("dataset_id"));else consumerManager(app);
        if(!isAvailable(id))throw error(HttpStatus.CONFLICT,"已到期或冻结快照/接收端/真实质量报告不可用，不能确认授权");
        if("CONFIRMED".equals(c.get("status")))return view(id);
        String field=access.actorOrg().equals(c.get("provider_org_id"))?"provider_confirmed_at":"consumer_confirmed_at";
        jdbc.update("UPDATE ds_contract SET "+field+"=COALESCE("+field+",now()),updated_at=now() WHERE contract_id=?",id);
        jdbc.update("UPDATE ds_contract SET status='CONFIRMED',updated_at=now() WHERE contract_id=? AND provider_confirmed_at IS NOT NULL AND consumer_confirmed_at IS NOT NULL",id);
        if("CONFIRMED".equals(jdbc.queryForObject("SELECT status FROM ds_contract WHERE contract_id=?",String.class,id)))jdbc.update("INSERT INTO ds_delivery_task(contract_id,contract_revision,status,next_attempt_at) SELECT contract_id,revision,'PENDING',scheduled_at FROM ds_contract WHERE contract_id=? ON CONFLICT(contract_id,contract_revision) DO NOTHING",id);
        audit("CONFIRM_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("revision",c.get("revision"),"party",access.actorOrg().equals(c.get("provider_org_id"))?"PROVIDER":"CONSUMER"));return view(id);
    }
    /** Scheduler authority uses actual snapshot state, independent of a logged-in browser. */
    public boolean isAvailable(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM ds_contract c JOIN ds_application a ON a.application_id=c.application_id AND a.status='APPROVED'
          JOIN ds_product_version p ON p.product_version_id=c.product_version_id
          JOIN mm_dataset d ON d.dataset_id=p.dataset_id AND d.org_id=c.provider_org_id AND d.status='ACTIVE'
          JOIN mm_dataset_version v ON v.version_id=p.version_id AND v.dataset_id=d.dataset_id AND v.raw_commit_id=p.raw_commit_id
          JOIN artifact_commit raw ON raw.commit_id=p.raw_commit_id AND raw.repo_id=d.raw_repo_id AND raw.commit_status='PUBLISHED'
          JOIN mm_index_version i ON i.index_version_id=p.index_version_id AND i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_status='READY'
          JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id AND idx.commit_status='PUBLISHED'
          JOIN ds_quality_report q ON q.report_id=p.quality_report_id AND q.version_id=v.version_id AND q.manifest_digest=p.manifest_digest AND q.completed_at IS NOT NULL AND q.blocking_count=0 AND q.status IN ('PASSED','PARTIAL')
          JOIN ds_receiver_endpoint e ON e.endpoint_id=c.endpoint_id AND e.consumer_org_id=c.consumer_org_id AND e.enabled=true
        WHERE c.contract_id=? AND c.valid_to>now() AND c.revoked_at IS NULL AND c.status IN ('PENDING_CONFIRMATION','CONFIRMED') AND (c.minimum_quality IS NULL OR q.score>=c.minimum_quality))
        """,Boolean.class,id));}
    @Transactional public Map<String,Object> revoke(UUID id,Map<String,Object> body){
        var c=contract(id,true);var app=application((UUID)c.get("application_id"),false);
        if(access.actorOrg().equals(c.get("provider_org_id")))access.requireDatasetWrite((UUID)c.get("dataset_id"));else consumerManager(app);
        String reason=text(body,"reason","");if(reason.isBlank())throw error(HttpStatus.BAD_REQUEST,"撤销需要填写原因");
        if("REVOKED".equals(c.get("status")))return view(id);
        jdbc.update("UPDATE ds_contract SET status='REVOKED',revoked_at=now(),revoke_reason=?,updated_at=now() WHERE contract_id=?",reason,id);
        jdbc.update("UPDATE ds_delivery_task SET status='CANCELLED',last_error='授权已显式撤销',updated_at=now() WHERE contract_id=? AND status<>'DELIVERED'",id);
        jdbc.update("UPDATE ds_delivery_attempt a SET status='CANCELLED',error_message='授权已撤销',finished_at=now() FROM ds_delivery_task t WHERE t.task_id=a.task_id AND t.contract_id=? AND a.status='RUNNING'",id);
        audit("REVOKE_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("reason",reason,"revision",c.get("revision")));return view(id);
    }
    @Transactional public Map<String,Object> revise(UUID id,Map<String,Object> body){
        var c=contract(id,true);access.requireDatasetWrite((UUID)c.get("dataset_id"));
        if(!Set.of("PENDING_CONFIRMATION","CONFIRMED").contains(c.get("status")))throw error(HttpStatus.CONFLICT,"已撤销或取消的合约不能静默恢复，请重新申请");
        if(!String.valueOf(c.get("revision")).equals(String.valueOf(body.get("revision"))))throw error(HttpStatus.CONFLICT,"请在当前修订基础上修改");
        var app=application((UUID)c.get("application_id"),false);var terms=terms(body,app);int revision=((Number)c.get("revision")).intValue()+1;
        jdbc.update("UPDATE ds_contract SET revision=?,purpose=?,valid_from=?,valid_to=?,scheduled_at=?,endpoint_id=?,minimum_quality=?,status='PENDING_CONFIRMATION',provider_confirmed_at=now(),consumer_confirmed_at=null,updated_at=now() WHERE contract_id=?",revision,terms.get("purpose"),OffsetDateTime.parse(terms.get("validFrom").toString()),OffsetDateTime.parse(terms.get("validTo").toString()),OffsetDateTime.parse(terms.get("sendTime").toString()),terms.get("endpointId"),terms.get("minimumQuality"),id);
        jdbc.update("UPDATE ds_delivery_task SET status='CANCELLED',last_error='条款修订，旧交付计划已取消',updated_at=now() WHERE contract_id=? AND status<>'DELIVERED'",id);
        jdbc.update("UPDATE ds_delivery_attempt a SET status='CANCELLED',error_message='条款已修订',finished_at=now() FROM ds_delivery_task t WHERE t.task_id=a.task_id AND t.contract_id=? AND a.status='RUNNING'",id);
        change(id,revision,terms);audit("REVISE_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("revision",revision));return view(id);
    }
    public List<Map<String,Object>> history(UUID id){contract(id,false);var rows=jdbc.queryForList("SELECT revision,terms,created_at AS \"createdAt\" FROM ds_contract_change WHERE contract_id=? ORDER BY revision",id);rows.forEach(r->r.computeIfPresent("terms",(k,v)->{try{return json.readValue(v.toString(),Object.class);}catch(Exception e){throw new IllegalStateException(e);}}));return rows;}
    @Transactional public Map<String,Object> retry(UUID id){
        var c=contract(id,true);access.requireDatasetWrite((UUID)c.get("dataset_id"));
        if(!"CONFIRMED".equals(c.get("status"))||!isAvailable(id))throw error(HttpStatus.CONFLICT,"合约未生效、已到期、已撤销或范围不可用");
        var t=one("SELECT * FROM ds_delivery_task WHERE contract_id=? AND contract_revision=? FOR UPDATE",id,c.get("revision"));
        if(Set.of("DELIVERED","RUNNING","PENDING").contains(t.get("status")))return map("taskId",t.get("task_id"),"status",t.get("status"),"nextAttemptAt",t.get("next_attempt_at"));
        if(((Number)t.get("attempt_count")).intValue()>=20)throw error(HttpStatus.CONFLICT,"已达到本修订20次总尝试上限，请检查原因后显式修订计划");
        jdbc.update("UPDATE ds_delivery_task SET status='RETRY',max_attempts=LEAST(20,GREATEST(max_attempts,attempt_count+3)),next_attempt_at=GREATEST(now(),?::timestamptz),lease_token=null,lease_until=null,updated_at=now() WHERE task_id=?",c.get("scheduled_at"),t.get("task_id"));
        audit("REQUEST_DELIVERY_RETRY","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("taskId",t.get("task_id")));return map("taskId",t.get("task_id"),"status","RETRY");
    }
    private void audit(String action,String type,UUID id,UUID consumer,UUID provider,Map<String,Object> details){for(UUID org:new HashSet<>(List.of(consumer,provider)))jdbc.update("INSERT INTO ds_audit_event(org_id,actor_user_id,action,object_type,object_id,details) VALUES (?,?,?,?,?,?::jsonb)",org,SecurityUtils.getUserId(),action,type,id,encode(details));}
}
