package com.linrun.agent.domain.agent.rag;

import com.linrun.agent.domain.agent.reactor.data.QueryResult;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnEsRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnVectorRecallReq;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 数据问答稳定领域 seam。
 * 收口 schema recall、NL2SQL、问数取数与模型预览等查询能力。
 */
public interface DataAgentQueryService {

    List<Map<String, Object>> vectorRecall(ColumnVectorRecallReq req);

    List<Map<String, Object>> esRecall(ColumnEsRecallReq req) throws IOException;

    List<?> queryAllModelsWithSchema();

    QueryResult previewData(String modelCode) throws Exception;
}
