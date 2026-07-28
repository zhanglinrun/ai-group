package com.linrun.agent.types.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;

/**
 * Shared Jackson JSON helpers for runtime and integration boundaries.
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JsonNode parseTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("parse json tree failed", e);
        }
    }

    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("serialize to json failed: " + obj.getClass(), e);
        }
    }

    public static String toPrettyJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("serialize to pretty json failed: " + obj.getClass(), e);
        }
    }

    public static <T> T parseObject(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("parse json to " + clazz.getName() + " failed: " + json, e);
        }
    }

    public static <T> T parseObject(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            throw new IllegalStateException("parse json via type reference failed: " + json, e);
        }
    }

    public static <T> T parseObject(byte[] json, Class<T> clazz) {
        if (json == null || json.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalStateException("parse json bytes to " + clazz.getName() + " failed", e);
        }
    }

    public static <T> T convertValue(Object fromValue, Class<T> toValueType) {
        if (fromValue == null) {
            return null;
        }
        return MAPPER.convertValue(fromValue, toValueType);
    }

    public static <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        if (fromValue == null) {
            return null;
        }
        return MAPPER.convertValue(fromValue, toValueTypeRef);
    }

    public static <T> java.util.List<T> parseArray(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return java.util.Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, clazz));
        } catch (Exception e) {
            throw new IllegalStateException("parse json array to List<" + clazz.getName() + "> failed: " + json, e);
        }
    }
}
