package com.fwdrobo.sirius.dataspace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import static com.fwdrobo.sirius.dataspace.PersistentDataSpaceService.map;

/** Only a deployment-configured receiver is addressable; API input never supplies a URL. */
@Component
public class DataSpaceReceiverClient {
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String base,token;private final ObjectMapper json;
    public DataSpaceReceiverClient(ObjectMapper json,@Value("${ds.receiver.base-url:http://local-receiver:18082}")String base,@Value("${mm.internal.token:}")String token){this.json=json;this.base=base.replaceAll("/+$","");this.token=token;}
    private HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create(base+path)).header("X-DS-Receiver-Token",token).timeout(Duration.ofSeconds(45));}
    private Map<String,Object> parse(String text)throws Exception{return json.readValue(text,new TypeReference<>(){});}
    public String digest(Object value)throws Exception{return DataSpaceCanonical.digest(value);}
    public Map<String,Object> identity(){
        try{
            var response=http.send(request("/identity").GET().build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new IllegalStateException();
            var identity=parse(response.body());byte[] pub=Base64.getDecoder().decode(identity.get("publicKey").toString());
            String id="local-receiver:sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pub));
            if(!id.equals(identity.get("receiverIdentity"))||!"SHA256withRSA".equals(identity.get("algorithm")))throw new IllegalStateException();return identity;
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"受控本地接收端尚未就绪或身份无法验证，请检查local-receiver配置");}
    }
    public void register(UUID endpoint,UUID org,String expectedIdentity){
        try{
            var response=http.send(request("/endpoints/"+endpoint).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(map("consumerOrgId",org)))).build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException();var binding=parse(response.body());
            if(!org.toString().equals(binding.get("consumerOrgId"))||!endpoint.toString().equals(binding.get("endpointId"))||!expectedIdentity.equals(binding.get("receiverIdentity")))throw new IllegalStateException();
        }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"本地接收端绑定失败，未建立可发送配置");}
    }
    public Map<String,Object> receipt(UUID key)throws Exception{
        var response=http.send(request("/deliveries/"+key).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()==404)return null;if(response.statusCode()!=200)throw new IllegalStateException("receiver receipt lookup failed: HTTP "+response.statusCode());return parse(response.body());
    }
    public Map<String,Object> send(UUID key,Path zip)throws Exception{
        var response=http.send(request("/deliveries/"+key).timeout(Duration.ofMinutes(15)).header("Content-Type","application/zip").POST(HttpRequest.BodyPublishers.ofFile(zip)).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)throw new IllegalStateException("independent receiver rejected package: HTTP "+response.statusCode());return parse(response.body());
    }
    public Map<String,Object> verify(Map<String,Object> envelope,Map<String,Object> manifest,String publicKey,String identity)throws Exception{
        if(!"SHA256withRSA".equals(envelope.get("algorithm")))throw new GeneralSecurityException("receipt algorithm mismatch");
        byte[] payload=Base64.getDecoder().decode(envelope.get("payload").toString()),signature=Base64.getDecoder().decode(envelope.get("signature").toString());
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        String actual="local-receiver:sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));if(!identity.equals(actual))throw new GeneralSecurityException("receiver identity mismatch");
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(payload);if(!verifier.verify(signature))throw new GeneralSecurityException("receipt signature invalid");
        var result=json.readValue(payload,new TypeReference<Map<String,Object>>(){});
        for(String field:List.of("deliveryKey","endpointId","consumerOrgId","contractId","contractRevision","productVersionId","manifestDigest"))
            if(!String.valueOf(manifest.get(field)).equals(String.valueOf(result.get(field))))throw new GeneralSecurityException("receipt scope mismatch: "+field);
        if(!digest(manifest).equals(result.get("packageManifestDigest"))||!identity.equals(result.get("receiverIdentity"))||!"ALL_FILES_HASHED_AND_MATCHED".equals(result.get("verification")))throw new GeneralSecurityException("receipt verification mismatch");
        @SuppressWarnings("unchecked") var files=(List<Map<String,Object>>)manifest.get("files");long bytes=files.stream().mapToLong(f->((Number)f.get("sizeBytes")).longValue()).sum();
        if(!(result.get("fileCount") instanceof Number count)||count.longValue()!=files.size()||!(result.get("sizeBytes") instanceof Number size)||size.longValue()!=bytes)throw new GeneralSecurityException("receipt content totals mismatch");
        if(OffsetDateTime.parse(result.get("receivedAt").toString()).isAfter(OffsetDateTime.now().plusMinutes(5)))throw new GeneralSecurityException("receipt time is in the future");
        return result;
    }
    public record PackageStream(InputStream inputStream,long size){}
    public PackageStream openPackage(UUID key)throws Exception{
        var response=http.send(request("/deliveries/"+key+"/package").timeout(Duration.ofMinutes(15)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
        if(response.statusCode()!=200){response.body().close();throw new IllegalStateException("receiver package unavailable");}
        return new PackageStream(response.body(),response.headers().firstValueAsLong("Content-Length").orElse(-1));
    }
}
