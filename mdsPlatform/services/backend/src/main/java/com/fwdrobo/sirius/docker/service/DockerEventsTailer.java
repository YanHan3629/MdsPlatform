package com.fwdrobo.sirius.docker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.container.ContainerService;
import com.fwdrobo.sirius.docker.entity.DockerHostResolver;
import com.fwdrobo.sirius.docker.entity.DockerServer;
import com.fwdrobo.sirius.entity.job.ContainerStatus;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1) 每台 DockerServer 启一个 docker events tailer（3 台 host 就 3 个线程 + 3 个进程）
 * 2) 每个 tailer 启动 docker events 时显式传 dockerHost（避免 "Cannot connect to the Docker daemon"）
 * 3) 事件触发的 inspect/refresh 也必须使用同一个 dockerHost（否则会 "No such container"）
 */
@Slf4j
@Component
public class DockerEventsTailer {

    private static final ObjectMapper M = new ObjectMapper();

    private final DockerCli dockerCli;
    private final JobRunMapper repo;
    private final ContainerService containerService;
    private final DockerHostResolver resolver;
    private final String labelApp;

    private volatile boolean running = true;

    // 同时监听多台 host：每台各自一个 Process
    private final ConcurrentHashMap<String, Process> currents = new ConcurrentHashMap<>();

    public DockerEventsTailer(
            DockerCli dockerCli,
            JobRunMapper repo,
            ContainerService containerService,
            DockerHostResolver resolver,
            @Value("${app.docker.labelApp:sirius-backend}") String labelApp
    ) {
        this.dockerCli = dockerCli;
        this.repo = repo;
        this.containerService = containerService;
        this.resolver = resolver;
        this.labelApp = labelApp;
    }

    @PostConstruct
    public void start() {
        log.info("[docker-events] tailer threads starting...");
        for (DockerServer server : DockerServer.values()) {
            Thread t = new Thread(() -> runForever(server.name()), "docker-events-tailer-" + server.name());
            t.setDaemon(true);
            t.start();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        currents.forEach((s, p) -> {
            try {
                p.destroy();
            } catch (Exception ignored) {
            }
        });
    }

    private void runForever(String server) {
        long backoffMs = 1000;
        long maxBackoff = 30_000;

        final String dockerHost;
        try {
            dockerHost = resolver.resolve(server);
        } catch (Exception e) {
            log.error("[docker-events] resolve docker host failed. server={}, err={}", server, e.getMessage());
            return;
        }

        while (running) {
            Process p = null;
            try {
                log.info("[docker-events] spawning docker events... server={}, host={}", server, dockerHost);

                p = dockerCli.startStreamingMergedStderr(List.of(
                        "events",
                        "--filter", "label=app=" + labelApp,
                        "--format", "{{json .}}"), dockerHost);

                currents.put(server, p);
                backoffMs = 1000;

                log.info("[docker-events] connected. server={}", server);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while (running && (line = br.readLine()) != null) {
                        try {
                            handle(server, dockerHost, line);
                        } catch (Exception ex) {
                            // 单条事件解析/处理失败不应干掉 tailer
                            log.warn("[docker-events] handle event failed. server={}, err={}, raw={}",
                                    server, ex.getMessage(), line);
                        }
                    }
                }

                // docker events 正常情况不会退出；退出则重连
                int code = p.waitFor();
                log.warn("[docker-events] docker events exited. server={}, code={}", server, code);

            } catch (Exception ex) {
                log.error("[docker-events] tailer error. server={}, err={}", server, ex.getMessage());
            } finally {
                if (p != null) {
                    try {
                        p.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                }
                currents.remove(server);
            }

            // 退避重连
            if (running) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ignored) {
                }
                backoffMs = Math.min(maxBackoff, backoffMs * 2);
            }
        }
    }

    // 监听 docker 状态（来自某个 server/host）
    private void handle(String server, String dockerHost, String jsonLine) throws Exception {
        JsonNode n = M.readTree(jsonLine);
        String action = n.path("Action").asText("");
        String containerId = n.path("id").asText(null);
        JsonNode attrs = n.path("Actor").path("Attributes");
        String runIdStr = attrs.path("runId").asText(null);

        log.debug("[docker-events] server={}, action={}, containerId={}, runId={}", server, action, containerId, runIdStr);

        if ((containerId == null || containerId.isBlank())
                && (runIdStr == null || runIdStr.isBlank())) {
            log.debug("[docker-events] skip event: no containerId and no runId. server={}, raw={}", server, jsonLine);
            return;
        }

        //短重试：应对 runJob 刚写入未提交 / 刚写回 containerId 的窗口
        JobRun e = findJobRunWithRetry(containerId, runIdStr, 5, 100);

        if (e == null) {
            log.warn("[docker-events] JobRun not found (yet). server={}, action={}, containerId={}, runId={}",
                    server, action, containerId, runIdStr);
            return;
        }
        //如果 DB 里还没写回 containerId，但事件里有，就用事件里的补上
        if ((e.getContainerId() == null || e.getContainerId().isBlank())
                && containerId != null && !containerId.isBlank()) {
            e.setContainerId(containerId);

            try {
                repo.updateContainerByRunId(e);
            } catch (Exception ex) {
                log.debug("[docker-events] update containerId by runId failed. server={}, err={}", server, ex.getMessage());
            }
        }

        switch (action) {
            case "start", "die", "stop" -> {
                try {
                    containerService.refreshFromInspect(e, dockerHost);
                } catch (Exception ex) {
                    log.warn("[docker-events] refreshFromInspect failed. server={}, action={}, runId={}, containerId={}, err={}",
                            server, action, e.getRunId(), e.getContainerId(), ex.getMessage());
                }
            }
            case "destroy" -> {
                //destroy 后 inspect 通常会失败，直接标记 MISSING
                e.setContainerStatus(ContainerStatus.MISSING);
                e.setDockerStatus(null);
                e.setLastSeenAt(OffsetDateTime.now());
                repo.updateStatusAndTimes(e);
            }
            default -> {
                //ignore other docker events
            }
        }
    }

    private JobRun findJobRunWithRetry(
            String containerId,
            String runIdStr,
            int attempts,
            long sleepMs
    ) {
        UUID runId = null;
        if (runIdStr != null && !runIdStr.isBlank()) {
            try {
                runId = UUID.fromString(runIdStr);
            } catch (IllegalArgumentException ex) {
                log.warn("[docker-events] invalid runId label: {}", runIdStr);
            }
        }

        for (int i = 0; i < attempts; i++) {
            try {
                if (containerId != null && !containerId.isBlank()) {
                    var byCid = repo.findByContainerId(containerId);
                    if (byCid.isPresent()) return byCid.get();
                }
                if (runId != null) {
                    var byRunId = repo.selectByRunId(runId);
                    if (byRunId.isPresent()) return byRunId.get();
                }
            } catch (Exception ex) {
                log.warn("[docker-events] query JobRun failed: {}", ex.getMessage());
            }

            if (i < attempts - 1) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                }
                sleepMs = Math.min(1000, sleepMs * 2);
            }
        }
        return null;
    }
}
