package org.wwz.ai.application.agent.quota;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai-group.member-service")
public class MemberQuotaProperties {

    private String baseUrl = "http://127.0.0.1:8082";
}
