package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpRegistry;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpConfig;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpExtensionService;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillMarkdownParser;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillExtensionService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class UserExtensionIsolationTest {

    @Test
    public void shouldIsolateUploadedSkillsByOwner() throws Exception {
        Path root = Files.createTempDirectory("user-skill-isolation");
        UserSkillExtensionService service = new UserSkillExtensionService(new SkillMarkdownParser());
        ReflectionTestUtils.setField(service, "extensionsDirectory", root.toString());

        service.install("1001", archive("SKILL.md", """
                ---
                name: demo-skill
                description: owner scoped skill
                ---
                # Demo
                """));

        Assert.assertEquals(1, service.list("1001").size());
        Assert.assertTrue(service.list("2002").isEmpty());
    }

    @Test(expected = RuntimeException.class)
    public void shouldRejectExecutableFilesInUserSkillArchive() throws Exception {
        Path root = Files.createTempDirectory("user-skill-script");
        UserSkillExtensionService service = new UserSkillExtensionService(new SkillMarkdownParser());
        ReflectionTestUtils.setField(service, "extensionsDirectory", root.toString());
        service.install("1001", archive(
                "SKILL.md", "---\nname: unsafe\ndescription: unsafe\n---\n# Unsafe",
                "scripts/run.py", "print('unsafe')"
        ));
    }

    @Test
    public void shouldIsolateMcpConfigsAndRejectPrivateNetwork() throws Exception {
        Path root = Files.createTempDirectory("user-mcp-isolation");
        UserMcpExtensionService service = new UserMcpExtensionService(org.mockito.Mockito.mock(McpRegistry.class));
        ReflectionTestUtils.setField(service, "extensionsDirectory", root.toString());

        service.save("1001", UserMcpConfig.builder()
                .name("public-mcp")
                .serverUrl("https://1.1.1.1/mcp")
                .transportType("streamable_http")
                .enabled(false)
                .build());

        Assert.assertEquals(1, service.list("1001").size());
        Assert.assertTrue(service.list("2002").isEmpty());
        try {
            service.save("1001", UserMcpConfig.builder()
                    .name("private-mcp")
                    .serverUrl("http://127.0.0.1:8080/mcp")
                    .transportType("streamable_http")
                    .enabled(false)
                    .build());
            Assert.fail("private MCP URL should be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("本机或内网"));
        }
    }

    @Test
    public void shouldMarkDiscoveredUserMcpAsUserExtension() throws Exception {
        Path root = Files.createTempDirectory("user-mcp-origin");
        McpRegistry registry = Mockito.mock(McpRegistry.class);
        Mockito.when(registry.ensureExternalDescriptor(Mockito.any())).thenReturn(java.util.List.of());
        UserMcpExtensionService service = new UserMcpExtensionService(registry);
        ReflectionTestUtils.setField(service, "extensionsDirectory", root.toString());

        service.save("1001", UserMcpConfig.builder()
                .name("public-mcp")
                .serverUrl("https://1.1.1.1/mcp")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP)
                .enabled(true)
                .build());

        ArgumentCaptor<McpServerDescriptor> descriptorCaptor =
                ArgumentCaptor.forClass(McpServerDescriptor.class);
        Mockito.verify(registry).ensureExternalDescriptor(descriptorCaptor.capture());
        Assert.assertEquals(McpToolOrigin.USER_EXTENSION, descriptorCaptor.getValue().getOrigin());
        Assert.assertEquals(McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP,
                descriptorCaptor.getValue().getTransportType());
    }

    private ByteArrayInputStream archive(String... nameAndContent) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (int index = 0; index < nameAndContent.length; index += 2) {
                zip.putNextEntry(new ZipEntry(nameAndContent[index]));
                zip.write(nameAndContent[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
