package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.runtime.dto.TodoList;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunView;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.LlmInvocationView;
import com.linrun.agent.domain.agent.ledger.model.ArtifactView;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationView;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.ledger.model.replay.ProjectedReplayEvent;
import com.linrun.agent.domain.agent.ledger.model.replay.ReplayFactBundle;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.FileToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.TodoWriteToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRef;
import com.linrun.agent.domain.agent.ledger.replay.ReplayProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.ToolInvocationProjectorRegistry;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.DefaultToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.DeepSearchToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.FileToolInvocationProjector;
import com.linrun.agent.domain.agent.ledger.replay.projector.impl.TodoWriteToolInvocationProjector;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * ReplayProjector 回归。
 * 验证共享回放入口会按 invocation 顺序委托 registry，而不是自己硬编码工具分支。
 */
public class ReplayProjectorTest {

    private static final String RETIRED_LLM_PLANNING = "planning";
    private static final String RETIRED_LLM_EXECUTOR = "executor";
    private static final String RETIRED_LLM_WORKFLOW = "workflow";

    private final ReplayProjector replayProjector = new ReplayProjector(
            new ToolInvocationProjectorRegistry(
                    List.of(
                            new FileToolInvocationProjector(),
                            new TodoWriteToolInvocationProjector(),
                            new DeepSearchToolInvocationProjector(),
                            new DefaultToolInvocationProjector()
                    ),
                    new DefaultToolInvocationProjector()
            )
    );

    @Test
    public void shouldProjectBundleByToolNameAndInvocationOrder() {
        ToolInvocationView fileInvocation = ToolInvocationView.builder()
                .id(1L)
                .toolCallId("tool-call-file-001")
                .toolName("file_tool")
                .structuredOutput(FileToolOutput.builder()
                        .command("get")
                        .primaryFileName("report.md")
                        .fileRefs(List.of(ToolFileRef.builder().fileName("report.md").build()))
                        .build())
                .build();
        ToolInvocationView plainInvocation = ToolInvocationView.builder()
                .id(2L)
                .toolCallId("tool-call-plain-001")
                .toolName("read_tool")
                .llmObservation("hello")
                .build();
        ArtifactView artifact = ArtifactView.builder()
                .toolInvocationId(1L)
                .toolCallId("tool-call-file-001")
                .fileName("report.md")
                .downloadUrl("https://file.example.com/report.md")
                .previewUrl("https://file.example.com/preview/report.md")
                .storageKey("artifact-report")
                .build();

        List<ProjectedReplayEvent> events = replayProjector.projectHistory(ReplayFactBundle.builder()
                .toolInvocations(List.of(fileInvocation, plainInvocation))
                .artifacts(List.of(artifact))
                .build());

        Assert.assertEquals(2, events.size());
        Assert.assertEquals("file", outerMessageType(events.get(0)));
        Assert.assertEquals("tool_result", outerMessageType(events.get(1)));
        Assert.assertNotEquals(events.get(0).getTaskId(), events.get(1).getTaskId());
        Assert.assertEquals("读取文件", nestedResultMap(events.get(0)).get("command"));
        Assert.assertEquals("hello", toolResult(events.get(1)).get("toolResult"));
    }

    @Test
    public void shouldReplayUserFacingToolResultInsteadOfLlmObservation() {
        ToolInvocationView invocation = ToolInvocationView.builder()
                .id(3L)
                .toolCallId("tool-call-platform-001")
                .toolName("platform_context")
                .toolResult("{\"operation\":\"orders\",\"status\":\"COMPLETE\"}")
                .llmObservation("platform_context status=COMPLETE\n{\"operation\":\"orders\"}")
                .build();

        List<ProjectedReplayEvent> events = replayProjector.projectHistory(ReplayFactBundle.builder()
                .toolInvocations(List.of(invocation))
                .build());

        Assert.assertEquals(1, events.size());
        Assert.assertEquals("{\"operation\":\"orders\",\"status\":\"COMPLETE\"}",
                toolResult(events.get(0)).get("toolResult"));
    }

    @Test
    public void shouldSuppressLegacyInternalAgentsAndAppendStoppedSummaryFallback() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 0, 0);
        DialogueRunView run = DialogueRunView.builder()
                .requestId("req-replay-001")
                .status(ExecutionLedgerConstants.STATUS_STOPPED)
                .finalSummaryText("已按用户要求停止，并保留当前结论")
                .errorCode("DOWNSTREAM_ABORTED")
                .errorMsg("客户端已断开")
                .startedAt(now.minusMinutes(1))
                .finishedAt(now)
                .build();
        LlmInvocationView planningInvocation = LlmInvocationView.builder()
                .invocationSeq(1)
                .agentName(RETIRED_LLM_PLANNING)
                .responseText("先拆分执行计划")
                .finishedAt(now.minusSeconds(30))
                .build();
        LlmInvocationView executorInvocation = LlmInvocationView.builder()
                .invocationSeq(2)
                .agentName(RETIRED_LLM_EXECUTOR)
                .responseText("准备执行搜索工具")
                .finishedAt(now.minusSeconds(20))
                .build();

        List<GptProcessResult> frames = replayProjector.projectHistoryFrames(ReplayFactBundle.builder()
                .run(run)
                .llmInvocations(List.of(planningInvocation, executorInvocation))
                .build());

        Assert.assertEquals(3, frames.size());
        Assert.assertEquals("run_started", frameResultMap(frames.get(0)).get("messageType"));
        Assert.assertEquals("run_finished", frameResultMap(frames.get(1)).get("messageType"));
        Assert.assertEquals("STOPPED", frameResultMap(frames.get(1)).get("runStatus"));
        Assert.assertEquals(Boolean.FALSE, frameResultMap(frames.get(1)).get("completionGatePassed"));
        Assert.assertEquals("DOWNSTREAM_ABORTED", frameResultMap(frames.get(1)).get("stopReason"));
        Assert.assertEquals("result", frameResultMap(frames.get(2)).get("messageType"));
        Assert.assertEquals("STOPPED", frameResultMap(frames.get(2)).get("runStatus"));
        Assert.assertEquals("已按用户要求停止，并保留当前结论", frameResultMap(frames.get(2)).get("result"));
    }

    @Test
    public void shouldIgnoreInternalDigitalEmployeeAskDuringReplayProjection() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 10, 0);
        LlmInvocationView internalDigitalEmployeeInvocation = LlmInvocationView.builder()
                .invocationSeq(1)
                .agentName(RETIRED_LLM_EXECUTOR)
                .callKind(ExecutionLedgerConstants.CALL_KIND_INTERNAL_DIGITAL_EMPLOYEE)
                .responseText("{\"file_tool\":\"市场洞察专员\"}")
                .finishedAt(now.minusSeconds(5))
                .build();
        LlmInvocationView agentLoopInvocation = LlmInvocationView.builder()
                .invocationSeq(2)
                .agentName(ExecutionLedgerConstants.AGENT_NAME_AGENT_LOOP)
                .callKind(ExecutionLedgerConstants.CALL_KIND_ASK)
                .responseText("准备执行搜索工具")
                .finishedAt(now)
                .build();

        List<GptProcessResult> frames = replayProjector.projectHistoryFrames(ReplayFactBundle.builder()
                .llmInvocations(List.of(internalDigitalEmployeeInvocation, agentLoopInvocation))
                .build());

        Assert.assertEquals(1, frames.size());
        Assert.assertEquals("agent_event", eventMessageType(frames.get(0)));
        Assert.assertEquals("tool_thought", frameResultMap(frames.get(0)).get("messageType"));
        Assert.assertEquals("准备执行搜索工具", frameResultMap(frames.get(0)).get("toolThought"));
    }

    @Test
    public void shouldProjectTodoWriteInvocationAsCanonicalSnapshot() {
        ToolInvocationView todoInvocation = ToolInvocationView.builder()
                .id(10L)
                .toolCallId("tool-call-todo-001")
                .toolName("todo_write")
                .inputJson("{\"command\":\"create\",\"title\":\"调研待办\",\"steps\":[\"执行顺序1. 信息收集：搜集资料\",\"执行顺序2. 输出总结：整理结论\"]}")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finishedAt(LocalDateTime.of(2026, 5, 2, 15, 0, 0))
                .build();

        List<ProjectedReplayEvent> events = replayProjector.projectHistory(ReplayFactBundle.builder()
                .toolInvocations(List.of(todoInvocation))
                .build());

        Assert.assertEquals(1, events.size());
        Assert.assertEquals("agent_event", events.get(0).getMessageType());
        Assert.assertEquals("todo_snapshot", plainResultMap(events.get(0)).get("messageType"));
        Assert.assertEquals("调研待办", plainResultMap(events.get(0)).get("title"));
        Assert.assertEquals("执行顺序1. 信息收集：搜集资料", todoAt(events.get(0), 0).get("title"));
        Assert.assertEquals("in_progress", todoAt(events.get(0), 0).get("status"));
        Assert.assertEquals("not_started", todoAt(events.get(0), 1).get("status"));
    }

    @Test
    public void shouldPreferStructuredTodoWriteOutputOverInputJson() {
        ToolInvocationView todoInvocation = ToolInvocationView.builder()
                .id(11L)
                .toolCallId("tool-call-todo-002")
                .toolName("todo_write")
                .inputJson("{\"command\":\"create\",\"title\":\"旧标题\",\"steps\":[\"旧步骤\"]}")
                .structuredOutput(TodoWriteToolOutput.builder()
                        .command("update")
                        .afterTodo(TodoList.builder()
                                .title("重排后的待办")
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
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finishedAt(LocalDateTime.of(2026, 5, 2, 15, 10, 0))
                .build();

        List<ProjectedReplayEvent> events = replayProjector.projectHistory(ReplayFactBundle.builder()
                .toolInvocations(List.of(todoInvocation))
                .build());

        Assert.assertEquals(1, events.size());
        Assert.assertEquals("agent_event", events.get(0).getMessageType());
        Assert.assertEquals("todo_snapshot", plainResultMap(events.get(0)).get("messageType"));
        Assert.assertEquals("重排后的待办", plainResultMap(events.get(0)).get("title"));
        Assert.assertEquals("completed", todoAt(events.get(0), 0).get("status"));
        Assert.assertEquals(List.of("tool-call-search-001"), todoAt(events.get(0), 0).get("evidenceRefs"));
        Assert.assertEquals("新步骤A", todoAt(events.get(0), 1).get("title"));
        Assert.assertEquals("in_progress", todoAt(events.get(0), 1).get("status"));
    }

    @Test
    public void shouldProjectCompletedTodoSnapshotWithoutPhantomTask() {
        ToolInvocationView todoInvocation = ToolInvocationView.builder()
                .id(12L)
                .toolCallId("tool-call-todo-003")
                .toolName("todo_write")
                .structuredOutput(TodoWriteToolOutput.builder()
                        .command("mark_step")
                        .afterTodo(TodoList.builder()
                                .title("已完成待办")
                                .steps(List.of("步骤一"))
                                .stepStatus(List.of("completed"))
                                .notes(List.of("全部完成"))
                                .evidenceRefs(List.of(List.of("tool-call-report-001")))
                                .build())
                        .currentStep("")
                        .currentStepIndex(null)
                        .autoAdvanced(false)
                        .autoFinished(true)
                        .build())
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finishedAt(LocalDateTime.of(2026, 5, 2, 15, 20, 0))
                .build();

        List<ProjectedReplayEvent> events = replayProjector.projectHistory(ReplayFactBundle.builder()
                .toolInvocations(List.of(todoInvocation))
                .build());

        Assert.assertEquals(1, events.size());
        Assert.assertEquals("agent_event", events.get(0).getMessageType());
        Assert.assertEquals("todo_snapshot", plainResultMap(events.get(0)).get("messageType"));
        Assert.assertEquals("已完成待办", plainResultMap(events.get(0)).get("title"));
        Assert.assertEquals("completed", todoAt(events.get(0), 0).get("status"));
    }

    @Test
    public void shouldKeepLastTodoSnapshotWhenMarkStepIsInvalid() {
        ToolInvocationView createInvocation = ToolInvocationView.builder()
                .id(13L)
                .toolCallId("tool-call-todo-004")
                .toolName("todo_write")
                .inputJson("{\"command\":\"create\",\"title\":\"执行待办\",\"steps\":[\"执行顺序1. 第一步\"]}")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finishedAt(LocalDateTime.of(2026, 5, 2, 15, 30, 0))
                .build();
        ToolInvocationView invalidMarkStepInvocation = ToolInvocationView.builder()
                .id(14L)
                .toolCallId("tool-call-todo-005")
                .toolName("todo_write")
                .inputJson("{\"command\":\"mark_step\",\"step_index\":1,\"step_status\":\"completed\"}")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finishedAt(LocalDateTime.of(2026, 5, 2, 15, 31, 0))
                .build();

        List<ProjectedReplayEvent> events = replayProjector.projectHistory(ReplayFactBundle.builder()
                .toolInvocations(List.of(createInvocation, invalidMarkStepInvocation))
                .build());

        Assert.assertEquals(2, events.size());
        Assert.assertEquals("agent_event", events.get(0).getMessageType());
        Assert.assertEquals("todo_snapshot", plainResultMap(events.get(0)).get("messageType"));
        Assert.assertEquals("agent_event", events.get(1).getMessageType());
        Assert.assertEquals("todo_snapshot", plainResultMap(events.get(1)).get("messageType"));
        Assert.assertEquals("执行待办", plainResultMap(events.get(1)).get("title"));
        Assert.assertEquals("执行顺序1. 第一步", todoAt(events.get(1), 0).get("title"));
        Assert.assertEquals("in_progress", todoAt(events.get(1), 0).get("status"));
    }

    @Test
    public void shouldProjectUnifiedAgentNoToolLlmAsFinalResult() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 16, 0, 0);
        LlmInvocationView agentLoopInvocation = LlmInvocationView.builder()
                .invocationSeq(3)
                .agentName(ExecutionLedgerConstants.AGENT_NAME_AGENT_LOOP)
                .toolCallCount(0)
                .responseText("本轮任务已完成")
                .finishedAt(now)
                .build();

        List<GptProcessResult> frames = replayProjector.projectHistoryFrames(ReplayFactBundle.builder()
                .llmInvocations(List.of(agentLoopInvocation))
                .build());

        Assert.assertEquals(1, frames.size());
        Assert.assertEquals("agent_event", eventMessageType(frames.get(0)));
        Assert.assertEquals("result", frameResultMap(frames.get(0)).get("messageType"));
        Assert.assertEquals("本轮任务已完成", frameResultMap(frames.get(0)).get("result"));
    }

    @Test
    public void shouldProjectLegacyWorkflowAnswerAsSingleResultWithoutSummaryFallbackDuplication() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 12, 0, 0);
        DialogueRunView run = DialogueRunView.builder()
                .requestId("req-workflow-001")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("普通聊天的最终回答")
                .startedAt(now.minusSeconds(2))
                .finishedAt(now)
                .build();
        LlmInvocationView workflowInvocation = LlmInvocationView.builder()
                .id(901L)
                .invocationSeq(1)
                .agentName(RETIRED_LLM_WORKFLOW)
                .toolCallCount(0)
                .responseText("普通聊天的最终回答")
                .finishedAt(now)
                .build();

        List<GptProcessResult> frames = replayProjector.projectHistoryFrames(ReplayFactBundle.builder()
                .run(run)
                .llmInvocations(List.of(workflowInvocation))
                .build());

        Assert.assertEquals(3, frames.size());
        Assert.assertEquals("run_started", frameResultMap(frames.get(0)).get("messageType"));
        Assert.assertEquals("run_finished", frameResultMap(frames.get(1)).get("messageType"));
        Assert.assertEquals("SUCCESS", frameResultMap(frames.get(1)).get("runStatus"));
        Assert.assertEquals(Boolean.TRUE, frameResultMap(frames.get(1)).get("completionGatePassed"));
        Assert.assertEquals("result", frameResultMap(frames.get(2)).get("messageType"));
        Assert.assertEquals("普通聊天的最终回答", frameResultMap(frames.get(2)).get("result"));
    }

    @Test
    public void shouldHideUnifiedLoopToolScratchAndRetainLinkedToolResults() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 17, 0, 0);
        LlmInvocationView firstAgentTurn = LlmInvocationView.builder()
                .id(101L)
                .invocationSeq(1)
                .agentName(ExecutionLedgerConstants.AGENT_NAME_AGENT_LOOP)
                .callKind(ExecutionLedgerConstants.CALL_KIND_ASK_TOOL)
                .toolCallCount(1)
                .responseText("先搜索第一批资料")
                .startedAt(now.minusSeconds(40))
                .finishedAt(now.minusSeconds(35))
                .build();
        LlmInvocationView secondAgentTurn = LlmInvocationView.builder()
                .id(102L)
                .invocationSeq(2)
                .agentName(ExecutionLedgerConstants.AGENT_NAME_AGENT_LOOP)
                .callKind(ExecutionLedgerConstants.CALL_KIND_ASK_TOOL)
                .toolCallCount(1)
                .responseText("再读取第二批资料")
                .startedAt(now.minusSeconds(25))
                .finishedAt(now.minusSeconds(20))
                .build();
        ToolInvocationView firstTool = ToolInvocationView.builder()
                .id(201L)
                .llmInvocationId(101L)
                .toolCallId("tool-call-1")
                .toolName("read_tool")
                .inputJson("{\"query\":\"第一批\"}")
                .llmObservation("第一批结果")
                .startedAt(now.minusSeconds(34))
                .finishedAt(now.minusSeconds(30))
                .build();
        ToolInvocationView secondTool = ToolInvocationView.builder()
                .id(202L)
                .llmInvocationId(102L)
                .toolCallId("tool-call-2")
                .toolName("read_tool")
                .inputJson("{\"query\":\"第二批\"}")
                .llmObservation("第二批结果")
                .startedAt(now.minusSeconds(19))
                .finishedAt(now.minusSeconds(15))
                .build();

        List<GptProcessResult> frames = replayProjector.projectHistoryFrames(ReplayFactBundle.builder()
                .llmInvocations(List.of(firstAgentTurn, secondAgentTurn))
                .toolInvocations(List.of(firstTool, secondTool))
                .build());

        Assert.assertEquals(2, frames.size());
        Assert.assertEquals("tool_result", frameResultMap(frames.get(0)).get("messageType"));
        Assert.assertEquals("tool_result", frameResultMap(frames.get(1)).get("messageType"));
        Assert.assertEquals("第一批结果", frameToolResult(frames.get(0)).get("toolResult"));
        Assert.assertEquals("第二批结果", frameToolResult(frames.get(1)).get("toolResult"));
        Assert.assertNotEquals(eventTaskId(frames.get(0)), eventTaskId(frames.get(1)));
    }

    @Test
    public void shouldParseSummaryLlmResponseAndAttachArtifacts() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 18, 0, 0);
        DialogueRunView run = DialogueRunView.builder()
                .requestId("req-summary-001")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .build();
        LlmInvocationView summaryInvocation = LlmInvocationView.builder()
                .id(301L)
                .invocationSeq(3)
                .agentName("summary")
                .responseText("""
                        最终结论已整理完成
                        $$$
                        call-report-001::report.html、call-check-001::checklist.md
                        """)
                .finishedAt(now)
                .build();
        ArtifactView reportArtifact = ArtifactView.builder()
                .toolInvocationId(401L)
                .toolCallId("call-report-001")
                .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT)
                .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                .fileName("report.html")
                .storageKey("artifact-report-html")
                .downloadUrl("https://file.example.com/report.html")
                .previewUrl("https://file.example.com/preview/report.html")
                .build();
        ArtifactView checklistArtifact = ArtifactView.builder()
                .toolInvocationId(402L)
                .toolCallId("call-check-001")
                .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT)
                .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                .fileName("checklist.md")
                .storageKey("artifact-checklist-md")
                .downloadUrl("https://file.example.com/checklist.md")
                .previewUrl("https://file.example.com/preview/checklist.md")
                .build();

        List<GptProcessResult> frames = replayProjector.projectHistoryFrames(ReplayFactBundle.builder()
                .run(run)
                .llmInvocations(List.of(summaryInvocation))
                .artifacts(List.of(reportArtifact, checklistArtifact))
                .build());

        Assert.assertEquals(3, frames.size());
        Assert.assertEquals("run_started", frameResultMap(frames.get(0)).get("messageType"));
        Assert.assertEquals("run_finished", frameResultMap(frames.get(1)).get("messageType"));
        Assert.assertEquals("result", frameResultMap(frames.get(2)).get("messageType"));
        Assert.assertEquals("最终结论已整理完成", frameResultMap(frames.get(2)).get("result"));
        Assert.assertEquals(2, frameFileList(frames.get(2)).size());
        Assert.assertEquals(2, frameArtifactRefs(frames.get(2)).size());
        Assert.assertEquals("report.html", frameFileList(frames.get(2)).get(0).get("fileName"));
        Assert.assertEquals("artifact-checklist-md", frameArtifactRefs(frames.get(2)).get(1).get("resourceKey"));
    }

    @Test
    public void shouldParseRunSummaryFallbackAndAttachArtifacts() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 19, 0, 0);
        DialogueRunView run = DialogueRunView.builder()
                .requestId("req-summary-fallback-001")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("""
                        请优先查看生成结果
                        $$$
                        call-report-002::result.md
                        """)
                .startedAt(now.minusMinutes(2))
                .finishedAt(now)
                .build();
        ArtifactView resultArtifact = ArtifactView.builder()
                .toolInvocationId(501L)
                .toolCallId("call-report-002")
                .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT)
                .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                .fileName("result.md")
                .storageKey("artifact-result-md")
                .downloadUrl("https://file.example.com/result.md")
                .previewUrl("https://file.example.com/preview/result.md")
                .build();

        List<GptProcessResult> frames = replayProjector.projectHistoryFrames(ReplayFactBundle.builder()
                .run(run)
                .artifacts(List.of(resultArtifact))
                .build());

        Assert.assertEquals(3, frames.size());
        Assert.assertEquals("run_started", frameResultMap(frames.get(0)).get("messageType"));
        Assert.assertEquals("run_finished", frameResultMap(frames.get(1)).get("messageType"));
        Assert.assertEquals("result", frameResultMap(frames.get(2)).get("messageType"));
        Assert.assertEquals("请优先查看生成结果", frameResultMap(frames.get(2)).get("result"));
        Assert.assertEquals(1, frameFileList(frames.get(2)).size());
        Assert.assertEquals(1, frameArtifactRefs(frames.get(2)).size());
        Assert.assertEquals("artifact-result-md", frameArtifactRefs(frames.get(2)).get(0).get("resourceKey"));
    }

    @SuppressWarnings("unchecked")
    private String outerMessageType(ProjectedReplayEvent event) {
        return String.valueOf(((Map<String, Object>) event.getResultMap()).get("messageType"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedResultMap(ProjectedReplayEvent event) {
        return (Map<String, Object>) ((Map<String, Object>) event.getResultMap()).get("resultMap");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> plainResultMap(ProjectedReplayEvent event) {
        return (Map<String, Object>) event.getResultMap();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolResult(ProjectedReplayEvent event) {
        return (Map<String, Object>) ((Map<String, Object>) event.getResultMap()).get("toolResult");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> todoAt(ProjectedReplayEvent event, int index) {
        List<Map<String, Object>> todos = (List<Map<String, Object>>) plainResultMap(event).get("todos");
        return todos.get(index);
    }

    @SuppressWarnings("unchecked")
    private String eventMessageType(GptProcessResult frame) {
        return String.valueOf(((Map<String, Object>) frame.getResultMap().get("eventData")).get("messageType"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> frameResultMap(GptProcessResult frame) {
        return (Map<String, Object>) ((Map<String, Object>) frame.getResultMap().get("eventData")).get("resultMap");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> frameToolResult(GptProcessResult frame) {
        return (Map<String, Object>) frameResultMap(frame).get("toolResult");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> frameFileList(GptProcessResult frame) {
        Object fileList = frameResultMap(frame).get("fileList");
        return fileList instanceof List<?> ? (List<Map<String, Object>>) fileList : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> frameArtifactRefs(GptProcessResult frame) {
        Object artifactRefs = ((Map<String, Object>) frame.getResultMap().get("eventData")).get("artifactRefs");
        return artifactRefs instanceof List<?> ? (List<Map<String, Object>>) artifactRefs : List.of();
    }

    @SuppressWarnings("unchecked")
    private String eventTaskId(GptProcessResult frame) {
        return String.valueOf(((Map<String, Object>) frame.getResultMap().get("eventData")).get("taskId"));
    }

}
