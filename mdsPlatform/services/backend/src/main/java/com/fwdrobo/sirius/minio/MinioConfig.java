package com.fwdrobo.sirius.minio;

import io.minio.MinioClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    @Bean("minioInternalClient")
    public MinioClient minioInternalClient(MinioProperties p) {
        return MinioClient.builder()
                .endpoint(p.getEndpoint())
                .credentials(p.getAccessKey(), p.getSecretKey())
                .build();
    }

    @Bean("minioPresignClient")
    public MinioClient minioPresignClient(MinioProperties p) {
        String endpoint = StringUtils.isBlank(p.getPublicEndpoint()) ? p.getEndpoint() : p.getPublicEndpoint();
        String accessKey = requirePresignCredential(p.getPresignAccessKey());
        String secretKey = requirePresignCredential(p.getPresignSecretKey());
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    private static String requirePresignCredential(String value) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException(
                    "MinIO presign credentials are required: please set MINIO_PRESIGN_ACCESS_KEY and MINIO_PRESIGN_SECRET_KEY"
            );
        }
        return value;
    }
}
