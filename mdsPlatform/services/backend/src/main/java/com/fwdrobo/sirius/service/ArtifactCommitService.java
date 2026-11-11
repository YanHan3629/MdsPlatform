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
    private final DatasetAccessService datasetAccess;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private ArtifactUploadService uploadProtocol;

    public ArtifactCommitService(
            ArtifactRepoMapper artifactRepoMapper, 
            ArtifactCommitMapper artifactCommitMapper,
            ManifestService manifestService,
            UserService userService,
            DatasetAccessService datasetAccess
    ) {
        this.artifactRepoMapper = artifactRepoMapper;
        this.artifactCommitMapper = artifactCommitMapper;
        this.manifestService = manifestService;
        this.userService = userService;
        this.datasetAccess = datasetAccess;
    }

    /**
     * 创建 Commit（默认 DRAFT）
     */
    @Transactional
    public ArtifactCommitResponse createCommit(UUID repoId, @Valid CreateCommitReq request) {
        datasetAccess.requireArtifact(repoId,null,true);
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
     * Index output is a new physical snapshot. Ordinary raw drafts keep their
     * existing parent inheritance through createCommit.
     */
    @Transactional
    public ArtifactCommitResponse createEmptyIndexDraft(UUID repoId,UUID datasetId,UUID indexId) {
        datasetAccess.requireArtifact(repoId,null,true);
        jdbc.execute("SELECT pg_advisory_xact_lock(805014630400001::bigint)");
        if(jdbc.queryForList("SELECT repo_id FROM artifact_repo WHERE repo_id=? FOR UPDATE",repoId).isEmpty())
            throw ExceptionUtils.notFound("index repository 不存在");
        if(!Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE dataset_id=? AND index_repo_id=?)",
            Boolean.class,datasetId,repoId)))throw ExceptionUtils.badRequest("INDEX_OUTPUT_REPOSITORY_MISMATCH");
        ArtifactCommit previousDraft=artifactCommitMapper.selectDraftByRepoId(repoId);
        if(previousDraft!=null) {
            // Only an explicitly managed, failed execution may release the draft
            // slot. Keep every file, manifest and receipt for later inspection.
            boolean terminal=Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM mm_index_version i
                  JOIN artifact_commit c ON c.repo_id=i.index_repo_id AND c.commit_id=i.index_commit_id
                  JOIN job_run j ON j.run_id=i.build_run_id AND j.job_id=i.build_job_id
                  WHERE i.dataset_id=? AND i.index_repo_id=? AND i.index_commit_id=? AND i.index_status='FAILED'
                    AND c.meta->>'managedIndexOutput'='true'
                    AND c.meta->>'indexVersionId'=i.index_version_id::text
                    AND (j.run_status IN('FAILED','CANCELED','SUCCEEDED')
                      OR EXISTS(SELECT 1 FROM ds_index_callback_receipt r WHERE r.run_id=j.run_id AND r.callback_kind='FAILED')))
                AND NOT EXISTS(SELECT 1 FROM mm_index_version WHERE index_repo_id=? AND index_commit_id=? AND index_status<>'FAILED')
                AND NOT EXISTS(SELECT 1 FROM artifact_upload_attempt WHERE commit_id=? AND state='PREPARED')
                """,Boolean.class,datasetId,repoId,previousDraft.getCommitId(),repoId,previousDraft.getCommitId(),previousDraft.getCommitId()));
            if(!terminal)throw ExceptionUtils.conflict("索引仓库仍有未结束或非托管的草稿，请先处理当前任务");
            int retired=jdbc.update("""
                UPDATE artifact_commit SET commit_status='DEPRECATED'
                WHERE repo_id=? AND commit_id=? AND commit_status='DRAFT'
                """,repoId,previousDraft.getCommitId());
            if(retired!=1)throw ExceptionUtils.conflict("索引草稿状态已改变，请重试");
        }
        ArtifactCommit commit=new ArtifactCommit();
        commit.setCommitId(UUID.randomUUID());commit.setRepoId(repoId);
        commit.setCommitStatus(CommitStatus.DRAFT);commit.setCommitSource("INDEX_BUILD");
        commit.setCreatedBy(SecurityUtils.getUserId());commit.setComment("isolated index execution output");
        var meta=MAPPER.createObjectNode();meta.put("managedIndexOutput",true);
        meta.put("datasetId",datasetId.toString());meta.put("indexVersionId",indexId.toString());commit.setMeta(meta);
        artifactCommitMapper.insert(commit);
        try { manifestService.initDraft(repoId,commit.getCommitId(),null); }
        catch(Exception failure){throw ExceptionUtils.internalError("INDEX_OUTPUT_MANIFEST_INITIALIZATION_FAILED");}
        return toResponse(artifactCommitMapper.selectByRepoAndCommit(repoId,commit.getCommitId()));
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
        List<ArtifactCommit> visible = catalogueCommits(query);
        int page = query.getPageOrDefault();
        int size = query.getSizeOrDefault();
        long total = visible.size();
        if (total == 0) {
            return new PageRes<>(0, 0, page, size, List.of());
        }
        int totalPages = (int) ((total + size - 1) / size);
        if (page > totalPages) {
            throw ExceptionUtils.badRequest(String.format("页码超出范围：请求第 %d 页，但总共只有 %d 页", page, totalPages));
        }
        long offset = (page - 1L) * size;
        List<ArtifactCommitResponse> items = visible.stream().skip(offset).limit(size).map(this::toResponse).toList();
        return new PageRes<>(total, totalPages, page, size, items);
    }

    private List<ArtifactCommit> catalogueCommits(ListCommitsQuery query) {
        UUID repoId = query.getRepoId();
        datasetAccess.requireArtifact(repoId, null, false);
        return artifactCommitMapper.selectCatalogueCandidates(query).stream()
                .filter(commit -> datasetAccess.canReadArtifact(repoId, commit.getCommitId()))
                .toList();
    }

    /**
     * 查询指定 commit
     */
    public ArtifactCommitResponse getCommit(UUID repoId, UUID commitId) {
        log.info("查询指定 commit: repoId={}, commitId={}", repoId, commitId);

        // A consumer may read only its granted commit; the owner-filtered repo
        // lookup cannot represent this exact-commit authorization.
        if(SecurityUtils.getUserId()!=null)datasetAccess.requireArtifact(repoId,commitId,false);
        else ensureRepoExists(repoId);

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
        try(var operation=datasetAccess.beginCommitPublication(repoId,commitId)) {
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

        // Recheck current authority immediately before the immutable state change.
        datasetAccess.requireArtifact(repoId,commitId,true);
        // 更新数据库中的 commit 状态
        uploadProtocol.requirePublishable(repoId,commitId);
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
    }

    /**
     * 获取最新已发布 commit（HEAD）
     */
    public ArtifactCommitResponse getHead(UUID repoId) {
        log.info("获取最新已发布 commit (HEAD): repoId={}", repoId);

        ensureRepoExists(repoId);

        ArtifactCommit commit = artifactCommitMapper.selectLatestPublishedByRepoId(repoId);

        if (commit == null) {
            log.debug("repo 下没有已发布的 commit: repoId={}", repoId);
            throw ExceptionUtils.notFound("no published commit in repo: " + repoId + ". Please publish a commit first.");
        }
        
        log.info("成功获取最新已发布 commit (HEAD): repoId={}, commitId={}", repoId, commit.getCommitId());
        return toResponse(commit);
    }

    public ArtifactCommitResponse getReadableHead(UUID repoId) {
        ListCommitsQuery query = ListCommitsQuery.builder().repoId(repoId).commitStatus("PUBLISHED")
                .sortBy("publishedAt").sortOrder("DESC").build();
        return catalogueCommits(query).stream().findFirst().map(this::toResponse)
                .orElseThrow(() -> ExceptionUtils.notFound("未找到可访问的已发布提交"));
    }

    /**
     * 获取当前 DRAFT commit
     */
    public ArtifactCommitResponse getDraft(UUID repoId) {
        log.info("获取当前 DRAFT commit: repoId={}", repoId);

        ensureRepoExists(repoId);

        ArtifactCommit commit = artifactCommitMapper.selectDraftByRepoId(repoId);

        if (commit == null || !datasetAccess.canReadArtifact(repoId, commit.getCommitId())) {
            log.debug("repo 下没有 DRAFT 状态的 commit: repoId={}", repoId);
            throw ExceptionUtils.notFound("未找到可访问的草稿提交");
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
        datasetAccess.requireArtifact(repoId,null,true);
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
        ListCommitsQuery query = ListCommitsQuery.builder().repoId(repoId).commitStatus(commitStatus).build();
        query.validate();
        return catalogueCommits(query).size();
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
