package com.linrun.agent;

import com.aigroup.common.config.ProductionSecurityValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.linrun.agent.infrastructure.gateway")
@EnableTransactionManagement
@EnableScheduling
@Import(ProductionSecurityValidator.class)
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class, args);
    }

}
