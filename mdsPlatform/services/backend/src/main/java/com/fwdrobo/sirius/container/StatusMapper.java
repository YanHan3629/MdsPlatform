package com.fwdrobo.sirius.container;

import com.fwdrobo.sirius.entity.job.ContainerStatus;
import com.fwdrobo.sirius.entity.job.RunStatus;

public class StatusMapper {
    public static ContainerStatus mapToContainerStatus(String dockerStatus) {
        if (dockerStatus == null) return ContainerStatus.UNKNOWN;
        return switch (dockerStatus) {
            case "running" -> ContainerStatus.RUNNING;
            case "created", "restarting" -> ContainerStatus.CREATED;
            case "exited", "dead" -> ContainerStatus.STOPPED;
            default -> ContainerStatus.UNKNOWN;
        };
    }

    public static RunStatus mapToRunStatus(String dockerStatus, Integer exitCode) {
        if (dockerStatus == null) return RunStatus.FAILED;
        return switch (dockerStatus) {
            case "running", "created", "restarting" -> RunStatus.RUNNING;
            case "exited", "dead" -> (exitCode != null && exitCode == 0) ? RunStatus.SUCCEEDED : RunStatus.FAILED;
            default -> RunStatus.FAILED;
        };
    }

    public static boolean isTerminalOrCanceling(RunStatus s) {
        return s == RunStatus.SUCCEEDED
                || s == RunStatus.FAILED
                || s == RunStatus.CANCELED
                || s == RunStatus.CANCELING;
    }
}
