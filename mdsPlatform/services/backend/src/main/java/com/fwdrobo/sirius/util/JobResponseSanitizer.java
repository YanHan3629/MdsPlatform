package com.fwdrobo.sirius.util;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.Pattern;
/** Public job metadata never carries executable credentials from stored environments. */
public final class JobResponseSanitizer {
 private JobResponseSanitizer(){}
 private static final String REDACTED="[REDACTED]";
 private static final Pattern SECRET_KEY=Pattern.compile(".*(?:token|password|passwd|secret|credential|authorization|authheader|privatekey|cookie|pgpass|accesskey|apikey).*");
 private static final Pattern JWT=Pattern.compile("(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
 private static final Pattern AUTH=Pattern.compile("(?i)(\\b(?:Bearer|Basic)\\s+)[^\\s\\\"']+");
 private static final Pattern INTERNAL=Pattern.compile("(?i)(X-MM-Internal-Token\\s*[:=]\\s*)[^\\s\\\"']+");
 private static final Pattern URL_AUTH=Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@");
 public static String text(String value){
  if(value==null)return null;
  value=JWT.matcher(value).replaceAll(REDACTED);
  value=AUTH.matcher(value).replaceAll("$1"+REDACTED);
  value=INTERNAL.matcher(value).replaceAll("$1"+REDACTED);
  return URL_AUTH.matcher(value).replaceAll("$1"+REDACTED+"@");
 }
 public static JsonNode params(JsonNode value){
  if(value==null)return null;
  if(value.isObject()){
   ObjectNode result=JsonNodeFactory.instance.objectNode();
   value.fields().forEachRemaining(e->{
    String normalized=e.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
    result.set(e.getKey(),SECRET_KEY.matcher(normalized).matches()?TextNode.valueOf(REDACTED):params(e.getValue()));
   });
   return result;
  }
  if(value.isArray()){
   ArrayNode result=JsonNodeFactory.instance.arrayNode();
   value.forEach(v->result.add(params(v)));return result;
  }
  return value.isTextual()?TextNode.valueOf(text(value.textValue())):value.deepCopy();
 }
}
