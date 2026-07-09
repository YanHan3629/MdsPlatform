package com.fwdrobo.sirius.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class JsonUtils {

    static ObjectMapper mapper = new ObjectMapper();

    public static JsonNode convertUserToJson(String userId, String userName) {
        ObjectNode obj = mapper.createObjectNode();
        obj.put("id", userId);
        obj.put("name", userName);
        return obj;
    }
}
