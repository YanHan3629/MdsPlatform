package com.fwdrobo.sirius.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Map<String,String> FIELDS = Map.ofEntries(
            Map.entry("userName", "账号"), Map.entry("username", "账号"),
            Map.entry("password", "密码"), Map.entry("newPassword", "新密码"),
            Map.entry("temporaryPassword", "临时密码"), Map.entry("name", "名称"),
            Map.entry("datasetId", "数据集"), Map.entry("versionId", "版本"),
            Map.entry("indexVersionId", "索引版本"), Map.entry("datasetName", "数据集名称"),
            Map.entry("spaceName", "空间名称"), Map.entry("versionName", "版本名称"),
            Map.entry("orgId", "所属企业"), Map.entry("question", "问题"),
            Map.entry("page", "页码"), Map.entry("size", "每页条数"),
            Map.entry("files", "文件"), Map.entry("images", "图片附件"));

    private static String validationReason(String code) {
        return switch (code == null ? "" : code) {
            case "NotNull", "NotBlank", "NotEmpty" -> "为必填项";
            case "Size", "Length" -> "长度或数量超出允许范围";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero" -> "数值超出允许范围";
            case "Pattern", "Email" -> "格式不正确";
            case "Past", "PastOrPresent", "Future", "FutureOrPresent" -> "时间超出允许范围";
            default -> "取值无效";
        };
    }

    private static String fieldLabel(String path) {
        String field = path.substring(path.lastIndexOf('.') + 1).replaceFirst("\\[.*", "");
        return FIELDS.getOrDefault(field, "字段");
    }

    private ResponseEntity<Map<String,Object>> validation(Map<String,String> fields) {
        var message = fields.entrySet().stream()
                .map(entry -> fieldLabel(entry.getKey()) + entry.getValue())
                .distinct().reduce((a, b) -> a + "，" + b).orElse("请检查填报内容") + "。";
        Map<String,Object> body = new LinkedHashMap<>(ApiErrors.body(400, "VALIDATION_ERROR", message));
        body.put("errors", fields);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String,Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String,String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> fields.put(err.getField(), validationReason(err.getCode())));
        return validation(fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String,Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String,String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(err -> fields.put(err.getPropertyPath().toString(),
                validationReason(err.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())));
        return validation(fields);
    }

    private ResponseEntity<Map<String,Object>> failure(int status, String code, String message) {
        return ResponseEntity.status(status).body(ApiErrors.body(status, code, message));
    }

    @ExceptionHandler(MissingPathVariableException.class)
    public ResponseEntity<Map<String,Object>> handleMissingPath(MissingPathVariableException ex) {
        return failure(400, "MISSING_PATH_VARIABLE", "缺少" + fieldLabel(ex.getVariableName()) + "，请返回页面重新选择。");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String,Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        return failure(400, "MISSING_PARAMETER", "缺少" + fieldLabel(ex.getParameterName()) + "，请填写后重试。");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String,Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return failure(400, "INVALID_PARAMETER_TYPE", fieldLabel(ex.getName()) + "格式不正确，请修改后重试。");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> handleResponseStatusException(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason();
        String code = reason != null && reason.matches("[A-Z][A-Z0-9_]{2,95}") ? reason : "HTTP_" + status;
        // Preserve application-level validation guidance. Hidden scopes and internal failures
        // have a uniform description regardless of the object or upstream response.
        String detail = reason != null && status >= 400 && status < 500 && status != 401 && status != 403 && status != 404
                && reason.length() <= 300 && reason.matches("(?s).*\\p{IsHan}.*")
                && !reason.matches("(?is).*(?:exception|<html|\\r|\\n|\\\\).*") ? reason : null;
        Map<String,Object> body = ApiErrors.body(status, code, detail);
        if (status >= 500) LOG.warn("Request {} failed with {}", body.get("requestId"), code);
        return ResponseEntity.status(status).headers(ex.getHeaders()).body(body);
    }

    @ExceptionHandler(ExpiredJwtException.class)
    public ResponseEntity<Map<String,Object>> handleExpiredJwtException() {
        return failure(401, "TOKEN_EXPIRED", null);
    }

    @ExceptionHandler(JwtException.class)
    public ResponseEntity<Map<String,Object>> handleJwtException() {
        return failure(401, "TOKEN_INVALID", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String,Object>> handleAccessDeniedException() {
        return failure(403, "ACCESS_DENIED", null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String,Object>> handleAuthenticationException() {
        return failure(401, "AUTHENTICATION_FAILED", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> handleIllegalArgumentException() {
        return failure(400, "INVALID_ARGUMENT", null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String,Object>> handleIllegalStateException(IllegalStateException ex) {
        return internalError("ILLEGAL_STATE", ex);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,Object>> unreadableBody() {
        return failure(400, "INVALID_OR_DUPLICATE_JSON_FIELDS", "请求格式不正确或含重复字段，请检查后重试。");
    }

    @ExceptionHandler(JsonProcessingException.class)
    public ResponseEntity<Map<String,Object>> handleJsonProcessingException() {
        return failure(400, "JSON_PROCESSING_ERROR", "请求内容格式不正确，请检查后重试。");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String,Object>> handleTooLarge() {
        return failure(413, "UPLOAD_TOO_LARGE", null);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String,Object>> handleMissingPart() {
        return failure(400, "MISSING_REQUEST_PART", "缺少文件或表单内容，请确认已选文件后重试。");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> handleMediaType() {
        return failure(415, "UNSUPPORTED_MEDIA_TYPE", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String,Object>> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(405).headers(ex.getHeaders()).body(ApiErrors.body(405, "METHOD_NOT_SUPPORTED", null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String,Object>> handleNotFound() {
        return failure(404, "RESOURCE_NOT_AVAILABLE", null);
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<Map<String,Object>> handleDatabaseConflict(Exception ex) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (!(cause instanceof SQLException sql)) continue;
            String message = sql.getMessage();
            if (message != null && message.contains("LAST_PLATFORM_ADMIN_REQUIRED"))
                return failure(409, "LAST_PLATFORM_ADMIN_REQUIRED", null);
            String code = switch (String.valueOf(sql.getSQLState())) {
                case "23505" -> "IDENTITY_OR_RESOURCE_CONFLICT";
                case "23503", "23514" -> "RESOURCE_STATE_CONFLICT";
                case "40P01", "40001" -> "CONCURRENT_IDENTITY_CHANGE_RETRY";
                default -> null;
            };
            if (code != null) return failure(409, code, null);
        }
        var body = ApiErrors.body(503, "AUTHORITY_UNAVAILABLE", null);
        LOG.error("Request {} failed to access database", body.get("requestId"), ex);
        return ResponseEntity.status(503).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> handleGenericException(Exception ex) {
        return internalError("INTERNAL_ERROR", ex);
    }

    private ResponseEntity<Map<String,Object>> internalError(String code, Exception ex) {
        var body = ApiErrors.body(500, code, null);
        LOG.error("Request {} failed with {}", body.get("requestId"), code, ex);
        return ResponseEntity.status(500).body(body);
    }
}
