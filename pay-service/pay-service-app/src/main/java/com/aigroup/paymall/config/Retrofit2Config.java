package com.aigroup.paymall.config;

import com.aigroup.paymall.infrastructure.gateway.IWeixinApiService;
import feign.RequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

@Slf4j
@Configuration
public class Retrofit2Config {

    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    public static final String DEFAULT_MEMBER_SERVICE_URL = "http://127.0.0.1:18082";

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    /**
     * 微信公众号外部 API 仍走 Retrofit2（非内部服务调用，不纳入 Nacos/Feign 体系）。
     */
    @Bean
    @ConditionalOnProperty(name = "weixin.enabled", havingValue = "true")
    public IWeixinApiService weixinApiService() {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.weixin.qq.com/")
                .addConverterFactory(JacksonConverterFactory.create()).build();

        return retrofit.create(IWeixinApiService.class);
    }

    /**
     * Feign 全局请求拦截器：为内部服务调用（group / member-service）注入 X-Internal-Token 网关身份头，
     * 替代原 Retrofit2 OkHttp Interceptor。
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> {
            if (StringUtils.isNotBlank(internalToken)) {
                template.header(HEADER_INTERNAL_TOKEN, internalToken);
            }
        };
    }
}
