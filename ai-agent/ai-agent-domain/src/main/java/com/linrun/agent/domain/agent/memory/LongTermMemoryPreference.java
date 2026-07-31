package com.linrun.agent.domain.agent.memory;

import org.apache.commons.lang3.StringUtils;

import java.time.Instant;

/** Owner-scoped consent and retention setting for cross-session memory. */
public record LongTermMemoryPreference(String ownerId,
                                       boolean enabled,
                                       int retentionDays,
                                       Instant updatedAt) {

    public static final int DEFAULT_RETENTION_DAYS = 180;
    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 365;

    public static LongTermMemoryPreference disabled(String ownerId) {
        return new LongTermMemoryPreference(ownerId, false, DEFAULT_RETENTION_DAYS, null);
    }

    public LongTermMemoryPreference normalized() {
        if (StringUtils.isBlank(ownerId)) {
            throw new IllegalArgumentException("ownerId must not be blank for memory preference");
        }
        return new LongTermMemoryPreference(ownerId, enabled,
                Math.max(MIN_RETENTION_DAYS, Math.min(MAX_RETENTION_DAYS, retentionDays)), updatedAt);
    }
}
