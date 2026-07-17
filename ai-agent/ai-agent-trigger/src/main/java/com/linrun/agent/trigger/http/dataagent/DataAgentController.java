package com.linrun.agent.trigger.http.dataagent;


import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.linrun.agent.domain.agent.rag.DataAgentQueryService;
import com.linrun.agent.domain.agent.reactor.data.dto.ChatQueryData;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnEsRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnVectorRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.NL2SQLReq;
import com.linrun.agent.domain.agent.reactor.model.req.DataAgentChatReq;
import com.linrun.agent.trigger.http.reactor.support.SseEmitterAgentSessionStream;
import com.linrun.agent.trigger.http.reactor.support.SseLifecycleSupport;

import javax.annotation.Resource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/data")
public class DataAgentController {

    @Resource
    private DataAgentQueryService dataAgentQueryService;

    @PostMapping(value = "queryModelInfo")
    public NL2SQLReq vectorRecall(@RequestBody JSONObject req) {
        return dataAgentQueryService.queryAllSchemaNl2SqlReq();
    }

    @PostMapping(value = "vectorRecall")
    public List<Map<String, Object>> vectorRecall(@RequestBody ColumnVectorRecallReq req) {
        return dataAgentQueryService.vectorRecall(req);
    }

    @PostMapping(value = "esRecall")
    public List<Map<String, Object>> esRecall(@RequestBody ColumnEsRecallReq req) throws IOException {
        return dataAgentQueryService.esRecall(req);
    }

    @PostMapping(value = "chatQuery", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatQuery(@RequestBody DataAgentChatReq req) throws Exception {
        SseEmitter emitter = SseLifecycleSupport.createEmitter(TimeUnit.HOURS.toMillis(1));
        SseLifecycleSupport.registerLifecycle(emitter,
                Objects.toString(req.getTraceId(), "data-agent-chat"),
                null,
                log);
        dataAgentQueryService.chatQuery(req, new SseEmitterAgentSessionStream(emitter));
        return emitter;
    }

    @PostMapping(value = "apiChatQuery")
    public List<ChatQueryData> apiChatQuery(@RequestBody DataAgentChatReq req) {
        return dataAgentQueryService.apiChatQuery(req);
    }


    @PostMapping(value = "testQuery")
    public Object testQuery(@RequestBody DataAgentChatReq req) throws Exception {
        return dataAgentQueryService.testQuery(req);
    }

    @PostMapping(value = "getNl2SqlReq")
    public NL2SQLReq getNl2SqlReq(@RequestBody DataAgentChatReq req) throws Exception {
        return dataAgentQueryService.getNl2SqlReq(req.getContent());
    }

    @GetMapping(value = "allModels")
    public Map<String, Object> allModels() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", dataAgentQueryService.queryAllModelsWithSchema());
        return result;
    }

    @GetMapping(value = "previewData")
    public Map<String, Object> previewData(@RequestParam("modelCode") String modelCode) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", dataAgentQueryService.previewData(modelCode));
        return result;
    }

}
