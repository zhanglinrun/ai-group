package com.aigroup.bff.config;

import com.aigroup.common.filter.GatewayUserContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class BffSecurityConfig {

    private final GatewayUserContextFilter gatewayUserContextFilter;

    public BffSecurityConfig(GatewayUserContextFilter gatewayUserContextFilter) {
        this.gatewayUserContextFilter = gatewayUserContextFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(gatewayUserContextFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
