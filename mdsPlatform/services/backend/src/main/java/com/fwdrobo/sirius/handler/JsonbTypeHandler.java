package com.fwdrobo.sirius.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.*;

public class JsonbTypeHandler extends BaseTypeHandler<JsonNode> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, JsonNode parameter, JdbcType jdbcType)
            throws SQLException {

        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(parameter.toString());
        ps.setObject(i, pg); // 或 ps.setObject(i, pg, Types.OTHER);
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseToJsonNode(rs.getObject(columnName));
    }

    @Override
    public JsonNode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseToJsonNode(rs.getObject(columnIndex));
    }

    @Override
    public JsonNode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseToJsonNode(cs.getObject(columnIndex));
    }

    private static JsonNode parseToJsonNode(Object obj) throws SQLException {
        if (obj == null) return null;
        try {
            return MAPPER.readTree(obj.toString());
        } catch (Exception e) {
            throw new SQLException("Failed to parse jsonb: " + obj, e);
        }
    }

    /** 可选：给业务层用，把 List/Map/POJO/String(JSON) 转成 JsonNode */
    public static JsonNode toJsonNode(Object any) {
        if (any == null) return null;
        if (any instanceof JsonNode n) return n;
        // 如果是 String：这里按“JSON 字符串”解析；不是 JSON 会抛异常（更安全）
        // 如果你希望普通字符串也能存进去：改成 MAPPER.valueToTree(any)
        if (any instanceof String s) {
            try {
                return MAPPER.readTree(s);
            } catch (Exception e) {
                throw new IllegalArgumentException("cmd/env/params must be valid JSON string, got: " + s, e);
            }
        }
        return MAPPER.valueToTree(any); // List/Map/POJO -> JsonNode
    }
}