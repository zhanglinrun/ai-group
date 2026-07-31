package com.linrun.agent.domain.agent.rag.memory;

import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalHit;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalRequest;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.model.ExecutionRunDetail;
import com.linrun.agent.domain.agent.runtime.llm.BillableModelInvocationService;
import com.linrun.agent.domain.agent.runtime.llm.ModelInvocationPolicy;
import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 语义记忆管理器：三层记忆 + 水位线 + 增量合并。
 *
 * <p>借鉴 dodo-agentx 的 SemanticMemoryManager：
 * <ul>
 *   <li><b>qa_pair</b>：每轮对话存一条，created_at 单调递增</li>
 *   <li><b>session_summary</b>：每会话一条，增量合并（旧摘要 + 新对话 → LLM 重摘要），上下文有界</li>
 *   <li><b>cross_summary</b>：跨会话摘要，{@code latest_qa_created_at} 水位线触发：
 *       当 qa_pair.created_at 超过水位线时，取最新 cross_summary + 溢出的 qa_pair → LLM 摘要</li>
 * </ul>
 *
 * <p>水位线机制避免每次都全量重算跨会话摘要，只处理增量部分。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "spring.datasource.postgres", name = "url")
public class SemanticMemoryManager {

    private static final int SUMMARY_QA_BATCH = 20;
    private static final int SUMMARY_INPUT_TOKENS = 6000;
    private static final int SUMMARY_OUTPUT_TOKENS = 800;
    private static final int DEFAULT_RETENTION_DAYS = 180;
    private static final long DEFAULT_INPUT_RATE = 5L;
    private static final long DEFAULT_OUTPUT_RATE = 30L;
    private static final TokenCounter TOKEN_COUNTER = new TokenCounter();

    private final PgVectorMemoryRepository memoryRepository;
    private final HybridRetriever hybridRetriever;
    private final JdbcTemplate pgJdbcTemplate;
    private final ChatModel chatModel;
    private final String summaryModel;

    @Autowired
    private BillableModelInvocationService modelInvocationService;

    @Autowired
    private ExecutionLedgerQueryService executionLedgerQueryService;

    @Autowired
    public SemanticMemoryManager(PgVectorMemoryRepository memoryRepository,
                                  HybridRetriever hybridRetriever,
                                  @Qualifier("pgJdbcTemplate") JdbcTemplate pgJdbcTemplate,
                                  @Autowired(required = false) ChatModel chatModel,
                                  @Value("${agent.rag.summary.model:qwen-plus}") String summaryModel) {
        this.memoryRepository = memoryRepository;
        this.hybridRetriever = hybridRetriever;
        this.pgJdbcTemplate = pgJdbcTemplate;
        this.chatModel = chatModel;
        this.summaryModel = summaryModel;
    }

    /**
     * 保存一轮 Q&A 对（原子记忆）。
     */
    public boolean saveQaPair(String ownerId, String conversationId, String question, String answer) {
        if (StringUtils.isBlank(ownerId) || StringUtils.isBlank(question)) {
            return false;
        }
        String content = "Q: " + question + "\nA: " + StringUtils.defaultString(answer);
        String memoryId = UUID.nameUUIDFromBytes(
                (ownerId + "|qa|" + conversationId + "|" + question.hashCode())
                        .getBytes()).toString();
        Map<String, Object> metadata = Map.of(
                "conversationId", StringUtils.defaultString(conversationId),
                "question", question);
        return memoryRepository.saveMemory(memoryId, ownerId,
                SemanticMemoryType.QA_PAIR.dbValue(), content, metadata, conversationId);
    }

    /**
     * 增量合并会话摘要：旧摘要 + 新对话 → LLM 重摘要 → upsert。
     *
     * <p>上下文有界：始终只取 1 条旧 session_summary + 本轮新对话，不会无限膨胀。</p>
     */
    public boolean mergeSessionSummary(String ownerId, String conversationId, String newConversation) {
        return mergeSessionSummary(ownerId, conversationId, null, newConversation, DEFAULT_RETENTION_DAYS);
    }

    public boolean mergeSessionSummary(String ownerId,
                                       String conversationId,
                                       String requestId,
                                       String newConversation) {
        return mergeSessionSummary(ownerId, conversationId, requestId, newConversation, DEFAULT_RETENTION_DAYS);
    }

    public boolean mergeSessionSummary(String ownerId,
                                       String conversationId,
                                       String requestId,
                                       String newConversation,
                                       int retentionDays) {
        if (StringUtils.isAnyBlank(ownerId, conversationId, newConversation)) {
            return false;
        }
        if (chatModel == null) {
            log.warn("session summary skipped: no ChatModel available ownerId={}", ownerId);
            return false;
        }
        List<Map<String, Object>> existing = memoryRepository.findByOwnerDocTypeAndConversation(
                ownerId, SemanticMemoryType.SESSION_SUMMARY.dbValue(), conversationId, 1);
        String oldSummary = existing.isEmpty() ? "" : asString(existing.get(0).get("content"));
        String memoryId = UUID.nameUUIDFromBytes((ownerId + "|ss|" + conversationId).getBytes()).toString();

        String prompt = buildSessionSummaryPrompt(oldSummary, newConversation);
        String summary = callLlm(ownerId, requestId, prompt);
        if (StringUtils.isBlank(summary)) {
            return false;
        }
        Map<String, Object> metadata = Map.of(
                "conversationId", StringUtils.defaultString(conversationId),
                "mergeType", "incremental");
        return memoryRepository.saveMemory(memoryId, ownerId,
                SemanticMemoryType.SESSION_SUMMARY.dbValue(), summary, metadata, conversationId,
                Instant.now().plusSeconds(retentionSeconds(retentionDays)));
    }

    /**
     * 水位线触发跨会话摘要：检查 qa_pair.created_at 是否超过 cross_summary.latest_qa_created_at。
     *
     * <p>若溢出，取最新 cross_summary + 溢出的 qa_pair → LLM 摘要 → 写新 cross_summary（更新水位线）。</p>
     */
    public boolean maybeSummarizeCrossSession(String ownerId) {
        return maybeSummarizeCrossSession(ownerId, null, DEFAULT_RETENTION_DAYS);
    }

    public boolean maybeSummarizeCrossSession(String ownerId, String requestId) {
        return maybeSummarizeCrossSession(ownerId, requestId, DEFAULT_RETENTION_DAYS);
    }

    public boolean maybeSummarizeCrossSession(String ownerId, String requestId, int retentionDays) {
        if (StringUtils.isBlank(ownerId)) {
            return false;
        }
        if (chatModel == null) {
            return false;
        }
        Timestamp watermark = resolveCrossSummaryWatermark(ownerId);
        List<Map<String, Object>> overflowQa = pgJdbcTemplate.queryForList(
                "SELECT content, created_at FROM agent_semantic_memory " +
                        "WHERE owner_id = ? AND doc_type = 'qa_pair' AND created_at > ? " +
                        "ORDER BY created_at ASC LIMIT ?",
                ownerId, watermark != null ? watermark : Timestamp.from(Instant.EPOCH),
                SUMMARY_QA_BATCH);
        if (overflowQa.size() < SUMMARY_QA_BATCH) {
            return false;
        }
        String oldCrossSummary = resolveLatestCrossSummaryContent(ownerId);
        String qaText = overflowQa.stream()
                .map(row -> asString(row.get("content")))
                .reduce("", (a, b) -> a + "\n" + b);
        Timestamp newWatermark = asTimestamp(overflowQa.get(overflowQa.size() - 1).get("created_at"));
        String prompt = buildCrossSummaryPrompt(oldCrossSummary, qaText);
        String summary = callLlm(ownerId, requestId, prompt);
        if (StringUtils.isBlank(summary)) {
            return false;
        }
        String memoryId = UUID.nameUUIDFromBytes(
                (ownerId + "|cs|" + newWatermark.getTime()).getBytes()).toString();
        Map<String, Object> metadata = Map.of("watermark", newWatermark.getTime());
        boolean saved = memoryRepository.saveMemoryWithWatermark(memoryId, ownerId,
                SemanticMemoryType.CROSS_SUMMARY.dbValue(), summary, metadata, null, newWatermark,
                Instant.now().plusSeconds(retentionSeconds(retentionDays)));
        log.info("cross session summary ownerId={} qaCount={} watermark={}",
                ownerId, overflowQa.size(), newWatermark);
        return saved;
    }

    /**
     * 检索相关记忆（混合检索，供 Agent 上下文注入）。
     */
    public List<HybridRetrievalHit> recallRelevant(String ownerId, String query, int topK) {
        HybridRetrievalRequest request = HybridRetrievalRequest.builder()
                .ownerId(ownerId)
                .query(query)
                .topK(topK)
                .scoreThreshold(0.3)
                .keywordEnabled(true)
                .build();
        return hybridRetriever.retrieve(request);
    }

    // ==================== 内部方法 ====================

    private Timestamp resolveCrossSummaryWatermark(String ownerId) {
        try {
            List<Timestamp> watermarks = pgJdbcTemplate.queryForList(
                    "SELECT latest_qa_created_at FROM agent_semantic_memory " +
                            "WHERE owner_id = ? AND doc_type = 'cross_summary' " +
                            "AND latest_qa_created_at IS NOT NULL " +
                            "ORDER BY latest_qa_created_at DESC LIMIT 1",
                    Timestamp.class, ownerId);
            return watermarks.isEmpty() ? null : watermarks.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveLatestCrossSummaryContent(String ownerId) {
        List<Map<String, Object>> rows = memoryRepository.findByOwnerAndDocType(
                ownerId, SemanticMemoryType.CROSS_SUMMARY.dbValue(), 1);
        return rows.isEmpty() ? "" : asString(rows.get(0).get("content"));
    }

    private long retentionSeconds(int retentionDays) {
        return Math.max(1, Math.min(365, retentionDays)) * 86_400L;
    }

    private String callLlm(String ownerId, String requestId, String prompt) {
        if (StringUtils.isBlank(requestId) || modelInvocationService == null || executionLedgerQueryService == null) {
            log.warn("summary llm call skipped because durable invocation context is unavailable ownerId={}", ownerId);
            return null;
        }
        try {
            ExecutionRunDetail runDetail = executionLedgerQueryService.queryRunDetail(requestId);
            if (runDetail == null || runDetail.getRun() == null || runDetail.getRun().getId() == null
                    || !StringUtils.equals(ownerId, runDetail.getRun().getOwnerId())) {
                log.warn("summary llm call skipped because run ownership cannot be verified requestId={}", requestId);
                return null;
            }
            String boundedPrompt = TOKEN_COUNTER.truncateTextToTokens(prompt, SUMMARY_INPUT_TOKENS);
            ChatResponse response = modelInvocationService.invoke(
                    chatModel,
                    new Prompt(new UserMessage(boundedPrompt),
                            OpenAiChatOptions.builder().model(summaryModel).maxTokens(SUMMARY_OUTPUT_TOKENS).build()),
                    ModelInvocationPolicy.platformCost(
                            runDetail.getRun().getId(), requestId, "memory_summary", summaryModel,
                            SUMMARY_OUTPUT_TOKENS, TOKEN_COUNTER.countText(boundedPrompt),
                            DEFAULT_INPUT_RATE, DEFAULT_OUTPUT_RATE, 0D));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("summary llm call failed errorType={}", e.getClass().getSimpleName(), e);
            return null;
        }
    }

    private String buildSessionSummaryPrompt(String oldSummary, String newConversation) {
        if (StringUtils.isBlank(oldSummary)) {
            return "请把以下对话总结为简洁的会话摘要，保留关键事实、用户偏好和未完成事项：\n\n" + newConversation;
        }
        return "已有会话摘要：\n" + oldSummary + "\n\n新增对话：\n" + newConversation
                + "\n\n请合并生成更新后的会话摘要，保留关键信息，去除冗余。用中文回答。";
    }

    private String buildCrossSummaryPrompt(String oldCrossSummary, String qaText) {
        if (StringUtils.isBlank(oldCrossSummary)) {
            return "请把以下多轮对话总结为跨会话的用户画像摘要，提炼长期事实、偏好和关键背景：\n\n" + qaText;
        }
        return "已有跨会话摘要：\n" + oldCrossSummary + "\n\n新增对话：\n" + qaText
                + "\n\n请合并生成更新后的跨会话摘要，聚焦长期稳定信息。用中文回答。";
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Timestamp asTimestamp(Object value) {
        if (value instanceof Timestamp ts) return ts;
        if (value instanceof java.util.Date date) return new Timestamp(date.getTime());
        return Timestamp.from(Instant.now());
    }
}
