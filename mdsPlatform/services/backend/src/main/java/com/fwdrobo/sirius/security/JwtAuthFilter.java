package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.entity.FwdUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.security.Principal;
import java.util.*;

/** Authenticate immutable actor/session identity; never derive privilege from a type name. */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    @org.springframework.beans.factory.annotation.Autowired
    private ServiceScopeService serviceScopes;
    @org.springframework.beans.factory.annotation.Autowired
    private IndexCacheScopeService indexCacheScopes;
    @org.springframework.beans.factory.annotation.Autowired private QualityJobAuthority qualityJobs;
    private final JwtService jwt;private final IdentitySessionService sessions;private final JobIoScope jobScope;private final ObjectMapper mapper;private final WorkerEvidenceScopeService evidenceScope;
    public JwtAuthFilter(JwtService jwt,IdentitySessionService sessions,JobIoScope jobScope,ObjectMapper mapper,WorkerEvidenceScopeService evidenceScope) {
        this.jwt=jwt;this.sessions=sessions;this.jobScope=jobScope;this.mapper=mapper;this.evidenceScope=evidenceScope;
    }
    public record UserPrincipal(UUID userId,String userName,List<String> roles,UUID orgId,UUID serviceId) implements Principal {
        public UserPrincipal(UUID userId,String userName,List<String> roles,UUID orgId){this(userId,userName,roles,orgId,null);}
        @Override public String getName(){return userName;}
    }
    private void authenticateIndexCache(HttpServletRequest request,HttpServletResponse response,Claims claims){
        var scope=indexCacheScopes.authenticate(request,claims);var actor=scope.actor();
        var authorities=java.util.List.of(new SimpleGrantedAuthority("OP_"+scope.operation()));
        var principal=new UserPrincipal(actor.userId(),actor.username(),authorities.stream().map(SimpleGrantedAuthority::getAuthority).toList(),actor.orgId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,authorities));
        request.setAttribute("ds.actor",actor);
        request.setAttribute("ds.liveAuthentication",(Runnable)()->indexCacheScopes.authenticate(request,claims));
        response.setHeader("X-Identity-Permission-Version",Long.toString(actor.permissionVersion()));
    }
    private void authenticateService(HttpServletRequest request,HttpServletResponse response,Claims claims){
        AuthorizationService.Actor actor;
        if("QUALITY_WORKER".equals(claims.get("service_kind")))actor=qualityJobs.authenticate(request,claims).actor();
        else actor=serviceScopes.authenticate(request,claims).actor();
        var authorities=actor.operations().stream().sorted().map(c->new SimpleGrantedAuthority("OP_"+c)).toList();
        var principal=new UserPrincipal(null,actor.username(),authorities.stream().map(SimpleGrantedAuthority::getAuthority).toList(),actor.orgId(),actor.serviceId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,authorities));
        request.setAttribute("ds.actor",actor);
        request.setAttribute("ds.liveAuthentication",(Runnable)()->{
            if("QUALITY_WORKER".equals(claims.get("service_kind")))qualityJobs.authenticate(request,claims);
            else serviceScopes.authenticate(request,claims);
        });
        response.setHeader("X-Identity-Permission-Version",Long.toString(actor.permissionVersion()));
    }
    private void reject(HttpServletResponse response,int status,String code) throws IOException {
        SecurityContextHolder.clearContext();response.setStatus(status);response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");response.setHeader("Cache-Control","no-store, private");
        mapper.writeValue(response.getOutputStream(),Map.of("success",false,"errorType",code,"status",status));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store, private");response.setHeader("Pragma","no-cache");
        String header=request.getHeader(HttpHeaders.AUTHORIZATION);
        if(header!=null&&!header.isBlank()) {
            if(!header.startsWith("Bearer ")||header.substring(7).isBlank()){reject(response,401,"ACCESS_TOKEN_REQUIRED");return;}
            try {
                Claims claims=jwt.parseClaims(header.substring(7).trim(),null);String type=claims.get("token_type",String.class);
                // Activation is a separate short-lived credential accepted only
                // by the explicit activation operation, never normal business APIs.
                if("INDEX_CACHE_IO".equals(type)){
                    authenticateIndexCache(request,response,claims);
                } else if("SERVICE_IO".equals(type)){
                    authenticateService(request,response,claims);
                } else if("ACTIVATION".equals(type)&&request.getRequestURI().equals("/api/auth/activate")) {
                    request.setAttribute("ds.activationToken",header.substring(7).trim());
                } else {
                boolean job="JOB_IO".equals(type),evidence="EVIDENCE_IO".equals(type);
                if(!job&&!evidence&&!"ACCESS".equals(type)){reject(response,401,"INTERACTIVE_ACCESS_TOKEN_REQUIRED");return;}
                WorkerEvidenceScopeService.Scope scoped=evidence?evidenceScope.authenticate(request,claims):null;
                AuthorizationService.Actor actor=evidence?scoped.actor():sessions.authenticateAccess(claims);
                if(!job&&!evidence)request.setAttribute("ds.accessClaims",claims);
                if(job) {
                    FwdUser u=new FwdUser();u.setUserId(actor.userId());u.setUserName(actor.username());u.setOrgId(actor.orgId());
                    // Legacy JobIoScope's online field is mapped from the already
                    // DB-verified ACTIVE account, not used as account validity.
                    u.setStatusCode(1);u.setTokenVersion(actor.tokenVersion());
                    if(!jobScope.permits(request,claims,u)){reject(response,403,"JOB_IO_SCOPE_DENIED");return;}
                    request.setAttribute("ds.jobIoClaims",claims);
                }
                var authorities=actor.operations().stream().sorted().map(c->new SimpleGrantedAuthority("OP_"+c)).toList();
                var principal=new UserPrincipal(actor.userId(),actor.username(),authorities.stream().map(SimpleGrantedAuthority::getAuthority).toList(),actor.orgId());
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,authorities));
                request.setAttribute("ds.actor",actor);response.setHeader("X-Identity-Permission-Version",Long.toString(actor.permissionVersion()));
                request.setAttribute("ds.liveAuthentication",(Runnable)()->{
                    if(claims.getExpiration()==null||!claims.getExpiration().after(new Date()))
                        throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"ACCESS_TOKEN_EXPIRED");
                    if(evidence)evidenceScope.revalidate(scoped,claims,request);
                    var live=sessions.authenticateAccess(claims);
                    if(!actor.userId().equals(live.userId())||!actor.orgId().equals(live.orgId())||
                            actor.tokenVersion()!=live.tokenVersion()||!actor.username().equals(live.username()))
                        throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"REQUEST_IDENTITY_REVOKED_OR_CHANGED");
                    if(job){
                        FwdUser current=new FwdUser();current.setUserId(live.userId());current.setUserName(live.username());current.setOrgId(live.orgId());current.setStatusCode(1);current.setTokenVersion(live.tokenVersion());
                        if(!jobScope.permits(request,claims,current))throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"JOB_IO_SCOPE_DENIED");
                    }
                });
                }
            } catch(JwtException|IllegalArgumentException invalid) {reject(response,401,"ACCESS_TOKEN_INVALID_OR_REVOKED");return;}
            catch(ResponseStatusException denied) {
                reject(response,denied.getStatusCode().value(),denied.getStatusCode().value()==503?"AUTHORIZATION_NOT_READY":"ACCESS_TOKEN_INVALID_OR_REVOKED");return;
            } catch(DataAccessException unavailable) {reject(response,503,"AUTHORITY_UNAVAILABLE");return;}
        }
        chain.doFilter(request,response);
    }
}
