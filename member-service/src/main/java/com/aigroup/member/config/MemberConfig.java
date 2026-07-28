package com.aigroup.member.config;

import com.aigroup.common.filter.GatewayUserContextFilter;
import com.aigroup.common.filter.InternalApiAuthFilter;
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
}
