package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.ledger.model.ArtifactRecordCommand;
import com.linrun.agent.domain.agent.ledger.model.ConversationHistoryDetail;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunFinishRecord;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunStartRecord;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.reactor.model.response.GptProcessResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话历史 canonical 回放测试。
 * 覆盖 M5 只读诊断依赖的历史回放语义：持久化流事件优先、执行模式与产物样式还原、未知会话返回空。
 */
public class ConversationHistoryReplayServiceTest {

    @Test
    public void shouldReplayDurableStreamEventsInsteadOfProjectedFrames() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        Long runId = seedRun(ctx, "req-replay-001", "session-replay-001", "owner-replay-001",
                ExecutionLedgerConstants.ENTRY_AGENT_LOOP_STANDARD, null);
        ctx.streamEventStore.append("req-replay-001", "message",
                "{\"type\":\"message\",\"runId\":\"" + runId + "\",\"text\":\"canonical-step\"}");
        ctx.streamEventStore.append("req-replay-001", "complete",
                "{\"type\":\"complete\",\"runId\":\"" + runId + "\",\"taskSummary\":\"canonical-summary\"}");

        ConversationHistoryDetail detail = ctx.replayService.queryConversationHistory("session-replay-001");

        Assert.assertNotNull(detail);
        Assert.assertEquals(1, detail.getRuns().size());
        List<GptProcessResult> frames = detail.getRuns().get(0).getReplayFrames();
        List<String> messageTypes = new ArrayList<>();
        frames.forEach(frame -> messageTypes.add(messageType(frame)));
        Assert.assertEquals("message", messageTypes.get(0));
        Assert.assertEquals("complete", messageTypes.get(1));

        // 持久化事件按原样回放，不是从账本重新投影出来的近似帧。
        Map<String, Object> completePayload = payload(frames.get(1));
        Assert.assertEquals("canonical-summary", completePayload.get("taskSummary"));
        Assert.assertTrue(frames.get(1).isFinished());
        Assert.assertFalse(frames.get(0).isFinished());
    }

    @Test
    public void shouldRestoreDeepExecutionModeAndArtifactOutputStyle() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedRun(ctx, "req-replay-deep-001", "session-replay-deep-001", "owner-replay-001",
                ExecutionLedgerConstants.ENTRY_AGENT_LOOP_DEEP, "deep-research-deck.pptx");

        ConversationHistoryDetail detail = ctx.replayService.queryConversationHistory("session-replay-deep-001");

        Assert.assertNotNull(detail);
        Assert.assertEquals("DEEP", detail.getExecutionMode());
        Assert.assertEquals("ppt", detail.getOutputStyle());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_SUCCESS),
                detail.getRuns().get(0).getStatus());
        Assert.assertEquals("summary:req-replay-deep-001", detail.getRuns().get(0).getFinalSummaryText());
    }

    @Test
    public void shouldReturnNullForBlankOrUnknownSession() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        seedRun(ctx, "req-replay-002", "session-replay-002", "owner-replay-002",
                ExecutionLedgerConstants.ENTRY_AGENT_LOOP_STANDARD, null);

        Assert.assertNull(ctx.replayService.queryConversationHistory(""));
        Assert.assertNull(ctx.replayService.queryConversationHistory("session-does-not-exist"));
        Assert.assertNotNull(ctx.replayService.queryConversationHistory("session-replay-002"));
    }

    private Long seedRun(ExecutionLedgerFixtureFactory.LedgerTestContext ctx,
                         String requestId,
                         String sessionId,
                         String ownerId,
                         String entryAgent,
                         String artifactFileName) {
        LocalDateTime now = LocalDateTime.now();
        Long runId = ctx.recorder.createRun(DialogueRunStartRecord.builder()
                .runUid(requestId)
                .requestId(requestId)
                .sessionId(sessionId)
                .ownerId(ownerId)
                .entryAgent(entryAgent)
                .queryText("seed:" + requestId)
                .startedAt(now)
                .build());
        if (artifactFileName != null) {
            ctx.recorder.recordArtifacts(List.of(ArtifactRecordCommand.builder()
                    .runId(runId)
                    .requestId(requestId)
                    .artifactRole(ExecutionLedgerConstants.ARTIFACT_ROLE_OUTPUT)
                    .visibility(ExecutionLedgerConstants.VISIBILITY_VISIBLE)
                    .sourceType(ExecutionLedgerConstants.SOURCE_TYPE_TOOL_OUTPUT)
                    .sourceName("report_tool")
                    .fileName(artifactFileName)
                    .storageKey("oss://" + artifactFileName)
                    .downloadUrl("oss://" + artifactFileName)
                    .previewUrl("oss://" + artifactFileName)
                    .build()));
        }
        ctx.recorder.finishRun(DialogueRunFinishRecord.builder()
                .runId(runId)
                .requestId(requestId)
                .status(ExecutionLedgerConstants.STATUS_SUCCESS)
                .finalSummaryText("summary:" + requestId)
                .finishedAt(now.plusSeconds(3))
                .build());
        return runId;
    }

    private String messageType(GptProcessResult frame) {
        Object messageType = nested(frame).get("messageType");
        return messageType == null ? null : String.valueOf(messageType);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(GptProcessResult frame) {
        Object payload = nested(frame).get("resultMap");
        return payload instanceof Map ? (Map<String, Object>) payload : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nested(GptProcessResult frame) {
        Object eventData = frame.getResultMap().get("eventData");
        if (!(eventData instanceof Map)) {
            return Map.of();
        }
        Object nested = ((Map<String, Object>) eventData).get("resultMap");
        return nested instanceof Map ? (Map<String, Object>) nested : Map.of();
    }
}
