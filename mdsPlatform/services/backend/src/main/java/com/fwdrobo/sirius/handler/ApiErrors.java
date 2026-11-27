package com.fwdrobo.sirius.handler;

import java.util.Map;
import java.util.UUID;

/** Stable protocol codes paired with user-facing messages. */
public final class ApiErrors {
    private ApiErrors() {}

    public static String message(int status, String code) {
        if (code != null) {
            String specific = switch (code) {
                case "TOKEN_EXPIRED", "ACCESS_REVOKED_OR_EXPIRED" -> "登录或授权已失效，请重新登录并核对授权。";
                case "IDENTITY_REVIEW_REQUIRED" -> "请先审阅变更影响，再确认提交。";
                case "IDENTITY_REVIEW_STALE", "IDENTITY_REVIEW_EXPIRED", "IDENTITY_REVIEW_CLOSED",
                     "IDENTITY_REVIEW_REQUEST_CHANGED" -> "本次审阅已失效，请重新审阅。";
                case "LAST_PLATFORM_ADMIN_REQUIRED" -> "请先新增并验证接任管理员，平台至少保留一名可登录的管理员。";
                case "IDENTITY_OR_RESOURCE_CONFLICT" -> "该名称或记录已存在，请刷新列表后核对。";
                case "CONCURRENT_IDENTITY_CHANGE_RETRY", "IDENTITY_VERSION_CONFLICT", "EXPECTED_VERSION_REQUIRED" ->
                    "记录已更新，请刷新后重新操作。";
                case "AUTHORIZATION_CHANGED_DURING_OUTPUT" -> "处理期间授权已变化，请重新核对权限。";
                case "AUTHORIZATION_MAINTENANCE_MODE" -> "平台正在维护，请稍后重试。";
                case "AMBIGUOUS_QA_INPUT_MODE", "INVALID_QA_INPUT_MODE" -> "问答输入模式无效，请重新选择。";
                case "USER_INPUT_CORPUS_SCOPE_NOT_ALLOWED", "USER_INPUT_QUERY_PLAN_NOT_ALLOWED" ->
                    "仅附件模式请清除数据空间范围和检索条件。";
                case "QA_QUEUE_FULL" -> "当前问答队列已满，请稍后重新提交。";
                case "QA_REQUEST_ALREADY_RUNNING" -> "该问答仍在处理，可等待或取消。";
                case "QA_USER_CHARACTER_LIMIT", "QA_USER_TOKEN_LIMIT" -> "问题及补充文本超过长度限制，请缩短后重试。";
                case "QA_IMAGE_BYTES", "QA_IMAGE_COUNT" -> "图片的总大小或数量超过限制，请减少附件后重试。";
                case "WORKER_SCOPE_REVOKED_OR_UNAVAILABLE" -> "处理期间选定范围或授权已失效，请刷新后重新选择。";
                default -> null;
            };
            if (specific != null) return specific;
        }
        return switch (status) {
            case 400 -> "请核对必填项、格式与取值范围后重新提交。";
            case 401 -> "登录凭证缺失或已失效，请重新登录。";
            case 403, 404 -> "当前账号无法访问此资源，请核对授权或返回列表。";
            case 405 -> "此操作方式不适用，请返回页面重新提交。";
            case 408, 504 -> "处理超时，请缩小范围或稍后重试。";
            case 409, 412, 428 -> "记录或授权已变化，请刷新后重试。";
            case 410 -> "该链接或授权已到期，请重新获取。";
            case 413 -> "文件或请求超出大小限制，请缩小后重试。";
            case 415 -> "当前格式不受支持，请核对允许的格式。";
            case 422 -> "资料无法解析，请检查文件内容或选择其他版本。";
            case 429 -> "当前请求较多，请稍后重试。";
            case 502, 503 -> "服务暂时不可用，请稍后重试。";
            default -> "处理请求时出现问题，请稍后重试或将请求编号提供给维护人员。";
        };
    }

    public static Map<String,Object> body(int status, String code, String message) {
        return Map.of("success", false, "errorType", code, "status", status,
                "message", message == null ? message(status, code) : message,
                "requestId", UUID.randomUUID().toString());
    }
}
