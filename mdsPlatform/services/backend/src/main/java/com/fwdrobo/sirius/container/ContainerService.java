package com.fwdrobo.sirius.container;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.docker.entity.DockerHostResolver;
import com.fwdrobo.sirius.docker.entity.DockerServer;
import com.fwdrobo.sirius.docker.service.*;
import com.fwdrobo.sirius.entity.job.ContainerStatus;
import com.fwdrobo.sirius.entity.job.Job;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.entity.job.RunStatus;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import com.fwdrobo.sirius.service.ArtifactCommitService;
import com.fwdrobo.sirius.service.JobService;
import com.fwdrobo.sirius.service.MinioService;
import com.fwdrobo.sirius.service.TrialLimitService;
import com.fwdrobo.sirius.util.ExceptionUtils;
import com.fwdrobo.sirius.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@EnableConfigurationProperties(ContainerProperties.class)
public class ContainerService {

    private final JobRunMapper repo;
    private final DockerCli dockerCli;
    private final JobService jobService;
    private final ArtifactCommitService artifactCommitService;
    private final ContainerLogStreamer logStreamer;
    private final MinioService minioService;
    private final ObjectMapper objectMapper;

    private final ContainerProperties containerProperties;
    private final TransactionTemplate tx;
    private final ContainerLauncher launcher;
    private final ContainerInspector inspector;
    private final DockerHostResolver resolver;
    private final TrialLimitService trialLimitService;

    public ContainerService(
            JobRunMapper repo,
            DockerCli dockerCli,
            JobService jobService,
            ArtifactCommitService artifactCommitService,
            ContainerLogStreamer logStreamer,
            MinioService minioService,
            ObjectMapper objectMapper,
            ContainerProperties containerProperties,
            PlatformTransactionManager txManager,
            ContainerLauncher launcher,
            ContainerInspector inspector,
            DockerHostResolver resolver,
            TrialLimitService trialLimitService
    ) {
        this.repo = repo;
        this.dockerCli = dockerCli;
        this.jobService = jobService;
        this.artifactCommitService = artifactCommitService;
        this.logStreamer = logStreamer;
        this.minioService = minioService;
        this.objectMapper = objectMapper;
        this.containerProperties = containerProperties;
        this.tx = new TransactionTemplate(txManager);
        this.launcher = launcher;
        this.inspector = inspector;
        this.resolver = resolver;
        this.trialLimitService = trialLimitService;
    }
    /**
     * dockerHost: 选择哪台 docker daemon 来 run（GPU_01/CPU_02/LOCAL）
     */
    @Transactional
    public JobRun runJob(UUID jobId, String token) throws Exception {
        UUID runId = UUID.randomUUID();
        UUID userId = SecurityUtils.getUserId();
        UUID orgId = SecurityUtils.getUserOrgId();
        if (orgId == null) {
            throw ExceptionUtils.notFound("当前用户未绑定组织，无法启动任务");
        }
        Job job = jobService.getJobById(jobId);
        JsonNode params = job.getParams();
        String name = "jobId-" + jobId + "-runId-" + runId;
        //todo 后续动态获取
        String dockerHostId = DockerServer.GPU_01.name();
        String dockerHostValue = resolver.resolve(dockerHostId);
        String image = job.getImage();
        String cmdNode = job.getCmd();
        String logUri = String.format("job/%s/%s/logs", jobId, runId);
        Map<String, Object> rawParams = objectMapper.convertValue(params, new TypeReference<>() {});
        TrialLimitService.JobResourceLimits limits = trialLimitService.parseAndValidateJobResources(rawParams);
        TrialLimitService.RuntimeQuotaReservation reservation = trialLimitService.reserveRunRuntimeQuota(orgId, limits);

        JobRun jobRun = new JobRun();
        jobRun.setRunId(runId);
        jobRun.setJobId(jobId);
        jobRun.setOrgId(orgId);
        jobRun.setContainerName(name);
        jobRun.setCreatedBy(userId);
        jobRun.setReservedGpu(reservation.gpu());
        jobRun.setReservedCpu(reservation.cpu());
        jobRun.setReservedMemoryMb(reservation.memoryMb());
        jobRun.setRuntimeQuotaReleased(0);

        //初始状态：排队中
        jobRun.setRunStatus(RunStatus.QUEUED);
        jobRun.setContainerStatus(ContainerStatus.PENDING_CREATE);
        jobRun.setLastSeenAt(OffsetDateTime.now());
        jobRun.setDockerHost(dockerHostId);
        jobRun.setLogsUri(logUri);

        try {
            //短事务：插入并提交，然后立刻返回
            tx.executeWithoutResult(status -> repo.insert(jobRun));

            JsonParam jp = objectMapper.treeToValue(params, JsonParam.class);
            Map<String, Object> envMap = jp.env;
            parseRepos(job, envMap);
            int timeoutSeconds = trialLimitService.currentRuntimeLimitSeconds();
            boolean formalOrg = isFormalOrgForCurrentUser();
            //同步组装 DockerArgs
            DockerArgs Args = DockerArgs.builder(
                            runId.toString(), jobId.toString(), containerProperties)
                    .name(name)
                    .envs(envMap)
                    .image(image)
                    .userCmd(cmdNode)
                    .token(token)
                    .timeoutSeconds(timeoutSeconds)
                    .formalOrg(formalOrg)
                    .build();

            List<String> volArgs = Args.buildVolArgs();

            RunSpec runSpec = JsonParamConverter.toRunSpec(jp);
            List<String> args = DockerRunArgsBuilder.build(runSpec);
            List<String> runArgs = Args.buildArgs(args);
            //异步启动（立刻返回，不阻塞HTTP）
            launcher.launch(runId, volArgs, runArgs, dockerHostValue);
            return jobRun;
        } catch (Exception ex) {
            trialLimitService.releaseRunRuntimeQuota(orgId, reservation);
            throw ex;
        }
    }

    /**
     * 刷新 JobRun 的容器状态
     */
    public JobRun refreshFromInspect(JobRun jobRun, String dockerHost) throws Exception {
        if (jobRun == null) throw ExceptionUtils.conflict("JobRun状态异常");
        UUID runId = jobRun.getRunId();
        if (runId == null) throw ExceptionUtils.conflict("JobRun状态异常");

        //用现有的 containerId 作为 hint（可能为空，inspector 会自己兜底）
        String hint = jobRun.getContainerId();
        inspector.refreshByRunId(runId, hint, dockerHost);

        //返回最新（保证调用方拿到的是更新后的）
        return repo.selectByRunId(runId)
                .orElseThrow(() -> ExceptionUtils.notFound("JobRun不存在, runId=" + runId));
    }

    /**
     * 判断当前用户所属组织是否为正式版组织。
     */
    private boolean isFormalOrgForCurrentUser() {
        UUID orgId = SecurityUtils.getUserOrgId();
        if (orgId == null) {
            throw ExceptionUtils.notFound("当前用户未绑定组织，无法识别授权类型");
        }
        return !trialLimitService.isTrialOrg(orgId);
    }

    /**
     * dockerHost：要 stop/rm 的 daemon
     */
    @Transactional
    public JobRun stopJob(UUID runId, boolean purge) throws Exception {
        JobRun jobRun = repo.selectByRunId(runId)
                .orElseThrow(() -> ExceptionUtils.notFound("JobRun不存在"));

        String dockerHost = resolver.resolve(jobRun.getDockerHost());
        String containerStr = (jobRun.getContainerId() != null) ? jobRun.getContainerId() : jobRun.getContainerName();
        ContainerStatus containerStatus;
        try {
            if (containerStr != null && !containerStr.isBlank()) {
                if (purge) {
                    dockerCli.exec(List.of("rm", "-f", containerStr), dockerHost);
                    containerStatus = ContainerStatus.REMOVED;

                    DockerCli.ExecResult vrm = dockerCli.exec(List.of("volume", "rm", "vol_" + runId), dockerHost);
                    if (vrm.exitCode() != 0) {
                        log.warn("volume rm failed runId={}, stderr={}", runId, vrm.stderr());
                    }
                    
                    // TODO: 如果 purge，可以选择同时删除 MinIO 中的日志
                    // minioService.removeObject("job/" + jobRun.getJobId() + "/" + runId + "/logs/");
                } else {
                    dockerCli.exec(List.of("stop", "-t", "10", containerStr), dockerHost);
                    containerStatus = ContainerStatus.STOPPED;
                }
            } else {
                containerStatus = ContainerStatus.UNKNOWN;
            }
        } catch (Exception ex) {
            containerStatus = purge ? ContainerStatus.REMOVE_FAILED : ContainerStatus.STOP_FAILED;
        }
        
        // 停止日志采集（在容器停止后）
        // 给日志采集器 2 秒时间读取和上传容器停止前的最后日志
        try {
            if (logStreamer.isStreaming(runId)) {
                Thread.sleep(2000);
                logStreamer.stopStreaming(runId);
                log.info("Stopped log streaming for runId={}", runId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for final logs for runId={}", runId);
            logStreamer.stopStreaming(runId);
        } catch (Exception e) {
            log.error("Failed to stop log streaming for runId={}", runId, e);
        }

        jobRun.setRunStatus(RunStatus.CANCELED);
        jobRun.setContainerStatus(containerStatus);
        jobRun.setFinishedAt(OffsetDateTime.now());
        jobRun.setLastSeenAt(OffsetDateTime.now());
        repo.updateStatusAndTimes(jobRun);
        trialLimitService.releaseRunRuntimeQuotaIfNeeded(jobRun);
        return jobRun;
    }

    /**
     * 获取容器日志
     * 混合模式：MinIO 历史日志 + Docker 最新日志（动态时间窗口）
     * 通过解析日志文件名中的时间戳，动态计算 Docker 日志的起始时间
     * 确保 MinIO 和 Docker 日志无缝衔接，避免遗漏
     * TODO: 实现 WebSocket 实时日志流（用户需要实时查看日志）
     * TODO: 分页返回，避免一次性返回过大的日志
     */
    public String logs(UUID runId) throws Exception {
        JobRun jobRun = repo.selectByRunId(runId)
                .orElseThrow(() -> ExceptionUtils.notFound("JobRun不存在"));

        String dockerHost = resolver.resolve(jobRun.getDockerHost());

        StringBuilder allLogs = new StringBuilder();

        String logPrefix = String.format("job/%s/%s/logs/", jobRun.getJobId(), runId);
        List<String> logParts = new ArrayList<>();

        try {
            logParts = minioService.listObjects(logPrefix);

            if (!logParts.isEmpty()) {
                // 按分段号和时间戳排序（确保顺序正确，避免字符串排序导致 part-1000 排在 part-999 前面）
                logParts.sort(this::compareLogPartKeys);
                
                // 合并所有分段日志文件
                for (String partKey : logParts) {
                    try (InputStream in = minioService.getObject(partKey)) {
                        String partContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        allLogs.append(partContent);
                    } catch (Exception e) {
                        log.error("Failed to read log part: {}", partKey, e);
                        // 继续读取其他分段，不中断
                    }
                }
                
                log.debug("Retrieved {} log parts from MinIO for runId={}, total size={}KB", 
                         logParts.size(), runId, allLogs.length() / 1024);
            } else {
                log.debug("No logs in MinIO for runId={}", runId);
            }
        } catch (Exception e) {
            log.warn("Failed to list logs from MinIO for runId={}, will try Docker", runId, e);
        }

        if (jobRun.getContainerId() != null) {
            try {
                // 从 MinIO 日志文件名中提取最后一条日志的时间戳
                Instant minioLastLogTime = extractLastLogTimestamp(logParts);

                String sinceParam;
                if (minioLastLogTime != null) {
                    sinceParam = minioLastLogTime.minusSeconds(10).toString();
                } else if (jobRun.getStartedAt() != null) {
                    sinceParam = jobRun.getStartedAt().toInstant().toString();
                } else {
                    sinceParam = "10m";
                }

                DockerCli.ExecResult r = dockerCli.exec(List.of(
                        "logs", "--since", sinceParam, "--timestamps", jobRun.getContainerId()), dockerHost);

                if (r.exitCode() == 0) {
                    String recentLogs = r.stdout() + (r.stderr().isBlank() ? "" : ("\n" + r.stderr()));
                    if (!recentLogs.trim().isEmpty()) {
                        String deduplicatedLogs = deduplicateLogs(allLogs.toString(), recentLogs, runId);
                        if (!deduplicatedLogs.isEmpty()) {
                            allLogs.append(deduplicatedLogs);
                            log.debug("Appended {} new log lines from Docker for runId={}, size={}KB", 
                                     deduplicatedLogs.split("\n").length, runId, deduplicatedLogs.length() / 1024);
                        } else {
                            log.debug("No new logs from Docker (all duplicates) for runId={}", runId);
                        }
                    }
                } else {
                    log.warn("Failed to get logs from Docker for runId={}: {}", runId, r.stderr());
                }
            } catch (Exception e) {
                log.warn("Failed to read recent logs from Docker for runId={}", runId, e);
                // 不影响主流程，继续返回 MinIO 中的日志
            }
        }

        if (allLogs.length() == 0) {
            if (jobRun.getContainerStatus() == ContainerStatus.PENDING_CREATE) {
                return "[Container is being created; no logs yet]\n";
            } else if (jobRun.getContainerStatus() == ContainerStatus.CREATED) {
                return "[Container created; waiting to start...]\n";
            } else {
                if (RunStatus.FAILED == jobRun.getRunStatus() && jobRun.getErrorMessage() != null) {
                    return "[job run failed]\nError message is " + jobRun.getErrorMessage();
                } else {
                    return "[No logs]\n";
                }
            }
        }

        return allLogs.toString();
    }

    /**
     * 从 MinIO 日志文件列表中提取最后一条日志的时间戳
     * 文件名格式：job/{jobId}/{runId}/logs/part-{00001}-{yyyyMMdd-HHmmss}.log
     * @param logParts 日志文件路径列表（已排序）
     * @return 最后一条日志的时间戳，如果无法解析则返回 null
     */
    private Instant extractLastLogTimestamp(List<String> logParts) {
        if (logParts == null || logParts.isEmpty()) {
            return null;
        }

        // 从最后一个文件开始尝试提取时间戳
        for (int i = logParts.size() - 1; i >= 0; i--) {
            String partKey = logParts.get(i);
            try {
                // 提取文件名：part-00001-20250205-103045.log
                String fileName = partKey.substring(partKey.lastIndexOf('/') + 1);

                // 提取时间戳部分：20250205-103045
                // 格式：part-{number}-{timestamp}.log
                String[] parts = fileName.split("-");
                if (parts.length >= 4) {
                    // parts[0] = "part"
                    // parts[1] = "00001"
                    // parts[2] = "20250205"
                    // parts[3] = "103045.log"
                    String dateStr = parts[2];
                    String timeStr = parts[3].replace(".log", "");
                    String timestamp = dateStr + "-" + timeStr;

                    // 解析时间戳
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                            .withZone(ZoneOffset.UTC);
                    Instant instant = Instant.from(formatter.parse(timestamp));

                    log.debug("Extracted timestamp from {}: {}", fileName, instant);
                    return instant;
                }
            } catch (DateTimeParseException | IndexOutOfBoundsException e) {
                log.debug("Failed to parse timestamp from log file: {}, trying previous file", partKey);
                // 继续尝试上一个文件
            }
        }

        log.warn("Could not extract timestamp from any log files, count={}", logParts.size());
        return null;
    }

    /**
     * 去重 Docker 日志，只保留 MinIO 中不存在的新日志
     * 使用滑动窗口匹配算法找到重叠边界
     * @param minioLogs MinIO 中的历史日志
     * @param dockerLogs Docker 返回的最新日志
     * @param runId 运行 ID（用于日志）
     * @return 去重后的新日志（如果全部重复则返回空字符串）
     */
    private String deduplicateLogs(String minioLogs, String dockerLogs, UUID runId) {
        if (minioLogs.isEmpty()) {
            // MinIO 无日志，直接返回所有 Docker 日志
            return dockerLogs;
        }

        String[] minioLines = minioLogs.split("\n");
        String[] dockerLines = dockerLogs.split("\n");

        // 动态计算搜索窗口：Docker 日志行数 + 50% 缓冲，最小 200 行（可以处理日志产出速度快或 -10 秒缓冲导致的大量重复）
        int searchWindow = Math.max(200, dockerLines.length + dockerLines.length / 2);
        int minioSearchStart = Math.max(0, minioLines.length - searchWindow);

        log.debug("Dynamic search window for runId={}: {} lines (MinIO total: {}, Docker: {})",
                 runId, searchWindow, minioLines.length, dockerLines.length);

        // 使用滑动窗口查找第一个不重复的 Docker 日志行
        int firstNewLineIndex = -1;

        for (int i = 0; i < dockerLines.length; i++) {
            String dockerLine = dockerLines[i].trim();
            if (dockerLine.isEmpty()) {
                continue; // 跳过空行
            }

            boolean found = false;
            // 在 MinIO 尾部查找该行
            for (int j = minioSearchStart; j < minioLines.length; j++) {
                if (dockerLine.equals(minioLines[j].trim())) {
                    found = true;
                    break;
                }
            }

            if (!found) {
                // 找到第一个不重复的行
                firstNewLineIndex = i;
                break;
            }
        }

        if (firstNewLineIndex == -1) {
            // 所有 Docker 日志都已存在于 MinIO 中
            log.debug("All Docker logs are duplicates for runId={}", runId);
            return "";
        }

        if (firstNewLineIndex == 0) {
            // 没有重复，返回全部 Docker 日志
            log.debug("No overlap detected, appending all Docker logs for runId={}", runId);
            return dockerLogs;
        }

        // 返回从第一个新行开始的所有日志
        StringBuilder result = new StringBuilder();
        for (int i = firstNewLineIndex; i < dockerLines.length; i++) {
            if (i > firstNewLineIndex) {
                result.append("\n");
            }
            result.append(dockerLines[i]);
        }

        log.debug("Found overlap at line {}/{} for runId={}, appending {} new lines",
                 firstNewLineIndex, dockerLines.length, runId, dockerLines.length - firstNewLineIndex);

        return result.toString();
    }

    //解析输入输出仓库ID
    private void parseRepos(Job job, Map<String, Object> envMap) {
        JsonNode inputRepoIds = job.getInputRepoIds();
        JsonNode outputRepoIds = job.getOutputRepoIds();
        if (inputRepoIds != null && inputRepoIds.isArray()) {
            List<String> inputList = objectMapper.convertValue(
                    inputRepoIds, new TypeReference<List<String>>() {
                    });
            String InputRepoId = inputList.get(0);
            String InputCommitId = artifactCommitService.getHead(UUID.fromString(InputRepoId))
                    .getCommitId().toString();
            envMap.put("InputRepoId", InputRepoId);
            envMap.put("InputCommitId", InputCommitId);
        }
        if (outputRepoIds != null && outputRepoIds.isArray()) {
            List<String> outputList = objectMapper.convertValue(
                    outputRepoIds, new TypeReference<List<String>>() {
                    });
            String OutputRepoId = outputList.get(0);
            String OutputCommitId = artifactCommitService.getOrCreateDraft(UUID.fromString(OutputRepoId))
                    .getCommitId().toString();
            envMap.put("OutputRepoId", OutputRepoId);
            envMap.put("OutputCommitId", OutputCommitId);
        }
    }

    /**
     * 比较两个日志分段文件的路径，按分段号和时间戳排序
     * 文件名格式：job/{jobId}/{runId}/logs/part-{00001}-{yyyyMMdd-HHmmss}.log
     * 先按分段号排序，如果分段号相同则按时间戳排序
     * @param key1 第一个文件路径
     * @param key2 第二个文件路径
     * @return 比较结果
     */
    private int compareLogPartKeys(String key1, String key2) {
        try {
            // 提取文件名
            String fileName1 = key1.substring(key1.lastIndexOf('/') + 1);
            String fileName2 = key2.substring(key2.lastIndexOf('/') + 1);

            // 提取分段号和时间戳
            // 格式：part-{00001}-{yyyyMMdd-HHmmss}.log
            String[] parts1 = fileName1.split("-");
            String[] parts2 = fileName2.split("-");

            if (parts1.length >= 4 && parts2.length >= 4) {
                // 提取分段号（parts[1]）
                int partNum1 = Integer.parseInt(parts1[1]);
                int partNum2 = Integer.parseInt(parts2[1]);

                // 先按分段号排序
                int partComparison = Integer.compare(partNum1, partNum2);
                if (partComparison != 0) {
                    return partComparison;
                }

                // 分段号相同，按时间戳排序
                // 时间戳格式：20250205-103045
                String timestamp1 = parts1[2] + parts1[3].replace(".log", "");
                String timestamp2 = parts2[2] + parts2[3].replace(".log", "");
                return timestamp1.compareTo(timestamp2);
            }
        } catch (Exception e) {
            log.warn("Failed to parse log part keys for comparison: {} vs {}, falling back to string compare",
                     key1, key2);
        }

        // 解析失败，回退到字符串比较
        return key1.compareTo(key2);
    }
}
