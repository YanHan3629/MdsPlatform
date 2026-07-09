package com.fwdrobo.sirius.dto.mm;

import java.util.List;
import java.util.UUID;

public record InternalTextToImageResp(
        List<Item> items
) {
    public record Item(
            UUID assetId,
            Double score,
            String logicalPath,
            List<String> texts
    ) {
    }
}
