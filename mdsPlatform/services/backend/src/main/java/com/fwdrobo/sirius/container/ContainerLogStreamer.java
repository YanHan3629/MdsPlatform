package com.fwdrobo.sirius.container;

import com.fwdrobo.sirius.docker.service.DockerCli;
import com.fwdrobo.sirius.service.MinioService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 容器日志流式采集器
 * 负责将 Docker 容器日志实时流式写入 MinIO
 * 使用分段文件策略避免内存溢出
 */
@Component
@Slf4j
public class ContainerLogStreamer {

    private final DockerCli dockerCli;
    private final MinioService minioService;

    // 管理活跃的日志采集进程
    private final Map<UUID, Process> activeStreamers = new ConcurrentHashMap<>();
    private final Map<UUID, Thread> activeThreads = new ConcurrentHashMap<>();

    // 管理每个 runId 的缓冲区和元数据（用于停止时强制刷新）
    private final Map<UUID, StreamContext> streamContexts = new ConcurrentHashMap<>();

    // 启动锁：确保同一个 runId 只能启动一次，防止并发竞态条件
    private final Map<UUID, Object> startLocks = new ConcurrentHashMap<>();

    // 从配置文件读取的参数
    private final long maxPartSize;
    private final long flushIntervalMs;

    /**
     * 流式上下文：保存每个日志流的状态，以便停止时刷新缓冲区
     */
    private static class StreamContext {
        final UUID jobId;
        final StringBuilder buffer;
        int partNumber;
        long totalBytesWritten;

        volatile boolean shouldStop = false;
        final Object bufferLock = new Object();

        StreamContext(UUID jobId) {
            this.jobId = jobId;
            this.buffer = new StringBuilder();
            this.partNumber = 1;
            this.totalBytesWritten = 0;
        }
    }

    public ContainerLogStreamer(DockerCli dockerCli, MinioService minioService, ContainerProperties properties) {
        this.dockerCli = dockerCli;
        this.minioService = minioService;
        this.maxPartSize = properties.getLogStreamer().getMaxPartSizeBytes();
        this.flushIntervalMs = properties.getLogStreamer().getFlushIntervalMs();

        log.info("ContainerLogStreamer initialized with maxPartSize={}MiB ({} bytes), flushInterval={}ms",
                maxPartSize / 1024 / 1024, maxPartSize, flushIntervalMs);
    }

    /**
     * 兼容旧调用：不传 dockerHost 时走默认 docker 环境
     */
    public void startStreaming(UUID runId, UUID jobId, String containerId) {
        startStreaming(runId, jobId, containerId, null);
    }

    /**
     * 启动日志流式采集（指定 dockerHost）
     *
     * @param dockerHost 本次容器所在的 docker daemon
     */
    public void startStreaming(UUID runId, UUID jobId, String containerId, String dockerHost) {
        Object lock = startLocks.computeIfAbsent(runId, k -> new Object());

        synchronized (lock) {
            // 在锁内再次检查，防止并发启动（双重检查锁模式）
            if (activeThreads.containsKey(runId)) {
                log.warn("Log streamer already running for runId={}", runId);
                return;
            }

            Thread t = new Thread(() -> streamLogs(runId, jobId, containerId, dockerHost),
                    "log-streamer-" + runId);
            t.setDaemon(true);

            // 先注册到 activeThreads（标记为已启动），再启动线程
            // 这样可以防止在线程启动和执行 streamLogs 之间的竞态窗口
            activeThreads.put(runId, t);
            t.start();

            log.info("Started log streamer for runId={}, containerId={}, dockerHost={}", runId, containerId, dockerHost);
        }
    }

    /**
     * 停止日志采集
     * 使用协作式停止机制：设置标志位 → 等待线程退出 → 安全清理资源
     *
     * @param runId 任务运行ID
     */
    public void stopStreaming(UUID runId) {
        // 获取锁对象，确保与 startStreaming 互斥
        Object lock = startLocks.get(runId);
        if (lock == null) {
            // 可能已经被清理或从未启动
            log.debug("No lock found for runId={}, attempting direct cleanup", runId);
            cleanupStreamingResources(runId);
            return;
        }

        synchronized (lock) {
            // 1. 设置停止标志位，通知后台线程优雅退出
            StreamContext context = streamContexts.get(runId);
            if (context != null) {
                context.shouldStop = true;
            }

            // 2. 强制关闭 Docker 进程（使 readLine() 返回 null）
            Process p = activeStreamers.get(runId);
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
                log.debug("Destroyed Docker process for runId={}", runId);
            }

            // 3. 等待后台线程检测到停止信号并退出（最多等待 2 秒）
            Thread t = activeThreads.get(runId);
            if (t != null && t.isAlive()) {
                try {
                    t.join(2000);
                    if (t.isAlive()) {
                        log.warn("Log streamer thread did not stop within 2s for runId={}, forcing interrupt", runId);
                        t.interrupt();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for log streamer thread to stop for runId={}", runId);
                }
            }

            // 4. 线程已停止，安全地清理资源（包括刷新剩余日志）
            cleanupStreamingResources(runId);

            // 5. 清理锁对象，释放内存
            startLocks.remove(runId);
        }
    }

    /**
     * 清理日志采集相关的所有资源
     * 注意：此方法应在后台线程已停止后调用，以确保线程安全
     *
     * @param runId 任务运行ID
     */
    private void cleanupStreamingResources(UUID runId) {
        // 1. 先尝试刷新缓冲区中的剩余日志
        StreamContext context = streamContexts.get(runId);
        if (context != null) {
            // 使用缓冲区锁保护并发访问
            // 此时后台线程应该已经停止，但为了绝对安全仍然加锁
            synchronized (context.bufferLock) {
                if (context.buffer.length() > 0) {
                    try {
                        long bytesWritten = uploadLogPart(runId, context.jobId, context.partNumber, context.buffer.toString());
                        context.totalBytesWritten += bytesWritten;
                        log.info("Flushed remaining logs on stop for runId={}, part={}, size={}KiB, total={}MiB",
                                runId, context.partNumber, bytesWritten / 1024, context.totalBytesWritten / 1024 / 1024);
                        context.buffer.setLength(0);
                    } catch (Exception e) {
                        log.error("Failed to flush remaining logs on stop for runId={}", runId, e);
                    }
                }
            }
        }

        // 2. 清理上下文
        streamContexts.remove(runId);

        // 3. 停止进程（应该已经停止，但再次确认）
        Process p = activeStreamers.remove(runId);
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            log.info("Stopped log streamer process for runId={}", runId);
        }

        // 4. 清理线程引用
        activeThreads.remove(runId);
    }

    /**
     * 执行日志流式采集的核心逻辑（指定 dockerHost）
     */
    private void streamLogs(UUID runId, UUID jobId, String containerId, String dockerHost) {
        Process process = null;

        // 创建并注册上下文
        StreamContext context = new StreamContext(jobId);
        streamContexts.put(runId, context);

        long lastFlushTime = System.currentTimeMillis();

        try {
            // 启动进程 docker logs -f --timestamps <container-id>
            process = dockerCli.startStreamingMergedStderr(
                    List.of("logs", "-f", "--timestamps", containerId), dockerHost
            );
            activeStreamers.put(runId, process);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                // 检查停止标志位，实现协作式停止
                while (!context.shouldStop && (line = reader.readLine()) != null) {
                    // 使用缓冲区锁保护 StringBuilder，防止与 stopStreaming 并发访问
                    synchronized (context.bufferLock) {
                        // 双重检查：进入锁后再次检查停止标志，快速响应停止请求
                        if (context.shouldStop) {
                            log.debug("Detected stop flag inside lock for runId={}, breaking loop", runId);
                            break;
                        }

                        context.buffer.append(line).append("\n");

                        long currentTime = System.currentTimeMillis();
                        int bufferSize = context.buffer.length();
                        if (bufferSize >= maxPartSize ||
                                (currentTime - lastFlushTime >= flushIntervalMs && bufferSize > 0)) {

                            long bytesWritten = uploadLogPart(runId, jobId, context.partNumber, context.buffer.toString());
                            context.totalBytesWritten += bytesWritten;

                            log.debug("Uploaded log part-{} for runId={}, size={}KiB, total={}MiB",
                                    context.partNumber, runId, bytesWritten / 1024, context.totalBytesWritten / 1024 / 1024);

                            context.buffer.setLength(0);
                            context.partNumber++;
                            lastFlushTime = currentTime;
                        }
                    }
                }

                // 上传最后一批日志（仅在容器自然结束时，非强制停止）
                if (!context.shouldStop) {
                    synchronized (context.bufferLock) {
                        if (context.buffer.length() > 0) {
                            long bytesWritten = uploadLogPart(runId, jobId, context.partNumber, context.buffer.toString());
                            context.totalBytesWritten += bytesWritten;
                            log.info("Uploaded final log part-{} for runId={} (natural end), total size={}MiB",
                                    context.partNumber, runId, context.totalBytesWritten / 1024 / 1024);
                        }
                    }
                }
            }

            // 区分自然结束和强制停止
            if (context.shouldStop) {
                log.info("Log streaming stopped by request for runId={}, total parts={}, size={}MiB",
                        runId, context.partNumber, context.totalBytesWritten / 1024 / 1024);
            } else {
                log.info("Log streaming completed naturally for runId={}, total parts={}, size={}MiB",
                        runId, context.partNumber, context.totalBytesWritten / 1024 / 1024);
            }

        } catch (Exception e) {
            // 确保异常时也上传已采集的日志，使用锁保护
            synchronized (context.bufferLock) {
                if (context.buffer.length() > 0) {
                    try {
                        uploadLogPart(runId, jobId, context.partNumber, context.buffer.toString());
                        log.warn("Emergency upload of buffered logs for runId={} due to error", runId);
                    } catch (Exception uploadEx) {
                        log.error("Failed to upload buffered logs for runId={}", runId, uploadEx);
                    }
                }
            }
            log.error("Log streaming failed for runId={}, containerId={}, dockerHost={}", runId, containerId, dockerHost, e);
        } finally {
            activeStreamers.remove(runId);
            activeThreads.remove(runId);
            streamContexts.remove(runId);

            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 上传日志分段到 MinIO
     * @param runId 运行ID
     * @param jobId 任务ID
     * @param partNumber 分段编号
     * @param content 日志内容
     * @return 写入的字节数
     */
    private long uploadLogPart(UUID runId, UUID jobId, int partNumber, String content) throws Exception {
        // 生成时间戳（格式：yyyyMMdd-HHmmss）
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());

        String objectKey = String.format("job/%s/%s/logs/part-%05d-%s.log", jobId, runId, partNumber, timestamp);

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioService.putObject(objectKey, inputStream, bytes.length, "text/plain");
        }

        return bytes.length;
    }

    /**
     * 检查日志采集是否正在运行
     */
    public boolean isStreaming(UUID runId) {
        return activeStreamers.containsKey(runId);
    }

    /**
     * 获取当前活跃的采集任务数量
     */
    public int getActiveStreamCount() {
        return activeStreamers.size();
    }

    /**
     * 应用关闭时清理所有采集进程
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all log streamers, active count={}", activeStreamers.size());
        
        // 复制 keySet 避免 ConcurrentModificationException
        List<UUID> runIds = new java.util.ArrayList<>(activeThreads.keySet());
        runIds.forEach(this::stopStreaming);
        
        // 等待所有线程结束
        activeThreads.values().forEach(thread -> {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        log.info("All log streamers stopped");
    }
}
