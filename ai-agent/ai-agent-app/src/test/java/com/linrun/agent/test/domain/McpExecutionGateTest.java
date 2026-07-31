package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.harness.DefaultPermissionPolicy;
import com.linrun.agent.domain.agent.runtime.harness.PermissionPolicy;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpClientRuntimeFactory;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpEndpointPolicy;
import org.junit.Assert;
import org.junit.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed contracts for user-managed MCP endpoints. */
public class McpExecutionGateTest {

    @Test
    public void shouldPermanentlyRejectUserStdioBeforeRuntimeCreation() {
        McpServerDescriptor descriptor = McpServerDescriptor.builder()
                .mcpId("user:1001:extension-1")
                .origin(McpToolOrigin.USER_EXTENSION)
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .serverUrl("stdio://user:1001:extension-1")
                .command("untrusted-command")
                .build();

        try {
            new McpClientRuntimeFactory().createRuntime(descriptor);
            Assert.fail("a user STDIO descriptor must not reach process creation");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("STDIO"));
        }
    }

    @Test
    public void shouldRejectAHostThatResolvesToPrivateAddressOnRecheck() throws Exception {
        UserMcpEndpointPolicy policy = new UserMcpEndpointPolicy(
                ignored -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});

        try {
            policy.validate("https://changed.example/mcp", McpServerDescriptor.TRANSPORT_TYPE_SSE);
            Assert.fail("DNS rebinding to a private address must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("本机或内网"));
        }
    }

    @Test
    public void shouldRejectUserMcpWhenDnsAnswerDriftsAfterRuntimeRegistration() throws Exception {
        AtomicReference<InetAddress[]> answers = new AtomicReference<>(
                new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        UserMcpEndpointPolicy policy = new UserMcpEndpointPolicy(ignored -> answers.get());
        McpServerDescriptor descriptor = McpServerDescriptor.builder()
                .mcpId("user:1001:extension-1")
                .origin(McpToolOrigin.USER_EXTENSION)
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP)
                .serverUrl("https://public.example/mcp")
                .build();

        policy.pin(descriptor);
        answers.set(new InetAddress[]{InetAddress.getByAddress(new byte[]{1, 1, 1, 1})});

        try {
            policy.validatePinned(descriptor);
            Assert.fail("a changed public DNS answer must require an explicit MCP re-registration");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("DNS 解析已变化"));
        }
    }

    @Test
    public void shouldRequireApprovalForEveryUserMcpTool() {
        McpToolInfo userTool = McpToolInfo.builder()
                .mcpId("user:1001:extension-1")
                .name("remote_read")
                .exposedName("mcp__user_1001_extension-1__remote_read")
                .origin(McpToolOrigin.USER_EXTENSION)
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP)
                .build();
        ToolCollection tools = new ToolCollection();
        tools.addMcpTool(userTool);
        AgentContext context = AgentContext.builder()
                .ownerId(1001L)
                .requestId("user-mcp-approval")
                .productFiles(List.of())
                .build();

        PermissionPolicy.PermissionDecision decision = new DefaultPermissionPolicy().evaluate(
                userTool.resolveExposedName(), java.util.Map.of(), tools, context);

        Assert.assertEquals(PermissionPolicy.Decision.ASK, decision.decision());
    }
}
