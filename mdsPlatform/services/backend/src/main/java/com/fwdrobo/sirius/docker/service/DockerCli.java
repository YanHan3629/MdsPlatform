package com.fwdrobo.sirius.docker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DockerCli {

    private final String dockerBin;
    private final Duration timeout;

    public DockerCli(
            @Value("${app.docker.binary:docker}") String dockerBin,
            @Value("${app.docker.cmdTimeoutSeconds:30}") long timeoutSeconds
    ) {
        this.dockerBin = dockerBin;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    //todo use docker API
    // 兼容旧调用：不指定 host，就走默认 docker 环境（本地/系统配置）
    public ExecResult exec(List<String> args) throws Exception {
        return exec(args, null);
    }

    public ExecResult exec(List<String> args, String dockerHost) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBin);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        applyDockerHost(pb, dockerHost);

        Process p = pb.start();

        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new RuntimeException("docker cmd timeout: " + String.join(" ", cmd));
        }

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        return new ExecResult(p.exitValue(), out, err);
    }

    public Process startStreamingMergedStderr(List<String> args, String dockerHost) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(dockerBin);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true);

        applyDockerHost(pb, dockerHost);
        return pb.start();
    }

    private void applyDockerHost(ProcessBuilder pb, String dockerHost) {
        if (dockerHost == null || dockerHost.isBlank()) return;

        Map<String, String> env = pb.environment();
        env.put("DOCKER_HOST", dockerHost);

        env.remove("DOCKER_CONTEXT");
    }

    public record ExecResult(int exitCode, String stdout, String stderr) {}
}
