package com.aigroup.bff.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Shared outbound client policy for the BFF-to-Agent boundary. */
@Configuration
public class WebClientConfig {

    private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

    @Bean
    @Primary
    WebClient.Builder agentWebClientBuilder(
            @Value("${ai-group.agent.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ai-group.agent.read-timeout-ms:45000}") int readTimeoutMs,
            @Value("${ai-group.agent.write-timeout-ms:10000}") int writeTimeoutMs) {
        return builder(connectTimeoutMs, readTimeoutMs, writeTimeoutMs);
    }

    @Bean
    @LoadBalanced
    WebClient.Builder loadBalancedAgentWebClientBuilder(
            @Value("${ai-group.agent.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ai-group.agent.read-timeout-ms:45000}") int readTimeoutMs,
            @Value("${ai-group.agent.write-timeout-ms:10000}") int writeTimeoutMs) {
        return builder(connectTimeoutMs, readTimeoutMs, writeTimeoutMs);
    }

    @Bean
    @Qualifier("sseAgentWebClientBuilder")
    WebClient.Builder sseAgentWebClientBuilder(
            @Value("${ai-group.agent.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ai-group.agent.sse-read-timeout-ms:1800000}") int sseReadTimeoutMs,
            @Value("${ai-group.agent.write-timeout-ms:10000}") int writeTimeoutMs) {
        return builder(connectTimeoutMs, sseReadTimeoutMs, writeTimeoutMs);
    }

    @Bean
    @LoadBalanced
    @Qualifier("loadBalancedSseAgentWebClientBuilder")
    WebClient.Builder loadBalancedSseAgentWebClientBuilder(
            @Value("${ai-group.agent.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${ai-group.agent.sse-read-timeout-ms:1800000}") int sseReadTimeoutMs,
            @Value("${ai-group.agent.write-timeout-ms:10000}") int writeTimeoutMs) {
        return builder(connectTimeoutMs, sseReadTimeoutMs, writeTimeoutMs);
    }

    private static WebClient.Builder builder(int connectTimeoutMs, int readTimeoutMs, int writeTimeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
                .responseTimeout(Duration.ofMillis(readTimeoutMs))
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(writeTimeoutMs, TimeUnit.MILLISECONDS)));
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(exchangeStrategies);
    }
}
