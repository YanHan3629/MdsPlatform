package com.fwdrobo.sirius.docker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.container.ContainerProperties;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Getter
public class DockerArgs {
    // 必填
    private String runId;
    private String jobId;
    // 可选
    private String name;
    private List<String> entry;
    private Map<String, Object> envs;
    private String image;
    private String userCmd;
    private int timeoutSeconds;
    private boolean formalOrg;

    private static ObjectMapper objectMapper = new ObjectMapper();
    // 配置项
    private final String volName;
    private final String labelApp;
    private final String host;
    String token;

    private DockerArgs(Builder builder) {
        this.name = builder.name;
        this.runId = builder.runId;
        this.jobId = builder.jobId;
        this.entry = builder.entry;
        this.envs = builder.envs;
        this.image = builder.image;
        this.userCmd = builder.userCmd;
        this.labelApp = builder.containerProperties.getLabelApp();
        this.host = builder.containerProperties.getHost();
        this.volName = "vol_" + this.runId;
        this.token = builder.token;
        this.timeoutSeconds = Math.max(1, builder.timeoutSeconds);
        this.formalOrg = builder.formalOrg;
    }

    /**
     * 创建 builder 实例
     */
    public static Builder builder(String runId, String jobId,
                                  ContainerProperties containerProperties) {
        return new Builder(runId, jobId, containerProperties);
    }

    // builder 类
    public static class Builder {

        private String runId;
        private String jobId;
        // 容器名
        private String name;
        // 入口参数，默认为空
        private List<String> entry;
        // 环境变量
        private Map<String, Object> envs = null;
        // 镜像名
        private String image = "sirius-work";
        // 用户命令
        private String userCmd = "";
        // 配置项
        private final ContainerProperties containerProperties;
        private String token = "";
        private int timeoutSeconds = 7200;
        private boolean formalOrg = false;

        private Builder(String runId, String jobId, ContainerProperties containerProperties) {
            this.runId = runId;
            this.jobId = jobId;
            this.name = "jobId-" + jobId + "-runId-" + runId; // 默认容器名
            this.containerProperties = containerProperties;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder token(String token) {
            this.token = token;
            return this;
        }

        public Builder entry(List<String> entry) {
            this.entry = entry;
            return this;
        }

        public Builder envs(Map<String, Object> envs) {
            this.envs = envs;
            return this;
        }

        public Builder image(String image) {
            if (image != null && image.contains(" ")) {
                // 发现空格时截断后面内容
                this.image = image.substring(0, image.indexOf(" "));
            } else {
                this.image = image;
            }
            return this;
        }

        public Builder userCmd(String userCmd) {
            this.userCmd = userCmd;
            return this;
        }

        /**
         * 设置任务运行超时时长（秒）。
         */
        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        /**
         * 设置当前任务是否来自正式版组织。
         */
        public Builder formalOrg(boolean formalOrg) {
            this.formalOrg = formalOrg;
            return this;
        }

        public DockerArgs build() {
            return new DockerArgs(this);
        }

    }

    /**
     * 构建 docker run 命令参数列表
     * run -d --name ... --label ... -e ... -v <volume>:/root --mount ... <image> <cmd...>
     */
    public List<String> buildArgs(List<String> jobParams) throws Exception {
        List<String> runArgs = new ArrayList<>();
        runArgs.addAll(List.of("run", "-d"));
        runArgs.addAll(List.of("--name", this.name));
        runArgs.addAll(List.of("--label", "app=" + labelApp));
        runArgs.addAll(List.of("--label", "managedBy=backend"));
        runArgs.addAll(List.of("--label", "jobId=" + this.jobId));
        runArgs.addAll(List.of("--label", "runId=" + this.runId));
        // 挂载 job 数据目录
        // -v <volume>:/root
        //todo 通过label 判断是否需要gpu
        runArgs.addAll(List.of("-e", "HOST=" + host));

        String inputRepoId = objectMapper.convertValue(this.envs.get("InputRepoId"), String.class);
        String inputCommitId = objectMapper.convertValue(this.envs.get("InputCommitId"), String.class);
        String outputRepoId = objectMapper.convertValue(this.envs.get("OutputRepoId"), String.class);
        String outputCommitId = objectMapper.convertValue(this.envs.get("OutputCommitId"), String.class);

        runArgs.addAll(List.of("-e", "JOB_ID=" + this.jobId));
        runArgs.addAll(List.of("-e", "ARTIFACT_ID=" + inputRepoId));
        runArgs.addAll(List.of("-e", "COMMIT_ID=" + inputCommitId));
        runArgs.addAll(List.of("-e", "OUTPUT_ID=" + outputRepoId));
        runArgs.addAll(List.of("-e", "OUTPUT_COMMIT_ID=" + outputCommitId));
        runArgs.addAll(List.of("-e", "SCRIPT_URL=" + host + "/api/jobs/" + jobId + "/entrypoint/content"));
        runArgs.addAll(List.of("-e", "AUTHHEADER=Authorization: Bearer " + token));

        if (this.envs != null) {
            for (var kv : this.envs.entrySet()) {
                Object v = kv.getValue();
                String value = (v instanceof JsonNode jn)
                        ? jn.asText()
                        : String.valueOf(v);
                runArgs.addAll(List.of("-e", kv.getKey() + "=" + value));
            }
        }
        runArgs.addAll(List.of("-e", "TEMPLATE_URL=" + host + "/api/jobs/internal/script/command"));

        runArgs.addAll(jobParams);
        if (jobParams.contains("--gpus")) {
            runArgs.addAll(List.of("--net", "host"));
            runArgs.addAll(List.of("--ipc", "host"));
        }

        runArgs.add(this.image);

        String timeoutLimit = this.timeoutSeconds + "s";

        String CMD = " set -eu"
                + " && set -x"  // 调试信息
                + " && echo '[boot] downloading script...'"
                + " && out=/root/command.sh"
                + " && url=\"$TEMPLATE_URL\""
                + " && http_code=\"$(curl -sS -G -H \"$AUTHHEADER\" -o \"$out\" -w \"%{http_code}\" \"$url\" || printf '%s' '000')\""
                + " && echo \"[boot] curl http_code=$http_code\""
                + " && test \"$http_code\" = \"200\""
                + " && ls -l \"$out\""
                + " && { head -n 5 \"$out\" || true; }"
                + " && sed -i 's/\\r$//' \"$out\""
                + " && chmod +x \"$out\""
                + " && echo '[boot] running script...'"
                + " && if command -v timeout >/dev/null 2>&1;"
                + " then"
                + " timeout " + timeoutLimit + " bash \"$out\"; code=$?; if [ \"$code\" -eq 124 ]; then echo '[boot] 任务执行超时(" + timeoutLimit + ")'; fi; if [ \"$code\" -ne 0 ]; then exit \"$code\"; fi;"
                + " else"
                + " bash \"$out\";"
                + " fi";

        runArgs.addAll(List.of("sh", "-lc", CMD));
        log.debug("Docker run args: {}", String.join(" ", runArgs));
        return runArgs;
    }

    /**
     * 构建 docker volume create 命令参数列表
     */
    public List<String> buildVolArgs() {
        List<String> volArgs = new ArrayList<>();
        volArgs.addAll(List.of("volume", "create"));
        volArgs.addAll(List.of("--label", "app=" + labelApp));
        volArgs.addAll(List.of("--label", "managedBy=backend"));
        volArgs.addAll(List.of("--label", "jobId=" + this.jobId));
        volArgs.addAll(List.of("--label", "runId=" + this.runId));
        volArgs.addAll(List.of(this.volName));
        return volArgs;
    }

}
