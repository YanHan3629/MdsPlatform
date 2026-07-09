package com.fwdrobo.sirius.dto.artifact;

import lombok.Builder;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 查询 Artifact Repos 列表的参数
 */
@Getter
@Builder
public class ListArtifactReposQuery {

    // 允许的排序字段
    private static final List<String> VALID_SORT_FIELDS = Arrays.asList(
        "repoName", "repo_name", "createdAt", "created_at",
        "updatedAt", "updated_at", "repoType", "repo_type"
    );

    // 允许的排序方向
    private static final List<String> VALID_SORT_ORDERS = Arrays.asList("ASC", "DESC");

    // 允许的可见性值
    private static final List<String> VALID_VISIBILITIES = Arrays.asList("PRIVATE", "INTERNAL", "PUBLIC");

    private Integer page;  // 页码（0-based）
    private Integer size;  // 每页大小
    private String sortBy;  // 排序字段
    private String sortOrder;  // 排序方向
    private String visibility;  // visibility 过滤
    private String searchKeyword;  // repoName 或 description 模糊搜索
    private String repoType;  // repoType 过滤
    private UUID ownerUserId;  // ownerUserId 过滤
    private String ownerUserName;  // ownerUserName 过滤

    /**
     * 获取规范化的 visibility 值（大写），如果为空则返回 null
     * 用于 MyBatis 查询
     */
    public String getVisibilityOrNull() {
        if (visibility == null || visibility.trim().isEmpty()) {
            return null;
        }
        return visibility.trim().toUpperCase();
    }
    
    /**
     * 获取规范化的 repoType 值，如果为空则返回 null
     */
    public String getRepoTypeOrNull() {
        if (repoType == null || repoType.trim().isEmpty()) {
            return null;
        }
        return repoType.trim();
    }
    
    /**
     * 获取 ownerUserId，如果为空则返回 null
     */
    public UUID getOwnerUserIdOrNull() {
        return ownerUserId;
    }
    
    /**
     * 获取规范化的 ownerUserName 值，如果为空则返回 null
     */
    public String getOwnerUserNameOrNull() {
        if (ownerUserName == null || ownerUserName.trim().isEmpty()) {
            return null;
        }
        return ownerUserName.trim();
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
            case "reponame", "repo_name" -> "repo_name";
            case "createdat", "created_at" -> "created_at";
            case "updatedat", "updated_at" -> "updated_at";
            case "repotype", "repo_type" -> "repo_type";
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

        // 校验 visibility
        if (visibility != null && !visibility.trim().isEmpty()
            && VALID_VISIBILITIES.stream().noneMatch(v -> v.equalsIgnoreCase(visibility))) {
            throw new IllegalArgumentException(
                String.format("Invalid visibility parameter: '%s'. Valid values are: %s",
                    visibility, String.join(", ", VALID_VISIBILITIES))
            );
        }
    }
}
