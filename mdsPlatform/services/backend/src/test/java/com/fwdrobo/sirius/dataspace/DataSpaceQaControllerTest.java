package com.fwdrobo.sirius.dataspace;

import com.fwdrobo.sirius.port.SearchServiceClient;
import com.fwdrobo.sirius.service.MmAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DataSpaceQaControllerTest {
    @Test void preservesPresignedUrlEncodingWhenDownloadingSource() {
        var controller = controller();
        var assets = (MmAssetService) ReflectionTestUtils.getField(controller, "assets");
        var asset = mock(com.fwdrobo.sirius.dto.mm.MmAssetResp.class);
        UUID dataset = UUID.randomUUID(), version = UUID.randomUUID(), id = UUID.randomUUID();
        when(assets.getAsset(dataset, version, id)).thenReturn(asset);
        when(asset.previewUrl()).thenReturn("http://minio:9000/file?X-Amz-Credential=key%2Fdate%2Fs3");
        when(asset.contentType()).thenReturn("image/png");
        when(asset.fileName()).thenReturn("sample.png");
        var http = (RestTemplate) ReflectionTestUtils.getField(controller, "http");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://minio:9000/file?X-Amz-Credential=key%2Fdate%2Fs3"))
                .andRespond(withSuccess(new byte[]{1, 2}, MediaType.IMAGE_PNG));
        assertArrayEquals(new byte[]{1, 2}, controller.source(dataset, version, id).getBody());
        server.verify();
    }

    private DataSpaceQaController controller() {
        return spy(new DataSpaceQaController(mock(JdbcTemplate.class), mock(SearchServiceClient.class),
                mock(MmAssetService.class), "http://qa-service:18081"));
    }

    @Test void rejectsForeignStaleAndPartialScopesBeforeForwarding() {
        var controller = controller();
        Map<String, Object> scope = Map.of("datasetId", UUID.randomUUID().toString(),
                "versionId", UUID.randomUUID().toString(), "indexVersionId", UUID.randomUUID().toString());
        doReturn(List.of(scope)).when(controller).datasets();
        assertDoesNotThrow(() -> controller.validateScope(scope));
        assertDoesNotThrow(() -> controller.validateScope(Map.of("question", "x")));
        assertThrows(ResponseStatusException.class, () -> controller.validateScope(Map.of("datasetId", scope.get("datasetId"))));
        var stale = new HashMap<>(scope); stale.put("indexVersionId", UUID.randomUUID().toString());
        assertThrows(ResponseStatusException.class, () -> controller.validateScope(stale));
    }

    @Test void preservesJsonAuthorizationAndUpstreamErrorStatus() {
        var controller = controller();
        var http = (RestTemplate) ReflectionTestUtils.getField(controller, "http");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://qa-service:18081/api/v1/qa"))
                .andExpect(header("Authorization", "Bearer request-a"))
                .andExpect(content().json("{\"question\":\"测试\"}"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"invalid input\"}"));
        var response = controller.askJson(Map.of("question", "测试"), "Bearer request-a");
        assertEquals(400, response.getStatusCode().value());
        server.verify();
    }

    @Test void forwardsMultipartImagesAndEnforcesLocalImageCount() throws Exception {
        var controller = controller();
        var http = (RestTemplate) ReflectionTestUtils.getField(controller, "http");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo("http://qa-service:18081/api/v1/qa"))
                .andExpect(header("Authorization", "Bearer request-b"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"query.png\"")))
                .andRespond(withSuccess("{\"answer\":\"ok\"}", MediaType.APPLICATION_JSON));
        var fields = new LinkedMultiValueMap<String, String>(); fields.add("question", "图片问题");
        var image = new MockMultipartFile("images", "query.png", "image/png", new byte[]{1, 2});
        assertEquals(200, controller.askMultipart(fields, List.of(image), "Bearer request-b").getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> controller.askMultipart(fields,
                List.of(image, image, image), "Bearer request-b"));
        server.verify();
    }
}
