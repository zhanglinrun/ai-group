package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.FileArtifactPort;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.FileToolOutput;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.dto.FileRequest;
import com.linrun.agent.domain.agent.runtime.dto.FileResponse;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.FileTool;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Map;

public class FileToolTest {

    @Test
    public void shouldExposePersistedFileNameAfterUpload() throws Exception {
        FileArtifactPort fileArtifactPort = Mockito.mock(FileArtifactPort.class);
        Mockito.when(fileArtifactPort.upload(Mockito.nullable(String.class), Mockito.any())).thenReturn(FileResponse.builder()
                .fileName("stored-name.md")
                .ossUrl("https://files.example/download/stored-name.md")
                .domainUrl("https://files.example/preview/stored-name.md")
                .fileSize(128)
                .build());

        AgentContext context = AgentContext.builder()
                .requestId("req-file-upload-001")
                .sessionId("session-file-upload-001")
                .printer(Mockito.mock(Printer.class))
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .reactorConfig(new ReactorConfig())
                        .fileArtifactPort(fileArtifactPort)
                        .build())
                .build();
        FileTool tool = new FileTool();
        tool.setAgentContext(context);

        context.bindCurrentToolArtifactSource(ToolArtifactSource.builder()
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .toolCallId("call-file-upload-001")
                .toolName("file_tool")
                .build());
        try {
            ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                    "command", "upload",
                    "fileName", "requested-name.md",
                    "description", "测试文件",
                    "content", "test content"
            ));

            FileToolOutput output = (FileToolOutput) payload.getStructuredOutput();
            Assert.assertFalse(payload.getFailed());
            Assert.assertTrue(payload.getToolResult().contains("stored-name.md"));
            Assert.assertEquals("stored-name.md", output.getPrimaryFileName());
            Assert.assertEquals("stored-name.md", context.getVisibleArtifactFiles().get(0).getFileName());
        } finally {
            context.clearCurrentToolArtifactSource();
        }
    }

    @Test
    public void shouldExposeCamelCaseFileNameAndAcceptHistoricFilenameAlias() throws Exception {
        FileArtifactPort fileArtifactPort = Mockito.mock(FileArtifactPort.class);
        Mockito.when(fileArtifactPort.upload(Mockito.nullable(String.class), Mockito.any())).thenReturn(FileResponse.builder()
                .fileName("golden_eval_note.md")
                .ossUrl("https://files.example/download/golden_eval_note.md")
                .domainUrl("https://files.example/preview/golden_eval_note.md")
                .fileSize(12)
                .build());

        FileTool tool = new FileTool();
        AgentContext context = fileContext(fileArtifactPort, "req-file-schema-001");
        tool.setAgentContext(context);
        context.bindCurrentToolArtifactSource(ToolArtifactSource.builder()
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .toolCallId("call-file-schema-001")
                .toolName("file_tool")
                .build());
        try {
            Assert.assertTrue(tool.toParams().get("properties") instanceof Map<?, ?>);
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) tool.toParams().get("properties");
            Assert.assertTrue(properties.containsKey("fileName"));
            Assert.assertFalse(properties.containsKey("filename"));

            ToolResultPayload payload = (ToolResultPayload) tool.execute(Map.of(
                    "command", "upload",
                    "fileName", "golden_eval_note.md",
                    "description", "golden ready",
                    "content", "golden ready"
            ));

            ArgumentCaptor<FileRequest> requestCaptor = ArgumentCaptor.forClass(FileRequest.class);
            Mockito.verify(fileArtifactPort).upload(Mockito.nullable(String.class), requestCaptor.capture());
            Assert.assertFalse(payload.getFailed());
            Assert.assertEquals("golden_eval_note.md", requestCaptor.getValue().getFileName());
            Assert.assertEquals("golden_eval_note.md", context.getVisibleArtifactFiles().get(0).getFileName());
        } finally {
            context.clearCurrentToolArtifactSource();
        }
    }

    @Test
    public void shouldAcceptHistoricFilenameAliasWhenReplayingAStoredToolCall() throws Exception {
        FileArtifactPort fileArtifactPort = Mockito.mock(FileArtifactPort.class);
        Mockito.when(fileArtifactPort.upload(Mockito.nullable(String.class), Mockito.any())).thenReturn(FileResponse.builder()
                .fileName("historic-name.md")
                .ossUrl("https://files.example/download/historic-name.md")
                .domainUrl("https://files.example/preview/historic-name.md")
                .fileSize(12)
                .build());
        FileTool tool = new FileTool();
        AgentContext context = fileContext(fileArtifactPort, "req-file-schema-002");
        tool.setAgentContext(context);
        context.bindCurrentToolArtifactSource(ToolArtifactSource.builder()
                .requestId(context.getRequestId())
                .sessionId(context.getSessionId())
                .toolCallId("call-file-schema-002")
                .toolName("file_tool")
                .build());
        try {
            tool.execute(Map.of(
                    "command", "upload",
                    "filename", "historic-name.md",
                    "description", "historic",
                    "content", "historic"
            ));

            ArgumentCaptor<FileRequest> requestCaptor = ArgumentCaptor.forClass(FileRequest.class);
            Mockito.verify(fileArtifactPort).upload(Mockito.nullable(String.class), requestCaptor.capture());
            Assert.assertEquals("historic-name.md", requestCaptor.getValue().getFileName());
        } finally {
            context.clearCurrentToolArtifactSource();
        }
    }

    private AgentContext fileContext(FileArtifactPort fileArtifactPort, String requestId) {
        return AgentContext.builder()
                .requestId(requestId)
                .sessionId("session-" + requestId)
                .printer(Mockito.mock(Printer.class))
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .reactorConfig(new ReactorConfig())
                        .fileArtifactPort(fileArtifactPort)
                        .build())
                .build();
    }
}
