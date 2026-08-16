package com.aigroup.groupbuy.config;

import com.aigroup.groupbuy.infrastructure.rate.limiter.RateLimiterAOP;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 限流 AOP 自动配置：将 {@link RateLimiterAOP} 注册为 Spring Bean，
 * Spring Boot 启动后所有标注了 {@code @RateLimiterAccessInterceptor} 的方法会被自动代理。
 */
@Configuration
public class RateLimiterAutoConfig {

    @Bean
    public RateLimiterAOP rateLimiterAOP(IRedisService redisService) {
        return new RateLimiterAOP(redisService);
    }
}
