package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.model.tooloutput.ExtractedEvidenceToolOutput;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.ExtractEvidenceTool;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

public class P90EvidenceToolContractTest {

    @Test
    public void shouldFailClosedWhenFetchHashDoesNotMatchContent() {
        ToolResultPayload result = (ToolResultPayload) new ExtractEvidenceTool().execute(Map.of(
                "source_id", "fetch-1", "source_url", "https://source.example", "title", "source",
                "claim", "Revenue grew", "content", "Revenue grew by 20 percent.",
                "content_hash", "not-the-content", "fetched_at_epoch_millis", 1L, "source_type", "FETCHED_PAGE"));

        Assert.assertTrue(result.getFailed());
        Assert.assertTrue(result.getErrorMsg().contains("content_hash"));
    }

    @Test
    public void shouldEmitFetchedVerbatimEvidenceWithOffsetsAndOfflineTrace() throws Exception {
        String content = "Revenue grew by 20 percent in 2025. Costs remained stable.";
        ToolResultPayload result = (ToolResultPayload) new ExtractEvidenceTool().execute(Map.of(
                "source_id", "fetch-2", "source_url", "https://source.example/research", "title", "source",
                "claim", "Revenue grew", "content", content, "content_hash", sha256(content),
                "fetched_at_epoch_millis", 1234L, "source_type", "FETCHED_PAGE", "retrieval_trace_id", "tool-2",
                "offline_fixture", true));

        Assert.assertFalse(result.getFailed());
        Assert.assertTrue(result.getStructuredOutput() instanceof ExtractedEvidenceToolOutput);
        ExtractedEvidenceToolOutput output = (ExtractedEvidenceToolOutput) result.getStructuredOutput();
        Assert.assertTrue(output.isFetchedSource());
        Assert.assertTrue(output.isOfflineFixture());
        Assert.assertEquals("tool-2", output.getRetrievalTraceId());
        Assert.assertTrue(output.getExcerpts().getFirst().getEndOffset() > output.getExcerpts().getFirst().getStartOffset());
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte item : digest) {
            output.append(String.format("%02x", item));
        }
        return output.toString();
    }
}
