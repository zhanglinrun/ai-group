package com.linrun.agent.types.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 主链路执行器与 CORS 配置。
 */
@Data
@ConfigurationProperties(prefix = "autobots.execution")
public class AgentExecutorProperties {

    private Pool dispatch = Pool.dispatchDefault();

    private Pool llm = Pool.llmDefault();

    private Pool task = Pool.taskDefault();

    private Pool tool = Pool.toolDefault();

    private Heartbeat heartbeat = Heartbeat.defaultValue();

    private RunRecovery runRecovery = RunRecovery.defaultValue();

    private Cors cors = Cors.defaultValue();

    @Data
    public static class Pool {

        private Integer corePoolSize;
        private Integer maxPoolSize;
        private Integer queueCapacity;
        private Long keepAliveSeconds;
        private String rejectPolicy;
        private String threadNamePrefix;

        public static Pool dispatchDefault() {
            Pool pool = new Pool();
            pool.setCorePoolSize(16);
            pool.setMaxPoolSize(32);
            pool.setQueueCapacity(200);
            pool.setKeepAliveSeconds(60L);
            pool.setRejectPolicy("AbortPolicy");
            pool.setThreadNamePrefix("agent-dispatch-");
            return pool;
        }

        public static Pool llmDefault() {
            Pool pool = new Pool();
            pool.setCorePoolSize(16);
            pool.setMaxPoolSize(32);
            pool.setQueueCapacity(100);
            pool.setKeepAliveSeconds(60L);
            pool.setRejectPolicy("AbortPolicy");
            pool.setThreadNamePrefix("agent-llm-");
            return pool;
        }

        public static Pool toolDefault() {
            Pool pool = new Pool();
            pool.setCorePoolSize(8);
            pool.setMaxPoolSize(16);
            pool.setQueueCapacity(50);
            pool.setKeepAliveSeconds(60L);
            pool.setRejectPolicy("AbortPolicy");
            pool.setThreadNamePrefix("agent-tool-");
            return pool;
        }

        public static Pool taskDefault() {
            Pool pool = new Pool();
            pool.setCorePoolSize(8);
            pool.setMaxPoolSize(16);
            pool.setQueueCapacity(50);
            pool.setKeepAliveSeconds(60L);
            pool.setRejectPolicy("AbortPolicy");
            pool.setThreadNamePrefix("agent-task-");
            return pool;
        }
    }

    @Data
    public static class Heartbeat {

        private Integer poolSize;
        private String threadNamePrefix;
        private Long intervalMillis;

        public static Heartbeat defaultValue() {
            Heartbeat heartbeat = new Heartbeat();
            heartbeat.setPoolSize(2);
            heartbeat.setThreadNamePrefix("agent-heartbeat-");
            heartbeat.setIntervalMillis(10_000L);
            return heartbeat;
        }
    }

    @Data
    public static class RunRecovery {

        private Boolean enabled;
        private Long scanIntervalMillis;
        private Long deadlineGraceMillis;
        private Long heartbeatTimeoutMillis;
        private Integer batchLimit;

        public static RunRecovery defaultValue() {
            RunRecovery recovery = new RunRecovery();
            recovery.setEnabled(true);
            recovery.setScanIntervalMillis(60_000L);
            recovery.setDeadlineGraceMillis(300_000L);
            recovery.setHeartbeatTimeoutMillis(60_000L);
            recovery.setBatchLimit(200);
            return recovery;
        }
    }

    @Data
    public static class Cors {

        private List<String> allowedOrigins = new ArrayList<>();

        public static Cors defaultValue() {
            Cors cors = new Cors();
            cors.setAllowedOrigins(new ArrayList<>());
            return cors;
        }
    }
}
