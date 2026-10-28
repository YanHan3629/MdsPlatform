package com.fwdrobo.sirius.dataspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.*;

/** Receipt manifest hash: recursive sorted object keys, array order preserved, UTF-8 JSON. */
public final class DataSpaceCanonical {
    private static final ObjectMapper JSON=new ObjectMapper();
    private DataSpaceCanonical(){}
    private static Object sorted(Object value){
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),sorted(v)));return result;}
        if(value instanceof List<?> list)return list.stream().map(DataSpaceCanonical::sorted).toList();return value;
    }
    public static String digest(Object value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(sorted(value))));}
}
