package com.fwdrobo.sirius.security;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.util.*;

/** Safe, view-local projection explanation. Never returns private source paths, IDs or hidden fields. */
public final class ControlledViewPublicProjection {
 private ControlledViewPublicProjection(){}
 private static void bad(){throw new ResponseStatusException(HttpStatus.CONFLICT,"CONTROLLED_VIEW_PROJECTION_SCOPE_INVALID");}
 private static String text(JsonNode value){
  if(value==null||!value.isTextual()||value.asText().isEmpty()||value.asText().length()>4096||value.asText().indexOf('\0')>=0)bad();return value.asText();
 }
 private static String output(JsonNode value){
  String p=text(value);
  if(!p.startsWith("corpus/")||p.length()>1024||p.contains("\\")||p.contains(":")||!Normalizer.isNormalized(p,Normalizer.Form.NFC)||p.codePoints().anyMatch(c->c<32))bad();
  for(String part:p.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))bad();return p;
 }
 private static void strings(ObjectNode result,JsonNode rule,String field){
  JsonNode values=rule.path(field);if(!values.isArray()||values.isEmpty()||values.size()>100000)bad();
  var names=new HashSet<String>();for(JsonNode value:values)if(!names.add(text(value)))bad();result.set(field,values.deepCopy());
 }
 private static void records(ObjectNode result,JsonNode rule){
  String key=text(rule.get("key"));result.put("key",key);strings(result,rule,"columns");
  boolean found=false;for(JsonNode field:rule.path("columns"))found|=key.equals(field.asText());if(!found)bad();
  if(rule.has("allRows")==rule.has("rowKeys"))bad();
  if(rule.has("allRows")){if(!rule.path("allRows").isBoolean()||!rule.path("allRows").booleanValue())bad();result.put("allRows",true);}
  else strings(result,rule,"rowKeys");
 }
 /** Responsibility details use verified output paths and counts, never private source locators. */
 public static ArrayNode describe(JsonNode rules,JsonNode selections){
  if(!ControlledViewResponsibilityRules.is(rules))return describe(rules);
  if(!selections.isArray()||selections.isEmpty()||selections.size()>2048)bad();
  var result=JsonNodeFactory.instance.arrayNode();var paths=new HashSet<String>();
  for(JsonNode selection:selections){
   String path=output(selection.get("path")),kind=text(selection.get("kind"));
   if(!paths.add(path.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT))||!ControlledViewResponsibilityRules.KINDS.contains(kind))bad();
   JsonNode count=selection.get("selectedRecordCount");
   if(count==null||!count.isIntegralNumber()||!count.canConvertToLong()||count.asLong()<1)bad();
   var item=result.addObject();item.put("path",path);item.put("kind",kind);item.put("selectedRecordCount",count.asLong());
   item.put("responsibilityRule","enterprise-responsibility-v3");
   if(kind.equals("XLSX_VISIBLE_TYPED_CELL_REBUILD"))item.put("formulaScope","AUTHORIZED_RANGE_AGGREGATES_V1");
   if(kind.equals("COMPLETE_FILE")){item.put("wholeAsset",true);item.put("includesHiddenContentAndMetadata",true);}
  }
  return result;
 }
 /** Null leaves separately versioned legacy rule formats on their existing public summary. */
 public static ArrayNode describe(JsonNode rules){
  if(!"COLLABORATION_VIEW_RULES_V1".equals(rules.path("schema").asText()))return null;
  var files=rules.path("files");if(!files.isArray()||files.isEmpty()||files.size()>250)bad();
  var result=JsonNodeFactory.instance.arrayNode();var paths=new HashSet<String>();
  for(JsonNode rule:files){
   String path=output(rule.get("outputPath")),kind=text(rule.get("kind"));if(!paths.add(path.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT)))bad();
   var item=result.addObject();item.put("path",path);item.put("kind",kind);
   switch(kind){
    case "CSV_ROWS","JSON_RECORDS","JSONL_RECORDS"->{records(item,rule);if(kind.equals("JSON_RECORDS"))item.put("recordCollection","/records");}
    case "XLSX_SHEETS"->{
     var sheets=rule.path("sheets");if(!sheets.isArray()||sheets.isEmpty()||sheets.size()>100)bad();var names=new HashSet<String>();var out=item.putArray("sheets");
     for(JsonNode sheet:sheets){
      String name=text(sheet.get("sheet"));if(!names.add(name)||!sheet.path("headerRow").isIntegralNumber()||sheet.path("headerRow").asLong()<1)bad();
      var selected=out.addObject();selected.put("sheet",name);selected.put("headerRow",1);selected.set("sourceHeaderRow",sheet.path("headerRow").deepCopy());records(selected,sheet);
     }
    }
    case "TEXT_LINES"->{
     var ranges=rule.path("ranges");if(!ranges.isArray()||ranges.isEmpty()||ranges.size()>10000)bad();long previous=0;
     for(JsonNode range:ranges){
      if(!range.isArray()||range.size()!=2||!range.get(0).isIntegralNumber()||!range.get(1).isIntegralNumber()||!range.get(0).canConvertToLong()||!range.get(1).canConvertToLong())bad();
      long first=range.get(0).asLong(),last=range.get(1).asLong();if(first<=previous||last<first)bad();previous=last;
     }
     item.set("sourceLineRanges",ranges.deepCopy());
    }
    case "COMPLETE_FILE"->{item.put("wholeAsset",true);item.put("includesHiddenContentAndMetadata",true);}
    default->bad();
   }
  }
  return result;
 }
}
