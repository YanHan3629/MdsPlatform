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

@Slf4j
@Service
public class JwtService {

    public enum TokenType {
        ACCESS, REFRESH
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
            @Value("${app.jwt.secret}") String secret,
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

    /**
     * 解析并验证令牌，返回声明
     */
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
}
