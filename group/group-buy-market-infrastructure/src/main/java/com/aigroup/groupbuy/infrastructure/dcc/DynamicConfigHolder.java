package com.aigroup.groupbuy.infrastructure.dcc;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory dynamic config store (replaces wrench DCC, Boot 3 compatible).
 * <p>
 * Supports change listeners so that {@code @DCCValue}-annotated fields can be
 * updated at runtime when a config value changes via Redis topic broadcast.
 */
@Component
public class DynamicConfigHolder {

    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    public void putAll(Map<String, String> seed) {
        if (seed != null) {
            seed.forEach(this::put);
        }
    }

    public void put(String key, String value) {
        if (key != null && value != null) {
            values.put(key, value);
            notifyListeners(key, value);
        }
    }

    public String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(String key, String value) {
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChange(key, value);
            } catch (Exception e) {
                // listener failure must not block config update
            }
        }
    }

    @FunctionalInterface
    public interface ConfigChangeListener {
        void onConfigChange(String key, String value);
    }
}
