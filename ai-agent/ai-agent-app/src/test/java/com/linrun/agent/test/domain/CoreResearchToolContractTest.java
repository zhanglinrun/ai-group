package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.AnalyzeFileTool;
import com.linrun.agent.domain.agent.runtime.tool.common.ExtractEvidenceTool;
import com.linrun.agent.domain.agent.runtime.tool.common.FetchPageTool;
import com.linrun.agent.domain.agent.runtime.tool.common.RequestApprovalTool;
import com.linrun.agent.domain.agent.runtime.tool.common.SearchWebTool;
import com.linrun.agent.domain.agent.runtime.tool.common.WriteReportSpecTool;

import java.util.List;
import java.util.Map;

/** P60 canonical core capability contracts. */
public class CoreResearchToolContractTest {

    @Test
    public void shouldExposeStableCoreCapabilityNames() {
        Assert.assertEquals("analyze_file", new AnalyzeFileTool().getName());
        Assert.assertEquals("search_web", new SearchWebTool().getName());
        Assert.assertEquals("fetch_page", new FetchPageTool().getName());
        Assert.assertEquals("extract_evidence", new ExtractEvidenceTool().getName());
        Assert.assertEquals("write_report_spec", new WriteReportSpecTool().getName());
        Assert.assertEquals("request_approval", new RequestApprovalTool().getName());
    }

    @Test
    public void shouldCreateGroundedEvidenceAndReportSpecWithoutHiddenModelCalls() {
        ToolResultPayload evidence = (ToolResultPayload) new ExtractEvidenceTool().execute(Map.of(
                "source_id", "src-1",
                "source_url", "https://example.com/research",
                "title", "Research",
                "content", "Revenue grew by 20 percent in 2025. Costs remained stable.",
                "claim", "Revenue grew"));
        Assert.assertFalse(evidence.getFailed());
        Assert.assertTrue(evidence.getToolResult().contains("src-1"));
        Assert.assertTrue(evidence.getToolResult().contains("Revenue grew by 20 percent"));

        ToolResultPayload spec = (ToolResultPayload) new WriteReportSpecTool().execute(Map.of(
                "title", "Market report",
                "audience", "product team",
                "format", "markdown",
                "sections", List.of("Summary", "Evidence"),
                "evidence_refs", List.of("src-1")));
        Assert.assertFalse(spec.getFailed());
        Assert.assertTrue(spec.getToolResult().contains("grounding_required"));
        Assert.assertTrue(spec.getToolResult().contains("src-1"));
    }

    @Test
    public void shouldFailClosedWhenExplicitApprovalCannotUseAnAuthenticatedGate() {
        ToolResultPayload result = (ToolResultPayload) new RequestApprovalTool().execute(Map.of(
                "action", "send_email",
                "reason", "share the verified report"));
        Assert.assertTrue(result.getFailed());
        Assert.assertTrue(result.getErrorMsg().contains("approval service is unavailable"));
    }
}
