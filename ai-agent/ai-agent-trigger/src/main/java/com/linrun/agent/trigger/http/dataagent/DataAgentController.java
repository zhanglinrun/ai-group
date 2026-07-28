package com.linrun.agent.trigger.http.dataagent;


import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import com.linrun.agent.domain.agent.rag.DataAgentQueryService;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnEsRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnVectorRecallReq;

import jakarta.annotation.Resource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/data")
public class DataAgentController {

    @Resource
    private DataAgentQueryService dataAgentQueryService;

    @PostMapping(value = "vectorRecall")
    public List<Map<String, Object>> vectorRecall(@RequestBody ColumnVectorRecallReq req) {
        return dataAgentQueryService.vectorRecall(req);
    }

    @PostMapping(value = "esRecall")
    public List<Map<String, Object>> esRecall(@RequestBody ColumnEsRecallReq req) throws IOException {
        return dataAgentQueryService.esRecall(req);
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
