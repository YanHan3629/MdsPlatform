package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 未认证入口处理器，统一返回 401 JSON 响应。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String JWT_EXCEPTION_ATTR = "jwt_exception";
    private final ObjectMapper objectMapper;

    /**
     * 构造未认证入口处理器。
     */
    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 输出统一的未认证响应。
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        JwtException jwtException = resolveJwtException(request);
        Map<String, Object> errorBody = buildUnauthorizedBody(request, jwtException);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(errorBody));
    }

    /**
     * 从请求上下文读取 JWT 异常对象。
     */
    private JwtException resolveJwtException(HttpServletRequest request) {
        Object exception = request.getAttribute(JWT_EXCEPTION_ATTR);
        if (exception instanceof JwtException jwtException) {
            return jwtException;
        }
        return null;
    }

    /**
     * 构建未认证返回体，区分缺失、无效、过期令牌。
     */
    private Map<String, Object> buildUnauthorizedBody(HttpServletRequest request, JwtException jwtException) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("status", HttpStatus.UNAUTHORIZED.value());

        if (jwtException instanceof ExpiredJwtException) {
            error.put("message", "登录已过期，请重新登录");
            error.put("errorType", "TOKEN_EXPIRED");
            return error;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (jwtException == null && !StringUtils.hasText(authHeader)) {
            error.put("message", "未提供登录凭证，请先登录");
            error.put("errorType", "TOKEN_MISSING");
            return error;
        }

        error.put("message", "登录凭证无效，请重新登录");
        error.put("errorType", "TOKEN_INVALID");
        return error;
    }
}
