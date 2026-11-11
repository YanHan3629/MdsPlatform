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
    private Map<String,Object> get(UUID id){return business.applicationView(id);}
    private ResponseStatusException unavailableProduct(){
        return error(HttpStatus.NOT_FOUND,"产品不存在或不可申请");
    }
    private record Submission(String purpose,OffsetDateTime expected,String digest,String key) {}

    private Submission submission(Map<String,Object> body,UUID version){
        String purpose=text(body,"purpose");
        OffsetDateTime expected;
        try{expected=OffsetDateTime.parse(text(body,"expectedSendTime"));}
        catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"期望交付时间必须是包含时区的ISO8601时间，例如2026-10-20T10:00:00+08:00");}
        String request=digest(map("productVersionId",version,"purpose",purpose,"expectedAt",expected.toInstant().toString()));
        String key=String.valueOf(body.getOrDefault("idempotencyKey",request));
        if(key.isBlank()||key.length()>128)throw error(HttpStatus.BAD_REQUEST,"申请幂等键长度需为1至128字符");
        return new Submission(purpose,expected,request,key);
    }

    private boolean canReadProviderProduct(Map<String,Object> product){
        try(var ignored=access.fixedOperation("data.catalog.read","GENERAL_READ")){
            return access.canReadVersion((UUID)product.get("dataset_id"),(UUID)product.get("version_id"),(UUID)product.get("index_version_id"));
        }
    }

    @Transactional public Map<String,Object> create(Map<String,Object> body){
        UUID org=access.actorOrg();
        if(body.containsKey("consumerOrgId")&&!org.equals(uuid(body.get("consumerOrgId"))))throw error(HttpStatus.FORBIDDEN,"消费主体必须是当前账号组织");
        UUID requestedProduct=body.containsKey("productId")?uuid(body.get("productId")):null;
        List<Map<String,Object>> products;
        if(body.containsKey("productVersionId"))products=jdbc.queryForList("""
            SELECT p.product_id,p.provider_org_id,p.status,p.visibility,
              v.product_version_id,v.revision,v.dataset_id,v.version_id,v.index_version_id
            FROM ds_product p JOIN ds_product_version v ON v.product_id=p.product_id
            WHERE v.product_version_id=? FOR SHARE OF p
            """,uuid(body.get("productVersionId")));
        else{
            if(requestedProduct==null)throw error(HttpStatus.BAD_REQUEST,"需要有效UUID");
            products=jdbc.queryForList("""
                SELECT p.product_id,p.provider_org_id,p.status,p.visibility,
                  v.product_version_id,v.revision,v.dataset_id,v.version_id,v.index_version_id
                FROM ds_product p JOIN LATERAL(
                    SELECT * FROM ds_product_version v WHERE v.product_id=p.product_id ORDER BY revision DESC LIMIT 1
                ) v ON true WHERE p.product_id=? FOR SHARE OF p
                """,requestedProduct);
        }
        if(products.isEmpty())throw unavailableProduct();
        var product=products.get(0);
        if(requestedProduct!=null&&!requestedProduct.equals(product.get("product_id")))throw unavailableProduct();
        boolean sameProvider=org.equals(product.get("provider_org_id"));
        boolean discoverable="PUBLISHED".equals(product.get("status"))
            &&("PUBLIC".equals(product.get("visibility"))||(sameProvider&&canReadProviderProduct(product)));
        UUID version=(UUID)product.get("product_version_id");
        Submission request;
        try{request=submission(body,version);}
        catch(ResponseStatusException invalid){
            if(!discoverable)throw unavailableProduct();
            throw invalid;
        }
        var prior=jdbc.queryForList("SELECT application_id,request_digest FROM ds_application WHERE consumer_org_id=? AND idempotency_key=?",org,request.key());
        // An identical, organization-owned request remains readable after delisting or upgrade.
        if(!prior.isEmpty()){
            var previous=prior.get(0);
            if(request.digest().equals(previous.get("request_digest").toString().trim()))return get((UUID)previous.get("application_id"));
        }
        if(!discoverable)throw unavailableProduct();
        if(!prior.isEmpty())throw error(HttpStatus.CONFLICT,"申请幂等键已用于不同请求");
        if(sameProvider)throw error(HttpStatus.CONFLICT,"供给组织不能申请本组织发布的产品，请由消费组织提交申请");
        int latest=jdbc.queryForObject("SELECT max(revision) FROM ds_product_version WHERE product_id=?",Integer.class,product.get("product_id"));
        if(latest!=((Number)product.get("revision")).intValue())throw error(HttpStatus.CONFLICT,"产品已升级，请刷新并确认最新摘要后申请");
        if(!request.expected().isAfter(OffsetDateTime.now()))throw error(HttpStatus.BAD_REQUEST,"期望交付时间需晚于当前时间");
        UUID id=UUID.randomUUID();int inserted=jdbc.update("INSERT INTO ds_application(application_id,product_version_id,consumer_org_id,purpose,expected_at,status,idempotency_key,request_digest,created_by) VALUES (?,?,?,?,?,'PENDING',?,?,?) ON CONFLICT(consumer_org_id,idempotency_key) DO NOTHING",id,version,org,request.purpose(),request.expected(),request.key(),request.digest(),SecurityUtils.getUserId());
        var existing=jdbc.queryForMap("SELECT application_id,request_digest FROM ds_application WHERE consumer_org_id=? AND idempotency_key=?",org,request.key());
        if(!request.digest().equals(existing.get("request_digest").toString().trim()))throw error(HttpStatus.CONFLICT,"申请幂等键已用于不同请求");id=(UUID)existing.get("application_id");
        if(inserted>0)audit("SUBMIT_APPLICATION",id,org,(UUID)product.get("provider_org_id"),map("productVersionId",version));return get(id);
    }
    @Transactional public Map<String,Object> decide(UUID id,Map<String,Object> body){
        access.actorOrg();var rows=jdbc.queryForList("SELECT a.*,v.dataset_id,p.provider_org_id FROM ds_application a JOIN ds_product_version v ON v.product_version_id=a.product_version_id JOIN ds_product p ON p.product_id=v.product_id WHERE a.application_id=? FOR UPDATE OF a",id);
        if(rows.isEmpty()||!access.actorOrg().equals(rows.get(0).get("provider_org_id")))
            throw error(HttpStatus.NOT_FOUND,"申请不存在或无权审核");
        var app=rows.get(0);access.requireDatasetWrite((UUID)app.get("dataset_id"));
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
