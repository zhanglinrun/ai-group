package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

public record ResearchEvidencePacket(String id,
                                     String title,
                                     String url,
                                     String snippet) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", title);
        map.put("url", url);
        map.put("snippet", snippet);
        return map;
    }

    public static ResearchEvidencePacket from(Object value) {
        if (value instanceof ResearchEvidencePacket packet) {
            return packet;
        }
        if (value instanceof Map<?, ?> map) {
            return new ResearchEvidencePacket(
                    string(map.get("id")),
                    string(map.get("title")),
                    string(map.get("url")),
                    string(map.get("snippet"))
            );
        }
        String text = string(value);
        return new ResearchEvidencePacket(text, text, "", "");
    }

    public boolean hasSource() {
        return StringUtils.isNotBlank(id) || StringUtils.isNotBlank(title) || StringUtils.isNotBlank(url);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
