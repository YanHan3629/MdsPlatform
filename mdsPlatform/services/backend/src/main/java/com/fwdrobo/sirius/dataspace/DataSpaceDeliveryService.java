package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.service.DatasetAccessService;
import com.fwdrobo.sirius.service.FileService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;
import static com.fwdrobo.sirius.dataspace.PersistentDataSpaceService.map;

/** Durable scheduling, bounded retries, independent receipt verification and recovery. */
@Service
public class DataSpaceDeliveryService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final DataSpaceContractService contracts;
    private final PersistentDataSpaceService business;private final FileService files;private final DataSpaceReceiverClient receiver;
    private final ObjectMapper json;private final DatasetAccessService access;private final long receiptCommitDelay;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"dataspace-delivery");t.setDaemon(true);return t;});
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"dataspace-delivery-lease");t.setDaemon(true);return t;});
    public DataSpaceDeliveryService(JdbcTemplate jdbc,TransactionTemplate tx,DataSpaceContractService contracts,PersistentDataSpaceService business,FileService files,DataSpaceReceiverClient receiver,ObjectMapper json,DatasetAccessService access,@Value("${ds.delivery.receipt-commit-delay-ms:0}")long delay){this.jdbc=jdbc;this.tx=tx;this.contracts=contracts;this.business=business;this.files=files;this.receiver=receiver;this.json=json;this.access=access;this.receiptCommitDelay=Math.max(0,Math.min(60000,delay));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Map<String,Object> decode(Object value){try{return json.readValue(value.toString(),new TypeReference<>(){});}catch(Exception e){throw new IllegalStateException(e);}}
    @Scheduled(initialDelay=12000,fixedDelay=5000)
    public void tick(){
        if(!busy.compareAndSet(false,true))return;
        worker.submit(()->{try{
            var task=claim();if(task!=null)execute(task);
            var historical=claimReceiptReconciliation();if(historical!=null)reconcileReceipt(historical);
        }catch(Exception e){System.err.println("dataspace delivery scheduler: "+e.getClass().getSimpleName());}finally{busy.set(false);}});
    }
    private Map<String,Object> claim(){return tx.execute(status->{
        // Reads never call this scheduler. Expiry/revocation also stop abandoned tasks.
        jdbc.update("""
            UPDATE ds_delivery_task t SET status='CANCELLED',last_error='授权已撤销、到期或合约修订已变更',updated_at=now()
            FROM ds_contract c WHERE c.contract_id=t.contract_id AND t.status IN ('PENDING','RUNNING','BLOCKED','RETRY','FAILED')
              AND (c.status IN ('REVOKED','CANCELLED') OR c.revoked_at IS NOT NULL OR c.valid_to<=now() OR c.revision<>t.contract_revision)
            """);
        jdbc.update("UPDATE ds_delivery_attempt a SET status='FAILED',error_message='执行租约过期，结果由下一次尝试对账',finished_at=now() FROM ds_delivery_task t WHERE t.task_id=a.task_id AND a.status='RUNNING' AND t.lease_until<now()");
        jdbc.update("UPDATE ds_delivery_task SET status='FAILED',last_error='租约过期且自动尝试已用尽；可手动重试对账',lease_token=null,lease_until=null,updated_at=now() WHERE status='RUNNING' AND lease_until<now() AND attempt_count>=max_attempts");
        var due=jdbc.queryForList("""
            SELECT t.* FROM ds_delivery_task t JOIN ds_contract c ON c.contract_id=t.contract_id
            WHERE c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.revision=t.contract_revision AND c.valid_from<=now() AND c.valid_to>now() AND c.scheduled_at<=now()
              AND t.attempt_count<t.max_attempts AND ((t.status IN ('PENDING','RETRY','BLOCKED') AND t.next_attempt_at<=now()) OR (t.status='RUNNING' AND t.lease_until<now()))
            ORDER BY t.next_attempt_at,t.created_at FOR UPDATE OF t SKIP LOCKED LIMIT 1
            """);
        if(due.isEmpty())return null;var task=due.get(0);UUID token=UUID.randomUUID();int attempt=((Number)task.get("attempt_count")).intValue()+1;
        jdbc.update("UPDATE ds_delivery_task SET status='RUNNING',attempt_count=?,lease_token=?,lease_until=now()+interval '120 seconds',updated_at=now() WHERE task_id=?",attempt,token,task.get("task_id"));
        jdbc.update("INSERT INTO ds_delivery_attempt(task_id,attempt_number,status) VALUES (?,?,'RUNNING')",task.get("task_id"),attempt);
        task.put("lease_token",token);task.put("attempt_count",attempt);return task;
    });}
    private boolean leased(Map<String,Object> task){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_delivery_task WHERE task_id=? AND lease_token=? AND lease_until>now() AND status='RUNNING')",Boolean.class,task.get("task_id"),task.get("lease_token")));}
    private void live(Map<String,Object> task){
        if(!leased(task)||!contracts.isAvailable((UUID)task.get("contract_id"))||!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ds_contract WHERE contract_id=? AND revision=? AND status='CONFIRMED' AND valid_from<=now() AND now()<valid_to AND revoked_at IS NULL)",Boolean.class,task.get("contract_id"),task.get("contract_revision"))))throw new IllegalStateException("delivery lease or live grant is no longer valid");
    }
    private Map<String,Object> manifest(Map<String,Object> task)throws Exception{
        if(task.get("package_manifest")!=null)return decode(task.get("package_manifest"));
        var c=jdbc.queryForMap("SELECT c.*,v.dataset_id,v.version_id,v.index_version_id,v.raw_commit_id,v.manifest_digest,v.product_version_id FROM ds_contract c JOIN ds_product_version v ON v.product_version_id=c.product_version_id WHERE c.contract_id=?",task.get("contract_id"));
        var summary=business.internalManifest((UUID)c.get("dataset_id"),(UUID)c.get("version_id"));
        if(!summary.get("manifestDigest").equals(c.get("manifest_digest").toString().trim()))throw new IllegalStateException("frozen raw manifest no longer matches product version");
        if(((Number)summary.get("sizeBytes")).longValue()>1024L*1024*1024||((Number)summary.get("fileCount")).longValue()>20000)throw new IllegalStateException("snapshot exceeds configured local receiver limits (1GiB, 20000 files)");
        var manifest=map("formatVersion",1,"deliveryKey",task.get("delivery_key"),"contractId",task.get("contract_id"),"contractRevision",task.get("contract_revision"),"productVersionId",c.get("product_version_id"),"consumerOrgId",c.get("consumer_org_id"),"endpointId",c.get("endpoint_id"),
            "scopeType","FULL_VERSION","datasetId",c.get("dataset_id"),"versionId",c.get("version_id"),"indexVersionId",c.get("index_version_id"),"rawCommitId",c.get("raw_commit_id"),"manifestDigest",summary.get("manifestDigest"),"purpose",c.get("purpose"),
            "sourceNotice","Files are read from the published raw snapshot; source units, null semantics and provenance remain in the original files. Index weights, credentials and internal configuration are excluded.","files",summary.get("files"));
        String digest=receiver.digest(manifest);int changed=jdbc.update("UPDATE ds_delivery_task SET package_manifest=?::jsonb,package_manifest_digest=?,manifest_digest=?,updated_at=now() WHERE task_id=? AND lease_token=? AND status='RUNNING' AND lease_until>now() AND package_manifest IS NULL",encode(manifest),digest,summary.get("manifestDigest"),task.get("task_id"),task.get("lease_token"));
        if(changed!=1)throw new IllegalStateException("delivery manifest lease lost");
        // JSONB has its own key order. Canonicalize using a sorted-key encoding for receipts.
        return manifest;
    }
    private Path archive(Map<String,Object> task,Map<String,Object> manifest)throws Exception{
        UUID dataset=UUID.fromString(manifest.get("datasetId").toString());UUID repo=jdbc.queryForObject("SELECT raw_repo_id FROM mm_dataset WHERE dataset_id=?",UUID.class,dataset);UUID commit=UUID.fromString(manifest.get("rawCommitId").toString());
        Path path=Files.createTempFile("dataspace-delivery-",".zip");boolean complete=false;
        try(var zip=new ZipOutputStream(Files.newOutputStream(path))){
            ZipEntry metadata=new ZipEntry("manifest.json");metadata.setTime(0);zip.putNextEntry(metadata);zip.write(json.writeValueAsBytes(manifest));zip.closeEntry();
            @SuppressWarnings("unchecked") var identities=(List<Map<String,Object>>)manifest.get("files");
            for(var file:identities){
                live(task);String logical=file.get("logicalPath").toString();ZipEntry entry=new ZipEntry("files"+logical);entry.setTime(0);zip.putNextEntry(entry);
                var source=files.downloadContent(repo,commit,logical);MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0,expected=((Number)file.get("sizeBytes")).longValue();
                try(var in=source.inputStream()){byte[] bytes=new byte[1024*1024];for(int n;(n=in.read(bytes))!=-1;){count+=n;if(count>expected)throw new IllegalStateException("source size changed within published snapshot");zip.write(bytes,0,n);digest.update(bytes,0,n);}}
                zip.closeEntry();if(count!=expected||!HexFormat.of().formatHex(digest.digest()).equals(file.get("sha256")))throw new IllegalStateException("source content does not match published SHA-256");
            }
            complete=true;return path;
        }catch(Exception error){Files.deleteIfExists(path);throw error;}
    }
    private void execute(Map<String,Object> task){
        ScheduledFuture<?> renew=heartbeat.scheduleAtFixedRate(()->{try{jdbc.update("UPDATE ds_delivery_task SET lease_until=now()+interval '120 seconds',updated_at=now() WHERE task_id=? AND lease_token=? AND status='RUNNING' AND lease_until>now()",task.get("task_id"),task.get("lease_token"));}catch(Exception ignored){}},20,20,TimeUnit.SECONDS);
        try{
            live(task);var manifest=manifest(task);UUID key=(UUID)task.get("delivery_key");
            var endpoint=jdbc.queryForMap("SELECT receiver_public_key,receiver_identity FROM ds_receiver_endpoint WHERE endpoint_id=?",UUID.fromString(manifest.get("endpointId").toString()));
            var envelope=receiver.receipt(key);boolean reconciled=envelope!=null;
            if(envelope==null){Path zip=archive(task,manifest);try{live(task);envelope=receiver.send(key,zip);}finally{Files.deleteIfExists(zip);}}
            var receipt=receiver.verify(envelope,manifest,endpoint.get("receiver_public_key").toString(),endpoint.get("receiver_identity").toString());
            // Explicit local fault-test setting, default zero; never driven by API input.
            if(receiptCommitDelay>0)Thread.sleep(receiptCommitDelay);
            finish(task,manifest,envelope,receipt,reconciled);
        }catch(Exception e){fail(task,e);}finally{renew.cancel(false);}
    }
    /**
     * A receiver can have committed its receipt before the sender crashes. Expired,
     * revoked or superseded grants forbid another send, but must not hide that receipt.
     * This queue has separate bounded attempts and never claims a delivery lease.
     */
    private Map<String,Object> claimReceiptReconciliation(){return tx.execute(status->{
        jdbc.update("""
            INSERT INTO ds_delivery_receipt_reconciliation(task_id)
            SELECT t.task_id FROM ds_delivery_task t
            WHERE t.status='CANCELLED' AND t.package_manifest IS NOT NULL AND t.attempt_count>0
              AND (t.lease_until IS NULL OR t.lease_until<=now())
              AND NOT EXISTS(SELECT 1 FROM ds_receipt r WHERE r.delivery_key=t.delivery_key)
            ON CONFLICT(task_id) DO NOTHING
            """);
        // A late original worker may already have stored a verified receipt.
        jdbc.update("""
            UPDATE ds_delivery_receipt_reconciliation r
            SET status='RECONCILED',last_error=null,lease_token=null,lease_until=null,completed_at=now(),updated_at=now()
            FROM ds_delivery_task t WHERE t.task_id=r.task_id AND r.status IN ('PENDING','RETRY','RUNNING','EXHAUSTED')
              AND EXISTS(SELECT 1 FROM ds_receipt received WHERE received.delivery_key=t.delivery_key)
            """);
        var exhausted=jdbc.queryForList("""
            UPDATE ds_delivery_receipt_reconciliation r
            SET status='EXHAUSTED',last_error='历史回执对账租约过期且自动尝试已用尽；未恢复授权或重新发送',
                lease_token=null,lease_until=null,completed_at=now(),updated_at=now()
            FROM ds_delivery_task t WHERE t.task_id=r.task_id AND r.status='RUNNING'
              AND r.lease_until<=now() AND r.attempt_count>=5 RETURNING t.*
            """);
        for(var task:exhausted)audit(task,"HISTORICAL_RECEIPT_CHECK_EXHAUSTED",map("attempts",5,"reason","reconciliation lease expired"));
        var due=jdbc.queryForList("""
            SELECT t.*,r.attempt_count AS receipt_attempt_count FROM ds_delivery_receipt_reconciliation r
            JOIN ds_delivery_task t ON t.task_id=r.task_id
            WHERE t.status='CANCELLED' AND r.attempt_count<5
              AND ((r.status IN ('PENDING','RETRY') AND r.next_attempt_at<=now()) OR (r.status='RUNNING' AND r.lease_until<=now()))
            ORDER BY r.next_attempt_at,r.created_at FOR UPDATE OF r SKIP LOCKED LIMIT 1
            """);
        if(due.isEmpty())return null;var task=due.get(0);UUID token=UUID.randomUUID();
        int attempt=((Number)task.get("receipt_attempt_count")).intValue()+1;
        jdbc.update("""
            UPDATE ds_delivery_receipt_reconciliation
            SET status='RUNNING',attempt_count=?,lease_token=?,lease_until=now()+interval '120 seconds',updated_at=now()
            WHERE task_id=?
            """,attempt,token,task.get("task_id"));
        task.put("receipt_lease_token",token);task.put("receipt_attempt_count",attempt);return task;
    });}
    private boolean receiptLeaseHeld(Map<String,Object> task){
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ds_delivery_receipt_reconciliation
            WHERE task_id=? AND lease_token=? AND status='RUNNING' AND lease_until>now())
            """,Boolean.class,task.get("task_id"),task.get("receipt_lease_token")));
    }
    private void reconcileReceipt(Map<String,Object> task){
        try{
            // Only the frozen manifest and receiver's signed metadata are read here.
            // Do not call manifest(), archive(), send(), or a live-grant retry path.
            var manifest=decode(task.get("package_manifest"));
            if(!receiver.digest(manifest).equals(String.valueOf(task.get("package_manifest_digest")).trim()))
                throw new IllegalStateException("frozen delivery manifest digest mismatch");
            for(String field:List.of("deliveryKey","contractId","contractRevision")){
                String column=switch(field){case "deliveryKey"->"delivery_key";case "contractId"->"contract_id";default->"contract_revision";};
                if(!String.valueOf(task.get(column)).equals(String.valueOf(manifest.get(field))))
                    throw new IllegalStateException("frozen delivery manifest identity mismatch");
            }
            var endpoint=jdbc.queryForMap("SELECT receiver_public_key,receiver_identity FROM ds_receiver_endpoint WHERE endpoint_id=?",UUID.fromString(manifest.get("endpointId").toString()));
            var envelope=receiver.receipt((UUID)task.get("delivery_key"));
            if(envelope==null)throw new IllegalStateException("receiver has no receipt for this delivery key");
            var receipt=receiver.verify(envelope,manifest,endpoint.get("receiver_public_key").toString(),endpoint.get("receiver_identity").toString());
            tx.executeWithoutResult(status->{
                jdbc.queryForMap("SELECT contract_id FROM ds_contract WHERE contract_id=? FOR UPDATE",task.get("contract_id"));
                jdbc.queryForMap("SELECT task_id FROM ds_delivery_task WHERE task_id=? FOR UPDATE",task.get("task_id"));
                jdbc.queryForMap("SELECT task_id FROM ds_delivery_receipt_reconciliation WHERE task_id=? FOR UPDATE",task.get("task_id"));
                if(!receiptLeaseHeld(task))return;
                storeReceipt(task,envelope,receipt);
                jdbc.update("""
                    UPDATE ds_delivery_receipt_reconciliation SET status='RECONCILED',last_error=null,lease_token=null,
                        lease_until=null,completed_at=now(),updated_at=now() WHERE task_id=? AND lease_token=?
                    """,task.get("task_id"),task.get("receipt_lease_token"));
                audit(task,"RECEIVED_AFTER_ACCESS_ENDED",map("deliveryKey",task.get("delivery_key"),"reconciled",true,
                    "receiptOnly",true,"fileCount",receipt.get("fileCount"),"manifestDigest",receipt.get("manifestDigest")));
            });
        }catch(Exception error){
            try{tx.executeWithoutResult(status->{
                jdbc.queryForMap("SELECT task_id FROM ds_delivery_receipt_reconciliation WHERE task_id=? FOR UPDATE",task.get("task_id"));
                if(!receiptLeaseHeld(task))return;
                int attempt=((Number)task.get("receipt_attempt_count")).intValue();boolean exhausted=attempt>=5;
                String detail=(error instanceof IllegalStateException||error instanceof java.security.GeneralSecurityException)
                    ?String.valueOf(error.getMessage()):"独立接收端历史回执读取或核验失败";
                String message=error.getClass().getSimpleName()+": "+detail;if(message.length()>1000)message=message.substring(0,1000);
                // Last retry is beyond the maximum in-flight send timeout (15 minutes).
                int delay=switch(attempt){case 1->30;case 2->120;case 3->300;default->900;};
                jdbc.update("""
                    UPDATE ds_delivery_receipt_reconciliation SET status=?,last_error=?,lease_token=null,lease_until=null,
                        next_attempt_at=now()+(?*interval '1 second'),completed_at=CASE WHEN ? THEN now() ELSE null END,updated_at=now()
                    WHERE task_id=? AND lease_token=?
                    """,exhausted?"EXHAUSTED":"RETRY",message,delay,exhausted,task.get("task_id"),task.get("receipt_lease_token"));
                audit(task,exhausted?"HISTORICAL_RECEIPT_CHECK_EXHAUSTED":"HISTORICAL_RECEIPT_CHECK_FAILED",
                    map("attempt",attempt,"reason",message,"receiptOnly",true));
            });}catch(Exception ignored){System.err.println("historical receipt persistence deferred to lease recovery");}
        }
    }
    private void finish(Map<String,Object> task,Map<String,Object> manifest,Map<String,Object> envelope,Map<String,Object> receipt,boolean reconciled){tx.executeWithoutResult(status->{
        jdbc.queryForMap("SELECT contract_id FROM ds_contract WHERE contract_id=? FOR UPDATE",task.get("contract_id"));
        jdbc.queryForMap("SELECT task_id FROM ds_delivery_task WHERE task_id=? FOR UPDATE",task.get("task_id"));
        // Even a transfer finishing after revocation is audited, but grants no access.
        storeReceipt(task,envelope,receipt);
        if(!leased(task))return; // A stale worker never changes a new worker's task; its receipt remains reconcilable.
        boolean valid;
        try{live(task);valid=true;}catch(IllegalStateException e){valid=false;}
        int changed=jdbc.update("UPDATE ds_delivery_task SET status=?,last_error=?,lease_until=null,lease_token=null,updated_at=now() WHERE task_id=? AND lease_token=?",valid?"DELIVERED":"CANCELLED",valid?null:"接收端已核验，但当前授权、修订或执行租约已失效",task.get("task_id"),task.get("lease_token"));
        if(changed==1){jdbc.update("UPDATE ds_delivery_attempt SET status=?,finished_at=now() WHERE task_id=? AND attempt_number=? AND status='RUNNING'",reconciled?"RECONCILED":"RECEIVED",task.get("task_id"),task.get("attempt_count"));
            audit(task,valid?"DELIVERY_RECEIPT_VERIFIED":"RECEIVED_AFTER_ACCESS_ENDED",map("deliveryKey",task.get("delivery_key"),"reconciled",reconciled,"fileCount",receipt.get("fileCount"),"manifestDigest",receipt.get("manifestDigest")));}
    });}
    /** Caller locks the contract and delivery task; receipts and their storage audit are immutable. */
    private void storeReceipt(Map<String,Object> task,Map<String,Object> envelope,Map<String,Object> receipt){
        var existing=jdbc.queryForList("SELECT manifest_digest,endpoint_id,contract_revision FROM ds_receipt WHERE delivery_key=?",task.get("delivery_key"));
        if(existing.isEmpty()){jdbc.update("""
            INSERT INTO ds_receipt(delivery_key,endpoint_id,consumer_org_id,contract_revision,manifest_digest,file_count,size_bytes,verification,receiver_identity,received_at)
            VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)
            """,task.get("delivery_key"),UUID.fromString(receipt.get("endpointId").toString()),UUID.fromString(receipt.get("consumerOrgId").toString()),receipt.get("contractRevision"),receipt.get("manifestDigest"),receipt.get("fileCount"),receipt.get("sizeBytes"),encode(envelope),receipt.get("receiverIdentity"),OffsetDateTime.parse(receipt.get("receivedAt").toString()));
            audit(task,"RECEIVER_RECEIPT_STORED",map("deliveryKey",task.get("delivery_key"),"manifestDigest",receipt.get("manifestDigest")));}
        else if(!existing.get(0).get("manifest_digest").toString().trim().equals(receipt.get("manifestDigest"))||!existing.get(0).get("endpoint_id").toString().equals(receipt.get("endpointId"))||!existing.get(0).get("contract_revision").toString().equals(receipt.get("contractRevision").toString()))throw new IllegalStateException("persisted receipt identity mismatch");
    }
    private void fail(Map<String,Object> task,Exception error){try{tx.executeWithoutResult(status->{
        jdbc.queryForMap("SELECT contract_id FROM ds_contract WHERE contract_id=? FOR UPDATE",task.get("contract_id"));
        jdbc.queryForMap("SELECT task_id FROM ds_delivery_task WHERE task_id=? FOR UPDATE",task.get("task_id"));
        if(!leased(task))return;
        int attempt=((Number)task.get("attempt_count")).intValue(),max=((Number)task.get("max_attempts")).intValue();boolean blocked=!contracts.isAvailable((UUID)task.get("contract_id"));
        String next=attempt>=max?"FAILED":blocked?"BLOCKED":"RETRY";String detail=(error instanceof IllegalStateException||error instanceof java.security.GeneralSecurityException)?String.valueOf(error.getMessage()):"源快照读取或接收端传输/核验失败，未计为已交付";String message=error.getClass().getSimpleName()+": "+detail;if(message.length()>1000)message=message.substring(0,1000);
        jdbc.update("UPDATE ds_delivery_attempt SET status='FAILED',error_message=?,finished_at=now() WHERE task_id=? AND attempt_number=? AND status='RUNNING'",message,task.get("task_id"),attempt);
        jdbc.update("UPDATE ds_delivery_task SET status=?,last_error=?,lease_token=null,lease_until=null,next_attempt_at=now()+(?*interval '1 second'),updated_at=now() WHERE task_id=? AND lease_token=?",next,message,Math.min(300,10*(1<<Math.min(5,attempt))),task.get("task_id"),task.get("lease_token"));
        audit(task,"DELIVERY_ATTEMPT_FAILED",map("attempt",attempt,"status",next,"reason",message));
    });}catch(Exception ignored){System.err.println("delivery failure persistence deferred to lease recovery");}}
    private void audit(Map<String,Object> task,String action,Object details){jdbc.update("INSERT INTO ds_audit_event(org_id,action,object_type,object_id,details) SELECT DISTINCT x.org_id,?,'DELIVERY_TASK',?,?::jsonb FROM ds_contract c CROSS JOIN LATERAL(VALUES(c.provider_org_id),(c.consumer_org_id)) x(org_id) WHERE c.contract_id=?",action,task.get("task_id"),encode(details),task.get("contract_id"));}
    private Map<String,Object> authorizedTask(UUID task,boolean content){
        UUID org=access.actorOrg();var rows=jdbc.queryForList("SELECT t.*,c.provider_org_id,c.consumer_org_id,c.revision AS current_revision,(c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.valid_from<=now() AND now()<c.valid_to) AS live_contract,v.dataset_id,v.version_id,v.index_version_id FROM ds_delivery_task t JOIN ds_contract c ON c.contract_id=t.contract_id JOIN ds_product_version v ON v.product_version_id=c.product_version_id WHERE t.task_id=?",task);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"交付不存在");var row=rows.get(0);
        if(!org.equals(row.get("provider_org_id"))&&!org.equals(row.get("consumer_org_id")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"交付不属于当前组织");
        if(content){access.requireVersionRead((UUID)row.get("dataset_id"),(UUID)row.get("version_id"),(UUID)row.get("index_version_id"));if((!Boolean.TRUE.equals(row.get("live_contract"))||!row.get("contract_revision").equals(row.get("current_revision")))&&!access.ownsDataset((UUID)row.get("dataset_id")))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"该交付的合约已失效或修订已变更，旧入口不能继续下载");}
        return row;
    }
    public Map<String,Object> manifestForUser(UUID task){var row=authorizedTask(task,true);if(row.get("package_manifest")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"交付清单尚未生成");return decode(row.get("package_manifest"));}
    public Map<String,Object> receiptForUser(UUID task){var row=authorizedTask(task,false);var receipts=jdbc.queryForList("SELECT r.verification,e.receiver_public_key FROM ds_receipt r JOIN ds_receiver_endpoint e ON e.endpoint_id=r.endpoint_id WHERE r.delivery_key=?",row.get("delivery_key"));if(receipts.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"尚无独立核验回执");var result=decode(receipts.get(0).get("verification"));result.put("receiverPublicKey",receipts.get(0).get("receiver_public_key"));return result;}
    public List<Map<String,Object>> attempts(UUID task){authorizedTask(task,false);return jdbc.queryForList("SELECT attempt_number AS \"attemptNumber\",status,error_message AS error,started_at AS \"startedAt\",finished_at AS \"finishedAt\" FROM ds_delivery_attempt WHERE task_id=? ORDER BY attempt_number",task);}
    public DataSpaceReceiverClient.PackageStream openPackage(UUID task)throws Exception{
        var row=authorizedTask(task,true);if(!"DELIVERED".equals(row.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"交付尚未完成独立接收验证");
        var manifest=decode(row.get("package_manifest"));var endpoint=jdbc.queryForMap("SELECT receiver_identity,receiver_public_key FROM ds_receiver_endpoint WHERE endpoint_id=?",UUID.fromString(manifest.get("endpointId").toString()));
        receiver.verify(receiptForUser(task),manifest,endpoint.get("receiver_public_key").toString(),endpoint.get("receiver_identity").toString());return receiver.openPackage((UUID)row.get("delivery_key"));
    }
    @PreDestroy public void close(){worker.shutdownNow();heartbeat.shutdownNow();}
}
