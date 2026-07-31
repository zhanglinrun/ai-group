package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

/** P150: configured MCP credentials and outbound hosts fail closed before a runtime is opened. */
public class McpConfiguredDescriptorSecurityTest {

    @Test
    public void shouldRejectScopedOAuthWithoutAnAudience() {
        McpServerDescriptor descriptor = configured(List.of("documents.read"), null,
                List.of("mcp.partner.example"), Map.of(), null);

        assertRejected(descriptor, "audience");
    }

    @Test
    public void shouldRejectEndpointOutsideConfiguredAllowDomain() {
        McpServerDescriptor descriptor = configured(List.of(), null,
                List.of("trusted.example"), Map.of(), null);

        assertRejected(descriptor, "allowed domains");
    }

    @Test
    public void shouldRejectTokenPassthroughHeaders() {
        McpServerDescriptor descriptor = configured(List.of(), null,
                List.of("mcp.partner.example"), Map.of("Authorization", "Bearer user-provided-token"), null);

        assertRejected(descriptor, "token passthrough");
    }

    @Test
    public void shouldAcceptVaultReferencedCredentialsForAnAllowedAudience() {
        McpServerDescriptor descriptor = configured(List.of("documents.read"), "https://mcp.partner.example",
                List.of("mcp.partner.example"), Map.of(), "vault:mcp/partner/read-only");

        invokeValidation(descriptor);
    }

    private McpServerDescriptor configured(List<String> scopes, String audience, List<String> allowedDomains,
                                            Map<String, String> headers, String credentialRef) {
        return McpServerDescriptor.builder()
                .mcpId("configured-partner")
                .origin(McpToolOrigin.CONFIGURED)
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_SSE)
                .serverUrl("https://mcp.partner.example/sse")
                .protocolVersion("2025-03-26")
                .oauthScopes(scopes)
                .oauthAudience(audience)
                .allowedDomains(allowedDomains)
                .headers(headers)
                .credentialRef(credentialRef)
                .build();
    }

    private void assertRejected(McpServerDescriptor descriptor, String reason) {
        try {
            invokeValidation(descriptor);
            Assert.fail("configured MCP must be rejected before runtime creation");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains(reason));
        }
    }

    private void invokeValidation(McpServerDescriptor descriptor) {
        ReflectionTestUtils.invokeMethod(new McpRegistry(), "validateConfiguredDescriptor", descriptor);
    }
}
