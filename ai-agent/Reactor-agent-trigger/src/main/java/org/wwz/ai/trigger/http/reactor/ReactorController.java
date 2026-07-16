package org.wwz.ai.trigger.http.reactor;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.wwz.ai.application.agent.visitor.ConversationSessionOwnershipApplicationService;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.query.GptQueryIngressService;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;
import org.wwz.ai.trigger.http.reactor.support.SseEmitterAgentSessionStream;
import org.wwz.ai.trigger.http.reactor.support.SseLifecycleSupport;
import org.wwz.ai.types.agent.config.AgentExecutorNames;
import org.wwz.ai.types.agent.config.AgentExecutorProperties;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;

import java.io.UnsupportedEncodingException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


// 仅本地调试；默认关闭，避免绕过配额计费。
@Slf4j
@RestController
@RequestMapping("/1")
@ConditionalOnProperty(name = "ai-agent.debug-endpoints.enabled", havingValue = "true")
public class ReactorController {
    @Autowired
    protected ReactorConfig reactorConfig;
    @Autowired
    private GptQueryIngressService gptQueryIngressService;
    @Autowired
    private IAgentDispatchService agentDispatchService;

    @Autowired
    private ConversationSessionOwnershipApplicationService conversationSessionOwnershipApplicationService;

    @Resource
    private AgentExecutorProperties agentExecutorProperties;

    @Resource
    @Qualifier(AgentExecutorNames.DISPATCH_EXECUTOR)
    private Executor dispatchExecutor;

    @Resource
    @Qualifier(AgentExecutorNames.HEARTBEAT_SCHEDULER)
    private TaskScheduler heartbeatScheduler;

    /**
     * 执行智能体调度
     * @param request
     * @return
     * @throws UnsupportedEncodingException
     */
    @PostMapping("/AutoAgent")
    public SseEmitter AutoAgent(@RequestBody AgentRequest request) throws UnsupportedEncodingException {

        log.info("{} debug auto agent request accepted agentType={} stream={} fileCount={} resumeRequested={}",
                request.getRequestId(), request.getAgentType(), request.getIsStream(),
                request.getSessionFiles() == null ? 0 : request.getSessionFiles().size(),
                StringUtils.isNotBlank(request.getResumeCheckpointId()));

        Long AUTO_AGENT_SSE_TIMEOUT = 600 * 1000L;

        SseEmitter emitter = SseLifecycleSupport.createEmitter(AUTO_AGENT_SSE_TIMEOUT);
        try {
            String ownerId = resolveOwnerId(request);
            request.setOwnerId(ownerId);
            conversationSessionOwnershipApplicationService.ensureSessionAccessible(
                    ownerId,
                    request.getSessionId(),
                    request.getQuery()
            );
        } catch (Exception e) {
            log.warn("{} reject auto agent request before dispatch errorType={}",
                    request.getRequestId(), e.getClass().getSimpleName());
            emitter.completeWithError(e);
            return emitter;
        }
        // SSE心跳
        ScheduledFuture<?> heartbeatFuture = SseLifecycleSupport.startHeartbeat(
                heartbeatScheduler,
                emitter,
                request.getRequestId(),
                agentExecutorProperties.getHeartbeat().getIntervalMillis(),
                log
        );
        // 监听SSE事件
        SseLifecycleSupport.registerLifecycle(emitter, request.getRequestId(), heartbeatFuture, log);

        try {
            AgentExecutorSupport.execute(dispatchExecutor, "dispatch", () -> {
                try {
                    agentDispatchService.dispatch(request, new SseEmitterAgentSessionStream(emitter));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("{} auto agent error errorType={}",
                            request.getRequestId(), e.getClass().getSimpleName());
                    emitter.completeWithError(e);
                }
            });
        } catch (AgentExecutorBusyException e) {
            log.warn("{} dispatch rejected errorType={}",
                    request.getRequestId(), e.getClass().getSimpleName());
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("{} auto agent error errorType={}",
                    request.getRequestId(), e.getClass().getSimpleName());
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private String resolveOwnerId(AgentRequest request) {
        return String.valueOf(OwnerRequestContext.requireOwnerId());
    }

    /**
     * 探活接口
     *
     * @return
     */
    @RequestMapping(value = "/web/health", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("ok");
    }


    /**
     * 处理Agent流式增量查询请求，返回SSE事件流
     * @param params 查询请求参数对象，包含GPT查询所需信息
     * @return 返回SSE事件发射器，用于流式传输增量响应结果
     */
    @RequestMapping(value = "/web/api/v1/gpt/queryAgentStreamIncr", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryAgentStreamIncr(@RequestBody GptQueryReq params) {
        SseEmitter emitter = SseLifecycleSupport.createEmitter(TimeUnit.HOURS.toMillis(1));
        SseLifecycleSupport.registerLifecycle(emitter,
                Objects.toString(params.getRequestId(), "legacy-gpt-query"),
                null,
                log);
        try {
            gptQueryIngressService.queryAgentStreamIncr(params, new SseEmitterAgentSessionStream(emitter));
        } catch (Exception e) {
            log.warn("reject gpt stream request {} errorType={}",
                    params.getRequestId(), e.getClass().getSimpleName());
            emitter.completeWithError(e);
        }
        return emitter;
    }

}
