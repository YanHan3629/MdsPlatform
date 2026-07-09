package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.artifact.ArtifactCommitResponse;
import com.fwdrobo.sirius.dto.artifact.CreateCommitReq;
import com.fwdrobo.sirius.dto.artifact.ListCommitsQuery;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.artifact.ArtifactCommit;
import com.fwdrobo.sirius.entity.artifact.ArtifactRepo;
import com.fwdrobo.sirius.entity.artifact.CommitStatus;
import com.fwdrobo.sirius.mapper.ArtifactCommitMapper;
import com.fwdrobo.sirius.mapper.ArtifactRepoMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Artifact Commit 相关能力：
 * 1. 在指定 repo 下创建 commit（默认创建为 DRAFT）
 * 2. 发布 commit（从 DRAFT -> PUBLISHED）
 * 3. 查询 repo 下 commit 列表、head、draft
 */
@Slf4j
@Service
public class ArtifactCommitService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ArtifactRepoMapper artifactRepoMapper;
    private final ArtifactCommitMapper artifactCommitMapper;
    private final ManifestService manifestService;
    private final UserService userService;

    public ArtifactCommitService(
            ArtifactRepoMapper artifactRepoMapper, 
            ArtifactCommitMapper artifactCommitMapper,
            ManifestService manifestService,
            UserService userService
    ) {
        this.artifactRepoMapper = artifactRepoMapper;
        this.artifactCommitMapper = artifactCommitMapper;
        this.manifestService = manifestService;
        this.userService = userService;
    }

    /**
     * 创建 Commit（默认 DRAFT）
     */
    @Transactional
    public ArtifactCommitResponse createCommit(UUID repoId, @Valid CreateCommitReq request) {
        log.info("开始创建 commit: repoId={}, commitSource={}", repoId, request.getCommitSource());
        
        // 确认 repo 存在
        ensureRepoExists(repoId);

        // 约束：同一个 repo 在同一时间只允许存在一个 draft commit
        ArtifactCommit existingDraft = artifactCommitMapper.selectDraftByRepoId(repoId);
        if (existingDraft != null) {
            log.debug("repo 下还有 DRAFT 状态的 commit 未提交: repoId={}, draftCommitId={}", repoId, existingDraft.getCommitId());
            throw ExceptionUtils.conflict("repo 下还有 DRAFT 状态的 commit 未提交, commitId = " + existingDraft.getCommitId());
        }

        UUID createdUserId = SecurityUtils.getUserId();  
        
        JsonNode meta = request.getMeta() == null ? MAPPER.createObjectNode() : request.getMeta();

        ArtifactCommit commit = new ArtifactCommit();
        commit.setCommitId(UUID.randomUUID());
        commit.setRepoId(repoId);
        commit.setCommitStatus(CommitStatus.DRAFT);
        commit.setCommitSource(request.getCommitSource());
        commit.setCreatedBy(createdUserId);
        commit.setComment(request.getComment());
        commit.setMeta(meta);

        artifactCommitMapper.insert(commit);
        log.debug("插入 commit 到数据库: repoId={}, commitId={}, commitSource={}", repoId, commit.getCommitId(), commit.getCommitSource());

        // 获取父 commit ID（最新的已发布 commit）
        ArtifactCommit parentCommit = artifactCommitMapper.selectLatestPublishedByRepoId(repoId);
        UUID parentCommitId = (parentCommit != null) ? parentCommit.getCommitId() : null;

        // 初始化 manifest 文件
        try {
            manifestService.initDraft(repoId, commit.getCommitId(), parentCommitId);
            log.debug("初始化 manifest: repoId={}, commitId={}, parentCommitId={}", repoId, commit.getCommitId(), parentCommitId);
        } catch (Exception e) {
            log.error("初始化 manifest 失败: repoId={}, commitId={}", repoId, commit.getCommitId(), e);
            throw ExceptionUtils.internalError("初始化 manifest 失败: " + e.getMessage());
        }

        ArtifactCommit created = artifactCommitMapper.selectByRepoAndCommit(repoId, commit.getCommitId());
        if (created == null) {
            log.error("创建后无法查询到 commit: repoId={}, commitId={}, 存在数据库一致性问题", repoId, commit.getCommitId());
            throw ExceptionUtils.internalError("未能获取到创建的 commit 信息, commitId: " + commit.getCommitId() + "存在数据库一致性问题。");
        }

        log.info("成功创建 commit: repoId={}, commitId={}, commitSource={}", repoId, created.getCommitId(), created.getCommitSource());
        return toResponse(created);
    }

    /**
     * 查询 repo 下所有 commits
     */
    public List<ArtifactCommitResponse> listCommits(UUID repoId) {
        log.info("查询 commit 列表: repoId={}", repoId);
        
        ensureRepoExists(repoId);

        List<ArtifactCommitResponse> commits = artifactCommitMapper
            .selectByRepoId(repoId)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        log.info("查询到 {} 个 commit: repoId={}", commits.size(), repoId);
        return commits;
    }

    /**
     * 分页查询 repo 下的 commits（支持按状态、来源过滤和排序）
     */
    public PageRes<ArtifactCommitResponse> listCommitsPaged(ListCommitsQuery query) {
        log.info("分页查询 commit 列表: repoId={}, page={}, size={}, sortBy={}, sortOrder={}, commitStatus={}, commitSource={}", 
            query.getRepoId(), query.getPageOrDefault(), query.getSizeOrDefault(), 
            query.getSortByOrDefault(), query.getSortOrderOrDefault(),
            query.getCommitStatusOrNull(), query.getCommitSourceOrNull());
        
        ensureRepoExists(query.getRepoId());

        // 查询总数
        long totalCount = artifactCommitMapper.countByRepoId(query);
        log.debug("查询到 commit 总数: repoId={}, totalCount={}", query.getRepoId(), totalCount);

        // 如果没有数据，直接返回空结果
        if (totalCount == 0) {
            return new PageRes<>(0, 0, query.getPageOrDefault(), query.getSizeOrDefault(), List.of());
        }

        // 计算总页数
        int totalPages = (int) Math.ceil((double) totalCount / query.getSizeOrDefault());

        // 检查页码是否越界（1-based）
        if (query.getPageOrDefault() > totalPages) {
            log.warn("请求的页码超出范围: page={}, totalPages={}, totalCount={}",
                    query.getPageOrDefault(), totalPages, totalCount);
            throw ExceptionUtils.badRequest(
                String.format("页码超出范围：请求第 %d 页，但总共只有 %d 页", 
                    query.getPageOrDefault(), totalPages)
            );
        } else if (query.getPageOrDefault() < 1) {
            log.warn("请求的页码小于1: page={}", query.getPageOrDefault());
            throw ExceptionUtils.badRequest("页码必须大于等于 1");
        }

        // 分页查询数据
        List<ArtifactCommitResponse> commits = artifactCommitMapper
            .selectByRepoIdPaged(query)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());

        log.info("成功查询 commit 列表: repoId={}, page={}, size={}, totalCount={}, totalPages={}", 
            query.getRepoId(), query.getPageOrDefault(), query.getSizeOrDefault(), totalCount, totalPages);

        return new PageRes<>(
            totalCount,
            totalPages,
            query.getPageOrDefault(),
            query.getSizeOrDefault(),
            commits
        );
    }

    /**
     * 查询指定 commit
     */
    public ArtifactCommitResponse getCommit(UUID repoId, UUID commitId) {
        log.info("查询指定 commit: repoId={}, commitId={}", repoId, commitId);

        ensureRepoExists(repoId);

        ArtifactCommit commit = artifactCommitMapper.selectByRepoAndCommit(repoId, commitId);
        log.debug("查询结果: repoId={}, commitId={}, commit={}", repoId, commitId, commit);

        if (commit == null) {
            log.debug("未找到指定的 commit: repoId={}, commitId={}", repoId, commitId);
            throw ExceptionUtils.notFound("commit not found: " + commitId);
        }
        
        log.info("成功查询到指定 commit: repoId={}, commitId={}", repoId, commitId);
        return toResponse(commit);
    }

    /**
     * 发布 commit：仅允许从 DRAFT -> PUBLISHED
     */
    @Transactional
    public ArtifactCommitResponse publishCommit(UUID repoId, UUID commitId, String comment) {
        log.info("发布 commit: repoId={}, commitId={}, comment={}", repoId, commitId, comment);

        ensureRepoExists(repoId);

        ArtifactCommit commit = artifactCommitMapper.selectByRepoAndCommit(repoId, commitId);
        if (commit == null) {
            log.debug("未找到指定的 commit: repoId={}, commitId={}", repoId, commitId);
            throw ExceptionUtils.notFound("commit not found: " + commitId);
        }

        if (commit.getCommitStatus() != CommitStatus.DRAFT) {
            log.debug("只能发布 DRAFT 状态的 commit: repoId={}, commitId={}, currentStatus={}", repoId, commitId, commit.getCommitStatus());
            throw ExceptionUtils.conflict("only DRAFT commit can be published, current status=" + commit.getCommitStatus());
        }

        if (comment == null || comment.trim().isEmpty()) {
            log.debug("发布 commit 时 comment 为空: repoId={}, commitId={}", repoId, commitId);
            throw ExceptionUtils.badRequest("comment is required when publishing a commit");
        }

        // 更新数据库中的 commit 状态
        int updated = artifactCommitMapper.publishDraft(repoId, commitId, SecurityUtils.getUserId(), comment);
        if (updated <= 0) {
            log.debug("发布 commit 失败: repoId={}, commitId={}", repoId, commitId);
            throw ExceptionUtils.conflict("publish failed for commit: " + commitId);
        }

        ArtifactCommit published = artifactCommitMapper.selectByRepoAndCommit(repoId, commitId);
        if (published == null) {
            log.debug("发布后无法查询到 commit: repoId={}, commitId={}, 存在数据库一致性问题", repoId, commitId);
            throw ExceptionUtils.notFound("commit not found after publish: " + commitId);
        }

        log.info("成功发布 commit: repoId={}, commitId={}", repoId, commitId);
        return toResponse(published);
    }

    /**
     * 获取最新已发布 commit（HEAD）
     */
    public ArtifactCommitResponse getHead(UUID repoId) {
        log.info("获取最新已发布 commit (HEAD): repoId={}", repoId);

        ensureRepoExists(repoId);

        ArtifactCommit commit = artifactCommitMapper.selectLatestPublishedByRepoId(repoId);
        log.debug("查询结果: repoId={}, commit={}", repoId, commit);

        if (commit == null) {
            log.debug("repo 下没有已发布的 commit: repoId={}", repoId);
            throw ExceptionUtils.notFound("no published commit in repo: " + repoId + ". Please publish a commit first.");
        }
        
        log.info("成功获取最新已发布 commit (HEAD): repoId={}, commitId={}", repoId, commit.getCommitId());
        return toResponse(commit);
    }

    /**
     * 获取当前 DRAFT commit
     */
    public ArtifactCommitResponse getDraft(UUID repoId) {
        log.info("获取当前 DRAFT commit: repoId={}", repoId);

        ensureRepoExists(repoId);

        ArtifactCommit commit = artifactCommitMapper.selectDraftByRepoId(repoId);
        log.debug("查询结果: repoId={}, commit={}", repoId, commit);

        if (commit == null) {
            log.debug("repo 下没有 DRAFT 状态的 commit: repoId={}", repoId);
            throw ExceptionUtils.notFound("no draft commit in repo: " + repoId);
        }

        log.info("成功获取当前 DRAFT commit: repoId={}, commitId={}", repoId, commit.getCommitId());
        return toResponse(commit);
    }

    /**
     * 获取或创建 DRAFT commit（幂等操作）
     * 如果当前存在 draft commit，直接返回；否则创建一个新的 draft commit，并返回
     */
    @Transactional
    public ArtifactCommitResponse getOrCreateDraft(UUID repoId) {
        log.info("获取或创建 DRAFT commit: repoId={}", repoId);

        ensureRepoExists(repoId);

        // 先尝试获取已存在的 draft
        ArtifactCommit existingDraft = artifactCommitMapper.selectDraftByRepoId(repoId);
        if (existingDraft != null) {
            log.info("repo 下已存在 DRAFT commit，直接返回: repoId={}, commitId={}", repoId, existingDraft.getCommitId());
            return toResponse(existingDraft);
        }

        // 不存在则创建新的 draft commit
        log.info("repo 下不存在 DRAFT commit，开始创建: repoId={}", repoId);
        CreateCommitReq request = new CreateCommitReq();

        return createCommit(repoId, request);
    }

    /**
     * 获取 repo 下的 commit 数量
     * @param repoId repo ID
     * @param commitStatus 可选，过滤状态：DRAFT, PUBLISHED。为 null 则返回全部数量
     * @return commit 数量
     */
    public long getCommitCount(UUID repoId, String commitStatus) {
        log.info("获取 commit 数量: repoId={}, commitStatus={}", repoId, commitStatus);
        
        ensureRepoExists(repoId);

        // 构建查询参数
        ListCommitsQuery query = ListCommitsQuery.builder()
                .repoId(repoId)
                .commitStatus(commitStatus)
                .page(0)
                .size(1)
                .build();

        // 如果传入了 commitStatus，进行参数校验
        if (commitStatus != null && !commitStatus.trim().isEmpty()) {
            query.validate();
        }

        long count = artifactCommitMapper.countByRepoId(query);
        log.info("查询到 commit 数量: repoId={}, commitStatus={}, count={}", repoId, commitStatus != null ? commitStatus : "ALL", count);
        
        return count;
    }

    private void ensureRepoExists(UUID repoId) {
        if (repoId == null) {
            throw ExceptionUtils.badRequest("repoId is required");
        }
        ArtifactRepo repo = artifactRepoMapper.selectById(repoId);
        if (repo == null) {
            log.debug("artifact repo not found: repoId={}", repoId);
            throw ExceptionUtils.notFound("artifact repo not found: " + repoId);
        }
    }

    private ArtifactCommitResponse toResponse(ArtifactCommit c) {
        ArtifactCommitResponse response = new ArtifactCommitResponse();
        UserInfo createdBy = new UserInfo(c.getCreatedBy(), userService.resolveUsername(c.getCreatedBy()));
        UserInfo publishedBy = new UserInfo(c.getPublishedBy(), userService.resolveUsername(c.getPublishedBy()));
        response.setCommitId(c.getCommitId());
        response.setRepoId(c.getRepoId());
        response.setCommitStatus(c.getCommitStatus());
        response.setCommitSource(c.getCommitSource());
        response.setCreatedBy(createdBy);
        response.setPublishedBy(publishedBy);
        response.setCreatedAt(c.getCreatedAt());
        response.setPublishedAt(c.getPublishedAt());
        response.setComment(c.getComment());
        response.setMeta(c.getMeta());
        return response;
    }
}
