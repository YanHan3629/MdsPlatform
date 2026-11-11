package com.fwdrobo.sirius.security;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.util.ClassUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Fixed job/run tuple validation for legacy task detail, logs and cancellation. */
@Component
public class JobResourceInterceptor implements HandlerInterceptor,WebMvcConfigurer {
 private final JobResourceAuthority jobs;
 public JobResourceInterceptor(JobResourceAuthority jobs){this.jobs=jobs;}
 @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(this).addPathPatterns("/api/jobs/**").order(-150);}
 @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
  if("OPTIONS".equals(request.getMethod())||request.getAttribute("ds.jobIoClaims") instanceof io.jsonwebtoken.Claims)return true;
  if(!(handler instanceof HandlerMethod method)||!"JobController".equals(ClassUtils.getUserClass(method.getBeanType()).getSimpleName()))return true;
  String operation=switch(method.getMethod().getName()){
   case "getJobById","getJobRunById","runPageQuery"->"job.read";
   case "logs"->"job.log.read";
   case "stop"->"job.cancel";
   case "downloadJobFile"->"data.raw.download";
   default->null;
  };
  if(operation==null)return true;
  // Each historical run is checked against its own immutable declaration before total/page.
  if("runPageQuery".equals(method.getMethod().getName()))return true;
  @SuppressWarnings("unchecked") Map<String,String> variables=(Map<String,String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
  if(variables==null||!variables.containsKey("jobId"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"JOB_TUPLE_REQUIRED");
  try{jobs.require(UUID.fromString(variables.get("jobId")),variables.containsKey("runId")?UUID.fromString(variables.get("runId")):null,operation);}
  catch(IllegalArgumentException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_JOB_TUPLE");}
  return true;
 }
}
