package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.util.ExceptionUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class JwtService {

    public enum TokenType {
        ACCESS, REFRESH, ACTIVATION, JOB_IO, EVIDENCE_IO, SERVICE_IO, INDEX_CACHE_IO
    }

    // 声明 token 相关键
    private static final String TOKEN_TYPE_CLAIM = "token_type";
    private static final String TOKEN_VERSION_CLAIM = "token_version";

    // JWT 密钥
    // 目前为静态密钥，便于开发测试
    // TODO: 生产环境中应使用更安全的密钥管理方式
    private final SecretKey key;
    // 过期时间
    private final long accessExpirationSeconds;
    private final long refreshExpirationSeconds;

    public JwtService(
            @Value("${MDS_JWT_SIGNING_SECRET}") String secret,
            @Value("${app.jwt.access-expiration-seconds:1800}") long accessExpirationSeconds,
            @Value("${app.jwt.refresh-expiration-seconds:1296000}") long refreshExpirationSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpirationSeconds = accessExpirationSeconds;
        this.refreshExpirationSeconds = refreshExpirationSeconds;
    }

    /**
     * 生成访问令牌
     */
    public String generateAccessToken(String username, List<String> roles, Integer tokenVersion) {
        log.debug("生成访问令牌: username={}, role={}, tokenVersion={}, expirationSeconds={}",
                username, roles, tokenVersion, accessExpirationSeconds);
        return generateToken(username, roles, tokenVersion, TokenType.ACCESS, accessExpirationSeconds);
    }

    /**
     * 生成刷新令牌
     */
    public String generateRefreshToken(String username, List<String> roles, Integer tokenVersion) {
        log.debug("生成刷新令牌: username={}, role={}, tokenVersion={}, expirationSeconds={}",
                username, roles, tokenVersion, refreshExpirationSeconds);
        return generateToken(username, roles, tokenVersion, TokenType.REFRESH, refreshExpirationSeconds);
    }

    /** A worker credential never becomes an interactive access/refresh token. */
    public String generateJobIoToken(String username, Integer tokenVersion,
                                     Map<String, String> frozenScope, int runtimeSeconds) {
        if (runtimeSeconds <= 0) throw new IllegalArgumentException("Job runtime must be positive");
        // Every human job remains bound to the durable session that submitted it.
        for (String key : List.of("userId", "orgId", "parentSessionId", "jobId", "runId",
                "inputRepoId", "inputCommitId", "outputRepoId", "outputCommitId")) {
            if (frozenScope == null || frozenScope.get(key) == null)
                throw new IllegalArgumentException("Complete parent-bound job scope is required");
            java.util.UUID.fromString(frozenScope.get(key));
        }
        if(frozenScope.containsKey("jobKind")){
            if("INDEX_BUILD".equals(frozenScope.get("jobKind"))){
                for(String key:List.of("datasetId","versionId","indexVersionId"))java.util.UUID.fromString(frozenScope.get(key));
            }else if(ControlledViewPreviewAuthority.KIND.equals(frozenScope.get("jobKind"))){
                for(String key:List.of("previewAttemptId","proposalId"))java.util.UUID.fromString(frozenScope.get(key));
                if(!ControlledViewPreviewAuthority.SHARE.equals(frozenScope.get("executionOperation"))||
                   !frozenScope.containsKey("executionDefinitionSha256"))
                    throw new IllegalArgumentException("Frozen controlled-view preview definition is required");
            }else throw new IllegalArgumentException("Unknown job credential purpose");
        }
        Instant now=Instant.now(),expiration=now.plusSeconds((long)runtimeSeconds+300);
        if(frozenScope.containsKey("executionDefinitionSha256")){
            for(String name:List.of("executionDefinitionSha256","entrypointSha256","bootstrapSha256"))
                if(!frozenScope.getOrDefault(name,"").matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Frozen execution hashes are required");
            expiration=Instant.ofEpochSecond(Long.parseLong(frozenScope.get("definitionExpiresAt")));
            if(!expiration.isAfter(now)||expiration.isAfter(now.plusSeconds((long)runtimeSeconds+301)))
                throw new IllegalArgumentException("Frozen execution expiration is invalid");
        }
        return Jwts.builder().subject(username).issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .claim(TOKEN_TYPE_CLAIM, TokenType.JOB_IO.name())
                .claim(TOKEN_VERSION_CLAIM, tokenVersion == null ? 0 : tokenVersion)
                .claim("uid",frozenScope.get("userId"))
                .claim("org_id",frozenScope.get("orgId"))
                .claim("sid",frozenScope.get("parentSessionId"))
                .claim("job_io_scope", Map.copyOf(frozenScope)).signWith(key).compact();
    }

    public String generateEvidenceIoToken(AuthorizationService.Actor actor,java.util.UUID session,java.util.UUID dataset,
            java.util.UUID version,java.util.UUID index,String operation,AuthorizationService.ResourceAuthority authority,long expirationSeconds){
        if(authority==null||actor.userId()==null||actor.serviceId()!=null||expirationSeconds<=0||expirationSeconds>1200||!java.util.Set.of("data.qa","data.search","data.preview").contains(operation))
            throw new IllegalArgumentException("Invalid evidence credential");
        Instant now=Instant.now();
        var token=Jwts.builder().subject(actor.username()).id(java.util.UUID.randomUUID().toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .claim("uid",actor.userId().toString()).claim("org_id",actor.orgId().toString()).claim("sid",session.toString())
                .claim(TOKEN_TYPE_CLAIM,TokenType.EVIDENCE_IO.name()).claim(TOKEN_VERSION_CLAIM,actor.tokenVersion())
                .claim("dataset_id",dataset.toString()).claim("version_id",version.toString()).claim("index_version_id",index.toString())
                .claim("operation",operation).claim("purpose","GENERAL_READ");
        if(authority.grant()!=null)token.claim("authority_kind","GRANT").claim("grant_id",authority.grant().grantId().toString()).claim("grant_version",authority.grant().version());
        else token.claim("authority_kind","LEGACY_FULL_VERSION_CONTRACT").claim("legacy_contract_id",authority.legacyContract().contractId().toString()).claim("legacy_contract_revision",authority.legacyContract().revision());
        return token.signWith(key).compact();
    }

    /** An attachment-only credential contains no corpus coordinates. */
    public String generateUserInputIoToken(AuthorizationService.Actor actor,java.util.UUID session,long expirationSeconds){
        if(expirationSeconds<=0||expirationSeconds>1200)throw new IllegalArgumentException("Invalid user-input credential");
        Instant now=Instant.now();
        return Jwts.builder().subject(actor.username()).id(java.util.UUID.randomUUID().toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .claim("uid",actor.userId().toString()).claim("org_id",actor.orgId().toString()).claim("sid",session.toString())
                .claim(TOKEN_TYPE_CLAIM,TokenType.EVIDENCE_IO.name()).claim(TOKEN_VERSION_CLAIM,actor.tokenVersion())
                .claim("operation","data.qa").claim("purpose","USER_INPUT_ONLY").signWith(key).compact();
    }

    /**
     * 解析并验证令牌，返回声明
     */
    public String generateQualityWorkerToken(AuthorizationService.Actor actor,long seconds){
        if(actor.serviceId()==null||actor.userId()!=null||!actor.operations().equals(java.util.Set.of("dataset.quality.run"))||seconds<1||seconds>120)
            throw new IllegalArgumentException("Invalid quality worker credential");
        Instant now=Instant.now();
        return Jwts.builder().subject("service:"+actor.serviceId()).id(java.util.UUID.randomUUID().toString())
            .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(seconds)))
            .claim(TOKEN_TYPE_CLAIM,TokenType.SERVICE_IO.name()).claim("service_kind","QUALITY_WORKER")
            .claim("service_subject_id",actor.serviceId().toString()).claim("org_id",actor.orgId().toString())
            .claim("service_version",actor.permissionVersion()).claim("purpose","QUALITY_CHECK")
            .claim("operation","dataset.quality.run").signWith(key).compact();
    }
    public String generateServiceIoToken(AuthorizationService.Actor actor,ServiceScopeService.Scope scope,long seconds){
        if(actor.serviceId()==null||actor.userId()!=null||seconds<1||seconds>300)
            throw new IllegalArgumentException("Invalid institutional credential");
        Instant now=Instant.now();
        return Jwts.builder().subject("service:"+actor.serviceId()).id(java.util.UUID.randomUUID().toString())
            .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(seconds)))
            .claim(TOKEN_TYPE_CLAIM,TokenType.SERVICE_IO.name())
            .claim("service_subject_id",actor.serviceId().toString()).claim("org_id",actor.orgId().toString())
            .claim("service_version",actor.permissionVersion()).claim("service_kind","INDEX_CACHE")
            .claim("purpose","VIEW_BUILD").claim("operation","dataset.index.build")
            .claim("dataset_id",scope.dataset().toString()).claim("version_id",scope.version().toString())
            .claim("index_version_id",scope.index().toString())
            .claim("raw_repo_id",scope.rawRepo().toString()).claim("raw_commit_id",scope.rawCommit().toString())
            .claim("index_repo_id",scope.indexRepo().toString()).claim("index_commit_id",scope.indexCommit().toString())
            .claim("grant_id",scope.grant().grantId().toString()).claim("grant_version",scope.grant().version())
            .signWith(key).compact();
    }
    public String generateIndexCacheToken(IndexCacheScopeService.Scope scope,long seconds){
        if(seconds<1||seconds>IndexCacheScopeService.credentialLifetimeLimit(scope)
                ||scope.actor().serviceId()!=null||scope.actor().userId()==null)
            throw new IllegalArgumentException("Invalid human index cache credential");
        Instant now=Instant.now();
        var token=Jwts.builder().subject(scope.actor().username()).id(java.util.UUID.randomUUID().toString()).issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(seconds))).claim(TOKEN_TYPE_CLAIM,TokenType.INDEX_CACHE_IO.name())
            .claim(TOKEN_VERSION_CLAIM,scope.actor().tokenVersion()).claim("uid",scope.actor().userId().toString())
            .claim("org_id",scope.actor().orgId().toString()).claim("sid",scope.session().toString())
            .claim("purpose","VIEW_BUILD").claim("cache_kind",scope.kind()).claim("operation",scope.operation())
            .claim("dataset_id",scope.dataset().toString()).claim("version_id",scope.version().toString()).claim("index_version_id",scope.index().toString())
            .claim("index_repo_id",scope.indexRepo().toString()).claim("index_commit_id",scope.indexCommit().toString())
            .claim("grant_id",scope.grant().grantId().toString()).claim("grant_version",scope.grant().version());
        if(scope.originRun()!=null)token.claim("origin_run_id",scope.originRun().toString());
        return token.signWith(key).compact();
    }
    public Claims parseClaims(String token, TokenType expectedType) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String type = claims.get(TOKEN_TYPE_CLAIM, String.class);
        String subject = claims.getSubject();

        if (expectedType != null && (type == null || !expectedType.name().equals(type))) {
            log.warn("令牌类型不匹配: subject={}, expectedType={}, actualType={}",
                    subject, expectedType, type);
            throw ExceptionUtils.unauthorized("Token type mismatch: expected " + expectedType);
        }

        log.debug("成功解析令牌: subject={}, type={}, tokenVersion={}",
                subject, type, claims.get(TOKEN_VERSION_CLAIM, Integer.class));
        return claims;
    }

    /**
     * 从声明中提取令牌版本
     */
    public Integer extractTokenVersion(Claims claims) {
        return claims.get(TOKEN_VERSION_CLAIM, Integer.class);
    }

    /**
     * 获取访问令牌过期时间
     */
    public long getAccessExpirationSeconds() {
        return accessExpirationSeconds;
    }

    /**
     * 获取刷新令牌过期时间
     */
    public long getRefreshExpirationSeconds() {
        return refreshExpirationSeconds;
    }

    // 通用的令牌生成方法
    private String generateToken(String username, List<String> roles, Integer tokenVersion, TokenType tokenType,
                                 long expirationSeconds) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationSeconds);
        
        String token = Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(TOKEN_TYPE_CLAIM, tokenType.name())
                .claim(TOKEN_VERSION_CLAIM, tokenVersion == null ? 0 : tokenVersion)
                .claim("role", roles)
                .signWith(key) // HS256
                .compact();

        log.debug("令牌生成完成: username={}, tokenType={}, expiresAt={}",
                username, tokenType, exp);
        return token;
    }

    /** Durable session identity: subject name is descriptive, uid is authoritative. */
    public String generateSessionToken(java.util.UUID userId,java.util.UUID orgId,String username,Integer version,
            java.util.UUID session,java.util.UUID tokenId,long generation,TokenType type,long expirationSeconds) {
        if(!java.util.Set.of(TokenType.ACCESS,TokenType.REFRESH,TokenType.ACTIVATION).contains(type)||expirationSeconds<=0)throw new IllegalArgumentException("Invalid session token purpose");
        Instant now=Instant.now();
        return Jwts.builder().subject(username).id(tokenId.toString()).issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .claim("uid",userId.toString()).claim("org_id",orgId.toString()).claim("sid",session.toString())
                .claim("refresh_id",tokenId.toString()).claim("refresh_generation",generation)
                .claim(TOKEN_TYPE_CLAIM,type.name()).claim(TOKEN_VERSION_CLAIM,version)
                .signWith(key).compact();
    }
}
