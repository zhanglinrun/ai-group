package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamListener;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamPort;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamRequest;
import org.wwz.ai.domain.agent.adapter.port.RemoteStreamSession;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.runtime.ReactorRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.artifact.ToolArtifactSource;
import org.wwz.ai.domain.agent.runtime.dto.CodeInterpreterRequest;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.tool.ToolCollection;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.common.ReportTool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ReportToolTerminalValidationTest {

    @Test
    public void shouldFailWhenStreamClosesBeforeFinalResponse() throws Exception {
        ReportTool tool = newTool(listener -> listener.onClosed());

        ToolResultPayload result = tool.callCodeAgentStream(request(), artifactSource()).get();

        Assert.assertEquals(Boolean.TRUE, result.getFailed());
        Assert.assertTrue(result.getErrorMsg().contains("有效最终响应到达前关闭"));
    }

    @Test
    public void shouldFailWhenFinalResponseHasNoArtifact() throws Exception {
        ReportTool tool = newTool(listener -> {
            listener.onLine("data:{\"requestId\":\"session-report\",\"data\":\"报告正文\",\"isFinal\":true}");
            listener.onClosed();
        });

        ToolResultPayload result = tool.callCodeAgentStream(request(), artifactSource()).get();

        Assert.assertEquals(Boolean.TRUE, result.getFailed());
        Assert.assertTrue(result.getErrorMsg().contains("missing report artifact"));
    }

    @Test
    public void shouldSucceedOnlyWithValidFinalContentAndArtifact() throws Exception {
        ReportTool tool = newTool(listener -> {
            listener.onLine("data:{\"requestId\":\"session-report\",\"data\":\"报告正文\","
                    + "\"fileInfo\":[{\"fileName\":\"demo.html\",\"ossUrl\":\"https://files.test/demo.html\","
                    + "\"domainUrl\":\"https://files.test/preview/demo.html\",\"fileSize\":12}],\"isFinal\":true}");
            listener.onClosed();
        });

        ToolResultPayload result = tool.callCodeAgentStream(request(), artifactSource()).get();

        Assert.assertEquals(Boolean.FALSE, result.getFailed());
        Assert.assertEquals("报告正文", result.getToolResult());
        Assert.assertEquals(1, tool.getAgentContext().getVisibleArtifactFiles().size());
    }

    private ReportTool newTool(StreamScript script) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://reactor-tool.test");
        AgentContext context = AgentContext.builder()
                .requestId("request-report")
                .sessionId("session-report")
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .reactorConfig(config)
                        .remoteStreamPort(new ScriptedRemoteStreamPort(script))
                        .build())
                .toolCollection(Mockito.mock(ToolCollection.class))
                .printer(Mockito.mock(Printer.class))
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .isStream(false)
                .build();
        ReportTool tool = new ReportTool();
        tool.setAgentContext(context);
        return tool;
    }

    private CodeInterpreterRequest request() {
        return CodeInterpreterRequest.builder()
                .requestId("session-report")
                .task("生成报告")
                .fileNames(List.of())
                .fileName("demo")
                .fileDescription("演示报告")
                .fileType("html")
                .stream(true)
                .build();
    }

    private ToolArtifactSource artifactSource() {
        return ToolArtifactSource.builder()
                .sessionId("session-report")
                .requestId("request-report")
                .toolCallId("call-report")
                .toolName("report_tool")
                .build();
    }

    @FunctionalInterface
    private interface StreamScript {
        void run(RemoteStreamListener listener) throws Exception;
    }

    private static final class ScriptedRemoteStreamPort implements RemoteStreamPort {
        private final StreamScript script;

        private ScriptedRemoteStreamPort(StreamScript script) {
            this.script = script;
        }

        @Override
        public RemoteStreamSession openStream(RemoteStreamRequest request,
                                              RemoteStreamListener listener) throws IOException {
            try {
                listener.onOpen();
                script.run(listener);
            } catch (Exception exception) {
                throw new IOException(exception);
            }
            return () -> { };
        }
    }
}
