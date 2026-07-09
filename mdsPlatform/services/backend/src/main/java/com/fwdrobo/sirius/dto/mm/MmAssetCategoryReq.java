package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record MmAssetCategoryReq(
        @NotEmpty List<UUID> categoryIds
) {
}
