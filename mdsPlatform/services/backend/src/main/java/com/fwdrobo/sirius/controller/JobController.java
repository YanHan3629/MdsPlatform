package com.fwdrobo.sirius.controller;

import com.fwdrobo.sirius.container.ContainerService;
import com.fwdrobo.sirius.docker.entity.DockerHostResolver;
import com.fwdrobo.sirius.dto.PageRes;
import com.fwdrobo.sirius.dto.file.FileStream;
import com.fwdrobo.sirius.dto.job.JobQuery;
import com.fwdrobo.sirius.dto.job.JobRequest;
import com.fwdrobo.sirius.dto.job.JobResponse;
import com.fwdrobo.sirius.dto.job.JobRunQuery;
import com.fwdrobo.sirius.dto.job.RunResponse;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.service.JobRunService;
import com.fwdrobo.sirius.service.JobService;
import com.fwdrobo.sirius.util.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/jobs")
@Validated
public class JobController {
    private final JobService jobService;
    private final JobRunService jobRunService;
    private final ContainerService containerService;
    private final FileController fileController;
    DockerHostResolver resolver;

    public JobController(JobService jobService, JobRunService jobRunService, ContainerService containerService, FileController fileController, DockerHostResolver resolver) {
        this.jobService = jobService;
        this.jobRunService = jobRunService;
        this.containerService = containerService;
        this.fileController = fileController;
        this.resolver = resolver;
    }

    @PostMapping
    public Map<String, Object> createJob(@Valid @RequestBody JobRequest req) {
        log.debug("user:{} start to create job", SecurityUtils.getUserId());
        UUID jobId = jobService.createJob(req, SecurityUtils.getUserId());
        return Map.of(
                "message", "job创建成功",
                "jobId", jobId);
    }

    @PatchMapping("/{jobId}")
    public Map<String, Object> updateJob(@PathVariable @Valid UUID jobId, @Valid @RequestBody JobRequest req) {
        log.debug("user:{} start to update job", SecurityUtils.getUserId());
        jobService.updateJob(jobId, req, SecurityUtils.getUserId());
        return Map.of("message", "job修改成功",
                "jobId", jobId);
    }

    @GetMapping
    public PageRes<JobResponse> pageQuery(@Validated @ModelAttribute JobQuery query) {
        return jobService.listPage(
                query.page(),
                query.size(),
                query.jobName(),
                query.jobCategory(),
                query.image()
        );
    }

    // Job detail query: inputs.paths is [] when repo-wide input is selected.
    @GetMapping("/{jobId}")
    public JobResponse getJobById(@PathVariable @Valid UUID jobId) {
        log.debug("start get job details by jobId:{} ", jobId);
        return jobService.getJobResponseById(jobId);
    }

    //todo ssh 模式, 内置container,可下载image, file, 提供python sdk, docker hub
    @PostMapping("/{jobId}/run")
    public JobRun run(@PathVariable @Valid UUID jobId, @RequestHeader(value = HttpHeaders.AUTHORIZATION) String auth) throws Exception {
        log.debug("start to create job run for jobId: {}", jobId);
        String token = SecurityUtils.getToken(auth);
        return containerService.runJob(jobId, token);
    }

    @GetMapping("/{jobId}/run/{runId}")
    public RunResponse getJobRunById(@PathVariable @Valid UUID jobId, @PathVariable @Valid UUID runId) {
        log.debug("start to run job, jobId:{}, runId: {}", jobId, runId);
        return jobRunService.getJobRunById(runId);
    }

    @GetMapping("/{jobId}/run")
    public PageRes<RunResponse> runPageQuery(@PathVariable @Valid String jobId, @Validated @ModelAttribute JobRunQuery query) {
        return jobRunService.listPage(
                query.page(),
                query.size(),
                jobId,
                query.dockerStatus(),
                query.containerStatus(),
                query.containerId(),
                query.containerName()
        );
    }

    @GetMapping("/{jobId}/run/{runId}/logs")
    public String logs(@PathVariable @Valid String jobId, @PathVariable @Valid UUID runId) throws Exception {
        return containerService.logs(runId);
    }

    @PostMapping("/{jobId}/run/{runId}/stop")
    public JobRun stop(@PathVariable @Valid UUID jobId, @PathVariable @Valid UUID runId, @RequestParam(defaultValue = "false") boolean purge) throws Exception {
        return containerService.stopJob(runId, purge);
    }

    @GetMapping("/{jobId}/entrypoint/content")
    public void downloadJobFile(
            @PathVariable @Valid UUID jobId,
            @RequestParam @Valid String filePath,
            HttpServletResponse response) throws Exception {
        log.debug("start download job file: jobId={}, filePath={}", jobId, filePath);
        FileStream stream = jobService.downloadShellScriptStream(jobId, filePath);
        fileController.writeFileStreamToResponse(response, stream);
    }

    @GetMapping(value = "/internal/script/command", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getCommand() throws Exception {
        String pathStr = "/app/script/command.sh";
        Path p = Path.of(pathStr);
        String content = Files.readString(p, StandardCharsets.UTF_8);
        return ResponseEntity.ok(content);
    }

}
