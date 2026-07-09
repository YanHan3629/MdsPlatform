package com.fwdrobo.sirius.dto.mm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MmSpaceReq(
        @NotBlank @Size(max = 128) String spaceName,
        @Size(max = 500) String description
) {
}
