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
    @org.springframework.beans.factory.annotation.Autowired
    private com.fwdrobo.sirius.security.JobResourceAuthority jobResourceAuthority;
    @org.springframework.beans.factory.annotation.Autowired
    private com.fwdrobo.sirius.security.JobExecutionDefinitionService definitions;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
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
        if(userId!=null)definitions.preflightRequest(req);
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
        definitions.validateUpdate(jobId,req);definitions.preflightRequest(req);
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
        Map<UUID,UUID> pins=new HashMap<>();Set<UUID> repoWide=new HashSet<>();
        int totalPaths = 0;
        for (JobRequest.Input input : inputs) {
            if(pins.containsKey(input.repoId())&&!java.util.Objects.equals(pins.get(input.repoId()),input.commitId()))throw ExceptionUtils.badRequest("JOB_INPUT_PIN_CONFLICT");
            pins.put(input.repoId(),input.commitId());if(input.paths().isEmpty())repoWide.add(input.repoId());
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
            List<String> input = repoWide.contains(repoId)?new ArrayList<>():new ArrayList<>(entry.getValue());
            jobInputMapper.insert(jobId, repoId, "{}");
            jdbc.update("UPDATE job_input SET input_commit_id=? WHERE job_id=? AND input_repo_id=?",pins.get(repoId),jobId,repoId);
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
        Map<UUID,UUID> pins=new HashMap<>();for(var pin:jdbc.queryForList("SELECT input_repo_id,input_commit_id FROM job_input WHERE job_id=?",jobId))pins.put((UUID)pin.get("input_repo_id"),(UUID)pin.get("input_commit_id"));
        List<JobResponse.Input> inputs = repos.stream()
                .map(repo -> new JobResponse.Input(
                        repo.getRepoId(),
                        repo.getRepoName(),
                        pins.get(repo.getRepoId()),
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
    public FileStream downloadShellScriptStream(UUID jobId,String filePath)throws Exception {
        if(!com.fwdrobo.sirius.security.JobExecutionDefinitionService.entrypoint(filePath))
            throw ExceptionUtils.forbidden("入口接口仅提供任务执行脚本");
        String content,key;
        var attrs=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if(attrs instanceof org.springframework.web.context.request.ServletRequestAttributes web
            &&web.getRequest().getAttribute("ds.jobIoClaims") instanceof io.jsonwebtoken.Claims){
            var definition=definitions.worker(web.getRequest());
            if(!jobId.toString().equals(definition.body().path("ioScope").path("jobId").asText()))
                throw ExceptionUtils.forbidden("EXECUTION_JOB_MISMATCH");
            content=definition.script();key="execution/"+definition.body().path("ioScope").path("runId").asText()+"/"+definition.scriptSha();
        }else{
            jobResourceAuthority.require(jobId,null,"data.raw.download");
            Job current=getJobById(jobId);if(current==null)throw ExceptionUtils.notFound("Job不存在");
            content=current.getCmd();if(content==null||content.isBlank())throw ExceptionUtils.notFound("任务执行脚本不存在");
            key="job/"+jobId+"/current/"+com.fwdrobo.sirius.security.JobExecutionDefinitionService.sha(content);
        }
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        return new FileStream("command.sh","text/x-shellscript",key,bytes.length,new ByteArrayInputStream(bytes));
    }

    /**
     * 分页列出设备
     */
    public PageRes<JobResponse> listPage(int page, int size, String jobName, String jobCategory, String image) {
        if (page < 1 || size < 1 || size > 100) throw ExceptionUtils.badRequest("INVALID_JOB_PAGE");
        List<UUID> visible = jobResourceAuthority.visibleIds(jobName, jobCategory, image, "job.read");
        long total = visible.size();
        long offset = (long) (page - 1) * size;
        List<Job> items = visible.stream().skip(offset).limit(size).map(jobMapper::findById).toList();

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
        int totalPages = (int) ((total + size - 1) / size);
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
        jobResourceAuthority.require(job.getJobId(), null, "job.read");
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
                latestRun != null && jobResourceAuthority.canInspectRun(job.getJobId(),latestRun.getRunId(),"job.read") ? latestRun.getRunStatus() : null
        );
    }
}
