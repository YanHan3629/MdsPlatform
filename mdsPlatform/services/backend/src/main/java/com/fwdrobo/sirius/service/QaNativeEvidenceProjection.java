package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pure bounded projections; all storage and caller authorization stay in QaEvidenceService. */
final class QaNativeEvidenceProjection {
    interface Loader { JsonNode load(JsonNode descriptor) throws Exception; }
    private final ObjectMapper json;
    QaNativeEvidenceProjection(ObjectMapper json){this.json=json;}
    private ResponseStatusException fail(HttpStatus status,String code,String reason){return new ResponseStatusException(status,code+": "+reason);}
    private String required(JsonNode node,String key,int maximum){
        var value=node.get(key);
        if(value==null||!value.isTextual()||value.asText().isEmpty()||value.asText().length()>maximum)
            throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","需要有效的 "+key);
        return value.asText();
    }
    private int integer(JsonNode node,String key,int fallback,int minimum,int maximum){
        var value=node.get(key);if(value==null)return fallback;
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<minimum||value.asInt()>maximum)
            throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID",key+" 超出允许范围");
        return value.asInt();
    }
    private List<String> strings(JsonNode request,String key,int limit){
        var values=request.get(key);if(values==null)return List.of();
        if(!values.isArray()||values.size()>limit)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID",key+" 数量超限");
        var result=new ArrayList<String>();
        for(var value:values){if(!value.isTextual()||value.asText().isEmpty()||value.asText().length()>256||result.contains(value.asText()))
            throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID",key+" 必须是非重复名称");result.add(value.asText());}
        return result;
    }
    private List<JsonNode> descriptors(JsonNode manifest,String kind){
        var result=new ArrayList<JsonNode>();for(var node:manifest.path("representations"))if(kind.equals(node.path("kind").asText()))result.add(node);return result;
    }
    private JsonNode byId(JsonNode manifest,String id){
        JsonNode result=null;for(var node:manifest.path("representations"))if(id.equals(node.path("representationId").asText())){
            if(result!=null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS","表示标识重复");result=node;}
        if(result==null)throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_NOT_FOUND","表示不属于当前资产");return result;
    }
    static String stable(String... parts)throws Exception{
        var hash=MessageDigest.getInstance("SHA-1");
        hash.update(HexFormat.of().parseHex("6ba7b8119dad11d180b400c04fd430c8"));
        byte[] bytes=Arrays.copyOf(hash.digest(String.join(":",parts).getBytes(StandardCharsets.UTF_8)),16);
        bytes[6]=(byte)((bytes[6]&15)|80);bytes[8]=(byte)((bytes[8]&63)|128);
        var buffer=ByteBuffer.wrap(bytes);return new UUID(buffer.getLong(),buffer.getLong()).toString();
    }
    private String canonical(JsonNode value)throws Exception{
        return json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true).writeValueAsString(json.convertValue(value,Object.class));
    }
    private ObjectNode projected(JsonNode original,JsonNode request)throws Exception{
        ObjectNode result=original.deepCopy();
        result.put("evidenceId",stable(original.path("evidenceId").asText(),"authorized-read-projection",canonical(request)));
        result.put("sourceRepresentationId",original.path("representationId").asText());
        result.put("representationId",stable(original.path("representationId").asText(),"authorized-read-projection",canonical(request)));
        result.set("parentEvidenceIds",json.createArrayNode().add(original.path("evidenceId").asText()));
        result.withArray("transforms").addObject().put("operation","authorized-native-projection").set("parameters",request.deepCopy());
        return result;
    }
    ObjectNode read(JsonNode manifest,JsonNode request,Loader loader)throws Exception{
        if(request==null||!request.isObject()||json.writeValueAsBytes(request).length>16384)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","精读参数无效或过大");
        String operation=required(request,"operation",64);
        Set<String> allowed=switch(operation){
            case "read_representation"->Set.of("operation","representationId");
            case "read_properties"->Set.of("operation","representationId","fields");
            case "read_json_subtree"->Set.of("operation","jsonPointer","jsonlLine");
            case "read_table"->Set.of("operation","tableId","rowStart","rowEnd","fields","limit");
            case "read_workbook_cells"->Set.of("operation","sheet","cells");
            case "read_region_text"->Set.of("operation","page","bbox","limit");
            default->throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_OPERATION_UNSUPPORTED","未注册的只读操作");};
        request.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","操作包含未允许的参数");});
        return switch(operation){
            case "read_representation"->(ObjectNode)loader.load(byId(manifest,required(request,"representationId",200))).deepCopy();
            case "read_properties"->properties(manifest,request,loader);
            case "read_json_subtree"->jsonSubtree(manifest,request,loader);
            case "read_table"->table(manifest,request,loader);
            case "read_workbook_cells"->cells(manifest,request,loader);
            case "read_region_text"->region(manifest,request,loader);
            default->throw new IllegalStateException();};
    }
    private ObjectNode properties(JsonNode manifest,JsonNode request,Loader loader)throws Exception{
        var descriptor=byId(manifest,required(request,"representationId",200));String kind=descriptor.path("kind").asText();
        if(!Set.of("geometry_properties","geometry_summary","svg_metadata").contains(kind))
            throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_PROPERTY_KIND_UNSUPPORTED","该表示不提供属性精读");
        var fields=strings(request,"fields",32);
        if(fields.isEmpty())throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","属性精读需要具体字段");
        var original=loader.load(descriptor);boolean declaration="svg_metadata".equals(kind);
        var values=original.path("content").path(declaration?"value":"properties");
        if(!values.isObject())throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"EVIDENCE_PROPERTIES_UNAVAILABLE","该源没有可选择的对象属性");
        var selected=json.createObjectNode();
        for(var field:fields){
            if(!values.has(field))throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_PROPERTY_NOT_FOUND","属性不属于当前冻结表示: "+field);
            selected.set(field,values.get(field).deepCopy());
        }
        var result=projected(original,request);var content=json.createObjectNode();content.set(declaration?"value":"properties",selected);
        if(original.path("content").has("units"))content.set("units",original.path("content").get("units").deepCopy());
        var facts=json.createArrayNode();
        for(var fact:original.path("facts")){
            String field=fact.path("field").asText();
            boolean included=fields.contains(field);
            if(declaration)for(var name:fields){String prefix="/"+name.replace("~","~0").replace("/","~1");included|=field.equals(prefix)||field.startsWith(prefix+"/");}
            if(included)facts.add(fact.deepCopy());
        }
        if(facts.isEmpty())throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"EVIDENCE_PROPERTY_FACTS_UNAVAILABLE","所选属性没有已定位事实");
        result.put("representationKind",kind+"_projection");result.set("content",content);result.set("facts",facts);
        var coverage=json.createObjectNode().put("partial",fields.size()!=values.size()).put("truncated",false).put("readPropertyCount",fields.size()).put("totalPropertyCount",values.size());
        coverage.set("fields",json.valueToTree(fields));result.set("coverage",coverage);
        result.withArray("capabilities").removeAll().add("read_properties");return result;
    }
    private ObjectNode jsonSubtree(JsonNode manifest,JsonNode request,Loader loader)throws Exception{
        var pointer=request.get("jsonPointer");
        if(pointer==null||!pointer.isTextual()||pointer.asText().length()>2048||(!pointer.asText().isEmpty()&&!pointer.asText().startsWith("/"))||java.util.regex.Pattern.compile("~(?![01])").matcher(pointer.asText()).find())
            throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_JSON_POINTER_INVALID","需要 RFC6901 JSON Pointer");
        String target=pointer.asText();if(target.split("/",-1).length>65)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","JSON 层级超限");
        Integer line=request.has("jsonlLine")?integer(request,"jsonlLine",1,1,Integer.MAX_VALUE):null;
        boolean jsonl="JSONL".equals(manifest.path("identity").path("originalFormat").asText());
        if(jsonl&&line==null)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_JSONL_LINE_REQUIRED","JSONL 精读需要物理行号");
        if(!jsonl&&line!=null)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","该来源不是 JSONL");
        JsonNode selected=null;int depth=-1;
        for(var descriptor:descriptors(manifest,"json_subtree")){
            var location=descriptor.path("locator");String prefix=location.path("jsonPointer").asText();
            if(line!=null&&location.path("jsonlLine").asInt()!=line)continue;
            boolean exact=target.equals(prefix),inside=target.startsWith(prefix+"/");
            if((exact||(inside&&descriptor.path("coverage").path("completeSubtree").asBoolean()))&&prefix.length()>depth){selected=descriptor;depth=prefix.length();}
        }
        JsonNode inline=null,inlineDescriptor=null;
        for(var descriptor:descriptors(manifest,"json_children_page")){
            var selector=descriptor.path("selector");if(line!=null&&selector.path("jsonlLine").asInt()!=line)continue;
            for(var candidate:selector.path("inlinePointers")){
                String prefix=candidate.asText();if((target.equals(prefix)||target.startsWith(prefix+"/"))&&prefix.length()>depth){depth=prefix.length();inlineDescriptor=descriptor;}
            }
        }
        if(jsonl)for(var descriptor:descriptors(manifest,"json_documents_page"))for(var candidate:descriptor.path("selector").path("jsonlLines"))if(candidate.asInt()==line&&depth<0){inlineDescriptor=descriptor;depth=0;}
        JsonNode original;String prefix;JsonNode sourceValue,numbersSource;
        if(inlineDescriptor!=null){
            original=loader.load(inlineDescriptor);
            if("json_documents_page".equals(inlineDescriptor.path("kind").asText())){
                for(var document:original.path("content").path("documents"))if(document.path("physicalLine").asInt()==line)inline=document;
                prefix="";
            }else{
                prefix=null;
                for(var child:original.path("content").path("children")){
                    String p=child.path("jsonPointer").asText();if(child.has("value")&&(target.equals(p)||target.startsWith(p+"/"))&&(prefix==null||p.length()>prefix.length())){inline=child;prefix=p;}
                }
            }
            if(inline==null||prefix==null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_MANIFEST_MISMATCH","内联子树定位不一致");
            sourceValue=inline.path("value");numbersSource=inline.path("numberLexemes");
        }else{
            if(selected==null)throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_JSON_SUBTREE_NOT_FOUND","当前快照没有该行或子树");
            original=loader.load(selected);
            if(!original.path("coverage").path("completeSubtree").asBoolean())return (ObjectNode)original.deepCopy();
            prefix=selected.path("locator").path("jsonPointer").asText();sourceValue=original.path("content").path("value");numbersSource=original.path("content").path("numberLexemes");
        }
        JsonNode value=sourceValue.at(JsonPointer.compile(target.substring(prefix.length())));
        if(value.isMissingNode())throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_JSON_SUBTREE_NOT_FOUND","该 JSON Pointer 不存在");
        var result=projected(original,request);var location=json.createObjectNode().put("jsonPointer",target).put("arrayIndexBase",0);if(line!=null)location.put("jsonlLine",line);
        result.put("representationKind","json_subtree_projection");
        result.set("locator",location);var numbers=json.createObjectNode();
        numbersSource.fields().forEachRemaining(entry->{if(entry.getKey().equals(target)||entry.getKey().startsWith(target+"/"))numbers.set(entry.getKey(),entry.getValue());});
        result.set("content",json.createObjectNode().set("value",value.deepCopy()));((ObjectNode)result.get("content")).set("numberLexemes",numbers);
        var identity=original.path("identity");String factId=stable(identity.path("assetId").asText(),identity.path("sourceSha256").asText(),original.path("recipeVersion").asText(),canonical(location),"source_record","value");
        if("native-evidence-2026-10-22.2".equals(original.path("recipeVersion").asText()))
            factId=stable(identity.path("datasetId").asText(),identity.path("versionId").asText(),identity.path("indexVersionId").asText(),identity.path("rawCommitId").asText(),
                identity.path("assetId").asText(),identity.path("sourceSha256").asText(),original.path("recipeVersion").asText(),identity.path("evidenceConfigSha256").asText(),
                canonical(location),"source_record","value","null");
        var fact=json.createObjectNode().put("factId",factId).put("field","value").put("evidenceNature","source_record")
            .put("calculationEligible",numbers.path(target).path("calculationEligible").asBoolean(true));
        fact.set("locator",location.deepCopy());fact.set("value",value.deepCopy());fact.set("sourceValue",value.deepCopy());fact.set("qualityFlags",json.createArrayNode());fact.putNull("unit");
        fact.set("numberSourceExpression",numbers.path(target).has("lexeme")?numbers.path(target).get("lexeme"):NullNode.instance);
        result.set("facts",json.createArrayNode().add(fact));result.set("coverage",json.createObjectNode().put("partial",false).put("truncated",false).put("completeSubtree",true).put("readScope","json-subtree"));return result;
    }
    private JsonNode fields(JsonNode original,List<String> names){
        var result=json.createObjectNode();if(original.isObject())original.fields().forEachRemaining(entry->{if(names.isEmpty()||names.contains(entry.getKey()))result.set(entry.getKey(),entry.getValue().deepCopy());});return result;
    }
    private ObjectNode table(JsonNode manifest,JsonNode request,Loader loader)throws Exception{
        String id=required(request,"tableId",200);int start=integer(request,"rowStart",1,1,Integer.MAX_VALUE),end=integer(request,"rowEnd",Integer.MAX_VALUE,start,Integer.MAX_VALUE),limit=integer(request,"limit",8,1,32);
        List<String> names=strings(request,"fields",64);JsonNode schema=null;boolean packedEmpty=false;
        for(var descriptor:descriptors(manifest,"table_schema"))if(id.equals(descriptor.path("selector").path("tableId").asText())){
            if(schema!=null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS","表定义绑定重复");schema=descriptor;
        }
        for(var descriptor:descriptors(manifest,"json_empty_table_schemas"))for(var tableId:descriptor.path("selector").path("tableIds"))if(id.equals(tableId.asText())){
            if(schema!=null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS","表定义绑定重复");schema=descriptor;packedEmpty=true;
        }
        if(schema==null)throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_TABLE_NOT_FOUND","工作表或数组表不在该资产中");
        var header=loader.load(schema);
        if(packedEmpty){
            if(json.writeValueAsBytes(header).length>4L*1024*1024)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET","空表定义批次超过本次字节限制");
            if(!"json-tree-record-table-2026-10-26.1".equals(header.path("content").path("version").asText()))
                throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","空表批次配方未注册");
            JsonNode selected=null;
            for(var entry:header.path("content").path("tables"))if(id.equals(entry.path("content").path("tableId").asText())){
                if(selected!=null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS","空表定义重复");selected=entry;
            }
            if(selected==null||!selected.path("locator").isObject()||!selected.path("content").path("jsonRecordTable").path("rowCount").isIntegralNumber()
                ||selected.path("content").path("jsonRecordTable").path("rowCount").asInt()!=0
                ||!"json-tree-record-table-2026-10-26.1".equals(selected.path("content").path("jsonRecordTable").path("version").asText())
                ||!selected.path("content").path("columns").isArray()||!selected.path("content").path("columns").isEmpty()
                ||!selected.path("content").path("jsonRecordTable").path("rowMapPages").isArray()||!selected.path("content").path("jsonRecordTable").path("rowMapPages").isEmpty())
                throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","空表批次内容与选择器不一致");
            ObjectNode single=header.deepCopy();single.set("content",selected.path("content").deepCopy());single.set("locator",selected.path("locator").deepCopy());
            var flags=new TreeSet<String>();for(var flag:header.path("qualityFlags"))flags.add(flag.asText());for(var flag:selected.path("qualityFlags"))flags.add(flag.asText());single.set("qualityFlags",json.valueToTree(flags));
            single.set("coverage",json.createObjectNode().put("partial",true).put("truncated",false).put("totalRowCount",0));header=single;
        }
        var known=new HashSet<String>();for(var field:header.path("content").path("columns"))known.add(field.path("name").asText());
        if(!known.containsAll(names))throw fail(HttpStatus.UNPROCESSABLE_ENTITY,"EVIDENCE_FIELD_NOT_FOUND","所选列不在冻结表定义中");
        if(header.path("content").has("jsonRecordTable"))return jsonRecordTable(manifest,request,loader,header,names,start,end,limit);
        var result=projected(header,request);var rows=json.createArrayNode();var facts=json.createArrayNode();var parents=json.createArrayNode().add(header.path("evidenceId").asText());boolean more=false;
        outer:for(var descriptor:descriptors(manifest,"table_records")){
            var selector=descriptor.path("selector");if(!id.equals(selector.path("tableId").asText())||selector.path("rowEnd").asInt()<start||selector.path("rowStart").asInt()>end)continue;
            var page=loader.load(descriptor);parents.add(page.path("evidenceId").asText());
            for(var row:page.path("content").path("records")){
                int number=row.path("rawRow").asInt();if(number<start||number>end)continue;
                if(rows.size()>=limit){more=true;break outer;}
                ObjectNode output=row.deepCopy();for(String key:List.of("values","sourceValues","formulas","calculationEligibility"))if(output.has(key))output.set(key,fields(output.get(key),names));
                if(!names.isEmpty()){
                    var map=json.createObjectNode();row.path("numberLexemes").fields().forEachRemaining(entry->{String prefix=row.path("locator").path("jsonPointer").asText();if(names.contains(entry.getKey())||names.stream().anyMatch(name->{String p=prefix+"/"+name.replace("~","~0").replace("/","~1");return entry.getKey().equals(p)||entry.getKey().startsWith(p+"/");}))map.set(entry.getKey(),entry.getValue());});output.set("numberLexemes",map);
                    var transforms=json.createArrayNode();for(var transform:row.path("valueTransforms"))if(names.contains(transform.path("field").asText()))transforms.add(transform);output.set("valueTransforms",transforms);
                    if(output.path("locator").has("cells"))((ObjectNode)output.get("locator")).set("cells",fields(output.path("locator").path("cells"),names));
                }
                rows.add(output);
                for(var fact:page.path("facts"))if(fact.path("locator").path("rawRow").asInt()==number&&(names.isEmpty()||names.contains(fact.path("field").asText())))facts.add(fact.deepCopy());
            }
        }
        var columns=json.createArrayNode();for(var field:header.path("content").path("columns"))if(names.isEmpty()||names.contains(field.path("name").asText()))columns.add(field);
        var content=json.createObjectNode().put("tableId",id);content.set("columns",columns);content.set("records",rows);result.set("content",content);result.set("facts",facts);result.set("parentEvidenceIds",parents);result.put("representationKind","table_record_projection");
        var coverage=json.createObjectNode().put("partial",true).put("truncated",more).put("requestedRangeComplete",!more).put("returnedRowCount",rows.size()).put("totalRowCount",header.path("coverage").path("totalRowCount").asInt()).put("rowStart",start).put("rowEnd",end).put("hasMore",more);if(more)coverage.put("nextRow",rows.get(rows.size()-1).path("rawRow").asInt()+1);result.set("coverage",coverage);return result;
    }
    private ObjectNode jsonRecordTable(JsonNode manifest,JsonNode request,Loader loader,JsonNode header,List<String> names,int start,int end,int limit)throws Exception{
        String id=header.path("content").path("tableId").asText(),format=manifest.path("identity").path("originalFormat").asText();
        var metadata=header.path("content").path("jsonRecordTable");
        String version=metadata.path("version").asText();
        if(!Set.of("JSON","JSONL").contains(format)||!Set.of("json-tree-record-table-2026-10-25.1","json-tree-record-table-2026-10-26.1").contains(version)
            ||!"authoritative-native-json-tree".equals(metadata.path("valueBacking").asText())||!metadata.path("rowMapPages").isArray()
            ||!metadata.path("rowCount").isIntegralNumber()||!metadata.path("rowCount").canConvertToInt()||metadata.path("rowCount").asInt()<0
            ||metadata.path("rowCount").asInt()!=header.path("coverage").path("totalRowCount").asInt()
            ||!"native-evidence-2026-10-22.2".equals(header.path("recipeVersion").asText()))
            throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","记录表未绑定兼容的原生 JSON 配方");
        // Cache only inside this authorized read. QaEvidenceService retains its
        // existing 32-load/15-second guard and rechecks access after projection.
        var cache=new HashMap<String,JsonNode>();cache.put(header.path("representationId").asText(),header);
        long[] bytes={json.writeValueAsBytes(header).length};
        if(bytes[0]>4L*1024*1024)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET","JSON 表定义超过本次字节限制");
        Loader cached=descriptor->{
            String key=descriptor.path("representationId").asText();var value=cache.get(key);
            if(value!=null)return value;
            if(bytes[0]+descriptor.path("sizeBytes").asLong()>4L*1024*1024)
                throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET","JSON 记录读取超过本次字节限制，请缩小行列范围");
            value=loader.load(descriptor);bytes[0]+=json.writeValueAsBytes(value).length;
            if(bytes[0]>4L*1024*1024)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET","JSON 记录读取超过本次字节限制");
            cache.put(key,value);return value;
        };
        var selected=new ArrayList<JsonNode>();var parents=new LinkedHashSet<String>();parents.add(header.path("evidenceId").asText());
        var pageIds=new HashSet<String>();int previous=0;boolean more=false;
        for(var pageId:metadata.path("rowMapPages")){
            if(!pageId.isTextual()||!pageIds.add(pageId.asText()))throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","行映射页绑定重复或无效");
            var descriptor=byId(manifest,pageId.asText());var selector=descriptor.path("selector");
            if(!"json_record_row_map".equals(descriptor.path("kind").asText())||!id.equals(selector.path("tableId").asText()))
                throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","行映射页不属于所选表");
            if(selector.path("rowEnd").asInt()<start||selector.path("rowStart").asInt()>end)continue;
            var page=cached.load(descriptor);var bindings=page.path("content").path("records");
            if(!version.equals(page.path("content").path("version").asText())||!id.equals(page.path("content").path("tableId").asText())||!bindings.isArray())
                throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","行映射内容不一致");
            parents.add(page.path("evidenceId").asText());
            for(var binding:bindings){
                var number=binding.path("rawRow");
                if(!number.isIntegralNumber()||!number.canConvertToInt()||number.asInt()<=previous||!binding.path("jsonPointer").isTextual()||!binding.path("fields").isArray())
                    throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","行号、字段或 JSON Pointer 无效");
                previous=number.asInt();
                if(previous<start||previous>end)continue;
                if(selected.size()==limit){more=true;break;}
                selected.add(binding);
            }
            if(more)break;
        }
        var registeredFields=new HashSet<String>();for(var column:header.path("content").path("columns"))registeredFields.add(column.path("name").asText());
        var result=projected(header,request);var rows=json.createArrayNode();var facts=json.createArrayNode();int reads=0;
        for(var binding:selected){
            String pointer=binding.path("jsonPointer").asText();
            if(pointer.length()>2048||!pointer.isEmpty()&&!pointer.startsWith("/")||java.util.regex.Pattern.compile("~(?![01])").matcher(pointer).find())
                throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","JSON Pointer 无效");
            var fields=new LinkedHashSet<String>();
            for(var field:binding.path("fields"))if(!field.isTextual()||!fields.add(field.asText()))
                throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","源字段绑定无效或重复");
            if(!registeredFields.containsAll(fields))throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","行字段超出冻结表定义");
            if("JSONL".equals(format)){
                var line=binding.path("jsonlLine");if(!line.isIntegralNumber()||!line.canConvertToInt()||line.asInt()<1)
                    throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","JSONL 缺少原始物理行号");
            }else if(binding.has("jsonlLine"))throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","JSON 不能带入事件物理行号");
            var location=(ObjectNode)header.path("locator").deepCopy();location.put("rawRow",binding.path("rawRow").asInt()).put("jsonPointer",pointer);
            if(binding.has("jsonlLine"))location.set("jsonlLine",binding.get("jsonlLine"));
            var values=json.createObjectNode();var numbers=json.createObjectNode();var eligibility=json.createObjectNode();var absent=json.createArrayNode();
            for(var field:names.isEmpty()?fields:names){
                if(!fields.contains(field)){absent.add(field);continue;}
                if(++reads>256)throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_READ_BUDGET","单次 JSON 记录最多读取 256 个字段，请缩小行列范围");
                String target=pointer+"/"+field.replace("~","~0").replace("/","~1");
                var leafRequest=json.createObjectNode().put("operation","read_json_subtree").put("jsonPointer",target);
                if(binding.has("jsonlLine"))leafRequest.set("jsonlLine",binding.get("jsonlLine"));
                var leaf=jsonSubtree(manifest,leafRequest,cached);
                if(!leaf.path("coverage").path("completeSubtree").asBoolean()||leaf.path("coverage").path("truncated").asBoolean()||leaf.path("facts").size()!=1||!leaf.path("content").has("value"))
                    throw fail(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_RECORD_VALUE_INCOMPLETE","字段需要进一步精读，不能把部分子树当成完整记录值");
                var sourceFact=leaf.path("facts").get(0);JsonNode value=leaf.path("content").get("value");
                values.set(field,value.deepCopy());leaf.path("content").path("numberLexemes").fields().forEachRemaining(entry->numbers.set(entry.getKey(),entry.getValue().deepCopy()));
                eligibility.put(field,sourceFact.path("calculationEligible").asBoolean(true));
                var fieldLocation=location.deepCopy().put("field",field).put("jsonPointer",target);
                JsonNode unit=header.path("content").path("fieldUnits").get(field);if(unit==null)unit=NullNode.instance;
                if(!unit.isNull()&&(!unit.isTextual()||unit.asText().length()>128))throw fail(HttpStatus.CONFLICT,"EVIDENCE_RECORD_TABLE_INVALID","字段单位绑定无效");
                var identity=header.path("identity");
                String factId=stable(identity.path("datasetId").asText(),identity.path("versionId").asText(),identity.path("indexVersionId").asText(),identity.path("rawCommitId").asText(),
                    identity.path("assetId").asText(),identity.path("sourceSha256").asText(),header.path("recipeVersion").asText(),identity.path("evidenceConfigSha256").asText(),canonical(fieldLocation),"source_record",field,canonical(unit));
                ObjectNode fact=sourceFact.deepCopy();fact.put("factId",factId).put("field",field).put("sourceFactId",sourceFact.path("factId").asText());
                fact.set("locator",fieldLocation);fact.set("unit",unit.deepCopy());
                if(!unit.isNull())fact.set("unitSource",json.createObjectNode().put("kind","frozen-table-field-binding").put("tableId",id).put("field",field).put("evidenceId",header.path("evidenceId").asText()));
                facts.add(fact);for(var parent:leaf.path("parentEvidenceIds"))parents.add(parent.asText());
            }
            var row=json.createObjectNode().put("rawRow",binding.path("rawRow").asInt());row.set("locator",location);row.set("values",values);row.set("sourceValues",values.deepCopy());
            row.set("numberLexemes",numbers);row.set("calculationEligibility",eligibility);row.set("absentFields",absent);rows.add(row);
        }
        var columns=json.createArrayNode();for(var field:header.path("content").path("columns"))if(names.isEmpty()||names.contains(field.path("name").asText()))columns.add(field.deepCopy());
        var content=json.createObjectNode().put("tableId",id).put("valueBacking","authoritative-native-json-tree").put("jsonRecordTableVersion",version);
        content.set("columns",columns);content.set("records",rows);result.set("content",content);result.set("facts",facts);result.set("parentEvidenceIds",json.valueToTree(parents));result.put("representationKind","table_record_projection");
        var coverage=json.createObjectNode().put("partial",true).put("truncated",more).put("requestedRangeComplete",!more).put("returnedRowCount",rows.size()).put("totalRowCount",metadata.path("rowCount").asInt())
            .put("rowStart",start).put("rowEnd",end).put("hasMore",more).put("readScope","json-record-table").put("fullTableRead",!more&&rows.size()==metadata.path("rowCount").asInt());
        if(more)coverage.put("nextRow",rows.get(rows.size()-1).path("rawRow").asInt()+1);result.set("coverage",coverage);return result;
    }
    private ObjectNode cells(JsonNode manifest,JsonNode request,Loader loader)throws Exception{
        String sheet=required(request,"sheet",256);var names=strings(request,"cells",64);if(names.isEmpty())throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","需要指定单元格");
        for(var name:names){if(!name.matches("[A-Z]{1,3}[1-9][0-9]{0,6}"))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_CELL_INVALID","单元格位置无效");String letters=name.replaceAll("[0-9]","");int column=0;for(char c:letters.toCharArray())column=column*26+c-'A'+1;if(column>16384||Integer.parseInt(name.substring(letters.length()))>1048576)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_CELL_INVALID","单元格超出工作簿格式范围");}
        JsonNode first=null;var selected=json.createArrayNode();var facts=json.createArrayNode();var parents=json.createArrayNode();var found=new HashSet<String>();
        for(var descriptor:descriptors(manifest,"workbook_cells")){
            if(!sheet.equals(descriptor.path("selector").path("sheet").asText()))continue;
            boolean needed=false;for(var cell:descriptor.path("selector").path("cells"))if(names.contains(cell.asText())){needed=true;break;}
            if(!needed)continue;var page=loader.load(descriptor);if(first==null)first=page;parents.add(page.path("evidenceId").asText());
            for(var cell:page.path("content").path("cells"))if(names.contains(cell.path("cell").asText())){selected.add(cell.deepCopy());found.add(cell.path("cell").asText());}
            for(var fact:page.path("facts"))if(names.contains(fact.path("locator").path("cell").asText()))facts.add(fact.deepCopy());
        }
        if(first==null)throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_CELLS_NOT_RECORDED","未找到指定工作表的源单元格记录；不将缺失值补0");
        var result=projected(first,request);var content=(ObjectNode)first.path("content").deepCopy();content.set("cells",selected);var missing=json.createArrayNode();names.stream().filter(name->!found.contains(name)).forEach(missing::add);content.set("unrecordedRequestedCells",missing);result.set("content",content);result.set("facts",facts);result.set("parentEvidenceIds",parents);result.set("locator",json.createObjectNode().put("sheet",sheet).set("cells",json.valueToTree(names)));
        result.set("coverage",json.createObjectNode().put("partial",true).put("truncated",false).put("requestedCellCount",names.size()).put("returnedCellCount",selected.size()).put("unrecordedCellCount",missing.size()));return result;
    }
    private double[] rectangle(JsonNode value,HttpStatus status)throws Exception{
        if(!value.isArray()||value.size()!=4)throw fail(status,"EVIDENCE_REGION_INVALID","区域需要四个有限坐标");
        double[] box=new double[4];for(int n=0;n<4;n++){
            if(!value.get(n).isNumber()||!Double.isFinite(value.get(n).asDouble()))throw fail(status,"EVIDENCE_REGION_INVALID","坐标必须为有限数值");
            box[n]=value.get(n).asDouble();
        }
        if(box[0]>=box[2]||box[1]>=box[3])throw fail(status,"EVIDENCE_REGION_INVALID","区域宽高必须为正");return box;
    }
    private boolean intersects(double[] left,double[] right){return Math.max(left[0],right[0])<Math.min(left[2],right[2])&&Math.max(left[1],right[1])<Math.min(left[3],right[3]);}
    ObjectNode renderPlan(JsonNode manifest,JsonNode request)throws Exception{
        if(request!=null&&request.isObject()&&"render_engineering_image".equals(request.path("operation").asText()))return engineeringRenderPlan(manifest,request);
        if(request==null||!request.isObject()||!"render_region_image".equals(request.path("operation").asText()))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","需要明确的区域渲染操作");
        Set<String> allowed=Set.of("operation","page","bbox","dpi","maxPixels");request.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","渲染操作包含未允许的参数");});
        if("PDF".equals(manifest.path("identity").path("originalFormat").asText())&&!request.has("page"))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_PAGE_REQUIRED","PDF区域渲染需要一基页码");
        int page=integer(request,"page",1,1,256),dpi=integer(request,"dpi",200,72,300),pixels=integer(request,"maxPixels",1_000_000,32768,4_000_000);
        JsonNode selected=null;for(var descriptor:descriptors(manifest,"document_page"))if(descriptor.path("selector").path("page").asInt()==page){
            if(selected!=null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS","页码绑定重复");selected=descriptor;
        }
        if(selected==null)throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_PAGE_NOT_FOUND","该冻结资产没有指定页的原生表示");
        boolean registered=false;for(var capability:selected.path("capabilities"))if("render_region_image".equals(capability.asText()))registered=true;
        if(!registered||!"native-region-render-2026-10-22.1".equals(selected.path("selector").path("renderRecipeVersion").asText()))throw fail(HttpStatus.CONFLICT,"EVIDENCE_INDEX_REBUILD_REQUIRED","该冻结索引尚未注册受控区域渲染配方");
        double[] bounds=rectangle(selected.path("selector").path("pageBounds"),HttpStatus.CONFLICT),box=rectangle(request.path("bbox"),HttpStatus.BAD_REQUEST);
        for(int n=0;n<2;n++)if(box[n]<bounds[n]||box[n+2]>bounds[n+2])throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_REGION_INVALID","区域超出冻结页或原图范围");
        var parameters=json.createObjectNode().put("page",page).put("dpi",dpi).put("maxPixels",pixels);parameters.set("bbox",request.get("bbox").deepCopy());
        var result=json.createObjectNode().put("recipeVersion",selected.path("selector").path("renderRecipeVersion").asText());result.set("parameters",parameters);result.set("parent",selected.deepCopy());return result;
    }
    private ObjectNode engineeringRenderPlan(JsonNode manifest,JsonNode request)throws Exception{
        Set<String> allowed=Set.of("operation","view","bbox","maxPixels","instanceIds","highlightInstanceIds");
        request.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_SELECTOR_INVALID","工程渲染包含未允许的参数");});
        String format=manifest.path("identity").path("originalFormat").asText();
        if(!Set.of("SVG","OBJ","STEP").contains(format))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_RENDER_FORMAT_UNSUPPORTED","该源不支持工程视图渲染");
        var candidates=descriptors(manifest,"SVG".equals(format)?"svg_views":"geometry_views");
        if(candidates.size()!=1)throw fail(HttpStatus.CONFLICT,"EVIDENCE_INDEX_REBUILD_REQUIRED","需要唯一且已注册的工程视图表示");
        var selected=candidates.get(0);var selector=selected.path("selector");boolean registered=false;
        for(var capability:selected.path("capabilities"))registered|="render_engineering_image".equals(capability.asText());
        if(!registered||!"engineering-region-render-2026-10-22.1".equals(selector.path("renderRecipeVersion").asText())||!selector.path("engineBinding").isObject()||json.writeValueAsBytes(selector.path("engineBinding")).length>4096)
            throw fail(HttpStatus.CONFLICT,"EVIDENCE_INDEX_REBUILD_REQUIRED","该冻结索引未绑定工程渲染配方及引擎");
        String view=request.has("view")?required(request,"view",32):"SVG".equals(format)?"source-2d":"isometric";
        boolean found=false;for(var value:selector.path("allowedViews"))found|=view.equals(value.asText());
        if(!found)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_VIEW_UNREGISTERED","视角不属于冻结的相机集合");
        JsonNode boxNode=request.has("bbox")?request.get("bbox"):selector.path("bounds");
        double[] box=rectangle(boxNode,HttpStatus.BAD_REQUEST),bounds=rectangle(selector.path("bounds"),HttpStatus.CONFLICT);
        for(int n=0;n<2;n++)if(box[n]<bounds[n]||box[n+2]>bounds[n+2])throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_REGION_INVALID","区域超出注册视图或图纸范围");
        if(!"SVG".equals(format)&&Math.min(box[2]-box[0],box[3]-box[1])<.001)throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_REGION_INVALID","局部区域超出最大缩放范围");
        var instances=strings(request,"instanceIds",8);var highlights=strings(request,"highlightInstanceIds",8);var available=new HashSet<String>();
        for(var value:selector.path("allowedInstanceIds"))available.add(value.asText());
        if(!available.containsAll(instances)||!available.containsAll(highlights)||(!instances.isEmpty()&&!instances.containsAll(highlights)))
            throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_INSTANCE_UNREGISTERED","实例或高亮不属于冻结源及所选范围");
        int pixels=integer(request,"maxPixels",1_000_000,65536,1048576);
        var parameters=json.createObjectNode().put("view",view).put("maxPixels",pixels);parameters.set("bbox",boxNode.deepCopy());
        parameters.set("instanceIds",json.valueToTree(instances));parameters.set("highlightInstanceIds",json.valueToTree(highlights));parameters.set("engineBinding",selector.path("engineBinding").deepCopy());
        var result=json.createObjectNode().put("recipeVersion",selector.path("renderRecipeVersion").asText());result.set("parameters",parameters);result.set("parent",selected.deepCopy());
        result.set("expectedEngineBinding",selector.path("engineBinding").deepCopy());return result;
    }
    private ObjectNode region(JsonNode manifest,JsonNode request,Loader loader)throws Exception{
        boolean pdf="PDF".equals(manifest.path("identity").path("originalFormat").asText());
        if(pdf&&!request.has("page"))throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_PAGE_REQUIRED","PDF区域精读需要一基页码");
        int pageNumber=integer(request,"page",1,1,256),limit=integer(request,"limit",64,1,256);
        JsonNode pageDescriptor=null;
        for(var descriptor:descriptors(manifest,"document_page"))if(descriptor.path("selector").path("page").asInt()==pageNumber){
            if(pageDescriptor!=null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS","页码绑定重复");pageDescriptor=descriptor;
        }
        if(pageDescriptor==null)throw fail(HttpStatus.NOT_FOUND,"EVIDENCE_PAGE_NOT_FOUND","该资产没有指定的原生页表示");
        double[] bounds=rectangle(pageDescriptor.path("selector").path("pageBounds"),HttpStatus.CONFLICT);
        double[] box=rectangle(request.path("bbox"),HttpStatus.BAD_REQUEST);
        for(int n=0;n<2;n++)if(box[n]<bounds[n]||box[n+2]>bounds[n+2])throw fail(HttpStatus.BAD_REQUEST,"EVIDENCE_REGION_INVALID","区域超出源页或原图范围");
        var original=loader.load(pageDescriptor);var result=projected(original,request);
        var words=json.createArrayNode();var facts=json.createArrayNode();var parents=json.createArrayNode().add(original.path("evidenceId").asText());
        var flags=new TreeSet<String>();boolean more=false;var text=new StringJoiner(" ");
        outer:for(var descriptor:descriptors(manifest,"document_words")){
            var selector=descriptor.path("selector");if(selector.path("page").asInt()!=pageNumber||!intersects(box,rectangle(selector.path("bbox"),HttpStatus.CONFLICT)))continue;
            var bundle=loader.load(descriptor);parents.add(bundle.path("evidenceId").asText());var byId=new HashMap<String,JsonNode>();
            for(var fact:bundle.path("facts"))if(byId.put(fact.path("factId").asText(),fact)!=null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_IDENTITY_AMBIGUOUS","页内事实标识重复");
            for(var word:bundle.path("content").path("words")){
                if(!intersects(box,rectangle(word.path("bbox"),HttpStatus.CONFLICT)))continue;
                if(words.size()>=limit){more=true;break outer;}
                var fact=byId.get(word.path("factId").asText());if(fact==null)throw fail(HttpStatus.CONFLICT,"EVIDENCE_MANIFEST_MISMATCH","区域文字没有对应事实");
                words.add(word.deepCopy());facts.add(fact.deepCopy());text.add(word.path("text").asText());
                for(var flag:word.path("qualityFlags"))flags.add(flag.asText());
            }
        }
        var content=json.createObjectNode().put("text",text.toString()).put("selectionPolicy","whole-words-intersecting-region");content.set("words",words);
        result.set("content",content);result.set("facts",facts);result.set("parentEvidenceIds",parents);result.put("representationKind","document_region_text_projection");
        var location=json.createObjectNode().put("page",pageNumber).put("coordinateSystem",pageDescriptor.path("selector").path("coordinateSystem").asText());location.set("bbox",request.get("bbox").deepCopy());result.set("locator",location);
        for(var flag:original.path("qualityFlags"))flags.add(flag.asText());result.set("qualityFlags",json.valueToTree(flags));
        result.set("coverage",json.createObjectNode().put("partial",true).put("truncated",more).put("requestedRegionComplete",!more)
            .put("returnedWordCount",words.size()).put("totalPageWordCount",original.path("coverage").path("totalWordCount").asInt())
            .put("hasMore",more).put("semanticReadingOrderVerified",false));return result;
    }
}
