package com.fwdrobo.sirius.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Builds fixed materializer code from the current JAR, never from a submitted
 * program or mutable worker directory. The caller must separately authorize,
 * review and freeze the source, target and rules. This class grants no access.
 */
@Component
public final class ControlledViewWorkerScriptFactory {
 private static final List<String> PACKAGED=List.of("collaboration_view.py","collaboration_job.py","scope_ownership.py","scoped_xlsx.py","enterprise_view.py","enterprise_collaboration.py");
 private static final List<String> EXECUTED=List.of("collaboration_view.py","collaboration_job.py","scope_ownership.py","scoped_xlsx.py");
 private static final BigInteger MAX_INTEGER=new BigInteger("9007199254740991");
 private final ObjectMapper json;private final Map<String,byte[]> modules=new TreeMap<>();
 private final Map<String,String> hashes=new TreeMap<>();
 public ControlledViewWorkerScriptFactory(ObjectMapper json){
  this.json=json;
  for(String name:PACKAGED){
   try(InputStream stream=getClass().getResourceAsStream("/workers/controlled-view/"+name)){
    if(stream==null)throw new IllegalStateException("CONTROLLED_VIEW_WORKER_RESOURCE_MISSING");
    byte[] bytes=stream.readNBytes(262145);
    if(bytes.length<1||bytes.length>262144)throw new IllegalStateException("CONTROLLED_VIEW_WORKER_RESOURCE_SIZE_INVALID");
    modules.put(name,bytes);hashes.put(name,sha(bytes));
   }catch(java.io.IOException error){throw new IllegalStateException("CONTROLLED_VIEW_WORKER_RESOURCE_NOT_READABLE",error);}
  }
 }
 public record Prepared(String script,String scriptSha256,String ruleSha256,String inventorySha256,Map<String,String> moduleSha256){
  public Prepared{moduleSha256=Map.copyOf(moduleSha256);}
 }
 private static void fail(String code){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,code);}
 private static String sha(byte[] bytes){
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 private static String uuid(JsonNode node){
  if(node==null||!node.isTextual())fail("CONTROLLED_VIEW_UUID_REQUIRED");
  try{String s=UUID.fromString(node.asText()).toString();if(!s.equals(node.asText()))fail("CONTROLLED_VIEW_CANONICAL_UUID_REQUIRED");return s;}
  catch(IllegalArgumentException invalid){fail("CONTROLLED_VIEW_UUID_REQUIRED");return null;}
 }
 private static int compareCodepoints(String a,String b){
  int x=0,y=0;
  while(x<a.length()&&y<b.length()){
   int ac=a.codePointAt(x),bc=b.codePointAt(y);if(ac!=bc)return Integer.compare(ac,bc);
   x+=Character.charCount(ac);y+=Character.charCount(bc);
  }
  return Integer.compare(a.length()-x,b.length()-y);
 }
 private static void quote(String s,StringBuilder out){
  out.append('"');
  for(int i=0;i<s.length();i++){
   char c=s.charAt(i);
   switch(c){
    case '"'->out.append("\\\"");case '\\'->out.append("\\\\");
    case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");
    case '\r'->out.append("\\r");case '\t'->out.append("\\t");
    default->{
     if(c<32){out.append("\\u");String h=Integer.toHexString(c);out.append("0".repeat(4-h.length())).append(h);}
     else if(Character.isHighSurrogate(c)){
      if(i+1>=s.length()||!Character.isLowSurrogate(s.charAt(i+1)))fail("CONTROLLED_VIEW_INVALID_UNICODE");
      out.append(c).append(s.charAt(++i));
     }else if(Character.isLowSurrogate(c))fail("CONTROLLED_VIEW_INVALID_UNICODE");
     else out.append(c);
    }
   }
  }
  out.append('"');
 }
 private static void encode(JsonNode node,StringBuilder out){
  if(node==null||node.isNull()){out.append("null");return;}
  if(node.isTextual()){quote(node.textValue(),out);return;}
  if(node.isBoolean()){out.append(node.booleanValue()?"true":"false");return;}
  if(node.isIntegralNumber()){
   BigInteger n=node.bigIntegerValue();if(n.abs().compareTo(MAX_INTEGER)>0)fail("CONTROLLED_VIEW_INTEGER_OUTSIDE_CANONICAL_RANGE");
   out.append(n.toString());return;
  }
  if(node.isArray()){
   out.append('[');boolean first=true;for(JsonNode value:node){if(!first)out.append(',');first=false;encode(value,out);}out.append(']');return;
  }
  if(node.isObject()){
   var keys=new ArrayList<String>();node.fieldNames().forEachRemaining(keys::add);keys.sort(ControlledViewWorkerScriptFactory::compareCodepoints);
   out.append('{');boolean first=true;for(String key:keys){if(!first)out.append(',');first=false;quote(key,out);out.append(':');encode(node.get(key),out);}out.append('}');return;
  }
  fail("CONTROLLED_VIEW_NONCANONICAL_VALUE");
 }
 /** Same UTF-8 JSON bytes as the worker's canonical rule/inventory encoder. */
 public static byte[] canonical(JsonNode node){
  var out=new StringBuilder();encode(node,out);byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);
  if(bytes.length>8*1024*1024)fail("CONTROLLED_VIEW_CAPSULE_SIZE_LIMIT");return bytes;
 }
 public Map<String,String> packagedModuleSha256(){return Map.copyOf(hashes);}
 public Prepared prepare(UUID sourceRepo,JsonNode inventory,JsonNode rules){
  if(sourceRepo==null||inventory==null||!inventory.isObject()||rules==null||!rules.isObject())fail("CONTROLLED_VIEW_SOURCE_AND_RULES_REQUIRED");
  Set<String> expected=Set.of("schema","viewId","targetOrgId","targetEnterpriseId","source","files","reason");
  var actual=new HashSet<String>();rules.fieldNames().forEachRemaining(actual::add);
  boolean responsibility=ControlledViewResponsibilityRules.is(rules);
  if(!actual.equals(expected)||!responsibility&&!"COLLABORATION_VIEW_RULES_V1".equals(rules.path("schema").asText()))fail("CONTROLLED_VIEW_RULE_SCHEMA_INVALID");
  if(responsibility)ControlledViewResponsibilityRules.completeFiles(rules.path("files"));
  uuid(rules.get("viewId"));uuid(rules.get("targetOrgId"));
  if(!rules.path("targetEnterpriseId").asText("").matches("ENT-[A-Za-z0-9]{1,100}"))fail("CONTROLLED_VIEW_ENTERPRISE_ID_REQUIRED");
  if(!inventory.path("version").isObject()||!inventory.path("files").isArray()||inventory.path("files").isEmpty())fail("CONTROLLED_VIEW_INPUT_INVENTORY_REQUIRED");
  String d=uuid(inventory.get("datasetId")),v=uuid(inventory.path("version").get("version_id")),c=uuid(inventory.path("version").get("raw_commit_id"));
  JsonNode src=rules.path("source");
  if(!src.isObject()||src.size()!=4||!d.equals(src.path("datasetId").asText())||!v.equals(src.path("versionId").asText())||!c.equals(src.path("rawCommitId").asText()))fail("CONTROLLED_VIEW_RULE_SOURCE_MISMATCH");
  byte[] inventoryBytes=canonical(inventory);String inventorySha=sha(inventoryBytes);
  if(!inventorySha.equals(src.path("inputInventorySha256").asText()))fail("CONTROLLED_VIEW_RULE_INVENTORY_MISMATCH");
  ObjectNode capsule=json.createObjectNode();capsule.put("schema","COLLABORATION_JOB_CAPSULE_V1");capsule.put("sourceRepoId",sourceRepo.toString());
  capsule.put("inventory",Base64.getEncoder().encodeToString(inventoryBytes));capsule.set("rules",rules.deepCopy());
  var moduleNode=capsule.putObject("modules");var executedHashes=new TreeMap<String,String>();
  var executed=new ArrayList<>(EXECUTED);if(responsibility)executed.addAll(List.of("enterprise_view.py","enterprise_collaboration.py"));
  for(String name:executed){
   var node=moduleNode.putObject(name);node.put("data",Base64.getEncoder().encodeToString(modules.get(name)));node.put("sha256",hashes.get(name));executedHashes.put(name,hashes.get(name));
  }
  String payload=Base64.getEncoder().encodeToString(canonical(capsule));
  String script="""
set -euo pipefail
python3 - <<'MDS_CONTROLLED_VIEW_CAPSULE_V1'
import base64,hashlib,json,sys
from pathlib import Path
capsule=json.loads(base64.b64decode('"""+payload+"""
'))
worker=Path('/tmp/collaboration-code');worker.mkdir(mode=0o700)
for name,item in capsule['modules'].items():
 raw=base64.b64decode(item['data'],validate=True)
 if hashlib.sha256(raw).hexdigest()!=item['sha256']:raise ValueError('FROZEN_MODULE_BYTE_IDENTITY_CHANGED')
 target=worker/name;target.write_bytes(raw);target.chmod(0o400)
sys.path.insert(0,str(worker))
import collaboration_job
result=collaboration_job.execute(capsule)
print(json.dumps({'state':result['state'],'fileCount':result['fileCount'],'authorizationGranted':False,'published':False}))
MDS_CONTROLLED_VIEW_CAPSULE_V1
""";
  return new Prepared(script,sha(script.getBytes(StandardCharsets.UTF_8)),sha(canonical(rules)),inventorySha,executedHashes);
 }

 /** Fixed preview callback; no caller-supplied program, URL, module or credential. */
 public Prepared preparePreview(UUID sourceRepo,JsonNode inventory,JsonNode rules,UUID attempt){
  if(attempt==null)fail("CONTROLLED_VIEW_PREVIEW_ATTEMPT_REQUIRED");
  Prepared base=prepare(sourceRepo,inventory,rules);
  String prelude="""
set -euo pipefail
python3 - <<'MDS_CONTROLLED_VIEW_IMAGE_V1'
import os
expected=os.environ.get('MDS_REQUIRED_IMAGE_SHA256','')
actual=os.environ.get('MDS_VERIFIED_IMAGE_SHA256','')
if len(expected)!=64 or expected!=actual:raise ValueError('CONTROLLED_VIEW_REGISTERED_IMAGE_NOT_VERIFIED')
MDS_CONTROLLED_VIEW_IMAGE_V1
""";
  String callback="""
python3 - <<'MDS_CONTROLLED_VIEW_PRIVATE_RECEIPT_V1'
import base64,hashlib,json,os,shutil,time,urllib.request,urllib.error
from pathlib import Path
try:
 candidate=Path('/tmp/collaboration-candidate/private-provenance.json').read_bytes()
 execution=Path('/tmp/collaboration-work/private-execution-receipt.json').read_bytes()
 state=Path('/tmp/collaboration-candidate/candidate-state.json').read_bytes()
 payload={'schema':'CONTROLLED_VIEW_PREVIEW_RECEIPT_V1','attemptId':'CONTROLLED_VIEW_ATTEMPT_UUID',
  'candidateReceiptBase64':base64.b64encode(candidate).decode('ascii'),
  'executionReceiptBase64':base64.b64encode(execution).decode('ascii'),
  'candidateStateBase64':base64.b64encode(state).decode('ascii'),
  'verifiedImageSha256':os.environ['MDS_VERIFIED_IMAGE_SHA256']}
 raw=json.dumps(payload,sort_keys=True,separators=(',',':')).encode()
 target=os.environ['BACKEND_BASE_URL'].rstrip('/')+'/api/controlled-view-previews/CONTROLLED_VIEW_ATTEMPT_UUID/receipt'
 token=os.environ['BACKEND_BEARER_TOKEN']
 for trial in range(3):
  try:
   req=urllib.request.Request(target,data=raw,method='PUT',headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
   with urllib.request.urlopen(req,timeout=60) as response:
    if response.status!=204:raise ValueError('CONTROLLED_VIEW_PRIVATE_RECEIPT_NOT_ACCEPTED')
   break
  except urllib.error.HTTPError as error:
   if error.code<500 or trial==2:raise
   time.sleep(1)
  except (OSError,urllib.error.URLError):
   if trial==2:raise
   time.sleep(1)
 print(json.dumps({'privateReceiptStored':True,'published':False,'authorizationGranted':False}))
except BaseException:
 out=Path('/tmp/out')
 if out.is_dir() and not out.is_symlink():shutil.rmtree(str(out))
 raise
MDS_CONTROLLED_VIEW_PRIVATE_RECEIPT_V1
""".replace("CONTROLLED_VIEW_ATTEMPT_UUID",attempt.toString());
  String script=prelude+base.script()+callback;
  return new Prepared(script,sha(script.getBytes(StandardCharsets.UTF_8)),base.ruleSha256(),base.inventorySha256(),base.moduleSha256());
 }
}
