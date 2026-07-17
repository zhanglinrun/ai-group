package com.linrun.agent.test.domain;

import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.api.dto.AiClientToolMcpRequestDTO;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.infrastructure.dao.IAiClientToolMcpDao;
import com.linrun.agent.trigger.http.admin.AiClientToolMcpAdminController;

public class SystemMcpAdminControllerTest {

    @Test
    public void shouldRefreshRuntimeAfterSystemMcpUpdate() {
        IAiClientToolMcpDao dao = Mockito.mock(IAiClientToolMcpDao.class);
        McpRegistry registry = Mockito.mock(McpRegistry.class);
        AiClientToolMcpAdminController controller = new AiClientToolMcpAdminController();
        ReflectionTestUtils.setField(controller, "aiClientToolMcpDao", dao);
        ReflectionTestUtils.setField(controller, "mcpRegistry", registry);
        Mockito.when(dao.updateById(Mockito.any())).thenReturn(1);

        controller.updateAiClientToolMcpById(AiClientToolMcpRequestDTO.builder()
                .id(1L)
                .mcpId("system-demo")
                .mcpName("System Demo")
                .transportType("streamable_http")
                .transportConfig("{\"baseUri\":\"https://example.com\",\"endpoint\":\"/mcp\"}")
                .requestTimeout(30)
                .status(1)
                .build());

        Mockito.verify(registry).preloadAllEnabledMcps();
    }
}
