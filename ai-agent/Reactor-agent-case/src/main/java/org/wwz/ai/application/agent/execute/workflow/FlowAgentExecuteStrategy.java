package org.wwz.ai.application.agent.execute.workflow;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.execute.IExecuteStrategy;
import org.wwz.ai.application.agent.stream.AgentSessionPrinter;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.adapter.repository.IAgentRepository;
import org.wwz.ai.domain.agent.adapter.port.ModelCatalogPort;
import org.wwz.ai.domain.agent.adapter.port.QuotaBillingPort;
import org.wwz.ai.domain.agent.memory.ConversationMemoryManager;
import org.wwz.ai.domain.agent.memory.MemoryQuery;
import org.wwz.ai.domain.agent.memory.MemoryTurn;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.printer.Printer;
import org.wwz.ai.domain.agent.runtime.util.DateUtil;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import org.wwz.ai.domain.agent.runtime.metrics.AgentRunMetrics;
import org.wwz.ai.domain.agent.runtime.llm.LLMSettings;
import org.wwz.ai.domain.agent.runtime.llm.TokenCounter;
import org.wwz.ai.domain.agent.runtime.llm.LlmQuotaCalculator;
import org.wwz.ai.domain.agent.runtime.llm.LlmUsageSettlement;
import org.wwz.ai.domain.agent.runtime.ReactorRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.tool.mcp.runtime.WorkflowToolTraceContext;
import org.wwz.ai.domain.agent.ledger.AgentExecutionRecorder;
import org.wwz.ai.domain.agent.ledger.ExecutionLedgerRunSupport;
import org.wwz.ai.domain.agent.ledger.model.ExecutionLedgerConstants;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationStartRecord;
import org.wwz.ai.domain.agent.ledger.model.LlmInvocationFinishRecord;
import org.wwz.ai.domain.agent.service.runtime.AiClientRuntimeRegistry;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import reactor.core.publisher.Flux;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Workflow 应用层执行策略。
 * 输出协议在 case 层收口，domain 运行时上下文只接收 Printer 抽象。
 */
@Slf4j
@Service("flowAgentExecuteStrategy")
public class FlowAgentExecuteStrategy implements IExecuteStrategy {

    public static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    public static final String CHAT_MEMORY_RETRIEVE_SIZE_KEY = "chat_memory_response_size";

    @Resource
    private IAgentRepository repository;

    @Resource
    private ReactorConfig reactorConfig;

    @Resource
    private AiClientRuntimeRegistry aiClientRuntimeRegistry;

    @Resource
    private ConversationMemoryManager conversationMemoryManager;

    @Resource
    private ModelCatalogPort modelCatalogPort;

    @Resource
    private MemberQuotaBillingService memberQuotaBillingService;

    @Resource
    private AgentExecutionRecorder agentExecutionRecorder;

    @Resource
    private ReactorRuntimeDependencies reactorRuntimeDependencies;

    private final TokenCounter tokenCounter = new TokenCounter();

    @Override
    public void execute(AgentRequest request, AgentSessionStream stream) throws Exception {
        log.info("{} workflow agent request accepted agentType={} stream={} fileCount={}",
                request.getRequestId(), request.getAgentType(), request.getIsStream(),
                request.getSessionFiles() == null ? 0 : request.getSessionFiles().size());

        Printer printer = new AgentSessionPrinter(stream, request, request.getAgentType());
        AgentContext agentContext = AgentContext.builder()
                .requestId(request.getRequestId())
                .sessionId(request.getSessionId())
                .ownerId(Long.valueOf(request.getOwnerId()))
                .printer(printer)
                .query(request.getQuery())
                .task("")
                .dateInfo(DateUtil.CurrentDateInfo())
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .sopPrompt(request.getSopPrompt())
                .basePrompt(request.getBasePrompt())
                .agentType(request.getAgentType())
                .isStream(Objects.nonNull(request.getIsStream()) ? request.getIsStream() : false)
                .templateType("dataAgent".equals(request.getOutputStyle()) ? "fix" : "empty")
                .executionRecorder(agentExecutionRecorder)
                .runtimeDependencies(reactorRuntimeDependencies)
                .build();

        if (request.getAiAgentId() == null || request.getAiAgentId().isBlank()) {
            throw new IllegalStateException("chat 角色未解析，无法执行 Fix 策略");
        }

        List<AiAgentClientFlowConfigVO> aiAgentClientList =
                repository.queryAiAgentClientsByAgentId(request.getAiAgentId());
        if (aiAgentClientList == null || aiAgentClientList.isEmpty()) {
            throw new IllegalStateException("当前角色未配置可执行的 Fix 流程");
        }
        ExecutionLedgerRunSupport.initializeRun(
                agentExecutionRecorder, agentContext, request, ExecutionLedgerConstants.ENTRY_AGENT_WORKFLOW);

        String content = "";
        final String sessionId = request.getSessionId();
        Exception streamError = null;
        // 三层记忆：chat 短期记忆走 Spring AI 内存窗口(advisor)，中期(会话摘要)+长期(跨会话向量)由记忆块前置注入 system。
        final String memoryBlock = assembleMemoryBlock(request);

        // 展示级 run 元数据：耗时本地测量，模型名/总 token 从 Spring AI 响应元数据采集（末帧携带 usage）。
        final long runStartedAtMillis = System.currentTimeMillis();
        final String[] modelHolder = {null};
        final long[] totalTokenHolder = {0L};

        int flowIndex = 0;
        for (AiAgentClientFlowConfigVO config : aiAgentClientList) {
            flowIndex++;
            ChatClient chatClient = resolveChatClient(config.getClientId(), request.getModelId());
            StringBuilder fullText = new StringBuilder();
            String userPrompt = request.getQuery() + "，" + content;
            String systemPrompt = buildSystemPrompt(memoryBlock, config.getStepPrompt());
            LLMSettings billingSettings = modelCatalogPort.resolveLlmSettings(request.getModelId());
            long inputRate = billingSettings == null ? 5L : Math.max(1L, billingSettings.getInputCreditsPerMillion());
            long outputRate = billingSettings == null ? 30L : Math.max(1L, billingSettings.getOutputCreditsPerMillion());
            int requestedMaxOutput = Math.max(LlmQuotaCalculator.MIN_OUTPUT_TOKENS,
                    billingSettings == null ? 16384 : billingSettings.getMaxTokens());
            int estimatedInput = tokenCounter.countText(userPrompt) + tokenCounter.countText(systemPrompt) + 8;
            int invocationSeq = agentContext.getAgentRunState().nextInvocationSeq();
            Long invocationId = agentExecutionRecorder.createLlmInvocation(LlmInvocationStartRecord.builder()
                    .runId(agentContext.getAgentRunState().getRunId())
                    .requestId(request.getRequestId())
                    .invocationSeq(invocationSeq)
                    .agentName("workflow")
                    .stepNo(flowIndex)
                    .callKind(ExecutionLedgerConstants.CALL_KIND_ASK)
                    .streaming(true)
                    .modelName(billingSettings == null ? request.getModelId() : billingSettings.getModel())
                    .inputRateSnapshot(inputRate)
                    .outputRateSnapshot(outputRate)
                    .startedAt(LocalDateTime.now())
                    .build());
            WorkflowToolTraceContext toolTrace = new WorkflowToolTraceContext(
                    agentExecutionRecorder,
                    agentContext.getAgentRunState().getRunId(),
                    request.getRequestId(),
                    request.getSessionId(),
                    invocationId,
                    "workflow",
                    flowIndex
            );
            LlmQuotaCalculator.ReservationAmounts amounts = LlmQuotaCalculator.reservation(
                    estimatedInput, requestedMaxOutput, inputRate, outputRate);
            QuotaBillingPort.Reservation reservation;
            try {
                reservation = memberQuotaBillingService.reserve(
                        request.getOwnerId() == null ? null : Long.valueOf(request.getOwnerId()),
                        amounts.requestedMicrocredits(),
                        amounts.minimumMicrocredits(),
                        request.getRequestId() + ":workflow:" + flowIndex);
            } catch (RuntimeException e) {
                recordWorkflowInvocation(agentContext, invocationId, ExecutionLedgerConstants.STATUS_FAILED,
                        estimatedInput, 0, "ESTIMATED", 0L, null, 0, e.getMessage());
                ExecutionLedgerRunSupport.finishRun(agentContext, ExecutionLedgerConstants.STATUS_FAILED,
                        null, "QUOTA_INSUFFICIENT", e.getMessage());
                throw e;
            }
            int affordableMaxOutput = LlmQuotaCalculator.affordableOutputTokens(
                    reservation.reservedMicrocredits(), estimatedInput, requestedMaxOutput, inputRate, outputRate);
            Integer[] promptTokens = new Integer[1];
            Integer[] completionTokens = new Integer[1];
            try {
                Flux<org.springframework.ai.chat.model.ChatResponse> flux = chatClient
                        .prompt(userPrompt)
                        .system(systemPrompt)
                        .options(OpenAiChatOptions.builder().maxTokens(affordableMaxOutput).build())
                        .advisors(a -> a
                                .param(CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId)
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100)
                        )
                        .toolContext(Map.of(WorkflowToolTraceContext.CONTEXT_KEY, toolTrace))
                        .stream().chatResponse();

                // 不再 doOnError 吞异常：错误从 blockLast() 抛出，由下方 catch 捕获并显式上抛
                flux.doOnNext(cr -> {
                    if (cr != null && cr.getResult() != null && cr.getResult().getOutput() != null) {
                        String text = cr.getResult().getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            fullText.append(text);
                            agentContext.getPrinter().send("agent_stream", text);
                        }
                    }
                    captureResponseMetrics(cr, modelHolder, totalTokenHolder, promptTokens, completionTokens);
                }).blockLast();
            } catch (Exception e) {
                log.error("流式调用 LLM 异常 clientId={} errorType={}",
                        config.getClientId(), e.getClass().getSimpleName());
                streamError = e;
                content = fullText.toString();
                try {
                    settleWorkflowCall(agentContext, invocationId, reservation, inputRate, outputRate, estimatedInput,
                            fullText.toString(), promptTokens[0], completionTokens[0],
                            ExecutionLedgerConstants.resolveFailureStatus(e), toolTrace.getCallCount(), e.getMessage());
                } catch (RuntimeException settlementFailure) {
                    ExecutionLedgerRunSupport.finishRun(agentContext, ExecutionLedgerConstants.STATUS_FAILED,
                            null, "BILLING_SETTLEMENT_FAILED", settlementFailure.getMessage());
                    throw settlementFailure;
                }
                break;
            }

            content = fullText.toString();
            try {
                settleWorkflowCall(agentContext, invocationId, reservation, inputRate, outputRate, estimatedInput,
                        content, promptTokens[0], completionTokens[0], ExecutionLedgerConstants.STATUS_SUCCESS,
                        toolTrace.getCallCount(), null);
            } catch (RuntimeException settlementFailure) {
                ExecutionLedgerRunSupport.finishRun(agentContext, ExecutionLedgerConstants.STATUS_FAILED,
                        null, "BILLING_SETTLEMENT_FAILED", settlementFailure.getMessage());
                throw settlementFailure;
            }
            log.info("固定智能体对话进行，客户端ID {}", config.getClientId());
        }

        // 失败可见：LLM 调用异常且无任何产出时显式上抛，让 dispatch 层 completeWithError
        // （前端看到错误、后续执行停止；已发生的 LLM 调用按调用级 usage 结算），
        // 不再静默 send("result", "") 让用户停留在空回复。
        if (streamError != null && content.isEmpty()) {
            ExecutionLedgerRunSupport.finishRun(agentContext, ExecutionLedgerConstants.STATUS_FAILED,
                    null, "LLM_FAILED", streamError.getMessage());
            throw new RuntimeException("chat 模式对话生成失败: " + streamError.getMessage(), streamError);
        }

        // 最终帧携带展示级 metrics（模型 / tokens / 耗时），供前端在回复下方渲染 chips
        long durationMs = System.currentTimeMillis() - runStartedAtMillis;
        Long totalTokens = totalTokenHolder[0] > 0 ? totalTokenHolder[0] : null;
        Map<String, Object> metrics = AgentRunMetrics.of(modelHolder[0], totalTokens, durationMs);
        if (metrics.isEmpty()) {
            agentContext.getPrinter().send("result", content);
        } else {
            agentContext.getPrinter().sendWithResultMap("result", content, Map.of(AgentRunMetrics.KEY, metrics));
        }
        ExecutionLedgerRunSupport.finishRun(agentContext,
                streamError == null ? ExecutionLedgerConstants.STATUS_SUCCESS : ExecutionLedgerConstants.STATUS_FAILED,
                streamError == null ? content : null,
                streamError == null ? null : "LLM_FAILED",
                streamError == null ? null : streamError.getMessage());
        persistTurn(request, content);
    }

    /**
     * 从 Spring AI 流式响应元数据采集模型名与总 token（usage 通常只在末帧出现，取到即覆盖）。
     */
    private void captureResponseMetrics(org.springframework.ai.chat.model.ChatResponse cr,
                                        String[] modelHolder,
                                        long[] totalTokenHolder,
                                        Integer[] promptTokenHolder,
                                        Integer[] completionTokenHolder) {
        if (cr == null) {
            return;
        }
        try {
            ChatResponseMetadata metadata = cr.getMetadata();
            if (metadata == null) {
                return;
            }
            String model = metadata.getModel();
            if (model != null && !model.isBlank()) {
                modelHolder[0] = model;
            }
            Usage usage = metadata.getUsage();
            if (usage != null && usage.getTotalTokens() != null) {
                totalTokenHolder[0] = usage.getTotalTokens().longValue();
            }
            if (usage != null && usage.getPromptTokens() != null) {
                promptTokenHolder[0] = usage.getPromptTokens();
            }
            if (usage != null && usage.getCompletionTokens() != null) {
                completionTokenHolder[0] = usage.getCompletionTokens();
            }
        } catch (Exception ignore) {
            // 元数据采集失败不影响主流程
        }
    }

    private void settleWorkflowCall(AgentContext context,
                                    Long invocationId,
                                    QuotaBillingPort.Reservation reservation,
                                    long inputRate,
                                    long outputRate,
                                    int estimatedInput,
                                    String output,
                                    Integer providerInput,
                                    Integer providerOutput,
                                    int status,
                                    int toolCallCount,
                                    String errorMsg) {
        LlmUsageSettlement.Result usage = LlmUsageSettlement.resolve(
                providerInput, providerOutput, estimatedInput, tokenCounter.countText(output), inputRate, outputRate);
        try {
            LlmQuotaCalculator.requireWithinReservation(
                    usage.chargedMicrocredits(), reservation.reservedMicrocredits(), "workflow");
            memberQuotaBillingService.settle(reservation.freezeId(), usage.chargedMicrocredits());
        } catch (RuntimeException settlementFailure) {
            try {
                memberQuotaBillingService.release(reservation.freezeId());
            } catch (RuntimeException releaseFailure) {
                settlementFailure.addSuppressed(releaseFailure);
            }
            recordWorkflowInvocation(context, invocationId, ExecutionLedgerConstants.STATUS_FAILED,
                    usage.inputTokens(), usage.outputTokens(), usage.usageSource(), 0L, output, toolCallCount,
                    settlementFailure.getMessage());
            throw settlementFailure;
        }
        recordWorkflowInvocation(context, invocationId, status, usage.inputTokens(), usage.outputTokens(),
                usage.usageSource(), usage.chargedMicrocredits(), output, toolCallCount, errorMsg);
    }

    private void recordWorkflowInvocation(AgentContext context,
                                          Long invocationId,
                                          int status,
                                          int inputTokens,
                                          int outputTokens,
                                          String usageSource,
                                          long chargedMicrocredits,
                                          String output,
                                          int toolCallCount,
                                          String errorMsg) {
        agentExecutionRecorder.finishLlmInvocation(LlmInvocationFinishRecord.builder()
                .llmInvocationId(invocationId)
                .requestId(context.getRequestId())
                .status(status)
                .responseText(output)
                .toolCallCount(Math.max(0, toolCallCount))
                .promptTokens(inputTokens)
                .completionTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .usageSource(usageSource)
                .chargedMicrocredits(chargedMicrocredits)
                .errorMsg(errorMsg)
                .finishedAt(LocalDateTime.now())
                .build());
    }

    /**
     * chat/workflow 模式解析对话客户端。
     * 用户选择模型时优先取该 client 的组合客户端（clientId::modelId）；
     * 组合未装配（目标模型未随任何 client 加载）时安全回退到 client 默认模型，保证不因换模型而失败。
     */
    private ChatClient resolveChatClient(String clientId, String overrideModelId) {
        if (StringUtils.isNotBlank(overrideModelId)) {
            ChatClient combo = aiClientRuntimeRegistry.findChatClient(
                    AiClientRuntimeRegistry.comboClientKey(clientId, overrideModelId));
            if (combo != null) {
                return combo;
            }
            log.warn("chat 模式所选模型 {} 未装配组合客户端，回退默认模型 clientId={}", overrideModelId, clientId);
        }
        return aiClientRuntimeRegistry.getRequiredChatClient(clientId);
    }

    private String assembleMemoryBlock(AgentRequest request) {
        if (conversationMemoryManager == null || request == null) {
            return "";
        }
        return conversationMemoryManager.assembleHistoryBlock(new MemoryQuery(
                request.getOwnerId(),
                request.getSessionId(),
                request.getRequestId(),
                request.getQuery()));
    }

    private String buildSystemPrompt(String memoryBlock, String stepPrompt) {
        String base = (stepPrompt == null ? "" : stepPrompt) + " current_date_time:" + LocalDateTime.now();
        if (memoryBlock == null || memoryBlock.isBlank()) {
            return base;
        }
        return memoryBlock + "\n\n" + base;
    }

    private void persistTurn(AgentRequest request, String content) {
        if (conversationMemoryManager == null || request == null || content == null || content.isBlank()) {
            return;
        }
        conversationMemoryManager.persistTurnAsync(new MemoryTurn(
                request.getOwnerId(),
                request.getSessionId(),
                request.getRequestId(),
                request.getQuery(),
                content));
    }
}
