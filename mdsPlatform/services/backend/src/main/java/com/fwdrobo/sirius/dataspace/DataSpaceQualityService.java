package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.service.DatasetAccessService;
import com.fwdrobo.sirius.service.FileService;
import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.*;
import static com.fwdrobo.sirius.dataspace.PersistentDataSpaceService.map;

/** Authenticated user requests and leased service-to-service quality work. */
@Service
public class DataSpaceQualityService {
    public static final String RULES_VERSION="snapshot-quality-2026-10-20.6";
    private final JdbcTemplate jdbc;
    private final DatasetAccessService access;
    private final PersistentDataSpaceService business;
    private final FileService files;
    private final ObjectMapper json;
    public DataSpaceQualityService(JdbcTemplate jdbc,DatasetAccessService access,PersistentDataSpaceService business,FileService files,ObjectMapper json) {
        this.jdbc=jdbc;this.access=access;this.business=business;this.files=files;this.json=json;
    }
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("规则或报告JSON无效",e);}}
    private Object decode(Object value){try{return json.readValue(value.toString(),Object.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private UUID uuid(Object value){try{return UUID.fromString(String.valueOf(value));}catch(Exception e){throw error(HttpStatus.BAD_REQUEST,"需要有效的UUID");}}
    @Transactional
    public Map<String,Object> request(UUID source,Map<String,Object> body) {
        var sources=jdbc.queryForList("SELECT dataset_id FROM ds_source WHERE source_id=? AND org_id=?",source,access.actorOrg());
        if(sources.isEmpty())throw error(HttpStatus.FORBIDDEN,"数据源不属于当前组织");
        UUID dataset=(UUID)sources.get(0).get("dataset_id");access.requireDatasetWrite(dataset);
        UUID version=body.containsKey("versionId")?uuid(body.get("versionId")):business.listDatasets().stream().filter(d->dataset.equals(d.get("datasetId"))).map(d->(UUID)d.get("versionId")).findFirst().orElseThrow();
        var rows=jdbc.queryForList("SELECT v.raw_commit_id FROM mm_dataset_version v JOIN artifact_commit c ON c.commit_id=v.raw_commit_id WHERE v.dataset_id=? AND v.version_id=? AND c.commit_status='PUBLISHED'",dataset,version);
        if(rows.isEmpty())throw error(HttpStatus.CONFLICT,"质量检查需要该数据集已发布的原始快照");
        String key=String.valueOf(body.getOrDefault("idempotencyKey",version+":"+RULES_VERSION));
        if(key.isBlank()||key.length()>128)throw error(HttpStatus.BAD_REQUEST,"质量任务幂等键长度需为1至128");
        Object config=body.getOrDefault("rules",Map.of());String encoded=encode(config);
        if(!(config instanceof Map)||encoded.length()>262144)throw error(HttpStatus.BAD_REQUEST,"规则应为JSON对象且不超过256KB");
        var summary=business.manifestSummary(dataset,version);
        validateRules((Map<?,?>)config,summary);
        if(((Number)summary.get("fileCount")).longValue()==0)throw error(HttpStatus.CONFLICT,"空快照不能完成质量验收");
        UUID report=UUID.randomUUID();
        int inserted=jdbc.update("""
            INSERT INTO ds_quality_report(report_id,dataset_id,version_id,rules_version,manifest_digest,status,created_by,rule_config,request_key)
            VALUES (?,?,?,?,?,'RUNNING',?,?::jsonb,?) ON CONFLICT(dataset_id,request_key) WHERE request_key IS NOT NULL DO NOTHING
            """,report,dataset,version,RULES_VERSION,summary.get("manifestDigest"),SecurityUtils.getUserId(),encoded,key);
        var existing=jdbc.queryForMap("SELECT report_id,version_id,rule_config FROM ds_quality_report WHERE dataset_id=? AND request_key=?",dataset,key);
        if(!version.equals(existing.get("version_id"))||!Objects.equals(decode(existing.get("rule_config")),config))throw error(HttpStatus.CONFLICT,"相同幂等键对应不同版本或规则，请使用新的运行键");
        report=(UUID)existing.get("report_id");
        if(inserted>0){
            jdbc.update("INSERT INTO ds_quality_job(report_id,status) VALUES (?,'QUEUED')",report);
            jdbc.update("INSERT INTO ds_audit_event(org_id,actor_user_id,action,object_type,object_id,details) VALUES (?,?,'REQUEST_QUALITY','QUALITY_REPORT',?,?::jsonb)",access.actorOrg(),SecurityUtils.getUserId(),report,encode(map("datasetId",dataset,"versionId",version,"rulesVersion",RULES_VERSION)));
        }
        return get(report);
    }
    private void validateRules(Map<?,?> config,Map<String,Object> summary){
        Object declarations=config.containsKey("files")?config.get("files"):Map.of();
        if(!(declarations instanceof Map<?,?> declarationsMap))throw error(HttpStatus.BAD_REQUEST,"rules.files需要以文件路径为键的对象");
        Set<String> available=new HashSet<>(),seen=new HashSet<>();
        for(Object raw:(List<?>)summary.get("files"))available.add(((Map<?,?>)raw).get("logicalPath").toString());
        for(var entry:declarationsMap.entrySet()){
            if(!(entry.getKey() instanceof String source))throw error(HttpStatus.BAD_REQUEST,"质量规则路径必须是字符串");
            String path=com.fwdrobo.sirius.util.PathUtils.normalizePath(source.startsWith("/")?source:"/"+source);
            if(!available.contains(path)||!seen.add(path))throw error(HttpStatus.BAD_REQUEST,"质量规则应唯一对应当前快照内的文件："+path);
            if(!(entry.getValue() instanceof Map<?,?> rule))throw error(HttpStatus.BAD_REQUEST,"每个文件的质量规则需要JSON对象");
            for(String key:List.of("timezone","geometryUnit"))if(rule.containsKey(key)&&!(rule.get(key) instanceof String))throw error(HttpStatus.BAD_REQUEST,key+"需要字符串");
            Object fields=rule.get("fields");
            if(fields instanceof Map<?,?> fm){for(var field:fm.entrySet()){if(!(field.getKey() instanceof String name)||name.isBlank())throw error(HttpStatus.BAD_REQUEST,"字段名需要非空字符串");validateField(field.getValue());}}
            else if(fields instanceof List<?> fl){for(Object raw:fl){validateField(raw);var field=(Map<?,?>)raw;if(!(field.get("field") instanceof String name)||name.isBlank())throw error(HttpStatus.BAD_REQUEST,"字段规则列表需要field名称");}}
            else if(rule.containsKey("fields"))throw error(HttpStatus.BAD_REQUEST,"fields需要字段对象或规则列表");
            Object primary=rule.get("primaryKey");
            if(primary!=null&&!(primary instanceof String)&&!(primary instanceof List<?> list&&list.stream().allMatch(v->v instanceof String s&&!s.isBlank())))throw error(HttpStatus.BAD_REQUEST,"primaryKey需要字段名或字段名列表");
            Object references=rule.get("references");
            if(references!=null){if(!(references instanceof List<?> refs))throw error(HttpStatus.BAD_REQUEST,"references需要列表");for(Object raw:refs){if(!(raw instanceof Map<?,?> ref))throw error(HttpStatus.BAD_REQUEST,"引用规则需要对象");for(String field:List.of("field","path","targetField"))if(!(ref.get(field) instanceof String s)||s.isBlank())throw error(HttpStatus.BAD_REQUEST,"引用规则缺少"+field);if(ref.get("nullable")!=null&&!(ref.get("nullable") instanceof Boolean))throw error(HttpStatus.BAD_REQUEST,"引用nullable需要布尔值");}}
            Object units=rule.get("additionalUnits");
            if(units!=null){if(!(units instanceof Map<?,?> unitMap))throw error(HttpStatus.BAD_REQUEST,"自定义单位需要单位名称与含义的映射");for(var unit:unitMap.entrySet())if(!(unit.getKey() instanceof String name)||name.isBlank()||!(unit.getValue() instanceof String meaning)||meaning.isBlank())throw error(HttpStatus.BAD_REQUEST,"自定义单位必须有非空名称和含义");}
        }
    }
    private void validateField(Object raw){
        if(!(raw instanceof Map<?,?> field))throw error(HttpStatus.BAD_REQUEST,"字段规则需要对象");
        if(field.get("nullable")!=null&&!(field.get("nullable") instanceof Boolean))throw error(HttpStatus.BAD_REQUEST,"nullable必须是真正的布尔值，不能使用字符串");
        for(String key:List.of("type","unit","timezone","sheet","nullRule","null_rule"))if(field.get(key)!=null&&!(field.get(key) instanceof String))throw error(HttpStatus.BAD_REQUEST,key+"需要字符串");
    }
    public List<Map<String,Object>> list(UUID dataset,UUID version) {
        access.requireVersionRead(dataset,version,null);
        return jdbc.queryForList("SELECT report_id FROM ds_quality_report WHERE dataset_id=? AND version_id=? ORDER BY created_at DESC",UUID.class,dataset,version).stream().map(this::get).toList();
    }
    public Map<String,Object> get(UUID report){
        var rows=jdbc.queryForList("""
            SELECT q.report_id AS "reportId",q.dataset_id AS "datasetId",q.version_id AS "versionId",q.rules_version AS "rulesVersion",
              q.manifest_digest AS "manifestDigest",q.status,q.measured_count AS "measuredCount",q.passed_count AS "passedCount",q.coverage,q.checks,q.score,
              q.blocking_count AS "blockingCount",q.rule_config AS rules,q.created_at AS "createdAt",q.completed_at AS "completedAt",
              j.status AS "jobStatus",j.attempt_count AS "attemptCount",j.last_error AS error,j.lease_until AS "leaseUntil"
            FROM ds_quality_report q JOIN ds_quality_job j ON j.report_id=q.report_id WHERE q.report_id=?
            """,report);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"质量报告不存在");
        var row=rows.get(0);access.requireVersionRead((UUID)row.get("datasetId"),(UUID)row.get("versionId"),null);
        row.replaceAll((k,v)->v instanceof org.postgresql.util.PGobject?decode(v):v instanceof Timestamp t?t.toInstant().atOffset(ZoneOffset.ofHours(8)).toString():v);
        return row;
    }
    @Transactional
    public Map<String,Object> claim(){
        // Reclaim expired attempts. The report remains immutable after completion.
        jdbc.update("""
            UPDATE ds_quality_report q SET status='FAILED',blocking_count=1,completed_at=now(),coverage='{"worker":"failed after three attempts"}'::jsonb
            FROM ds_quality_job j WHERE j.report_id=q.report_id AND j.status='RUNNING' AND j.lease_until<now() AND j.attempt_count>=3 AND q.completed_at IS NULL
            """);
        jdbc.update("UPDATE ds_quality_job SET status='FAILED',last_error='质量执行租约过期，已达到三次上限',updated_at=now() WHERE status='RUNNING' AND lease_until<now() AND attempt_count>=3");
        var jobs=jdbc.queryForList("""
            SELECT j.report_id FROM ds_quality_job j WHERE (j.status='QUEUED' AND j.next_attempt_at<=now()) OR
              (j.status='RUNNING' AND j.lease_until<now() AND j.attempt_count<3) ORDER BY j.updated_at FOR UPDATE SKIP LOCKED LIMIT 1
            """);
        if(jobs.isEmpty())return Map.of();UUID id=(UUID)jobs.get(0).get("report_id"),lease=UUID.randomUUID();
        jdbc.update("UPDATE ds_quality_job SET status='RUNNING',attempt_count=attempt_count+1,lease_token=?,lease_until=now()+interval '120 seconds',updated_at=now() WHERE report_id=?",lease,id);
        var scope=jdbc.queryForMap("""
            SELECT q.dataset_id,q.version_id,q.manifest_digest,q.rule_config,q.rules_version,d.raw_repo_id,v.raw_commit_id FROM ds_quality_report q
            JOIN mm_dataset d ON d.dataset_id=q.dataset_id JOIN mm_dataset_version v ON v.version_id=q.version_id WHERE q.report_id=?
            """,id);
        var identities=new ArrayList<Map<String,Object>>();
        for(var file:files.listFilesByRepoAndCommit((UUID)scope.get("raw_repo_id"),(UUID)scope.get("raw_commit_id")))
            identities.add(map("logicalPath",file.getLogicalPath(),"sizeBytes",file.getSizeBytes(),"sha256",file.getSha256()));
        identities.sort(Comparator.comparing(f->f.get("logicalPath").toString()));
        return map("reportId",id,"leaseToken",lease,"datasetId",scope.get("dataset_id"),"versionId",scope.get("version_id"),"manifestDigest",scope.get("manifest_digest"),"rulesVersion",scope.get("rules_version"),"rules",decode(scope.get("rule_config")),"files",identities);
    }
    private Map<String,Object> lease(UUID id,UUID token){
        var rows=jdbc.queryForList("SELECT report_id FROM ds_quality_job WHERE report_id=? AND lease_token=? AND status='RUNNING' AND lease_until>now()",id,token);
        if(rows.isEmpty())throw error(HttpStatus.CONFLICT,"质量任务租约已过期或已由其他工作器接管");return rows.get(0);
    }
    public com.fwdrobo.sirius.dto.file.FileStream content(UUID id,UUID token,String path)throws Exception{
        lease(id,token);var scope=jdbc.queryForMap("SELECT d.raw_repo_id,v.raw_commit_id FROM ds_quality_report q JOIN mm_dataset d ON d.dataset_id=q.dataset_id JOIN mm_dataset_version v ON v.version_id=q.version_id WHERE q.report_id=?",id);
        return files.downloadContent((UUID)scope.get("raw_repo_id"),(UUID)scope.get("raw_commit_id"),path);
    }
    public void heartbeat(UUID id,UUID token){
        int count=jdbc.update("UPDATE ds_quality_job SET lease_until=now()+interval '120 seconds',updated_at=now() WHERE report_id=? AND lease_token=? AND status='RUNNING' AND lease_until>now()",id,token);
        if(count!=1)throw error(HttpStatus.CONFLICT,"质量任务租约失效");
    }
    @Transactional
    public Map<String,Object> complete(UUID id,UUID token,Map<String,Object> body){
        lease(id,token);
        var report=jdbc.queryForMap("SELECT q.* FROM ds_quality_report q JOIN ds_quality_job j ON j.report_id=q.report_id WHERE q.report_id=? FOR UPDATE OF q,j",id);
        lease(id,token);
        if(!report.get("rules_version").equals(body.get("rulesVersion"))||!report.get("manifest_digest").toString().trim().equals(body.get("manifestDigest")))throw error(HttpStatus.CONFLICT,"质量结果不匹配冻结快照或规则版本");
        if(!(body.get("checks") instanceof List<?> checks)||checks.isEmpty())throw error(HttpStatus.BAD_REQUEST,"必须提供逐项测量证据");
        var scope=jdbc.queryForMap("SELECT d.raw_repo_id,v.raw_commit_id FROM ds_quality_report q JOIN mm_dataset d ON d.dataset_id=q.dataset_id JOIN mm_dataset_version v ON v.version_id=q.version_id WHERE q.report_id=?",id);
        Set<String> expectedFiles=new HashSet<>();for(var file:files.listFilesByRepoAndCommit((UUID)scope.get("raw_repo_id"),(UUID)scope.get("raw_commit_id")))expectedFiles.add(file.getLogicalPath());
        Set<String> measuredFiles=new HashSet<>();long digestChecks=0;
        long measured=0,passed=0,blocking=0,unassessed=0,na=0;
        for(Object raw:checks){
            if(!(raw instanceof Map<?,?> c)||!(c.get("status") instanceof String state)||!Set.of("PASS","FAIL","NOT_ASSESSED","NOT_APPLICABLE").contains(state))throw error(HttpStatus.BAD_REQUEST,"质量检查状态无效");
            if("file_integrity".equals(c.get("rule"))&&(!Boolean.TRUE.equals(c.get("critical"))||!measuredFiles.add(String.valueOf(c.get("logicalPath")))))throw error(HttpStatus.BAD_REQUEST,"文件校验范围重复或未标记关键检查");
            if("manifest_digest".equals(c.get("rule")))digestChecks++;
            long n=c.get("denominator") instanceof Number num?num.longValue():0;
            long p=c.get("passed") instanceof Number num?num.longValue():0;
            if(n<0||p<0||p>n||("PASS".equals(state)&&p!=n)||("FAIL".equals(state)&&p>=n))throw error(HttpStatus.BAD_REQUEST,"质量检查分母或通过数无效");
            if(Set.of("PASS","FAIL").contains(state)){measured+=n;passed+=p;}
            if("NOT_ASSESSED".equals(state))unassessed++;
            if("NOT_APPLICABLE".equals(state))na++;
            if(Boolean.TRUE.equals(c.get("critical"))&&!Set.of("PASS","NOT_APPLICABLE").contains(state))blocking++;
        }
        if(!measuredFiles.equals(expectedFiles)||digestChecks!=1)throw error(HttpStatus.BAD_REQUEST,"质量报告必须覆盖完整快照的全部文件及唯一清单校验");
        if(measured==0)throw error(HttpStatus.BAD_REQUEST,"没有实际测量结果");
        String state=blocking>0||passed<measured?"FAILED":unassessed>0?"PARTIAL":"PASSED";
        double score=100.0*passed/measured;
        Object coverage=map("measuredAssertions",measured,"passedAssertions",passed,"unassessedRuleScopes",unassessed,"notApplicableRuleScopes",na,
            "formula","100 * passedAssertions / measuredAssertions; NOT_ASSESSED is never PASS; publish also requires zero critical blockers",
            "details",body.getOrDefault("coverage",Map.of()));
        jdbc.update("UPDATE ds_quality_report SET status=?,measured_count=?,passed_count=?,blocking_count=?,coverage=?::jsonb,checks=?::jsonb,score=?,completed_at=now() WHERE report_id=?",state,measured,passed,blocking,encode(coverage),encode(checks),score,id);
        jdbc.update("UPDATE ds_quality_job SET status='COMPLETED',lease_until=null,updated_at=now() WHERE report_id=? AND lease_token=?",id,token);
        jdbc.update("INSERT INTO ds_audit_event(org_id,action,object_type,object_id,details) SELECT d.org_id,'COMPLETE_QUALITY','QUALITY_REPORT',q.report_id,?::jsonb FROM ds_quality_report q JOIN mm_dataset d ON d.dataset_id=q.dataset_id WHERE q.report_id=?",encode(map("status",state,"blockingCount",blocking,"score",score)),id);
        return map("reportId",id,"status",state,"blockingCount",blocking,"score",score);
    }
    @Transactional
    public void fail(UUID id,UUID token){
        lease(id,token);int attempt=jdbc.queryForObject("SELECT attempt_count FROM ds_quality_job WHERE report_id=?",Integer.class,id);
        if(attempt>=3){
            jdbc.update("UPDATE ds_quality_report SET status='FAILED',blocking_count=1,completed_at=now(),coverage=?::jsonb WHERE report_id=?",encode(map("worker","执行失败，未测量结果不计通过")),id);
            jdbc.update("UPDATE ds_quality_job SET status='FAILED',last_error='质量工作器执行失败，三次尝试已用尽',lease_until=null,updated_at=now() WHERE report_id=?",id);
        }else jdbc.update("UPDATE ds_quality_job SET status='QUEUED',last_error='质量工作器执行失败，等待重试',lease_until=null,next_attempt_at=now()+interval '30 seconds',updated_at=now() WHERE report_id=?",id);
    }
}
