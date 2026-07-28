package com.linrun.agent.domain.agent.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.reactor.data.QueryResult;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnEsRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnVectorRecallReq;
import com.linrun.agent.domain.agent.reactor.service.ChatModelInfoService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据问答稳定领域实现。
 * 通过 rag 子域语义收口 legacy dataagent 主链路，避免 case 继续直连旧 bridge。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAgentQueryServiceImpl implements DataAgentQueryService {

    private final ChatModelInfoService chatModelInfoService;
    private final SchemaRecallService schemaRecallService;

    @Override
    public List<Map<String, Object>> vectorRecall(ColumnVectorRecallReq req) {
        return schemaRecallService.vectorRecall(req);
    }

    @Override
    public List<Map<String, Object>> esRecall(ColumnEsRecallReq req) throws IOException {
        return schemaRecallService.esValueRecall(req);
    }

    @Override
    public List<?> queryAllModelsWithSchema() {
        return chatModelInfoService.queryAllModelsWithSchema();
    }

    @Override
    public QueryResult previewData(String modelCode) throws Exception {
        return chatModelInfoService.previewData(modelCode);
    }
}
