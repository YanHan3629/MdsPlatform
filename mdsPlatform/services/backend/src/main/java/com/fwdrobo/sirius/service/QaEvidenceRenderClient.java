package com.fwdrobo.sirius.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.security.MessageDigest;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/** Internal CPU converter identity is separate from every caller's scope grant. */
@Service
public class QaEvidenceRenderClient {
    private final ObjectMapper json;
    private final String base,internalToken;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public QaEvidenceRenderClient(ObjectMapper json,@Value("${mm.evidence-worker.base-url:http://evidence-worker:18083}") String base,
            @Value("${mm.internal.token:}") String internalToken){this.json=json;this.base=base;this.internalToken=internalToken;}
    public record Rendered(byte[] bytes,JsonNode metadata){}
    private ResponseStatusException failure(HttpStatus status,String code){return new ResponseStatusException(status,code+": 区域证据暂不可用，请核对范围或稍后重试");}
    private boolean sameJson(JsonNode left,JsonNode right){
        if(left==null||right==null)return left==right;
        if(left.isNumber()&&right.isNumber())return left.decimalValue().compareTo(right.decimalValue())==0;
        if(left.isArray()&&right.isArray()){
            if(left.size()!=right.size())return false;for(int index=0;index<left.size();index++)if(!sameJson(left.get(index),right.get(index)))return false;return true;
        }
        if(left.isObject()&&right.isObject()){
            if(left.size()!=right.size())return false;var fields=left.fields();while(fields.hasNext()){var field=fields.next();if(!right.has(field.getKey())||!sameJson(field.getValue(),right.get(field.getKey())))return false;}return true;
        }
        return left.equals(right);
    }
    public Rendered render(JsonNode identity,JsonNode plan,byte[] source)throws Exception{
        if(internalToken.isBlank())throw failure(HttpStatus.SERVICE_UNAVAILABLE,"EVIDENCE_RENDER_NOT_CONFIGURED");
        var payload=json.createObjectNode().put("recipeVersion",plan.path("recipeVersion").asText());payload.set("identity",identity.deepCopy());payload.set("parameters",plan.path("parameters").deepCopy());payload.put("sourceBase64",Base64.getEncoder().encodeToString(source));
        var request=HttpRequest.newBuilder(URI.create(base+"/internal/v1/evidence/render")).timeout(Duration.ofSeconds(90))
            .header("Content-Type","application/json").header("X-MM-Internal-Token",internalToken)
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
        HttpResponse<java.io.InputStream> response;
        try{response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());}
        catch(java.net.http.HttpTimeoutException error){throw failure(HttpStatus.GATEWAY_TIMEOUT,"EVIDENCE_RENDER_TIMEOUT");}
        catch(java.io.IOException error){throw failure(HttpStatus.SERVICE_UNAVAILABLE,"EVIDENCE_RENDER_SERVICE_UNAVAILABLE");}
        byte[] body;try(var stream=response.body()){body=stream.readNBytes(24*1024*1024+1);}
        if(body.length>24*1024*1024)throw failure(HttpStatus.PAYLOAD_TOO_LARGE,"EVIDENCE_RENDER_RESPONSE_LIMIT");
        if(body.length==0)throw failure(HttpStatus.SERVICE_UNAVAILABLE,"EVIDENCE_RENDER_SERVICE_UNAVAILABLE");
        JsonNode result;try{result=json.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);}
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw failure(HttpStatus.SERVICE_UNAVAILABLE,"EVIDENCE_RENDER_SERVICE_UNAVAILABLE");}
        if(result==null||!result.isObject())throw failure(HttpStatus.SERVICE_UNAVAILABLE,"EVIDENCE_RENDER_SERVICE_UNAVAILABLE");
        if(response.statusCode()!=200){
            HttpStatus status=Set.of(400,404,409,413,422,504).contains(response.statusCode())?HttpStatus.valueOf(response.statusCode()):HttpStatus.SERVICE_UNAVAILABLE;
            String code=result.path("error").asText("EVIDENCE_RENDER_FAILED");if(!code.matches("[A-Z0-9_]{1,100}"))code="EVIDENCE_RENDER_FAILED";
            throw failure(status,code);
        }
        if(!"qa-region-image-1".equals(result.path("schemaVersion").asText())||!sameJson(identity,result.path("identity"))||!sameJson(plan.path("parameters"),result.path("parameters"))||!plan.path("recipeVersion").equals(result.path("recipeVersion")))
            throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_IDENTITY_MISMATCH");
        if(plan.has("expectedEngineBinding")&&!sameJson(plan.path("expectedEngineBinding"),result.path("engine")))throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_ENGINE_MISMATCH");
        if(!result.path("representationId").asText().matches("[a-f0-9-]{36}")||!result.path("outputSha256").asText().matches("[a-f0-9]{64}"))throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_IDENTITY_MISMATCH");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(result.path("imageBase64").asText());}catch(IllegalArgumentException error){throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_HASH_MISMATCH");}
        if(bytes.length<24||!Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_IMAGE_INVALID");
        var dimensions=java.nio.ByteBuffer.wrap(bytes,16,8);int width=dimensions.getInt(),height=dimensions.getInt();
        if(width<=0||height<=0||(long)width*height>plan.path("parameters").path("maxPixels").asInt())throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_IMAGE_INVALID");
        if(bytes.length>16*1024*1024||bytes.length!=result.path("outputBytes").asInt()||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(result.path("outputSha256").asText()))throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_HASH_MISMATCH");
        var image=ImageIO.read(new ByteArrayInputStream(bytes));
        if(image==null||image.getWidth()!=result.path("width").asInt()||image.getHeight()!=result.path("height").asInt()||(long)image.getWidth()*image.getHeight()>plan.path("parameters").path("maxPixels").asInt())throw failure(HttpStatus.CONFLICT,"EVIDENCE_RENDER_IMAGE_INVALID");
        ((com.fasterxml.jackson.databind.node.ObjectNode)result).remove("imageBase64");return new Rendered(bytes,result);
    }
}
