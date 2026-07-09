package org.wwz.ai.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.wwz.ai.application.agent.quota.MemberQuotaProperties;

@Configuration
@EnableConfigurationProperties(MemberQuotaProperties.class)
public class MemberQuotaConfiguration {
}
