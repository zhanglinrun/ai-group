package com.aigroup.gateway;

import com.aigroup.common.config.InternalTokenProperties;
import com.aigroup.common.config.JwtProperties;
import com.aigroup.common.config.ProductionSecurityValidator;
import com.aigroup.common.config.RedisConfig;
import com.aigroup.common.utils.JwtUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.aigroup.gateway")
@EnableDiscoveryClient
@EnableConfigurationProperties({JwtProperties.class, InternalTokenProperties.class})
@Import({JwtUtils.class, RedisConfig.class, ProductionSecurityValidator.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
