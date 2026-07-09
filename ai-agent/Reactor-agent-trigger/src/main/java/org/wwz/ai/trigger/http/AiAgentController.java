package org.wwz.ai.trigger.http;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.wwz.ai.api.IAiAgentService;
import org.wwz.ai.api.dto.AiAgentResponseDTO;
import org.wwz.ai.api.dto.ArmoryAgentRequestDTO;
import org.wwz.ai.api.dto.ArmoryApiRequestDTO;
import org.wwz.ai.api.dto.AutoAgentRequestDTO;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.armory.IArmoryService;
import org.wwz.ai.application.agent.dispatch.IAgentDispatchService;
import org.wwz.ai.application.agent.query.GptQueryIngressService;
import org.wwz.ai.application.agent.query.IGptQueryApplicationService;
import org.wwz.ai.application.agent.visitor.ConversationSessionOwnershipApplicationService;
import org.wwz.ai.domain.agent.runtime.executor.AgentExecutorSupport;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;
import org.wwz.ai.domain.agent.model.valobj.AiAgentVO;
import org.wwz.ai.types.agent.config.AgentExecutorNames;
import org.wwz.ai.types.agent.config.AgentExecutorProperties;
import org.wwz.ai.types.agent.exception.AgentExecutorBusyException;
import org.wwz.ai.application.agent.quota.AgentRunSettlementService;
import org.wwz.ai.application.agent.quota.MemberQuotaBillingService;
import org.wwz.ai.application.agent.quota.QuotaInsufficientException;
import org.wwz.ai.application.agent.stream.QuotaBillingAgentSessionStream;
import org.wwz.ai.types.agent.owner.OwnerRequestContext;
import org.wwz.ai.types.enums.ResponseCode;
import com.alibaba.fastjson.JSON;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.wwz.ai.trigger.http.reactor.support.SseLifecycleSupport;
import org.wwz.ai.trigger.http.reactor.support.SseEmitterAgentSessionStream;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.wwz.ai.domain.agent.reactor.model.req.GptQueryReq;
import org.apache.commons.lang3.StringUtils;

/**
 * AutoAgent 自动智能对话体
 */
@Slf4j
@RestController
@RequestMapping("/")
public class AiAgentController implements IAiAgentService {

    @Autowired
    protected ReactorConfig reactorConfig;

    @Resource
    private IAgentDispatchService agentDispatchService;

    @Resource
    private IArmoryService armoryService;

    @Resource
    private GptQueryIngressService gptQueryIngressService;

    @Resource
    private IGptQueryApplicationService gptQueryApplicationService;

    @Resource
    private ConversationSessionOwnershipApplicationService conversationSessionOwnershipApplicationService;

    @Resource
    private AgentExecutorProperties agentExecutorProperties;

    @Resource
    @Qualifier(AgentExecutorNames.DISPATCH_EXECUTOR)
    private Executor dispatchExecutor;

    @Resource
    private MemberQuotaBillingService memberQuotaBillingService;

    @Resource
    private AgentRunSettlementService agentRunSettlementService;

    @Resource
    @Qualifier(AgentExecutorNames.HEARTBEAT_SCHEDULER)
    private TaskScheduler heartbeatScheduler;

    /**
     * 执行智能体调度
     */
    @PostMapping("/AutoAgent")
    public SseEmitter AutoAgent(@RequestBody AgentRequest request) throws UnsupportedEncodingException {

        log.info("{} auto agent request: {}", request.getRequestId(), JSON.toJSONString(request));

        Long AUTO_AGENT_SSE_TIMEOUT = 600 * 1000L;

        SseEmitter emitter = SseLifecycleSupport.createEmitter(AUTO_AGENT_SSE_TIMEOUT);
        String freezeId = null;
        try {
            Long ownerId = resolveOwnerId(request);
            request.setOwnerId(String.valueOf(ownerId));
            conversationSessionOwnershipApplicationService.ensureSessionAccessible(
                    request.getOwnerId(),
                    request.getSessionId(),
                    request.getQuery()
            );
            freezeId = memberQuotaBillingService.freezeForAgentRun(ownerId, request);
        } catch (QuotaInsufficientException e) {
            log.warn("{} quota rejected auto agent request", request.getRequestId(), e);
            emitter.completeWithError(e);
            return emitter;
        } catch (Exception e) {
            log.warn("{} reject auto agent request before dispatch", request.getRequestId(), e);
            emitter.completeWithError(e);
            return emitter;
        }
        ScheduledFuture<?> heartbeatFuture = SseLifecycleSupport.startHeartbeat(
                heartbeatScheduler,
                emitter,
                request.getRequestId(),
                agentExecutorProperties.getHeartbeat().getIntervalMillis(),
                log
        );

        SseLifecycleSupport.registerLifecycle(emitter, request.getRequestId(), heartbeatFuture, log);

        final String quotaFreezeId = freezeId;
        try {
            AgentExecutorSupport.execute(dispatchExecutor, "dispatch", () -> {
                // 结算统一收口到 QuotaBillingAgentSessionStream：settled 保护保证 confirm/release 至多发生一次，
                // confirm 自身异常也不会再触发 release。
                QuotaBillingAgentSessionStream billingStream = new QuotaBillingAgentSessionStream(
                        new SseEmitterAgentSessionStream(emitter), memberQuotaBillingService, quotaFreezeId);
                try {
                    agentDispatchService.dispatch(request, billingStream);
                    // dispatch 正常返回不代表执行成功（agent 失败分支会吞异常），以账本 run 终态决定 confirm/release。
                    if (agentRunSettlementService.shouldReleaseAfterDispatch(request.getRequestId())) {
                        billingStream.completeWithFailureSettlement();
                    } else {
                        billingStream.complete();
                    }
                } catch (Exception e) {
                    log.error("{} auto agent error", request.getRequestId(), e);
                    try {
                        billingStream.completeWithError(e);
                    } catch (Exception ex) {
                        log.warn("{} emitter completeWithError failed", request.getRequestId(), ex);
                    }
                }
            });
        } catch (AgentExecutorBusyException e) {
            memberQuotaBillingService.release(quotaFreezeId);
            log.warn("{} dispatch rejected", request.getRequestId(), e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private Long resolveOwnerId(AgentRequest request) {
        return OwnerRequestContext.requireOwnerId();
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
            log.warn("reject gpt stream request {}", params.getRequestId(), e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

//    @RequestMapping(value = "auto_agent1", method = RequestMethod.POST)
//    public ResponseBodyEmitter autoAgent(@RequestBody AutoAgentRequestDTO request, HttpServletResponse response) {
//        log.info("AutoAgent流式执行请求开始，请求信息：{}", JSON.toJSONString(request));
//
//        try {
//            // 设置SSE响应头
//            response.setContentType("text/event-stream");
//            response.setCharacterEncoding("UTF-8");
//            response.setHeader("Cache-Control", "no-cache");
//            response.setHeader("Connection", "keep-alive");
//
//            // 1. 创建流式输出对象
//            ResponseBodyEmitter emitter = new ResponseBodyEmitter(Long.MAX_VALUE);
//
//            // 2. 构建执行命令实体
//            ExecuteCommandEntity executeCommandEntity = ExecuteCommandEntity.builder()
//                    .aiAgentId(request.getAiAgentId())
//                    .message(request.getMessage())
//                    .sessionId(request.getSessionId())
//                    .maxStep(request.getMaxStep())
//                    .build();
//
////            // 3. 调度处理
////            agentDispatchService.dispatch(executeCommandEntity, emitter);
//
//            return emitter;
//
//        } catch (Exception e) {
//            log.error("AutoAgent请求处理异常：{}", e.getMessage(), e);
//            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter();
//            try {
//                errorEmitter.send("请求处理异常：" + e.getMessage());
//                errorEmitter.complete();
//            } catch (Exception ex) {
//                log.error("发送错误信息失败：{}", ex.getMessage(), ex);
//            }
//            return errorEmitter;
//        }
//    }


    @RequestMapping(value = "armory_agent", method = RequestMethod.POST)
    @Override
    public Response<Boolean> armoryAgent(@RequestBody ArmoryAgentRequestDTO request) {
        log.info("装配智能体请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            // 参数校验
            if (request == null || request.getAgentId() == null || request.getAgentId().trim().isEmpty()) {
                log.warn("装配智能体请求参数无效：agentId为空");
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("agentId不能为空")
                        .data(false)
                        .build();
            }

            // 调用装配服务
            armoryService.acceptArmoryAgent(request.getAgentId());

            log.info("装配智能体成功，agentId：{}", request.getAgentId());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("装配成功")
                    .data(true)
                    .build();

        } catch (Exception e) {
            log.error("装配智能体失败，agentId：{}，错误信息：{}",
                    request != null ? request.getAgentId() : "null", e.getMessage(), e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("装配失败：" + e.getMessage())
                    .data(false)
                    .build();
        }
    }

    @RequestMapping(value = "query_available_agents", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentResponseDTO>> queryAvailableAgents() {
        log.info("查询可用智能体列表请求开始");

        try {
            // 调用装配服务查询可用智能体
            List<AiAgentVO> aiAgentVOList = armoryService.queryAvailableAgents();

            // 转换为响应DTO
            List<AiAgentResponseDTO> responseList = new ArrayList<>();
            for (AiAgentVO aiAgentVO : aiAgentVOList) {
                AiAgentResponseDTO responseDTO = AiAgentResponseDTO.builder()
                        .agentId(aiAgentVO.getAgentId())
                        .agentName(aiAgentVO.getAgentName())
                        .description(aiAgentVO.getDescription())
                        .channel(aiAgentVO.getChannel())
                        .strategy(aiAgentVO.getStrategy())
                        .status(aiAgentVO.getStatus())
                        .build();
                responseList.add(responseDTO);
            }

            log.info("查询可用智能体列表成功，共{}个智能体", responseList.size());
            return Response.<List<AiAgentResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("查询成功")
                    .data(responseList)
                    .build();

        } catch (Exception e) {
            log.error("查询可用智能体列表失败，错误信息：{}", e.getMessage(), e);
            return Response.<List<AiAgentResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败：" + e.getMessage())
                    .data(new ArrayList<>())
                    .build();
        }
    }

    @RequestMapping(value = "armory_api", method = RequestMethod.POST)
    @Override
    public Response<Boolean> armoryApi(@RequestBody ArmoryApiRequestDTO request) {
        log.info("装配API请求开始，请求信息：{}", JSON.toJSONString(request));

        try {
            // 参数校验
            if (request == null || request.getApiId() == null || request.getApiId().trim().isEmpty()) {
                log.warn("装配API请求参数无效：apiId为空");
                return Response.<Boolean>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("apiId不能为空")
                        .data(false)
                        .build();
            }

            // 调用装配服务
            armoryService.acceptArmoryAgentClientModelApi(request.getApiId());

            log.info("装配API成功，apiId：{}", request.getApiId());
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("装配成功")
                    .data(true)
                    .build();

        } catch (Exception e) {
            log.error("装配API失败，apiId：{}，错误信息：{}",
                    request != null ? request.getApiId() : "null", e.getMessage(), e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("装配失败：" + e.getMessage())
                    .data(false)
                    .build();
        }
    }

}
