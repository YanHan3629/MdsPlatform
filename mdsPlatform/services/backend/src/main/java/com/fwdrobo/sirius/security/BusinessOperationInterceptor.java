package com.fwdrobo.sirius.security;
import jakarta.servlet.http.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;
import org.springframework.web.server.ResponseStatusException;
/** Runs before any resource interceptor. New unmapped business handlers fail closed. */
@Component
public class BusinessOperationInterceptor implements HandlerInterceptor,WebMvcConfigurer {
 @org.springframework.beans.factory.annotation.Autowired private JobExecutionDefinitionService definitions;
 private final AuthorizationService auth;
 public BusinessOperationInterceptor(AuthorizationService auth){this.auth=auth;}
 @Override public void addInterceptors(InterceptorRegistry registry){
  registry.addInterceptor(this).addPathPatterns("/api/mm/**","/api/artifacts/**","/api/jobs/**","/api/data-space/**","/api/docker/**","/api/rbac/**","/api/controlled-view-proposals/**","/api/controlled-view-previews/**","/api/controlled-view-publications/**").order(-200);
 }
 @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
  if("OPTIONS".equals(request.getMethod()))return true;
  if(!(handler instanceof HandlerMethod method))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"OPERATION_NOT_AVAILABLE");
  String controller=ClassUtils.getUserClass(method.getBeanType()).getSimpleName(),name=method.getMethod().getName();
  if((controller.equals("DataSpaceFlowController")||controller.equals("DataSpaceQaController"))&&name.equals("health"))return true;
  String operation;
  if(request.getAttribute(QualityJobAuthority.ATTRIBUTE) instanceof QualityJobAuthority.Scope quality){
   if(!Set.of("DataSpaceQualityController.claim","DataSpaceQualityController.heartbeat","DataSpaceQualityController.content","DataSpaceQualityController.complete","DataSpaceQualityController.fail").contains(controller+"."+name))
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"QUALITY_HANDLER_OUTSIDE_SCOPE");
   operation=QualityJobAuthority.OP;request.setAttribute(AuthorizationService.class.getName()+".purpose",QualityJobAuthority.PURPOSE);
   auth.requireOperation(quality.actor(),operation);
  }else if(request.getAttribute(IndexCacheScopeService.ATTRIBUTE) instanceof IndexCacheScopeService.Scope cache){
   if(!Set.of("IndexCacheWorkerController.authority","IndexCacheWorkerController.bundle","FileController.downloadContent").contains(controller+"."+name))
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"INDEX_CACHE_HANDLER_OUTSIDE_SCOPE");
   operation=cache.operation();
   request.setAttribute(AuthorizationService.class.getName()+".purpose","VIEW_BUILD");
   auth.requireOperation(cache.actor(),operation);
  }else if(request.getAttribute(ServiceScopeService.ATTRIBUTE) instanceof ServiceScopeService.Scope service){
   if(!Set.of("ServiceWorkerController.authority","ServiceWorkerController.bundle","FileController.downloadContent").contains(controller+"."+name))
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"SERVICE_HANDLER_OUTSIDE_SCOPE");
   operation="dataset.index.build";
   request.setAttribute(AuthorizationService.class.getName()+".purpose","VIEW_BUILD");
   auth.requireOperation(service.actor(),operation);
  }else if(request.getAttribute(WorkerEvidenceScopeService.ATTRIBUTE) instanceof WorkerEvidenceScopeService.Scope scoped){
   if(!Set.of("WorkerEvidenceController.authorize","MmIndexController.resolveBundle","FileController.downloadContent",
       "DataSpaceQaController.capabilities","DataSpaceQaController.access","DataSpaceQaController.source",
       "QaEvidenceController.read","QaEvidenceController.render","QaEvidenceController.describe","QaEvidenceController.image").contains(controller+"."+name))
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"EVIDENCE_HANDLER_OUTSIDE_SCOPE");
   operation=scoped.operation();
   if(scoped.userInputOnly()){
    if(!"WorkerEvidenceController.authorize".equals(controller+"."+name))
     throw new ResponseStatusException(HttpStatus.FORBIDDEN,"USER_INPUT_HANDLER_OUTSIDE_SCOPE");
    request.setAttribute(AuthorizationService.class.getName()+".purpose","USER_INPUT_ONLY");
    auth.requireOperation(scoped.actor(),"data.qa");
   }else{
    request.setAttribute(AuthorizationService.class.getName()+".purpose","GENERAL_READ");
    auth.requireDataset(scoped.actor(),scoped.dataset(),scoped.version(),scoped.index(),operation,"GENERAL_READ");
   }
  }else{
   if(request.getRequestURI().contains("/api/mm/internal/"))
    throw new ResponseStatusException(HttpStatus.FORBIDDEN,"SCOPED_SERVICE_SUBJECT_REQUIRED");
   operation=EndpointOperationCatalog.operation(controller,name,request.getMethod());
   if(operation==null)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UNMAPPED_OPERATION");
   if("MmIngestTaskController".equals(controller)&&"mapping".equals(name)&&"dataset.index.build".equals(operation))
    operation=mappingReadOperation(request);
   if("DataSpaceFlowController".equals(controller)){
    if("listIntents".equals(name)&&!auth.hasOperation(operation)){
     if(auth.hasOperation("application.review"))operation="application.review";
     else if(auth.hasOperation("contract.edit"))operation="contract.edit";
    }
    if("endpoints".equals(name)&&request.getParameter("applicationId")!=null&&auth.hasOperation("contract.edit"))
     operation="contract.edit";
   }
  }
  if(request.getAttribute("ds.jobIoClaims") instanceof io.jsonwebtoken.Claims){
   // JwtAuthFilter already verified immutable run/repo/commit/path coordinates.
   var execution=definitions.workerOperation(request,(io.jsonwebtoken.Claims)request.getAttribute("ds.jobIoClaims"));
   operation=execution.code();
   request.setAttribute(AuthorizationService.class.getName()+".purpose",execution.purpose());
  }
  if(Set.of("dataset.index.build","dataset.index.activate").contains(operation))request.setAttribute(AuthorizationService.class.getName()+".purpose","VIEW_BUILD");
  if(Set.of("delivery.execute","delivery.package.download").contains(operation)) request.setAttribute(AuthorizationService.class.getName()+".purpose","CONTROLLED_DELIVERY");
  auth.requireOperation(operation);
  request.setAttribute(AuthorizationService.REQUEST_OPERATION,operation);
  response.setHeader("Cache-Control","no-store, private, max-age=0");
  response.setHeader("Pragma","no-cache");
  response.setHeader("X-Authorization-Policy-Version",Long.toString(auth.currentPolicyVersion()));
  return true;
 }

 private String mappingReadOperation(HttpServletRequest request){
  var actor=auth.actor();
  if(!actor.operations().contains("dataset.draft.manage"))return "dataset.index.build";
  if(!actor.operations().contains("dataset.index.build"))return "dataset.draft.manage";
  // A dual-role account can have different resource grants for these two operations.
  var variables=(Map<?,?>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
  if(variables==null||!(variables.get("datasetId") instanceof String rawDataset))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"对象标识需要有效UUID");
  UUID dataset;
  try{dataset=UUID.fromString(rawDataset);}
  catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"对象标识需要有效UUID");}
  return auth.canUseDataset(actor,dataset,null,null,"dataset.index.build","VIEW_BUILD")
    ?"dataset.index.build":"dataset.draft.manage";
 }
}
