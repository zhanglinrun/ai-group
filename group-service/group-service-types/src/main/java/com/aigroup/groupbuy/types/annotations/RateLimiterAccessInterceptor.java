package com.aigroup.groupbuy.types.annotations;

import java.lang.annotation.*;

/**
 * 限流注解，标记需要限流的方法。
 * <p>
 * 配合 {@code RateLimiterAOP} 切面实现基于 Redis 固定窗口的集群限流，
 * 支持按字段限流标识、黑名单拦截和超频次降级。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@Documented
public @interface RateLimiterAccessInterceptor {

    /**
     * 用哪个字段作为拦截标识，未配置则默认走全部
     */
    String key() default "all";

    /**
     * 限制频次（每秒请求次数）
     */
    double permitsPerSecond();

    /**
     * 黑名单拦截（超过多少次限制后加入黑名单），0 表示不限制
     */
    double blacklistCount() default 0;

    /**
     * 拦截后的执行回调方法名（同类同参数列表）
     */
    String fallbackMethod();
}
