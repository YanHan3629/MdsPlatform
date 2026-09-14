package com.fwdrobo.sirius.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.*;

/** Validated, canonical processing configuration; captured immutably in each task. */
public final class IndexProcessingConfig {
    private IndexProcessingConfig() {}
    public static Map<String,Object> validate(Map<String,Object> input) {
        Map<String,Object> value = input == null ? Map.of() : input;
        if (!Set.of("timezone","timeColumn","entityColumns","unitMapping","assetMappings").containsAll(value.keySet()))
            throw ExceptionUtils.badRequest("索引映射包含未知字段");
        TreeMap<String,Object> result = new TreeMap<>();
        for (String key : List.of("timezone","timeColumn")) {
            Object raw = value.get(key);
            if (raw != null && (!(raw instanceof String) || ((String)raw).length()>128))
                throw ExceptionUtils.badRequest("映射字段必须是短字符串: " + key);
            if (raw instanceof String text && !text.isBlank()) {
                if (key.equals("timezone")) { try { ZoneId.of(text); } catch(Exception e) { throw ExceptionUtils.badRequest("无效来源时区"); } }
                result.put(key,text);
            }
        }
        if (value.containsKey("entityColumns")) {
            if (!(value.get("entityColumns") instanceof List<?> list) || list.size()>16 || list.stream().anyMatch(x -> !(x instanceof String s) || s.isBlank() || s.length()>128))
                throw ExceptionUtils.badRequest("实体分组字段无效");
            result.put("entityColumns",value.get("entityColumns"));
        }
        if (value.containsKey("unitMapping")) {
            if (!(value.get("unitMapping") instanceof Map<?,?> map) || map.size()>256)
                throw ExceptionUtils.badRequest("单位映射必须是有限字段字典");
            TreeMap<String,String> units = new TreeMap<>();
            for (var entry : ((Map<?,?>)value.get("unitMapping")).entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.length()>128 || !(entry.getValue() instanceof String unit) || unit.isBlank() || unit.length()>32)
                    throw ExceptionUtils.badRequest("单位映射值无效");
                units.put(key,unit);
            }
            result.put("unitMapping",units);
        }
        if (value.containsKey("assetMappings")) {
            if (!(value.get("assetMappings") instanceof Map<?,?> map) || map.size()>500)
                throw ExceptionUtils.badRequest("按文件映射最多 500 项");
            TreeMap<String,Object> assets = new TreeMap<>();
            for (var entry : ((Map<?,?>)value.get("assetMappings")).entrySet()) {
                if (!(entry.getKey() instanceof String path) || !path.startsWith("/") || path.contains("..") || path.length()>512
                        || !(entry.getValue() instanceof Map<?,?> settings) || settings.containsKey("assetMappings"))
                    throw ExceptionUtils.badRequest("按文件映射路径或配置无效");
                assets.put(path,validate((Map<String,Object>) settings));
            }
            result.put("assetMappings",assets);
        }
        return result;
    }
    public static String json(Map<String,Object> value) {
        try { return new ObjectMapper().writeValueAsString(validate(value)); }
        catch (java.io.IOException e) { throw new IllegalArgumentException(e); }
    }
    public static String fingerprint(Map<String,Object> value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
