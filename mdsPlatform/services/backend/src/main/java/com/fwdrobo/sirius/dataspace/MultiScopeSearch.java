package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fwdrobo.sirius.port.SearchServiceClient;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
class MultiScopeSearch {
    private static final int PARALLEL_SCOPES = 3;
    private final SearchServiceClient search;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    MultiScopeSearch(SearchServiceClient search, ObjectMapper json, JdbcTemplate jdbc) {
        this.search = search;
        this.json = json;
        this.jdbc = jdbc;
    }

    ResponseEntity<byte[]> search(FrozenScopes scopes, Map<String,Object> body, MultipartFile image) throws IOException {
        if (!(json.valueToTree(body.get("plan")) instanceof ObjectNode original))
            throw FrozenScopes.invalid("请提供有效的检索条件。");
        int topK = original.path("topK").asInt(original.path("top_k").asInt(8));
        if (topK < 1 || topK > 50) throw FrozenScopes.invalid("结果上限应为 1 至 50。");
        int offset = parseOffset(body.get("offset"), topK);
        int size = pageSize(body.get("size"), topK);
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(4).toNanos();
        List<Plan> plans = new ArrayList<>();
        for (int start = 0; start < scopes.items().size(); start += PARALLEL_SCOPES) {
            var batch = scopes.items().subList(start, Math.min(start+PARALLEL_SCOPES, scopes.items().size()));
            var pending = new LinkedHashMap<FrozenScopes.Scope,CompletableFuture<HttpResponse<byte[]>>>();
            try {
                for (var scope : batch) pending.put(scope, search.dispatchScopedJson("/indexes/" + scope.datasetId() +
                        "/" + scope.versionId() + "/" + scope.indexVersionId() + "/capabilities?summary=true",
                        null, timeLeft(deadline)));
                for (var entry : pending.entrySet()) {
                    var capabilities = await(entry.getValue(),deadline);
                    plans.add(new Plan(entry.getKey(), forIndex(original, capabilities.path("filters"), topK)));
                }
            } finally {
                pending.values().forEach(task -> task.cancel(true));
            }
        }
        List<ObjectNode> status = new ArrayList<>();
        List<Hit> hits = new ArrayList<>();
        var limitations = new LinkedHashSet<String>();
        var searched = image == null ? searchText(plans,deadline) : Map.<FrozenScopes.Scope,SearchPack>of();
        for (var plan : plans) {
            long started = System.nanoTime();
            var scope = plan.scope;
            var textPack = searched.get(scope);
            JsonNode pack = textPack==null ? json.createObjectNode() : textPack.payload;
            if (plan.query != null) {
                if (image != null) pack = responseObject(search.scopedImage(scope.datasetId(),scope.versionId(),
                        scope.indexVersionId(),json.writeValueAsString(plan.query),image,timeLeft(deadline)));
                if (!scope.versionId().toString().equals(pack.path("snapshot").path("dataVersion").asText()) ||
                    !scope.indexVersionId().toString().equals(pack.path("snapshot").path("indexVersion").asText()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "检索索引已变化，请重新选择范围。");
            }
            int rank = 0;
            var identities = sourceIdentities(scope);
            for (JsonNode item : pack.path("evidence")) {
                if (!(item instanceof ObjectNode hit) ||
                        !scope.versionId().toString().equals(hit.path("dataVersion").asText()) ||
                        !scope.indexVersionId().toString().equals(hit.path("indexVersion").asText()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "检索结果与冻结范围不一致。");
                hit.put("datasetId", scope.datasetId().toString());
                var source = identities.get(hit.path("assetId").asText());
                if(source!=null)hit.set("sourceIdentity",json.valueToTree(source));
                hits.add(new Hit(scope, ++rank, hit, source));
                if (rank >= topK) break;
            }
            for (JsonNode limitation : pack.path("limitations")) {
                String code = limitation.asText().split(":",2)[0];
                if (code.contains("NOT_READY") || code.contains("UNAVAILABLE") || code.contains("FAILED") || code.contains("TIMEOUT"))
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "所选范围有索引能力不可用，请稍后重试。");
                limitations.add("EVIDENCE_CONFLICT".equals(code) ? "部分记录存在差异，请核对各来源。" : "部分检索结果有适用范围限制，请核对来源定位。");
            }
            var info = json.createObjectNode();
            info.putPOJO("scope", scope);
            info.put("status","COMPLETED");
            info.put("candidates",rank);
            info.put("elapsedMs",textPack==null ? (System.nanoTime()-started)/1_000_000 : textPack.elapsedMs);
            info.set("coverage", pack.path("coverage").isObject()?pack.path("coverage"):json.createObjectNode());
            status.add(info);
        }
        hits.sort(Comparator.comparingInt(Hit::rank).thenComparing(hit -> hit.scope.datasetId()));
        // Local scores from different index and modality families have different scales.
        // Equal ranks share a fusion priority. Stable frozen IDs break ties.
        var unique = new LinkedHashMap<Object, ObjectNode>();
        for (var hit : hits) {
            var value = hit.value;
            var locator = value.path("locator");
            String kind = value.path("evidenceType").asText();
            boolean locatedSource = hit.source != null && locator.isObject() && !locator.isEmpty() &&
                    ("retrieval".equals(kind) || "constraint".equals(kind));
            Object key = locatedSource
                    ? new IdenticalLocation(hit.source.ownerOrgId, hit.source.sha256, locator)
                    : hit.scope.datasetId() + ":" + value.path("evidenceId").asText();
            var previous = unique.putIfAbsent(key, value);
            if (previous != null && locatedSource) {
                String datasetId = hit.scope.datasetId().toString();
                String fileId = hit.source.fileId().toString();
                if (datasetId.equals(previous.path("datasetId").asText()) &&
                        fileId.equals(previous.path("sourceIdentity").path("fileId").asText())) continue;
                var alternatives = previous.withArray("alternateSources");
                boolean linked = false;
                for (JsonNode alternate : alternatives) {
                    if (datasetId.equals(alternate.path("scope").path("datasetId").asText()) &&
                            fileId.equals(alternate.path("sourceIdentity").path("fileId").asText())) {
                        linked = true;
                        break;
                    }
                }
                if (!linked) {
                    var alternate = alternatives.addObject();
                    alternate.set("scope",json.valueToTree(hit.scope));
                    alternate.set("sourceIdentity",json.valueToTree(hit.source));
                    alternate.set("locator",locator);
                }
            }
        }
        var ranked = unique.values().stream().limit(topK).toList();
        var data = json.createObjectNode();
        data.putPOJO("scopes",scopes.items()).putPOJO("scopeResults",status);
        data.set("plan",original);
        data.putPOJO("evidence",ranked.stream().skip(offset).limit(size).toList());
        data.putPOJO("limitations",limitations);
        data.putPOJO("pagination",Map.of("offset",offset,"size",size,"topK",topK,"total",ranked.size(),
                "hasNext",offset+size<ranked.size()));
        data.put("rankingMethod","SCOPE_RANK");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsBytes(data));
    }

    private record Plan(FrozenScopes.Scope scope, ObjectNode query) {}
    private record SearchPack(JsonNode payload,long elapsedMs) {}
    private record Hit(FrozenScopes.Scope scope, int rank, ObjectNode value, SourceIdentity source) {}
    private record SourceIdentity(UUID ownerOrgId, UUID fileId, String logicalPath, String sha256) {}
    private record IdenticalLocation(UUID ownerOrgId, String sha256, JsonNode locator) {}

    private Map<FrozenScopes.Scope,SearchPack> searchText(List<Plan> plans, long deadline) throws IOException {
        var packs = new HashMap<FrozenScopes.Scope,SearchPack>();
        for (int start=0; start<plans.size(); start+=PARALLEL_SCOPES) {
            var batch = plans.subList(start,Math.min(start+PARALLEL_SCOPES,plans.size()));
            var pending = new LinkedHashMap<FrozenScopes.Scope,CompletableFuture<HttpResponse<byte[]>>>();
            var started = new HashMap<FrozenScopes.Scope,Long>();
            try {
                for (var plan : batch) {
                    if (plan.query==null) continue;
                    var input = new LinkedHashMap<>(plan.scope.fields());
                    input.put("plan",plan.query);
                    started.put(plan.scope,System.nanoTime());
                    pending.put(plan.scope,search.dispatchScopedJson("/search/execute",input,timeLeft(deadline)));
                }
                for (var entry : pending.entrySet()) {
                    var payload = await(entry.getValue(),deadline);
                    packs.put(entry.getKey(),new SearchPack(payload,(System.nanoTime()-started.get(entry.getKey()))/1_000_000));
                }
            } finally {
                pending.values().forEach(task -> task.cancel(true));
            }
        }
        return packs;
    }

    private JsonNode await(CompletableFuture<HttpResponse<byte[]>> task,long deadline) throws IOException {
        try {
            return responseObject(search.scopedResult(task.get(timeLeft(deadline).toNanos(),TimeUnit.NANOSECONDS)));
        } catch (TimeoutException error) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "多数据集检索已达到总处理时限。");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "检索请求已中断。");
        } catch (ExecutionException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "检索服务不可用或请求超时。");
        }
    }

    private Map<String,SourceIdentity> sourceIdentities(FrozenScopes.Scope scope) {
        return jdbc.query("""
                SELECT a.asset_id, f.file_id, f.logical_path, f.sha256, d.org_id
                FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
                JOIN mm_asset a ON a.dataset_version_id=v.version_id
                JOIN artifact_file f ON f.file_id=a.file_id AND f.commit_id=v.raw_commit_id
                WHERE d.dataset_id=? AND v.version_id=? AND f.sha256 IS NOT NULL AND a.status='ACTIVE'
                """, rows -> {
            var output = new HashMap<String,SourceIdentity>();
            while(rows.next()) {
                output.put(rows.getString("asset_id"),new SourceIdentity(rows.getObject("org_id",UUID.class),
                        rows.getObject("file_id",UUID.class),rows.getString("logical_path"),rows.getString("sha256")));
            }
            return output;
        },scope.datasetId(),scope.versionId());
    }

    private static int pageSize(Object raw, int topK) {
        if (raw == null) return topK;
        if (raw instanceof Integer value && value >= 1 && value <= topK) return value;
        throw FrozenScopes.invalid("每页条数超出本次检索上限。");
    }

    private static int parseOffset(Object raw, int topK) {
        if (raw == null) return 0;
        if (raw instanceof Integer value && value >= 0 && value < topK) return value;
        throw FrozenScopes.invalid("检索结果页码超出范围。");
    }

    private java.time.Duration timeLeft(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "多数据集检索已达到总处理时限。");
        return java.time.Duration.ofNanos(nanos);
    }

    private JsonNode responseObject(ResponseEntity<byte[]> response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful())
            throw new ResponseStatusException(response.getStatusCode(), "所选范围无法完整执行，请核对数据集、索引和检索条件。");
        if (response.getBody() == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        var payload = json.readTree(response.getBody());
        if (payload == null || !payload.isObject()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        return payload;
    }

    private ObjectNode forIndex(ObjectNode original, JsonNode filters, int topK) {
        var assets = filters.path("assets");
        var requested = original.path("retrievers");
        if (!assets.isArray() || !requested.isArray() || requested.isEmpty())
            throw FrozenScopes.invalid("请核对所选类型和数据集能力。");
        String type = original.path("contentType").asText(original.path("content_type").asText("all"));
        String corpus = original.path("corpusScope").asText(original.path("corpus_scope").asText("corpus"));
        var formats = new HashSet<String>();
        original.path("formats").forEach(format -> formats.add(format.asText()));
        var applicable = new HashSet<String>();
        var matchingFormats = new TreeSet<String>();
        for (JsonNode asset : assets) {
            if (!corpus.equals(asset.path("scope").asText()) ||
                    (!formats.isEmpty() && !formats.contains(asset.path("originalFormat").asText()))) continue;
            asset.path("contentTypes").forEach(channel -> applicable.add(channel.asText()));
            matchingFormats.add(asset.path("originalFormat").asText());
        }
        boolean constrained = !"search".equals(original.path("operation").asText("search"))
                || List.of("hardConditions","hard_conditions","objectConditions","object_conditions","tableId","table_id",
                    "targetEntity","target_entity","timeRange","time_range","assetIds","asset_ids",
                    "dependsOn","depends_on","retrieverOperations","retriever_operations")
                        .stream().anyMatch(field -> original.hasNonNull(field) && (!original.get(field).isContainerNode() || !original.get(field).isEmpty()));
        if (constrained && "all".equals(type)) throw FrozenScopes.invalid("多数据集的硬条件需要选择明确的数据类型。");
        var channels = new ArrayList<String>();
        for (JsonNode channel : requested) {
            String name = channel.asText();
            if (!Set.of("text","visual","structured","timeseries","geometry").contains(name))
                throw FrozenScopes.invalid("检索类型不受支持。");
            if (applicable.contains(name)) channels.add(name);
            else if (constrained) throw FrozenScopes.invalid("有数据集不支持所选类型或条件，请调整范围或移除条件。");
        }
        if (channels.isEmpty()) return null;
        if (channels.contains("structured") && constrained)
            throw FrozenScopes.invalid("表格硬条件需在单个数据集内选择已核验的业务表。跨数据集可使用文本或无硬条件的表格检索。");
        var query = original.deepCopy();
        query.putPOJO("retrievers", channels);
        if (!formats.isEmpty()) query.putPOJO("formats", matchingFormats);
        if (channels.contains("structured"))
            query.put("all_tables_search", true);
        query.remove("top_k");
        query.put("topK", topK);
        return query;
    }
}
