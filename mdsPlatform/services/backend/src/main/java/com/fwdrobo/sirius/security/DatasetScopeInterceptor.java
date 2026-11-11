package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.service.DatasetAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.util.Map;
import java.util.UUID;

/** Protect legacy HTTP entry points as well as the QA gateway. */
@Component
public class DatasetScopeInterceptor implements HandlerInterceptor,WebMvcConfigurer {
    private final DatasetAccessService access;
    @org.springframework.beans.factory.annotation.Autowired private IndexJobAuthority indexJobs;
    private UUID uuid(String value){try{return UUID.fromString(value);}catch(IllegalArgumentException e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"对象标识需要有效UUID");}}
    public DatasetScopeInterceptor(DatasetAccessService access) {this.access=access;}
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/mm/**","/api/artifacts/**").excludePathPatterns("/api/mm/internal/**");
    }
    @Override public boolean preHandle(HttpServletRequest req,HttpServletResponse resp,Object handler) {
        access.actorOrg();
        @SuppressWarnings("unchecked") Map<String,String> vars=(Map<String,String>)req.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars==null) return true;
        // A native callback updates only its signed run's exact result. It is not a human dataset write.
        if(handler instanceof org.springframework.web.method.HandlerMethod method &&
           org.springframework.util.ClassUtils.getUserClass(method.getBeanType()).getSimpleName().equals("IndexJobCallbackController") &&
           java.util.Set.of("ready","failed").contains(method.getMethod().getName())){
            if(!(req.getAttribute("ds.jobIoClaims") instanceof io.jsonwebtoken.Claims claims))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"TYPED_INDEX_CALLBACK_REQUIRED");
            IndexJobAuthority.Origin origin=indexJobs.revalidateRecordedOrigin(claims);
            if(!origin.coordinates().equals(new IndexJobAuthority.Coordinates(uuid(vars.get("datasetId")),uuid(vars.get("versionId")),uuid(vars.get("indexVersionId")))) ||
               !indexJobs.permits(req,claims))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"INDEX_CALLBACK_SCOPE_MISMATCH");
            return true;
        }
        boolean write=!"GET".equals(req.getMethod())&&!"HEAD".equals(req.getMethod());
        if (vars.containsKey("datasetId")) {
            UUID dataset=uuid(vars.get("datasetId"));
            if (write || req.getRequestURI().contains("/ingest-tasks")) access.requireDatasetWrite(dataset);
            else if (vars.containsKey("versionId")) access.requireVersionRead(dataset,uuid(vars.get("versionId")),
                vars.containsKey("indexVersionId")?uuid(vars.get("indexVersionId")):null);
            else access.requireDatasetRead(dataset);
        }
        String repo=vars.getOrDefault("artifactId",vars.get("repoId"));
        if (repo!=null) access.requireArtifact(uuid(repo),vars.containsKey("commitId")?uuid(vars.get("commitId")):null,write);
        return true;
    }
}
