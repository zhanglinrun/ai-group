package com.linrun.agent.domain.agent.rag;


import com.linrun.agent.types.common.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import lombok.RequiredArgsConstructor;
import com.linrun.agent.domain.agent.reactor.data.dto.ChatSchemaDto;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnVectorRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.NL2SQLReq;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class TableRagService {

    private final SchemaRecallService schemaRecallService;

    public List<ChatSchemaDto> tableRag(NL2SQLReq req) throws IOException {
        ColumnVectorRecallReq recall = new ColumnVectorRecallReq();
        recall.setQuery(req.getQuery());
        recall.setModelCodeList(req.getModelCodeList());
        List<Map<String, Object>> data = schemaRecallService.vectorRecall(recall);
        if (CollectionUtils.isEmpty(data)) {
            log.warn("{},{} tableRag result data is empty，降级为空结果，由上游决定是否回退",
                    req.getTraceId(), req.getRequestId());
            return List.of();
        }
        Map<String, ChatSchemaDto> unique = new LinkedHashMap<>();
        for (Map<String, Object> row : data) {
            ChatSchemaDto schema = JsonUtils.convertValue(row, ChatSchemaDto.class);
            unique.putIfAbsent(schema.getModelCode() + ':' + schema.getColumnId(), schema);
        }
        return List.copyOf(unique.values());
    }
}
