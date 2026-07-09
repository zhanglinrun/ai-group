package com.aigroup.groupbuy.infrastructure.dcc;

import com.aigroup.groupbuy.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DCCService {

    private final DynamicConfigHolder dynamicConfigHolder;

    public boolean isDowngradeSwitch() {
        return "1".equals(dynamicConfigHolder.get("downgradeSwitch", "0"));
    }

    public boolean isCutRange(String userId) {
        int hashCode = Math.abs(userId.hashCode());
        int lastTwoDigits = hashCode % 100;
        int cutRange = Integer.parseInt(dynamicConfigHolder.get("cutRange", "100"));
        return lastTwoDigits <= cutRange;
    }

    public boolean isSCBlackIntercept(String source, String channel) {
        String scBlacklist = dynamicConfigHolder.get("scBlacklist", "s02c02");
        List<String> list = Arrays.asList(scBlacklist.split(Constants.SPLIT));
        return list.contains(source + channel);
    }

    public boolean isCacheOpenSwitch() {
        return "0".equals(dynamicConfigHolder.get("cacheSwitch", "0"));
    }
}
