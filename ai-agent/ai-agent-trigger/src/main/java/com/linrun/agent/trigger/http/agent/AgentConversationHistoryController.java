package com.linrun.agent.trigger.http.agent;

import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.domain.agent.model.valobj.ConversationRoleVO;
import com.linrun.agent.domain.agent.ledger.model.ConversationHistoryDetail;
import com.linrun.agent.domain.agent.ledger.model.DialogueSessionView;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.replay.ConversationHistoryReplayService;
import com.linrun.agent.trigger.http.agent.vo.ConversationHistoryDetailRespVO;
import com.linrun.agent.trigger.http.agent.vo.ConversationRoleRespVO;
import com.linrun.agent.trigger.http.agent.vo.ConversationSessionRespVO;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话历史恢复接口。
 */
@RestController
@RequestMapping("/api/agent/conversation/sessions")
public class AgentConversationHistoryController {

    @Resource
    private ExecutionLedgerQueryService executionLedgerQueryService;

    @Resource
    private ConversationHistoryReplayService conversationHistoryReplayService;

    @Resource
    private ConversationSessionOwnershipService conversationSessionOwnershipService;

    @GetMapping
    public Response<List<ConversationSessionRespVO>> list(
            @RequestParam(name = "limit", defaultValue = "20") Integer limit) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        List<ConversationSessionRespVO> sessions = executionLedgerQueryService.queryRecentSessions(ownerId, limit == null ? 20 : limit)
                .stream()
                .map(this::toSessionRespVO)
                .collect(Collectors.toList());

        return Response.<List<ConversationSessionRespVO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(sessions)
                .build();
    }

    @GetMapping("/{sessionId}")
    public Response<ConversationHistoryDetailRespVO> detail(@PathVariable("sessionId") String sessionId) {
        try {
            conversationSessionOwnershipService.ensureExistingSessionAccessible(
                    OwnerRequestContext.requireOwnerIdAsString(),
                    sessionId
            );
        } catch (Exception e) {
            return Response.<ConversationHistoryDetailRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .build();
        }
        ConversationHistoryDetail detail = conversationHistoryReplayService.queryConversationHistory(sessionId);
        return Response.<ConversationHistoryDetailRespVO>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(toDetailRespVO(detail))
                .build();
    }

    @DeleteMapping("/{sessionId}")
    public Response<Boolean> delete(@PathVariable("sessionId") String sessionId) {
        try {
            conversationSessionOwnershipService.deleteSession(
                    OwnerRequestContext.requireOwnerIdAsString(),
                    sessionId
            );
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(Boolean.TRUE)
                    .build();
        } catch (Exception e) {
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(e.getMessage())
                    .data(Boolean.FALSE)
                    .build();
        }
    }

    private ConversationSessionRespVO toSessionRespVO(DialogueSessionView session) {
        if (session == null) {
            return null;
        }
        return ConversationSessionRespVO.builder()
                .sessionId(session.getSessionId())
                .title(session.getTitle())
                .status(resolveStatusLabel(session.getStatus()))
                .latestQueryText(session.getLatestQueryText())
                .runCount(session.getRunCount())
                .finishedRunCount(session.getFinishedRunCount())
                .failedRunCount(session.getFailedRunCount())
                .startedAt(session.getStartedAt())
                .lastActiveAt(session.getLastActiveAt())
                .build();
    }

    private ConversationHistoryDetailRespVO toDetailRespVO(ConversationHistoryDetail detail) {
        if (detail == null) {
            return null;
        }
        List<ConversationHistoryDetailRespVO.RunDetailRespVO> runs = CollectionUtils.isEmpty(detail.getRuns())
                ? List.of()
                : detail.getRuns().stream()
                .map(run -> ConversationHistoryDetailRespVO.RunDetailRespVO.builder()
                        .requestId(run.getRequestId())
                        .status(resolveStatusLabel(run.getStatus()))
                        .queryText(run.getQueryText())
                        .finalSummaryText(run.getFinalSummaryText())
                        .startedAt(run.getStartedAt())
                        .finishedAt(run.getFinishedAt())
                        .modelName(run.getModelName())
                        .totalTokens(run.getTotalTokens())
                        .durationMs(run.getDurationMs())
                        .replayFrames(run.getReplayFrames() == null ? List.of() : run.getReplayFrames())
                        .build())
                .collect(Collectors.toList());

        return ConversationHistoryDetailRespVO.builder()
                .sessionId(detail.getSessionId())
                .title(detail.getTitle())
                .status(resolveStatusLabel(detail.getStatus()))
                .outputStyle(detail.getOutputStyle())
                .executionMode(detail.getExecutionMode())
                .role(toRoleRespVO(detail.getRole()))
                .runCount(detail.getRunCount())
                .finishedRunCount(detail.getFinishedRunCount())
                .failedRunCount(detail.getFailedRunCount())
                .startedAt(detail.getStartedAt())
                .lastActiveAt(detail.getLastActiveAt())
                .runs(runs)
                .build();
    }

    private ConversationRoleRespVO toRoleRespVO(ConversationRoleVO role) {
        if (role == null) {
            return null;
        }
        return ConversationRoleRespVO.builder()
                .agentId(role.getAgentId())
                .agentName(role.getAgentName())
                .available(role.isAvailable())
                .defaultRole(role.isDefaultRole())
                .build();
    }

    /**
     * 对外接口统一返回可读终态，避免前端和调试工具重复维护状态枚举映射。
     */
    private String resolveStatusLabel(Integer status) {
        int normalized = status == null ? ExecutionLedgerConstants.STATUS_RUNNING : status;
        return switch (normalized) {
            case ExecutionLedgerConstants.STATUS_SUCCESS -> "SUCCESS";
            case ExecutionLedgerConstants.STATUS_FAILED -> "FAILED";
            case ExecutionLedgerConstants.STATUS_TIMEOUT -> "TIMEOUT";
            case ExecutionLedgerConstants.STATUS_STOPPED -> "STOPPED";
            default -> "RUNNING";
        };
    }
}
