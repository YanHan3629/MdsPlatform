package com.fwdrobo.sirius.minio;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Setter
@Getter
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {
    private String endpoint;
    private String publicEndpoint;
    private String accessKey;
    private String secretKey;
    private String presignAccessKey;
    private String presignSecretKey;
    private String bucket;
}
