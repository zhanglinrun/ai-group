package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.FileToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ImageGenerationToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.TodoWriteToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRef;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputView;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.runtime.enums.TodoEvidencePolicy;

import java.util.List;

/**
 * 输出表 writer 契约测试。
 */
public class ToolStructuredOutputWriterTest {

    @Test
    public void shouldPersistRichToolOutputAndKeepFirstWriteWins() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ToolOutputPersistCommand first = ToolOutputPersistCommand.builder()
                .toolInvocationId(101L)
                .runId(201L)
                .requestId("req-writer-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId("session-writer-001")
                .toolCallId("tool-call-writer-001")
                .toolName("file_tool")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(FileToolOutput.builder()
                        .command("upload")
                        .primaryFileName("report-a.md")
                        .previewUrl("https://file.example.com/preview/report-a.md")
                        .downloadUrl("https://file.example.com/download/report-a.md")
                        .fileRefs(List.of(ToolFileRef.builder().fileName("report-a.md").build()))
                        .build())
                .build();
        ToolOutputPersistCommand duplicate = ToolOutputPersistCommand.builder()
                .toolInvocationId(101L)
                .runId(201L)
                .requestId("req-writer-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId("session-writer-001")
                .toolCallId("tool-call-writer-001")
                .toolName("file_tool")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(FileToolOutput.builder()
                        .command("upload")
                        .primaryFileName("report-b.md")
                        .previewUrl("https://file.example.com/preview/report-b.md")
                        .downloadUrl("https://file.example.com/download/report-b.md")
                        .fileRefs(List.of(ToolFileRef.builder().fileName("report-b.md").build()))
                        .build())
                .build();

        ctx.toolOutputWriter.write(first);
        ctx.toolOutputWriter.write(duplicate);

        ToolOutputView outputView = ctx.toolOutputReader.readDirect("req-writer-001", "tool-call-writer-001")
                .orElseThrow();
        FileToolOutput structuredOutput = (FileToolOutput) outputView.getStructuredOutput();

        Assert.assertTrue(ctx.toolOutputReader.readByInvocationId("file_tool", 101L).isPresent());
        Assert.assertEquals("file_tool", outputView.getToolName());
        Assert.assertEquals(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT, outputView.getRequestSource());
        Assert.assertEquals("session-writer-001", outputView.getSessionId());
        Assert.assertEquals("report-a.md", structuredOutput.getPrimaryFileName());
        Assert.assertEquals("https://file.example.com/preview/report-a.md", structuredOutput.getPreviewUrl());
        Assert.assertEquals("https://file.example.com/download/report-a.md", structuredOutput.getDownloadUrl());
        Assert.assertEquals(1, structuredOutput.getFileRefs().size());
        Assert.assertEquals("report-a.md", structuredOutput.getFileRefs().get(0).getFileName());
    }

    @Test
    public void shouldSupportDirectToolCallWithoutLedgerFieldsAndKeepFirstWriteWins() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .requestId("req-writer-direct-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_WORKSPACE)
                .toolCallId("tool-call-direct-001")
                .toolName("image_generation_tool")
                .status(ExecutionLedgerConstants.STATUS_FAILED)
                .errorMsg("upstream timeout")
                .structuredOutput(ImageGenerationToolOutput.builder()
                        .prompt("sunrise over lake")
                        .mode("images")
                        .summary("upstream timeout")
                        .build())
                .build());
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .requestId("req-writer-direct-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_WORKSPACE)
                .toolCallId("tool-call-direct-001")
                .toolName("image_generation_tool")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(ImageGenerationToolOutput.builder()
                        .prompt("another prompt")
                        .mode("images")
                        .summary("should be ignored")
                        .build())
                .build());

        ToolOutputView outputView = ctx.toolOutputReader.readDirect("req-writer-direct-001", "tool-call-direct-001")
                .orElseThrow();
        ImageGenerationToolOutput structuredOutput = (ImageGenerationToolOutput) outputView.getStructuredOutput();

        Assert.assertNull(outputView.getSessionId());
        Assert.assertEquals(ExecutionLedgerConstants.REQUEST_SOURCE_WORKSPACE, outputView.getRequestSource());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED), outputView.getStatus());
        Assert.assertEquals("upstream timeout", outputView.getErrorMsg());
        Assert.assertEquals("sunrise over lake", structuredOutput.getPrompt());
        Assert.assertTrue(ctx.toolOutputReader.readByInvocationId("image_generation_tool", null).isEmpty());
    }

    @Test
    public void shouldPersistTodoWriteStructuredOutputWithSnapshots() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .toolInvocationId(901L)
                .runId(902L)
                .requestId("req-writer-todo-write-001")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId("session-writer-todo-write-001")
                .toolCallId("tool-call-writer-todo-write-001")
                .toolName("todo_write")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(TodoWriteToolOutput.builder()
                        .command("mark_step")
                        .beforeTodo(TodoList.builder()
                                .title("执行待办")
                                .steps(List.of("步骤一", "步骤二"))
                                .stepStatus(List.of("in_progress", "not_started"))
                                .notes(List.of("", ""))
                                .evidenceRefs(List.of(List.of(), List.of()))
                                .evidencePolicies(List.of(
                                        TodoEvidencePolicy.TOOL,
                                        TodoEvidencePolicy.TOOL))
                                .stepActivationIds(java.util.Arrays.asList(1L, null))
                                .build())
                        .afterTodo(TodoList.builder()
                                .title("执行待办")
                                .steps(List.of("步骤一", "步骤二"))
                                .stepStatus(List.of("completed", "in_progress"))
                                .notes(List.of("已完成", ""))
                                .evidenceRefs(List.of(List.of("tool-call-search-001"), List.of()))
                                .evidencePolicies(List.of(
                                        TodoEvidencePolicy.TOOL,
                                        TodoEvidencePolicy.TOOL))
                                .stepActivationIds(List.of(1L, 2L))
                                .build())
                        .currentStep("步骤二")
                        .currentStepIndex(1)
                        .autoAdvanced(true)
                        .autoFinished(false)
                        .build())
                .build());

        TodoWriteToolOutput output = (TodoWriteToolOutput) ctx.toolOutputReader
                .readByInvocationId("todo_write", 901L)
                .orElseThrow();
        ToolOutputView direct = ctx.toolOutputReader
                .readDirect("req-writer-todo-write-001", "tool-call-writer-todo-write-001")
                .orElseThrow();

        Assert.assertEquals("mark_step", output.getCommand());
        Assert.assertEquals("步骤二", output.getCurrentStep());
        Assert.assertEquals(Integer.valueOf(1), output.getCurrentStepIndex());
        Assert.assertTrue(output.getAutoAdvanced());
        Assert.assertFalse(output.getAutoFinished());
        Assert.assertEquals(List.of("completed", "in_progress"), output.getAfterTodo().getStepStatus());
        Assert.assertEquals(List.of("tool-call-search-001"), output.getAfterTodo().getEvidenceRefs().get(0));
        Assert.assertEquals(List.of(TodoEvidencePolicy.TOOL, TodoEvidencePolicy.TOOL),
                output.getAfterTodo().getEvidencePolicies());
        Assert.assertEquals(List.of(1L, 2L), output.getAfterTodo().getStepActivationIds());
        Assert.assertEquals("todo_write", direct.getToolName());
    }

    @Test
    public void shouldPersistTodoWriteFinishStructuredOutput() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ctx.toolOutputWriter.write(ToolOutputPersistCommand.builder()
                .toolInvocationId(903L)
                .runId(904L)
                .requestId("req-writer-todo-write-002")
                .requestSource(ExecutionLedgerConstants.REQUEST_SOURCE_AGENT)
                .sessionId("session-writer-todo-write-002")
                .toolCallId("tool-call-writer-todo-write-002")
                .toolName("todo_write")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .structuredOutput(TodoWriteToolOutput.builder()
                        .command("finish")
                        .beforeTodo(TodoList.builder()
                                .title("执行待办")
                                .steps(List.of("步骤一", "步骤二"))
                                .stepStatus(List.of("completed", "in_progress"))
                                .notes(List.of("已完成", "待收口"))
                                .evidenceRefs(List.of(List.of("tool-call-search-001"), List.of()))
                                .build())
                        .afterTodo(TodoList.builder()
                                .title("执行待办")
                                .steps(List.of("步骤一", "步骤二"))
                                .stepStatus(List.of("completed", "completed"))
                                .notes(List.of("已完成", "待收口"))
                                .evidenceRefs(List.of(
                                        List.of("tool-call-search-001"),
                                        List.of("tool-call-report-001")))
                                .build())
                        .currentStep("")
                        .currentStepIndex(null)
                        .autoAdvanced(false)
                        .autoFinished(true)
                        .build())
                .build());

        TodoWriteToolOutput output = (TodoWriteToolOutput) ctx.toolOutputReader
                .readByInvocationId("todo_write", 903L)
                .orElseThrow();

        Assert.assertEquals("finish", output.getCommand());
        Assert.assertTrue(output.getAutoFinished());
        Assert.assertFalse(output.getAutoAdvanced());
        Assert.assertEquals(List.of("completed", "completed"), output.getAfterTodo().getStepStatus());
        Assert.assertEquals(List.of("tool-call-report-001"), output.getAfterTodo().getEvidenceRefs().get(1));
        Assert.assertEquals("", output.getCurrentStep());
        Assert.assertNull(output.getCurrentStepIndex());
    }
}
