package com.linrun.agent.test.domain;

import org.junit.Test;
import org.junit.Assert;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.api.dto.AiClientToolMcpRequestDTO;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.infrastructure.dao.IAiClientToolMcpDao;
import com.linrun.agent.infrastructure.dao.po.AiClientToolMcp;
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

    @Test
    public void shouldPersistGovernanceMetadataAsCredentialFreeJson() {
        IAiClientToolMcpDao dao = Mockito.mock(IAiClientToolMcpDao.class);
        McpRegistry registry = Mockito.mock(McpRegistry.class);
        AiClientToolMcpAdminController controller = new AiClientToolMcpAdminController();
        ReflectionTestUtils.setField(controller, "aiClientToolMcpDao", dao);
        ReflectionTestUtils.setField(controller, "mcpRegistry", registry);
        Mockito.when(dao.insert(Mockito.any())).thenReturn(1);

        controller.createAiClientToolMcp(AiClientToolMcpRequestDTO.builder()
                .mcpId("governed-mcp")
                .mcpName("Governed MCP")
                .transportType("streamable_http")
                .transportConfig("{\"baseUri\":\"https://mcp.example.com\",\"endpoint\":\"/mcp\"}")
                .requestTimeout(30)
                .protocolVersion("2025-03-26")
                .oauthAudience("researchpilot")
                .oauthScopes(java.util.List.of("tools.read"))
                .allowedDomains(java.util.List.of("mcp.example.com"))
                .toolAllowlist(java.util.List.of("search"))
                .credentialRef("vault:mcp/governed")
                .version("2026.07.30")
                .status(1)
                .build());

        ArgumentCaptor<AiClientToolMcp> captor = ArgumentCaptor.forClass(AiClientToolMcp.class);
        Mockito.verify(dao).insert(captor.capture());
        AiClientToolMcp saved = captor.getValue();
        Assert.assertEquals("[\"tools.read\"]", saved.getOauthScopesJson());
        Assert.assertEquals("[\"mcp.example.com\"]", saved.getAllowedDomainsJson());
        Assert.assertEquals("[\"search\"]", saved.getToolAllowlistJson());
        Assert.assertEquals("vault:mcp/governed", saved.getCredentialRef());
        Assert.assertTrue(saved.getConfigHash().startsWith("sha256:"));
    }
}
