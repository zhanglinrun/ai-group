package com.linrun.agent.domain.agent.runtime.tool.durable;

import com.linrun.agent.domain.agent.adapter.port.RemoteHttpPort;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.CodeInterpreterToolOutput;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class RemoteDurableToolExecutorTest {

    @Test
    public void shouldMapDurableCodeFileInfoToStrongArtifactOutput() throws Exception {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://127.0.0.1:1601/v1/tool");
        ReflectionTestUtils.setField(config, "reactorToolToken", "test-tool-token");
        RemoteHttpPort remoteHttpPort = Mockito.mock(RemoteHttpPort.class);
        Mockito.when(remoteHttpPort.execute(Mockito.any())).thenReturn("""
                {"status":"SUCCEEDED","providerRequestId":"python-worker-test","result":{
                  "stdout":"","fileInfo":[{
                    "fileName":"deep.csv",
                    "downloadUrl":"http://files.test/download/deep.csv",
                    "domainUrl":"http://files.test/preview/deep.csv",
                    "fileSize":64
                  }]
                }}
                """);
        RemoteDurableToolExecutor executor = new RemoteDurableToolExecutor(
                new DurableToolControlPlane(new InMemoryDurableToolStore()), remoteHttpPort, config);

        ToolResultPayload payload = executor.execute(DurableToolExecutionRequest.builder()
                .toolInvocationId(101L)
                .runId(88L)
                .requestId("durable-code-request")
                .toolCallId("deep-research-delivery:table")
                .toolName("code_interpreter")
                .operationKey("sha256:durable-code")
                .inputJson("{\"code\":\"print('ok')\"}")
                .ownerWorkerId("test-worker")
                .fencingToken(7L)
                .build());

        Assert.assertEquals(Boolean.FALSE, payload.getFailed());
        Assert.assertTrue(payload.getStructuredOutput() instanceof CodeInterpreterToolOutput);
        CodeInterpreterToolOutput output = (CodeInterpreterToolOutput) payload.getStructuredOutput();
        Assert.assertEquals(1, output.getFileRefs().size());
        Assert.assertEquals("deep.csv", output.getFileRefs().getFirst().getFileName());
        Assert.assertEquals("http://files.test/download/deep.csv", output.getFileRefs().getFirst().getDownloadUrl());
        Assert.assertEquals("http://files.test/preview/deep.csv", output.getFileRefs().getFirst().getPreviewUrl());

        ArgumentCaptor<com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest> request =
                ArgumentCaptor.forClass(com.linrun.agent.domain.agent.adapter.port.RemoteHttpRequest.class);
        Mockito.verify(remoteHttpPort).execute(request.capture());
        Assert.assertTrue(request.getValue().getUrl().endsWith("/internal/runtime/tools/execute"));
    }
}
