package com.linrun.agent.test.spring.ai;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpClientRuntime;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpClientRuntimeFactory;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Java MCP Client 与 reactor-tool FastMCP Server 的真实 STDIO 互操作测试。
 *
 * <p>默认回归在本地未执行 {@code uv sync --frozen} 时跳过，避免 Maven 单测隐式联网或修改 Python 环境。
 * 使用 {@code mvn -Pmcp-stdio-it -Dtest=McpStdioInteropTest test} 时，环境缺失会直接判定失败。</p>
 */
public class McpStdioInteropTest {

    private static final boolean REQUIRED = Boolean.getBoolean("mcp.stdio.it.required");
    private static final int PROCESS_PROBE_TIMEOUT_SECONDS = 10;
    private static Path reactorToolDirectory;

    @Before
    public void verifyLocalRuntime() {
        reactorToolDirectory = findReactorToolDirectory();
        requireOrSkip(reactorToolDirectory != null,
                "reactor-tool directory not found; run the test from the ai-agent workspace");

        requireOrSkip(commandSucceeds(List.of("uv", "--version")),
                "uv is not available on PATH");

        Path pythonExecutable = resolveVirtualEnvPython(reactorToolDirectory);
        requireOrSkip(Files.isRegularFile(pythonExecutable),
                "reactor-tool virtual environment is missing; run 'uv sync --frozen' first");
        requireOrSkip(commandSucceeds(List.of(pythonExecutable.toString(), "-c", "import mcp")),
                "official Python MCP SDK is missing; run 'uv sync --frozen' first");

        requireOrSkip(Files.isRegularFile(reactorToolDirectory.resolve(
                        "reactor_tool/mcp_servers/project_knowledge_server.py")),
                "project knowledge MCP server module is missing");
        requireOrSkip(Files.isRegularFile(reactorToolDirectory.resolve(
                        "reactor_tool/mcp_servers/agent_utility_server.py")),
                "agent utility MCP server module is missing");
    }

    @Test
    public void test_projectKnowledgeServer_listAndCallTool() throws Exception {
        verifyServer(
                "project-knowledge-it",
                "reactor_tool.mcp_servers.project_knowledge_server",
                Set.of("project_search_knowledge", "project_get_flow"),
                "project_search_knowledge",
                Map.of("query", "长期记忆", "limit", 3),
                List.of("memory-architecture", "\"ok\":true")
        );
    }

    @Test
    public void test_agentUtilityServer_listAndCallTool() throws Exception {
        verifyServer(
                "agent-utility-it",
                "reactor_tool.mcp_servers.agent_utility_server",
                Set.of("utility_estimate_llm_quota", "utility_explain_quota_formula"),
                "utility_estimate_llm_quota",
                Map.of(
                        "input_tokens", 1000,
                        "requested_output_tokens", 512,
                        "actual_output_tokens", 100,
                        "input_microcredits_per_token", 5,
                        "output_microcredits_per_token", 30
                ),
                List.of("20360", "12680", "8000")
        );
    }

    private void verifyServer(String mcpId,
                              String moduleName,
                              Set<String> expectedToolNames,
                              String toolName,
                              Map<String, Object> arguments,
                              List<String> expectedPayloadFragments) throws Exception {
        McpServerDescriptor descriptor = McpServerDescriptor.builder()
                .mcpId(mcpId)
                .serverKey(mcpId)
                .serverUrl("stdio://" + mcpId)
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .command("uv")
                .args(List.of(
                        "--directory",
                        resolveUvProjectDirectoryArgument(),
                        "run",
                        "--frozen",
                        "python",
                        "-m",
                        moduleName
                ))
                .env(Map.of(
                        "PYTHONIOENCODING", "utf-8",
                        "PYTHONUNBUFFERED", "1"
                ))
                .requestTimeout(30)
                .build();

        McpClientRuntime runtime = null;
        try {
            runtime = new McpClientRuntimeFactory().createRuntime(descriptor);

            McpSchema.ListToolsResult listToolsResult = runtime.getSyncClient().listTools();
            Assert.assertNotNull("tools/list must return a result", listToolsResult);
            Assert.assertNotNull("tools/list must return a tool collection", listToolsResult.tools());

            Set<String> actualToolNames = listToolsResult.tools().stream()
                    .map(McpSchema.Tool::name)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Assert.assertEquals("unexpected MCP tool surface", expectedToolNames, actualToolNames);

            McpSchema.CallToolResult callResult = runtime.getSyncClient()
                    .callTool(new McpSchema.CallToolRequest(toolName, arguments));
            Assert.assertNotNull("tools/call must return a result", callResult);
            Assert.assertFalse("MCP tool returned isError=true: " + callResult,
                    Boolean.TRUE.equals(callResult.isError()));

            String payload = flattenPayload(callResult);
            Assert.assertFalse("MCP tool returned an empty payload", payload.isBlank());
            for (String fragment : expectedPayloadFragments) {
                Assert.assertTrue(
                        "MCP payload does not contain expected fragment '" + fragment + "': " + payload,
                        payload.contains(fragment)
                );
            }
        } finally {
            if (runtime != null && runtime.getSyncClient() != null) {
                runtime.getSyncClient().closeGracefully();
            }
        }
    }

    /**
     * Maven 从 ai-agent-app 运行时优先复用开发种子里的相对路径，直接覆盖一键启动的真实配置。
     * 其他 IDE 工作目录则使用绝对路径，避免测试自身受 user.dir 差异影响。
     */
    private String resolveUvProjectDirectoryArgument() {
        Path seededRelativeDirectory = Path.of("../reactor-tool").toAbsolutePath().normalize();
        return seededRelativeDirectory.equals(reactorToolDirectory)
                ? "../reactor-tool"
                : reactorToolDirectory.toString();
    }

    private String flattenPayload(McpSchema.CallToolResult result) {
        List<String> parts = new ArrayList<>();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                    parts.add(textContent.text());
                } else if (content != null) {
                    parts.add(content.toString());
                }
            }
        }
        if (result.structuredContent() != null) {
            parts.add(result.structuredContent().toString());
        }
        return String.join(System.lineSeparator(), parts);
    }

    private static Path findReactorToolDirectory() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            for (Path candidate : List.of(
                    cursor.resolve("reactor-tool"),
                    cursor.resolve("ai-agent").resolve("reactor-tool")
            )) {
                if (Files.isRegularFile(candidate.resolve("pyproject.toml"))) {
                    return candidate.normalize();
                }
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    private static Path resolveVirtualEnvPython(Path projectDirectory) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win")
                ? projectDirectory.resolve(".venv/Scripts/python.exe")
                : projectDirectory.resolve(".venv/bin/python");
    }

    private static boolean commandSucceeds(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(PROCESS_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            try (InputStream ignored = process.getInputStream()) {
                ignored.readAllBytes();
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void requireOrSkip(boolean condition, String message) {
        if (!condition && REQUIRED) {
            Assert.fail(message);
        }
        Assume.assumeTrue(message, condition);
    }
}
