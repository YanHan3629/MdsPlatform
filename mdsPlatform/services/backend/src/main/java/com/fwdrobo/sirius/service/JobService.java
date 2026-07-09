package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.file.FileStream;
import com.fwdrobo.sirius.dto.job.JobRequest;
import com.fwdrobo.sirius.dto.job.JobResponse;
import com.fwdrobo.sirius.dto.user.UserInfo;
import com.fwdrobo.sirius.entity.artifact.ArtifactRepo;
import com.fwdrobo.sirius.entity.job.Job;
import com.fwdrobo.sirius.entity.job.JobInputFile;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.handler.JsonbTypeHandler;
import com.fwdrobo.sirius.mapper.ArtifactRepoMapper;
import com.fwdrobo.sirius.mapper.JobInputFileMapper;
import com.fwdrobo.sirius.mapper.JobInputMapper;
import com.fwdrobo.sirius.mapper.JobMapper;
import com.fwdrobo.sirius.mapper.JobOutputMapper;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.PathUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JobService {
    private static final int MAX_TOTAL_INPUT_PATHS = 2000;
    private static final int MAX_REPO_INPUT_PATHS = 100;

    private final JobMapper jobMapper;
    private final JobInputMapper jobInputMapper;
    private final JobOutputMapper jobOutputMapper;
    private final UserService userService;
    private final MinioService minioService;
    private final JobInputFileMapper jobInputFileMapper;
    private final ArtifactRepoMapper artifactRepoMapper;
    private final JobRunMapper jobRunMapper;
    private final TrialLimitService trialLimitService;

    public JobService(
            JobMapper jobMapper,
            JobInputMapper jobInputMapper,
            JobOutputMapper jobOutputMapper,
            UserService userService,
            MinioService minioService,
            JobInputFileMapper jobInputFileMapper,
            ArtifactRepoMapper artifactRepoMapper,
            JobRunMapper jobRunMapper,
            TrialLimitService trialLimitService
    ) {
        this.jobMapper = jobMapper;
        this.jobInputMapper = jobInputMapper;
        this.jobOutputMapper = jobOutputMapper;
        this.userService = userService;
        this.minioService = minioService;
        this.jobInputFileMapper = jobInputFileMapper;
        this.artifactRepoMapper = artifactRepoMapper;
        this.jobRunMapper = jobRunMapper;
        this.trialLimitService = trialLimitService;
    }

    /**
     * 创建任务并在入口处执行资源参数校验。
     */
    @Transactional
    public UUID createJob(JobRequest req, UUID userId) {
        TrialLimitService.JobResourceLimits limits = trialLimitService.parseAndValidateJobResources(req.params());
        trialLimitService.checkTrialJobResourceLimit(limits);
        JsonNode jobParamsJson = JsonbTypeHandler.toJsonNode(req.params());
        UUID orgId = SecurityUtils.getUserOrgId();
        UUID jobId = jobMapper.insertJob(req.jobName(), req.jobCategory(), req.image(), req.cmd(), jobParamsJson, userId, orgId);

        //保存 shell 脚本到 MinIO
        saveShellScriptToMinio(jobId, req.cmd());

        return insertDeduplicatedInputs(req, jobId);
    }

    /**
     * 更新任务并在入口处执行资源参数校验。
     */
    @Transactional
    public UUID updateJob(UUID jobId, JobRequest req, UUID userId) {
        TrialLimitService.JobResourceLimits limits = trialLimitService.parseAndValidateJobResources(req.params());
        trialLimitService.checkTrialJobResourceLimit(limits);
        JsonNode jobParamsJson = JsonbTypeHandler.toJsonNode(req.params());
        int affectedRows = jobMapper.updateJob(jobId, req.jobName(), req.jobCategory(), req.image(), req.cmd(), jobParamsJson);
        if (affectedRows <= 0) {
            throw ExceptionUtils.notFound("任务不存在或无权限修改，任务参数未更新");
        }

        // 保存 shell 脚本到 MinIO
        saveShellScriptToMinio(jobId, req.cmd());

        //overwrite
        jobInputMapper.deleteByJobId(jobId);
        jobInputFileMapper.deleteByJobId(jobId);
        jobOutputMapper.deleteByJobId(jobId);

        return insertDeduplicatedInputs(req, jobId);
    }

    // 对 repoId,path 进行去重整合,并写入数据库
    private UUID insertDeduplicatedInputs(JobRequest req, UUID jobId) {
        List<JobRequest.Input> inputs = req.inputs();
        Map<UUID, HashSet<String>> map = new HashMap<>();
        int totalPaths = 0;
        for (JobRequest.Input input : inputs) {
            HashSet<String> logicalPaths = map.computeIfAbsent(input.repoId(), k -> new HashSet<>());
            for (String path : input.paths()) {
                String logicalPath = PathUtils.normalizePath(path);
                if (logicalPaths.add(logicalPath)) {
                    ++totalPaths;
                    if (totalPaths > MAX_TOTAL_INPUT_PATHS) {
                        throw ExceptionUtils.badRequest("inputs paths 总数不能超过 " + MAX_TOTAL_INPUT_PATHS);
                    }
                    if (logicalPaths.size() > MAX_REPO_INPUT_PATHS) {
                        throw ExceptionUtils.badRequest("单个 repo 的 paths 最多支持 " + MAX_REPO_INPUT_PATHS + " 条");
                    }
                }
            }
        }
        for (var entry : map.entrySet()) {
            UUID repoId = entry.getKey();
            List<String> input = new ArrayList<>(entry.getValue());
            jobInputMapper.insert(jobId, repoId, "{}");
            if (input.isEmpty()) {
                // Mapper SQL persists sentinel '*ALL*' when logicalPaths is empty.
                log.debug("insertDeduplicatedInputs: repoId={} has empty paths, persist as *ALL*", repoId);
            }
            jobInputFileMapper.batchInsertJobInputFiles(jobId, repoId, input);
        }
        for (UUID outputRepoId : req.outputRepoIds()) {
            jobOutputMapper.insert(jobId, outputRepoId, "{}");
        }
        return jobId;
    }

    public JobResponse getJobResponseById(UUID jobId) {
        Job job = getJobById(jobId);
        List<JobInputFile> inputFiles = Optional
                .ofNullable(jobInputFileMapper.findByJobId(jobId))
                .orElseThrow(() -> ExceptionUtils.notFound("job input files not found: " + jobId));
        log.debug("getJobResponseById: jobId={}, fetched input file rows={}", jobId, inputFiles.size());

        Map<UUID, List<String>> map = new HashMap<>();
        int repoWideInputCount = 0;
        for (var i : inputFiles) {
            List<String> list = map.computeIfAbsent(i.getRepoId(), k -> new ArrayList<>());
            // logicalPath is null when DB value is sentinel '*ALL*'.
            if (i.getLogicalPath() != null) {
                list.add(i.getLogicalPath());
            } else {
                repoWideInputCount++;
            }
        }

        List<UUID> repoIds = inputFiles.stream().map(JobInputFile::getRepoId).distinct().toList();
        List<ArtifactRepo> repos = artifactRepoMapper.selectArtifactsByIds(repoIds);
        List<JobResponse.Input> inputs = repos.stream()
                .map(repo -> new JobResponse.Input(
                        repo.getRepoId(),
                        repo.getRepoName(),
                        map.get(repo.getRepoId())
                )).toList();
        log.debug("getJobResponseById: jobId={}, repos={}, repoWideInputs={}", jobId, inputs.size(), repoWideInputCount);

        // 查询最近一次 JobRun
        JobRun latestRun = jobRunMapper.selectLatestRunByJobId(jobId);

        return toResponse(job, inputs, latestRun);
    }

    public Job getJobById(UUID jobId) {
        Job job = jobMapper.findById(jobId);
        return job;
    }

    /**
     * 将 cmd字符串 保存到 MinIO 的 job/{jobId}/command.sh
     * @param jobId Job ID
     * @param cmd 命令字符串（完整的 shell 脚本内容）
     */
    private void saveShellScriptToMinio(UUID jobId, String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            log.debug("saveShellScriptToMinio: cmd is empty, skip saving for jobId={}", jobId);
            return;
        }

        try {
            //构建 MinIO object key: job/{jobId}/command.sh
            String objectKey = String.format("job/%s/command.sh", jobId);

            //转换为字节流
            byte[] scriptBytes = cmd.getBytes(StandardCharsets.UTF_8);
            //上传到 MinIO
            try (InputStream inputStream = new ByteArrayInputStream(scriptBytes)) {
                String etag = minioService.putObject(
                        objectKey,
                        inputStream,
                        scriptBytes.length,
                        "text/x-shellscript"
                );
                log.info("saveShellScriptToMinio success: jobId={}, objectKey={}, etag={}, size={}",
                        jobId, objectKey, etag, scriptBytes.length);
            }
        } catch (Exception e) {
            log.error("saveShellScriptToMinio failed: jobId={}", jobId, e);
            throw ExceptionUtils.internalError("保存 shell 脚本到 MinIO 失败: " + e.getMessage());
        }
    }

    /**
     * 从 MinIO 流式获取 job/{jobId}/ 目录下的 shell 脚本文件
     * @param jobId Job ID
     * @param filePath 文件相对路径（相对于 job/{jobId}/）
     * @return FileStream 文件流对象
     */
    public FileStream downloadShellScriptStream(UUID jobId, String filePath) throws Exception {
        log.info("downloadShellScriptStream: jobId={}, filePath={}", jobId, filePath);

        //构建 MinIO 中的对象 key
        String objectKey = String.format("job/%s/%s", jobId, filePath);

        try {
            //获取对象的元信息
            StatObjectResponse stat = minioService.statObject(objectKey);
            //获取文件流
            InputStream inputStream = minioService.getObject(objectKey);
            //构建并返回 FileStream
            String contentType = stat.contentType();
            if (contentType == null || contentType.isBlank()) {
                // 根据文件扩展名推断 content type
                if (filePath.endsWith(".sh")) {
                    contentType = "text/x-shellscript";
                } else if (filePath.endsWith(".txt")) {
                    contentType = "text/plain";
                } else {
                    contentType = "application/octet-stream";
                }
            }
            FileStream fileStream = new FileStream(
                    filePath,
                    contentType,
                    objectKey,
                    stat.size(),
                    inputStream
            );
            log.info("downloadShellScriptStream success: jobId={}, filePath={}, objectKey={}, size={}",
                    jobId, filePath, objectKey, stat.size());
            return fileStream;
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                throw ExceptionUtils.notFound("文件不存在: " + filePath);
            }
            throw e;
        } catch (Exception e) {
            log.error("downloadShellScriptStream failed: jobId={}, filePath={}, objectKey={}",
                    jobId, filePath, objectKey, e);
            throw ExceptionUtils.internalError("获取文件流失败: " + e.getMessage());
        }
    }

    /**
     * 分页列出设备
     */
    public PageRes<JobResponse> listPage(int page, int size, String jobName, String jobCategory, String image) {
        List<Job> items;
        // 计算总记录数
        long total = jobMapper.countByFilters(jobName, jobCategory, image);
        if (total == 0) {
            // 无记录，直接赋为空页
            items = List.of();
        } else {
            // 获取分页数据
            int offset = Math.max(0, (page - 1) * size);
            items = jobMapper.selectPageByFilters(jobName, jobCategory, image, offset, size);
            items = items != null ? items : List.of();  // 强保护，防止返回 null
        }

        Map<UUID, List<UUID>> inputRepoIds = items.stream().collect(
                Collectors.toMap(Job::getJobId, job -> toUuidList(job.getInputRepoIds(), job.getJobId(), "inputRepoIds"))
        );
        Set<UUID> allInputRepoIds = inputRepoIds.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toSet());
        Map<UUID, String> repoNamesById = allInputRepoIds.isEmpty()
                ? Map.of()
                : artifactRepoMapper.selectArtifactsByIds(new ArrayList<>(allInputRepoIds)).stream()
                .collect(Collectors.toMap(ArtifactRepo::getRepoId, ArtifactRepo::getRepoName));

        Map<UUID, List<JobResponse.Input>> itemInputs = new HashMap<>();
        for (var e : inputRepoIds.entrySet()) {
            itemInputs.put(e.getKey(),
                    e.getValue().stream()
                            .map(repoId -> new JobResponse.Input(repoId, repoNamesById.get(repoId), null))
                            .toList());
        }

        // 批量查询最近 JobRun
        List<UUID> jobIds = items.stream().map(Job::getJobId).toList();
        List<JobRun> latestRuns = jobIds.isEmpty() ? List.of() : jobRunMapper.selectLatestRunsByJobIds(jobIds);
        
        // 构建 Map（jobId -> JobRun）
        Map<UUID, JobRun> latestRunMap = latestRuns.stream()
                .collect(Collectors.toMap(JobRun::getJobId, java.util.function.Function.identity(), (existing, replacement) -> existing));

        List<JobResponse> responses = items.stream()
                .map(i -> toResponse(i, itemInputs.get(i.getJobId()), latestRunMap.get(i.getJobId())))
                .toList();

        // 计算总页数
        int totalPages = (int) (total + size - 1);
        if (totalPages > 0 && page > totalPages) {
            throw ExceptionUtils.notFound("页数超过上限");
        }

        return new PageRes<>(total, totalPages, page, size, responses);
    }

    private List<UUID> toUuidList(JsonNode jsonNode, UUID jobId, String fieldName) {
        if (jsonNode == null || !jsonNode.isArray()) {
            return List.of();
        }

        List<UUID> ids = new ArrayList<>(jsonNode.size());
        for (JsonNode node : jsonNode) {
            if (node == null || node.isNull()) {
                continue;
            }

            String text = node.asText();
            if (text == null || text.isBlank()) {
                continue;
            }

            try {
                ids.add(UUID.fromString(text));
            } catch (IllegalArgumentException ex) {
                log.error("invalid uuid in {}, jobId={}, value={}", fieldName, jobId, text, ex);
                throw ExceptionUtils.internalError("invalid uuid in " + fieldName + ": " + text);
            }
        }
        return ids;
    }

    private JobResponse toResponse(Job job, List<JobResponse.Input> inputs, JobRun latestRun) {
        if (job == null) {
            return null;
        }
        UserInfo createdBy = new UserInfo(job.getCreatedBy(), userService.resolveUsername(job.getCreatedBy()));
        return new JobResponse(
                job.getJobId(),
                job.getJobName(),
                job.getJobCategory(),
                job.getImage(),
                job.getCmd(),
                job.getParams(),
                createdBy,
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getOutputRepoIds(),
                inputs,
                latestRun != null ? latestRun.getRunStatus() : null
        );
    }
}
