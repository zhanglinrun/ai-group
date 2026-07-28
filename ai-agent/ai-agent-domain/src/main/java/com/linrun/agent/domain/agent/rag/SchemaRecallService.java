package com.linrun.agent.domain.agent.rag;


import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import com.linrun.agent.domain.agent.reactor.config.data.DataAgentConstants;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnEsRecallReq;
import com.linrun.agent.domain.agent.reactor.data.dto.ColumnVectorRecallReq;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.types.common.JsonUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SchemaRecallService {

    @Autowired(required = false)
    RestHighLevelClient dataAgentEsClient;
    private final PgVectorMemoryRepository memoryRepository;

    public SchemaRecallService(ObjectProvider<PgVectorMemoryRepository> memoryRepository) {
        this.memoryRepository = memoryRepository.getIfAvailable();
    }


    public List<Map<String, Object>> vectorRecall(ColumnVectorRecallReq recallReq) {
        if (memoryRepository == null || recallReq == null) {
            return List.of();
        }
        Map<String, Object> filters = new HashMap<>();
        filters.put("modelCode", recallReq.getModelCodeList());
        List<Map<String, Object>> rows = memoryRepository.recallByVector(
                DataAgentConstants.SCHEMA_OWNER, recallReq.getQuery(),
                List.of(DataAgentConstants.SCHEMA_DOC_TYPE), filters,
                recallReq.getLimit(), recallReq.getScoreThreshold());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> schema = JsonUtils.parseObject(
                    String.valueOf(row.get("metadata_json")), Map.class);
            if (schema != null) {
                schema.put("_score", row.get("score"));
                result.add(schema);
            }
        }
        return result;
    }

    public List<Map<String, Object>> esValueRecall(ColumnEsRecallReq req) throws IOException {
        if (dataAgentEsClient == null) {
            log.warn("ES 客户端不可用，返回空的列值召回结果");
            return new ArrayList<>();
        }
        SearchRequest searchRequest = new SearchRequest(DataAgentConstants.COLUMN_VALUE_ES_INDEX);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();
        boolQueryBuilder.filter(QueryBuilders.termsQuery("modelCode", req.getModelCodeList()));
        boolQueryBuilder.must(QueryBuilders.matchQuery("value", req.getQuery()));
        sourceBuilder.query(boolQueryBuilder);
        sourceBuilder.sort(SortBuilders.scoreSort().order(SortOrder.DESC));
        sourceBuilder.size(req.getLimit());
        log.info("esValueRecall query params:{}", sourceBuilder);

        searchRequest.source(sourceBuilder);
        SearchResponse search = dataAgentEsClient.search(searchRequest, RequestOptions.DEFAULT);
        SearchHit[] hits = search.getHits().getHits();
        List<Map<String, Object>> dataList = new ArrayList<>();
        for (SearchHit hit : hits) {
            Map<String, Object> row = hit.getSourceAsMap();
            row.put("_score", hit.getScore());
            dataList.add(row);
        }
        return dataList;
    }
}
