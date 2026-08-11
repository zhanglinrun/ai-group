package com.aigroup.auth.config;

import com.aigroup.common.filter.GatewayUserContextFilter;
import com.aigroup.common.filter.InternalApiAuthFilter;
import com.aigroup.common.filter.OperationalAuditFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GatewayUserContextFilter gatewayUserContextFilter;
    private final InternalApiAuthFilter internalApiAuthFilter;

    public SecurityConfig(GatewayUserContextFilter gatewayUserContextFilter,
                          InternalApiAuthFilter internalApiAuthFilter) {
        this.gatewayUserContextFilter = gatewayUserContextFilter;
        this.internalApiAuthFilter = internalApiAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().permitAll())
                .addFilterBefore(internalApiAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(gatewayUserContextFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new OperationalAuditFilter(), GatewayUserContextFilter.class);
        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
