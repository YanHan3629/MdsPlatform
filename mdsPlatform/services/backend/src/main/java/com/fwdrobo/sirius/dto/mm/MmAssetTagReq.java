package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record MmAssetTagReq(
        @NotEmpty List<String> tagNames
) {
}
