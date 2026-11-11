package com.fwdrobo.sirius.security;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;

/** Trusted deployment entry. Its stdout is a private credential pipe, never an operator log. */
public final class QualityWorkerCredentialMain {
 private static String env(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException();return value;}
 public static void main(String[] args){
  try{
   if(args.length!=0)throw new IllegalArgumentException();
   var json=new ObjectMapper();byte[] input=System.in.readNBytes(4097);if(input.length>4096)throw new IllegalArgumentException();
   var request=json.readTree(input);if(!request.isObject()||request.size()!=1||!"IssueQualityWorkers".equals(request.path("action").asText()))throw new IllegalArgumentException();
   var data=new DriverManagerDataSource(env("SPRING_DATASOURCE_URL"),env("SPRING_DATASOURCE_USERNAME"),env("SPRING_DATASOURCE_PASSWORD"));
   var db=new JdbcTemplate(data);var auth=new AuthorizationService(db);var jwt=new JwtService(env("MDS_JWT_SIGNING_SECRET"),120,120);
   var ids=db.queryForList("""
    SELECT s.service_subject_id FROM ds_service_subject s JOIN org o ON o.org_id=s.org_id
    WHERE s.purpose='QUALITY_CHECK' AND s.active AND s.closed_at IS NULL AND o.org_status=1
     AND (s.valid_until IS NULL OR clock_timestamp()<s.valid_until)
     AND EXISTS(SELECT 1 FROM ds_resource_grant g WHERE g.subject_kind='SERVICE' AND g.subject_id=s.service_subject_id AND g.state='ACTIVE'
       AND g.valid_from<=clock_timestamp() AND (g.valid_to IS NULL OR clock_timestamp()<g.valid_to)
       AND 'dataset.quality.run'=ANY(g.operation_codes) AND 'GENERAL_READ'=ANY(g.purpose_codes))
    ORDER BY s.service_subject_id LIMIT 65
    """,java.util.UUID.class);
   if(ids.size()>64)throw new IllegalStateException();
   var workers=new ArrayList<Map<String,Object>>();
   for(var id:ids){var actor=auth.serviceActorFor(id);auth.requireOperation(actor,QualityJobAuthority.OP);
    workers.add(Map.of("serviceSubjectId",id.toString(),"expiresIn",120,"token",jwt.generateQualityWorkerToken(actor,120)));}
   System.out.println(json.writeValueAsString(Map.of("success",true,"workers",workers)));
  }catch(Exception error){System.err.println("QUALITY_WORKER_CREDENTIAL_ISSUANCE_UNAVAILABLE");System.exit(1);}
 }
}
