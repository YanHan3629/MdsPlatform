package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.artifact.*;
import com.fwdrobo.sirius.service.ArtifactCommitService;
import com.fwdrobo.sirius.service.ArtifactRepoService;
import com.fwdrobo.sirius.service.ArtifactTagService;
import com.fwdrobo.sirius.util.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Validated
@RestController
@RequestMapping("/api")
public class ArtifactController {

    private final ArtifactRepoService artifactRepoService;

    private final ArtifactCommitService artifactCommitService;

    private final ArtifactTagService artifactTagService;

    public ArtifactController(ArtifactRepoService artifactRepoService,
                              ArtifactCommitService artifactCommitService,
                              ArtifactTagService artifactTagService) {
        this.artifactRepoService = artifactRepoService;
        this.artifactCommitService = artifactCommitService;
        this.artifactTagService = artifactTagService;
    }

    // ------------------------------
    // Artifacts (ArtifactRepo)
    // ------------------------------

    /**
     * 创建 Artifact Repo
     * POST /api/artifacts
     */
    @PostMapping("/artifacts")
    public ArtifactRepoResponse createArtifact(@Valid @RequestBody ArtifactRepoReq request) {
        ArtifactRepoResponse response = artifactRepoService.create(request);
        return response;
    }

    @PatchMapping("/artifacts/{artifactId}")
    public Map<String, Object> updateArtifact(@PathVariable @Valid UUID artifactId, @Valid @RequestBody ArtifactRepoReq req) {
        log.debug("user:{} start to update job", SecurityUtils.getUserId());
        ArtifactRepoResponse response = artifactRepoService.updateArtifact(artifactId, req);
        return Map.of(
                "success", true,
                "message", "Artifact已更新",
                "artifactRepo", response
        );
    }

    /**
     * 根据 ID 获取 Artifact Repo
     * GET /api/artifacts/{artifactId}
     */
    @GetMapping("/artifacts/{artifactId}")
    public ArtifactRepoResponse getArtifact(@PathVariable("artifactId") UUID artifactId) {
        ArtifactRepoResponse response = artifactRepoService.getById(artifactId);
        return response;
    }

    /**
     * 根据 name 查询单个 Artifact Repo，或分页列出 Artifact Repos（支持排序、过滤、搜索）
     * GET /api/artifacts?repoName={repoName}  - 按名称查询单个 repo
     * GET /api/artifacts  - 分页列表
     * 返回结果包含每个 repo 的 published commit 数量和 draft commit 数量
     *
     * @param repoName 根据 repoName 精确查询（返回单个 repo）
     * @param page 页码（从 1 开始），默认 1
     * @param size 每页大小，默认 20，最大 100
     * @param sortBy 排序字段：repoName, createdAt, updatedAt, repoType，默认 createdAt
     * @param sortOrder 排序方向：ASC, DESC，默认 DESC
     * @param visibility 可见性过滤：PRIVATE, PUBLIC
     * @param search 名称或描述模糊搜索关键词
     * @param repoType repo 类型过滤
     * @param ownerUserId 所有者 ID 过滤
     * @param ownerUserName 所有者用户名过滤
     */
    @GetMapping("/artifacts")
    public Object listArtifacts(
            @RequestParam(required = false) String repoName,
            @Min(value = 1, message = "page 必须大于等于 1") @RequestParam(required = false) Integer page,
            @Min(value = 1, message = "size 必须大于等于 1") @Max(value = 100, message = "size 最大限制为 100") @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String repoType,
            @RequestParam(required = false) UUID ownerUserId,
            @RequestParam(required = false) String ownerUserName
    ) {
        // 如果提供了 name 参数，则按名称精确查询单个 repo
        if (repoName != null && !repoName.trim().isEmpty()) {
            return artifactRepoService.getByName(repoName.trim());
        }

        // 否则返回分页列表（支持动态过滤）
        ListArtifactReposQuery query = ListArtifactReposQuery.builder()
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .visibility(visibility)
                .searchKeyword(search)
                .repoType(repoType)
                .ownerUserId(ownerUserId)
                .ownerUserName(ownerUserName)
                .build();

        query.validate();  // 校验参数合法性，如果不合法会抛出 IllegalArgumentException

        return artifactRepoService.listAllPaged(query);
    }

    /**
     * 删除 Artifact Repo（级联删除其所有 commits；文件删除暂留 TODO）
     * DELETE /api/artifacts/{artifactId}
     */
    @DeleteMapping("/artifacts/{artifactId}")
    public Map<String, Object> deleteArtifact(@PathVariable("artifactId") UUID artifactId) {
        int deletedCommits = artifactRepoService.deleteRepoCascade(artifactId);
        return Map.of(
                "success", true,
                "message", "artifact repo deleted",
                "repoId", artifactId,
                "deletedCommits", deletedCommits
        );
    }

    /**
     * 查询 Artifact 下的可用标签。
     */
    @GetMapping("/artifacts/{artifactId}/tags")
    public ArtifactTagsResponse getArtifactTags(@PathVariable("artifactId") UUID artifactId) {
        return artifactTagService.getArtifactTags(artifactId);
    }

    // ------------------------------
    // Commits
    // ------------------------------

    /**
     * 在指定 artifact(repo) 下创建 commit（默认创建为 DRAFT）
     * POST /api/artifacts/{artifactId}/commits
     */
    @PostMapping("/artifacts/{artifactId}/commits")
    public ArtifactCommitResponse createCommit(
            @PathVariable("artifactId") UUID artifactId,
            @Valid @RequestBody CreateCommitReq request
    ) {
        ArtifactCommitResponse response = artifactCommitService.createCommit(artifactId, request);
        return response;
    }

    /**
     * 获取指定 artifact(repo) 下的所有 commit 记录（分页）
     * GET /api/artifacts/{artifactId}/commits
     *
     * @param artifactId repo ID
     * @param page 页码（从 1 开始），默认 1
     * @param size 每页大小，默认 20，最大 100
     * @param sortBy 排序字段：createdAt, updatedAt, publishedAt, commitStatus，默认 createdAt
     * @param sortOrder 排序方向：ASC, DESC，默认 DESC
     * @param commitStatus commit 状态过滤：DRAFT, PUBLISHED
     * @param commitSource commit 来源过滤（可选，任意字符串）
     */
    @GetMapping("/artifacts/{artifactId}/commits")
    public PageRes<ArtifactCommitResponse> listCommits(
            @PathVariable("artifactId") UUID artifactId,
            @Min(value = 1, message = "page 必须大于等于 1") @RequestParam(required = false) Integer page,
            @Min(value = 1, message = "size 必须大于等于 1") @Max(value = 100, message = "size 最大限制为 100") @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) String commitStatus,
            @RequestParam(required = false) String commitSource
    ) {
        ListCommitsQuery query = ListCommitsQuery.builder()
                .repoId(artifactId)
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortOrder(sortOrder)
                .commitStatus(commitStatus)
                .commitSource(commitSource)
                .build();

        query.validate();  // 校验参数合法性，如果不合法会抛出 IllegalArgumentException

        return artifactCommitService.listCommitsPaged(query);
    }

    /**
     * 获取 Artifact Repo 的 commit 数量
     * GET /api/artifacts/{artifactId}/commits/count
     *
     * @param artifactId repo ID
     * @param commitStatus 可选，过滤状态：DRAFT, PUBLISHED。不传则返回全部数量
     */
    @GetMapping("/artifacts/{artifactId}/commits/count")
    public Map<String, Object> getCommitCount(
            @PathVariable("artifactId") UUID artifactId,
            @RequestParam(required = false) String commitStatus
    ) {
        long count = artifactCommitService.getCommitCount(artifactId, commitStatus);
        return Map.of(
                "repoId", artifactId,
                "commitStatus", commitStatus != null ? commitStatus : "ALL",
                "count", count
        );
    }

    /**
     * 获取指定 commit 的详情
     * GET /api/artifacts/{artifactId}/commits/{commitId}
     */
    @GetMapping("/artifacts/{artifactId}/commits/{commitId}")
    public ArtifactCommitResponse getCommit(
            @PathVariable("artifactId") UUID artifactId,
            @PathVariable("commitId") UUID commitId
    ) {
        ArtifactCommitResponse response = artifactCommitService.getCommit(artifactId, commitId);
        return response;
    }

    /**
     * 发布 commit：仅允许从 DRAFT -> PUBLISHED
     * POST /api/artifacts/{artifactId}/commits/{commitId}/publish
     */
    @PostMapping("/artifacts/{artifactId}/commits/{commitId}/publish")
    public ArtifactCommitResponse publishCommit(
            @PathVariable("artifactId") UUID artifactId,
            @PathVariable("commitId") UUID commitId,
            @Valid @RequestBody PublishCommitReq request
    ) {
        ArtifactCommitResponse response = artifactCommitService.publishCommit(artifactId, commitId, request.getComment());
        return response;
    }

    /**
     * 获取最新已发布 commit（HEAD）
     * GET /api/artifacts/{artifactId}/commits/refs/head
     */
    @GetMapping("/artifacts/{artifactId}/commits/refs/head")
    public ArtifactCommitResponse getHead(@PathVariable("artifactId") UUID artifactId) {
        ArtifactCommitResponse response = artifactCommitService.getHead(artifactId);
        return response;
    }

    /**
     * 获取当前 DRAFT commit
     * GET /api/artifacts/{artifactId}/commits/refs/draft
     */
    @GetMapping("/artifacts/{artifactId}/commits/refs/draft")
    public ArtifactCommitResponse getDraft(@PathVariable("artifactId") UUID artifactId) {
        ArtifactCommitResponse response = artifactCommitService.getDraft(artifactId);
        return response;
    }

    /**
     * 获取或创建 DRAFT commit（幂等操作）
     * PUT /api/artifacts/{artifactId}/commits/refs/draft
     * 如果当前存在 draft commit，直接返回；否则创建一个新的 draft commit
     */
    @PutMapping("/artifacts/{artifactId}/commits/refs/draft")
    public ArtifactCommitResponse getOrCreateDraft(@PathVariable("artifactId") UUID artifactId
    ) {
        ArtifactCommitResponse response = artifactCommitService.getOrCreateDraft(artifactId);
        return response;
    }


    // todo: 支持更新 Artifact Repo 的元信息，比如 description、visibility 等等

    // todo: commitSource 还应当包含具体的上传者 ID，（ DeviceID ？ 或者 UserID ），需要记录一下。

    // todo: 考虑并发场景下的锁机制
}
