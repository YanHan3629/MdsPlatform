package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.OffsetDateTime;
import java.util.*;

/** Append-only minimal authorization metadata. Denials survive caller rollback. */
@Service
public class AuthorizationAuditService {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    private static final Set<String> FIELDS=Set.of("username","userId","orgId","typeIds","operations","mappingId","grantId","datasetId","versionId","indexVersionId","roleId","viewId","version","state","accountState","qualification","validFrom","validTo","purposeCodes","count","reasonCode","permissionVersion","typeVersion","active","entityId","enterpriseName","industryKind","identityKind","catalogRevisionId","sourceDatasetId","sourceVersionId","sourceSha256","mappingVersion","orgVersion","reviewId","subjectKind","subjectId","ownerOrgId","granteeOrgId","scopeKind","purpose","contractId","contractRevision");
    private static final Set<String> CONTEXT_FIELDS=Set.of("serviceSubjectId","reportId","runId","jobId",
            "viewAuthorizationVersion","action","result");
    private static final Set<String> TYPE_FIELDS=Set.of("typeCode","typeName","typeScope","readOnly","replacementTypeId");
    public record Entry(UUID eventId, OffsetDateTime occurredAt, UUID actorUserId, UUID actorOrgId,
                        UUID actorServiceId, String operation, String targetKind, String targetId,
                        String outcome, String reasonCode, String requestId,
                        JsonNode scope, JsonNode beforeValue, JsonNode afterValue) {}
    public record Page(List<Entry> items, UUID nextCursor) {}
    public AuthorizationAuditService(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    private Map<String,Object> clean(Map<String,?> input) {
        var out=new LinkedHashMap<String,Object>();
        if(input!=null)for(var entry:input.entrySet())if(FIELDS.contains(entry.getKey())||TYPE_FIELDS.contains(entry.getKey()))out.put(entry.getKey(),entry.getValue());
        return out;
    }
    private String json(Map<String,?> input) {
        try{return mapper.writeValueAsString(clean(input));}catch(Exception error){throw new IllegalStateException("Audit metadata cannot be encoded");}
    }
    @Transactional
    public void record(UUID actor,UUID org,String operation,String targetKind,String targetId,String outcome,String reason,
                       Map<String,?> scope,Map<String,?> before,Map<String,?> after) {
        String requestId=UUID.randomUUID().toString();
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            Object existing=attrs.getRequest().getAttribute("ds.requestId");
            if(existing instanceof String value&&value.matches("[A-Za-z0-9._-]{1,128}"))requestId=value;
            else attrs.getRequest().setAttribute("ds.requestId",requestId);
        }
        jdbc.update("""
            INSERT INTO ds_authorization_audit(actor_user_id,actor_org_id,operation_code,target_kind,target_id,
                outcome,request_id,scope,before_value,after_value,reason_code)
            VALUES(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?)
            """,actor,org,operation,targetKind,targetId,outcome,requestId,json(scope),before==null?null:json(before),after==null?null:json(after),reason);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void denied(UUID actor,UUID org,String operation,String targetKind,String targetId,String reason,Map<String,?> scope) {
        record(actor,org,operation,targetKind,targetId,"DENY",reason,scope,null,null);
    }

    public Page list(String targetId, UUID before, int limit) {
        var rows=jdbc.query("""
            SELECT event_id,created_at,actor_user_id,actor_org_id,actor_service_id,operation_code,target_kind,
                   target_id,outcome,reason_code,request_id,scope,before_value,after_value
            FROM ds_authorization_audit
            WHERE (?::text IS NULL OR target_id=?)
              AND (?::uuid IS NULL OR (created_at,event_id) <
                  (SELECT created_at,event_id FROM ds_authorization_audit WHERE event_id=?))
            ORDER BY created_at DESC,event_id DESC LIMIT ?
            """,(rs,n)->new Entry(rs.getObject("event_id",UUID.class),rs.getObject("created_at",OffsetDateTime.class),
                rs.getObject("actor_user_id",UUID.class),rs.getObject("actor_org_id",UUID.class),
                rs.getObject("actor_service_id",UUID.class),rs.getString("operation_code"),rs.getString("target_kind"),
                rs.getString("target_id"),rs.getString("outcome"),rs.getString("reason_code"),rs.getString("request_id"),
                auditDetails(rs.getString("scope")),auditDetails(rs.getString("before_value")),auditDetails(rs.getString("after_value"))),
                targetId,targetId,before,before,limit+1);
        if(rows.size()<=limit)return new Page(List.copyOf(rows),null);
        return new Page(List.copyOf(rows.subList(0,limit)),rows.get(limit-1).eventId());
    }

    private JsonNode auditDetails(String stored) {
        if(stored==null)return null;
        try {
            var result=mapper.createObjectNode();
            var metadata=mapper.readTree(stored);
            if(!metadata.isObject())return result;
            metadata.fields().forEachRemaining(entry->{
                String key=entry.getKey();
                if(!FIELDS.contains(key)&&!TYPE_FIELDS.contains(key)&&!CONTEXT_FIELDS.contains(key))return;
                JsonNode value=entry.getValue();
                if(value.isValueNode())result.set(key,value);
                else if(value.isArray()){
                    var flat=result.putArray(key);
                    for(JsonNode item:value)if(item.isValueNode())flat.add(item);
                }
            });
            return result;
        }catch(JsonProcessingException invalid){
            throw new IllegalStateException("Audit metadata cannot be read",invalid);
        }
    }
}
