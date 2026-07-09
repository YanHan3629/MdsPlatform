package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 权限拒绝处理器，统一返回 403 JSON 响应。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * 构造权限拒绝处理器。
     */
    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 输出统一的权限不足响应。
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "权限不足，无法访问此资源");
        error.put("status", HttpStatus.FORBIDDEN.value());
        error.put("errorType", "ACCESS_DENIED");

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
