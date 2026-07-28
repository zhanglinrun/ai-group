package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.ledger.model.ArtifactRecordCommand;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationBatchStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationFinishRecord;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.FileToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ReportToolOutput;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolFileRef;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;
import com.linrun.agent.trigger.http.agent.AgentConversationHistoryController;
import com.linrun.agent.trigger.http.agent.vo.ConversationHistoryDetailRespVO;
import com.linrun.agent.trigger.http.agent.vo.ConversationSessionRespVO;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 会话历史接口回归测试。
 */
public class ConversationHistoryControllerTest {

    private static final String TEST_OWNER_ID = "1001";
    private static final String RETIRED_ENTRY_DEEP = "plan_solve";
    private static final String RETIRED_ENTRY_STANDARD = "react";

    private AgentConversationHistoryController wiredController(ExecutionLedgerFixtureFactory.LedgerTestContext ctx) {
        AgentConversationHistoryController controller = new AgentConversationHistoryController();
        ReflectionTestUtils.setField(controller, "executionLedgerQueryService", ctx.queryService);
        ReflectionTestUtils.setField(controller, "conversationHistoryReplayService", ctx.replayService);
        ReflectionTestUtils.setField(controller, "conversationSessionOwnershipService",
                new ConversationSessionOwnershipService(ctx.readRepository, ctx.writeRepository));
        return controller;
    }

    private <T> T asOwnerReturn(java.util.concurrent.Callable<T> action) {
        OwnerRequestContext.bind(Long.parseLong(TEST_OWNER_ID));
        try {
            return action.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            OwnerRequestContext.clear();
        }
    }
    @Test
    public void shouldReturnSessionDetailWithStatsAndReplayFrames() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedRun(ctx, "req-history-001", "session-history-001", "file_tool",
                "先分析项目风险", LocalDateTime.of(2026, 5, 2, 10, 0, 0),
                ExecutionLedgerConstants.STATUS_SUCCESS, "summary:req-history-001", "report-1.md");
        seedRun(ctx, "req-history-002", "session-history-001", "read_tool",
                "继续补充方案", LocalDateTime.of(2026, 5, 2, 10, 5, 0),
                ExecutionLedgerConstants.STATUS_FAILED, "summary:req-history-002", null);

        AgentConversationHistoryController controller = wiredController(ctx);

        Response<ConversationHistoryDetailRespVO> response = asOwnerReturn(() -> controller.detail("session-history-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        ConversationHistoryDetailRespVO detail = response.getData();
        Assert.assertEquals("session-history-001", detail.getSessionId());
        Assert.assertEquals("FAILED", detail.getStatus());
        Assert.assertEquals(Integer.valueOf(2), detail.getRunCount());
        Assert.assertEquals(Integer.valueOf(1), detail.getFinishedRunCount());
        Assert.assertEquals(Integer.valueOf(1), detail.getFailedRunCount());
        Assert.assertNotNull(detail.getRole());
        Assert.assertEquals("默认助手", detail.getRole().getAgentName());
        Assert.assertEquals(2, detail.getRuns().size());
        Assert.assertFalse(detail.getRuns().get(0).getReplayFrames().isEmpty());

        List<GptProcessResult> secondRunFrames = detail.getRuns().get(1).getReplayFrames();
        Assert.assertFalse(secondRunFrames.isEmpty());
        Map<String, Object> finalResultMap = nestedResultMap(secondRunFrames.get(secondRunFrames.size() - 1));
        Assert.assertEquals("result", finalResultMap.get("messageType"));
        Assert.assertEquals("summary:req-history-002", finalResultMap.get("result"));
    }

    @Test
    public void shouldReplayLatestPersistedFixedRoleAndKeepLegacyFallback() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        Long runId = ctx.recorder.createRun(DialogueRunStartRecord.builder()
                .runUid("req-history-role-001")
                .requestId("req-history-role-001")
                .sessionId("session-history-role-001")
                .ownerId(TEST_OWNER_ID)
                .entryAgent(ExecutionLedgerConstants.ENTRY_AGENT_LOOP_DEEP)
                .roleAgentId("role-researcher-001")
                .roleAgentName("深度研究助手")
                .queryText("对比三款 Agent 产品")
                .startedAt(LocalDateTime.of(2026, 5, 2, 10, 10, 0))
                .build());
        ctx.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(runId)
                .requestId("req-history-role-001")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("对比完成")
                .finishedAt(LocalDateTime.of(2026, 5, 2, 10, 11, 0))
                .build());

        Response<ConversationHistoryDetailRespVO> response = asOwnerReturn(() ->
                wiredController(ctx).detail("session-history-role-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals("role-researcher-001", response.getData().getRole().getAgentId());
        Assert.assertEquals("深度研究助手", response.getData().getRole().getAgentName());
        Assert.assertFalse(response.getData().getRole().isDefaultRole());
        Assert.assertEquals("DEEP", response.getData().getExecutionMode());
    }

    @Test
    public void shouldReturnRecentSessionsOrderedByLastActiveAt() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedRun(ctx, "req-list-001", "session-list-001", "file_tool",
                "第一个会话", LocalDateTime.of(2026, 5, 2, 9, 0, 0),
                ExecutionLedgerConstants.STATUS_SUCCESS, "summary:req-list-001", "report-list-001.md");
        seedRun(ctx, "req-list-002", "session-list-002", "read_tool",
                "第二个会话", LocalDateTime.of(2026, 5, 2, 9, 30, 0),
                ExecutionLedgerConstants.STATUS_FAILED, "summary:req-list-002", null);

        AgentConversationHistoryController controller = wiredController(ctx);

        Response<List<ConversationSessionRespVO>> response = asOwnerReturn(() -> controller.list(20));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals(2, response.getData().size());
        Assert.assertEquals("session-list-002", response.getData().get(0).getSessionId());
        Assert.assertEquals("FAILED", response.getData().get(0).getStatus());
        Assert.assertEquals("第二个会话", response.getData().get(0).getLatestQueryText());
        Assert.assertEquals("session-list-001", response.getData().get(1).getSessionId());
        Assert.assertEquals("SUCCESS", response.getData().get(1).getStatus());
    }

    @Test
    public void shouldRestoreStructuredStandardModeFromHistoryDetail() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        String fileName = "standard-loop-history-report.html";
        seedRun(
                ctx,
                "req-standard-loop-001",
                "session-standard-loop-001",
                ExecutionLedgerConstants.ENTRY_AGENT_LOOP_STANDARD,
                "report_tool",
                "帮我输出网页报告",
                LocalDateTime.of(2026, 5, 2, 11, 0, 0),
                ExecutionLedgerConstants.STATUS_SUCCESS,
                "summary:req-standard-loop-001",
                ReportToolOutput.builder()
                        .fileType("html")
                        .content("<html><body>网页报告</body></html>")
                        .fileRefs(List.of(buildFileRef(fileName)))
                        .build(),
                fileName
        );

        AgentConversationHistoryController controller = wiredController(ctx);

        Response<ConversationHistoryDetailRespVO> response = asOwnerReturn(() -> controller.detail("session-standard-loop-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals("html", response.getData().getOutputStyle());
        Assert.assertEquals("STANDARD", response.getData().getExecutionMode());
    }

    @Test
    public void shouldRestoreDeepAgentLoopModeFromHistoryDetail() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        String fileName = "deep-loop-history-report.html";
        seedRun(
                ctx,
                "req-deep-loop-001",
                "session-deep-loop-001",
                ExecutionLedgerConstants.ENTRY_AGENT_LOOP_DEEP,
                "report_tool",
                "帮我做深度研究并输出网页报告",
                LocalDateTime.of(2026, 5, 2, 11, 30, 0),
                ExecutionLedgerConstants.STATUS_SUCCESS,
                "summary:req-deep-loop-001",
                ReportToolOutput.builder()
                        .fileType("html")
                        .content("<html><body>深度研究报告</body></html>")
                        .fileRefs(List.of(buildFileRef(fileName)))
                        .build(),
                fileName
        );

        AgentConversationHistoryController controller = wiredController(ctx);

        Response<ConversationHistoryDetailRespVO> response = asOwnerReturn(() -> controller.detail("session-deep-loop-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals("html", response.getData().getOutputStyle());
        Assert.assertEquals("DEEP", response.getData().getExecutionMode());
    }

    @Test
    public void shouldReplayCanonicalDeepResearchReportFramesFromHistoryDetail() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        Long runId = ctx.recorder.createRun(DialogueRunStartRecord.builder()
                .runUid("req-history-deep-report-001")
                .requestId("req-history-deep-report-001")
                .sessionId("session-history-deep-report-001")
                .ownerId(TEST_OWNER_ID)
                .entryAgent(ExecutionLedgerConstants.ENTRY_AGENT_LOOP_DEEP)
                .queryText("deep research replay")
                .startedAt(LocalDateTime.of(2026, 5, 2, 11, 35, 0))
                .build());
        ctx.streamEventStore.append(
                "req-history-deep-report-001",
                "stage_output",
                """
                {"type":"stage_output","runId":"req-history-deep-report-001","toolCallId":null,"outputType":"deep_research_report","payload":{"qualityStatus":"PASSED","sourceCount":20,"charCount":15000,"previewMarkdown":"# report","reportArtifactId":"artifact-md"},"artifactRefs":[{"resourceKey":"artifact-md","displayName":"report.md","downloadUrl":"https://file.example.com/report.md","previewUrl":"https://file.example.com/report.md"}],"isFinal":true}
                """);
        ctx.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(runId)
                .requestId("req-history-deep-report-001")
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("deep report ready")
                .finishedAt(LocalDateTime.of(2026, 5, 2, 11, 36, 0))
                .build());

        Response<ConversationHistoryDetailRespVO> response = asOwnerReturn(() ->
                wiredController(ctx).detail("session-history-deep-report-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals("DEEP", response.getData().getExecutionMode());
        Assert.assertEquals("docs", response.getData().getOutputStyle());
        List<GptProcessResult> frames = response.getData().getRuns().get(0).getReplayFrames();
        Assert.assertTrue(frames.stream()
                .map(this::eventData)
                .anyMatch(eventData -> "deep_research_report".equals(
                        ((Map<?, ?>) eventData.get("resultMap")).get("messageType"))));
    }

    @Test
    public void shouldKeepRetiredRuntimeLedgerRowsReadableAfterAgentLoopMigration() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        String legacyReport = "legacy-plan-report.md";
        seedRun(
                ctx,
                "req-legacy-plan-001",
                "session-legacy-plan-001",
                RETIRED_ENTRY_DEEP,
                "report_tool",
                "读取旧 Plan-Solve 历史",
                LocalDateTime.of(2026, 5, 2, 11, 40, 0),
                ExecutionLedgerConstants.STATUS_SUCCESS,
                "summary:req-legacy-plan-001",
                ReportToolOutput.builder()
                        .fileType("markdown")
                        .content("# 历史报告")
                        .fileRefs(List.of(buildFileRef(legacyReport)))
                        .build(),
                legacyReport
        );
        seedRun(
                ctx,
                "req-legacy-react-001",
                "session-legacy-react-001",
                RETIRED_ENTRY_STANDARD,
                "read_tool",
                "读取旧 ReAct 历史",
                LocalDateTime.of(2026, 5, 2, 11, 45, 0),
                ExecutionLedgerConstants.STATUS_SUCCESS,
                "summary:req-legacy-react-001",
                null,
                null
        );

        AgentConversationHistoryController controller = wiredController(ctx);
        Response<ConversationHistoryDetailRespVO> legacyPlan = asOwnerReturn(
                () -> controller.detail("session-legacy-plan-001"));
        Response<ConversationHistoryDetailRespVO> legacyReact = asOwnerReturn(
                () -> controller.detail("session-legacy-react-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), legacyPlan.getCode());
        Assert.assertNotNull(legacyPlan.getData());
        Assert.assertEquals("docs", legacyPlan.getData().getOutputStyle());
        Assert.assertEquals("DEEP", legacyPlan.getData().getExecutionMode());
        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), legacyReact.getCode());
        Assert.assertNotNull(legacyReact.getData());
        Assert.assertEquals("chat", legacyReact.getData().getOutputStyle());
        Assert.assertEquals("STANDARD", legacyReact.getData().getExecutionMode());
    }

    @Test
    public void shouldLimitRecentSessionsToTwentyAndKeepSummaryOutOfListPayload() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        for (int index = 1; index <= 25; index += 1) {
            seedRun(
                    ctx,
                    String.format("req-limit-%03d", index),
                    String.format("session-limit-%03d", index),
                    index % 2 == 0 ? "file_tool" : "read_tool",
                    String.format("最近会话 %03d", index),
                    LocalDateTime.of(2026, 5, 2, 8, 0, 0).plusMinutes(index),
                    index % 3 == 0 ? ExecutionLedgerConstants.STATUS_FAILED : ExecutionLedgerConstants.STATUS_SUCCESS,
                    "summary-body-" + index,
                    index % 2 == 0 ? "report-limit-" + index + ".md" : null
            );
        }

        AgentConversationHistoryController controller = wiredController(ctx);

        Response<List<ConversationSessionRespVO>> response = asOwnerReturn(() -> controller.list(null));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals(20, response.getData().size());
        Assert.assertEquals("session-limit-025", response.getData().get(0).getSessionId());
        Assert.assertEquals("最近会话 025", response.getData().get(0).getLatestQueryText());
        Assert.assertEquals("session-limit-006", response.getData().get(19).getSessionId());
    }

    @Test
    public void shouldExposeMissingArtifactReasonAndStoppedRunStatus() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedRun(ctx, "req-history-stop-001", "session-history-stop-001", "file_tool",
                "停止前先生成文件", LocalDateTime.of(2026, 5, 2, 12, 0, 0),
                ExecutionLedgerConstants.STATUS_STOPPED, "summary:req-history-stop-001", "stopped-report.md");

        AgentConversationHistoryController controller = wiredController(ctx);

        Response<ConversationHistoryDetailRespVO> response = asOwnerReturn(() -> controller.detail("session-history-stop-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertNotNull(response.getData());
        Assert.assertEquals("STOPPED", response.getData().getStatus());
        Assert.assertEquals("STOPPED", response.getData().getRuns().get(0).getStatus());
        List<GptProcessResult> replayFrames = response.getData().getRuns().get(0).getReplayFrames();
        Assert.assertFalse(replayFrames.isEmpty());
        Map<String, Object> artifactEventData = replayFrames.stream()
                .map(this::eventData)
                .filter(eventData -> eventData.containsKey("artifactRefs"))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> artifactRefs = (List<Map<String, Object>>) artifactEventData.get("artifactRefs");
        Assert.assertEquals(1, artifactRefs.size());
        Assert.assertEquals(Boolean.FALSE, artifactRefs.get(0).get("missing"));
    }

    @Test
    public void shouldReturnNullDetailWhenSessionMissing() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        AgentConversationHistoryController controller = wiredController(ctx);

        Response<ConversationHistoryDetailRespVO> response = asOwnerReturn(() -> controller.detail("session-missing-001"));

        Assert.assertEquals(ResponseCode.UN_ERROR.getCode(), response.getCode());
    }

    @Test
    public void shouldSoftDeleteOwnedSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedRun(ctx, "req-delete-001", "session-delete-001", "read_tool",
                "待删除会话", LocalDateTime.of(2026, 5, 2, 13, 0, 0),
                ExecutionLedgerConstants.STATUS_SUCCESS, "summary:req-delete-001", null);
        AgentConversationHistoryController controller = wiredController(ctx);

        Response<Boolean> response = asOwnerReturn(() -> controller.delete("session-delete-001"));

        Assert.assertEquals(ResponseCode.SUCCESS.getCode(), response.getCode());
        Assert.assertEquals(Boolean.TRUE, response.getData());
        Assert.assertNull(ctx.readRepository.querySessionEntity("session-delete-001"));
    }

    @Test
    public void shouldRejectDeletingAnotherOwnersSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        new ConversationSessionOwnershipService(ctx.readRepository, ctx.writeRepository)
                .ensureSessionAccessible("2002", "session-delete-other", "其他用户会话");
        AgentConversationHistoryController controller = wiredController(ctx);

        Response<Boolean> response = asOwnerReturn(() -> controller.delete("session-delete-other"));

        Assert.assertEquals(ResponseCode.UN_ERROR.getCode(), response.getCode());
        Assert.assertEquals(Boolean.FALSE, response.getData());
        Assert.assertNotNull(ctx.readRepository.querySessionEntity("session-delete-other"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedResultMap(GptProcessResult frame) {
        Map<String, Object> eventData = (Map<String, Object>) frame.getResultMap().get("eventData");
        return (Map<String, Object>) eventData.get("resultMap");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> eventData(GptProcessResult frame) {
        return (Map<String, Object>) frame.getResultMap().get("eventData");
    }

    private void seedRun(ExecutionLedgerFixtureFactory.LedgerTestContext ctx,
                         String requestId,
                         String sessionId,
                         String toolName,
                         String queryText,
                         LocalDateTime startedAt,
                         Integer runStatus,
                         String finalSummaryText,
                         String fileName) {
        ToolStructuredOutput structuredOutput = "file_tool".equals(toolName)
                ? FileToolOutput.builder()
                .command("upload")
                .primaryFileName(fileName)
                .fileRefs(fileName == null ? List.of() : List.of(buildFileRef(fileName)))
                .build()
                : null;
        seedRun(
                ctx,
                requestId,
                sessionId,
                ExecutionLedgerConstants.ENTRY_AGENT_LOOP_STANDARD,
                toolName,
                queryText,
                startedAt,
                runStatus,
                finalSummaryText,
                structuredOutput,
                fileName
        );
    }

    private void seedRun(ExecutionLedgerFixtureFactory.LedgerTestContext ctx,
                         String requestId,
                         String sessionId,
                         String entryAgent,
                         String toolName,
                         String queryText,
                         LocalDateTime startedAt,
                         Integer runStatus,
                         String finalSummaryText,
                         ToolStructuredOutput structuredOutput,
                         String fileName) {
        Long runId = ctx.recorder.createRun(DialogueRunStartRecord.builder()
                .runUid(requestId)
                .requestId(requestId)
                .sessionId(sessionId)
                .ownerId(TEST_OWNER_ID)
                .entryAgent(entryAgent)
                .queryText(queryText)
                .startedAt(startedAt)
                .build());

        Map<String, Long> toolIds = ctx.recorder.createToolInvocations(ToolInvocationBatchStartRecord.builder()
                .runId(runId)
                .requestId(requestId)
                .llmInvocationId(Math.abs((long) requestId.hashCode()))
                .agentName(RETIRED_ENTRY_STANDARD)
                .stepNo(1)
                .items(List.of(ToolInvocationBatchStartRecord.Item.builder()
                        .toolCallId(requestId + "-tool-1")
                        .dispatchIndex(1)
                        .toolName(toolName)
                        .toolProvider(ExecutionLedgerConstants.TOOL_PROVIDER_LOCAL)
                        .inputJson("{\"requestId\":\"" + requestId + "\"}")
                        .startedAt(startedAt.plusSeconds(1))
                        .build()))
                .build());
        Long toolInvocationId = toolIds.get(requestId + "-tool-1");
        ctx.recorder.finishToolInvocation(ToolInvocationFinishRecord.builder()
                .toolInvocationId(toolInvocationId)
                .runId(runId)
                .requestId(requestId)
                .sessionId(sessionId)
                .toolCallId(requestId + "-tool-1")
                .toolName(toolName)
                .status(runStatus)
                .llmObservation(runStatus != null && runStatus == ExecutionLedgerConstants.STATUS_SUCCESS ? "done" : "failed")
                .errorMsg(runStatus != null && runStatus == ExecutionLedgerConstants.STATUS_SUCCESS ? null : "tool_failed")
                .structuredOutput(structuredOutput)
                .finishedAt(startedAt.plusSeconds(2))
                .build());

        if (fileName != null) {
            ctx.recorder.recordArtifacts(List.of(ArtifactRecordCommand.builder()
                    .runId(runId)
                    .requestId(requestId)
                    .toolInvocationId(toolInvocationId)
                    .toolCallId(requestId + "-tool-1")
                    .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT)
                    .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                    .sourceType(ExecutionLedgerConstants.SOURCE_TYPE_TOOL_OUTPUT)
                    .sourceName(toolName)
                    .fileName(fileName)
                    .storageKey("oss://" + fileName)
                    .downloadUrl("https://file.example.com/download/" + fileName)
                    .previewUrl("https://file.example.com/preview/" + fileName)
                    .build()));
        }

        ctx.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(runId)
                .requestId(requestId)
                .status(runStatus)
                .finalSummaryText(finalSummaryText)
                .errorMsg(runStatus != null && runStatus == ExecutionLedgerConstants.STATUS_SUCCESS ? null : "run_failed")
                .finishedAt(startedAt.plusSeconds(3))
                .build());
    }

    private ToolFileRef buildFileRef(String fileName) {
        return ToolFileRef.builder()
                .fileName(fileName)
                .ossUrl("oss://" + fileName)
                .downloadUrl("https://file.example.com/download/" + fileName)
                .previewUrl("https://file.example.com/preview/" + fileName)
                .build();
    }

}
