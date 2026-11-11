package com.fwdrobo.sirius.security;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Fixed current-JAR native code; the registered SIF supplies immutable libraries and models. */
@Service
public class NativeIndexWorkerScriptFactory {
 private static final String ROOT="workers/native-index/";
 private final JsonNode manifest;private final String manifestSha,capsuleSha,script;
 public NativeIndexWorkerScriptFactory(ObjectMapper json,@Value("${mm.index-builder.model-path:/app/clip_model_chinese}") String modelPath){
  try{
   if(modelPath==null||!modelPath.startsWith("/app/")||!modelPath.matches("[A-Za-z0-9_/.-]{1,512}")||Arrays.asList(modelPath.split("/",-1)).contains(".."))
    throw new IllegalArgumentException("Native model path must be inside the registered image");
   byte[] raw=resource("source-manifest.json",1048576);manifest=json.readTree(raw);
   if(!"NATIVE_INDEX_SOURCE_CAPSULE_V1".equals(manifest.path("schema").asText())||!manifest.path("files").isObject()||
    manifest.path("files").size()<3||manifest.path("files").size()>256||(!manifest.path("files").has("build_index.py")||!manifest.path("files").has("index_callback.py")))
    throw new IllegalStateException("Native source manifest invalid");
   manifestSha=sha(ControlledViewWorkerScriptFactory.canonical(manifest));
   var out=new ByteArrayOutputStream();long total=0;
   try(var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)){
    var names=new TreeSet<String>();manifest.path("files").fieldNames().forEachRemaining(names::add);
    for(String name:names){
     if(!name.matches("[A-Za-z0-9_./-]+\\.py")||name.startsWith("/")||Arrays.asList(name.split("/",-1)).stream().anyMatch(x->x.isEmpty()||x.equals(".")||x.equals("..")))
      throw new IllegalStateException("Native source path invalid");
     byte[] code=resource(name,2097152);total+=code.length;if(total>8388608||!sha(code).equals(manifest.path("files").path(name).asText()))
      throw new IllegalStateException("Native packaged code identity changed");
     var entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(code);zip.closeEntry();
    }
   }
   byte[] capsule=out.toByteArray();capsuleSha=sha(capsule);
   String encoded=Base64.getEncoder().encodeToString(capsule),declared=Base64.getEncoder().encodeToString(ControlledViewWorkerScriptFactory.canonical(manifest));
   String model=Base64.getEncoder().encodeToString(modelPath.getBytes(StandardCharsets.UTF_8));
   script="""
set -eu
python - <<'MDS_APPROVED_NATIVE_INDEX_V1'
import base64,hashlib,io,json,os,sys,zipfile
from pathlib import Path
capsule=base64.b64decode('__CAPSULE__',validate=True)
manifest_bytes=base64.b64decode('__MANIFEST__',validate=True)
assert hashlib.sha256(capsule).hexdigest()=='__CAPSULE_SHA__','NATIVE_CODE_CAPSULE_CHANGED'
assert hashlib.sha256(manifest_bytes).hexdigest()=='__MANIFEST_SHA__','NATIVE_CODE_MANIFEST_CHANGED'
manifest=json.loads(manifest_bytes)
root=Path('/tmp/approved-native-index')
root.mkdir(mode=0o700,exist_ok=False)
with zipfile.ZipFile(io.BytesIO(capsule)) as source:
 assert sorted(source.namelist())==sorted(manifest['files']),'NATIVE_CODE_FILE_SET_CHANGED'
 for name,expected in sorted(manifest['files'].items()):
  assert not name.startswith('/') and all(x not in ('','.','..') for x in name.split('/')),'NATIVE_CODE_PATH_INVALID'
  code=source.read(name)
  assert hashlib.sha256(code).hexdigest()==expected,'NATIVE_CODE_MODULE_CHANGED'
  path=root/name
  path.parent.mkdir(parents=True,exist_ok=True)
  path.write_bytes(code)
proof={'schema':'NATIVE_INDEX_CODE_EXECUTION_V1','capsuleSha256':'__CAPSULE_SHA__','moduleManifestSha256':'__MANIFEST_SHA__','moduleSha256':manifest['files']}
Path('/tmp/controlled-native-index-execution.json').write_text(json.dumps(proof,sort_keys=True,separators=(',',':'))+'\\n')
env=dict(os.environ,MODEL_PATH=base64.b64decode('__MODEL__',validate=True).decode(),PYTHONPATH=str(root),HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1')
env.pop('PYTHONSTARTUP',None)
os.execve(sys.executable,[sys.executable,str(root/'build_index.py')],env)
MDS_APPROVED_NATIVE_INDEX_V1
""".replace("__CAPSULE__",encoded).replace("__MANIFEST__",declared).replace("__CAPSULE_SHA__",capsuleSha).replace("__MANIFEST_SHA__",manifestSha).replace("__MODEL__",model);
  }catch(Exception e){throw new IllegalStateException("PACKAGED_NATIVE_INDEX_SOURCE_INVALID",e);}
 }
 private static byte[] resource(String path,int maximum)throws Exception{
  try(var input=NativeIndexWorkerScriptFactory.class.getClassLoader().getResourceAsStream(ROOT+path)){
   if(input==null)throw new FileNotFoundException("Missing packaged native source");
   byte[] data=input.readNBytes(maximum+1);if(data.length<1||data.length>maximum)throw new IOException("Packaged native source size invalid");return data;
  }
 }
 private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
 public String script(){return script;}
 public String scriptSha256(){return JobExecutionDefinitionService.sha(script);}
 public String moduleManifestSha256(){return manifestSha;}
 public JsonNode moduleSha256(){return manifest.path("files").deepCopy();}
 public String capsuleSha256(){return capsuleSha;}
 public String callbackSha256(){return manifest.path("files").path("index_callback.py").asText();}
}
