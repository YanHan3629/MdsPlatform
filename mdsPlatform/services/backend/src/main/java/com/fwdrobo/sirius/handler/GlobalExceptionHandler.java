package com.fwdrobo.sirius.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器，统一处理auth模块的各类异常
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理请求参数校验失败异常
     * 拦截 @Valid 注解校验失败的情况
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();
        StringBuilder messageBuilder = new StringBuilder();

        ex.getBindingResult().getFieldErrors().forEach(err -> {
            fieldErrors.put(err.getField(), err.getDefaultMessage());
            if (messageBuilder.length() > 0) {
                messageBuilder.append("; ");
            }
            messageBuilder.append(err.getField()).append(": ").append(err.getDefaultMessage());
        });

        return Map.of(
            "success", false,
            "errorType", "VALIDATION_ERROR",
            "message", messageBuilder.toString(),
            "errors", fieldErrors,
            "status", HttpStatus.BAD_REQUEST.value()
        );
    }

    /**
     * 处理方法级参数校验异常
     * 拦截 @RequestParam、@PathVariable 等注解校验失败的情况
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        StringBuilder messageBuilder = new StringBuilder();
        
        ex.getConstraintViolations().forEach(violation -> {
            String fieldName = violation.getPropertyPath().toString();
            String errorMsg = violation.getMessage();
            fieldErrors.put(fieldName, errorMsg);
            if (messageBuilder.length() > 0) {
                messageBuilder.append("; ");
            }
            messageBuilder.append(fieldName).append(": ").append(errorMsg);
        });
        
        return Map.of(
                "success", false,
                "errorType", "VALIDATION_ERROR",
                "message", messageBuilder.toString(),
                "errors", fieldErrors,
                "status", HttpStatus.BAD_REQUEST.value()
        );
    }

    /**
     * 处理缺失路径参数异常
     * 拦截 @PathVariable 注解缺失参数的情况
     */
    @ExceptionHandler(MissingPathVariableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMissingPathVariable(MissingPathVariableException ex) {
        return Map.of(
                "success", false,
                "message", "缺少路径参数: " + ex.getVariableName(),
                "status", HttpStatus.BAD_REQUEST.value(),
                "errorType", "MISSING_PATH_VARIABLE"
        );
    }

    /**
     * 处理类型转换异常
     * 拦截 @PathVariable 或 @RequestParam 等参数类型转换失败的情况（如无效的UUID格式）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String requiredType = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "Unknown";
        String providedValue = ex.getValue() != null ? ex.getValue().toString() : "null";

        String message = String.format("参数 '%s' 的值 '%s' 无法转换为所需的类型 %s",
                paramName, providedValue, requiredType);

        return Map.of(
                "success", false,
                "message", message,
                "status", HttpStatus.BAD_REQUEST.value(),
                "errorType", "INVALID_PARAMETER_TYPE"
        );
    }

    /**
     * 处理ResponseStatusException异常
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(ResponseStatusException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", ex.getReason() != null ? ex.getReason() : "请求处理失败");
        error.put("status", ex.getStatusCode().value());
        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }

    /**
     * 处理JWT过期异常
     */
    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<Map<String, Object>> handleExpiredJwtException(ExpiredJwtException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "登录已过期，请重新登录");
        error.put("status", HttpStatus.UNAUTHORIZED.value());
        error.put("errorType", "TOKEN_EXPIRED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * 处理JWT无效异常
     */
    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Map<String, Object>> handleJwtException(JwtException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "登录凭证无效，请重新登录");
        error.put("status", HttpStatus.UNAUTHORIZED.value());
        error.put("errorType", "TOKEN_INVALID");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * 处理访问拒绝异常（无权限）
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(AccessDeniedException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "权限不足，无法访问此资源");
        error.put("status", HttpStatus.FORBIDDEN.value());
        error.put("errorType", "ACCESS_DENIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * 处理认证异常
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationException(AuthenticationException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "认证失败：" + ex.getMessage());
        error.put("status", HttpStatus.UNAUTHORIZED.value());
        error.put("errorType", "AUTHENTICATION_FAILED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
    }

    /**
     * 处理IllegalArgumentException异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", ex.getMessage() != null ? ex.getMessage() : "请求参数错误");
        error.put("status", HttpStatus.BAD_REQUEST.value());
        error.put("errorType", "INVALID_ARGUMENT");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 处理IllegalStateException异常
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalStateException(IllegalStateException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "INTERNAL_STATE_ERROR");
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        error.put("errorType", "ILLEGAL_STATE");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * 处理JSON解析/序列化异常
     */
    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<Map<String, Object>> handleJsonProcessingException(JsonProcessingException ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "INVALID_JSON");
        error.put("status", HttpStatus.BAD_REQUEST.value());
        error.put("errorType", "JSON_PROCESSING_ERROR");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<Map<String,Object>> handleDatabaseConflict(Exception ex) {
        String code="AUTHORITY_UNAVAILABLE";int status=503;
        Throwable cause=ex;
        for(int depth=0;cause!=null&&depth<16;depth++,cause=cause.getCause()) {
            if(cause instanceof java.sql.SQLException sql) {
                String state=sql.getSQLState();String message=sql.getMessage();
                if(message!=null&&message.contains("LAST_PLATFORM_ADMIN_REQUIRED")){code="LAST_PLATFORM_ADMIN_REQUIRED";status=409;break;}
                if("23505".equals(state)){code="IDENTITY_OR_RESOURCE_CONFLICT";status=409;break;}
                if("23503".equals(state)||"23514".equals(state)){code="RESOURCE_STATE_CONFLICT";status=409;break;}
                if("40P01".equals(state)||"40001".equals(state)){code="CONCURRENT_IDENTITY_CHANGE_RETRY";status=409;break;}
            }
        }
        return ResponseEntity.status(status).body(Map.of("success",false,"errorType",code,"message",code,"status",status));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> unreadableBody(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("success",false,"errorType","INVALID_OR_DUPLICATE_JSON_FIELDS","status",400));
    }

    /**
     * 处理其他未捕获的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("message", "INTERNAL_ERROR");
        error.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        error.put("errorType", "INTERNAL_ERROR");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
