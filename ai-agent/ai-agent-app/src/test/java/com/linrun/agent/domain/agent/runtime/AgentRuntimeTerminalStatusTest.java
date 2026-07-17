package com.linrun.agent.domain.agent.runtime;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;

public class AgentRuntimeTerminalStatusTest {

    @Test
    public void shouldKeepTypedTerminalStatusAcrossProtocolAndLedger() {
        Assert.assertEquals("SUCCESS",
                AgentRuntime.resolveProtocolStatus(AgentStopReason.COMPLETED, true));
        Assert.assertEquals(ExecutionLedgerConstants.STATUS_SUCCESS,
                AgentRuntime.resolveLedgerStatus(AgentStopReason.COMPLETED, true));

        Assert.assertEquals("TIMEOUT",
                AgentRuntime.resolveProtocolStatus(AgentStopReason.TIME_BUDGET, false));
        Assert.assertEquals(ExecutionLedgerConstants.STATUS_TIMEOUT,
                AgentRuntime.resolveLedgerStatus(AgentStopReason.TIME_BUDGET, false));

        Assert.assertEquals("STOPPED",
                AgentRuntime.resolveProtocolStatus(AgentStopReason.DOWNSTREAM_ABORTED, false));
        Assert.assertEquals(ExecutionLedgerConstants.STATUS_STOPPED,
                AgentRuntime.resolveLedgerStatus(AgentStopReason.DOWNSTREAM_ABORTED, false));

        Assert.assertEquals("FAILED",
                AgentRuntime.resolveProtocolStatus(AgentStopReason.COMPLETION_ATTEMPT_BUDGET, false));
        Assert.assertEquals(ExecutionLedgerConstants.STATUS_FAILED,
                AgentRuntime.resolveLedgerStatus(AgentStopReason.COMPLETION_ATTEMPT_BUDGET, false));

        Assert.assertEquals("FAILED",
                AgentRuntime.resolveProtocolStatus(AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE, false));
        Assert.assertEquals(ExecutionLedgerConstants.STATUS_FAILED,
                AgentRuntime.resolveLedgerStatus(AgentStopReason.REQUIRED_CAPABILITY_UNAVAILABLE, false));
    }
}
