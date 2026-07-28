package com.linrun.agent.domain.agent.runtime.deepresearch;

import com.linrun.agent.domain.agent.adapter.port.QuotaInsufficientException;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;
import com.linrun.agent.domain.agent.runtime.AgentLoopFactory;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.AgentLoop;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.enums.AgentType;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.factory.AgentToolCollectionFactory;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AgentLoopResearchBranchExecutor implements ResearchBranchExecutor {

    private final AgentToolCollectionFactory toolCollectionFactory;
    private final AgentLoopFactory agentLoopFactory;

    public AgentLoopResearchBranchExecutor(AgentToolCollectionFactory toolCollectionFactory,
                                           AgentLoopFactory agentLoopFactory) {
        this.toolCollectionFactory = toolCollectionFactory;
        this.agentLoopFactory = agentLoopFactory;
    }

    @Override
    public ResearchBranchResult execute(AgentContext parentContext,
                                        AgentRequest parentRequest,
                                        ResearchPlan plan,
                                        int researcherIndex) {
        long startedAt = System.currentTimeMillis();
        List<String> assignedSections = plan.assignedSections(researcherIndex);
        String researcherId = "researcher_" + researcherIndex;
        String prompt = buildPrompt(parentRequest.getQuery(), plan, researcherIndex, assignedSections);
        AgentContext childContext = parentContext.forkForParallelTask(researcherId);
        childContext.setQuery(prompt);
        childContext.setOutputStyle("markdown");
        childContext.setPrinter(new BranchPrinter(parentContext.getPrinter()));

        AgentRequest childRequest = copyRequest(parentRequest, prompt);
        ToolCollection toolCollection = toolCollectionFactory.buildForUnified(childContext, childRequest);
        childContext.setToolCollection(toolCollection);
        childContext.setToolInvocationContract(ToolInvocationContract.resolve(prompt, activeToolNames(toolCollection)));

        List<ToolExecutionEvidence> before = parentContext.snapshotToolExecutionEvidence();
        AgentLoop agentLoop = agentLoopFactory.create(childContext);
        agentLoop.setRunBudget(agentLoop.getRunBudget()
                .withMaxTurns(4)
                .withMaxDurationMillis(120_000L));
        String markdown = StringUtils.defaultString(agentLoop.run(prompt));
        if (agentLoop.getStopReason() == AgentStopReason.MODEL_ERROR && isQuotaFailure(agentLoop, markdown)) {
            throw new QuotaInsufficientException(quotaFailureMessage(agentLoop, markdown));
        }
        List<String> gaps = new ArrayList<>();
        if (agentLoop.getState() != AgentState.FINISHED) {
            gaps.add("分支未正常完成：" + agentLoop.getStopReason());
        }
        List<ResearchEvidencePacket> evidence = evidenceProducedAfter(before, parentContext.snapshotToolExecutionEvidence());
        return new ResearchBranchResult(
                researcherId,
                assignedSections,
                markdown,
                evidence,
                List.of(),
                gaps,
                startedAt,
                System.currentTimeMillis()
        );
    }

    private AgentRequest copyRequest(AgentRequest parentRequest, String prompt) {
        return AgentRequest.builder()
                .requestId(parentRequest.getRequestId())
                .sessionId(parentRequest.getSessionId())
                .ownerId(parentRequest.getOwnerId())
                .query(prompt)
                .originalQuery(prompt)
                .agentType(AgentType.AGENT_LOOP.getValue())
                .executionMode("DEEP")
                .basePrompt(parentRequest.getBasePrompt())
                .historyDialogue(parentRequest.getHistoryDialogue())
                .isStream(parentRequest.getIsStream())
                .sessionFiles(parentRequest.getSessionFiles())
                .outputStyle("markdown")
                .aiAgentId(parentRequest.getAiAgentId())
                .profileClientIds(parentRequest.getProfileClientIds())
                .resolvedRoleName(parentRequest.getResolvedRoleName())
                .modelId(parentRequest.getModelId())
                .online(parentRequest.getOnline())
                .build();
    }

    private String buildPrompt(String query,
                               ResearchPlan plan,
                               int researcherIndex,
                               List<String> assignedSections) {
        return """
                你是熊博士深度调研的第 %d 路 Researcher。
                原始问题：%s
                报告标题：%s
                只研究这些章节：%s

                要求：
                - 不要调用 todo_write，本分支计划已由 Research Planner 完成。
                - 最多使用一次搜索、网页、文件、MCP、Skills、记忆或分析工具收集证据；工具不可用时直接写证据缺口。
                - 输出 Markdown，按章节组织。
                - 对证据不足的地方明确写“证据不足”，不要编造。
                - 拿到材料后立即给最终 Markdown，不要等待确认或继续拆任务。
                """.formatted(researcherIndex, StringUtils.defaultString(query), plan.title(), assignedSections);
    }

    private List<String> activeToolNames(ToolCollection toolCollection) {
        List<String> names = new ArrayList<>();
        if (toolCollection == null) {
            return names;
        }
        if (toolCollection.getToolMap() != null) {
            names.addAll(toolCollection.getToolMap().keySet());
        }
        if (toolCollection.getMcpToolMap() != null) {
            names.addAll(toolCollection.getMcpToolMap().keySet());
        }
        return names;
    }

    private boolean isQuotaFailure(AgentLoop agentLoop, String markdown) {
        Message lastMessage = agentLoop.getMemory() == null ? null : agentLoop.getMemory().getLastMessage();
        String normalized = (StringUtils.defaultString(markdown) + "\n"
                + (lastMessage == null ? "" : StringUtils.defaultString(lastMessage.getContent())))
                .toLowerCase(Locale.ROOT);
        return normalized.contains("quota")
                || normalized.contains("insufficient")
                || normalized.contains("配额不足")
                || normalized.contains("额度不足")
                || normalized.contains("余额不足");
    }

    private String quotaFailureMessage(AgentLoop agentLoop, String markdown) {
        Message lastMessage = agentLoop.getMemory() == null ? null : agentLoop.getMemory().getLastMessage();
        String message = lastMessage == null ? "" : StringUtils.defaultString(lastMessage.getContent());
        return StringUtils.defaultIfBlank(message, StringUtils.defaultIfBlank(markdown, "额度不足，无法执行深度调研。"));
    }

    private List<ResearchEvidencePacket> evidenceProducedAfter(List<ToolExecutionEvidence> before,
                                                               List<ToolExecutionEvidence> after) {
        List<ToolExecutionEvidence> delta = new ArrayList<>(after);
        delta.removeAll(before);
        return delta.stream()
                .filter(ToolExecutionEvidence::isSuccess)
                .map(evidence -> new ResearchEvidencePacket(
                        StringUtils.defaultIfBlank(evidence.getToolCallId(), evidence.getOperationKey()),
                        StringUtils.defaultIfBlank(evidence.getToolName(), "tool"),
                        "",
                        StringUtils.defaultString(evidence.getOperationKey())))
                .filter(ResearchEvidencePacket::hasSource)
                .toList();
    }

    private record BranchPrinter(Printer delegate) implements Printer {
        @Override
        public void send(AgentStreamEvent event) {
            if (delegate != null) {
                if (event instanceof AgentStreamEvent.ToolStart
                        || event instanceof AgentStreamEvent.ToolEnd
                        || event instanceof AgentStreamEvent.Paused
                        || event instanceof AgentStreamEvent.ResumeStart) {
                    delegate.send(event);
                }
            }
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isAborted() {
            return delegate != null && delegate.isAborted();
        }
    }
}
