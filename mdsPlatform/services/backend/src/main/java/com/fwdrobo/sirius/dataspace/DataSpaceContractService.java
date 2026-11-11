package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.service.DatasetAccessService;
import com.fwdrobo.sirius.security.AuthorizationService;
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

/** Revisioned technical contracts. Content access also requires a current scoped grant. */
@Service
public class DataSpaceContractService {
    private static final String APPLICATION_UNAVAILABLE = "CONTRACT_APPLICATION_NOT_AVAILABLE";
    private static final String CONTRACT_UNAVAILABLE = "合约不存在或不可访问";
    private final JdbcTemplate jdbc;private final DatasetAccessService access;private final DataSpaceReceiverClient receiver;
    private final ObjectMapper json;private final PersistentDataSpaceService business;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.fwdrobo.sirius.security.GenericFileOriginService> deliveryOrigins;private final AuthorizationService auth;
    public DataSpaceContractService(JdbcTemplate jdbc,DatasetAccessService access,DataSpaceReceiverClient receiver,ObjectMapper json,PersistentDataSpaceService business,AuthorizationService auth){this.jdbc=jdbc;this.access=access;this.receiver=receiver;this.json=json;this.business=business;this.auth=auth;}
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private UUID uuid(Object value){try{return UUID.fromString(String.valueOf(value));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"需要有效UUID");}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String text(Map<String,Object> body,String key,String fallback){String value=String.valueOf(body.getOrDefault(key,fallback)).trim();if(value.length()>4000)throw error(HttpStatus.BAD_REQUEST,key+"过长");return value;}
    private OffsetDateTime time(Map<String,Object> body,String field){try{return OffsetDateTime.parse(String.valueOf(body.get(field)));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,field+"需要包含时区的ISO8601时间，不能默认立即执行");}}
    private Map<String,Object> one(String sql,Object... args){var rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"对象不存在或无权操作");return rows.get(0);}
    private Map<String,Object> application(UUID id,boolean lock){
        UUID org=access.actorOrg();
        var rows=jdbc.queryForList("""
            SELECT a.*,p.provider_org_id,v.dataset_id,v.version_id,v.index_version_id,v.quality_report_id,v.manifest_digest
            FROM ds_application a JOIN ds_product_version v ON v.product_version_id=a.product_version_id JOIN ds_product p ON p.product_id=v.product_id
            WHERE a.application_id=? AND (a.consumer_org_id=? OR p.provider_org_id=?)
            """+(lock?" FOR UPDATE OF a":""),id,org,org);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,APPLICATION_UNAVAILABLE);
        return rows.get(0);
    }

    private void requireProviderScope(Map<String,Object> resource,String unavailable){
        if(!access.actorOrg().equals(resource.get("provider_org_id")))throw error(HttpStatus.NOT_FOUND,unavailable);
        try{
            access.requireDatasetWrite((UUID)resource.get("dataset_id"));
        }catch(ResponseStatusException denied){
            if(denied.getStatusCode().value()==404)throw error(HttpStatus.NOT_FOUND,unavailable);
            throw denied;
        }
    }
    private void requireConsumerContractOperation(Map<String,Object> contract,Map<String,Object> app,String operation){
        auth.requireOperation(operation);
        if(!access.actorOrg().equals(app.get("consumer_org_id")))throw error(HttpStatus.FORBIDDEN,"只能由申请的消费组织确认");
        if("CONTROLLED_VIEW".equals(contract.get("scope_type")))return;
        List<String> roles=SecurityUtils.getUserRoles();
        if(!SecurityUtils.getUserId().equals(app.get("created_by"))&&(roles==null||roles.stream().noneMatch(r->Set.of("ROLE_SUPER_ADMIN","ROLE_ADMIN","ROLE_ORG_ADMIN","ROLE_DATA_MANAGER").contains(r))))throw error(HttpStatus.FORBIDDEN,"需要原申请人或当前组织管理角色");
    }
    private Map<String,Object> contract(UUID id,boolean lock){
        UUID org=access.actorOrg();
        var rows=jdbc.queryForList("""
            SELECT c.*,v.dataset_id,v.version_id,v.index_version_id
            FROM ds_contract c JOIN ds_product_version v ON v.product_version_id=c.product_version_id
            WHERE c.contract_id=? AND (c.provider_org_id=? OR c.consumer_org_id=?)
            """+(lock?" FOR UPDATE OF c":""),id,org,org);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,CONTRACT_UNAVAILABLE);
        return rows.get(0);
    }
    private Map<String,Object> view(UUID id){return business.contractView(id);}
    public List<Map<String,Object>> endpoints(UUID applicationId){
        UUID org=access.actorOrg();
        if(applicationId!=null){
            var app=application(applicationId,false);
            if(org.equals(app.get("consumer_org_id"))){
                if(!auth.hasOperation("delivery.endpoint.manage"))throw error(HttpStatus.NOT_FOUND,APPLICATION_UNAVAILABLE);
            }else{
                if(!auth.hasOperation("contract.edit")||!"APPROVED".equals(app.get("status")))
                    throw error(HttpStatus.NOT_FOUND,APPLICATION_UNAVAILABLE);
                try(var ignored=access.fixedOperation("contract.edit","GENERAL_READ")){
                    requireProviderScope(app,APPLICATION_UNAVAILABLE);
                }
            }
            org=(UUID)app.get("consumer_org_id");
        }
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
    private static final Set<String> CONTROLLED_ACTIONS = Set.of(
        "data.catalog.read", "data.preview", "data.search", "data.qa", "data.raw.download",
        "data.view.export", "delivery.package.download");
    private static final Set<String> CONTRACT_PURPOSES = Set.of("GENERAL_READ", "CONTROLLED_DELIVERY");

    private List<String> selected(Map<String,Object> body, String field, Set<String> allowed) {
        if (!(body.get(field) instanceof List<?> values) || values.isEmpty() || values.size()>allowed.size())
            throw error(HttpStatus.BAD_REQUEST, "CONTRACT_EXPLICIT_"+field.toUpperCase(Locale.ROOT)+"_REQUIRED");
        var result = new TreeSet<String>();
        for (Object value:values) {
            if (!(value instanceof String code) || !allowed.contains(code) || !result.add(code))
                throw error(HttpStatus.BAD_REQUEST, "CONTRACT_INVALID_OR_DUPLICATE_"+field.toUpperCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    private static Set<String> exportOperations(boolean copies, String operation) {
        return copies ? Set.of(operation,"data.view.export","data.raw.download") : Set.of(operation,"data.view.export");
    }

    private List<Map<String,Object>> registeredViews(UUID dataset) {
        return jdbc.queryForList("""
            SELECT cv.view_id, cv.authorization_version, cv.owner_org_id, cv.version_id, cv.index_version_id,
                   cv.state, controlled_view_publication_policy_current(cv.dataset_id) AS policy_current,
                   COALESCE((cv.public_scope_summary->>'hasOriginalByteCopies')::boolean,false) AS original_copies,
                   (review.payload->>'validTo')::timestamptz AS publication_end
            FROM ds_controlled_view cv
            LEFT JOIN ds_controlled_view_publication publication ON publication.view_id=cv.view_id
            LEFT JOIN ds_controlled_view_publication_review review ON review.review_id=publication.review_id
            WHERE cv.dataset_id=?
            """,dataset);
    }

    private Map<String,Object> exactCurrentView(Map<String,Object> app,List<Map<String,Object>> views) {
        if (views.size()!=1) throw error(HttpStatus.CONFLICT,"CONTRACT_VIEW_AMBIGUOUS");
        var view=views.get(0);
        if (!app.get("version_id").equals(view.get("version_id")) ||
            !app.get("index_version_id").equals(view.get("index_version_id")) ||
            !app.get("provider_org_id").equals(view.get("owner_org_id")) ||
            !"READY".equals(view.get("state")) || !Boolean.TRUE.equals(view.get("policy_current")))
            throw error(HttpStatus.NOT_FOUND,"CONTRACT_EXACT_VIEW_NOT_AVAILABLE");
        auth.requireDataset(auth.actor(),(UUID)app.get("dataset_id"),(UUID)app.get("version_id"),
            (UUID)app.get("index_version_id"),Set.of("dataset.share.manage"),"GENERAL_READ");
        return view;
    }

    private List<Map<String,Object>> deliveryGrants(Map<String,Object> app,Map<String,Object> view,UUID selected,OffsetDateTime until) {
        UUID dataset=(UUID)app.get("dataset_id"),version=(UUID)app.get("version_id"),index=(UUID)app.get("index_version_id");
        UUID org=(UUID)app.get("provider_org_id");
        var grants=jdbc.queryForList("""
            SELECT s.service_subject_id, s.display_name, s.version AS service_version,
                   g.grant_id, g.version AS grant_version,
                   LEAST(s.valid_until,g.valid_to) AS authority_end
            FROM ds_service_subject s JOIN ds_resource_grant g ON g.subject_kind='SERVICE' AND g.subject_id=s.service_subject_id
            WHERE s.org_id=? AND s.purpose='SCHEDULED_DELIVERY' AND s.active AND s.closed_at IS NULL
              AND (?::uuid IS NULL OR s.service_subject_id=?)
              AND (s.valid_until IS NULL OR s.valid_until>=?) AND g.state='ACTIVE' AND g.scope_kind='CONTROLLED_VIEW'
              AND g.dataset_id=? AND g.version_id=? AND g.index_version_id=? AND g.view_authorization_version=?
              AND g.valid_from<=clock_timestamp() AND (g.valid_to IS NULL OR g.valid_to>=?)
            ORDER BY s.display_name,s.service_subject_id,g.grant_id
            """,org,selected,selected,until,dataset,version,index,view.get("authorization_version"),until);
        Set<String> needed=exportOperations(Boolean.TRUE.equals(view.get("original_copies")),"delivery.execute");
        return grants.stream().filter(g->auth.persistedServiceGrantCurrent((UUID)g.get("service_subject_id"),org,
            dataset,version,index,needed,"CONTROLLED_DELIVERY",grantBinding(g))).toList();
    }

    public Map<String,Object> options(UUID applicationId) {
        auth.requireOperation(auth.actor(),"contract.edit");
        var app=application(applicationId,false);
        if(!"APPROVED".equals(app.get("status")))throw error(HttpStatus.NOT_FOUND,APPLICATION_UNAVAILABLE);
        requireProviderScope(app,APPLICATION_UNAVAILABLE);
        UUID dataset=(UUID)app.get("dataset_id");
        var views=registeredViews(dataset);
        if (views.isEmpty()) return map("scopeType","FULL_VERSION");
        var view=exactCurrentView(app,views);
        var services=deliveryGrants(app,view,null,OffsetDateTime.now()).stream()
            .map(g->map("serviceSubjectId",g.get("service_subject_id"),"name",g.get("display_name"),
                "validUntil",instantText(g.get("authority_end")))).toList();
        return map("scopeType","CONTROLLED_VIEW","viewId",view.get("view_id"),
            "viewAuthorizationVersion",view.get("authorization_version"),
            "viewValidUntil",instantText(view.get("publication_end")),
            "hasOriginalByteCopies",view.get("original_copies"),"allowedOperations",new TreeSet<>(CONTROLLED_ACTIONS),
            "allowedPurposeCodes",new TreeSet<>(CONTRACT_PURPOSES),"deliveryServices",services);
    }

    private static String instantText(Object value) {
        return value instanceof java.sql.Timestamp time ? time.toInstant().toString() : null;
    }

    private Map<String,Object> scopeTerms(Map<String,Object> body, Map<String,Object> app, OffsetDateTime until) {
        var views=registeredViews((UUID)app.get("dataset_id"));
        if (views.isEmpty()) {
            if (!"FULL_VERSION".equals(body.getOrDefault("scopeType","FULL_VERSION")) ||
                List.of("viewId","operations","purposeCodes","deliveryServiceSubjectId").stream().anyMatch(body::containsKey))
                throw error(HttpStatus.BAD_REQUEST,"CONTROLLED_CONTRACT_REQUIRES_REGISTERED_VIEW");
            return map("scopeType","FULL_VERSION");
        }
        if (!"CONTROLLED_VIEW".equals(body.get("scopeType")) || body.get("viewId")==null)
            throw error(HttpStatus.BAD_REQUEST,"CONTRACT_EXPLICIT_CONTROLLED_VIEW_REQUIRED");
        UUID viewId=uuid(body.get("viewId"));
        var view=exactCurrentView(app,views);
        if (!viewId.equals(view.get("view_id")))
            throw error(HttpStatus.NOT_FOUND,"CONTRACT_EXACT_VIEW_NOT_AVAILABLE");
        if (view.get("publication_end") instanceof java.sql.Timestamp end && until.toInstant().isAfter(end.toInstant()))
            throw error(HttpStatus.CONFLICT,"CONTRACT_OUTSIDE_VIEW_AUTHORITY_WINDOW");
        List<String> operations=selected(body,"operations",CONTROLLED_ACTIONS);
        List<String> purposes=selected(body,"purposeCodes",CONTRACT_PURPOSES);
        boolean copies=Boolean.TRUE.equals(view.get("original_copies"));
        if (copies && operations.contains("data.view.export") && !operations.contains("data.raw.download"))
            throw error(HttpStatus.BAD_REQUEST,"CONTRACT_ORIGINAL_BYTE_EXPORT_REQUIRES_RAW_OPERATION");
        var terms=map("scopeType","CONTROLLED_VIEW","viewId",viewId,
            "viewAuthorizationVersion",view.get("authorization_version"),"operations",operations,"purposeCodes",purposes);
        Object serviceId=body.get("deliveryServiceSubjectId");
        boolean requestedDelivery=operations.contains("delivery.package.download");
        if (requestedDelivery != (serviceId!=null))
            throw error(HttpStatus.BAD_REQUEST,"CONTRACT_PACKAGE_DELIVERY_REQUIRES_EXPLICIT_SERVICE");
        if (requestedDelivery) {
            if (!purposes.contains("CONTROLLED_DELIVERY") || !operations.containsAll(exportOperations(copies,"delivery.package.download")))
                throw error(HttpStatus.BAD_REQUEST,"CONTRACT_COMPLETE_DELIVERY_ACTIONS_REQUIRED");
            UUID service=uuid(serviceId);
            var grant=deliveryGrants(app,view,service,until).stream().findFirst().orElseThrow(
                ()->error(HttpStatus.NOT_FOUND,"CONTRACT_DELIVERY_SERVICE_SCOPE_NOT_AVAILABLE"));
            terms.put("deliveryServiceSubjectId",service);
            terms.put("deliveryServiceVersion",grant.get("service_version"));
            terms.put("deliveryGrantId",grant.get("grant_id"));
            terms.put("deliveryGrantVersion",grant.get("grant_version"));
        }
        return terms;
    }

    private AuthorizationService.GrantBinding grantBinding(Map<String,Object> row) {
        return new AuthorizationService.GrantBinding((UUID)row.get("grant_id"),((Number)row.get("grant_version")).longValue());
    }

    private void persistScope(UUID contract, int revision, Map<String,Object> terms) {
        if (!"CONTROLLED_VIEW".equals(terms.get("scopeType"))) return;
        jdbc.update("""
            INSERT INTO ds_contract_controlled_revision(contract_id,revision,view_id,view_authorization_version,
                delivery_service_id,delivery_service_version,delivery_grant_id,delivery_grant_version)
            VALUES (?,?,?,?,?,?,?,?)
            """,contract,revision,terms.get("viewId"),terms.get("viewAuthorizationVersion"),
            terms.get("deliveryServiceSubjectId"),terms.get("deliveryServiceVersion"),terms.get("deliveryGrantId"),terms.get("deliveryGrantVersion"));
        List<?> purposes=(List<?>)terms.get("purposeCodes");
        for (Object operation:(List<?>)terms.get("operations")) {
            jdbc.update("INSERT INTO ds_contract_operation(contract_id,contract_revision,operation_code,purpose_codes) VALUES (?,?,?,?::varchar[])",
                contract,revision,operation,"{"+String.join(",",purposes.stream().map(Object::toString).toList())+"}");
        }
    }

    private boolean sameScopeRequest(Map<String,Object> contract, Map<String,Object> body) {
        if ("FULL_VERSION".equals(contract.get("scope_type")))
            return "FULL_VERSION".equals(body.getOrDefault("scopeType","FULL_VERSION")) &&
                List.of("viewId","operations","purposeCodes","deliveryServiceSubjectId").stream().noneMatch(body::containsKey);
        if (!"CONTROLLED_VIEW".equals(body.get("scopeType")) || body.get("viewId")==null) return false;
        var frozen=one("SELECT terms FROM ds_contract_change WHERE contract_id=? AND revision=?",contract.get("contract_id"),contract.get("revision"));
        try {
            var terms=json.readTree(frozen.get("terms").toString());
            String service=body.get("deliveryServiceSubjectId")==null?null:uuid(body.get("deliveryServiceSubjectId")).toString();
            return uuid(body.get("viewId")).toString().equals(terms.path("viewId").asText()) &&
                json.valueToTree(selected(body,"operations",CONTROLLED_ACTIONS)).equals(terms.path("operations")) &&
                json.valueToTree(selected(body,"purposeCodes",CONTRACT_PURPOSES)).equals(terms.path("purposeCodes")) &&
                Objects.equals(service,terms.path("deliveryServiceSubjectId").asText(null));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {throw new IllegalStateException(invalid);}
    }

    private boolean hasDeliveryPlan(UUID id, Object revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ds_contract_controlled_revision
              WHERE contract_id=? AND revision=? AND delivery_service_id IS NOT NULL)
            """,Boolean.class,id,revision));
    }

    public boolean deliveryAuthorityCurrent(UUID id, int revision) {
        var rows=jdbc.queryForList("""
            SELECT c.scope_type, c.provider_org_id, p.dataset_id,p.version_id,p.index_version_id,
                   plan.delivery_service_id, plan.delivery_grant_id AS grant_id, plan.delivery_grant_version AS grant_version,
                   s.version=plan.delivery_service_version AND s.purpose='SCHEDULED_DELIVERY' AS service_version_current,
                   COALESCE((cv.public_scope_summary->>'hasOriginalByteCopies')::boolean,false) AS original_copies
            FROM ds_contract c JOIN ds_product_version p ON p.product_version_id=c.product_version_id
            LEFT JOIN ds_contract_controlled_revision plan ON plan.contract_id=c.contract_id AND plan.revision=c.revision
            LEFT JOIN ds_controlled_view cv ON cv.view_id=plan.view_id
            LEFT JOIN ds_service_subject s ON s.service_subject_id=plan.delivery_service_id
            WHERE c.contract_id=? AND c.revision=?
            """,id,revision);
        if (rows.size()!=1) return false;
        var row=rows.get(0);
        if ("FULL_VERSION".equals(row.get("scope_type"))) return legacyDeliveryAuthorityCurrent(id,revision);
        if (!Boolean.TRUE.equals(row.get("service_version_current")) || !scopeCurrent(id,revision)) return false;
        UUID service=(UUID)row.get("delivery_service_id");
        UUID dataset=(UUID)row.get("dataset_id"),version=(UUID)row.get("version_id"),index=(UUID)row.get("index_version_id");
        boolean copies=Boolean.TRUE.equals(row.get("original_copies"));
        if(!auth.persistedServiceGrantCurrent(service,(UUID)row.get("provider_org_id"),dataset,version,index,
            exportOperations(copies,"delivery.execute"),"CONTROLLED_DELIVERY",grantBinding(row)))return false;
        Set<String> consumption=copies?Set.of("data.view.export","data.raw.download"):Set.of("data.view.export");
        return deliveryOrigins.getObject().datasetInputsCurrent(dataset,version,index,consumption,
            "CONTROLLED_DELIVERY",auth.serviceActorFor(service));
    }

    private boolean legacyDeliveryAuthorityCurrent(UUID id, int revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ds_contract c
              JOIN ds_product_version p ON p.product_version_id=c.product_version_id
              WHERE c.contract_id=? AND c.revision=? AND c.scope_type='FULL_VERSION'
                AND NOT EXISTS(SELECT 1 FROM ds_controlled_view cv WHERE cv.dataset_id=p.dataset_id)
                AND (SELECT count(DISTINCT a.operation_code) FROM ds_contract_operation a
                     WHERE a.contract_id=c.contract_id AND a.contract_revision=c.revision
                       AND a.operation_code IN ('data.raw.download','data.view.export','delivery.package.download')
                       AND 'CONTROLLED_DELIVERY'=ANY(a.purpose_codes))=3)
            """,Boolean.class,id,revision));
    }

    private boolean scopeCurrent(UUID id, int revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT ds_controlled_contract_scope_current(?,?)",Boolean.class,id,revision));
    }

    private void requireCurrentPackageContract(UUID id, int revision, boolean controlledView) {
        if (!isAvailable(id) || (controlledView && !scopeCurrent(id,revision)) || !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ds_contract WHERE contract_id=? AND revision=? AND status='CONFIRMED'
                AND valid_from<=clock_timestamp() AND clock_timestamp()<valid_to AND revoked_at IS NULL)
            """,Boolean.class,id,revision))) throw error(HttpStatus.FORBIDDEN,"CONTRACT_PACKAGE_SCOPE_NOT_CURRENT");
    }

    void trackPackageOutput(UUID id, int revision, boolean controlledView) {
        auth.rememberSourceConstraint("delivery-package:" + id + ":" + revision,
            () -> requireCurrentPackageContract(id, revision, controlledView));
    }

    void requireControlledPackage(UUID id, int revision, UUID dataset, UUID version, UUID index, boolean consumer) {
        requireCurrentPackageContract(id, revision, true);
        if (!consumer) {
            access.requireVersionRead(dataset,version,index);
            return;
        }
        boolean copies=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT COALESCE((cv.public_scope_summary->>'hasOriginalByteCopies')::boolean,false)
            FROM ds_contract_controlled_revision plan JOIN ds_controlled_view cv ON cv.view_id=plan.view_id
            WHERE plan.contract_id=? AND plan.revision=?
            """,Boolean.class,id,revision));
        var actor=auth.actor();
        Set<String> operations=exportOperations(copies,"delivery.package.download");
        var grants=jdbc.queryForList("""
            SELECT grant_id,version AS grant_version FROM ds_resource_grant
            WHERE contract_id=? AND contract_revision=? AND dataset_id=? AND grantee_org_id=? AND state='ACTIVE'
            ORDER BY grant_id
            """,id,revision,dataset,actor.orgId());
        if (grants.stream().noneMatch(g->auth.canUseDatasetBound(actor,dataset,version,index,operations,"CONTROLLED_DELIVERY",grantBinding(g))))
            throw error(HttpStatus.NOT_FOUND,"CONTRACT_PACKAGE_EXACT_GRANT_REQUIRED");
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
        var result = scopeTerms(body,app,to);
        result.putAll(map("purpose",purpose,"validFrom",from.toString(),"validTo",to.toString(),"sendTime",send.toString(),"endpointId",endpoint,"minimumQuality",threshold,"productVersionId",app.get("product_version_id"),"qualityReportId",app.get("quality_report_id")));
        return result;
    }
    @Transactional public Map<String,Object> create(Map<String,Object> body){
        auth.lockIdentityMutation();
        UUID application=uuid(body.getOrDefault("applicationId",body.get("intentId")));var app=application(application,true);
        requireProviderScope(app,APPLICATION_UNAVAILABLE);
        if(!"APPROVED".equals(app.get("status")))throw error(HttpStatus.CONFLICT,"申请需经供给方批准后才能提交技术合约");
        var existing=jdbc.queryForList("SELECT * FROM ds_contract WHERE application_id=?",application);
        if(!existing.isEmpty()){
            var old=existing.get(0);Object minimum=body.getOrDefault("minimumQuality",body.get("qualityThreshold"));
            boolean same=uuid(body.get("endpointId")).equals(old.get("endpoint_id"))&&time(body,"validFrom").toInstant().equals(((java.sql.Timestamp)old.get("valid_from")).toInstant())&&time(body,"validTo").toInstant().equals(((java.sql.Timestamp)old.get("valid_to")).toInstant())&&time(body,"sendTime").toInstant().equals(((java.sql.Timestamp)old.get("scheduled_at")).toInstant())&&text(body,"purpose",app.get("purpose").toString()).equals(old.get("purpose"));
            try{same&=(minimum==null||minimum.toString().isBlank())?old.get("minimum_quality")==null:old.get("minimum_quality")!=null&&Double.parseDouble(minimum.toString())==((Number)old.get("minimum_quality")).doubleValue();}catch(Exception e){same=false;}
            if(!same||!sameScopeRequest(old,body))throw error(HttpStatus.CONFLICT,"该申请已有技术合约；条款变更请显式修订并重新确认");return view((UUID)old.get("contract_id"));
        }
        var terms=terms(body,app);UUID id=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ds_contract(contract_id,application_id,product_version_id,provider_org_id,consumer_org_id,purpose,scope_type,valid_from,valid_to,scheduled_at,endpoint_id,minimum_quality,status,provider_confirmed_at,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PENDING_CONFIRMATION',now(),?)
            """,id,application,app.get("product_version_id"),app.get("provider_org_id"),app.get("consumer_org_id"),terms.get("purpose"),terms.get("scopeType"),OffsetDateTime.parse(terms.get("validFrom").toString()),OffsetDateTime.parse(terms.get("validTo").toString()),OffsetDateTime.parse(terms.get("sendTime").toString()),terms.get("endpointId"),terms.get("minimumQuality"),SecurityUtils.getUserId());
        change(id,1,terms);persistScope(id,1,terms);audit("PROPOSE_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)app.get("consumer_org_id"),(UUID)app.get("provider_org_id"),map("revision",1,"state","PENDING_CONFIRMATION"));return view(id);
    }
    private void change(UUID id,int revision,Map<String,Object> terms){
        var reviewed = new LinkedHashMap<>(terms);
        for (String internal:List.of("deliveryGrantId","deliveryGrantVersion","deliveryServiceVersion")) reviewed.remove(internal);
        jdbc.update("INSERT INTO ds_contract_change(contract_id,revision,terms,actor_user_id) VALUES (?,?,?::jsonb,?)",id,revision,encode(reviewed),SecurityUtils.getUserId());
    }
    @Transactional public Map<String,Object> confirm(UUID id,Map<String,Object> body){
        auth.lockIdentityMutation();
        var c=contract(id,true);var app=application((UUID)c.get("application_id"),false);
        boolean provider=access.actorOrg().equals(c.get("provider_org_id"));
        if(provider)requireProviderScope(c,CONTRACT_UNAVAILABLE);else requireConsumerContractOperation(c,app,"contract.confirm");
        if(!String.valueOf(c.get("revision")).equals(String.valueOf(body.get("revision"))))throw error(HttpStatus.CONFLICT,"条款已变更，请读取当前修订后确认");
        if(!Set.of("PENDING_CONFIRMATION","CONFIRMED").contains(c.get("status"))||!"APPROVED".equals(app.get("status")))throw error(HttpStatus.CONFLICT,"合约或申请状态不允许确认");
        if(!isAvailable(id))throw error(HttpStatus.CONFLICT,"已到期或冻结快照/接收端/真实质量报告不可用，不能确认授权");
        if("CONFIRMED".equals(c.get("status")))return view(id);
        int revision=((Number)c.get("revision")).intValue();
        boolean schedule="FULL_VERSION".equals(c.get("scope_type"))||hasDeliveryPlan(id,revision);
        if(schedule&&!deliveryAuthorityCurrent(id,revision))
            throw error(HttpStatus.CONFLICT,"FULL_VERSION".equals(c.get("scope_type"))
                ?"LEGACY_FULL_VERSION_DELIVERY_SCOPE_REQUIRED":"CONTRACT_DELIVERY_SERVICE_AUTHORITY_CHANGED");
        String field=provider?"provider_confirmed_at":"consumer_confirmed_at";
        jdbc.update("UPDATE ds_contract SET "+field+"=COALESCE("+field+",now()),updated_at=now() WHERE contract_id=?",id);
        jdbc.update("UPDATE ds_contract SET status='CONFIRMED',updated_at=now() WHERE contract_id=? AND provider_confirmed_at IS NOT NULL AND consumer_confirmed_at IS NOT NULL",id);
        if(schedule&&"CONFIRMED".equals(jdbc.queryForObject("SELECT status FROM ds_contract WHERE contract_id=?",String.class,id)))jdbc.update("INSERT INTO ds_delivery_task(contract_id,contract_revision,status,next_attempt_at) SELECT contract_id,revision,'PENDING',scheduled_at FROM ds_contract WHERE contract_id=? ON CONFLICT(contract_id,contract_revision) DO NOTHING",id);
        audit("CONFIRM_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("revision",c.get("revision"),"party",provider?"PROVIDER":"CONSUMER"));return view(id);
    }
    /** Scheduler authority uses actual snapshot state, independent of a logged-in browser. */
    public boolean isAvailable(UUID id){return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM ds_contract c JOIN ds_application a ON a.application_id=c.application_id AND a.status='APPROVED'
          JOIN org provider ON provider.org_id=c.provider_org_id AND provider.org_status=1
          JOIN org consumer ON consumer.org_id=c.consumer_org_id AND consumer.org_status=1
          JOIN ds_product_version p ON p.product_version_id=c.product_version_id
          JOIN mm_dataset d ON d.dataset_id=p.dataset_id AND d.org_id=c.provider_org_id AND d.status='ACTIVE'
          JOIN mm_dataset_version v ON v.version_id=p.version_id AND v.dataset_id=d.dataset_id AND v.raw_commit_id=p.raw_commit_id
          JOIN artifact_commit raw ON raw.commit_id=p.raw_commit_id AND raw.repo_id=d.raw_repo_id AND raw.commit_status='PUBLISHED'
          JOIN mm_index_version i ON i.index_version_id=p.index_version_id AND i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_status='READY'
          JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id AND idx.commit_status='PUBLISHED'
          JOIN ds_quality_report q ON q.report_id=p.quality_report_id AND q.version_id=v.version_id AND q.manifest_digest=p.manifest_digest AND q.completed_at IS NOT NULL AND q.blocking_count=0 AND q.status IN ('PASSED','PARTIAL')
          JOIN ds_receiver_endpoint e ON e.endpoint_id=c.endpoint_id AND e.consumer_org_id=c.consumer_org_id AND e.enabled=true
        WHERE c.contract_id=? AND c.valid_to>now() AND c.revoked_at IS NULL AND c.status IN ('PENDING_CONFIRMATION','CONFIRMED')
          AND (c.scope_type='FULL_VERSION' OR ds_controlled_contract_scope_current(c.contract_id,c.revision))
          AND (c.minimum_quality IS NULL OR q.score>=c.minimum_quality))
        """,Boolean.class,id));}
    @Transactional public Map<String,Object> revoke(UUID id,Map<String,Object> body){
        auth.lockIdentityMutation();
        var c=contract(id,true);var app=application((UUID)c.get("application_id"),false);
        if(access.actorOrg().equals(c.get("provider_org_id")))requireProviderScope(c,CONTRACT_UNAVAILABLE);else requireConsumerContractOperation(c,app,"contract.revoke");
        String reason=text(body,"reason","");if(reason.isBlank())throw error(HttpStatus.BAD_REQUEST,"撤销需要填写原因");
        if("REVOKED".equals(c.get("status")))return view(id);
        jdbc.update("UPDATE ds_contract SET status='REVOKED',revoked_at=now(),revoke_reason=?,updated_at=now() WHERE contract_id=?",reason,id);
        jdbc.update("UPDATE ds_delivery_task SET status='CANCELLED',last_error='授权已显式撤销',updated_at=now() WHERE contract_id=? AND status<>'DELIVERED'",id);
        jdbc.update("UPDATE ds_delivery_attempt a SET status='CANCELLED',error_message='授权已撤销',finished_at=now() FROM ds_delivery_task t WHERE t.task_id=a.task_id AND t.contract_id=? AND a.status='RUNNING'",id);
        audit("REVOKE_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("reason",reason,"revision",c.get("revision")));return view(id);
    }
    @Transactional public Map<String,Object> revise(UUID id,Map<String,Object> body){
        auth.lockIdentityMutation();
        var c=contract(id,true);requireProviderScope(c,CONTRACT_UNAVAILABLE);
        if(!Set.of("PENDING_CONFIRMATION","CONFIRMED").contains(c.get("status")))throw error(HttpStatus.CONFLICT,"已撤销或取消的合约不能静默恢复，请重新申请");
        if(!String.valueOf(c.get("revision")).equals(String.valueOf(body.get("revision"))))throw error(HttpStatus.CONFLICT,"请在当前修订基础上修改");
        var app=application((UUID)c.get("application_id"),false);var terms=terms(body,app);int revision=((Number)c.get("revision")).intValue()+1;
        jdbc.update("UPDATE ds_contract SET revision=?,purpose=?,scope_type=?,valid_from=?,valid_to=?,scheduled_at=?,endpoint_id=?,minimum_quality=?,status='PENDING_CONFIRMATION',provider_confirmed_at=now(),consumer_confirmed_at=null,updated_at=now() WHERE contract_id=?",revision,terms.get("purpose"),terms.get("scopeType"),OffsetDateTime.parse(terms.get("validFrom").toString()),OffsetDateTime.parse(terms.get("validTo").toString()),OffsetDateTime.parse(terms.get("sendTime").toString()),terms.get("endpointId"),terms.get("minimumQuality"),id);
        jdbc.update("UPDATE ds_delivery_task SET status='CANCELLED',last_error='条款修订，旧交付计划已取消',updated_at=now() WHERE contract_id=? AND status<>'DELIVERED'",id);
        jdbc.update("UPDATE ds_delivery_attempt a SET status='CANCELLED',error_message='条款已修订',finished_at=now() FROM ds_delivery_task t WHERE t.task_id=a.task_id AND t.contract_id=? AND a.status='RUNNING'",id);
        change(id,revision,terms);persistScope(id,revision,terms);audit("REVISE_TECHNICAL_CONTRACT","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("revision",revision));return view(id);
    }
    public List<Map<String,Object>> history(UUID id){contract(id,false);var rows=jdbc.queryForList("SELECT revision,terms,created_at AS \"createdAt\" FROM ds_contract_change WHERE contract_id=? ORDER BY revision",id);rows.forEach(r->r.computeIfPresent("terms",(k,v)->{try{return json.readValue(v.toString(),Object.class);}catch(Exception e){throw new IllegalStateException(e);}}));return rows;}
    @Transactional public Map<String,Object> retry(UUID id){
        var c=contract(id,true);requireProviderScope(c,CONTRACT_UNAVAILABLE);
        if(!"CONFIRMED".equals(c.get("status"))||!isAvailable(id))throw error(HttpStatus.CONFLICT,"合约未生效、已到期、已撤销或范围不可用");
        if(!deliveryAuthorityCurrent(id,((Number)c.get("revision")).intValue()))throw error(HttpStatus.CONFLICT,"CONTRACT_DELIVERY_SERVICE_AUTHORITY_CHANGED");
        var t=one("SELECT * FROM ds_delivery_task WHERE contract_id=? AND contract_revision=? FOR UPDATE",id,c.get("revision"));
        if(Set.of("DELIVERED","RUNNING","PENDING").contains(t.get("status")))return map("taskId",t.get("task_id"),"status",t.get("status"),"nextAttemptAt",t.get("next_attempt_at"));
        if(((Number)t.get("attempt_count")).intValue()>=20)throw error(HttpStatus.CONFLICT,"已达到本修订20次总尝试上限，请检查原因后显式修订计划");
        jdbc.update("UPDATE ds_delivery_task SET status='RETRY',max_attempts=LEAST(20,GREATEST(max_attempts,attempt_count+3)),next_attempt_at=GREATEST(now(),?::timestamptz),lease_token=null,lease_until=null,updated_at=now() WHERE task_id=?",c.get("scheduled_at"),t.get("task_id"));
        audit("REQUEST_DELIVERY_RETRY","CONTRACT",id,(UUID)c.get("consumer_org_id"),(UUID)c.get("provider_org_id"),map("taskId",t.get("task_id")));return map("taskId",t.get("task_id"),"status","RETRY");
    }
    private void audit(String action,String type,UUID id,UUID consumer,UUID provider,Map<String,Object> details){for(UUID org:new HashSet<>(List.of(consumer,provider)))jdbc.update("INSERT INTO ds_audit_event(org_id,actor_user_id,action,object_type,object_id,details) VALUES (?,?,?,?,?,?::jsonb)",org,SecurityUtils.getUserId(),action,type,id,encode(details));}
}
