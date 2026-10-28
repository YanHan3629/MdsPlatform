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
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import static com.fwdrobo.sirius.dataspace.PersistentDataSpaceService.map;

@Service
public class DataSpaceApplicationService {
    private final JdbcTemplate jdbc;private final DatasetAccessService access;private final ObjectMapper json;
    private final PersistentDataSpaceService business;
    public DataSpaceApplicationService(JdbcTemplate jdbc,DatasetAccessService access,ObjectMapper json,PersistentDataSpaceService business){this.jdbc=jdbc;this.access=access;this.json=json;this.business=business;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private UUID uuid(Object value){try{return UUID.fromString(String.valueOf(value));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"需要有效UUID");}}
    private String text(Map<String,Object> body,String key){String value=String.valueOf(body.getOrDefault(key,"")).trim();if(value.isBlank()||value.length()>4000)throw error(HttpStatus.BAD_REQUEST,"请填写有效的"+key);return value;}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> get(UUID id){return business.listIntents().stream().filter(a->id.equals(a.get("intentId"))).findFirst().orElseThrow(()->error(HttpStatus.NOT_FOUND,"消费申请不存在或无权查看"));}
    @Transactional public Map<String,Object> create(Map<String,Object> body){
        UUID org=access.actorOrg();
        if(body.containsKey("consumerOrgId")&&!org.equals(uuid(body.get("consumerOrgId"))))throw error(HttpStatus.FORBIDDEN,"消费主体必须是当前账号组织");
        List<Map<String,Object>> products;
        if(body.containsKey("productVersionId"))products=jdbc.queryForList("SELECT p.*,v.product_version_id,v.revision FROM ds_product p JOIN ds_product_version v ON v.product_id=p.product_id WHERE v.product_version_id=? FOR SHARE OF p",uuid(body.get("productVersionId")));
        else products=jdbc.queryForList("SELECT p.*,v.product_version_id,v.revision FROM ds_product p JOIN LATERAL(SELECT * FROM ds_product_version v WHERE v.product_id=p.product_id ORDER BY revision DESC LIMIT 1) v ON true WHERE p.product_id=? FOR SHARE OF p",uuid(body.get("productId")));
        if(products.isEmpty())throw error(HttpStatus.NOT_FOUND,"产品不存在或不可申请");var product=products.get(0);
        String purpose=text(body,"purpose");OffsetDateTime expected;
        try{expected=OffsetDateTime.parse(text(body,"expectedSendTime"));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"期望交付时间必须是包含时区的ISO8601时间，例如2026-10-20T10:00:00+08:00");}
        UUID version=(UUID)product.get("product_version_id");String request=digest(map("productVersionId",version,"purpose",purpose,"expectedAt",expected.toInstant().toString()));
        String key=String.valueOf(body.getOrDefault("idempotencyKey",request));if(key.isBlank()||key.length()>128)throw error(HttpStatus.BAD_REQUEST,"申请幂等键长度需为1至128");
        var prior=jdbc.queryForList("SELECT application_id,request_digest FROM ds_application WHERE consumer_org_id=? AND idempotency_key=?",org,key);
        if(!prior.isEmpty()){
            if(!request.equals(prior.get(0).get("request_digest").toString().trim()))throw error(HttpStatus.CONFLICT,"申请幂等键已用于不同请求");
            return get((UUID)prior.get(0).get("application_id"));
        }
        // Returning an existing identical application is safe after delisting or
        // upgrading; only creation must satisfy current discovery conditions.
        if(!"PUBLISHED".equals(product.get("status"))||(!org.equals(product.get("provider_org_id"))&&!"PUBLIC".equals(product.get("visibility"))))throw error(HttpStatus.FORBIDDEN,"产品未上架或不在当前组织可见范围内");
        if(org.equals(product.get("provider_org_id")))throw error(HttpStatus.CONFLICT,"供给方已有自有数据访问权，请由实际消费组织提交申请");
        int latest=jdbc.queryForObject("SELECT max(revision) FROM ds_product_version WHERE product_id=?",Integer.class,product.get("product_id"));
        if(latest!=((Number)product.get("revision")).intValue())throw error(HttpStatus.CONFLICT,"产品已升级，请刷新并确认最新摘要后申请");
        if(!expected.isAfter(OffsetDateTime.now()))throw error(HttpStatus.BAD_REQUEST,"期望交付时间需晚于当前时间");
        UUID id=UUID.randomUUID();int inserted=jdbc.update("INSERT INTO ds_application(application_id,product_version_id,consumer_org_id,purpose,expected_at,status,idempotency_key,request_digest,created_by) VALUES (?,?,?,?,?,'PENDING',?,?,?) ON CONFLICT(consumer_org_id,idempotency_key) DO NOTHING",id,version,org,purpose,expected,key,request,SecurityUtils.getUserId());
        var existing=jdbc.queryForMap("SELECT application_id,request_digest FROM ds_application WHERE consumer_org_id=? AND idempotency_key=?",org,key);
        if(!request.equals(existing.get("request_digest").toString().trim()))throw error(HttpStatus.CONFLICT,"申请幂等键已用于不同请求");id=(UUID)existing.get("application_id");
        if(inserted>0)audit("SUBMIT_APPLICATION",id,org,(UUID)product.get("provider_org_id"),map("productVersionId",version));return get(id);
    }
    @Transactional public Map<String,Object> decide(UUID id,Map<String,Object> body){
        access.actorOrg();var rows=jdbc.queryForList("SELECT a.*,v.dataset_id,p.provider_org_id FROM ds_application a JOIN ds_product_version v ON v.product_version_id=a.product_version_id JOIN ds_product p ON p.product_id=v.product_id WHERE a.application_id=? FOR UPDATE OF a",id);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"申请不存在");var app=rows.get(0);access.requireDatasetWrite((UUID)app.get("dataset_id"));
        String decision=text(body,"decision");if(!Set.of("APPROVED","REJECTED").contains(decision))throw error(HttpStatus.BAD_REQUEST,"审核结果需为APPROVED或REJECTED");
        String reason=String.valueOf(body.getOrDefault("reason","")).trim();if(reason.length()>4000||("REJECTED".equals(decision)&&reason.isBlank()))throw error(HttpStatus.BAD_REQUEST,"拒绝申请时需要填写原因（不超过4000字符）");
        if(decision.equals(app.get("status")))return get(id);
        if(!"PENDING".equals(app.get("status")))throw error(HttpStatus.CONFLICT,"申请已审核；生效合约需通过显式撤销停止授权");
        jdbc.update("UPDATE ds_application SET status=?,decision_reason=?,decided_by=?,decided_at=now() WHERE application_id=?",decision,reason,SecurityUtils.getUserId(),id);
        audit("DECIDE_APPLICATION",id,(UUID)app.get("consumer_org_id"),(UUID)app.get("provider_org_id"),map("decision",decision,"reason",reason));return get(id);
    }
    private void audit(String action,UUID id,UUID consumer,UUID provider,Map<String,Object> detail){
        for(UUID org:new HashSet<>(List.of(consumer,provider)))jdbc.update("INSERT INTO ds_audit_event(org_id,actor_user_id,action,object_type,object_id,details) VALUES (?,?,?,'APPLICATION',?,?::jsonb)",org,SecurityUtils.getUserId(),action,id,encode(detail));
    }
}
