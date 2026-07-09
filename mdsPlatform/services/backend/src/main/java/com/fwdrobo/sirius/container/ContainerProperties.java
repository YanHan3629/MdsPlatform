package com.fwdrobo.sirius.container;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Docker 容器相关配置
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.docker")
public class ContainerProperties {
    
    private String labelApp;
    private String host;
    private Volume volume;
    private LogStreamer logStreamer = new LogStreamer();
    public Map<String, String> servers;
    
    @Getter
    @Setter
    public static class Volume {
        private Job job;
        
        @Getter
        @Setter
        public static class Job {
            private String vol;
            private String prefix;
        }
    }
    
    @Getter
    @Setter
    public static class LogStreamer {
        /**
         * 日志分段上传的最大文件大小
         * 支持格式：10m, 50m, 100m, 1048576 (字节)
         * 默认值：10m
         */
        private String maxPartSize = "10m";
        
        /**
         * 日志强制刷新间隔（毫秒）
         * 默认值：60000 (1分钟)
         */
        private long flushIntervalMs = 60000;
        
        /**
         * 解析大小字符串为字节数
         */
        public long getMaxPartSizeBytes() {
            String size = maxPartSize.toLowerCase().trim();
            long multiplier = 1;
            
            if (size.endsWith("k") || size.endsWith("kb")) {
                multiplier = 1024;
                size = size.replaceAll("[kb]+$", "");
            } else if (size.endsWith("m") || size.endsWith("mb")) {
                multiplier = 1024 * 1024;
                size = size.replaceAll("[mb]+$", "");
            } else if (size.endsWith("g") || size.endsWith("gb")) {
                multiplier = 1024 * 1024 * 1024;
                size = size.replaceAll("[gb]+$", "");
            }
            
            try {
                return Long.parseLong(size) * multiplier;
            } catch (NumberFormatException e) {
                // 默认 10MB
                return 10 * 1024 * 1024;
            }
        }
    }
}
