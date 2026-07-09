package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.artifact.ArtifactRepoReq;
import com.fwdrobo.sirius.dto.artifact.ArtifactRepoResponse;
import com.fwdrobo.sirius.dto.artifact.ListArtifactReposQuery;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.artifact.ArtifactRepo;
import com.fwdrobo.sirius.entity.artifact.ArtifactVisibility;
import com.fwdrobo.sirius.mapper.ArtifactCommitMapper;
import com.fwdrobo.sirius.mapper.ArtifactFileMapper;
import com.fwdrobo.sirius.mapper.ArtifactRepoMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ArtifactRepoService {

    private final ArtifactRepoMapper artifactRepoMapper;
    private final ArtifactCommitMapper artifactCommitMapper;
    private final ArtifactFileMapper artifactFileMapper;
    private final UserService userService;

    public ArtifactRepoService(
            ArtifactRepoMapper artifactRepoMapper,
            ArtifactCommitMapper artifactCommitMapper,
            ArtifactFileMapper artifactFileMapper,
            UserService userService
    ) {
        this.artifactRepoMapper = artifactRepoMapper;
        this.artifactCommitMapper = artifactCommitMapper;
        this.artifactFileMapper = artifactFileMapper;
        this.userService = userService;
    }

    /**
     * 创建 Artifact Repo
     * @param request
     * @return
     */
    @Transactional
    public ArtifactRepoResponse create(@Valid ArtifactRepoReq request) {
        log.info("开始创建 artifact repo: repoName={}, repoType={}", request.getRepoName(), request.getRepoType());
        // 检查 repoName 是否已存在
        ArtifactRepo existingRepo = artifactRepoMapper.selectByName(request.getRepoName().trim());
        if (existingRepo != null) {
            log.debug("artifact repo 名称已存在: repoName={}, 存在的 repoId={}", request.getRepoName(), existingRepo.getRepoId());
            throw ExceptionUtils.conflict("artifact repo 已存在: " + request.getRepoName());
        }

        UUID ownerUserId = SecurityUtils.getUserId();

        ArtifactVisibility visibility = request.getVisibility() != null ? request.getVisibility() : ArtifactVisibility.PRIVATE;

        ArtifactRepo repo = new ArtifactRepo();
        repo.setRepoId(UUID.randomUUID());
        repo.setRepoName(request.getRepoName().trim());
        repo.setRepoType(request.getRepoType().trim());
        repo.setDescription(request.getDescription());
        repo.setOwnerUserId(ownerUserId);
        repo.setVisibility(visibility.name());
        repo.setOrgId(SecurityUtils.getUserOrgId());

        artifactRepoMapper.insert(repo);
        log.debug("artifact repo 已插入数据库: repoId={}, repoName={}", repo.getRepoId(), repo.getRepoName());

        ArtifactRepo created = artifactRepoMapper.selectById(repo.getRepoId());
        if (created == null) {
            log.error("创建后无法查询到 artifact repo: repoId={}, 存在数据库一致性问题", repo.getRepoId());
            throw ExceptionUtils.internalError("未能获取到创建的 artifact repo 信息, repoId: " + repo.getRepoId() + "存在数据库一致性问题。");
        }
        log.info("成功创建 artifact repo: repoId={}, repoName={}", created.getRepoId(), created.getRepoName());
        return toResponse(created);
    }

    public ArtifactRepoResponse updateArtifact(UUID repoId, ArtifactRepoReq request) {
        ArtifactRepo repo = artifactRepoMapper.selectById(repoId);
        if (repo == null) {
            ExceptionUtils.notFound("no artifact found");
        }
        repo.setRepoName(request.getRepoName());
        repo.setRepoType(request.getRepoType());
        repo.setDescription(request.getDescription());
        repo.setVisibility(request.getVisibility().name());
        repo.setUpdatedAt(OffsetDateTime.now());

        int updated = artifactRepoMapper.update(repo);

        if (updated <= 0) {
            throw ExceptionUtils.conflict("Artifact更新失败");
        }
        return toResponse(repo);
    }

    /**
     * 根据 repoId 获取 Artifact Repo
     * @param repoId
     * @return
     */
    public ArtifactRepoResponse getById(UUID repoId) {
        if (repoId == null) {
            log.debug("getById 调用时 repoId 为空");
            throw new IllegalArgumentException("repoId is required");
        }
        log.debug("查询 artifact repo: repoId={}", repoId);
        ArtifactRepo repo = artifactRepoMapper.selectById(repoId);
        if (repo == null) {
            log.debug("未找到指定的 artifact repo: repoId={}", repoId);
            throw ExceptionUtils.notFound("未找到 artifact repo， repoId: " + repoId);
        }
        log.info("成功查询 artifact repo: repoId={}, repoName={}", repo.getRepoId(), repo.getRepoName());
        return toResponse(repo);
    }

    /**
     * 获取仓库实体，不存在则抛出 404。
     */
    public ArtifactRepo getRepoOrNotFound(UUID repoId) {
        ArtifactRepo repo = artifactRepoMapper.selectById(repoId);
        if (repo == null) {
            throw ExceptionUtils.notFound("未找到 artifact repo，repoId: " + repoId);
        }
        return repo;
    }

    /**
     * 校验仓库类型必须为 URDF。
     */
    public void assertUrdfRepoType(ArtifactRepo repo) {
        if (repo == null) {
            throw ExceptionUtils.notFound("URDF 仓库不存在");
        }
        if (repo.getRepoType() == null || !"URDF".equalsIgnoreCase(repo.getRepoType().trim())) {
            throw ExceptionUtils.badRequest("仓库类型必须为URDF");
        }
    }

    /**
     * 根据 repoName 获取 Artifact Repo
     * @param repoName
     * @return
     */
    public ArtifactRepoResponse getByName(String repoName) {
        if (repoName == null || repoName.trim().isEmpty()) {
            log.debug("getByName 调用时 repoName 为空");
            throw new IllegalArgumentException("repoName is required");
        }
        log.debug("查询 artifact repo: repoName={}", repoName);
        ArtifactRepo repo = artifactRepoMapper.selectByName(repoName.trim());
        if (repo == null) {
            log.debug("未找到指定的 artifact repo: repoName={}", repoName);
            throw ExceptionUtils.notFound("未找到 artifact repo， repoName: " + repoName);
        }
        log.info("成功查询 artifact repo: repoId={}, repoName={}", repo.getRepoId(), repo.getRepoName());
        return toResponse(repo);
    }
    
    /**
     * 分页查询 Artifact Repos（支持排序、过滤）
     * @param query 查询参数
     * @return 分页结果
     */
    public PageRes<ArtifactRepoResponse> listAllPaged(ListArtifactReposQuery query) {
        log.info("分页查询 artifact repos: page={}, size={}, sortBy={}, sortOrder={}, visibility={}, searchKeyword={}",
                query.getPageOrDefault(), query.getSizeOrDefault(), query.getSortByOrDefault(),
                query.getSortOrderOrDefault(), query.getVisibility(), query.getSearchKeyword());

        // 查询总数
        long totalCount = artifactRepoMapper.countByQuery(query);
        log.debug("符合条件的 artifact repos 总数: {}", totalCount);

        // 如果没有数据，直接返回空结果
        if (totalCount == 0) {
            return new PageRes<>(0, 0, query.getPageOrDefault(), query.getSizeOrDefault(), List.of());
        }

        // 计算总页数
        int totalPages = (int) ((totalCount + query.getSizeOrDefault() - 1) / query.getSizeOrDefault());

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

        // 查询当前页数据
        List<ArtifactRepo> repos = artifactRepoMapper.selectByPage(query);
        List<ArtifactRepoResponse> responses = repos.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());


        log.info("查询到 {} 个 artifact repos，当前页: {}/{}", responses.size(), query.getPageOrDefault(), totalPages);

        return new PageRes<>(
                totalCount,
                totalPages,
                query.getPageOrDefault(),
                query.getSizeOrDefault(),
                responses
        );
    }

    /**
     * 删除 Artifact Repo（级联删除 commits / tags / artifact_file 记录）
     *
     * @return 被删除的 commit 数量（DB 记录数）
     */
    @Transactional
    public int deleteRepoCascade(UUID repoId) {
        log.info("开始删除 artifact repo 及其关联 commits: repoId={}", repoId);

        if (repoId == null) {
            throw ExceptionUtils.badRequest("repoId is required");
        }
        ArtifactRepo existing = artifactRepoMapper.selectById(repoId);
        if (existing == null) {
            log.debug("未找到指定的 artifact repo，无法删除: repoId={}", repoId);
            throw ExceptionUtils.notFound("未找到 artifact repo， repoId: " + repoId);
        }

        // 检查当前登陆用户权限
        ensureCanManageRepo(existing);

        // 先取出 commitId 列表，供后续做存储侧清理（TODO）
        List<UUID> commitIds = artifactCommitMapper.selectCommitIdsByRepoId(repoId);
        for (UUID commitId : commitIds) {
            // TODO: 级联删除该 commit 下的物理文件（MinIO）。
            //       目前 DB 的 artifact_file 记录会因外键 ON DELETE CASCADE 自动删除，但 minIO 对象存储侧仍会残留：
            //          1) workspace/{artifactId}/{commitId}/...（文件对象）
            //          2) manifest/artifacts/{artifactId}/commits/{commitId}/commit_manifest.json（manifest）
            //       下一步计划，待惠强 artifact_file 部分 pr 后 ：
            //         - 通过调 API: artifact_file.file_key 查询出所有 objectKey（见 selectFileKeysByCommitId），逐个 remove, 。
            //         - 或者按 prefix 列举对象后批量删除。

            // or:另一种设计是在数据库的文件行加上 deleted 标记，然后专门有一个垃圾收集定时任务每天查询所有含有这个标记的文件行做相应处理，
            // 可以删除，也可以是压缩后存档起来。这样的话这个 API 也能极快返回，而且能够减小万一系统崩溃后导致不一致的概率。
            List<String> fileKeys = artifactFileMapper.selectFileKeysByCommitId(commitId);
            log.debug("TODO delete minio objects for commit: repoId={}, commitId={}, files={}", repoId, commitId, fileKeys.size());
        }

        // DB 删除：artifact_commit / artifact_tag / artifact_file 会级联删除
        int deleted = artifactRepoMapper.deleteById(repoId);
        log.debug("artifact repo 删除结果: repoId={}, deleted={}", repoId, deleted);

        if (deleted <= 0) {
            log.debug("删除 artifact repo 失败: repoId={}", repoId);
            throw ExceptionUtils.conflict("删除失败");
        }
        return commitIds.size();
    }

    // 确保当前用户有权限管理该 repo（删除 artifact_repo 等操作前调用）
    private void ensureCanManageRepo(ArtifactRepo repo) {
        UUID userId = SecurityUtils.getUserId();

        if (userId == null) {
            throw ExceptionUtils.badRequest("当前用户userId 为空，请检查登录状态");
        }

        boolean isOwner = repo.getOwnerUserId() != null && repo.getOwnerUserId().equals(userId);
        boolean isAdmin = SecurityUtils.getUserRoles() != null && SecurityUtils.getUserRoles().contains("ROLE_ADMIN");

        if (!isOwner && !isAdmin) {
            log.debug("当前用户无权限操作该 artifact repo: repoId={}, ownerUserId={}, currentUserId={}",
                    repo.getRepoId(), repo.getOwnerUserId(), userId);
            throw ExceptionUtils.forbidden("当前用户无权限操作该 artifact repo");
        }
    }

    // 将实体类转换为响应 DTO
    private ArtifactRepoResponse toResponse(ArtifactRepo r) {
        ArtifactRepoResponse response = new ArtifactRepoResponse();

        long publishedCount = artifactCommitMapper.countByRepoIdAndStatus(r.getRepoId(), "PUBLISHED");
        long draftCount = artifactCommitMapper.countByRepoIdAndStatus(r.getRepoId(), "DRAFT");
        UserInfo ownerUser = new UserInfo(r.getOwnerUserId(), userService.resolveUsername(r.getOwnerUserId()));
        response.setRepoId(r.getRepoId());
        response.setRepoName(r.getRepoName());
        response.setRepoType(r.getRepoType());
        response.setDescription(r.getDescription());
        response.setOwnerUser(ownerUser);
        response.setVisibility(ArtifactVisibility.fromString(r.getVisibility()));
        response.setCreatedAt(r.getCreatedAt());
        response.setUpdatedAt(r.getUpdatedAt());
        response.setPublishedCount(publishedCount);
        response.setDraftCount(draftCount);
        
        return response;
    }
}
