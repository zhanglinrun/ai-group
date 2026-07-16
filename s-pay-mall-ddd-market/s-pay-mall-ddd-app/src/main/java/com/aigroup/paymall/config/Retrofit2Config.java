package com.aigroup.paymall.config;

import com.aigroup.paymall.infrastructure.gateway.IGroupBuyMarketService;
import com.aigroup.paymall.infrastructure.gateway.IMemberCatalogService;
import com.aigroup.paymall.infrastructure.gateway.IWeixinApiService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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

    @Value("${app.config.group-buy-market.api-url}")
    private String groupBuyMarketApiUrl;

    @Value("${app.config.member-service.api-url:http://127.0.0.1:8082}")
    private String memberServiceApiUrl;

    @Value("${ai-group.internal.token:}")
    private String internalToken;

    @Bean
    @ConditionalOnProperty(name = "weixin.enabled", havingValue = "true")
    public IWeixinApiService weixinApiService() {
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.weixin.qq.com/")
                .addConverterFactory(JacksonConverterFactory.create()).build();

        return retrofit.create(IWeixinApiService.class);
    }

    @Bean
    public IGroupBuyMarketService groupBuyMarketService() {
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        if (StringUtils.isNotBlank(internalToken)) {
            clientBuilder.addInterceptor(internalTokenInterceptor(internalToken));
            log.info("group-buy Retrofit client configured with X-Internal-Token interceptor");
        } else {
            log.warn("ai-group.internal.token is blank; group-buy Retrofit calls will omit X-Internal-Token");
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(groupBuyMarketApiUrl)
                .client(clientBuilder.build())
                .addConverterFactory(JacksonConverterFactory.create())
                .build();

        return retrofit.create(IGroupBuyMarketService.class);
    }

    @Bean
    public IMemberCatalogService memberCatalogService() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(internalTokenInterceptor(internalToken))
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(memberServiceApiUrl)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build();
        return retrofit.create(IMemberCatalogService.class);
    }

    static Interceptor internalTokenInterceptor(String token) {
        return chain -> {
            Request request = chain.request().newBuilder()
                    .header(HEADER_INTERNAL_TOKEN, token)
                    .build();
            return chain.proceed(request);
        };
    }
}
