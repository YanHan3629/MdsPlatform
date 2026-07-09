package com.fwdrobo.sirius.docker.service;


import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.regex.Pattern;

public final class JsonParamConverter {

    private static final Pattern NUM = Pattern.compile("^[0-9]+$");

    private JsonParamConverter() {}

    public static RunSpec toRunSpec(JsonParam jp) {
        if (jp == null) throw new IllegalArgumentException("jsonParam is null");

        RunSpec s = new RunSpec();

        // workingDir
        if (jp.workingDir != null) {
            s.workingDir = jp.workingDir;
        }

        // runtime strings -> booleans
        if (jp.runtime != null) {
            s.runtime.interactive = parseBool(jp.runtime.interactive);
            s.runtime.tty = parseBool(jp.runtime.tty);
            s.runtime.autoRemove = parseBool(jp.runtime.autoRemove);
        }

        // gpus
        if (jp.gpus != null) {
            s.gpus = blankToNull(jp.gpus);
        }

        // volumes: each line -> Mount object
        if (!CollectionUtils.isEmpty(jp.volumes)) {
            s.volumes = new ArrayList<>(jp.volumes);
        }

        // network/ipc
        if (jp.network != null) {
            s.network = blankToNull(jp.network);
            s.ipc = blankToNull(jp.ipc);
        }

        // resources
        if (jp.resources != null) {
            s.resources = parseResources(jp.resources);
        }

        // restart
        if (jp.restart != null) {
            s.restart = parseRestart(jp.restart);
        }

        // ports（如果你后续要支持字符串化 ports）
        if (jp.ports != null) {
            for (String line : jp.ports) {
                if (line == null || line.isBlank()) continue;
                s.ports.add(parsePortLine(line));
            }
        }

        return s;
    }

    // ---------- parsers ----------

    private static boolean parseBool(String s) {
        return s != null && Boolean.parseBoolean(s.trim());
    }


    private static RunSpec.Resources parseResources(JsonParam.Resources r) {
        RunSpec.Resources out = new RunSpec.Resources();
        if (notBlank(r.cpus)) out.cpus = Double.parseDouble(r.cpus.trim());
        if (notBlank(r.memoryMb)) out.memoryMb = parseIntStrict(r.memoryMb, "resources.memoryMb");
        if (notBlank(r.memorySwapMb)) out.memorySwapMb = parseIntStrict(r.memorySwapMb, "resources.memorySwapMb");
        if (notBlank(r.pidsLimit)) out.pidsLimit = parseIntStrict(r.pidsLimit, "resources.pidsLimit");
        if (notBlank(r.cpusetCpus)) out.cpusetCpus = r.cpusetCpus.trim();
        if (notBlank(r.cpusetMems)) out.cpusetMems = r.cpusetMems.trim();
        return out;
    }

    private static RunSpec.Restart parseRestart(JsonParam.Restart r) {
        RunSpec.Restart out = new RunSpec.Restart();
        out.name = blankToNull(r.name);
        if (notBlank(r.maxRetries)) out.maxRetries = parseIntStrict(r.maxRetries, "restart.maxRetries");
        return out;
    }

    private static RunSpec.Port parsePortLine(String line) {
        // line example: "host=18080,container=8080,protocol=tcp" or "container=8080,exposeOnly=true"
        Map<String, String> kv = parseCommaKv(line);
        RunSpec.Port p = new RunSpec.Port();
        if (kv.containsKey("host")) p.host = parseIntStrict(kv.get("host"), "ports.host");
        p.container = parseIntStrict(kv.getOrDefault("container", ""), "ports.container");
        p.protocol = kv.getOrDefault("protocol", "tcp").toLowerCase(Locale.ROOT);
        p.hostIp = kv.get("hostIp");
        p.exposeOnly = Boolean.parseBoolean(kv.getOrDefault("exposeOnly", "false"));
        return p;
    }

    private static Map<String, String> parseCommaKv(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int i = p.indexOf('=');
            if (i <= 0) continue;
            String k = p.substring(0, i).trim();
            String v = p.substring(i + 1).trim();
            m.put(k, v);
        }
        return m;
    }

    private static int parseIntStrict(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(field + " required");
        String t = s.trim();
        if (!NUM.matcher(t).matches()) throw new IllegalArgumentException(field + " must be integer: " + s);
        return Integer.parseInt(t);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String blankToNull(String s) { return notBlank(s) ? s.trim() : null; }
}