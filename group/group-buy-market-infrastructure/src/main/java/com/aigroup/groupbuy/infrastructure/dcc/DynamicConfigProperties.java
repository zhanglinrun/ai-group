package com.aigroup.groupbuy.infrastructure.dcc;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai-group.gbm.dcc")
public class DynamicConfigProperties {

    private Map<String, String> defaults = defaultMap();

    private static Map<String, String> defaultMap() {
        Map<String, String> map = new HashMap<>();
        map.put("downgradeSwitch", "0");
        map.put("cutRange", "100");
        map.put("scBlacklist", "s02c02");
        map.put("cacheSwitch", "0");
        return map;
    }
}
