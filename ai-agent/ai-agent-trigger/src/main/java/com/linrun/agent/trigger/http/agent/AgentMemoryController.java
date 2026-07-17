package com.linrun.agent.trigger.http.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.domain.agent.memory.ConversationMemoryManager;
import com.linrun.agent.domain.agent.memory.MemoryQuery;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三层对话记忆只读检视接口（演示/排障用）。
 * 返回某会话为当前用户组装出的记忆注入块（长期跨会话召回 + 中期会话摘要/近期原文），
 * 便于面试演示与观察记忆分层效果。只读、按会话归属校验，不产生副作用。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/memory")
public class AgentMemoryController {

    @Resource
    private ConversationMemoryManager conversationMemoryManager;

    @Resource
    private ConversationSessionOwnershipService conversationSessionOwnershipService;

    @GetMapping("/inspect")
    public Response<Map<String, Object>> inspect(@RequestParam("sessionId") String sessionId,
                                                 @RequestParam(value = "query", required = false) String query) {
        if (!StringUtils.hasText(sessionId)) {
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("sessionId不能为空")
                    .build();
        }
        try {
            String ownerId = OwnerRequestContext.requireOwnerIdAsString();
            conversationSessionOwnershipService.ensureExistingSessionAccessible(ownerId, sessionId);
            String memoryBlock = conversationMemoryManager.assembleHistoryBlock(
                    new MemoryQuery(ownerId, sessionId, null, query == null ? "" : query));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", sessionId);
            data.put("query", query == null ? "" : query);
            data.put("memoryBlock", memoryBlock);
            data.put("length", memoryBlock == null ? 0 : memoryBlock.length());
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.warn("memory inspect failed, sessionId={}", sessionId, e);
            return Response.<Map<String, Object>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(StringUtils.hasText(e.getMessage()) ? e.getMessage() : ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }
}
