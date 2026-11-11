package com.fwdrobo.sirius.security;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Fixed enterprise master schema. Nodes never become enterprise identities. */
public final class EnterpriseCatalogCsv {
 private EnterpriseCatalogCsv(){}
 public record Company(String entityId,String name,String industryKind,int sourceRow){}
 public record Parsed(List<Company> companies,int nodeCount,int rowCount){}
 private static final Map<String,String> KINDS=Map.ofEntries(
  Map.entry("制造","MANUFACTURER"),Map.entry("电子供应","SUPPLIER"),Map.entry("驱动供应","SUPPLIER"),
  Map.entry("机械供应","SUPPLIER"),Map.entry("零件供应","SUPPLIER"),Map.entry("仓储","WAREHOUSE"),
  Map.entry("承运","CARRIER"),Map.entry("客户","CUSTOMER"),Map.entry("服务","SERVICE"));
 private static final Set<String> CANONICAL=Set.of("MANUFACTURER","SUPPLIER","WAREHOUSE","CARRIER","CUSTOMER","SERVICE");
 private static void bad(String code){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,code);}
 private static String identifier(String value){
  if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))bad("ENTERPRISE_CATALOG_INVALID_IDENTIFIER");
  return value;
 }
 public static Parsed parse(byte[] bytes){
  if(bytes==null||bytes.length==0||bytes.length>1_048_576)bad("ENTERPRISE_CATALOG_SIZE_LIMIT");
  String text;
  try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
  catch(CharacterCodingException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ENTERPRISE_CATALOG_UTF8_REQUIRED");}
  if(text.startsWith("\uFEFF"))text=text.substring(1);
  var rows=rows(text);if(rows.size()<2)bad("ENTERPRISE_CATALOG_EMPTY");
  var header=rows.get(0);var positions=new HashMap<String,Integer>();
  for(int n=0;n<header.size();n++){
   String name=header.get(n).trim();
   if(name.isEmpty()||positions.putIfAbsent(name,n)!=null)bad("ENTERPRISE_CATALOG_AMBIGUOUS_HEADER");
  }
  for(String required:List.of("record_type","company_id","name","role"))if(!positions.containsKey(required))bad("ENTERPRISE_CATALOG_SCHEMA_REQUIRED");
  var companies=new LinkedHashMap<String,Company>();var nodeIds=new HashSet<String>();var nodeCompanies=new ArrayList<String>();
  for(int n=1;n<rows.size();n++){
   var row=rows.get(n);if(row.stream().allMatch(String::isBlank))continue;
   if(row.size()!=header.size())bad("ENTERPRISE_CATALOG_COLUMN_COUNT");
   String type=row.get(positions.get("record_type")).trim().toLowerCase(Locale.ROOT);
   if(Set.of("企业","company","companies").contains(type)){
    String id=identifier(row.get(positions.get("company_id")).trim()),name=row.get(positions.get("name")).trim();
    if(name.isEmpty()||name.length()>256||name.chars().anyMatch(c->c<32))bad("ENTERPRISE_CATALOG_INVALID_NAME");
    String role=row.get(positions.get("role")).trim(),kind=KINDS.get(role);
    if(kind==null&&CANONICAL.contains(role.toUpperCase(Locale.ROOT)))kind=role.toUpperCase(Locale.ROOT);
    if(kind==null)bad("ENTERPRISE_CATALOG_UNRECOGNIZED_INDUSTRY");
    if(companies.putIfAbsent(id,new Company(id,name,kind,n+1))!=null)bad("ENTERPRISE_CATALOG_DUPLICATE_COMPANY");
   }else if(Set.of("节点","node","nodes").contains(type)){
    if(!positions.containsKey("node_id"))bad("ENTERPRISE_CATALOG_NODE_ID_REQUIRED");
    String id=identifier(row.get(positions.get("node_id")).trim());
    if(!nodeIds.add(id))bad("ENTERPRISE_CATALOG_DUPLICATE_NODE");
    nodeCompanies.add(identifier(row.get(positions.get("company_id")).trim()));
   }else bad("ENTERPRISE_CATALOG_UNRECOGNIZED_RECORD_TYPE");
  }
  if(companies.isEmpty()||companies.size()>10000)bad("ENTERPRISE_CATALOG_COMPANY_COUNT");
  if(nodeCompanies.stream().anyMatch(id->!companies.containsKey(id)))bad("ENTERPRISE_CATALOG_ORPHAN_NODE");
  return new Parsed(List.copyOf(companies.values()),nodeIds.size(),rows.size()-1);
 }
 private static List<List<String>> rows(String text){
  var result=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();
  boolean quoted=false,closed=false;
  for(int n=0;n<text.length();n++){
   char c=text.charAt(n);
   if(c=='\0')bad("ENTERPRISE_CATALOG_INVALID_CHARACTER");
   if(quoted){
    if(c=='"'){
     if(n+1<text.length()&&text.charAt(n+1)=='"'){field.append('"');n++;}
     else{quoted=false;closed=true;}
    }else field.append(c);
   }else if(c==','||c=='\n'||c=='\r'){
    row.add(field.toString());field.setLength(0);closed=false;
    if(row.size()>100)bad("ENTERPRISE_CATALOG_COLUMN_LIMIT");
    if(c!=','){
     if(c=='\r'&&n+1<text.length()&&text.charAt(n+1)=='\n')n++;
     result.add(List.copyOf(row));row.clear();
     if(result.size()>10001)bad("ENTERPRISE_CATALOG_ROW_LIMIT");
    }
   }else if(c=='"'){
    if(field.length()!=0||closed)bad("ENTERPRISE_CATALOG_INVALID_QUOTING");
    quoted=true;
   }else{
    if(closed)bad("ENTERPRISE_CATALOG_INVALID_QUOTING");
    field.append(c);
   }
   if(field.length()>16384)bad("ENTERPRISE_CATALOG_FIELD_LIMIT");
  }
  if(quoted)bad("ENTERPRISE_CATALOG_UNTERMINATED_QUOTE");
  if(field.length()>0||closed||!row.isEmpty()){row.add(field.toString());result.add(List.copyOf(row));}
  return result;
 }
}
