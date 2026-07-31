package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunClaim;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentRuntime;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.llm.LLMSettings;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

/** P80 lifecycle contract: an accepted attachment is visible to a Run before tool execution. */
public class FileUploadLifecycleEventTest {

    @Test
    @SuppressWarnings("unchecked")
    public void shouldEmitOnlySafeFileUploadedMetadataBeforeToolAssembly() {
        AgentToolCollectionFactory toolFactory = Mockito.mock(AgentToolCollectionFactory.class);
        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        ReactorRuntimeDependencies dependencies = Mockito.mock(ReactorRuntimeDependencies.class);
        Printer printer = Mockito.mock(Printer.class);
        Mockito.when(dependencies.resolveAgentLlmSettings(Mockito.any()))
                .thenReturn(LLMSettings.builder().model("test-model").build());
        Mockito.when(recorder.claimRun(Mockito.any())).thenReturn(DialogueRunClaim.builder()
                .disposition(DialogueRunClaim.Disposition.NEW).runId(81L).runUid("req-file-upload")
                .requestId("req-file-upload").runStatus(ExecutionLedgerConstants.STATUS_RUNNING).build());
        Mockito.when(toolFactory.buildForUnified(Mockito.any(), Mockito.any()))
                .thenThrow(new IllegalStateException("stop after lifecycle event"));

        AgentRuntime runtime = new AgentRuntime(toolFactory, recorder, dependencies);
        runtime.run(AgentRequest.builder()
                .requestId("req-file-upload").sessionId("session-file-upload").ownerId("1001")
                .query("analyse the attachment").executionMode("STANDARD")
                .sessionFiles(List.of(FileInformation.builder().fileName("research.pdf")
                        .mimeType("application/pdf").fileSize(123).artifactHash("sha256:abc")
                        .resourceKey("session-file-upload:research.pdf:abc").domainUrl("https://private.example/file")
                        .build()))
                .build(), printer);

        ArgumentCaptor<AgentStreamEvent> events = ArgumentCaptor.forClass(AgentStreamEvent.class);
        Mockito.verify(printer, Mockito.atLeastOnce()).send(events.capture());
        AgentStreamEvent.StageOutput uploaded = events.getAllValues().stream()
                .filter(AgentStreamEvent.StageOutput.class::isInstance)
                .map(AgentStreamEvent.StageOutput.class::cast)
                .filter(event -> "file_upload".equals(event.outputType()))
                .findFirst().orElseThrow();

        Map<String, Object> payload = (Map<String, Object>) uploaded.payload();
        Assert.assertEquals("FILE_UPLOADED", payload.get("event"));
        Assert.assertEquals("research.pdf", payload.get("fileName"));
        Assert.assertEquals("sha256:abc", payload.get("artifactHash"));
        Assert.assertFalse(payload.containsKey("domainUrl"));
        Assert.assertFalse(payload.containsKey("ossUrl"));
        Assert.assertEquals("session-file-upload:research.pdf:abc",
                uploaded.artifactRefs().getFirst().get("artifactReference"));
    }
}
