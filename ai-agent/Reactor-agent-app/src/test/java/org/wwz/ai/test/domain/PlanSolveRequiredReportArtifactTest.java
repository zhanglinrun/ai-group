package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.File;
import org.wwz.ai.domain.agent.service.execute.planexecute.step.Step2PlanExecuteNode;

public class PlanSolveRequiredReportArtifactTest {

    @Test
    public void shouldTreatReportOutputStylesAsRequiredArtifactContract() {
        ExposedStepNode node = new ExposedStepNode();

        Assert.assertTrue(node.requires(request("html")));
        Assert.assertTrue(node.requires(request("docs")));
        Assert.assertTrue(node.requires(request("ppt")));
        Assert.assertFalse(node.requires(request("chat")));
        Assert.assertFalse(node.requires(request("table")));
    }

    @Test
    public void shouldAcceptOnlyArtifactRegisteredByReportTool() {
        ExposedStepNode node = new ExposedStepNode();
        AgentContext context = AgentContext.builder()
                .requestId("request-report-contract")
                .sessionId("session-report-contract")
                .build();

        context.registerGeneratedArtifact(source("deep_search"), file("evidence.md"));
        Assert.assertFalse(node.hasReport(context));

        context.registerGeneratedArtifact(source("report_tool"), file("strict-report.md"));
        Assert.assertTrue(node.hasReport(context));
    }

    @Test
    public void shouldBuildAnUnambiguousForcedReportTaskForEachStyle() {
        ExposedStepNode node = new ExposedStepNode();

        Assert.assertTrue(node.deliveryTask("docs").contains("report_tool"));
        Assert.assertTrue(node.deliveryTask("docs").contains("fileType=markdown"));
        Assert.assertTrue(node.deliveryTask("html").contains("fileType=html"));
        Assert.assertTrue(node.deliveryTask("ppt").contains("fileType=ppt"));
    }

    private AgentRequest request(String outputStyle) {
        AgentRequest request = new AgentRequest();
        request.setOutputStyle(outputStyle);
        return request;
    }

    private ToolArtifactSource source(String toolName) {
        return ToolArtifactSource.builder()
                .requestId("request-report-contract")
                .sessionId("session-report-contract")
                .toolCallId("call-" + toolName)
                .toolName(toolName)
                .build();
    }

    private File file(String fileName) {
        return File.builder()
                .fileName(fileName)
                .ossUrl("https://files.example.test/" + fileName)
                .isInternalFile(false)
                .build();
    }

    private static final class ExposedStepNode extends Step2PlanExecuteNode {
        private boolean requires(AgentRequest request) {
            return requiresReportArtifact(request);
        }

        private boolean hasReport(AgentContext context) {
            return hasVisibleReportArtifact(context);
        }

        private String deliveryTask(String outputStyle) {
            return requiredReportDeliveryTask(outputStyle);
        }
    }
}
