package com.fwdrobo.sirius.docker.service;


import java.util.*;

public class JsonParam {

        public Runtime runtime = new Runtime();
        public List<String> volumes = new ArrayList<>();     // 每条 mount 一行字符串
        public String workingDir;                           // 直接字符串
        public String gpus;                                   // gpus.mode + deviceIds/count
        public Map<String, Object> env = new LinkedHashMap<>();

        // 可选：后续你要扩展 ports/network/ipc/resources/restart 也可以继续加（同样字符串化）
        public List<String> ports = new ArrayList<>();      // e.g. "host=18080,container=8080,protocol=tcp"
        public String network;                              // "bridge"/"host"/"none"
        public String ipc;                                  // "private"/"host"/"shareable"
        public Resources resources;                         // 字符串化数值
        public Restart restart;                             // 字符串化


    public static class Runtime {
        public String interactive; // "true"/"false"
        public String tty;         // "true"/"false"
        public String autoRemove;  // "true"/"false"
    }

    public static class Resources {
        public String cpus;        // "2" or "2.5"
        public String memoryMb;    // "131072"
        public String memorySwapMb;// "196608"
        public String pidsLimit;   // "512"
        public String cpusetCpus;  // "0-23"
        public String cpusetMems;  // "0"
    }

    public static class Restart {
        public String name;        // "no"|"always"|...
        public String maxRetries;  // "3"
    }
}