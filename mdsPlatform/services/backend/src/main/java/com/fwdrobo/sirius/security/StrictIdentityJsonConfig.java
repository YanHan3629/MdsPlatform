package com.fwdrobo.sirius.security;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
/** Reject ambiguous and undeclared DTO fields before any service mutation. */
@Configuration
public class StrictIdentityJsonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer strictRequestFields() {
        return builder->builder.featuresToEnable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION,
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
