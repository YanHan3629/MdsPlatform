package com.fwdrobo.sirius.entity.job;

public enum ContainerStatus {
    MISSING,
    UNKNOWN,
    PENDING_CREATE,
    CREATED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REMOVED,
    STOPPED,
    REMOVE_FAILED,
    STOP_FAILED
}