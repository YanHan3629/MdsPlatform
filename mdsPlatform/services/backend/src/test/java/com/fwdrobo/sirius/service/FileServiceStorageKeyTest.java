package com.fwdrobo.sirius.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileServiceStorageKeyTest {
    private static final UUID ARTIFACT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMMIT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final FileService service = new FileService(null, null, null, null, null);

    @Test
    void keepsLogicalPathUnderAFormatSpecificPhysicalPrefix() {
        assertEquals(
                "workspace/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/pdf/fridge/manuals/01.pdf",
                service.buildWorkspaceKey(ARTIFACT_ID, COMMIT_ID, "/fridge/manuals/01.pdf", "application/pdf"));
        assertEquals(
                "workspace/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222/image/fridge/images/img.png",
                service.buildWorkspaceKey(ARTIFACT_ID, COMMIT_ID, "/fridge/images/img.png", "image/png"));
    }
}
