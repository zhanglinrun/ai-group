package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.context.ContextProjection;
import com.linrun.agent.domain.agent.runtime.context.ContextProjectionRequest;
import com.linrun.agent.domain.agent.runtime.context.ContextProjectionService;
import com.linrun.agent.domain.agent.runtime.context.ContextRole;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshot;
import com.linrun.agent.domain.agent.runtime.context.ContextSnapshotKey;
import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class ContextProjectionServiceTest {

    @Test
    public void shouldCompactDeterministicallyWithoutDroppingRequestTodoOrEvidence() {
        ContextSnapshot snapshot = snapshot();
        ContextProjection projection = new ContextProjectionService().project(new ContextProjectionRequest(
                snapshot, ContextRole.RESEARCHER, "Compare current market evidence", "Find official sources",
                List.of("freshness within 12 months"),
                List.of("duplicate search history", "duplicate search history", "long history ".repeat(100)),
                List.of(), List.of(), "", List.of(), 180));

        Assert.assertTrue(projection.rendered().contains("CURRENT_USER_REQUEST"));
        Assert.assertTrue(projection.rendered().contains("Compare current market evidence"));
        Assert.assertTrue(projection.rendered().contains("UNFINISHED_TODO"));
        Assert.assertTrue(projection.rendered().contains("evidence-official-1"));
        Assert.assertTrue(projection.compacted());
        Assert.assertTrue(new TokenCounter().countText(projection.rendered()) <= 180);
    }

    @Test
    public void shouldExposeOnlyRoleRelevantViews() {
        ContextSnapshot snapshot = snapshot();
        ContextProjectionService service = new ContextProjectionService();
        ContextProjection writer = service.project(new ContextProjectionRequest(snapshot, ContextRole.WRITER,
                "report", "", List.of(), List.of("search history must stay hidden"),
                List.of("claim-1 -> evidence-official-1"), List.of("summary", "limitations"), "", List.of(), 600));
        ContextProjection tool = service.project(new ContextProjectionRequest(snapshot, ContextRole.TOOL,
                "report", "fetch official page", List.of(), List.of(), List.of("claim should not leak"),
                List.of(), "report spec should not leak", List.of("url=https://example.test"), 600));

        Assert.assertTrue(writer.rendered().contains("CLAIM_EVIDENCE"));
        Assert.assertFalse(writer.rendered().contains("SEARCH_HISTORY"));
        Assert.assertTrue(tool.rendered().contains("MINIMAL_IDENTITY"));
        Assert.assertTrue(tool.rendered().contains("TOOL_PARAMETERS"));
        Assert.assertFalse(tool.rendered().contains("claim should not leak"));
        Assert.assertFalse(tool.rendered().contains("report spec should not leak"));
    }

    private ContextSnapshot snapshot() {
        return new ContextSnapshot(new ContextSnapshotKey("tenant-a", "owner-a", "session-a", 1L), 3,
                "Assess the market", List.of("fact-a", "fact-a"), List.of("assumption-a"),
                List.of("evidence-official-1"), List.of("conflict-a"), List.of("draft report"),
                List.of("verify citations before final"), "deterministic", "p70", "source", false, 1L, 2L);
    }
}
