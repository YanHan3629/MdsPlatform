package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.service.*;
import com.fwdrobo.sirius.dto.mm.MmSpaceReq;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Persistent business associations; mm/artifact remain the snapshot authority. */
@Service
public class PersistentDataSpaceService {
    private final JdbcTemplate jdbc;
    private final DatasetAccessService access;
    private final FileService files;
    private final MmSpaceService spaces;
    private final MmIndexOutboxService outbox;
    private final ObjectMapper json;
    public PersistentDataSpaceService(JdbcTemplate jdbc,DatasetAccessService access,FileService files,
            MmSpaceService spaces,MmIndexOutboxService outbox,ObjectMapper json) {
        this.jdbc=jdbc;this.access=access;this.files=files;this.spaces=spaces;this.outbox=outbox;this.json=json;
    }
    static Map<String,Object> map(Object... fields) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2) result.put((String)fields[i],fields[i+1]);
        return result;
    }
    private ResponseStatusException error(HttpStatus status,String message) {return new ResponseStatusException(status,message);}
    private String text(Map<String,Object> body,String name,String fallback) {return body.get(name)==null?fallback:body.get(name).toString().trim();}
    private String required(Map<String,Object> body,String name) {
        String value=text(body,name,"");if(value.isBlank()||value.length()>4000) throw error(HttpStatus.BAD_REQUEST,"请填写有效的"+name);return value;
    }
    private UUID uuid(String value) {try{return UUID.fromString(value);}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"对象标识需要有效UUID");}}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Object decode(Object value) {try{return value==null?null:json.readValue(value.toString(),Object.class);}catch(Exception e){throw new IllegalStateException("持久化JSON无效",e);}}
    private Map<String,Object> one(String sql,Object... args) {
        var rows=jdbc.queryForList(sql,args);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"对象不存在或不可访问");return rows.get(0);
    }
    private void audit(String action,String type,UUID id,Object detail) {
        jdbc.update("INSERT INTO ds_audit_event(org_id,actor_user_id,action,object_type,object_id,details) VALUES (?,?,?,?,?,?::jsonb)",
            access.actorOrg(),SecurityUtils.getUserId(),action,type,id,encode(detail));
    }
    private List<Map<String,Object>> normalise(List<Map<String,Object>> rows) {
        for(var row:rows) row.replaceAll((key,value)->value instanceof java.sql.Timestamp t?t.toInstant().atOffset(ZoneOffset.ofHours(8)).toString():
            value instanceof org.postgresql.util.PGobject p && "jsonb".equals(p.getType())?decode(p):value);
        return rows;
    }
    public List<Map<String,Object>> listBusinessDomains() {
        String[][] names={{"marketing","营销"},{"service","服务"},{"retail","零售"},{"customer-service","客服"},{"channel-sales","渠道销售"},{"procurement","采购"},
            {"manufacturing","制造"},{"rd","研发"},{"quality","质量"},{"logistics","物流"},{"supply-chain","供应链"},{"finance","财务"},{"planning","企划"},{"legal","法务"},{"hr","人力"},{"strategy","战略"},{"it","IT"}};
        return Arrays.stream(names).map(n->map("code",n[0],"name",n[1])).toList();
    }
    private String businessName(Object code) {return listBusinessDomains().stream().filter(d->Objects.equals(d.get("code"),code)).map(d->d.get("name").toString()).findFirst().orElse("待补充");}
    public List<Map<String,Object>> listSpaces() {
        return normalise(jdbc.queryForList("""
            SELECT s.space_id AS "spaceId",s.space_name AS "spaceName",o.org_name AS "orgName",s.org_id AS "orgId",
            s.status,'NOT_ASSESSED' AS "authStatus",'OWNER' AS role,s.created_at AS "createdAt"
            FROM mm_space s JOIN org o ON o.org_id=s.org_id WHERE s.org_id=? ORDER BY s.created_at DESC
            """,access.actorOrg()));
    }
    @Transactional public Map<String,Object> registerSpace(Map<String,Object> body) {
        access.actorOrg();var created=spaces.create(new MmSpaceReq(required(body,"orgName"),text(body,"description","")));
        audit("CREATE_SPACE","SPACE",created.spaceId(),map("spaceName",created.spaceName()));
        return map("spaceId",created.spaceId(),"status","ACTIVE","authStatus","NOT_ASSESSED");
    }
    public Map<String,Object> verifySpace(String id) {throw error(HttpStatus.CONFLICT,"未配置企业认证审核；创建平台空间不代表企业已认证");}
    private Map<String,Object> source(UUID source) {
        var row=one("SELECT s.*,d.space_id FROM ds_source s JOIN mm_dataset d ON d.dataset_id=s.dataset_id WHERE source_id=?",source);
        access.requireDatasetWrite((UUID)row.get("dataset_id"));return row;
    }
    @Transactional public Map<String,Object> createSource(Map<String,Object> body) {
        UUID org=access.actorOrg();String type=text(body,"sourceType","EXISTING_DATASET");
        if(!Set.of("EXISTING_DATASET","LOCAL_UPLOAD").contains(type))throw error(HttpStatus.CONFLICT,"该外部连接器尚未配置，不能执行接入；可复用已有数据或打开真实文件/目录上传");
        UUID dataset=uuid(required(body,"datasetId"));access.requireDatasetWrite(dataset);
        var actual=one("SELECT space_id FROM mm_dataset WHERE dataset_id=?",dataset);
        if(!text(body,"spaceId","").isBlank()&&!actual.get("space_id").equals(uuid(body.get("spaceId").toString())))throw error(HttpStatus.BAD_REQUEST,"空间与数据集不匹配");
        UUID id=UUID.randomUUID();
        int changed=jdbc.update("""
            INSERT INTO ds_source(source_id,dataset_id,org_id,source_type,description,created_by) VALUES (?,?,?,'EXISTING_DATASET',?,?)
            ON CONFLICT(dataset_id) DO NOTHING
            """,id,dataset,org,text(body,"description",""),SecurityUtils.getUserId());
        UUID persisted=jdbc.queryForObject("SELECT source_id FROM ds_source WHERE dataset_id=?",UUID.class,dataset);
        if(changed>0)audit("ASSOCIATE_EXISTING_DATASET","SOURCE",persisted,map("datasetId",dataset));
        return listSources().stream().filter(s->persisted.equals(s.get("sourceId"))).findFirst().orElseThrow();
    }
    public List<Map<String,Object>> listSources() {
        var rows=jdbc.queryForList("""
            SELECT s.source_id AS "sourceId",s.dataset_id AS "datasetId",d.space_id AS "spaceId",d.dataset_name AS "sourceName",
            d.modality_type AS "modalityType",s.source_type AS "sourceType",o.org_name AS "providerName",c.business_code AS "businessCode",
            'ASSOCIATED' AS status,s.created_at AS "createdAt" FROM ds_source s JOIN mm_dataset d ON d.dataset_id=s.dataset_id
            JOIN org o ON o.org_id=s.org_id LEFT JOIN ds_catalog c ON c.source_id=s.source_id WHERE s.org_id=? ORDER BY s.created_at DESC
            """,access.actorOrg());
        rows.forEach(r->r.put("businessName",businessName(r.get("businessCode"))));return normalise(rows);
    }
    @Transactional public Map<String,Object> createCatalog(String sourceId,Map<String,Object> body) {
        UUID id=uuid(sourceId);source(id);String code=text(body,"businessCode","");
        if(!code.isBlank()&&listBusinessDomains().stream().noneMatch(d->code.equals(d.get("code"))))throw error(HttpStatus.BAD_REQUEST,"业务域不在分类字典中");
        jdbc.update("""
            INSERT INTO ds_catalog(source_id,catalog_name,business_code,description,intended_use,updated_by) VALUES (?,?,?,?,?,?)
            ON CONFLICT(source_id) DO UPDATE SET catalog_name=EXCLUDED.catalog_name,business_code=EXCLUDED.business_code,
            description=EXCLUDED.description,intended_use=EXCLUDED.intended_use,updated_by=EXCLUDED.updated_by,updated_at=now()
            """,id,required(body,"catalogName"),code.isBlank()?null:code,text(body,"description",""),text(body,"intendedUse",""),SecurityUtils.getUserId());
        audit("UPDATE_CATALOG","SOURCE",id,map("businessCode",code));
        return listCatalogs().stream().filter(c->id.equals(c.get("sourceId"))).findFirst().orElseThrow();
    }
    public List<Map<String,Object>> listCatalogs() {
        var rows=jdbc.queryForList("""
            SELECT c.source_id AS "sourceId",c.source_id AS "catalogId",c.catalog_name AS "catalogName",c.business_code AS "businessCode",
            c.description,c.intended_use AS "intendedUse",s.dataset_id AS "datasetId",'DESCRIBED' AS status,c.updated_at AS "updatedAt"
            FROM ds_catalog c JOIN ds_source s ON s.source_id=c.source_id WHERE s.org_id=? ORDER BY c.updated_at DESC
            """,access.actorOrg());rows.forEach(r->r.put("businessName",businessName(r.get("businessCode"))));return normalise(rows);
    }
    public Map<String,Object> manifestSummary(UUID dataset,UUID version) {
        access.requireVersionRead(dataset,version,null);
        return internalManifest(dataset,version);
    }
    // Package-private: only leased service work calls this without a user context.
    Map<String,Object> internalManifest(UUID dataset,UUID version) {
        var scope=one("SELECT d.raw_repo_id,v.raw_commit_id FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id WHERE d.dataset_id=? AND v.version_id=?",dataset,version);
        List<Map<String,Object>> identities=new ArrayList<>();Map<String,Long> formats=new TreeMap<>();long size=0;
        for(var file:files.listFilesByRepoAndCommit((UUID)scope.get("raw_repo_id"),(UUID)scope.get("raw_commit_id"))) {
            identities.add(map("logicalPath",file.getLogicalPath(),"sizeBytes",file.getSizeBytes(),"sha256",file.getSha256()));
            String path=file.getLogicalPath();String fmt=path.contains(".")?path.substring(path.lastIndexOf('.')+1).toUpperCase(Locale.ROOT):"UNKNOWN";
            if(fmt.equals("JPEG"))fmt="JPG";formats.merge(fmt,1L,Long::sum);size+=file.getSizeBytes()==null?0:file.getSizeBytes();
        }
        identities.sort(Comparator.comparing(r->r.get("logicalPath").toString()));
        try {return map("fileCount",identities.size(),"sizeBytes",size,"formatCounts",formats,"formats",new ArrayList<>(formats.keySet()),
            "manifestDigest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(identities).getBytes(StandardCharsets.UTF_8))),"files",identities);
        }catch(Exception e){throw new IllegalStateException("无法计算快照清单摘要",e);}
    }
    public List<Map<String,Object>> listDatasets() {
        UUID org=access.actorOrg();var rows=jdbc.queryForList("""
            SELECT d.dataset_id AS "datasetId",d.dataset_name AS "datasetName",d.space_id AS "spaceId",d.modality_type AS "modalityType",
            s.source_id AS "sourceId",c.business_code AS "businessCode",o.org_name AS "providerName",'OWNER' AS "accessMode",
            v.version_id AS "versionId",v.version_name AS "versionName",v.raw_commit_id AS "rawCommitId",v.version_status AS status,
            i.index_version_id AS "indexVersionId",COALESCE(i.index_status,'NOT_BUILT') AS "indexStatus",
            q.score AS "qualityScore",q.report_id AS "qualityReportId",COALESCE(q.status,'NOT_ASSESSED') AS "qualityStatus"
            FROM mm_dataset d JOIN org o ON o.org_id=d.org_id LEFT JOIN ds_source s ON s.dataset_id=d.dataset_id
            LEFT JOIN ds_catalog c ON c.source_id=s.source_id
            LEFT JOIN LATERAL(SELECT * FROM mm_dataset_version dv WHERE dv.dataset_id=d.dataset_id
                ORDER BY CASE WHEN dv.active_index_version_id IS NOT NULL THEN 0 WHEN dv.published_at IS NOT NULL THEN 1 ELSE 2 END,dv.created_at DESC LIMIT 1) v ON true
            LEFT JOIN mm_index_version i ON i.index_version_id=v.active_index_version_id AND i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
            LEFT JOIN LATERAL(SELECT * FROM ds_quality_report qr WHERE qr.version_id=v.version_id ORDER BY qr.created_at DESC LIMIT 1) q ON true
            WHERE d.org_id=? AND d.status='ACTIVE' ORDER BY d.created_at DESC
            """,org);
        Set<Object> present=new HashSet<>();rows.forEach(r->present.add(r.get("datasetId")));
        for(var scope:access.readableReadyScopes()) if(present.add(scope.get("datasetId"))) {
            var entry=new LinkedHashMap<>(scope);entry.put("accessMode","GRANTED");entry.put("status","PUBLISHED");entry.put("qualityStatus","NOT_ASSESSED");
            var metadata=jdbc.queryForList("""
                SELECT p.business_code AS "businessCode",o.org_name AS "providerName",q.score AS "qualityScore",q.status AS "qualityStatus",q.report_id AS "qualityReportId"
                FROM ds_contract c JOIN ds_product_version v ON v.product_version_id=c.product_version_id JOIN ds_product p ON p.product_id=v.product_id JOIN org o ON o.org_id=p.provider_org_id JOIN ds_quality_report q ON q.report_id=v.quality_report_id
                WHERE c.consumer_org_id=? AND c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.valid_from<=now() AND now()<c.valid_to AND v.dataset_id=? AND v.version_id=? AND v.index_version_id=? ORDER BY c.created_at DESC LIMIT 1
                """,org,scope.get("datasetId"),scope.get("versionId"),scope.get("indexVersionId"));
            if(!metadata.isEmpty())entry.putAll(metadata.get(0));rows.add(entry);
        }
        for(var row:rows) {
            row.put("businessName",businessName(row.get("businessCode")));row.put("recordCount",null);
            row.put("associationStatus",row.get("sourceId")==null?"NEEDS_METADATA":"ASSOCIATED");
            row.put("productStatus",jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_product p JOIN ds_source s ON s.source_id=p.source_id WHERE s.dataset_id=? AND p.status='PUBLISHED')",Boolean.class,row.get("datasetId"))?"PUBLISHED":"NOT_PUBLISHED");
            if(row.get("versionId")!=null) {
                var summary=manifestSummary((UUID)row.get("datasetId"),(UUID)row.get("versionId"));
                for(String key:List.of("fileCount","sizeBytes","formatCounts","formats","manifestDigest"))row.put(key,summary.get(key));
            } else row.put("fileCount",0L);
        }
        return normalise(rows);
    }
    public Map<String,Object> snapshot() {
        UUID org=access.actorOrg();var datasets=listDatasets();var products=listProducts();var sources=listSources();var catalogs=listCatalogs();
        long count=datasets.stream().mapToLong(d->((Number)d.getOrDefault("fileCount",0L)).longValue()).sum();
        long ready=datasets.stream().filter(d->"READY".equals(d.get("indexStatus"))).count();
        var owner=one("SELECT org_id AS \"orgId\",org_name AS name FROM org WHERE org_id=?",org);
        return map("platformName","产业链数据空间平台","chainOwner",owner,"businessDomains",listBusinessDomains(),"spaces",listSpaces(),
            "sources",sources,"catalogs",catalogs,"datasets",datasets,"products",products,"intents",listIntents(),"contracts",listContracts(),"deliveries",listDeliveries(),
            "kpis",map("sources",sources.size(),"catalogs",catalogs.size(),"datasets",datasets.size(),"readyDatasets",ready,"fileCount",count,
                "recordCount",null,"avgQuality",null,"qualityMeasuredDatasets",datasets.stream().filter(d->d.get("qualityScore")!=null).count(),"qualityBlockedDatasets",datasets.stream().filter(d->"FAILED".equals(d.get("qualityStatus"))).count(),"products",products.stream().filter(p->"PUBLISHED".equals(p.get("status"))).count()),
            "tasks",normalise(jdbc.queryForList("SELECT t.task_id AS \"taskId\",t.dataset_id AS \"datasetId\",t.dataset_version_id AS \"versionId\",d.dataset_name AS \"datasetName\",v.version_name AS \"versionName\",t.task_status AS status,t.stage,t.error_message AS error,t.updated_at AS \"updatedAt\" FROM mm_ingest_task t JOIN mm_dataset d ON d.dataset_id=t.dataset_id JOIN mm_dataset_version v ON v.version_id=t.dataset_version_id WHERE t.org_id=? ORDER BY t.updated_at DESC LIMIT 50",org)),
            "events",normalise(jdbc.queryForList("SELECT a.event_id AS \"eventId\",a.action AS title,a.details AS detail,a.object_type AS \"objectType\",a.object_id AS \"objectId\",u.user_name AS actor,a.created_at AS \"createdAt\" FROM ds_audit_event a LEFT JOIN fwd_user u ON u.user_id=a.actor_user_id WHERE a.org_id=? ORDER BY a.created_at DESC LIMIT 50",org)));
    }
    @Transactional public Map<String,Object> buildIndex(String datasetId,Map<String,Object> body) {
        UUID dataset=uuid(datasetId);access.requireDatasetWrite(dataset);
        var scope=listDatasets().stream().filter(d->dataset.equals(d.get("datasetId"))).findFirst().orElseThrow();
        UUID version=body.containsKey("versionId")?uuid(body.get("versionId").toString()):(UUID)scope.get("versionId");
        var raw=one("SELECT v.raw_commit_id FROM mm_dataset_version v JOIN artifact_commit c ON c.commit_id=v.raw_commit_id WHERE v.dataset_id=? AND v.version_id=? AND c.commit_status='PUBLISHED'",dataset,version);
        UUID task=outbox.enqueueSnapshot(access.actorOrg(),dataset,version,(UUID)raw.get("raw_commit_id"),SecurityUtils.getUserId());
        audit("REQUEST_INDEX","DATASET",dataset,map("versionId",version,"taskId",task));
        return normalise(jdbc.queryForList("SELECT task_id AS \"taskId\",task_status AS status,stage,error_message AS error FROM mm_ingest_task WHERE task_id=?",task)).get(0);
    }
    public List<Map<String,Object>> listProducts() {
        UUID org=access.actorOrg();var rows=jdbc.queryForList("""
            SELECT p.product_id AS "productId",p.product_name AS "productName",p.description,p.status,p.visibility,p.business_code AS "businessCode",
            o.org_name AS "providerName",p.provider_org_id AS "providerOrgId",v.product_version_id AS "productVersionId",v.revision,
            v.dataset_id AS "datasetId",v.version_id AS "versionId",v.index_version_id AS "indexVersionId",dv.version_name AS "versionName",
            v.file_count AS "fileCount",v.formats,v.content_types AS "contentTypes",v.allowed_use AS rights,v.created_at AS "updatedAt",
            q.score AS "qualityScore",q.report_id AS "qualityReportId",
            jsonb_build_object('measuredAssertions',q.measured_count,'passedAssertions',q.passed_count,'unassessedRuleScopes',q.coverage->'unassessedRuleScopes','notApplicableRuleScopes',q.coverage->'notApplicableRuleScopes') AS "qualityCoverage",q.blocking_count AS "blockingCount",
            COALESCE(q.status,'NOT_ASSESSED') AS "qualityStatus",i.index_status AS "indexStatus"
            FROM ds_product p JOIN org o ON o.org_id=p.provider_org_id
            JOIN LATERAL(SELECT * FROM ds_product_version pv WHERE pv.product_id=p.product_id ORDER BY pv.revision DESC LIMIT 1) v ON true
            JOIN mm_dataset_version dv ON dv.version_id=v.version_id JOIN mm_index_version i ON i.index_version_id=v.index_version_id
            LEFT JOIN ds_quality_report q ON q.report_id=v.quality_report_id
            WHERE p.provider_org_id=? OR (p.status='PUBLISHED' AND p.visibility='PUBLIC') ORDER BY v.created_at DESC
            """,org);
        for(var row:rows) {
            boolean readable=access.canReadVersion((UUID)row.get("datasetId"),(UUID)row.get("versionId"),(UUID)row.get("indexVersionId"));
            row.put("hasAccess",readable);row.put("formats",decode(row.get("formats")));row.put("contentTypes",decode(row.get("contentTypes")));
            row.put("businessName",businessName(row.get("businessCode")));
            if(!readable)for(String key:List.of("datasetId","versionId","indexVersionId"))row.remove(key);
        }
        return normalise(rows);
    }
    public List<Map<String,Object>> searchProducts(String keyword,String modality,String business) {
        String needle=keyword==null?"":keyword.toLowerCase(Locale.ROOT).trim();
        return listProducts().stream().filter(p->"PUBLISHED".equals(p.get("status")))
            .filter(p->business==null||business.isBlank()||business.equals(p.get("businessCode")))
            .filter(p->modality==null||modality.isBlank()||((List<?>)p.get("contentTypes")).contains(modality)||((List<?>)p.get("formats")).contains(modality))
            .filter(p->needle.isBlank()||List.of("productName","description","providerName","businessName","formats","contentTypes").stream().anyMatch(k->String.valueOf(p.get(k)).toLowerCase(Locale.ROOT).contains(needle))).toList();
    }
    @Transactional public Map<String,Object> deleteProduct(String productId) {
        UUID id=uuid(productId);var product=one("SELECT p.*,s.dataset_id FROM ds_product p JOIN ds_source s ON s.source_id=p.source_id WHERE product_id=? FOR UPDATE",id);
        access.requireDatasetWrite((UUID)product.get("dataset_id"));jdbc.update("UPDATE ds_product SET status='OFFLINE',updated_at=now() WHERE product_id=?",id);
        audit("DELIST_PRODUCT","PRODUCT",id,Map.of());return map("productId",id,"status","OFFLINE");
    }
    public List<Map<String,Object>> listIntents() {
        return normalise(jdbc.queryForList("""
            SELECT a.application_id AS "intentId",a.product_version_id AS "productVersionId",p.product_name AS "productName",p.business_code AS "businessCode",
            a.purpose,a.status,dv.version_name AS "versionName",v.revision AS "productRevision",a.decision_reason AS "decisionReason",o.org_name AS "consumerName",a.consumer_org_id AS "consumerOrgId",
            p.provider_org_id AS "providerOrgId",a.created_by AS "createdBy",a.expected_at AS "expectedSendTime",a.created_at AS "createdAt",
            a.decided_at AS "decidedAt",u.user_name AS "decisionActor"
            FROM ds_application a JOIN ds_product_version v ON v.product_version_id=a.product_version_id JOIN ds_product p ON p.product_id=v.product_id JOIN mm_dataset_version dv ON dv.version_id=v.version_id
            JOIN org o ON o.org_id=a.consumer_org_id LEFT JOIN fwd_user u ON u.user_id=a.decided_by WHERE a.consumer_org_id=? OR p.provider_org_id=? ORDER BY a.created_at DESC
            """,access.actorOrg(),access.actorOrg()));
    }
    public List<Map<String,Object>> listContracts() {
        var rows=jdbc.queryForList("""
            SELECT c.contract_id AS "contractId",c.application_id AS "intentId",c.status,c.scheduled_at AS "sendTime",c.valid_from AS "validFrom",c.valid_to AS "validTo",c.revision,c.revoked_at AS "revokedAt",c.revoke_reason AS "revokeReason",
              c.provider_org_id AS "providerOrgId",c.consumer_org_id AS "consumerOrgId",c.provider_confirmed_at AS "providerConfirmedAt",c.consumer_confirmed_at AS "consumerConfirmedAt",c.purpose,c.scope_type AS "scopeType",
              c.minimum_quality AS "qualityThreshold",v.product_version_id AS "productVersionId",p.product_id AS "productId",p.product_name AS "productName",p.business_code AS "businessCode",v.revision AS "productRevision",
              v.dataset_id AS "datasetId",v.version_id AS "versionId",v.index_version_id AS "indexVersionId",dv.version_name AS "versionName",v.manifest_digest AS "manifestDigest",v.file_count AS "fileCount",
              po.org_name AS "providerName",co.org_name AS "consumerName",e.endpoint_id AS "endpointId",e.endpoint_name AS "receiverEndpoint",e.adapter_type AS "deliveryMethod",
              q.score AS "measuredQuality",q.rules_version AS "qualityRulesVersion",q.report_id AS "qualityReportId",COALESCE(t.status,'NOT_SCHEDULED') AS "pushStatus",t.task_id AS "taskId",t.last_error AS error,
              CASE WHEN c.status IN ('REVOKED','CANCELLED') THEN c.status WHEN c.valid_to<=now() THEN 'EXPIRED' WHEN c.status<>'CONFIRMED' THEN 'PENDING_CONFIRMATION' WHEN c.valid_from>now() THEN 'WAITING_VALIDITY' ELSE 'EFFECTIVE' END AS "authorizationStatus"
            FROM ds_contract c JOIN ds_product_version v ON v.product_version_id=c.product_version_id JOIN ds_product p ON p.product_id=v.product_id JOIN mm_dataset_version dv ON dv.version_id=v.version_id
            JOIN org po ON po.org_id=c.provider_org_id JOIN org co ON co.org_id=c.consumer_org_id JOIN ds_receiver_endpoint e ON e.endpoint_id=c.endpoint_id
            JOIN ds_quality_report q ON q.report_id=v.quality_report_id LEFT JOIN ds_delivery_task t ON t.contract_id=c.contract_id AND t.contract_revision=c.revision
            WHERE c.provider_org_id=? OR c.consumer_org_id=? ORDER BY c.created_at DESC
            """,access.actorOrg(),access.actorOrg());
        for(var row:rows){row.put("contractName","技术授权 · "+row.get("productName"));row.put("businessName",businessName(row.get("businessCode")));row.put("hasAccess",access.canReadVersion((UUID)row.get("datasetId"),(UUID)row.get("versionId"),(UUID)row.get("indexVersionId")));}return normalise(rows);
    }
    public List<Map<String,Object>> listDeliveries() {
        var rows=jdbc.queryForList("""
            SELECT t.task_id AS "taskId",t.contract_id AS "contractId",t.delivery_key AS "deliveryKey",t.contract_revision AS "contractRevision",t.status AS "deliveryStatus",t.attempt_count AS "attemptCount",t.max_attempts AS "maxAttempts",t.last_error AS error,t.next_attempt_at AS "nextAttemptAt",t.updated_at AS "updatedAt",
              p.product_name AS "productName",e.endpoint_name AS "receiverEndpoint",r.receipt_id AS "receiptId",r.file_count AS "fileCount",r.size_bytes AS "sizeBytes",r.manifest_digest AS "manifestDigest",r.received_at AS "deliveredAt",r.receiver_identity AS "receiverIdentity",
              v.dataset_id AS "datasetId",v.version_id AS "versionId",v.index_version_id AS "indexVersionId",c.revision AS "currentRevision",(c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.valid_from<=now() AND now()<c.valid_to) AS "liveContract"
            FROM ds_delivery_task t JOIN ds_contract c ON c.contract_id=t.contract_id JOIN ds_product_version v ON v.product_version_id=c.product_version_id JOIN ds_product p ON p.product_id=v.product_id
            JOIN ds_receiver_endpoint e ON e.endpoint_id=c.endpoint_id LEFT JOIN ds_receipt r ON r.delivery_key=t.delivery_key
            WHERE c.provider_org_id=? OR c.consumer_org_id=? ORDER BY t.created_at DESC
            """,access.actorOrg(),access.actorOrg());
        for(var row:rows){
            boolean readable=true;try{access.requireVersionRead((UUID)row.get("datasetId"),(UUID)row.get("versionId"),(UUID)row.get("indexVersionId"));}catch(ResponseStatusException e){readable=false;}
            row.put("hasPackageAccess",readable&&(access.ownsDataset((UUID)row.get("datasetId"))||(Boolean.TRUE.equals(row.get("liveContract"))&&row.get("currentRevision").equals(row.get("contractRevision")))));
        }
        return normalise(rows);
    }
    private ResponseStatusException migration(String detail) {access.actorOrg();return error(HttpStatus.CONFLICT,detail);}
    public Map<String,Object> uploadFiles(String id,MultipartFile[] input) {source(uuid(id));throw migration("请打开共用上传与发布区，在真实DRAFT版本上传文件或目录；不再写入独立演示存储");}
    public Map<String,Object> attachLocalDataset(String id,Map<String,Object> body) {source(uuid(id));throw migration("请使用真实版本的文件/目录上传入口；服务端不接受任意本地路径");}
    @Transactional public Map<String,Object> publishProduct(String id,Map<String,Object> body) {
        UUID dataset=uuid(id);access.requireDatasetWrite(dataset);
        // Serialize publication and product revisions for this actual dataset.
        one("SELECT dataset_id FROM mm_dataset WHERE dataset_id=? AND status='ACTIVE' FOR UPDATE",dataset);
        var source=one("SELECT s.source_id,c.catalog_name,c.business_code,c.description,c.intended_use FROM ds_source s JOIN ds_catalog c ON c.source_id=s.source_id WHERE s.dataset_id=?",dataset);
        var scope=listDatasets().stream().filter(d->dataset.equals(d.get("datasetId"))).findFirst().orElseThrow();
        UUID version=body.containsKey("versionId")?uuid(body.get("versionId").toString()):(UUID)scope.get("versionId");
        var raw=one("SELECT v.raw_commit_id,v.active_index_version_id FROM mm_dataset_version v JOIN artifact_commit c ON c.commit_id=v.raw_commit_id WHERE v.dataset_id=? AND v.version_id=? AND c.commit_status='PUBLISHED'",dataset,version);
        UUID index=body.containsKey("indexVersionId")?uuid(body.get("indexVersionId").toString()):(UUID)raw.get("active_index_version_id");
        if(index==null)throw error(HttpStatus.CONFLICT,"该版本尚无已发布的真实索引");
        access.requireVersionRead(dataset,version,index);
        var summary=manifestSummary(dataset,version);
        var reports=jdbc.queryForList("SELECT * FROM ds_quality_report WHERE dataset_id=? AND version_id=? AND manifest_digest=? ORDER BY created_at DESC LIMIT 1",dataset,version,summary.get("manifestDigest"));
        if(reports.isEmpty())throw error(HttpStatus.CONFLICT,"请先执行同一冻结快照的真实质量检查");
        var quality=reports.get(0);
        if(!Set.of("PASSED","PARTIAL").contains(quality.get("status"))||quality.get("completed_at")==null||quality.get("score")==null||((Number)quality.get("blocking_count")).longValue()!=0)
            throw error(HttpStatus.CONFLICT,"该快照的质量检查未完成、失败或存在关键阻断项，请查看报告");
        String name=required(body,"productName"),allowed=text(body,"allowedUse",String.valueOf(source.get("intended_use")));
        if(allowed.isBlank())throw error(HttpStatus.BAD_REQUEST,"必须说明产品允许用途");
        String visibility=text(body,"visibility","INTERNAL");if(!Set.of("PUBLIC","INTERNAL").contains(visibility))throw error(HttpStatus.BAD_REQUEST,"产品可见范围需为PUBLIC或INTERNAL");
        UUID product;int revision;
        if(body.containsKey("productId")){
            product=uuid(body.get("productId").toString());var old=one("SELECT product_id FROM ds_product WHERE product_id=? AND source_id=? AND provider_org_id=? FOR UPDATE",product,source.get("source_id"),access.actorOrg());
        }else{
            String key=text(body,"idempotencyKey",dataset+":"+HexFormat.of().formatHex(digest(name)));
            if(key.isBlank()||key.length()>128)throw error(HttpStatus.BAD_REQUEST,"发布幂等键需为1至128字符");
            product=UUID.randomUUID();
            jdbc.update("INSERT INTO ds_product(product_id,source_id,provider_org_id,product_name,description,business_code,visibility,status,created_by,request_key) VALUES (?,?,?,?,?,?,?,'DRAFT',?,?) ON CONFLICT(provider_org_id,request_key) WHERE request_key IS NOT NULL DO NOTHING",product,source.get("source_id"),access.actorOrg(),name,text(body,"description",String.valueOf(source.get("description"))),source.get("business_code"),visibility,SecurityUtils.getUserId(),key);
            var found=one("SELECT product_id,source_id FROM ds_product WHERE provider_org_id=? AND request_key=? FOR UPDATE",access.actorOrg(),key);
            if(!source.get("source_id").equals(found.get("source_id")))throw error(HttpStatus.CONFLICT,"发布幂等键已用于另一个数据源");product=(UUID)found.get("product_id");
        }
        var existing=jdbc.queryForList("SELECT * FROM ds_product_version WHERE product_id=? ORDER BY revision DESC LIMIT 1",product);
        var currentProduct=one("SELECT product_name,description,visibility,status FROM ds_product WHERE product_id=? FOR UPDATE",product);
        String description=text(body,"description",String.valueOf(source.get("description")));
        boolean sameMetadata=name.equals(currentProduct.get("product_name"))&&description.equals(currentProduct.get("description"))&&visibility.equals(currentProduct.get("visibility"));
        if(!existing.isEmpty()){
            var frozen=existing.get(0);
            if(version.equals(frozen.get("version_id"))&&index.equals(frozen.get("index_version_id"))&&allowed.equals(frozen.get("allowed_use"))&&sameMetadata){
                if("OFFLINE".equals(currentProduct.get("status"))){
                    if(!body.containsKey("productId"))throw error(HttpStatus.CONFLICT,"产品已下架，请明确选择该产品重新上架");
                    jdbc.update("UPDATE ds_product SET status='PUBLISHED',updated_at=now() WHERE product_id=?",product);
                    audit("RELIST_FROZEN_PRODUCT","PRODUCT",product,map("productVersionId",frozen.get("product_version_id")));
                }
                return listProducts().stream().filter(p->frozen.get("product_version_id").equals(p.get("productVersionId"))).findFirst().orElseThrow();
            }
            if(!body.containsKey("productId"))throw error(HttpStatus.CONFLICT,"相同发布键已有其他冻结范围，请明确选择产品升级并传入productId");
        }
        revision=existing.isEmpty()?1:((Number)existing.get(0).get("revision")).intValue()+1;
        UUID productVersion=UUID.randomUUID();
        @SuppressWarnings("unchecked") List<String> formats=(List<String>)summary.get("formats");Set<String> types=new TreeSet<>();
        for(String format:formats)types.add(switch(format){case "CSV","XLSX","JSON","JSONL"->"TABLE";case "PNG","JPG","SVG"->"IMAGE";case "OBJ","STEP","STP"->"GEOMETRY";case "TXT"->"TEXT";default->"DOCUMENT";});
        // Declared time-series representations are taken from the real index registrations.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mm_index_registration WHERE index_version_id=? AND semantic_space LIKE 'timeseries.%')",Boolean.class,index)))types.add("TIMESERIES");
        jdbc.update("""
            INSERT INTO ds_product_version(product_version_id,product_id,revision,dataset_id,version_id,index_version_id,raw_commit_id,manifest_digest,file_count,size_bytes,formats,content_types,allowed_use,quality_report_id,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?)
            """,productVersion,product,revision,dataset,version,index,raw.get("raw_commit_id"),summary.get("manifestDigest"),summary.get("fileCount"),summary.get("sizeBytes"),encode(formats),encode(types),allowed,quality.get("report_id"),SecurityUtils.getUserId());
        jdbc.update("UPDATE ds_product SET product_name=?,description=?,business_code=?,visibility=?,status='PUBLISHED',updated_at=now() WHERE product_id=?",name,text(body,"description",String.valueOf(source.get("description"))),source.get("business_code"),visibility,product);
        audit("PUBLISH_PRODUCT_VERSION","PRODUCT",product,map("productVersionId",productVersion,"revision",revision,"datasetId",dataset,"versionId",version,"indexVersionId",index,"manifestDigest",summary.get("manifestDigest"),"qualityReportId",quality.get("report_id")));
        return listProducts().stream().filter(p->productVersion.equals(p.get("productVersionId"))).findFirst().orElseThrow();
    }
    private byte[] digest(String text){try{return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new IllegalStateException(e);}}
    public Map<String,Object> runVal2017Flow(Map<String,Object> body) {throw migration("演示流程已停用，请使用当前账号真实数据集的工作台流程");}
    public Map<String,Object> syncProductToMinio(String id,Map<String,Object> body) {throw migration("不支持向未配置地址同步；请使用技术合约中的受控接收端");}
    private Map<String,Object> readableProduct(String id) {
        UUID product=uuid(id);var row=one("SELECT v.dataset_id,v.version_id FROM ds_product_version v JOIN ds_product p ON p.product_id=v.product_id WHERE p.product_id=? ORDER BY v.revision DESC LIMIT 1",product);
        access.requireVersionRead((UUID)row.get("dataset_id"),(UUID)row.get("version_id"),null);return row;
    }
    public Map<String,Object> productScope(UUID product,UUID productVersion){
        var row=one("SELECT v.dataset_id AS \"datasetId\",v.version_id AS \"versionId\",v.index_version_id AS \"indexVersionId\",v.manifest_digest AS \"manifestDigest\",v.file_count AS \"fileCount\",v.revision FROM ds_product_version v WHERE v.product_id=? AND v.product_version_id=?",product,productVersion);
        access.requireVersionRead((UUID)row.get("datasetId"),(UUID)row.get("versionId"),(UUID)row.get("indexVersionId"));
        var summary=manifestSummary((UUID)row.get("datasetId"),(UUID)row.get("versionId"));
        if(!row.get("manifestDigest").toString().trim().equals(summary.get("manifestDigest")))throw error(HttpStatus.CONFLICT,"原始清单与产品冻结摘要不一致，请核对来源状态");
        row.put("files",summary.get("files"));return row;
    }
    public List<Map<String,Object>> productSamples(String id,int limit) {
        var product=readableProduct(id);
        @SuppressWarnings("unchecked") var list=(List<Map<String,Object>>)manifestSummary((UUID)product.get("dataset_id"),(UUID)product.get("version_id")).get("files");
        return list.stream().limit(Math.max(0,Math.min(100,limit))).toList();
    }
    public List<Map<String,Object>> searchProductContent(String id,String query,int limit) {readableProduct(id);throw migration("内容检索请进入绑定产品版本的多模态检索页面，旧文件名搜索不冒充全文检索");}
    public DataSpaceFlowService.DataSpaceFile openProductFile(String id,String name) throws Exception {
        var product=readableProduct(id);UUID dataset=(UUID)product.get("dataset_id"),version=(UUID)product.get("version_id");
        var scope=one("SELECT d.raw_repo_id,v.raw_commit_id FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id WHERE d.dataset_id=? AND v.version_id=?",dataset,version);
        var matches=files.listFilesByRepoAndCommit((UUID)scope.get("raw_repo_id"),(UUID)scope.get("raw_commit_id")).stream().filter(f->f.getLogicalPath().equals(name)||f.getLogicalPath().equals("/"+name)).toList();
        if(matches.size()!=1)throw error(HttpStatus.NOT_FOUND,"请提供该冻结版本中完整且唯一的逻辑路径");
        var stream=files.downloadContent((UUID)scope.get("raw_repo_id"),(UUID)scope.get("raw_commit_id"),matches.get(0).getLogicalPath());
        return new DataSpaceFlowService.DataSpaceFile(name,stream.contentType(),stream.size(),stream.inputStream());
    }
}
