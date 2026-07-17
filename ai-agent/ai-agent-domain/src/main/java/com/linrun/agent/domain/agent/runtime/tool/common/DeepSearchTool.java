package com.linrun.agent.domain.agent.runtime.tool.common;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamListener;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamPort;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamRequest;
import com.linrun.agent.domain.agent.adapter.port.RemoteStreamSession;
import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;
import com.linrun.agent.domain.agent.quota.QuotaProviderAlreadyStartedException;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.artifact.ToolArtifactSource;
import com.linrun.agent.domain.agent.runtime.dto.DeepSearchRequest;
import com.linrun.agent.domain.agent.runtime.dto.DeepSearchrResponse;
import com.linrun.agent.domain.agent.runtime.dto.FileRequest;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.harness.AgentFutureWaiter;
import com.linrun.agent.domain.agent.runtime.util.StringUtil;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.config.ReactorToolRequestHeaders;
import com.linrun.agent.domain.agent.ledger.model.tooloutput.DeepSearchToolOutput;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Data

public class DeepSearchTool implements BaseTool {

    /**
     * deep_search 单次调用保底上限；实际等待还会被 run 剩余时间进一步截短。
     * 外部搜索/模型不可达时必须快速返回结构化失败 observation，让主 Agent
     * 继续完成交付，而不是把整个 SSE 请求拖到客户端超时。
     */
    private static final long DEEP_SEARCH_TIMEOUT_SECONDS = 45L;
    /**
     * deep_search HTTP 连接超时时间。
     */
    private static final long DEEP_SEARCH_CONNECT_TIMEOUT_SECONDS = 10L;
    /**
     * deep_search HTTP 读写超时时间。
     */
    private static final long DEEP_SEARCH_IO_TIMEOUT_SECONDS = 60L;

    private AgentContext agentContext;
    /**
     * 当前正在执行的 deep_search SSE 连接，用于超时后主动取消。
     */
    private volatile RemoteStreamSession activeStreamSession;

    @Override
    public String getName() {
        return "deep_search";
    }

    @Override
    public String getDescription() {
        String desc = "这是一个搜索工具，可以通过搜索内外网知识";
        ReactorConfig reactorConfig = requireReactorConfig();
        return reactorConfig.getDeepSearchToolDesc().isEmpty() ? desc : reactorConfig.getDeepSearchToolDesc();
    }

    @Override
    public Map<String, Object> toParams() {

        ReactorConfig reactorConfig = requireReactorConfig();
        if (!reactorConfig.getDeepSearchToolParams().isEmpty()) {
            return reactorConfig.getDeepSearchToolParams();
        }

        Map<String, Object> taskParam = new HashMap<>();
        taskParam.put("type", "string");
        taskParam.put("description", "需要搜索的query");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        Map<String, Object> properties = new HashMap<>();
        properties.put("query", taskParam);
        parameters.put("properties", properties);
        parameters.put("required", Collections.singletonList("query"));

        return parameters;
    }

    @Override
    public Object execute(Object input) {
        long startTime = System.currentTimeMillis();
        QuotaBillingPort.Reservation reservation = null;

        try {
            ReactorConfig reactorConfig = requireReactorConfig();
            Map<String, Object> params = (Map<String, Object>) input;
            String query = (String) params.get("query");
            Map<String, Object> srcConfig = new HashMap<>();

            Map<String, Object> bingConfig = new HashMap<>();
            bingConfig.put("count", Integer.parseInt(reactorConfig.getDeepSearchPageCount()));
            srcConfig.put("bing", bingConfig);
            DeepSearchRequest request = DeepSearchRequest.builder()
                    .request_id(agentContext.getRequestId() + ":" + StringUtil.generateRandomString(5))
                    .query(query)
                    .agent_id("1")
                    .scene_type("auto_agent")
                    .src_configs(srcConfig)
                    .stream(true)
                    .content_stream(agentContext.getIsStream())
                    .build();
            ToolArtifactSource artifactSource = agentContext.requireCurrentToolArtifactSource(getName());
            reservation = reserveSurcharge(artifactSource);
            try {
                markProviderStarted(reservation);
            } catch (QuotaProviderAlreadyStartedException duplicateAdmission) {
                reservation = null;
                throw duplicateAdmission;
            }

            // 调用流式 API
            Future<ToolResultPayload> future = callDeepSearchStream(request, artifactSource);
            ToolResultPayload result = AgentFutureWaiter.await(
                    future, agentContext, Duration.ofSeconds(DEEP_SEARCH_TIMEOUT_SECONDS));
            QuotaBillingPort.Reservation terminalReservation = reservation;
            reservation = null;
            if (Boolean.TRUE.equals(result.getFailed())) {
                releaseSurcharge(terminalReservation);
            } else {
                settleSurcharge(terminalReservation);
            }
            return result;
        } catch (AgentFutureWaiter.DownstreamAbortedException e) {
            releaseSurcharge(reservation);
            cancelActiveStream();
            return buildFailurePayload("deep_search 已取消：客户端连接已断开。");
        } catch (TimeoutException e) {
            releaseSurcharge(reservation);
            cancelActiveStream();
            log.error("{} deep_search timeout after {} seconds errorType={}",
                    agentContext.getRequestId(), DEEP_SEARCH_TIMEOUT_SECONDS, e.getClass().getSimpleName());
            return buildFailurePayload("deep_search执行超时，已终止本次搜索，请基于当前已获取的信息继续处理。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            releaseSurcharge(reservation);
            cancelActiveStream();
            return buildFailurePayload("deep_search执行被中断，已终止本次搜索。");
        } catch (Exception e) {
            releaseSurcharge(reservation);

            log.error("{} deep_search execute failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            return buildFailurePayload("deep_search执行失败：" + StringUtils.defaultIfBlank(e.getMessage(), "未知异常"));
        } finally {
            activeStreamSession = null;
        }
    }

    private void cancelActiveStream() {
        RemoteStreamSession session = activeStreamSession;
        if (session != null) {
            session.cancel();
        }
    }

    private QuotaBillingPort.Reservation reserveSurcharge(ToolArtifactSource source) {
        if (agentContext.getOwnerId() == null || agentContext.getRuntimeDependencies().getQuotaBillingPort() == null) {
            return null;
        }
        long amount = Math.max(0L, requireReactorConfig().getDeepSearchMicrocredits());
        if (amount == 0L) {
            return null;
        }
        String toolCallId = source == null ? null : StringUtils.trimToNull(source.getToolCallId());
        if (toolCallId == null) {
            throw new IllegalStateException("deep_search billing requires a durable toolCallId");
        }
        return agentContext.getRuntimeDependencies().getQuotaBillingPort().reserve(
                agentContext.getOwnerId(), amount, amount, "deep_search",
                agentContext.getRequestId() + ":tool:deep_search:" + toolCallId);
    }

    private void markProviderStarted(QuotaBillingPort.Reservation reservation) {
        if (reservation != null) {
            agentContext.getRuntimeDependencies().getQuotaBillingPort()
                    .markProviderStarted(reservation.freezeId());
        }
    }

    private void settleSurcharge(QuotaBillingPort.Reservation reservation) {
        if (reservation != null) {
            agentContext.getRuntimeDependencies().getQuotaBillingPort()
                    .settle(reservation.freezeId(), reservation.reservedMicrocredits());
            if (agentContext.getAgentRunState() != null) {
                agentContext.getAgentRunState().recordChargedMicrocredits(reservation.reservedMicrocredits());
            }
        }
    }

    private void releaseSurcharge(QuotaBillingPort.Reservation reservation) {
        if (reservation != null) {
            agentContext.getRuntimeDependencies().getQuotaBillingPort().release(reservation.freezeId());
        }
    }

    /**
     * 调用 DeepSearch
     */
    public CompletableFuture<ToolResultPayload> callDeepSearchStream(DeepSearchRequest searchRequest,
                                                                     ToolArtifactSource artifactSource) {
        CompletableFuture<ToolResultPayload> future = new CompletableFuture<>();
        try {
            ReactorConfig reactorConfig = requireReactorConfig();
            String url = reactorConfig.getDeepSearchUrl() + "/v1/tool/deepsearch";
            log.info("{} deep_search request started queryChars={} stream={} contentStream={}",
                    agentContext.getRequestId(),
                    searchRequest.getQuery() == null ? 0 : searchRequest.getQuery().length(),
                    searchRequest.getStream(), searchRequest.getContent_stream());

            String[] interval = reactorConfig.getMessageInterval().getOrDefault("search", "5,20").split(",");
            int firstInterval = Integer.parseInt(interval[0]);
            int sendInterval = Integer.parseInt(interval[1]);
            AtomicInteger index = new AtomicInteger(1);
            AtomicReference<String> resultRef = new AtomicReference<>("搜索结果为空");
            AtomicReference<String> messageIdRef = new AtomicReference<>("");
            String toolCallId = artifactSource == null ? null : artifactSource.getToolCallId();
            StringBuilder stringBuilderIncr = new StringBuilder();
            StringBuilder stringBuilderAll = new StringBuilder();
            DeepSearchStructuredResultBuilder resultBuilder = new DeepSearchStructuredResultBuilder(searchRequest.getQuery());
            activeStreamSession = requireRemoteStreamPort().openStream(RemoteStreamRequest.builder()
                    .method("POST")
                    .url(url)
                    .headers(ReactorToolRequestHeaders.sse(reactorConfig))
                    .body(JSONObject.toJSONString(searchRequest))
                    .connectTimeoutSeconds(DEEP_SEARCH_CONNECT_TIMEOUT_SECONDS)
                    .readTimeoutSeconds(DEEP_SEARCH_IO_TIMEOUT_SECONDS)
                    .writeTimeoutSeconds(DEEP_SEARCH_IO_TIMEOUT_SECONDS)
                    .callTimeoutSeconds(DEEP_SEARCH_IO_TIMEOUT_SECONDS)
                    .build(), new RemoteStreamListener() {
                @Override
                public void onOpen() {
                    log.info("{} deep_search stream opened", agentContext.getRequestId());
                }

                @Override
                public void onLine(String line) {
                    try {
                        if (!line.startsWith("data:")) {
                            return;
                        }
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            return;
                        }
                        if (data.startsWith("heartbeat")) {
                            return;
                        }
                        int currentIndex = index.get();
                        if (currentIndex == 1 || currentIndex % 100 == 0) {
                            log.debug("{} deep_search event received sequence={} payloadChars={}",
                                    agentContext.getRequestId(), currentIndex, data.length());
                        }
                        DeepSearchrResponse searchResponse = JSONObject.parseObject(data, DeepSearchrResponse.class);
                        searchResponse.setToolCallId(toolCallId);
                        FileTool fileTool = new FileTool();
                        fileTool.setAgentContext(agentContext);
                        // 使用标准 SSE 客户端逐条消费事件，避免 extend 被上游缓冲后延迟透传。
                        if (searchResponse.getIsFinal()) {
                            if (agentContext.getIsStream()) {
                                searchResponse.setAnswer(stringBuilderAll.toString());
                            }
                            if (searchResponse.getAnswer().isEmpty()) {
                                log.error("{} deep search answer empty", agentContext.getRequestId());
                                resultRef.set("搜索结果为空");
                                return;
                            }
                            resultBuilder.recordFinalAnswer(searchResponse.getQuery(), searchResponse.getAnswer());
                            String fileName = StringUtil.removeSpecialChars(searchResponse.getQuery() + "的搜索结果.md");
                            String fileDesc = searchResponse.getAnswer()
                                    .substring(0, Math.min(searchResponse.getAnswer().length(), reactorConfig.getDeepSearchToolFileDescTruncateLen())) + "...";
                            FileRequest fileRequest = FileRequest.builder()
                                    .requestId(agentContext.getRequestId())
                                    .fileName(fileName)
                                    .description(fileDesc)
                                    .content(searchResponse.getAnswer())
                                    .build();
                            fileTool.uploadFile(fileRequest, false, false, artifactSource);
                            resultRef.set(searchResponse.getAnswer()
                                    .substring(0, Math.min(searchResponse.getAnswer().length(), reactorConfig.getDeepSearchToolMessageTruncateLen())));

                            agentContext.getPrinter().send(messageIdRef.get(), "deep_search", searchResponse, null, true);
                            return;
                        }

                        resultBuilder.recordEvent(searchResponse);

                        Map<String, Object> contentMap = new HashMap<>();
                        if (searchResponse.getSearchResult() != null
                                && searchResponse.getSearchResult().getQuery() != null
                                && searchResponse.getSearchResult().getDocs() != null) {
                            for (int idx = 0; idx < searchResponse.getSearchResult().getQuery().size(); idx++) {
                                contentMap.put(searchResponse.getSearchResult().getQuery().get(idx),
                                        searchResponse.getSearchResult().getDocs().get(idx));
                            }
                        }

                        if ("extend".equals(searchResponse.getMessageType())) {
                            messageIdRef.set(StringUtil.getUUID());
                            searchResponse.setSearchFinish(false);
                            agentContext.getPrinter().send(messageIdRef.get(), "deep_search", searchResponse, null, true);
                        } else if ("search".equals(searchResponse.getMessageType())) {
                            searchResponse.setSearchFinish(true);
                            agentContext.getPrinter().send(messageIdRef.get(), "deep_search", searchResponse, null, true);
                            FileRequest fileRequest = FileRequest.builder()
                                    .requestId(agentContext.getRequestId())
                                    .fileName(searchResponse.getQuery() + "_search_result.txt")
                                    .description(searchResponse.getQuery() + "...")
                                    .content(JSON.toJSONString(contentMap))
                                    .build();
                            fileTool.uploadFile(fileRequest, false, true, artifactSource);
                        } else if ("report".equals(searchResponse.getMessageType())) {
                            if (currentIndex == 1 && messageIdRef.get().isEmpty()) {
                                messageIdRef.set(StringUtil.getUUID());
                            }
                            stringBuilderIncr.append(searchResponse.getAnswer());
                            stringBuilderAll.append(searchResponse.getAnswer());
                            if (currentIndex == firstInterval || currentIndex % sendInterval == 0) {
                                searchResponse.setAnswer(stringBuilderIncr.toString());
                                agentContext.getPrinter().send(messageIdRef.get(), "deep_search", searchResponse, null, false);
                                stringBuilderIncr.setLength(0);
                            }
                            index.incrementAndGet();
                        }
                    } catch (Exception e) {
                            log.error("{} deep_search event handling failed errorType={}",
                                    agentContext.getRequestId(), e.getClass().getSimpleName());
                            if (!future.isDone()) {
                                future.completeExceptionally(e);
                            }
                        if (activeStreamSession != null) {
                            activeStreamSession.cancel();
                        }
                    }
                }

                @Override
                public void onClosed() {
                    activeStreamSession = null;
                    if (!future.isDone()) {
                        future.complete(resultBuilder.buildPayload(resultRef.get()));
                    }
                }

                @Override
                public void onFailure(Throwable throwable, Integer statusCode, String responseBody) {
                    activeStreamSession = null;
                    if (throwable == null && statusCode == null) {
                        if (!future.isDone()) {
                            future.complete(resultBuilder.buildPayload(resultRef.get()));
                        }
                        return;
                    }
                    log.error("{} deep_search upstream failed statusCode={} responseChars={} errorType={}",
                            agentContext.getRequestId(), statusCode,
                            responseBody == null ? 0 : responseBody.length(),
                            throwable == null ? "unknown" : throwable.getClass().getSimpleName());
                    if (!future.isDone()) {
                        future.completeExceptionally(throwable instanceof Exception
                                ? (Exception) throwable
                                : new RuntimeException(throwable));
                    }
                }
            });
        } catch (Exception e) {
            log.error("{} deep_search request failed errorType={}",
                    agentContext.getRequestId(), e.getClass().getSimpleName());
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * deep_search 失败时仍返回可解释 observation，避免主智能体拿到空结果。
     */
    private ToolResultPayload buildFailurePayload(String message) {
        return ToolResultPayload.failure(
                message,
                message,
                DeepSearchToolOutput.of("", message, null),
                message
        );
    }

    private ReactorConfig requireReactorConfig() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("DeepSearchTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireReactorConfig();
    }

    private RemoteStreamPort requireRemoteStreamPort() {
        if (agentContext == null || agentContext.getRuntimeDependencies() == null) {
            throw new IllegalStateException("DeepSearchTool 缺少 ReactorRuntimeDependencies");
        }
        return agentContext.getRuntimeDependencies().requireRemoteStreamPort();
    }
}
