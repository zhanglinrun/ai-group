package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.runtime.hitl.ApprovalDecision;
import com.linrun.agent.domain.agent.runtime.hitl.ApprovalGate;
import com.linrun.agent.domain.agent.runtime.hitl.ToolApproval;
import com.linrun.agent.domain.agent.runtime.hitl.ToolApprovalRepository;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

public class ToolApprovalRecoveryIT {

    @Test
    public void shouldExposeAndPersistDecisionForApprovalWithoutLocalWaiter() {
        ToolApprovalRepository repository = Mockito.mock(ToolApprovalRepository.class);
        ToolApproval pending = ToolApproval.builder()
                .id(17L).ownerId("1001").runId("request-approval")
                .toolCallId("call-1").toolName("user_mcp_write")
                .status(ApprovalDecision.PENDING).expiresAt(Instant.now().plusSeconds(60)).build();
        Mockito.when(repository.findPending("1001", "request-approval")).thenReturn(List.of(pending));
        Mockito.when(repository.decide(17L, "1001", ApprovalDecision.APPROVED, null)).thenReturn(true);

        ApprovalGate restartedGate = new ApprovalGate(repository, 1_000L);

        Assert.assertEquals(List.of(pending), restartedGate.findPending("1001", "request-approval"));
        Assert.assertTrue(restartedGate.decide(17L, "1001", ApprovalDecision.APPROVED, null));
        Mockito.verify(repository).decide(17L, "1001", ApprovalDecision.APPROVED, null);
    }
}
