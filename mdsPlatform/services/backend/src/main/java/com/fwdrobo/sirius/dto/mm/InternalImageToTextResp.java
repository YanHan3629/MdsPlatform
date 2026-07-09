package com.fwdrobo.sirius.dto.mm;

import java.util.List;
import java.util.UUID;

public record InternalImageToTextResp(
        List<Item> items
) {
    public record Item(
            UUID assetId,
            Double score,
            String text,
            String logicalPath
    ) {
    }
}
