package org.wwz.ai.application.agent.execute.workflow;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.execute.IExecuteStrategy;
import org.wwz.ai.application.agent.stream.AgentSessionPrinter;
import org.wwz.ai.application.agent.stream.AgentSessionStream;
import org.wwz.ai.domain.agent.adapter.repository.IAgentRepository;
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
import org.wwz.ai.domain.agent.service.runtime.AiClientRuntimeRegistry;
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

    @Override
    public void execute(AgentRequest request, AgentSessionStream stream) throws Exception {
        log.info("{} fixed agent request: {}", request.getRequestId(), request);

        Printer printer = new AgentSessionPrinter(stream, request, request.getAgentType());
        AgentContext agentContext = AgentContext.builder()
                .requestId(request.getRequestId())
                .sessionId(request.getSessionId())
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
                .build();

        if (request.getAiAgentId() == null || request.getAiAgentId().isBlank()) {
            throw new IllegalStateException("chat 角色未解析，无法执行 Fix 策略");
        }

        List<AiAgentClientFlowConfigVO> aiAgentClientList =
                repository.queryAiAgentClientsByAgentId(request.getAiAgentId());
        if (aiAgentClientList == null || aiAgentClientList.isEmpty()) {
            throw new IllegalStateException("当前角色未配置可执行的 Fix 流程");
        }

        String content = "";
        final String sessionId = request.getSessionId();
        Exception streamError = null;
        // 三层记忆：chat 短期记忆走 Spring AI 内存窗口(advisor)，中期(会话摘要)+长期(跨会话向量)由记忆块前置注入 system。
        final String memoryBlock = assembleMemoryBlock(request);

        // 展示级 run 元数据：耗时本地测量，模型名/总 token 从 Spring AI 响应元数据采集（末帧携带 usage）。
        final long runStartedAtMillis = System.currentTimeMillis();
        final String[] modelHolder = {null};
        final long[] totalTokenHolder = {0L};

        for (AiAgentClientFlowConfigVO config : aiAgentClientList) {
            ChatClient chatClient = resolveChatClient(config.getClientId(), request.getModelId());
            StringBuilder fullText = new StringBuilder();
            try {
                Flux<org.springframework.ai.chat.model.ChatResponse> flux = chatClient
                        .prompt(request.getQuery() + "，" + content)
                        .system(buildSystemPrompt(memoryBlock, config.getStepPrompt()))
                        .advisors(a -> a
                                .param(CHAT_MEMORY_CONVERSATION_ID_KEY, sessionId)
                                .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 100)
                        )
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
                    captureResponseMetrics(cr, modelHolder, totalTokenHolder);
                }).blockLast();
            } catch (Exception e) {
                log.error("流式调用 LLM 异常 clientId:{} : {}", config.getClientId(), e.getMessage(), e);
                streamError = e;
                content = fullText.toString();
                break;
            }

            content = fullText.toString();
            log.info("固定智能体对话进行，客户端ID {}", config.getClientId());
        }

        // 失败可见：LLM 调用异常且无任何产出时显式上抛，让 dispatch 层 completeWithError
        // （前端看到错误、配额释放），不再静默 send("result", "") 让用户停留在空回复。
        if (streamError != null && content.isEmpty()) {
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
        // chat 无执行账本 run，answerSummary 直接用最终答复内容落长期记忆
        persistTurn(request, content);
    }

    /**
     * 从 Spring AI 流式响应元数据采集模型名与总 token（usage 通常只在末帧出现，取到即覆盖）。
     */
    private void captureResponseMetrics(org.springframework.ai.chat.model.ChatResponse cr,
                                        String[] modelHolder,
                                        long[] totalTokenHolder) {
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
        } catch (Exception ignore) {
            // 元数据采集失败不影响主流程
        }
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
