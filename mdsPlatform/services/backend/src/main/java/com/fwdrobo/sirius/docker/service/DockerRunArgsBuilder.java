package com.fwdrobo.sirius.docker.service;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

public final class DockerRunArgsBuilder {

    private static final Pattern ENV_KEY = Pattern.compile("^[A-Z_][A-Z0-9_]*$");
    private static final Pattern CPUSET = Pattern.compile("^[0-9,-]+$"); // 允许 0-3,6,8-9 这类（更严可再细化）
    private static final Set<String> ALLOWED_PROTOCOL = Set.of("tcp", "udp");

    private static final Set<String> ALLOWED_NETWORK = Set.of("bridge", "none", "host");
    private static final Set<String> ALLOWED_IPC = Set.of("private", "host", "shareable");
    private static final Set<String> ALLOWED_RESTART = Set.of("no", "always", "unless-stopped", "on-failure");
    private static final Set<String> ALLOWED_GPU_MODE = Set.of("all", "count", "device");

    // bind mount 允许的宿主机路径前缀（示例：只允许 /data 和 /home/hy/workspace）
    private static final List<Path> ALLOWED_BIND_PREFIX = List.of(
            Paths.get("/data"),
            Paths.get("/home/hy/workspace")
    );

    public static List<String> build(RunSpec spec) {
        Objects.requireNonNull(spec, "spec");

        List<String> args = new ArrayList<>();

        // ===== runtime: -it / --rm =====
        if (spec.runtime != null) {
            if (spec.runtime.interactive) args.add("-i");
            if (spec.runtime.tty) args.add("-t");
            if (spec.runtime.autoRemove) args.add("--rm");
        }

        // ===== GPUs: --gpus ... =====
        // 环境兼容：device 模式用 --gpus all + NVIDIA_VISIBLE_DEVICES=...
        if (spec.gpus != null ) {
            args.add("--gpus");
            args.add(spec.gpus.toLowerCase(Locale.ROOT));
        }

        // ===== network =====
        if (spec.network != null) {
            String net = spec.network.toLowerCase(Locale.ROOT);
            if (!ALLOWED_NETWORK.contains(net)) {
                throw new IllegalArgumentException("network not allowed: " + spec.network);
            }
            args.add("--net");
            args.add(net);
        }

        // ===== ipc =====
        if (spec.ipc != null) {
            String ipc = spec.ipc.toLowerCase(Locale.ROOT);
            if (!ALLOWED_IPC.contains(ipc)) {
                throw new IllegalArgumentException("ipc not allowed: " + spec.ipc);
            }
            args.add("--ipc");
            args.add(ipc);
        }

        // ===== restart =====
        if (spec.restart != null && spec.restart.name != null) {
            String r = spec.restart.name.toLowerCase(Locale.ROOT);
            if (!ALLOWED_RESTART.contains(r)) {
                throw new IllegalArgumentException("restart not allowed: " + spec.restart.name);
            }
            args.add("--restart");
            if ("on-failure".equals(r) && spec.restart.maxRetries != null) {
                int mr = spec.restart.maxRetries;
                if (mr < 0 || mr > 100) throw new IllegalArgumentException("maxRetries out of range");
                args.add("on-failure:" + mr);
            } else {
                args.add(r);
            }
        }

        // ===== resources (cpus/mem/memSwap/pids/cpuset) =====
        if (spec.resources != null) {
            if (spec.resources.cpus != null && spec.resources.cpus > 0) {
                if (spec.resources.cpus > 64) throw new IllegalArgumentException("cpus too large");
                args.add("--cpus");
                args.add(trimTrailingZeros(spec.resources.cpus));
            }

            if (spec.resources.cpusetCpus != null) {
                String s = spec.resources.cpusetCpus.trim();
                if (!CPUSET.matcher(s).matches()) throw new IllegalArgumentException("invalid cpusetCpus: " + s);
                args.add("--cpuset-cpus");
                args.add(s);
            }

            if (spec.resources.cpusetMems != null) {
                String s = spec.resources.cpusetMems.trim();
                if (!CPUSET.matcher(s).matches()) throw new IllegalArgumentException("invalid cpusetMems: " + s);
                args.add("--cpuset-mems");
                args.add(s);
            }

            if (spec.resources.memoryMb != null && spec.resources.memoryMb > 0) {
                int m = spec.resources.memoryMb;
                if (m > 1024 * 1024) throw new IllegalArgumentException("memory too large");
                args.add("--memory");
                args.add(m + "m");
            }

            if (spec.resources.memorySwapMb != null && spec.resources.memorySwapMb > 0) {
                int ms = spec.resources.memorySwapMb;
                if (ms > 1024 * 1024) throw new IllegalArgumentException("memorySwap too large");
                // 建议约束：swap 总量 >= memory
                if (spec.resources.memoryMb != null && spec.resources.memoryMb > 0 && ms < spec.resources.memoryMb) {
                    throw new IllegalArgumentException("memorySwapMb must be >= memoryMb");
                }
                args.add("--memory-swap");
                args.add(ms + "m");
            }

            if (spec.resources.pidsLimit != null) {
                int p = spec.resources.pidsLimit;
                if (p < 0 || p > 100000) throw new IllegalArgumentException("pidsLimit out of range");
                args.add("--pids-limit");
                args.add(String.valueOf(p));
            }
        }

        // ===== env =====
        if (spec.env != null) {
            for (Map.Entry<String, String> e : spec.env.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || !ENV_KEY.matcher(k).matches()) {
                    throw new IllegalArgumentException("invalid env key: " + k);
                }
                if (v == null) v = "";
                if (v.length() > 8192) throw new IllegalArgumentException("env value too long: " + k);
                args.add("-e");
                args.add(k + "=" + v);
            }
        }

        // ===== workingDir (-w) =====
        if (spec.workingDir != null && !spec.workingDir.trim().isEmpty()) {
            args.add("-w");
            args.add(spec.workingDir.trim());
        }

        // ===== ports =====
        if (spec.ports != null) {
            for (RunSpec.Port p : spec.ports) {
                if (p.exposeOnly) {
                    // only expose
                    args.add("--expose");
                    args.add(p.container + "/" + (p.protocol == null ? "tcp" : p.protocol.toLowerCase(Locale.ROOT)));
                    continue;
                }

                requirePortRange(p.container, "container");
                if (p.host == null) throw new IllegalArgumentException("host port required when publish");
                requirePortRange(p.host, "host");

                String proto = (p.protocol == null) ? "tcp" : p.protocol.toLowerCase(Locale.ROOT);
                if (!ALLOWED_PROTOCOL.contains(proto))
                    throw new IllegalArgumentException("protocol not allowed: " + proto);

                // hostIp 可选
                String hostIp = (p.hostIp == null || p.hostIp.isBlank()) ? "" : (p.hostIp.trim() + ":");
                args.add("-p");
                args.add(hostIp + p.host + ":" + p.container + "/" + proto);
            }
        }

        // ===== volumes (-v) =====
        if (spec.volumes != null) {
            for (String v : spec.volumes) {
                if (v == null || v.isBlank()) continue;
                // 基本校验：必须至少有 source:target
                String vv = v.trim();
                int firstColon = vv.indexOf(':');
                int lastColon = vv.lastIndexOf(':');
                if (firstColon <= 0 || firstColon == vv.length() - 1) {
                    throw new IllegalArgumentException("invalid -v volume format: " + vv);
                }
                // 可选：如果有第三段，只允许 ro/rw（你也可以允许更多）
                if (lastColon != firstColon) {
                    String opt = vv.substring(lastColon + 1);
                    if (!opt.equals("ro") && !opt.equals("rw")) {
                        throw new IllegalArgumentException("invalid -v option (only ro/rw allowed): " + vv);
                    }
                }
                args.add("-v");
                args.add(vv);
            }
        }

        return args;
    }

    private static void requirePortRange(int port, String field) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException(field + " port out of range: " + port);
    }

    private static String trimTrailingZeros(double v) {
        String s = Double.toString(v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}