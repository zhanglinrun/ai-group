package com.aigroup.paymall.config;

import com.aigroup.paymall.trigger.listener.OrderPaySuccessListener;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.eventbus.EventBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class GuavaConfig {

    @Bean(name = "weixinAccessToken")
    @ConditionalOnProperty(name = "weixin.enabled", havingValue = "true")
    public Cache<String, String> weixinAccessToken() {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(2, TimeUnit.HOURS)
                .build();
    }

    @Bean(name = "openidToken")
    @ConditionalOnProperty(name = "weixin.enabled", havingValue = "true")
    public Cache<String, String> openidToken() {
        return CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build();
    }

    @Bean
    public EventBus eventBusListener(OrderPaySuccessListener listener){
        EventBus eventBus = new EventBus();
        eventBus.register(listener);
        return eventBus;
    }

}
