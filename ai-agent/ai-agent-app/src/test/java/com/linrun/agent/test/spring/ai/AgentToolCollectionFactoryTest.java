package com.linrun.agent.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.ExecuteExtraTool;
import com.linrun.agent.domain.agent.runtime.tool.common.PlatformContextTool;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpServerDescriptor;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolOrigin;
import com.linrun.agent.domain.agent.runtime.tool.skill.DefaultSkillRegistry;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillMarkdownParser;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillPathGuard;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillScriptDiscoverer;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillScriptRunnerClient;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillExtensionService;
import com.linrun.agent.domain.agent.runtime.tool.mcp.user.UserMcpExtensionService;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.test.domain.support.ReactorRuntimeTestSupport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 统一 Agent Loop 的 run-local 工具装配测试。
 */
public class AgentToolCollectionFactoryTest {

    @Test
    public void shouldRegisterPlatformContextWhenTypedBffPortIsAvailable() {
        McpToolExecutor mcpToolExecutor = Mockito.mock(McpToolExecutor.class);
        Mockito.when(mcpToolExecutor.discoverConfiguredTools()).thenReturn(List.of());
        AgentToolCollectionFactory factory = new AgentToolCollectionFactory(
                buildReactorConfig(),
                mcpToolExecutor,
                Mockito.mock(DefaultSkillRegistry.class),
                SkillRuntimeOptions.builder().enabled(false).agentLoopEnabled(false).build(),
                Mockito.mock(SkillScriptRunnerClient.class),
                mockUserSkillService(),
                mockUserMcpService());
        AgentContext context = buildAgentContext();
        PlatformContextPort port = Mockito.mock(PlatformContextPort.class);
        context.setRuntimeDependencies(context.getRuntimeDependencies().toBuilder()
                .platformContextPort(port)
                .build());

        ToolCollection tools = factory.buildForUnified(context, buildAgentRequest("html"));

        Assert.assertTrue(tools.getToolMap().containsKey(PlatformContextTool.NAME));
    }

    @Test
    public void shouldIncludeSkillToolsAndTodoWriteForUnifiedLoopInStableOrder() throws Exception {
        DefaultSkillRegistry skillRegistry = createRegistry(true);
        skillRegistry.refresh();

        McpToolExecutor mcpToolExecutor = Mockito.mock(McpToolExecutor.class);
        Mockito.when(mcpToolExecutor.discoverConfiguredTools()).thenReturn(List.of(
                McpToolInfo.builder()
                        .name("remote_tool")
                        .desc("远程测试工具")
                        .parameters("{}")
                        .build()
        ));

        AgentToolCollectionFactory factory = new AgentToolCollectionFactory(
                buildReactorConfig(),
                mcpToolExecutor,
                skillRegistry,
                SkillRuntimeOptions.builder()
                        .enabled(true)
                        .agentLoopEnabled(true)
                        .build(),
                Mockito.mock(SkillScriptRunnerClient.class),
                mockUserSkillService(),
                mockUserMcpService()
        );

        ToolCollection toolCollection = factory.buildForUnified(buildAgentContext(), buildAgentRequest("html"));

        Assert.assertEquals(
                Arrays.asList(
                        "file_tool",
                        "code_interpreter",
                        "report_tool",
                        "deep_search",
                        "web_fetch",
                        "skill_tool",
                        "read_tool",
                        "list_directory_tool",
                        "glob_tool",
                        "grep_tool",
                        "script_runner_tool",
                        "todo_write"
                ),
                new ArrayList<>(toolCollection.getToolMap().keySet())
        );
        Assert.assertTrue(toolCollection.getMcpToolMap().containsKey("remote_tool"));
    }

    @Test
    public void shouldNotIncludeSkillToolsWhenAgentLoopSkillsAreDisabled() throws Exception {
        DefaultSkillRegistry skillRegistry = createRegistry(true);
        skillRegistry.refresh();

        McpToolExecutor mcpToolExecutor = Mockito.mock(McpToolExecutor.class);
        Mockito.when(mcpToolExecutor.discoverConfiguredTools()).thenReturn(List.of());

        AgentToolCollectionFactory factory = new AgentToolCollectionFactory(
                buildReactorConfig(),
                mcpToolExecutor,
                skillRegistry,
                SkillRuntimeOptions.builder()
                        .enabled(true)
                        .agentLoopEnabled(false)
                        .build(),
                Mockito.mock(SkillScriptRunnerClient.class),
                mockUserSkillService(),
                mockUserMcpService()
        );

        ToolCollection toolCollection = factory.buildForUnified(buildAgentContext(), buildAgentRequest("docs"));

        Assert.assertFalse(toolCollection.getToolMap().containsKey("skill_tool"));
        Assert.assertFalse(toolCollection.getToolMap().containsKey("script_runner_tool"));
        Assert.assertTrue(toolCollection.getToolMap().containsKey("file_tool"));
        Assert.assertTrue(toolCollection.getToolMap().containsKey("todo_write"));
        Assert.assertFalse(toolCollection.getToolMap().containsKey("multimodalagent_tool"));
    }

    @Test
    public void shouldExposeOnlyConfiguredStdioMcpAndSkipUserMcpWhenOffline() {
        McpToolExecutor mcpToolExecutor = Mockito.mock(McpToolExecutor.class);
        UserMcpExtensionService userMcpExtensionService = Mockito.mock(UserMcpExtensionService.class);
        UserSkillExtensionService userSkillExtensionService = mockUserSkillService();
        ReactorConfig reactorConfig = buildReactorConfig();
        ReflectionTestUtils.setField(reactorConfig, "toolExposureMode", "filtered");
        McpToolInfo configuredStdio = McpToolInfo.builder()
                .mcpId("local-configured")
                .name("estimate")
                .exposedName("mcp__local_configured__estimate")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .origin(McpToolOrigin.CONFIGURED)
                .build();
        McpToolInfo configuredHttp = McpToolInfo.builder()
                .mcpId("remote-configured")
                .name("search")
                .exposedName("mcp__remote_configured__search")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STREAMABLE_HTTP)
                .origin(McpToolOrigin.CONFIGURED)
                .build();
        McpToolInfo userStdio = McpToolInfo.builder()
                .mcpId("user:1001:test")
                .name("unsafe_local")
                .exposedName("mcp__user_1001_test__unsafe_local")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .origin(McpToolOrigin.USER_EXTENSION)
                .build();
        Mockito.when(mcpToolExecutor.discoverOfflineEligibleConfiguredTools())
                .thenReturn(List.of(configuredStdio, configuredHttp, userStdio));

        AgentToolCollectionFactory factory = new AgentToolCollectionFactory(
                reactorConfig,
                mcpToolExecutor,
                Mockito.mock(DefaultSkillRegistry.class),
                SkillRuntimeOptions.builder()
                        .enabled(false)
                        .agentLoopEnabled(false)
                        .build(),
                Mockito.mock(SkillScriptRunnerClient.class),
                userSkillExtensionService,
                userMcpExtensionService
        );
        AgentRequest request = buildAgentRequest("html");
        request.setOnline(false);

        ToolCollection toolCollection = factory.buildForUnified(buildAgentContext(), request);

        Mockito.verify(mcpToolExecutor).discoverOfflineEligibleConfiguredTools();
        Mockito.verify(mcpToolExecutor, Mockito.never()).discoverConfiguredTools();
        Mockito.verify(userMcpExtensionService, Mockito.never())
                .discoverEnabledTools(Mockito.any());
        Assert.assertEquals(List.of("mcp__local_configured__estimate"),
                new ArrayList<>(toolCollection.getMcpToolMap().keySet()));
        Assert.assertTrue(toolCollection.getToolMap().containsKey("tool_search"));
        Assert.assertTrue(toolCollection.getToolMap().containsKey(ExecuteExtraTool.NAME));
        Assert.assertFalse(toolCollection.getToolMap().containsKey("deep_search"));
        Assert.assertFalse(toolCollection.getToolMap().containsKey("web_fetch"));
    }

    @Test
    public void shouldScopeOfflineConfiguredMcpByProfileClientIds() {
        McpToolExecutor mcpToolExecutor = Mockito.mock(McpToolExecutor.class);
        UserMcpExtensionService userMcpExtensionService = Mockito.mock(UserMcpExtensionService.class);
        McpToolInfo roleTool = McpToolInfo.builder()
                .mcpId("role-local-mcp")
                .name("lookup")
                .exposedName("mcp__role_local_mcp__lookup")
                .transportType(McpServerDescriptor.TRANSPORT_TYPE_STDIO)
                .origin(McpToolOrigin.CONFIGURED)
                .build();
        Mockito.when(mcpToolExecutor.discoverOfflineEligibleToolsForClients(List.of("client-role")))
                .thenReturn(List.of(roleTool));

        AgentToolCollectionFactory factory = new AgentToolCollectionFactory(
                buildReactorConfig(),
                mcpToolExecutor,
                Mockito.mock(DefaultSkillRegistry.class),
                SkillRuntimeOptions.builder().enabled(false).agentLoopEnabled(false).build(),
                Mockito.mock(SkillScriptRunnerClient.class),
                mockUserSkillService(),
                userMcpExtensionService
        );
        AgentRequest request = buildAgentRequest("html");
        request.setOnline(false);
        request.setProfileClientIds(List.of("client-role"));

        ToolCollection toolCollection = factory.buildForUnified(buildAgentContext(), request);

        Mockito.verify(mcpToolExecutor).discoverOfflineEligibleToolsForClients(List.of("client-role"));
        Mockito.verify(mcpToolExecutor, Mockito.never()).discoverOfflineEligibleConfiguredTools();
        Assert.assertEquals(List.of("mcp__role_local_mcp__lookup"),
                new ArrayList<>(toolCollection.getMcpToolMap().keySet()));
    }

    @Test
    public void shouldNotIncludeMultiModalAgentWhenRemovedFromDefaultList() {
        McpToolExecutor mcpToolExecutor = Mockito.mock(McpToolExecutor.class);
        Mockito.when(mcpToolExecutor.discoverConfiguredTools()).thenReturn(List.of());

        ReactorConfig reactorConfig = buildReactorConfig();
        reactorConfig.setMultiAgentToolList("{\"default\":\"search,code,report\"}");

        AgentToolCollectionFactory factory = new AgentToolCollectionFactory(
                reactorConfig,
                mcpToolExecutor,
                Mockito.mock(DefaultSkillRegistry.class),
                SkillRuntimeOptions.builder()
                        .enabled(false)
                        .agentLoopEnabled(false)
                        .build(),
                Mockito.mock(SkillScriptRunnerClient.class),
                mockUserSkillService(),
                mockUserMcpService()
        );

        ToolCollection toolCollection = factory.buildForUnified(buildAgentContext(), buildAgentRequest("html"));

        Assert.assertFalse(toolCollection.getToolMap().containsKey("multimodalagent_tool"));
        Assert.assertTrue(toolCollection.getToolMap().containsKey("todo_write"));
    }

    @Test
    public void shouldKeepDataAgentToolingStableWithoutMultiModalAgent() {
        McpToolExecutor mcpToolExecutor = Mockito.mock(McpToolExecutor.class);
        Mockito.when(mcpToolExecutor.discoverConfiguredTools()).thenReturn(List.of());

        AgentToolCollectionFactory factory = new AgentToolCollectionFactory(
                buildReactorConfig(),
                mcpToolExecutor,
                Mockito.mock(DefaultSkillRegistry.class),
                SkillRuntimeOptions.builder()
                        .enabled(false)
                        .agentLoopEnabled(false)
                        .build(),
                Mockito.mock(SkillScriptRunnerClient.class),
                mockUserSkillService(),
                mockUserMcpService()
        );

        ToolCollection toolCollection = factory.buildForUnified(buildAgentContext(), buildAgentRequest("dataAgent"));

        Assert.assertTrue(toolCollection.getToolMap().containsKey("report_tool"));
        Assert.assertTrue(toolCollection.getToolMap().containsKey("data_analysis"));
        Assert.assertTrue(toolCollection.getToolMap().containsKey("todo_write"));
        Assert.assertFalse(toolCollection.getToolMap().containsKey("multimodalagent_tool"));
    }

    @Test
    public void shouldKeepRunStateCursorThreadScopedForParallelReaders() throws Exception {
        AgentContext context = buildAgentContext();
        context.activateLedgerRun(101L, "run-101");
        context.markExecutionPosition("parent", 1);
        context.getAgentRunState().bindCurrentLlmInvocationId(11L);

        final String[] agentName = new String[1];
        final Integer[] stepNo = new Integer[1];
        final Long[] llmInvocationId = new Long[1];

        Thread childThread = new Thread(() -> {
            AgentContext childContext = context.forkForParallelTask("并发子任务");
            childContext.markExecutionPosition("child", 3);
            childContext.getAgentRunState().bindCurrentLlmInvocationId(33L);
            agentName[0] = childContext.getAgentRunState().getCurrentAgentName();
            stepNo[0] = childContext.getAgentRunState().getCurrentStepNo();
            llmInvocationId[0] = childContext.getAgentRunState().getCurrentLlmInvocationId();
        });
        childThread.start();
        childThread.join();

        Assert.assertEquals("parent", context.getAgentRunState().getCurrentAgentName());
        Assert.assertEquals(Integer.valueOf(1), context.getAgentRunState().getCurrentStepNo());
        Assert.assertEquals(Long.valueOf(11L), context.getAgentRunState().getCurrentLlmInvocationId());
        Assert.assertEquals("child", agentName[0]);
        Assert.assertEquals(Integer.valueOf(3), stepNo[0]);
        Assert.assertEquals(Long.valueOf(33L), llmInvocationId[0]);
    }

    private DefaultSkillRegistry createRegistry(boolean agentLoopEnabled) throws Exception {
        SkillPathGuard skillPathGuard = new SkillPathGuard();
        return new DefaultSkillRegistry(
                SkillRuntimeOptions.builder()
                        .enabled(true)
                        .directories(List.of(new ClassPathResource("skills").getFile().getAbsolutePath()))
                        .agentLoopEnabled(agentLoopEnabled)
                        .build(),
                new SkillMarkdownParser(),
                new SkillScriptDiscoverer(skillPathGuard),
                skillPathGuard
        );
    }

    private ReactorConfig buildReactorConfig() {
        ReactorConfig reactorConfig = new ReactorConfig();
        reactorConfig.setMultiAgentToolList("{\"default\":\"search,web_fetch,code,report,multimodalagent\"}");
        return reactorConfig;
    }

    private AgentContext buildAgentContext() {
        ReactorRuntimeDependencies runtimeDependencies = ReactorRuntimeTestSupport.runtimeDependencies(buildReactorConfig());
        return AgentContext.builder()
                .requestId("req-001")
                .sessionId("session-001")
                .query("测试 skill 工具装配")
                .task("")
                .printer(new SilentPrinter())
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .runtimeDependencies(runtimeDependencies)
                .historyDialogue("")
                .basePrompt("")
                .dateInfo("2026-05-10")
                .build();
    }

    private AgentRequest buildAgentRequest(String outputStyle) {
        return AgentRequest.builder()
                .requestId("req-001")
                .sessionId("session-001")
                .query("测试 skill 工具装配")
                .outputStyle(outputStyle)
                .build();
    }

    private UserSkillExtensionService mockUserSkillService() {
        UserSkillExtensionService service = Mockito.mock(UserSkillExtensionService.class);
        Mockito.when(service.listEnabled(Mockito.nullable(String.class))).thenReturn(List.of());
        return service;
    }

    private UserMcpExtensionService mockUserMcpService() {
        UserMcpExtensionService service = Mockito.mock(UserMcpExtensionService.class);
        Mockito.when(service.discoverEnabledTools(Mockito.nullable(String.class))).thenReturn(List.of());
        return service;
    }

    private static final class SilentPrinter implements Printer {

        @Override
        public void send(String messageId, String messageType, Object message, String digitalEmployee, Boolean isFinal) {
        }

        @Override
        public void send(String messageId, String messageType, Object message, java.util.Map<String, Object> extraResultMap, String digitalEmployee, Boolean isFinal) {
        }

        @Override
        public void send(String messageType, Object message) {
        }

        @Override
        public void send(String messageType, Object message, String digitalEmployee) {
        }

        @Override
        public void send(String messageId, String messageType, Object message, Boolean isFinal) {
        }

        @Override
        public void sendWithResultMap(String messageId, String messageType, Object message, java.util.Map<String, Object> extraResultMap, Boolean isFinal) {
        }

        @Override
        public void sendWithResultMap(String messageType, Object message, java.util.Map<String, Object> extraResultMap) {
        }

        @Override
        public void close() {
        }

    }
}
