package com.fwdrobo.sirius.service;

import java.util.UUID;

public record MmIndexPreparedEvent(UUID datasetId, UUID datasetVersionId, UUID indexVersionId) {
}
