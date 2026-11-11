package com.fwdrobo.sirius.security;
import com.fwdrobo.sirius.service.DatasetAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
/** Capture immutable request coordinates before MinIO reads on another thread. */
@Service
public class ArtifactWriteAuthority {
 private final AuthorizationService authorization;private final DatasetAccessService access;
 public ArtifactWriteAuthority(AuthorizationService authorization,DatasetAccessService access){this.authorization=authorization;this.access=access;}
 @FunctionalInterface public interface Work<T>{T run()throws Exception;}
 public final class Guard {
  private final AuthorizationService.Actor actor;private final Authentication identity;
  private final ServletRequestAttributes attributes;private final UUID repo,commit;private final String operation,purpose;
  private Guard(AuthorizationService.Actor actor,Authentication identity,ServletRequestAttributes attributes,UUID repo,UUID commit,String operation,String purpose){
   this.actor=actor;this.identity=identity;this.attributes=attributes;this.repo=repo;this.commit=commit;this.operation=operation;this.purpose=purpose;
  }
  public AuthorizationService.Actor actor(){return actor;}public String operation(){return operation;}public String purpose(){return purpose;}
  public <T>T withContext(Work<T> work)throws Exception{
   var prior=SecurityContextHolder.getContext();var priorRequest=RequestContextHolder.getRequestAttributes();
   var captured=SecurityContextHolder.createEmptyContext();captured.setAuthentication(identity);
   SecurityContextHolder.setContext(captured);RequestContextHolder.setRequestAttributes(attributes,false);
   try{return work.run();}finally{
    SecurityContextHolder.setContext(prior);
    if(priorRequest==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(priorRequest,false);
   }
  }
  public void check(){
   try{withContext(()->{try(var fixed=authorization.enterOperation(operation,purpose)){access.requireArtifact(repo,commit,true);}return null;});}
   catch(RuntimeException denied){throw denied;}catch(Exception unavailable){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"UPLOAD_AUTHORITY_UNAVAILABLE");}
  }
 }
 public Guard capture(UUID repo,UUID commit,String requestedOperation){
  if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)||
     !(attributes.getRequest().getAttribute("ds.liveAuthentication") instanceof Runnable))
   throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"LIVE_UPLOAD_SESSION_REQUIRED");
  var actor=authorization.actor();var identity=SecurityContextHolder.getContext().getAuthentication();
  boolean job=attributes.getRequest().getAttribute("ds.jobIoClaims") instanceof io.jsonwebtoken.Claims;
  String operation=job?authorization.currentOperation(requestedOperation):requestedOperation;
  String purpose=job?"VIEW_BUILD":"GENERAL_READ";
  Guard guard=new Guard(actor,identity,attributes,repo,commit,operation,purpose);guard.check();return guard;
 }
}
