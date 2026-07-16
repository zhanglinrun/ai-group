package org.wwz.ai.domain.agent.memory;

import org.apache.commons.lang3.StringUtils;

/**
 * 可进入长期记忆的最小结构化语义类型。
 */
public enum LongTermMemoryType {
    /** 用户稳定偏好，例如语言、格式和沟通习惯。 */
    PREFERENCE,
    /** 可复用事实，例如项目约束或已确认的业务信息。 */
    FACT,
    /** 可复用流程、SOP 或操作方法。 */
    PROCEDURE;

    public static LongTermMemoryType from(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).trim();
        if (StringUtils.isBlank(normalized)) {
            return FACT;
        }
        try {
            return valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ignore) {
            return FACT;
        }
    }
}
