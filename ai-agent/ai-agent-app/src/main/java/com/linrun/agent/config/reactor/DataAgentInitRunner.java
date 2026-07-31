package com.linrun.agent.config.reactor;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRegistry;
import com.linrun.agent.domain.agent.reactor.config.data.DataAgentConfig;
import com.linrun.agent.domain.agent.reactor.config.data.DbConfig;
import com.linrun.agent.domain.agent.reactor.config.data.EsConfig;
import com.linrun.agent.domain.agent.reactor.service.ChatModelInfoService;
import com.linrun.agent.domain.agent.reactor.service.ColumnValueSyncService;
import com.linrun.agent.infrastructure.dataquery.jdbc.connection.JdbcConnectionFactory;
import com.linrun.agent.infrastructure.dataquery.util.JdbcUtils;

import java.sql.Connection;

@Slf4j
@Component
public class DataAgentInitRunner implements CommandLineRunner {

    @Autowired
    private DataAgentConfig dataAgentConfig;
    @Autowired
    private ChatModelInfoService chatModelInfoService;
    @Autowired
    private ColumnValueSyncService columnValueSyncService;
    @Autowired(required = false)
    private SkillRegistry skillRegistry;


    @Override
    public void run(String... args) throws Exception {
        boolean forceRefresh = Boolean.TRUE.equals(dataAgentConfig.getForceRefresh());
        log.info("dataAgent initialization forceRefresh={} dbConfigured={} esEnabled={}",
                forceRefresh,
                dataAgentConfig.getDbConfig() != null,
                dataAgentConfig.getEsConfig() != null && Boolean.TRUE.equals(dataAgentConfig.getEsConfig().getEnable()));

        // H2数据库初始化：如果配置为H2且存在初始化脚本，则执行初始化
        DbConfig dbConfig = dataAgentConfig.getDbConfig();
        if (dbConfig != null && "h2".equalsIgnoreCase(dbConfig.getType())) {
            try (Connection connection = JdbcConnectionFactory.getConnection(JdbcUtils.parseJdbcConnectionConfig(dbConfig)).getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
                // 尝试执行data.sql，如果文件不存在或出错不影响启动
                try {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/data.sql"));
                } catch (Exception e) {
                   log.warn("Execute data.sql failed or file not found, skipping data init errorType={}",
                           e.getClass().getSimpleName());
                }
                log.info("H2 database initialized with schema.sql");
            } catch (Exception e) {
                log.error("Failed to initialize H2 database errorType={}", e.getClass().getSimpleName());
                // 不抛出异常，避免影响主流程，但可能会导致后续查询失败
            }
        }

        prepareEsCapability(forceRefresh);

        try {
            if (forceRefresh) {
                chatModelInfoService.refreshModelInfo(dataAgentConfig);
            } else {
                chatModelInfoService.initModelInfo(dataAgentConfig);
            }
        } catch (Exception e) {
            if (forceRefresh) {
                log.error("强制刷新失败，终止启动流程 errorType={}", e.getClass().getSimpleName());
                throw e;
            }
            log.error("Failed to init model info errorType={}", e.getClass().getSimpleName());
        }

        if (skillRegistry != null) {
            try {
                skillRegistry.refresh();
                log.info("skill registry init success, loaded skills={}", skillRegistry.listSkills().size());
            } catch (Exception e) {
                log.error("Failed to init skill registry errorType={}", e.getClass().getSimpleName());
            }
        }
    }

    private void prepareEsCapability(boolean forceRefresh) throws Exception {
        EsConfig esConfig = dataAgentConfig.getEsConfig();
        if (!Boolean.TRUE.equals(esConfig.getEnable())) {
            return;
        }
        try {
            if (forceRefresh) {
                columnValueSyncService.recreateColumnValueIndex();
            } else {
                columnValueSyncService.initColumnValueIndex();
            }
            log.info("column value es index init success");
        } catch (Exception e) {
            handleCapabilityFailure("es", forceRefresh, e);
            esConfig.setEnable(false);
            if (forceRefresh) {
                throw e;
            }
        }
    }

    private void handleCapabilityFailure(String capability, boolean forceRefresh, Exception e) {
        if (forceRefresh) {
            log.error("{} capability force-refresh failed errorType={}", capability, e.getClass().getSimpleName());
            return;
        }
        log.warn("{} capability degraded and disabled errorType={}", capability, e.getClass().getSimpleName());
    }
}
