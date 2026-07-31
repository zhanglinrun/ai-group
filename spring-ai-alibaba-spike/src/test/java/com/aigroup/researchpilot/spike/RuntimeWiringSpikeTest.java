package com.aigroup.researchpilot.spike;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = SpikeApplication.class,
        properties = {
                "spring.main.web-application-type=none",
                "management.otlp.metrics.export.enabled=true",
                "management.otlp.metrics.export.url=http://127.0.0.1:4318/v1/metrics"
        })
class RuntimeWiringSpikeTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void bootsWithOtlpMeterRegistryAndFunctionMcpContracts() {
        assertThat(meterRegistry).isNotNull();
        assertThat(FunctionToolCallback.class.getName())
                .isEqualTo("org.springframework.ai.tool.function.FunctionToolCallback");
        assertThat(Arrays.stream(new String[]{
                "io.modelcontextprotocol.client.McpClient",
                "io.modelcontextprotocol.client.McpAsyncClient",
                "io.modelcontextprotocol.client.McpSyncClient",
                "org.springframework.ai.mcp.client.McpAsyncClient",
                "org.springframework.ai.mcp.client.McpSyncClient"
        }).anyMatch(this::classExists)).isTrue();
    }

    private boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
