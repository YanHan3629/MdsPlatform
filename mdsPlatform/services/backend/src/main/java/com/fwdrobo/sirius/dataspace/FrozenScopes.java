package com.fwdrobo.sirius.dataspace;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

record FrozenScopes(List<FrozenScopes.Scope> items) {
    private static final List<String> FIELDS = List.of("dataset_id", "version_id", "index_version_id");
    record Scope(UUID datasetId, UUID versionId, UUID indexVersionId) {
        Map<String,Object> fields() {
            return Map.of("dataset_id", datasetId, "version_id", versionId, "index_version_id", indexVersionId);
        }
    }

    static FrozenScopes from(Map<String,Object> normalized, boolean userInputOnly, int maximum) {
        boolean hasLegacy = FIELDS.stream().anyMatch(normalized::containsKey);
        Scope legacy = hasLegacy ? scope(normalized) : null;
        if (!normalized.containsKey("scopes")) {
            if (userInputOnly && !hasLegacy) return new FrozenScopes(List.of());
            if (!userInputOnly && hasLegacy) return new FrozenScopes(List.of(legacy));
            throw invalid(userInputOnly ? "仅附件模式请清除数据空间范围。" : "请选择至少一个数据集及其可用索引。");
        }
        if (!(normalized.get("scopes") instanceof List<?> values) || values.size() > maximum)
            throw invalid("一次最多选择 " + maximum + " 个数据集。");
        if (userInputOnly) {
            if (hasLegacy || !values.isEmpty()) throw invalid("仅附件模式请清除数据空间范围。");
            return new FrozenScopes(List.of());
        }
        if (values.isEmpty()) throw invalid("请选择至少一个数据集及其可用索引。");
        var result = new ArrayList<Scope>();
        var datasets = new HashSet<UUID>();
        for (Object value : values) {
            if (!(value instanceof Map<?,?> mapping)) throw invalid("数据集范围格式不正确。");
            var fields = new LinkedHashMap<String,Object>();
            mapping.forEach((key, field) -> {
                if (!(key instanceof String) || !Set.of("datasetId","versionId","indexVersionId",
                        "dataset_id","version_id","index_version_id").contains(key)) throw invalid("数据集范围含有未知字段。");
                fields.put((String) key, field);
            });
            Scope scope = scope(DataSpaceQaController.normalizeScope(fields));
            if (!datasets.add(scope.datasetId)) throw invalid("同一数据集只能选择一个冻结版本和索引。");
            result.add(scope);
        }
        if (legacy != null && (result.size() != 1 || !legacy.equals(result.get(0))))
            throw invalid("新旧数据集范围设置不一致，请重新选择。");
        return new FrozenScopes(List.copyOf(result));
    }

    private static Scope scope(Map<String,Object> fields) {
        try {
            return new Scope(id(fields.get(FIELDS.get(0))), id(fields.get(FIELDS.get(1))), id(fields.get(FIELDS.get(2))));
        } catch (IllegalArgumentException ex) {
            throw invalid("请为每个数据集提供有效的版本和索引标识。");
        }
    }

    private static UUID id(Object raw) {
        String text = Objects.toString(raw, "");
        if (!text.matches("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException();
        return UUID.fromString(text);
    }

    static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
