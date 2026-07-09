package com.aigroup.groupbuy.infrastructure.dcc;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory dynamic config store (replaces wrench DCC, Boot 3 compatible).
 */
@Component
public class DynamicConfigHolder {

    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();

    public void putAll(Map<String, String> seed) {
        if (seed != null) {
            values.putAll(seed);
        }
    }

    public void put(String key, String value) {
        if (key != null && value != null) {
            values.put(key, value);
        }
    }

    public String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }
}
