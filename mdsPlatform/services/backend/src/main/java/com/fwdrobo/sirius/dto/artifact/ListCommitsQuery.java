package com.fwdrobo.sirius.dto.artifact;

import lombok.Builder;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 查询 Artifact Commits 列表的参数
 */
@Getter
@Builder
public class ListCommitsQuery {

    // 允许的排序字段
    private static final List<String> VALID_SORT_FIELDS = Arrays.asList(
        "createdAt", "created_at", "updatedAt", "updated_at", 
        "publishedAt", "published_at", "commitStatus", "commit_status"
    );

    // 允许的排序方向
    private static final List<String> VALID_SORT_ORDERS = Arrays.asList("ASC", "DESC");

    // 允许的 commit 状态值
    private static final List<String> VALID_COMMIT_STATUSES = Arrays.asList("DRAFT", "PUBLISHED");

    private UUID repoId;  // repo ID（必填）
    private Integer page;  // 页码（0-based）
    private Integer size;  // 每页大小
    private String sortBy;  // 排序字段
    private String sortOrder;  // 排序方向
    private String commitStatus;  // commitStatus 过滤：DRAFT, PUBLISHED
    private String commitSource;  // commitSource 过滤（可选，任意字符串）

    /**
     * 获取规范化的 commitStatus 值（大写），如果为空则返回 null
     * 用于 MyBatis 查询
     */
    public String getCommitStatusOrNull() {
        if (commitStatus == null || commitStatus.trim().isEmpty()) {
            return null;
        }
        return commitStatus.trim().toUpperCase();
    }

    /**
     * 获取规范化的 commitSource 值（大写），如果为空则返回 null
     */
    public String getCommitSourceOrNull() {
        if (commitSource == null || commitSource.trim().isEmpty()) {
            return null;
        }
        return commitSource.trim().toUpperCase();
    }

    /**
     * 获取排序字段（带有默认值和白名单校验）
     */
    public String getSortByOrDefault() {
        if (sortBy == null || sortBy.trim().isEmpty()) {
            return "created_at";
        }
        // 白名单校验，防止 SQL 注入
        return switch (sortBy.toLowerCase()) {
            case "createdat", "created_at" -> "created_at";
            case "updatedat", "updated_at" -> "updated_at";
            case "publishedat", "published_at" -> "published_at";
            case "commitstatus", "commit_status" -> "commit_status";
            default -> "created_at";
        };
    }

    /**
     * 获取排序方向（带有默认值和白名单校验）
     */
    public String getSortOrderOrDefault() {
        if (sortOrder == null || sortOrder.trim().isEmpty()) {
            return "DESC";
        }
        return sortOrder.equalsIgnoreCase("ASC") ? "ASC" : "DESC";
    }

    /**
     * 获取页码（带默认值，1-based）
     * 对外接口使用 1-based（第1页、第2页...）
     */
    public int getPageOrDefault() {
        return page != null && page >= 1 ? page : 1;
    }

    /**
     * 获取每页大小（带默认值和最大值限制）
     */
    public int getSizeOrDefault() {
        if (size == null || size <= 0) {
            return 20; // 默认 20
        }
        return Math.min(size, 100); // 最大 100
    }

    /**
     * 计算 SQL OFFSET（转换为 0-based）
     */
    public int getOffset() {
        return (getPageOrDefault() - 1) * getSizeOrDefault();
    }

    /**
     * 计算 SQL LIMIT
     */
    public int getLimit() {
        return getSizeOrDefault();
    }

    /**
     * 校验参数合法性
     * @throws IllegalArgumentException 参数不合法时抛出异常，包含详细错误信息
     */
    public void validate() {
        // 校验 sortBy
        if (sortBy != null && !sortBy.trim().isEmpty()
            && VALID_SORT_FIELDS.stream().noneMatch(f -> f.equalsIgnoreCase(sortBy))) {
            throw new IllegalArgumentException(
                String.format("Invalid sortBy parameter: '%s'. Valid values are: %s",
                    sortBy, String.join(", ", VALID_SORT_FIELDS))
            );
        }

        // 校验 sortOrder
        if (sortOrder != null && !sortOrder.trim().isEmpty()
            && VALID_SORT_ORDERS.stream().noneMatch(o -> o.equalsIgnoreCase(sortOrder))) {
            throw new IllegalArgumentException(
                String.format("Invalid sortOrder parameter: '%s'. Valid values are: %s",
                    sortOrder, String.join(", ", VALID_SORT_ORDERS))
            );
        }

        // 校验 commitStatus
        if (commitStatus != null && !commitStatus.trim().isEmpty()
            && VALID_COMMIT_STATUSES.stream().noneMatch(s -> s.equalsIgnoreCase(commitStatus))) {
            throw new IllegalArgumentException(
                String.format("Invalid commitStatus parameter: '%s'. Valid values are: %s",
                    commitStatus, String.join(", ", VALID_COMMIT_STATUSES))
            );
        }

        // commitSource 不再进行枚举值验证，允许任意字符串
    }
}
