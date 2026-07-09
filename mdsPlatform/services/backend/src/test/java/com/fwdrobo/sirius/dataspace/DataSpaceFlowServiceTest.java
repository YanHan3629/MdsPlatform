package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSpaceFlowServiceTest {
    @Test
    void runsVal2017ProviderFlow() {
        Path imageDir = Path.of("../../../dataset/val2017");
        Path captionsJson = Path.of("../../../dataset/captions_val2017.json");
        Assumptions.assumeTrue(Files.isDirectory(imageDir), "val2017 image directory is not available");
        Assumptions.assumeTrue(Files.isRegularFile(captionsJson), "captions_val2017.json is not available");

        DataSpaceFlowService service = new DataSpaceFlowService(new ObjectMapper());
        service.init();

        Map<String, Object> result = service.runVal2017Flow(Map.of(
                "imageDir", imageDir.toString(),
                "captionsJson", captionsJson.toString(),
                "datasetName", "COCO Val2017 图文治理数据集",
                "productName", "COCO Val2017 图文检索数据产品"));

        assertEquals(true, result.get("ok"));
        Map<?, ?> stats = (Map<?, ?>) result.get("stats");
        assertEquals(5000, stats.get("imageCount"));
        assertEquals(5000, stats.get("jsonImageCount"));
        assertEquals(25014, stats.get("annotationCount"));

        Map<?, ?> space = (Map<?, ?>) result.get("space");
        Map<?, ?> source = (Map<?, ?>) result.get("source");
        Map<?, ?> dataset = (Map<?, ?>) result.get("dataset");
        Map<?, ?> product = (Map<?, ?>) result.get("product");

        assertEquals("VERIFIED", space.get("authStatus"));
        assertEquals("CONNECTED", source.get("status"));
        assertEquals("READY", dataset.get("governanceStatus"));
        assertEquals("READY", dataset.get("indexStatus"));
        assertEquals("PUBLISHED", product.get("status"));
        assertTrue(((String) product.get("productName")).contains("COCO Val2017"));
    }
}
