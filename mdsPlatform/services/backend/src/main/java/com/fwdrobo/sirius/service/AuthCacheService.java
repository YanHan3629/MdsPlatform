package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.entity.FwdUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class AuthCacheService {
    // Redis 前缀键
    private static final String ACCESS_TOKEN_KEY_PREFIX = "auth:token:access:";
    private static final String REFRESH_TOKEN_KEY_PREFIX = "auth:token:refresh:";
    private static final String USER_KEY_PREFIX = "auth:user:";
    private static final String USER_ID_KEY_PREFIX = "auth:user-id:";
    private static final String USER_TOKENS_KEY_PREFIX = "auth:user-tokens:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long userCacheTtlSeconds;

    public AuthCacheService(StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper,
                            @Value("${app.auth-cache.user-ttl-seconds:900}") long userCacheTtlSeconds) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.userCacheTtlSeconds = userCacheTtlSeconds;
    }

    /**
     * 缓存用户信息
     */
    public void cacheUser(FwdUser user) {
        if (user == null || !StringUtils.hasText(user.getUserName())) {
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(user);
            redisTemplate.opsForValue().set(userKey(user.getUserName()), payload, Duration.ofSeconds(userCacheTtlSeconds));
            if (user.getUserId() != null) {
                redisTemplate.opsForValue().set(userIdKey(user.getUserId()), payload, Duration.ofSeconds(userCacheTtlSeconds));
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize user for cache: {}", user.getUserName(), e);
        }
    }

    /**
     * 获取缓存的用户信息
     */
    public FwdUser getCachedUser(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        String json = redisTemplate.opsForValue().get(userKey(username));
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FwdUser.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached user: {}", username, e);
            return null;
        }
    }

    /**
     * 根据用户ID获取缓存的用户信息
     */
    public FwdUser getCachedUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        String json = redisTemplate.opsForValue().get(userIdKey(userId));
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FwdUser.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached user by id: {}", userId, e);
            return null;
        }
    }

    /**
     * 缓存访问令牌和刷新令牌
     */
    public void cacheTokens(UUID userId, String accessToken, long accessTtlSeconds, String refreshToken, long refreshTtlSeconds) {
        if (userId == null) {
            return;
        }
        String idString = userId.toString();
        String userTokensKey = userTokensKey(userId);

        if (StringUtils.hasText(accessToken)) {
            redisTemplate.opsForValue().set(ACCESS_TOKEN_KEY_PREFIX + accessToken, idString, Duration.ofSeconds(accessTtlSeconds));
            redisTemplate.opsForSet().add(userTokensKey, "ACCESS:" + accessToken);
        }
        if (StringUtils.hasText(refreshToken)) {
            redisTemplate.opsForValue().set(REFRESH_TOKEN_KEY_PREFIX + refreshToken, idString, Duration.ofSeconds(refreshTtlSeconds));
            redisTemplate.opsForSet().add(userTokensKey, "REFRESH:" + refreshToken);
        }

        long maxTtl = Math.max(accessTtlSeconds, refreshTtlSeconds);
        if (maxTtl > 0) {
            redisTemplate.expire(userTokensKey, Duration.ofSeconds(maxTtl));
        }
    }

    /**
     * 根据refreshToken获取用户ID
     */
    public UUID getUserIdByRefreshToken(String token) {
        return getUserId(token, REFRESH_TOKEN_KEY_PREFIX);
    }

    /**
     * 清除缓存的用户信息
     */
    public void evictUser(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        FwdUser cached = getCachedUser(username);
        redisTemplate.delete(userKey(username));
        if (cached != null && cached.getUserId() != null) {
            redisTemplate.delete(userIdKey(cached.getUserId()));
        }
        log.info("Evicted cached user: {}", username);
    }

    /**
     * 清除指定用户的所有token缓存
     */
    public void evictTokensForUser(UUID userId) {
        if (userId == null) {
            return;
        }
        log.info("Evicting all tokens for userId: {}", userId);
        String userTokensKey = userTokensKey(userId);
        Set<String> tokens = redisTemplate.opsForSet().members(userTokensKey);
        if (tokens != null) {
            for (String entry : tokens) {
                if (!StringUtils.hasText(entry)) {
                    continue;
                }
                if (entry.startsWith("ACCESS:")) {
                    redisTemplate.delete(ACCESS_TOKEN_KEY_PREFIX + entry.substring("ACCESS:".length()));
                } else if (entry.startsWith("REFRESH:")) {
                    redisTemplate.delete(REFRESH_TOKEN_KEY_PREFIX + entry.substring("REFRESH:".length()));
                }
            }
        }
        redisTemplate.delete(userTokensKey);
        log.info("Evicted all tokens for userId: {}", userId);
    }

    /**
     * 清除所有认证相关缓存（用户和令牌）
     */
    public void clearAllAuthCaches() {
        deleteByPattern(USER_KEY_PREFIX + "*");
        deleteByPattern(USER_ID_KEY_PREFIX + "*");
        deleteByPattern(ACCESS_TOKEN_KEY_PREFIX + "*");
        deleteByPattern(REFRESH_TOKEN_KEY_PREFIX + "*");
        deleteByPattern(USER_TOKENS_KEY_PREFIX + "*");
        log.info("Cleared all authentication caches");
    }

    // 辅助方法构建Redis键
    private String userKey(String username) {
        return USER_KEY_PREFIX + username;
    }

    // 通过用户ID缓存的键
    private String userIdKey(UUID userId) {
        return USER_ID_KEY_PREFIX + userId;
    }

    // 用户令牌集合键
    private String userTokensKey(UUID userId) {
        return USER_TOKENS_KEY_PREFIX + userId;
    }

    // 根据模式删除键
    private void deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // 根据token前缀获取用户ID
    private UUID getUserId(String token, String prefix) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(prefix + token);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid user id in redis for token");
            return null;
        }
    }
}
