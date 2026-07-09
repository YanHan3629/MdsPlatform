package com.fwdrobo.sirius.docker.entity;

public enum DockerServer {
    LOCAL,
    GPU_01,
    CPU_02;

    public static DockerServer fromString(String s) {
        if (s == null) throw new IllegalArgumentException("dockerServer is null");

        if (s.isEmpty()) throw new IllegalArgumentException("dockerServer is blank");

        try {
            return DockerServer.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown dockerServer: " + s + ", allowed: " + java.util.Arrays.toString(DockerServer.values()),
                    e
            );
        }
    }
}

