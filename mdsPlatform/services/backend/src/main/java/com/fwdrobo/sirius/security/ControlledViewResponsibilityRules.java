package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.util.*;

/** Fixed responsibility recipe; it neither confirms review nor grants content access. */
public final class ControlledViewResponsibilityRules {
 public static final String SCHEMA="ENTERPRISE_RESPONSIBILITY_RULES_V1";
 public static final Set<String> KINDS=Set.of("ROW_PROJECTION","EXPLICIT_PARTY_COLUMN_PROJECTION","JSON_POINTER_PROJECTION","JSONL_RECORD_PROJECTION","XLSX_VISIBLE_TYPED_CELL_REBUILD","SERIES_SCOPE_PROJECTION","COMPLETE_FILE");
 private static final Set<String> COMPLETE_EXTENSIONS=Set.of("txt","pdf","png","jpg","jpeg","svg","obj","step","stp");
 private ControlledViewResponsibilityRules(){}
 public static boolean is(JsonNode rules){return SCHEMA.equals(rules.path("schema").asText());}
 private static void fail(String code){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,code);}
 private static String text(JsonNode n){
  if(n==null||!n.isTextual()||n.asText().isEmpty()||n.asText().length()>4096||n.asText().indexOf('\0')>=0)fail("VIEW_RESPONSIBILITY_TEXT_REQUIRED");return n.asText();
 }
 private static String path(JsonNode n){
  String p=text(n);if(!p.startsWith("corpus/")||p.length()>1024||!Normalizer.isNormalized(p,Normalizer.Form.NFC)||p.contains("\\")||p.contains(":")||p.codePoints().anyMatch(c->c<32))fail("VIEW_RESPONSIBILITY_PATH_INVALID");
  for(String part:p.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))fail("VIEW_RESPONSIBILITY_PATH_INVALID");return p;
 }
 private static String ext(String p){return p.substring(p.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);}
 private static Set<String> records(JsonNode n){
  if(n==null||!n.isArray()||n.isEmpty()||n.size()>100000)fail("VIEW_RESPONSIBILITY_COMPLETE_RECORDS_REQUIRED");
  var values=new HashSet<String>();for(JsonNode value:n)if(!values.add(text(value)))fail("VIEW_RESPONSIBILITY_DUPLICATE_RECORD");return values;
 }
 public static JsonNode completeFiles(JsonNode files){
  if(files==null||!files.isArray()||files.size()>250)fail("VIEW_RESPONSIBILITY_COMPLETE_FILE_SELECTION_REQUIRED");
  if(ControlledViewWorkerScriptFactory.canonical(files).length>1048576)fail("VIEW_PROPOSAL_RULE_SIZE_LIMIT");
  var paths=new HashSet<String>();
  for(JsonNode f:files){
   var fields=new HashSet<String>();if(!f.isObject())fail("VIEW_RESPONSIBILITY_COMPLETE_FILE_FIELDS_INVALID");f.fieldNames().forEachRemaining(fields::add);
   if(!fields.equals(Set.of("sourcePath","sourceSha256","outputPath","kind","acknowledgement","recordIds")))fail("VIEW_RESPONSIBILITY_COMPLETE_FILE_FIELDS_INVALID");
   String source=path(f.get("sourcePath"));
   if(!source.equals(path(f.get("outputPath")))||!paths.add(source.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT))||!COMPLETE_EXTENSIONS.contains(ext(source))||
    !"COMPLETE_FILE".equals(f.path("kind").asText())||!text(f.get("sourceSha256")).matches("[a-f0-9]{64}")||
    !"EXACT_SOURCE_BYTES_INCLUDING_HIDDEN_CONTENT".equals(f.path("acknowledgement").asText()))fail("VIEW_RESPONSIBILITY_COMPLETE_FILE_INVALID");
   records(f.get("recordIds"));
  }
  return files.deepCopy();
 }
 /** Dynamic outputs are accepted only from the frozen recipe, bound source files and exact complete-file requests. */
 public static Map<String,JsonNode> expected(JsonNode plan,JsonNode candidate){
  if(plan.path("source").has("controlledParent"))fail("VIEW_RESPONSIBILITY_ORIGINAL_SOURCE_REQUIRED");
  JsonNode pending=candidate.path("pendingDecisionCount");
  if(!pending.isIntegralNumber()||!pending.canConvertToLong()||pending.asLong(-1)!=0)fail("VIEW_RESPONSIBILITY_PENDING_DECISIONS");
  var requested=new TreeMap<String,JsonNode>();for(JsonNode f:completeFiles(plan.path("rules").path("files")))requested.put(path(f.get("outputPath")),f);
  var input=new HashMap<String,JsonNode>();for(JsonNode f:plan.path("inventory").path("files"))input.put(f.path("logical_path").asText().replaceFirst("^/",""),f);
  var output=new HashMap<String,String>();for(JsonNode f:candidate.path("files"))output.put(f.path("path").asText(),f.path("sha256").asText());
  JsonNode provenance=candidate.path("provenance");
  if(!provenance.isArray()||provenance.isEmpty()||provenance.size()>2048)fail("VIEW_RESPONSIBILITY_PROVENANCE_REQUIRED");
  var result=new TreeMap<String,JsonNode>();var aliases=new HashSet<String>();var complete=new HashSet<String>();
  for(JsonNode f:provenance){
   String source=path(f.get("sourcePath")),out=path(f.get("path")),kind=text(f.get("kind"));JsonNode original=input.get(source);
   if(original==null||!original.path("sha256").equals(f.path("sourceSha256"))||!original.path("file_id").equals(f.path("sourceFileId"))||
    !Objects.equals(output.get(out),f.path("sha256").asText())||!KINDS.contains(kind)||!aliases.add(out.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)))
    fail("VIEW_RESPONSIBILITY_PROVENANCE_CHANGED");
   JsonNode locations=f.path("locations"),count=f.path("selectedRecordCount");
   if(!locations.isArray()||locations.isEmpty()||!count.isIntegralNumber()||!count.canConvertToLong()||count.asLong()!=locations.size())fail("VIEW_RESPONSIBILITY_RECORD_COUNT_CHANGED");
   boolean valid=switch(kind){
    case "ROW_PROJECTION"->source.startsWith("corpus/structured/")&&ext(source).equals("csv")&&out.equals(source);
    case "EXPLICIT_PARTY_COLUMN_PROJECTION"->source.startsWith("corpus/structured/")&&ext(source).equals("csv")&&out.equals(source.substring(0,source.lastIndexOf('/')+1)+"party_"+source.substring(source.lastIndexOf('/')+1));
    case "SERIES_SCOPE_PROJECTION"->source.startsWith("corpus/timeseries/")&&ext(source).equals("csv")&&out.equals(source);
    case "JSON_POINTER_PROJECTION"->ext(source).equals("json")&&out.equals(source);
    case "JSONL_RECORD_PROJECTION"->ext(source).equals("jsonl")&&out.equals(source);
    case "XLSX_VISIBLE_TYPED_CELL_REBUILD"->ext(source).equals("xlsx")&&out.equals(source)&&"AUTHORIZED_RANGE_AGGREGATES_V1".equals(f.path("formulaProjectionRule").asText());
    case "COMPLETE_FILE"->requested.containsKey(out)&&out.equals(source)&&requested.get(out).path("sourceSha256").equals(f.path("sourceSha256"));
    default->false;
   };
   if(!valid)fail("VIEW_RESPONSIBILITY_OUTPUT_RULE_CHANGED");
   for(JsonNode location:locations)if(!location.path("source").isObject()||!location.path("view").isObject())fail("VIEW_RESPONSIBILITY_LOCATION_REQUIRED");
   if(kind.equals("COMPLETE_FILE")){
    var observed=new HashSet<String>();for(JsonNode location:locations){
     String id=text(location.get("recordId"));
     if(!observed.add(id)||!location.path("source").path("whole_asset").asBoolean(false)||!location.path("view").path("whole_asset").asBoolean(false)||
      !id.equals(location.path("source").path("source_entity_id").asText())||!id.equals(location.path("view").path("source_entity_id").asText()))fail("VIEW_RESPONSIBILITY_COMPLETE_RECORDS_CHANGED");
    }
    if(!records(requested.get(out).get("recordIds")).equals(observed))fail("VIEW_RESPONSIBILITY_COMPLETE_RECORDS_CHANGED");complete.add(out);
   }
   ObjectNode rule=JsonNodeFactory.instance.objectNode();rule.put("sourcePath",source);rule.set("sourceSha256",f.path("sourceSha256"));rule.put("outputPath",out);rule.put("kind",kind);result.put(out,rule);
  }
  if(!complete.equals(requested.keySet()))fail("VIEW_RESPONSIBILITY_COMPLETE_REQUEST_NOT_MATERIALIZED");
  return result;
 }
}
