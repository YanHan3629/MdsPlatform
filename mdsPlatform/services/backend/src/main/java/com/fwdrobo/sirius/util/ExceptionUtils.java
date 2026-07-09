package com.fwdrobo.sirius.util;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具类：用于生成常见的 ResponseStatusException
 */
@Slf4j
public final class ExceptionUtils {

    private ExceptionUtils() {
    }
    /**
     * 生成表示资源未找到的异常
     */
    public static ResponseStatusException notFound(String message) {
        log.debug("Not found: {}", message);
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
    /**
     * 生成表示资源冲突的异常
     */
    public static ResponseStatusException conflict(String message) {
        log.debug("Conflict: {}", message);
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
    /**
     * 生成表示请求参数错误的异常
     */
    public static IllegalArgumentException badRequest(String message) {
        log.debug("Bad request: {}", message);
        return new IllegalArgumentException(message);
    }
    /**
     * 生成表示未授权访问的异常
     */
    public static JwtException unauthorized(String message) {
        log.debug("Unauthorized: {}", message);
        return new JwtException(message);
    }

    /**
     * 生成表示权限不足的异常
     */
    public static ResponseStatusException forbidden(String message) {
        log.debug("Forbidden: {}", message);
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    /**
     * 兜底方案
     * 生成表示服务器内部错误的异常
     */
    public static RuntimeException internalError(String message) {
        log.warn("Internal error: {}", message);
        return new RuntimeException(message);
    }
}
