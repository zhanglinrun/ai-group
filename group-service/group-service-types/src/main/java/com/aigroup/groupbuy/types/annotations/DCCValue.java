package com.aigroup.groupbuy.types.annotations;

import java.lang.annotation.*;

/**
 * 动态配置中心标记注解。
 * <p>
 * 用在字段上，格式 {@code "配置键名:默认值"}。启动时从 {@link com.aigroup.groupbuy.infrastructure.dcc.DynamicConfigHolder}
 * 读取配置值并反射注入；运行时配置变更通过 Redis topic 广播后同步更新字段值，无需重启。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Documented
public @interface DCCValue {

    /**
     * 配置信息，格式 "配置键名:默认值"，例如 "rateLimiterSwitch:open"
     */
    String value() default "";
}
