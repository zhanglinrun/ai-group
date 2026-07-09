package com.aigroup.member.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "ai-group.member.ability-cost")
public class QuotaAbilityProperties {

    private Map<String, Integer> multipliers = defaultMultipliers();

    private static Map<String, Integer> defaultMultipliers() {
        Map<String, Integer> defaults = new HashMap<>();
        defaults.put("react", 1);
        defaults.put("workflow", 1);
        defaults.put("plan_solve", 3);
        defaults.put("image_generation", 5);
        defaults.put("deep_search", 4);
        return defaults;
    }

    public int resolveCost(String abilityCode, int multiplier) {
        int base = multipliers.getOrDefault(normalize(abilityCode), 1);
        return Math.max(1, base * Math.max(1, multiplier));
    }

    private String normalize(String abilityCode) {
        return abilityCode == null ? "react" : abilityCode.trim().toLowerCase();
    }
}
