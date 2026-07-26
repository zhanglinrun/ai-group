package com.aigroup.paymall.types.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Drop-in replacement for {@code com.alibaba.fastjson.JSONObject} backed by Jackson.
 * Extends {@link LinkedHashMap} so existing Map-style code keeps working, and provides
 * the fastjson convenience getters ({@link #getJSONObject(String)}, {@link #getString(String)},
 * {@link #getInteger(String)}, {@link #getInt(String)}, {@link #put(String, Object)}).
 */
public class JSONObject extends LinkedHashMap<String, Object> {

    private static final long serialVersionUID = 1L;

    public JSONObject() {
        super();
    }

    public JSONObject(Map<String, Object> map) {
        super(map);
    }

    public static JSONObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtils.mapper().readValue(text, new TypeReference<Map<String, Object>>() {
            });
            JSONObject obj = new JSONObject();
            if (map != null) {
                obj.putAll(map);
            }
            return obj;
        } catch (Exception e) {
            throw new IllegalStateException("parse json to JSONObject failed: " + text, e);
        }
    }

    public static String toJSONString(Object obj) {
        return JsonUtils.toJson(obj);
    }

    public JSONObject getJSONObject(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value instanceof Map) {
            JSONObject child = new JSONObject();
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            child.putAll(mapValue);
            return child;
        }
        if (value instanceof JsonNode node && node.isObject()) {
            return JsonUtils.mapper().convertValue(node, JSONObject.class);
        }
        return null;
    }

    public String getString(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return value.toString();
    }

    public Integer getInteger(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.valueOf(s);
        }
        return null;
    }

    public int getIntValue(String key) {
        Integer val = getInteger(key);
        return val == null ? 0 : val;
    }

    public Long getLong(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.valueOf(s);
        }
        return null;
    }

    public Boolean getBoolean(String key) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Boolean.valueOf(s);
        }
        return null;
    }

    public JSONObject put(String key, Object value) {
        super.put(key, value);
        return this;
    }

    public JSONObject fluentPut(String key, Object value) {
        super.put(key, value);
        return this;
    }

    public String toJSONString() {
        return JsonUtils.toJson(this);
    }

    @Override
    public String toString() {
        return JsonUtils.toJson(this);
    }
}
