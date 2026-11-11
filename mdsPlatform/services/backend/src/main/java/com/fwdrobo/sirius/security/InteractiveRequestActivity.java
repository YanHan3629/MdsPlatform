package com.fwdrobo.sirius.security;

import io.jsonwebtoken.Claims;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Lifecycle observations, never an authorization mechanism. No user content or bearer is stored. */
@Service
public class InteractiveRequestActivity {
 public enum Kind { QA_REQUEST, RENDER_REQUEST }
 private final JdbcTemplate db;
 private final TransactionTemplate tx;
 private final UUID runtime=UUID.randomUUID();
 private final Set<UUID> active=ConcurrentHashMap.newKeySet();
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(InteractiveRequestActivity.class);
 public InteractiveRequestActivity(JdbcTemplate db,PlatformTransactionManager manager){
  this.db=db;this.tx=new TransactionTemplate(manager);
  this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
 }
 public final class Ticket implements AutoCloseable {
  private final UUID id;
  private boolean succeeded,uncertain,closed;
  private Ticket(UUID id){this.id=id;}
  public void dispatched(){uncertain=true;}
  public void acknowledged(){uncertain=false;}
  public void succeeded(){succeeded=true;uncertain=false;}
  @Override public void close(){
   if(closed)return;closed=true;active.remove(id);
   String state=uncertain?"UNCERTAIN":succeeded?"SUCCEEDED":"FAILED";
   tx.executeWithoutResult(status->db.update("""
    UPDATE ds_interactive_request_activity SET state=?,lease_until=clock_timestamp(),
     finished_at=CASE WHEN ?='UNCERTAIN' THEN NULL ELSE clock_timestamp() END
    WHERE request_id=? AND runtime_id=? AND state='RUNNING'
    """,state,state,id,runtime));
  }
 }
 public Ticket begin(Kind kind){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs))
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INTERACTIVE_REQUEST_REQUIRED");
  var request=attrs.getRequest();
  if(!(request.getAttribute("ds.liveAuthentication") instanceof Runnable live))
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"INTERACTIVE_REQUEST_REQUIRED");
  live.run();
  if(!(request.getAttribute("ds.actor") instanceof AuthorizationService.Actor actor)||actor.userId()==null)
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"HUMAN_REQUEST_ORIGIN_REQUIRED");
  Object value=request.getAttribute("ds.accessClaims");
  if(value==null)value=request.getAttribute("ds.evidenceClaims");
  if(!(value instanceof Claims claims)||!(claims.get("sid") instanceof String sid))
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"HUMAN_REQUEST_SESSION_REQUIRED");
  UUID session;
  try{session=UUID.fromString(sid);}catch(IllegalArgumentException invalid){
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"HUMAN_REQUEST_SESSION_REQUIRED");
  }
  UUID id=UUID.randomUUID();
  tx.executeWithoutResult(status->db.update("""
   INSERT INTO ds_interactive_request_activity(request_id,runtime_id,actor_user_id,actor_org_id,
    origin_session_id,actor_token_version,request_kind,state,lease_until)
   VALUES(?,?,?,?,?,?,?,'RUNNING',clock_timestamp()+interval '45 seconds')
   """,id,runtime,actor.userId(),actor.orgId(),session,actor.tokenVersion(),kind.name()));
  active.add(id);return new Ticket(id);
 }
 @Scheduled(initialDelay=10000,fixedDelay=10000)
 public void heartbeat(){
  var ids=List.copyOf(active);if(ids.isEmpty())return;
  try{
   var parameters=new ArrayList<Object[]>();for(UUID id:ids)parameters.add(new Object[]{id,runtime});
   db.batchUpdate("UPDATE ds_interactive_request_activity SET lease_until=clock_timestamp()+interval '45 seconds' WHERE request_id=? AND runtime_id=? AND state='RUNNING'",parameters);
  }catch(RuntimeException unavailable){log.warn("Interactive request observation heartbeat unavailable");}
 }
 @PreDestroy public void shutdown(){
  try{db.update("UPDATE ds_interactive_request_activity SET state='UNCERTAIN',lease_until=clock_timestamp() WHERE runtime_id=? AND state='RUNNING'",runtime);}
  catch(RuntimeException unavailable){log.warn("Interactive request shutdown observation unavailable");}
 }
}
