package com.fwdrobo.sirius.dataspace;

import com.fwdrobo.sirius.dto.file.FileResp;
import com.fwdrobo.sirius.security.AuthorizationService;
import com.fwdrobo.sirius.service.FileService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class DashboardService {
    private static final String CATALOG = "data.catalog.read";
    private static final String PREVIEW = "data.preview";
    private static final String PURPOSE = "GENERAL_READ";
    private static final int PAGE_SIZE = 10;
    private static final int IMAGE_PAGE_SIZE = 4;
    private static final int SOURCE_LIMIT = 16 * 1024 * 1024;
    private static final int CACHE_IMAGES = 160;
    private static final Set<String> FORMATS = Set.of("JPG","PNG","PDF","CSV","XLSX","JSON","JSONL","TXT","SVG","OBJ","STEP");
    private static final List<Business> BUSINESS = List.of(
            new Business("procurement", "供应采购"), new Business("rd", "产品设计与规格"),
            new Business("manufacturing", "生产制造"), new Business("quality", "质量检验"),
            new Business("warehousing", "仓储包装"), new Business("transport", "运输交接"),
            new Business("fulfilment", "订单履约"), new Business("service", "售后运维"),
            new Business("reference", "基础资料"));
    private static final Set<String> BUSINESS_CODES = Set.copyOf(BUSINESS.stream().map(Business::code).toList());
    private static final String SCOPE_SQL = """
            SELECT d.dataset_id, d.dataset_name, d.raw_repo_id, d.org_id,
              COALESCE(e.enterprise_name, o.org_name) AS org_name,
              v.version_id, v.version_name, v.raw_commit_id,
              coalesce(v.version_status,'NO_VERSION') AS version_status,
              coalesce(i.index_status,CASE WHEN v.version_status='FAILED' THEN 'FAILED'
                 WHEN v.version_status='INDEXING' THEN 'BUILDING' ELSE 'NOT_BUILT' END) AS index_status, i.index_version_id,
              cv.view_id, cv.authorization_version,
              m.display_name, m.business_codes, c.catalog_name
            FROM mm_dataset d JOIN org o ON o.org_id=d.org_id
            LEFT JOIN ds_enterprise_identity e ON e.org_id=d.org_id AND e.state='CONFIRMED'
            LEFT JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
            LEFT JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
              AND i.index_status='READY'
            LEFT JOIN ds_controlled_view cv ON cv.dataset_id=d.dataset_id AND cv.version_id=v.version_id
              AND cv.index_version_id=i.index_version_id AND cv.state='READY'
            LEFT JOIN ds_dashboard_business_metadata m ON m.version_id=v.version_id
            LEFT JOIN ds_source s ON s.dataset_id=d.dataset_id AND s.org_id=d.org_id
            LEFT JOIN ds_catalog c ON c.source_id=s.source_id
            WHERE d.status='ACTIVE'
              AND (cv.view_id IS NOT NULL OR NOT EXISTS (SELECT 1 FROM ds_controlled_view x WHERE x.dataset_id=d.dataset_id))
            """;
    private static final String SCOPE_ORDER = """
            ORDER BY d.dataset_id, CASE WHEN v.active_index_version_id=i.index_version_id THEN 0
                WHEN v.published_at IS NOT NULL THEN 1 ELSE 2 END,
                v.created_at DESC NULLS LAST, i.created_at DESC NULLS LAST, v.version_id, i.index_version_id
            """;

    private final JdbcTemplate jdbc;
    private final AuthorizationService auth;
    private final FileService files;
    private final DashboardThumbnailClient renderer;
    private final Semaphore renderSlots = new Semaphore(1, true);
    private final Semaphore pendingRenders = new Semaphore(8);
    private final Map<ImageKey, byte[]> cache = new LinkedHashMap<>(CACHE_IMAGES, .75f, true);

    public DashboardService(JdbcTemplate jdbc, AuthorizationService auth, FileService files,
                            DashboardThumbnailClient renderer) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.files = files;
        this.renderer = renderer;
    }

    public record Business(String code, String name) {}
    public record Choice(UUID versionId, String versionName, UUID indexVersionId, UUID viewId,
                         String status, String indexStatus, boolean canPreview, List<String> businessCodes) {}
    public record Row(UUID datasetId, String datasetName, String originalDatasetName, String catalogName, UUID orgId,
                      String orgName, List<String> businessCodes, Choice selected, List<Choice> versions) {}
    public record Page(List<Row> items, List<Business> business, int page, int totalPages, int total,
                       PreviewPage firstPreview) {}
    public record Asset(UUID fileId, String logicalPath, String format, String sha256, String locator,
                        String cachedImage) {}
    public record PreviewPage(List<Asset> items, int total, int offset, String status) {}
    public record Metadata(String displayName, List<String> businessCodes, String evidenceReference) {}

    private record Scope(UUID dataset, String name, String originalName, String catalog, UUID version, String versionName, UUID index,
                         UUID view, long authorizationVersion, UUID repo, UUID commit, UUID org, String orgName,
                         String status, String indexStatus, List<String> business) {}
    private record ImageKey(UUID dataset, UUID version, UUID index, UUID view, long viewRevision,
                            UUID commit, UUID file, String sha, String recipe, String locator) {}

    private List<Scope> scopes(String filter, Object... parameters) {
        return jdbc.query(SCOPE_SQL + filter + SCOPE_ORDER, (rs, row) -> {
            var business = rs.getArray("business_codes");
            String name = rs.getString("display_name");
            String originalName = rs.getString("dataset_name");
            return new Scope(rs.getObject("dataset_id", UUID.class), name == null ? displayName(originalName) : name,
                    originalName,rs.getString("catalog_name"),
                    rs.getObject("version_id", UUID.class), rs.getString("version_name"),
                    rs.getObject("index_version_id", UUID.class), rs.getObject("view_id", UUID.class),
                    rs.getLong("authorization_version"), rs.getObject("raw_repo_id", UUID.class),
                    rs.getObject("raw_commit_id", UUID.class), rs.getObject("org_id", UUID.class), rs.getString("org_name"),
                    rs.getString("version_status"), rs.getString("index_status"),
                    business == null ? List.of() : List.of((String[]) business.getArray()));
        }, parameters);
    }

    public Page page(String keyword, String status, List<String> filter, int requestedPage, boolean includeFirstPreview) {
        if (keyword.length() > 160 || !BUSINESS_CODES.containsAll(filter) || requestedPage < 1)
            throw failure(HttpStatus.BAD_REQUEST, "请核对筛选条件和页码。");
        var actor = auth.requireOperation(CATALOG);
        Map<UUID,List<Scope>> visible = new LinkedHashMap<>();
        for (Scope scope : scopes("")) {
            if (allowed(actor, scope, CATALOG))
                visible.computeIfAbsent(scope.dataset, key -> new ArrayList<>()).add(scope);
        }
        var nodes = new HashSet<String>();
        var rows = new ArrayList<Row>();
        String query = keyword.strip().toLowerCase(Locale.ROOT);
        for (var group : visible.values()) {
            var choices = group.stream().map(s -> new Choice(s.version, s.versionName, s.index, s.view, s.status,
                    s.indexStatus, s.commit != null && allowed(actor, s, PREVIEW), s.business)).toList();
            choices.forEach(choice -> nodes.addAll(choice.businessCodes));
            int current = -1;
            for (int i = 0; i < group.size(); i++) {
                var scope = group.get(i);
                String catalog = scope.org.equals(actor.orgId()) ? Objects.toString(scope.catalog,"") : "";
                if ((!filter.isEmpty() && scope.business.stream().noneMatch(filter::contains)) ||
                    (!status.isEmpty() && !status.equals(scope.status) && !status.equals(scope.indexStatus)) ||
                    (!query.isEmpty() && !String.join(" ",scope.name,scope.originalName,catalog).toLowerCase(Locale.ROOT).contains(query))) continue;
                if (current < 0 || !choices.get(current).canPreview && choices.get(i).canPreview) current = i;
            }
            if (current < 0) continue;
            var selected = group.get(current);
            var choice = choices.get(current);
            rows.add(new Row(selected.dataset, selected.name, selected.originalName,
                    selected.org.equals(actor.orgId()) ? selected.catalog : null, selected.org, selected.orgName,
                    choice.businessCodes, choice, choices));
        }
        rows.sort(Comparator.comparing((Row row) -> !row.selected.canPreview)
                .thenComparing(Row::datasetName).thenComparing(Row::datasetId));
        int total = rows.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(requestedPage, totalPages);
        int start = (page - 1) * PAGE_SIZE;
        var slice = rows.subList(start, Math.min(total, start + PAGE_SIZE));
        // Record only delivered scopes so the output guard can revalidate them.
        for (Row row : slice) {
            for (Choice choice : row.versions) {
                auth.lease(row.datasetId, choice.versionId, choice.indexVersionId, Set.of(CATALOG), PURPOSE);
                if (choice.canPreview)
                    auth.lease(row.datasetId, choice.versionId, choice.indexVersionId, Set.of(PREVIEW), PURPOSE);
            }
        }
        PreviewPage firstPreview = null;
        if (includeFirstPreview && !slice.isEmpty() && slice.get(0).selected.canPreview) {
            var first = slice.get(0);
            try {
                firstPreview = previews(first.datasetId, first.selected.versionId, first.selected.indexVersionId, 0);
            } catch (ResponseStatusException unavailable) {
                // Directory rows still render when a preview file list is temporarily unavailable.
            }
        }
        return new Page(slice, BUSINESS.stream().filter(b -> nodes.contains(b.code)).toList(),
                page, totalPages, total, firstPreview);
    }

    private boolean allowed(AuthorizationService.Actor actor, Scope scope, String operation) {
        return auth.canUseDataset(actor, scope.dataset, scope.version, scope.index, operation, PURPOSE);
    }

    private static String displayName(String name) {
        if ("f04_upload_enterprise_source_518_current_bytes".equals(name)) return "家电产业链源数据集";
        if (name.matches("Original human-reviewed eleven formats [0-9]+")) return "十一种格式复核资料";
        var scope = java.util.regex.Pattern.compile("Current enterprise responsibility (ENT-[A-Z0-9]+) [0-9]+").matcher(name);
        return scope.matches() ? "企业责任资料 · " + scope.group(1) : name;
    }

    private Scope exact(UUID dataset, UUID version, UUID index) {
        return scopes(" AND d.dataset_id=? AND v.version_id=? AND i.index_version_id IS NOT DISTINCT FROM ?::uuid ",
                dataset, version, index).stream().findFirst()
                .orElseThrow(() -> failure(HttpStatus.NOT_FOUND, "RESOURCE_SCOPE_NOT_AVAILABLE"));
    }

    private Scope authorizedPreview(UUID dataset, UUID version, UUID index) {
        auth.lease(dataset, version, index, Set.of(CATALOG), PURPOSE);
        auth.lease(dataset, version, index, Set.of(PREVIEW), PURPOSE);
        return exact(dataset, version, index);
    }

    private static String format(FileResp file) {
        String path = file.getLogicalPath();
        String suffix = path.substring(path.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        return switch (suffix) { case "JPEG" -> "JPG"; case "STP" -> "STEP"; default -> suffix; };
    }

    private static String locator(String format) {
        return switch (format) {
            case "PDF" -> "第 1 页";
            case "CSV", "XLSX" -> "表头及首批记录";
            case "JSON", "JSONL" -> "首段字段与记录";
            case "TXT" -> "开头文本";
            case "OBJ", "STEP" -> "等轴测视图";
            default -> "原图全幅";
        };
    }

    public PreviewPage previews(UUID dataset, UUID version, UUID index, int offset) {
        if (offset < 0) throw failure(HttpStatus.BAD_REQUEST, "预览页码不正确。");
        Scope scope = authorizedPreview(dataset, version, index);
        if (scope.commit == null) return new PreviewPage(List.of(), 0, 0, "UPLOADING");
        var members = files.listFilesByRepoAndCommit(scope.repo, scope.commit);
        // Start with one real asset per format, then offer the rest in path order.
        List<FileResp> ordered = new ArrayList<>();
        List<FileResp> remaining = new ArrayList<>();
        Set<String> formats = new HashSet<>();
        for (var file : members) {
            String format = format(file);
            if (FORMATS.contains(format) && formats.add(format)) ordered.add(file);
            else remaining.add(file);
        }
        ordered.addAll(remaining);
        int start = Math.min(offset, ordered.size());
        var items = ordered.stream().skip(start).limit(IMAGE_PAGE_SIZE).map(file -> {
            String format = format(file);
            byte[] hit = cached(imageKey(scope, file, format));
            return new Asset(file.getFileId(), file.getLogicalPath(), format, file.getSha256(), locator(format),
                    hit == null ? null : Base64.getEncoder().encodeToString(hit));
        }).toList();
        return new PreviewPage(items, ordered.size(), start, ordered.isEmpty() ? "EMPTY" : "AVAILABLE");
    }

    public byte[] thumbnail(UUID dataset, UUID version, UUID index, UUID fileId, String sha) throws Exception {
        Scope scope = authorizedPreview(dataset, version, index);
        if (scope.commit == null || !sha.matches("[a-f0-9]{64}"))
            throw failure(HttpStatus.NOT_FOUND, "RESOURCE_SCOPE_NOT_AVAILABLE");
        FileResp file = files.describeFileById(scope.repo, scope.commit, fileId);
        if (!sha.equals(file.getSha256()))
            throw failure(HttpStatus.NOT_FOUND, "RESOURCE_SCOPE_NOT_AVAILABLE");
        String format = format(file);
        if (!FORMATS.contains(format)) throw failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "此格式暂无内容预览，请联系数据维护者。");
        if (file.getSizeBytes() == null || file.getSizeBytes() > SOURCE_LIMIT)
            throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "该文件超过内容预览大小限制。");

        var key = imageKey(scope, file, format);
        byte[] hit = cached(key);
        if (hit != null) return hit;
        if (!pendingRenders.tryAcquire()) throw failure(HttpStatus.TOO_MANY_REQUESTS, "预览正在处理，请稍后重试。");
        try {
            if (!renderSlots.tryAcquire(6, TimeUnit.SECONDS))
                throw failure(HttpStatus.TOO_MANY_REQUESTS, "预览正在处理，请稍后重试。");
            try {
                authorizedPreview(dataset, version, index);
                return render(scope, file, key, format, sha);
            } finally {
                renderSlots.release();
            }
        } finally {
            pendingRenders.release();
        }
    }

    private static ImageKey imageKey(Scope scope, FileResp file, String format) {
        return new ImageKey(scope.dataset, scope.version, scope.index, scope.view, scope.authorizationVersion,
                scope.commit, file.getFileId(), file.getSha256(), DashboardThumbnailClient.RECIPE, locator(format));
    }

    private byte[] cached(ImageKey key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private byte[] render(Scope scope, FileResp file, ImageKey key, String format, String sha) throws Exception {
        byte[] hit = cached(key);
        if (hit != null) return hit;
        byte[] raw;
        try (var stream = files.downloadContentById(scope.repo, scope.commit, file.getFileId())) {
            raw = stream.inputStream().readNBytes(SOURCE_LIMIT + 1);
        }
        if (raw.length != file.getSizeBytes() || !sha.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))))
            throw failure(HttpStatus.CONFLICT, "文件已变化，请刷新当前版本。");
        byte[] image = renderer.render(format, sha, raw);
        // Recheck the existing leases, including exact grant versions, after conversion.
        authorizedPreview(scope.dataset, scope.version, scope.index);
        synchronized (cache) {
            cache.put(key, image);
            if (cache.size() > CACHE_IMAGES) cache.remove(cache.keySet().iterator().next());
        }
        return image;
    }

    @Transactional
    public void updateMetadata(UUID dataset, UUID version, UUID index, Metadata input) {
        var actor = auth.actor();
        auth.lease(dataset, version, index, Set.of("dataset.draft.manage"), PURPOSE);
        Scope scope = exact(dataset, version, index);
        if (!scope.org.equals(actor.orgId())) throw failure(HttpStatus.NOT_FOUND, "RESOURCE_SCOPE_NOT_AVAILABLE");
        if (input == null || input.displayName == null || input.displayName.isBlank() || input.displayName.length() > 160 ||
                input.businessCodes == null || input.businessCodes.stream().anyMatch(Objects::isNull) ||
                !BUSINESS_CODES.containsAll(input.businessCodes) || input.evidenceReference == null ||
                input.evidenceReference.isBlank() || input.evidenceReference.length() > 300)
            throw failure(HttpStatus.BAD_REQUEST, "请填写中文名称、有效业务分类和来源依据。");
        var codes = String.join(",", new TreeSet<>(input.businessCodes));
        jdbc.update("""
                INSERT INTO ds_dashboard_business_metadata(version_id,display_name,business_codes,evidence_reference,updated_by)
                VALUES (?,?,CASE WHEN ?='' THEN ARRAY[]::text[] ELSE string_to_array(?,',') END,?,?)
                ON CONFLICT(version_id) DO UPDATE SET display_name=excluded.display_name,
                    business_codes=excluded.business_codes,evidence_reference=excluded.evidence_reference,
                    updated_by=excluded.updated_by,updated_at=clock_timestamp()
                """, version, input.displayName.strip(), codes, codes, input.evidenceReference.strip(), actor.userId());
        jdbc.update("""
                INSERT INTO ds_authorization_audit(actor_user_id,actor_org_id,operation_code,target_kind,target_id,outcome,
                    request_id,scope,reason_code)
                VALUES (?,?,'dataset.draft.manage','DATASET_VERSION',?,'SUCCESS',?,
                    jsonb_build_object('datasetId',?::text,'versionId',?::text,'businessCodes',?::text,
                        'evidenceReference',?::text),'DASHBOARD_BUSINESS_METADATA_UPDATED')
                """, actor.userId(), actor.orgId(), version.toString(), UUID.randomUUID().toString(),dataset.toString(),
                version.toString(),codes,input.evidenceReference.strip());
    }

    private static ResponseStatusException failure(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }
}
