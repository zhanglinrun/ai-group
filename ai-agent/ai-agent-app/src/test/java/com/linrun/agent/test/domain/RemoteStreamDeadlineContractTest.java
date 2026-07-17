package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.adapter.port.RemoteStreamListener;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamRequest;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamSession;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.dto.CodeInterpreterRequest;
import com.linrun.agent.domain.agent.runtime.dto.DataAnalysisRequest;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.common.CodeInterpreterTool;
import com.linrun.agent.domain.agent.runtime.tool.common.DataAnalysisTool;
import com.linrun.agent.domain.agent.runtime.tool.common.ReportTool;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Offline contract tests for seconds-based HTTP timeouts and run-bound stream cancellation. */
public class RemoteStreamDeadlineContractTest {

    @Test
    public void shouldUseSecondsScaleTimeoutsForCoreRemoteStreams() throws Exception {
        CapturingRemoteStreamPort port = new CapturingRemoteStreamPort(true);
        AgentContext context = context(port);

        CodeInterpreterTool codeTool = new CodeInterpreterTool();
        codeTool.setAgentContext(context);
        CompletableFuture<ToolResultPayload> codeFuture = codeTool.callCodeAgentStream(
                CodeInterpreterRequest.builder().requestId("session").task("code").fileNames(List.of()).build(),
                source("code-call", "code_interpreter"));
        codeFuture.get(1, TimeUnit.SECONDS);
        assertTimeouts(port.lastRequest.get(), 30L, 300L, 60L, 300L);

        ReportTool reportTool = new ReportTool();
        reportTool.setAgentContext(context);
        CompletableFuture<ToolResultPayload> reportFuture = reportTool.callCodeAgentStream(
                CodeInterpreterRequest.builder()
                        .requestId("session")
                        .task("report")
                        .fileNames(List.of())
                        .fileName("report")
                        .fileType("html")
                        .build(),
                source("report-call", "report_tool"));
        reportFuture.get(1, TimeUnit.SECONDS);
        assertTimeouts(port.lastRequest.get(), 30L, 600L, 60L, 600L);

        DataAnalysisTool dataTool = new DataAnalysisTool();
        dataTool.setAgentContext(context);
        CompletableFuture<ToolResultPayload> dataFuture = dataTool.callAutoAnalysisStream(
                DataAnalysisRequest.builder().request_id("session").task("analysis").build(),
                source("data-call", "data_analysis"));
        dataFuture.get(1, TimeUnit.SECONDS);
        assertTimeouts(port.lastRequest.get(), 30L, 300L, 60L, 300L);
    }

    @Test(timeout = 2000L)
    public void shouldCancelRemoteSessionWhenRunDeadlineExpires() {
        CapturingRemoteStreamPort port = new CapturingRemoteStreamPort(false);
        AgentContext context = context(port);
        context.activateRunDeadline(80L);
        context.bindCurrentToolArtifactSource(source("code-call", "code_interpreter"));

        CodeInterpreterTool tool = new CodeInterpreterTool();
        tool.setAgentContext(context);
        Object rawResult;
        try {
            rawResult = tool.execute(Map.of("task", "never completes"));
        } finally {
            context.clearCurrentToolArtifactSource();
        }

        Assert.assertTrue(rawResult instanceof ToolResultPayload);
        ToolResultPayload result = (ToolResultPayload) rawResult;
        Assert.assertEquals(Boolean.TRUE, result.getFailed());
        Assert.assertTrue(result.getErrorMsg().contains("运行时间预算"));
        Assert.assertEquals(1, port.cancelCount.get());
    }

    @Test(timeout = 2000L)
    public void shouldCancelRemoteSessionWhenDownstreamDisconnects() {
        CapturingRemoteStreamPort port = new CapturingRemoteStreamPort(false);
        AgentContext context = context(port);
        Printer abortedPrinter = Mockito.mock(Printer.class);
        Mockito.when(abortedPrinter.isAborted()).thenReturn(true);
        context.setPrinter(abortedPrinter);
        context.bindCurrentToolArtifactSource(source("code-call", "code_interpreter"));

        CodeInterpreterTool tool = new CodeInterpreterTool();
        tool.setAgentContext(context);
        Object rawResult;
        try {
            rawResult = tool.execute(Map.of("task", "client disconnected"));
        } finally {
            context.clearCurrentToolArtifactSource();
        }

        ToolResultPayload result = (ToolResultPayload) rawResult;
        Assert.assertEquals(Boolean.TRUE, result.getFailed());
        Assert.assertTrue(result.getErrorMsg().contains("客户端连接已断开"));
        Assert.assertEquals(1, port.cancelCount.get());
    }

    private AgentContext context(CapturingRemoteStreamPort port) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "codeInterpreterUrl", "http://reactor-tool.test");
        ReflectionTestUtils.setField(config, "dataAnalysisUrl", "http://reactor-tool.test");
        return AgentContext.builder()
                .requestId("request")
                .sessionId("session")
                .query("query")
                .runtimeDependencies(ReactorRuntimeDependencies.builder()
                        .reactorConfig(config)
                        .remoteStreamPort(port)
                        .build())
                .printer(Mockito.mock(Printer.class))
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .isStream(false)
                .build();
    }

    private ToolArtifactSource source(String toolCallId, String toolName) {
        return ToolArtifactSource.builder()
                .sessionId("session")
                .requestId("request")
                .toolCallId(toolCallId)
                .toolName(toolName)
                .build();
    }

    private void assertTimeouts(RemoteStreamRequest request,
                                long connect,
                                long read,
                                long write,
                                long call) {
        Assert.assertNotNull(request);
        Assert.assertEquals(Long.valueOf(connect), request.getConnectTimeoutSeconds());
        Assert.assertEquals(Long.valueOf(read), request.getReadTimeoutSeconds());
        Assert.assertEquals(Long.valueOf(write), request.getWriteTimeoutSeconds());
        Assert.assertEquals(Long.valueOf(call), request.getCallTimeoutSeconds());
    }

    private static final class CapturingRemoteStreamPort implements RemoteStreamPort {
        private final boolean failImmediately;
        private final AtomicReference<RemoteStreamRequest> lastRequest = new AtomicReference<>();
        private final AtomicInteger cancelCount = new AtomicInteger();

        private CapturingRemoteStreamPort(boolean failImmediately) {
            this.failImmediately = failImmediately;
        }

        @Override
        public RemoteStreamSession openStream(RemoteStreamRequest request,
                                              RemoteStreamListener listener) throws IOException {
            lastRequest.set(request);
            if (failImmediately) {
                listener.onFailure(new IOException("offline fixture"), null, null);
            }
            return cancelCount::incrementAndGet;
        }
    }
}
