package com.fwdrobo.sirius.docker.service;

import java.util.*;

public class RunSpec {
    public String image;
    public List<String> cmd = new ArrayList<>();
    public Map<String, String> env = new LinkedHashMap<>();
    public List<String> volumes = new ArrayList<>();
    public String workingDir;
    public Runtime runtime = new Runtime();
    public String gpus;

    public List<Port> ports = new ArrayList<>();
    public String network;
    public String ipc;
    public Resources resources;
    public Restart restart;

    public static class Runtime {
        public boolean interactive;
        public boolean tty;
        public boolean autoRemove;
    }

    public static class Port {
        public Integer host;
        public int container;
        public String protocol = "tcp";
        public String hostIp;
        public boolean exposeOnly;
    }

    public static class Resources {
        public Double cpus;
        public Integer memoryMb;
        public Integer memorySwapMb;
        public Integer pidsLimit;
        public String cpusetCpus;
        public String cpusetMems;
    }

    public static class Restart {
        public String name;
        public Integer maxRetries;
    }
}