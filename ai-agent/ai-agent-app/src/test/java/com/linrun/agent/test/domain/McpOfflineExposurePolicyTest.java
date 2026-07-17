package com.linrun.agent.test.domain;

import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.adapter.repository.IAgentRepository;
import com.linrun.agent.domain.agent.model.valobj.AiClientToolMcpVO;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpClientRuntime;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpClientRuntimeFactory;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;

import java.util.List;
import java.util.Map;

public class McpOfflineExposurePolicyTest {

    @Test
    public void shouldFilterNetworkTransportsBeforeCreatingOfflineRuntime() {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        McpClientRuntimeFactory runtimeFactory = Mockito.mock(McpClientRuntimeFactory.class);
        Mockito.when(repository.queryEnabledAiClientToolMcpVOList()).thenReturn(List.of(
                config("local-stdio", McpServerDescriptor.TRANSPORT_TYPE_STDIO),
                config("remote-sse", McpServerDescriptor.TRANSPORT_TYPE_SSE),
                config("remote-http", McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP)
        ));
        Mockito.when(runtimeFactory.createRuntime(Mockito.any())).thenAnswer(invocation ->
                McpClientRuntime.builder()
                        .descriptor(invocation.getArgument(0))
                        .syncClient(Mockito.mock(McpSyncClient.class))
                        .build());
        McpRegistry registry = registry(repository, runtimeFactory);

        List<McpToolInfo> tools = registry.listOfflineEligibleConfiguredTools();

        Assert.assertTrue(tools.isEmpty());
        ArgumentCaptor<McpServerDescriptor> descriptorCaptor =
                ArgumentCaptor.forClass(McpServerDescriptor.class);
        Mockito.verify(runtimeFactory).createRuntime(descriptorCaptor.capture());
        Assert.assertEquals(McpServerDescriptor.TRANSPORT_TYPE_STDIO,
                descriptorCaptor.getValue().getTransportType());
        Assert.assertEquals(McpToolOrigin.CONFIGURED, descriptorCaptor.getValue().getOrigin());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void shouldReturnOnlyTrustedConfiguredStdioMetadataFromOfflineCache() {
        IAgentRepository repository = Mockito.mock(IAgentRepository.class);
        McpClientRuntimeFactory runtimeFactory = Mockito.mock(McpClientRuntimeFactory.class);
        Mockito.when(repository.queryEnabledAiClientToolMcpVOList()).thenReturn(List.of(
                config("local-stdio", McpServerDescriptor.TRANSPORT_TYPE_STDIO)
        ));
        McpRegistry registry = registry(repository, runtimeFactory);
        Map<String, McpClientRuntime> runtimeCache =
                (Map<String, McpClientRuntime>) ReflectionTestUtils.getField(registry, "runtimeCache");
        Map<String, List<McpToolInfo>> toolCache =
                (Map<String, List<McpToolInfo>>) ReflectionTestUtils.getField(registry, "toolCache");
        Assert.assertNotNull(runtimeCache);
        Assert.assertNotNull(toolCache);
        runtimeCache.put("local-stdio", McpClientRuntime.builder().build());
        toolCache.put("local-stdio", List.of(
                tool("configured_tool", McpToolOrigin.CONFIGURED),
                tool("user_tool", McpToolOrigin.USER_EXTENSION),
                tool("unknown_tool", McpToolOrigin.UNKNOWN)
        ));

        List<McpToolInfo> tools = registry.listOfflineEligibleConfiguredTools();

        Assert.assertEquals(List.of("configured_tool"),
                tools.stream().map(McpToolInfo::getName).toList());
        Mockito.verifyNoInteractions(runtimeFactory);
    }

    private McpRegistry registry(IAgentRepository repository,
                                 McpClientRuntimeFactory runtimeFactory) {
        McpRegistry registry = new McpRegistry();
        ReflectionTestUtils.setField(registry, "repository", repository);
        ReflectionTestUtils.setField(registry, "runtimeFactory", runtimeFactory);
        return registry;
    }

    private AiClientToolMcpVO config(String id, String transportType) {
        return AiClientToolMcpVO.builder()
                .mcpId(id)
                .mcpName(id)
                .transportType(transportType)
                .transportConfigStdio(AiClientToolMcpVO.TransportConfigStdio.builder()
                        .stdio(Map.of())
                        .build())
                .build();
    }

    private McpToolInfo tool(String name, McpToolOrigin origin) {
        return McpToolInfo.builder()
                .mcpId("local-stdio")
                .name(name)
                .exposedName("mcp__local_stdio__" + name)
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .origin(origin)
                .build();
    }
}
