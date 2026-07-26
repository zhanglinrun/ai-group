package com.aigroup.paymall;

import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Configurable
@EnableFeignClients(basePackages = "com.aigroup.paymall.infrastructure.gateway")
public class Application {

    public static void main(String[] args){
        SpringApplication.run(Application.class, args);
    }

}
