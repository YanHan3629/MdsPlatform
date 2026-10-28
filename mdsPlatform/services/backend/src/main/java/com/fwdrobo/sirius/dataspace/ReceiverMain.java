package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/** Independent local receiver process. Its signing key and volume are not mounted by the sender. */
public final class ReceiverMain {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final long MAX_BYTES=1024L*1024*1024;
    private static final Path ROOT=Path.of(System.getenv().getOrDefault("DS_RECEIVER_ROOT","/receiver-data")).toAbsolutePath().normalize();
    private static final byte[] TOKEN=System.getenv().getOrDefault("DS_RECEIVER_TOKEN","").getBytes(StandardCharsets.UTF_8);
    private static PrivateKey privateKey;private static PublicKey publicKey;private static String identity;
    private static final Map<String,Object> locks=new java.util.concurrent.ConcurrentHashMap<>();
    private static Map<String,Object> map(Object... values){var result=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put(values[i].toString(),values[i+1]);return result;}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static void main(String[] args)throws Exception{
        if(TOKEN.length<24)throw new IllegalStateException("receiver service credential must be configured");
        Files.createDirectories(ROOT.resolve("endpoints"));Files.createDirectories(ROOT.resolve("deliveries"));Files.createDirectories(ROOT.resolve("incoming"));
        Path secret=ROOT.resolve("receipt-signing-key.pk8"),pub=ROOT.resolve("receipt-public-key.x509");
        if(!Files.exists(secret)){
            KeyPairGenerator gen=KeyPairGenerator.getInstance("RSA");gen.initialize(3072);KeyPair pair=gen.generateKeyPair();
            atomic(secret,pair.getPrivate().getEncoded());atomic(pub,pair.getPublic().getEncoded());
            try{Files.setPosixFilePermissions(secret,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
        }
        KeyFactory factory=KeyFactory.getInstance("RSA");privateKey=factory.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(secret)));
        // Recover a public key after a crash between writing the private/public files.
        if(!Files.exists(pub)){
            var rsa=(java.security.interfaces.RSAPrivateCrtKey)privateKey;
            atomic(pub,factory.generatePublic(new java.security.spec.RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent())).getEncoded());
        }
        publicKey=factory.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub)));identity="local-receiver:sha256:"+hash(publicKey.getEncoded());
        HttpServer server=HttpServer.create(new InetSocketAddress("0.0.0.0",18082),16);
        server.createContext("/",ReceiverMain::handle);server.setExecutor(Executors.newFixedThreadPool(4));server.start();
        System.out.println("local verified receiver ready "+identity);
    }
    private static void atomic(Path path,byte[] data)throws Exception{
        Path temp=Files.createTempFile(path.getParent(),".write-",".tmp");
        try{Files.write(temp,data);try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}}
        finally{Files.deleteIfExists(temp);}
    }
    private static void answer(HttpExchange x,int status,Object body)throws IOException{
        byte[] bytes=JSON.writeValueAsBytes(body);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}
    }
    private static Map<String,Object> read(Path path)throws Exception{return JSON.readValue(Files.readAllBytes(path),new TypeReference<>(){});}
    private static String uuid(Object value){return UUID.fromString(String.valueOf(value)).toString();}
    private static void handle(HttpExchange x)throws IOException{
        try{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(path.equals("/healthz")&&method.equals("GET")){answer(x,200,map("status","UP"));return;}
            if(!MessageDigest.isEqual(TOKEN,String.valueOf(x.getRequestHeaders().getFirst("X-DS-Receiver-Token")).getBytes(StandardCharsets.UTF_8))){answer(x,401,map("error","invalid receiver service credential"));return;}
            if(path.equals("/identity")&&method.equals("GET")){answer(x,200,map("receiverIdentity",identity,"publicKey",Base64.getEncoder().encodeToString(publicKey.getEncoded()),"algorithm","SHA256withRSA"));return;}
            String[] parts=path.split("/");
            if(parts.length==3&&parts[1].equals("endpoints")&&method.equals("POST")){
                String id=uuid(parts[2]);byte[] data=x.getRequestBody().readNBytes(65537);if(data.length>65536)throw new IllegalArgumentException("endpoint body too large");
                var request=JSON.readValue(data,new TypeReference<Map<String,Object>>(){});
                var endpoint=map("endpointId",id,"consumerOrgId",uuid(request.get("consumerOrgId")),"receiverIdentity",identity);
                Path target=ROOT.resolve("endpoints").resolve(id+".json");
                synchronized(locks.computeIfAbsent("endpoint:"+id,k->new Object())){
                    if(Files.exists(target)&&!read(target).equals(endpoint)){answer(x,409,map("error","endpoint binding mismatch"));return;}
                    if(!Files.exists(target))atomic(target,JSON.writeValueAsBytes(endpoint));
                }
                answer(x,200,endpoint);return;
            }
            if(parts.length>=3&&parts[1].equals("deliveries")){
                String key=uuid(parts[2]);Path directory=ROOT.resolve("deliveries").resolve(key);Path receipt=directory.resolve("receipt.json");
                if(parts.length==3&&method.equals("POST")){
                    synchronized(locks.computeIfAbsent("delivery:"+key,k->new Object())){receive(x,key,directory,receipt);}return;
                }
                if(method.equals("GET")&&Files.exists(receipt)){
                    if(parts.length==3){answer(x,200,read(receipt));return;}
                    if(parts.length==4&&parts[3].equals("package")){
                        Path zip=directory.resolve("package.zip");x.getResponseHeaders().set("Content-Type","application/zip");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(200,Files.size(zip));try(var out=x.getResponseBody()){Files.copy(zip,out);}return;
                    }
                }
            }
            answer(x,404,map("error","receiver object not found"));
        }catch(IllegalArgumentException e){answer(x,400,map("error","invalid receiver input", "detail",e.getMessage()==null?"invalid fields":e.getMessage()));}
        catch(Exception e){System.err.println("receiver request failed "+e.getClass().getSimpleName());answer(x,422,map("error","receiver could not independently verify complete package"));}
        finally{x.close();}
    }
    private static void receive(HttpExchange x,String key,Path directory,Path receipt)throws Exception{
        Path upload=Files.createTempFile(ROOT.resolve("incoming"),key+"-",".zip");
        try{
            try(var out=Files.newOutputStream(upload);var in=x.getRequestBody()){
                byte[] buffer=new byte[1024*1024];long total=0;for(int n;(n=in.read(buffer))!=-1;){total+=n;if(total>MAX_BYTES)throw new IllegalArgumentException("package exceeds local receiver 1GiB limit");out.write(buffer,0,n);}
            }
            Map<String,Object> manifest;long count=0,size=0;
            try(ZipFile zip=new ZipFile(upload.toFile(),StandardCharsets.UTF_8)){
                var entry=zip.getEntry("manifest.json");if(entry==null||entry.getSize()>8*1024*1024)throw new IllegalArgumentException("missing or oversized manifest");
                byte[] bytes;try(var in=zip.getInputStream(entry)){bytes=in.readNBytes(8*1024*1024+1);}if(bytes.length>8*1024*1024)throw new IllegalArgumentException("manifest too large");
                manifest=JSON.readValue(bytes,new TypeReference<>(){});
                if(!key.equals(uuid(manifest.get("deliveryKey"))))throw new IllegalArgumentException("delivery key mismatch");
                String endpoint=uuid(manifest.get("endpointId")),consumer=uuid(manifest.get("consumerOrgId"));
                Path config=ROOT.resolve("endpoints").resolve(endpoint+".json");if(!Files.exists(config)||!consumer.equals(read(config).get("consumerOrgId")))throw new IllegalArgumentException("consumer endpoint mismatch");
                for(String field:List.of("contractId","productVersionId","datasetId","versionId","indexVersionId","rawCommitId"))uuid(manifest.get(field));
                if(!(manifest.get("contractRevision") instanceof Number revision)||revision.intValue()<1)throw new IllegalArgumentException("invalid contract revision");
                if(!"FULL_VERSION".equals(manifest.get("scopeType")))throw new IllegalArgumentException("unsupported authorization scope");
                if(!(manifest.get("files") instanceof List<?> files)||files.isEmpty()||files.size()>20000)throw new IllegalArgumentException("invalid complete manifest");
                Map<String,Map<String,Object>> expected=new TreeMap<>();
                for(Object raw:files){
                    @SuppressWarnings("unchecked") var file=(Map<String,Object>)raw;String path=String.valueOf(file.get("logicalPath"));
                    if(!path.startsWith("/")||path.startsWith("//")||path.contains("\\")||path.contains("\0")||Arrays.stream(path.substring(1).split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))throw new IllegalArgumentException("unsafe logical path");
                    if(!(file.get("sizeBytes") instanceof Number n)||n.longValue()<0||n.longValue()>MAX_BYTES||!String.valueOf(file.get("sha256")).matches("[a-f0-9]{64}"))throw new IllegalArgumentException("invalid content identity");
                    if(expected.put("files"+path,file)!=null)throw new IllegalArgumentException("duplicate manifest path");
                }
                List<Map<String,Object>> identities=new ArrayList<>();for(var file:expected.values())identities.add(map("logicalPath",file.get("logicalPath"),"sizeBytes",file.get("sizeBytes"),"sha256",file.get("sha256")));identities.sort(Comparator.comparing(f->f.get("logicalPath").toString()));
                if(!hash(JSON.writeValueAsBytes(identities)).equals(manifest.get("manifestDigest")))throw new IllegalArgumentException("manifest digest mismatch");
                Set<String> seen=new HashSet<>();var entries=zip.entries();
                while(entries.hasMoreElements()){
                    var file=entries.nextElement();String name=file.getName();if(!seen.add(name))throw new IllegalArgumentException("duplicate archive path");
                    if(name.equals("manifest.json"))continue;var expect=expected.get(name);if(expect==null||file.isDirectory())throw new IllegalArgumentException("unlisted archive member");
                    long actual=0,declared=((Number)expect.get("sizeBytes")).longValue();var digest=MessageDigest.getInstance("SHA-256");
                    try(var in=zip.getInputStream(file)){byte[] buffer=new byte[1024*1024];for(int n;(n=in.read(buffer))!=-1;){actual+=n;if(actual>declared)throw new IllegalArgumentException("file size mismatch");digest.update(buffer,0,n);}}
                    if(actual!=declared||!HexFormat.of().formatHex(digest.digest()).equals(expect.get("sha256")))throw new IllegalArgumentException("file content mismatch");
                    size+=actual;if(size>MAX_BYTES)throw new IllegalArgumentException("expanded package exceeds receiver limit");count++;
                }
                if(count!=expected.size()||seen.size()!=expected.size()+1)throw new IllegalArgumentException("incomplete version");
            }
            String manifestHash=DataSpaceCanonical.digest(manifest);
            if(Files.exists(receipt)){
                var prior=read(receipt);var payload=JSON.readValue(Base64.getDecoder().decode(prior.get("payload").toString()),new TypeReference<Map<String,Object>>(){});
                if(!manifestHash.equals(payload.get("packageManifestDigest"))){answer(x,409,map("error","idempotency key already bound to different manifest"));return;}
                answer(x,200,prior);return;
            }
            var payload=map("deliveryKey",key,"endpointId",manifest.get("endpointId"),"consumerOrgId",manifest.get("consumerOrgId"),"contractId",manifest.get("contractId"),"contractRevision",manifest.get("contractRevision"),"productVersionId",manifest.get("productVersionId"),"manifestDigest",manifest.get("manifestDigest"),"packageManifestDigest",manifestHash,"fileCount",count,"sizeBytes",size,"receiverIdentity",identity,"receivedAt",OffsetDateTime.now(ZoneOffset.UTC).toString(),"verification","ALL_FILES_HASHED_AND_MATCHED");
            byte[] payloadBytes=JSON.writeValueAsBytes(payload);Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(privateKey);signature.update(payloadBytes);
            var envelope=map("algorithm","SHA256withRSA","payload",Base64.getEncoder().encodeToString(payloadBytes),"signature",Base64.getEncoder().encodeToString(signature.sign()));
            Files.createDirectories(directory);
            Files.move(upload,directory.resolve("package.zip"),StandardCopyOption.REPLACE_EXISTING);
            atomic(directory.resolve("manifest.json"),JSON.writeValueAsBytes(manifest));atomic(receipt,JSON.writeValueAsBytes(envelope));
            answer(x,200,envelope);
        }finally{Files.deleteIfExists(upload);}
    }
}
