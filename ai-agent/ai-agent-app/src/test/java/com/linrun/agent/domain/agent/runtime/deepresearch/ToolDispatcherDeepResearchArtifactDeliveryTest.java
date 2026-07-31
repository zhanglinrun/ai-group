package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.dto.File;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.dispatch.ToolExecutionOutcome;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.ReportSpec;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.CodeInterpreterToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRef;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

public class ToolDispatcherDeepResearchArtifactDeliveryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    public void shouldDeliverPptThroughStableReportToolCall() throws Exception {
        ToolCollection tools = tools("report_tool");
        AgentContext context = context(tools);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Mockito.when(loopFactory.create(context)).thenReturn(loop);
        Mockito.when(loop.executeToolOutcome(Mockito.any(ToolCall.class))).thenAnswer(invocation -> {
            ToolCall call = invocation.getArgument(0);
            context.registerGeneratedArtifact(source(call), artifact("delivery.pptx"));
            return ToolExecutionOutcome.success("generated", "generated", null);
        });

        AgentRequest request = request("ppt");
        List<?> delivered = new ToolDispatcherDeepResearchArtifactDelivery(loopFactory)
                .deliver(context, request, "checkpoint-001", "# Verified research\n\n[S1] https://example.test/source");

        Assert.assertEquals(1, delivered.size());
        ArgumentCaptor<ToolCall> captor = ArgumentCaptor.forClass(ToolCall.class);
        Mockito.verify(loop).executeToolOutcome(captor.capture());
        ToolCall call = captor.getValue();
        Assert.assertEquals("deep-research-delivery:ppt", call.getId());
        Assert.assertEquals("report_tool", call.getFunction().getName());
        JsonNode arguments = JSON.readTree(call.getFunction().getArguments());
        Assert.assertEquals("ppt", arguments.get("fileType").asText());
        Assert.assertTrue(arguments.get("task").asText().contains("https://example.test/source"));
    }

    @Test
    public void shouldDeliverTableThroughCodeInterpreterWithCsvTarget() throws Exception {
        ToolCollection tools = tools("code_interpreter");
        AgentContext context = context(tools);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Mockito.when(loopFactory.create(context)).thenReturn(loop);
        Mockito.when(loop.executeToolOutcome(Mockito.any(ToolCall.class))).thenReturn(
                ToolExecutionOutcome.success("generated", "generated", CodeInterpreterToolOutput.builder()
                        .fileRefs(List.of(ToolFileRef.builder()
                                .fileName("delivery.csv")
                                .ossUrl("https://files.test/delivery.csv")
                                .domainUrl("https://files.test/preview/delivery.csv")
                                .fileSize(64L)
                                .build()))
                        .build()));

        new ToolDispatcherDeepResearchArtifactDelivery(loopFactory)
                .deliver(context, request("table"), "checkpoint-002", "# Verified research");

        ArgumentCaptor<ToolCall> captor = ArgumentCaptor.forClass(ToolCall.class);
        Mockito.verify(loop).executeToolOutcome(captor.capture());
        ToolCall call = captor.getValue();
        Assert.assertEquals("deep-research-delivery:table", call.getId());
        Assert.assertEquals("code_interpreter", call.getFunction().getName());
        JsonNode arguments = JSON.readTree(call.getFunction().getArguments());
        Assert.assertTrue(arguments.get("fileName").asText().endsWith(".csv"));
        Assert.assertTrue(arguments.hasNonNull("code"));
        Assert.assertTrue(arguments.get("code").asText().contains("build_output_path"));
        Assert.assertTrue(arguments.get("code").asText().contains("section,claim,source_url,evidence_status"));
        JsonNode taskPayload = JSON.readTree(arguments.get("task").asText());
        Assert.assertEquals("analysis", taskPayload.get("permissionProfile").asText());
        Assert.assertEquals(arguments.get("code").asText(), taskPayload.get("code").asText());
    }

    @Test
    public void shouldFailClosedWhenDeliveryToolReturnsWrongArtifactType() throws Exception {
        ToolCollection tools = tools("report_tool");
        AgentContext context = context(tools);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Mockito.when(loopFactory.create(context)).thenReturn(loop);
        Mockito.when(loop.executeToolOutcome(Mockito.any(ToolCall.class))).thenAnswer(invocation -> {
            ToolCall call = invocation.getArgument(0);
            context.registerGeneratedArtifact(source(call), artifact("delivery.md"));
            return ToolExecutionOutcome.success("generated", "generated", null);
        });

        IllegalStateException error = Assert.assertThrows(IllegalStateException.class,
                () -> new ToolDispatcherDeepResearchArtifactDelivery(loopFactory)
                        .deliver(context, request("html"), "checkpoint-003", "# Verified research"));

        Assert.assertTrue(error.getMessage().contains("required"));
        Assert.assertTrue(error.getMessage().contains("delivery.md"));
    }

    @Test
    public void shouldTreatConversationalStyleAsInlineWithoutExtraDelivery() throws Exception {
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Mockito.when(loopFactory.create(Mockito.any(AgentContext.class))).thenReturn(loop);

        for (String outputStyle : List.of("chat", "text")) {
            ToolCollection tools = tools("report_tool");
            AgentContext context = context(tools);
            List<?> delivered = new ToolDispatcherDeepResearchArtifactDelivery(loopFactory)
                    .deliver(context, request(outputStyle), "checkpoint-004", "# Verified research");
            Assert.assertTrue("outputStyle=" + outputStyle + " must not produce a delivery artifact",
                    delivered.isEmpty());
        }
        Mockito.verify(loop, Mockito.never()).executeTool(Mockito.any(ToolCall.class));
    }

    @Test
    public void shouldSendAuditedReportSpecToDeterministicMarkdownRenderer() throws Exception {
        ToolCollection tools = tools("report_tool");
        AgentContext context = context(tools);
        AgentLoopFactory loopFactory = Mockito.mock(AgentLoopFactory.class);
        AgentLoop loop = Mockito.mock(AgentLoop.class);
        Mockito.when(loopFactory.create(context)).thenReturn(loop);
        Mockito.when(loop.executeToolOutcome(Mockito.any(ToolCall.class))).thenAnswer(invocation -> {
            ToolCall call = invocation.getArgument(0);
            context.registerGeneratedArtifact(source(call), artifact("delivery.md"));
            return ToolExecutionOutcome.success("generated", "generated", null);
        });
        ReportSpec reportSpec = ReportSpec.fromResearch(ResearchPlan.create("report renderer"), List.of(
                new ResearchEvidencePacket("claim-1", "source", "https://example.test/source", "verified quote")));

        new ToolDispatcherDeepResearchArtifactDelivery(loopFactory).deliver(context, request("markdown"), "checkpoint-005",
                reportSpec, reportSpec.reviewMarkdown());

        ArgumentCaptor<ToolCall> captor = ArgumentCaptor.forClass(ToolCall.class);
        Mockito.verify(loop).executeToolOutcome(captor.capture());
        JsonNode arguments = JSON.readTree(captor.getValue().getFunction().getArguments());
        Assert.assertEquals("markdown", arguments.get("fileType").asText());
        Assert.assertEquals(reportSpec.title(), arguments.get("reportSpec").get("title").asText());
        Assert.assertTrue(arguments.get("reportSpec").has("citations"));
    }

    private ToolCollection tools(String toolName) {
        BaseTool tool = Mockito.mock(BaseTool.class);
        Mockito.when(tool.getName()).thenReturn(toolName);
        ToolCollection tools = new ToolCollection();
        tools.addTool(tool);
        return tools;
    }

    private AgentContext context(ToolCollection tools) {
        return AgentContext.builder()
                .requestId("deep-delivery-request")
                .sessionId("deep-delivery-session")
                .ownerId(1001L)
                .query("Deep research request")
                .toolCollection(tools)
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .reactorConfig(new ReactorConfig())
                        .build())
                .build();
    }

    private AgentRequest request(String outputStyle) {
        return AgentRequest.builder()
                .requestId("deep-delivery-request")
                .sessionId("deep-delivery-session")
                .ownerId("1001")
                .query("Deep research request")
                .executionMode("DEEP")
                .outputStyle(outputStyle)
                .build();
    }

    private ToolArtifactSource source(ToolCall call) {
        return ToolArtifactSource.builder()
                .requestId("deep-delivery-request")
                .sessionId("deep-delivery-session")
                .toolCallId(call.getId())
                .toolName(call.getFunction().getName())
                .build();
    }

    private File artifact(String fileName) {
        return File.builder()
                .fileName(fileName)
                .ossUrl("https://files.test/" + fileName)
                .domainUrl("https://files.test/preview/" + fileName)
                .fileSize(64)
                .description("test delivery")
                .isInternalFile(false)
                .build();
    }
}
