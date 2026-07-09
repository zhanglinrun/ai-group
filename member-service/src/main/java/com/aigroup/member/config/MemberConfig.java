package com.aigroup.member.config;

import com.aigroup.common.filter.GatewayUserContextFilter;
import com.aigroup.common.filter.InternalApiAuthFilter;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class MemberConfig {

    @Value("${ai-group.member.benefit-queue:member.benefit.queue}")
    private String benefitQueue;

    @Value("${ai-group.member.benefit-exchange:member.benefit.exchange}")
    private String benefitExchange;

    @Value("${ai-group.member.benefit-routing-key:member.benefit.completed}")
    private String benefitRoutingKey;

    private static final String DLX_SUFFIX = ".dlx";
    private static final String DLQ_SUFFIX = ".dlq";

    private final GatewayUserContextFilter gatewayUserContextFilter;
    private final InternalApiAuthFilter internalApiAuthFilter;

    public MemberConfig(GatewayUserContextFilter gatewayUserContextFilter,
                        InternalApiAuthFilter internalApiAuthFilter) {
        this.gatewayUserContextFilter = gatewayUserContextFilter;
        this.internalApiAuthFilter = internalApiAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(internalApiAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(gatewayUserContextFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public Queue benefitQueueBean() {
        return QueueBuilder.durable(benefitQueue)
                .withArgument("x-dead-letter-exchange", benefitExchange + DLX_SUFFIX)
                .withArgument("x-dead-letter-routing-key", benefitRoutingKey + DLQ_SUFFIX)
                .build();
    }

    @Bean
    public TopicExchange benefitExchangeBean() {
        return new TopicExchange(benefitExchange, true, false);
    }

    @Bean
    public Binding benefitBinding() {
        return BindingBuilder.bind(benefitQueueBean()).to(benefitExchangeBean()).with(benefitRoutingKey);
    }

    @Bean
    public TopicExchange benefitDlxExchangeBean() {
        return new TopicExchange(benefitExchange + DLX_SUFFIX, true, false);
    }

    @Bean
    public Queue benefitDlqBean() {
        return QueueBuilder.durable(benefitQueue + DLQ_SUFFIX).build();
    }

    @Bean
    public Binding benefitDlqBinding() {
        return BindingBuilder.bind(benefitDlqBean())
                .to(benefitDlxExchangeBean())
                .with(benefitRoutingKey + DLQ_SUFFIX);
    }
}
