package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fwdrobo.sirius.dto.file.FileResp;
import com.fwdrobo.sirius.mapper.ArtifactFileMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.util.*;

/** Resolves only manifest-bound evidence, under the caller's exact live grant.
 * The internal object reader does not give the caller an index-repository grant.
 */
@Service
public class QaEvidenceService {
    private static final int MAX_JSON=16*1024*1024, MAX_IMAGE=16*1024*1024;
    private final DatasetAccessService access;
    private final JdbcTemplate jdbc;
    private final ManifestService manifests;
    private final ArtifactFileMapper files;
    private final MinioService minio;
    private final MmSourceFileService sources;
    private final ObjectMapper json;
    private final QaEvidenceRenderClient renderer;
    public QaEvidenceService(DatasetAccessService access,JdbcTemplate jdbc,ManifestService manifests,
            ArtifactFileMapper files,MinioService minio,MmSourceFileService sources,ObjectMapper json,QaEvidenceRenderClient renderer){
        this.access=access;this.jdbc=jdbc;this.manifests=manifests;this.files=files;this.minio=minio;this.sources=sources;this.json=json;this.renderer=renderer;
    }
    private ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private record Scope(UUID dataset,UUID version,UUID index,UUID rawRepo,UUID rawCommit,UUID indexRepo,UUID indexCommit){}
    private Scope scope(UUID d,UUID v,UUID i){
        access.requireVersionRead(d,v,i);
        var rows=jdbc.queryForList("""
            SELECT d.raw_repo_id,v.raw_commit_id,i.index_repo_id,i.index_commit_id
            FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
            JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
            JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=v.raw_commit_id AND raw.commit_status='PUBLISHED'
            JOIN artifact_commit idx ON idx.repo_id=i.index_repo_id AND idx.commit_id=i.index_commit_id AND idx.commit_status='PUBLISHED'
            WHERE d.dataset_id=? AND v.version_id=? AND i.index_version_id=? AND i.index_status='READY'
            """,d,v,i);
        if(rows.size()!=1)throw error(HttpStatus.CONFLICT,"EVIDENCE_SNAPSHOT_UNAVAILABLE: 证据需要完整已发布快照");
        var r=rows.get(0);return new Scope(d,v,i,(UUID)r.get("raw_repo_id"),(UUID)r.get("raw_commit_id"),(UUID)r.get("index_repo_id"),(UUID)r.get("index_commit_id"));
    }
    private FileResp member(UUID repo,UUID commit,String path)throws Exception{
        var id=manifests.getManifest(repo,commit).getFiles().stream().filter(f->path.equals(f.getPath())).map(f->f.getFileId()).findFirst()
            .orElseThrow(()->error(HttpStatus.NOT_FOUND,"EVIDENCE_NOT_FOUND: 证据不在该冻结清单中"));
        var rows=files.selectFilesByIds(List.of(id));
        if(rows.size()!=1||rows.get(0).getSha256()==null||!rows.get(0).getSha256().matches("[a-f0-9]{64}"))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_HASH_UNAVAILABLE: 该证据没有可核验内容摘要");
        return rows.get(0);
    }
    private byte[] verified(FileResp file,int maximum)throws Exception{
        if(file.getSizeBytes()==null||file.getSizeBytes()<0||file.getSizeBytes()>maximum)
            throw error(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_SIZE_LIMIT: 证据超过本次读取大小限制");
        byte[] content;try(var in=minio.getObject(file.getFileKey())){content=in.readNBytes(maximum+1);}
        if(content.length!=file.getSizeBytes()||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).equals(file.getSha256()))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_HASH_MISMATCH: 证据内容与冻结清单摘要不一致");
        return content;
    }
    private JsonNode readJson(Scope s,String path)throws Exception{
        return json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readTree(verified(member(s.indexRepo,s.indexCommit,path),MAX_JSON));
    }
    private record Bound(Scope scope,UUID asset,String path,String format,FileResp source,JsonNode representation,JsonNode definition){}
    private Bound bind(UUID d,UUID v,UUID i,UUID asset)throws Exception{
        return bind(d,v,i,asset,true);
    }
    private Bound bind(UUID d,UUID v,UUID i,UUID asset,boolean legacyVisual)throws Exception{
        var s=scope(d,v,i);
        var resolved=sources.resolve(d,v,List.of(asset)).get(0);
        if(!resolved.available())throw error(HttpStatus.NOT_FOUND,"EVIDENCE_SOURCE_UNAVAILABLE: 原始来源不在可读快照中");
        var raw=member(s.rawRepo,s.rawCommit,resolved.logicalPath());
        // The professional manifest supplies the source content hash even for
        // historical visual manifests that predate the richer evidence schema.
        var professional=readJson(s,"/professional/manifest.json");JsonNode identity=null;
        for(var candidate:professional.path("assets"))if(asset.toString().equals(candidate.path("assetId").asText())){
            if(identity!=null)throw error(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS: 证据身份重复");identity=candidate;
        }
        if(identity==null||!resolved.logicalPath().equals(identity.path("logicalPath").asText())||!raw.getSha256().equals(identity.path("contentSha256").asText()))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_SOURCE_MISMATCH: 证据源摘要或逻辑位置不属于该快照");
        var representation=legacyVisual?readJson(s,"/representations/manifests/"+asset+".json"):json.createObjectNode();
        String format=identity.path("sourceFormat").asText();
        if(legacyVisual&&(!asset.toString().equals(representation.path("assetId").asText())||!resolved.logicalPath().equals(representation.path("sourcePath").asText())||!format.equals(representation.path("sourceFormat").asText())))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_MANIFEST_MISMATCH: 表示清单与源身份不一致");
        return new Bound(s,asset,resolved.logicalPath(),format,raw,representation,identity);
    }
    private FileResp image(Bound b,JsonNode unit)throws Exception{
        String path=unit.path("outputPath").asText("");
        if(path.isEmpty()){
            if(!Set.of("JPG","JPEG","PNG","WEBP").contains(b.format))throw error(HttpStatus.NOT_FOUND,"EVIDENCE_NOT_AN_IMAGE: 未提供可用图像");
            return b.source;
        }
        if(!path.startsWith("/representations/")||path.contains("\\")||Arrays.stream(path.split("/",-1)).anyMatch(p->p.equals(".")||p.equals(".."))||!path.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp)$"))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_RECIPE_INVALID: 图像输出不符合冻结配方");
        return member(b.scope.indexRepo,b.scope.indexCommit,path);
    }
    private Map<String,Object> descriptor(Bound b,JsonNode unit)throws Exception{
        var file=image(b,unit);var value=new LinkedHashMap<String,Object>();
        value.put("representationId",unit.path("unitId").asText());value.put("kind",unit.path("kind").asText());
        value.put("locator",unit.path("locator"));value.put("outputSha256",file.getSha256());value.put("sizeBytes",file.getSizeBytes());
        value.put("evidenceNature","program_rendering");value.put("originalFormat",b.format);
        value.put("recipeVersion","legacy-representations:"+b.representation.path("schemaVersion").asText("unknown"));
        value.put("partial",true);value.put("coverageNote","历史预览仅证明实际渲染区域，不能代替完整原文、全表或完整几何测量");
        value.put("url","/api/data-space/qa/evidence/"+b.scope.dataset+"/"+b.scope.version+"/"+b.scope.index+"/"+b.asset+"/"+unit.path("unitId").asText());
        return value;
    }
    private void checkNativeIdentity(Bound b,JsonNode identity){
        Map<String,String> expected=Map.of("datasetId",b.scope.dataset.toString(),"versionId",b.scope.version.toString(),
            "indexVersionId",b.scope.index.toString(),"rawCommitId",b.scope.rawCommit.toString(),"assetId",b.asset.toString(),
            "logicalPath",b.path,"sourceSha256",b.source.getSha256(),"originalFormat",b.format);
        if(expected.entrySet().stream().anyMatch(entry->!entry.getValue().equals(identity.path(entry.getKey()).asText())))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_SOURCE_MISMATCH: 原生证据与冻结身份不一致");
    }
    private JsonNode nativeManifest(Bound b,boolean required)throws Exception{
        var definition=b.definition.path("evidenceManifest");
        if(!definition.isObject()){
            if(required)throw error(HttpStatus.CONFLICT,"EVIDENCE_INDEX_REBUILD_REQUIRED: 该历史索引没有冻结的原生证据清单");
            return null;
        }
        String path="/evidence/"+b.asset+"/manifest.json";
        if(!path.equals(definition.path("path").asText()))throw error(HttpStatus.CONFLICT,"EVIDENCE_MANIFEST_MISMATCH: 原生清单路径不属于该资产");
        var member=member(b.scope.indexRepo,b.scope.indexCommit,path);
        if(!member.getSha256().equals(definition.path("sha256").asText())||member.getSizeBytes()!=definition.path("sizeBytes").asLong())
            throw error(HttpStatus.CONFLICT,"EVIDENCE_MANIFEST_MISMATCH: 原生清单摘要不一致");
        var manifest=readNativeJson(verified(member,MAX_JSON));
        checkNativeIdentity(b,manifest.path("identity"));
        if(!"qa-evidence-bundle-2".equals(manifest.path("schemaVersion").asText())||!supportedNativeRecipe(manifest.path("recipeVersion").asText())||!manifest.path("representations").isArray())
            throw error(HttpStatus.CONFLICT,"EVIDENCE_RECIPE_UNSUPPORTED: 当前服务不支持该原生证据配方");
        if("native-evidence-2026-10-22.2".equals(manifest.path("recipeVersion").asText())){
            if(!manifest.path("identity").path("evidenceConfigSha256").asText().matches("[a-f0-9]{64}")||manifest.path("transforms").size()!=1)
                throw error(HttpStatus.CONFLICT,"EVIDENCE_CONFIG_INVALID: 新配方缺少冻结的解析配置摘要");
            var transform=manifest.path("transforms").get(0);
            if(!transform.path("processingConfigSha256").asText().matches("[a-f0-9]{64}")||transform.path("organizerVersion").asText().isBlank()||transform.path("catalogVersion").asText().isBlank())
                throw error(HttpStatus.CONFLICT,"EVIDENCE_CONFIG_INVALID: 新配方缺少解析和组织版本");
        }
        Set<String> identifiers=new HashSet<>();
        for(var descriptor:manifest.path("representations")){
            String id=descriptor.path("representationId").asText();
            if(!id.matches("[a-f0-9-]{36}")||!identifiers.add(id))throw error(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS: 原生表示标识重复或无效");
        }
        return manifest;
    }
    private Map<String,Object> publicDescriptor(JsonNode descriptor){
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("representationId","evidenceId","kind","locator","outputSha256","sizeBytes","recipeVersion","schemaVersion","evidenceNature","coverage","capabilities","unsupportedReasons","selector"))
            if(descriptor.has(key))result.put(key,descriptor.get(key));
        return result;
    }
    private boolean supportedNativeRecipe(String recipe){return Set.of("native-evidence-2026-10-21.1","native-evidence-2026-10-22.2").contains(recipe);}
    private JsonNode readNativeJson(byte[] bytes)throws Exception{
        // Manifest transforms and content transforms must use identical numeric
        // node types, including nested OCR/render parameters. Keep exact numbers.
        return json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .readTree(bytes);
    }
    private JsonNode nativeBundle(Bound b,JsonNode manifest,JsonNode descriptor)throws Exception{
        String path=descriptor.path("outputPath").asText(),hash=descriptor.path("outputSha256").asText();
        if(!hash.matches("[a-f0-9]{64}")||!path.equals("/evidence/"+b.asset+"/content/"+hash+".json"))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_RECIPE_INVALID: 原生表示不属于该内容寻址配方");
        var member=member(b.scope.indexRepo,b.scope.indexCommit,path);
        if(!hash.equals(member.getSha256())||member.getSizeBytes()!=descriptor.path("sizeBytes").asLong())
            throw error(HttpStatus.CONFLICT,"EVIDENCE_HASH_MISMATCH: 原生表示与已发布清单摘要不一致");
        var bundle=readNativeJson(verified(member,8*1024*1024));
        checkNativeIdentity(b,bundle.path("identity"));
        if(!descriptor.path("representationId").equals(bundle.path("representationId"))||!descriptor.path("evidenceId").equals(bundle.path("evidenceId"))
            ||!"qa-evidence-bundle-2".equals(bundle.path("schemaVersion").asText())||!manifest.path("recipeVersion").equals(bundle.path("recipeVersion"))
            ||!manifest.path("identity").equals(bundle.path("identity"))
            ||("native-evidence-2026-10-22.2".equals(bundle.path("recipeVersion").asText())&&!manifest.path("transforms").equals(bundle.path("transforms")))
            ||!descriptor.path("kind").asText().equals(bundle.path("representationKind").asText()))
            throw error(HttpStatus.CONFLICT,"EVIDENCE_MANIFEST_MISMATCH: 原生内容身份不匹配");
        return bundle;
    }
    public JsonNode read(UUID d,UUID v,UUID i,UUID asset,JsonNode request)throws Exception{
        var bound=bind(d,v,i,asset,false);var manifest=nativeManifest(bound,true);
        long deadline=System.nanoTime()+15_000_000_000L;long[] total={0};int[] count={0};var loaded=json.createArrayNode();
        var result=new QaNativeEvidenceProjection(json).read(manifest,request,descriptor->{
            if(++count[0]>32||System.nanoTime()>deadline)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET: 精读步骤或时间超过本次限制，请缩小范围");
            total[0]+=descriptor.path("sizeBytes").asLong();if(total[0]>32L*1024*1024)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET: 精读总字节超限，请选择较少行列");
            access.requireVersionRead(d,v,i);var bundle=nativeBundle(bound,manifest,descriptor);
            loaded.addObject().put("representationId",descriptor.path("representationId").asText()).put("evidenceId",descriptor.path("evidenceId").asText()).put("outputSha256",descriptor.path("outputSha256").asText());return bundle;
        });
        if(json.writeValueAsBytes(result).length>2*1024*1024)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET: 返回内容过大，请选择较少字段或更小子树");
        if(result.has("sourceRepresentationId")&&"native-evidence-2026-10-22.2".equals(result.path("recipeVersion").asText())){
            // Hash the complete deterministic projection, excluding its own ID
            // and per-request audit fields. Legacy recipes keep their old IDs.
            result.remove("representationId");
            var canonical=json.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true)
                .writeValueAsBytes(json.convertValue(result,Object.class));
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
            result.put("representationContentSha256",hash);
            result.put("representationId",QaNativeEvidenceProjection.stable(result.path("sourceRepresentationId").asText(),"authorized-native-projection-content",hash));
        }
        result.put("readOutputSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(result))));
        result.put("readId",UUID.randomUUID().toString());result.put("readOperation",request.path("operation").asText());result.set("readRepresentations",loaded);
        access.requireVersionRead(d,v,i);return result;
    }
    public QaEvidenceRenderClient.Rendered render(UUID d,UUID v,UUID i,UUID asset,JsonNode request)throws Exception{
        var bound=bind(d,v,i,asset,false);var manifest=nativeManifest(bound,true);
        var plan=new QaNativeEvidenceProjection(json).renderPlan(manifest,request);
        byte[] raw=verified(bound.source,MAX_IMAGE);access.requireVersionRead(d,v,i);
        QaEvidenceRenderClient.Rendered rendered;
        try{rendered=renderer.render(manifest.path("identity"),plan,raw);}
        finally{access.requireVersionRead(d,v,i);}
        var metadata=(com.fasterxml.jackson.databind.node.ObjectNode)rendered.metadata();
        metadata.put("parentEvidenceId",plan.path("parent").path("evidenceId").asText());metadata.put("parentRepresentationId",plan.path("parent").path("representationId").asText());
        metadata.put("readId",UUID.randomUUID().toString());metadata.put("evidenceNature","program_rendering");
        metadata.put("sourceRepresentationSha256",plan.path("parent").path("outputSha256").asText());
        access.requireVersionRead(d,v,i);return rendered;
    }
    public Map<String,Object> describe(UUID d,UUID v,UUID i,UUID asset)throws Exception{
        var b=bind(d,v,i,asset);var items=new ArrayList<Map<String,Object>>();Set<String> seen=new HashSet<>();
        for(var unit:b.representation.path("visualUnits")){
            String id=unit.path("unitId").asText();
            if(id.isBlank()||id.length()>200||id.contains("/")||id.contains("\\")||!seen.add(id))throw error(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS: 表示标识无效或重复");
            items.add(descriptor(b,unit));
        }
        var nativeManifest=nativeManifest(b,false);
        if(nativeManifest!=null)for(var unit:nativeManifest.path("representations"))items.add(publicDescriptor(unit));
        access.requireVersionRead(d,v,i);
        var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","qa-evidence-access-1");
        result.put("datasetId",d);result.put("versionId",v);result.put("indexVersionId",i);result.put("rawCommitId",b.scope.rawCommit);
        result.put("assetId",asset);result.put("logicalPath",b.path);result.put("sourceSha256",b.source.getSha256());result.put("originalFormat",b.format);result.put("representations",items);
        result.put("nativeEvidenceStatus",nativeManifest==null?"LEGACY_INDEX_REBUILD_REQUIRED":"AVAILABLE");
        return result;
    }
    public record Image(byte[] bytes,String contentType,String sha256,int width,int height){}
    public Image open(UUID d,UUID v,UUID i,UUID asset,String representationId)throws Exception{
        if(representationId.isBlank()||representationId.length()>200)throw error(HttpStatus.BAD_REQUEST,"EVIDENCE_ID_INVALID: 表示标识无效");
        var b=bind(d,v,i,asset);JsonNode selected=null;
        for(var unit:b.representation.path("visualUnits"))if(representationId.equals(unit.path("unitId").asText())){
            if(selected!=null)throw error(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS: 表示标识重复");selected=unit;
        }
        if(selected==null)throw error(HttpStatus.NOT_FOUND,"EVIDENCE_NOT_FOUND: 表示不属于该原始资产");
        var file=image(b,selected);byte[] content=verified(file,MAX_IMAGE);int width,height;String mime;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(content))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw error(HttpStatus.UNPROCESSABLE_ENTITY,"EVIDENCE_IMAGE_INVALID: 图像无法读取");
            var reader=readers.next();try{reader.setInput(input);width=reader.getWidth(0);height=reader.getHeight(0);mime="image/"+reader.getFormatName().toLowerCase(Locale.ROOT);if(mime.equals("image/jpg"))mime="image/jpeg";}finally{reader.dispose();}
        }
        if(width<1||height<1||(long)width*height>40000000L)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_PIXEL_LIMIT: 图像像素超过安全读取限制");
        access.requireVersionRead(d,v,i); // Revocation/expiry is checked again after object reads.
        return new Image(content,mime,file.getSha256(),width,height);
    }
}
