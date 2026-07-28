package com.linrun.agent.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(
            @Value("${xxl.job.admin.addresses}") String adminAddresses,
            @Value("${xxl.job.accessToken}") String accessToken,
            @Value("${xxl.job.executor.appname:ai-agent}") String appName,
            @Value("${xxl.job.executor.address:}") String address,
            @Value("${xxl.job.executor.ip:}") String ip,
            @Value("${xxl.job.executor.port}") int port,
            @Value("${xxl.job.executor.logpath:./logs/xxl-job/agent}") String logPath,
            @Value("${xxl.job.executor.logretentiondays:30}") int logRetentionDays) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAccessToken(accessToken);
        executor.setAppname(appName);
        executor.setAddress(address);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
