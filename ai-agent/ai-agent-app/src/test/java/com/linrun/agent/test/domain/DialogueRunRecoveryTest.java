package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.DialogueRunRecoveryService;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerWriteRepository;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunRecoveryCommand;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;

public class DialogueRunRecoveryTest {

    @Test
    public void shouldTerminalizeOnlyStaleRunsWithoutRequestingProviderReplay() {
        IExecutionLedgerWriteRepository repository = Mockito.mock(IExecutionLedgerWriteRepository.class);
        Mockito.when(repository.failWorkerLostRuns(Mockito.any())).thenReturn(1);
        DialogueRunRecoveryService service = new DialogueRunRecoveryService(repository);
        LocalDateTime now = LocalDateTime.of(2026, 7, 30, 12, 0);

        Assert.assertEquals(1, service.failWorkerLostRuns(now, Duration.ofMinutes(2), Duration.ofMinutes(1), 25));

        ArgumentCaptor<DialogueRunRecoveryCommand> command = ArgumentCaptor.forClass(DialogueRunRecoveryCommand.class);
        Mockito.verify(repository).failWorkerLostRuns(command.capture());
        Assert.assertEquals(now.minusMinutes(2), command.getValue().deadlineBefore());
        Assert.assertEquals(now.minusMinutes(1), command.getValue().heartbeatBefore());
        Assert.assertEquals(DialogueRunRecoveryService.WORKER_LOST_ERROR_CODE, command.getValue().errorCode());
    }
}
