package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DeepSearchStage;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DeepSearchToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.FileToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.TodoWriteToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ReportToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputView;

import java.util.List;

/**
 * 输出表 reader 契约测试。
 */
public class ToolStructuredOutputReaderTest {

    @Test
    public void shouldReadByInvocationIdAndDirectLookup() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .toolInvocationId(301L)
                .runId(401L)
                .requestId("req-reader-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId("session-reader-001")
                .toolCallId("tool-call-reader-001")
                .toolName("deep_search")
                .status(ExecutionLedgerConstants.STATUS_FAILED)
                .errorMsg("timeout")
                .structuredOutput(DeepSearchToolOutput.of(
                        "AI 芯片供应链",
                        "timeout",
                        List.of(DeepSearchStage.extend(List.of("AI 芯片供应链")))
                ))
                .build());

        DeepSearchToolOutput byInvocation = (DeepSearchToolOutput) ctx.toolOutputReader
                .readByInvocationId("deep_search", 301L)
                .orElseThrow();
        ToolOutputView direct = ctx.toolOutputReader.readDirect("req-reader-001", "tool-call-reader-001")
                .orElseThrow();

        Assert.assertEquals("AI 芯片供应链", byInvocation.getQuery());
        Assert.assertEquals(1, byInvocation.getStages().size());
        Assert.assertEquals("extend", byInvocation.getStages().get(0).getStage());
        Assert.assertEquals("deep_search", direct.getToolName());
        Assert.assertEquals(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT, direct.getRequestSource());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED), direct.getStatus());
    }

    @Test
    public void shouldReturnEmptyWhenDirectLookupHitsMultipleToolTables() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .requestId("req-reader-conflict-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .toolCallId("tool-call-conflict-001")
                .toolName("file_tool")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(FileToolOutput.builder()
                        .command("upload")
                        .primaryFileName("conflict-a.md")
                        .build())
                .build());
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .requestId("req-reader-conflict-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .toolCallId("tool-call-conflict-001")
                .toolName("report_tool")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(ReportToolOutput.builder()
                        .summary("conflict")
                        .content("conflict")
                        .build())
                .build());

        Assert.assertTrue(ctx.toolOutputReader.readDirect("req-reader-conflict-001", "tool-call-conflict-001").isEmpty());
    }

    @Test
    public void shouldReadFileToolPreviewUrlByInvocationIdAndDirectLookup() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .toolInvocationId(501L)
                .runId(601L)
                .requestId("req-reader-file-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId("session-reader-file-001")
                .toolCallId("tool-call-reader-file-001")
                .toolName("file_tool")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(FileToolOutput.builder()
                        .command("get")
                        .primaryFileName("风险日报.md")
                        .previewUrl("https://file.example.com/preview/risk.md")
                        .downloadUrl("https://file.example.com/download/risk.md")
                        .build())
                .build());

        FileToolOutput byInvocation = (FileToolOutput) ctx.toolOutputReader
                .readByInvocationId("file_tool", 501L)
                .orElseThrow();
        ToolOutputView direct = ctx.toolOutputReader
                .readDirect("req-reader-file-001", "tool-call-reader-file-001")
                .orElseThrow();
        FileToolOutput directOutput = (FileToolOutput) direct.getStructuredOutput();

        Assert.assertEquals("风险日报.md", byInvocation.getPrimaryFileName());
        Assert.assertEquals("https://file.example.com/preview/risk.md", byInvocation.getPreviewUrl());
        Assert.assertEquals("https://file.example.com/download/risk.md", byInvocation.getDownloadUrl());
        Assert.assertEquals("https://file.example.com/preview/risk.md", directOutput.getPreviewUrl());
        Assert.assertEquals("https://file.example.com/download/risk.md", directOutput.getDownloadUrl());
        Assert.assertEquals("file_tool", direct.getToolName());
        Assert.assertEquals(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT, direct.getRequestSource());
    }

    @Test
    public void shouldReadTodoWriteStructuredOutputByInvocationIdAndDirectLookup() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .toolInvocationId(601L)
                .runId(701L)
                .requestId("req-reader-todo-write-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId("session-reader-todo-write-001")
                .toolCallId("tool-call-reader-todo-write-001")
                .toolName("todo_write")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(TodoWriteToolOutput.builder()
                        .command("update")
                        .beforeTodo(TodoList.builder()
                                .title("旧待办")
                                .steps(List.of("步骤一", "步骤二"))
                                .stepStatus(List.of("completed", "in_progress"))
                                .notes(List.of("已完成", ""))
                                .evidenceRefs(List.of(List.of("tool-call-search-001"), List.of()))
                                .build())
                        .afterTodo(TodoList.builder()
                                .title("新待办")
                                .steps(List.of("步骤一", "新步骤A", "新步骤B"))
                                .stepStatus(List.of("completed", "in_progress", "not_started"))
                                .notes(List.of("已完成", "", ""))
                                .evidenceRefs(List.of(List.of("tool-call-search-001"), List.of(), List.of()))
                                .build())
                        .currentStep("新步骤A")
                        .currentStepIndex(1)
                        .autoAdvanced(true)
                        .autoFinished(false)
                        .build())
                .build());

        TodoWriteToolOutput byInvocation = (TodoWriteToolOutput) ctx.toolOutputReader
                .readByInvocationId("todo_write", 601L)
                .orElseThrow();
        ToolOutputView direct = ctx.toolOutputReader
                .readDirect("req-reader-todo-write-001", "tool-call-reader-todo-write-001")
                .orElseThrow();

        Assert.assertEquals("update", byInvocation.getCommand());
        Assert.assertEquals("新步骤A", byInvocation.getCurrentStep());
        Assert.assertEquals(Integer.valueOf(1), byInvocation.getCurrentStepIndex());
        Assert.assertEquals("新待办", byInvocation.getAfterTodo().getTitle());
        Assert.assertEquals(List.of("tool-call-search-001"),
                byInvocation.getAfterTodo().getEvidenceRefs().get(0));
        Assert.assertEquals(TodoEvidencePolicy.LEGACY,
                byInvocation.getAfterTodo().getEvidencePolicyAt(0));
        Assert.assertNull(byInvocation.getAfterTodo().getStepActivationIdAt(0));
        Assert.assertEquals("todo_write", direct.getToolName());
        Assert.assertEquals(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT, direct.getRequestSource());
    }
}
