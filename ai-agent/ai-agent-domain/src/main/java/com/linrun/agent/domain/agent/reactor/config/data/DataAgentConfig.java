package com.linrun.agent.domain.agent.reactor.config.data;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "autobots.data-agent")
public class DataAgentConfig {
    private String agentUrl;
    private Boolean forceRefresh = false;
    private List<DataAgentModelConfig> modelList;
    private DbConfig dbConfig = new DbConfig();
    private EsConfig esConfig = new EsConfig();

    /**
     * 保持数据库配置读取端始终拿到可用对象，减少分散判空。
     */
    public DbConfig getDbConfig() {
        if (dbConfig == null) {
            dbConfig = new DbConfig();
        }
        return dbConfig;
    }

    /**
     * 兼容缺省 ES 配置，保证能力降级逻辑可安全执行。
     */
    public EsConfig getEsConfig() {
        if (esConfig == null) {
            esConfig = new EsConfig();
        }
        return esConfig;
    }
}
