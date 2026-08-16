package com.aigroup.paymall.config;

import com.aigroup.common.constant.CommonConstant;
import com.aigroup.paymall.infrastructure.gateway.IWeixinApiService;
import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
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
     * Feign 全局请求拦截器：为内部服务调用注入内部令牌，并在用户请求线程里转发 JWT。
     * 结算/退款等无会话回调没有 JWT，Group 对那两条路径只认内部令牌。
     */
    @Bean
    public RequestInterceptor internalTokenRequestInterceptor() {
        return template -> {
            if (StringUtils.isNotBlank(internalToken)) {
                template.header(HEADER_INTERNAL_TOKEN, internalToken);
            }
            HttpServletRequest request = currentRequest();
            if (request == null) {
                return;
            }
            String jwt = request.getHeader(CommonConstant.HEADER_INTERNAL_JWT);
            if (StringUtils.isNotBlank(jwt)) {
                template.header(CommonConstant.HEADER_INTERNAL_JWT, jwt);
            }
            String gatewayRequest = request.getHeader(CommonConstant.HEADER_GATEWAY_REQUEST);
            if (StringUtils.isNotBlank(gatewayRequest)) {
                template.header(CommonConstant.HEADER_GATEWAY_REQUEST, gatewayRequest);
            }
        };
    }

    private static HttpServletRequest currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        return attributes.getRequest();
    }
}
