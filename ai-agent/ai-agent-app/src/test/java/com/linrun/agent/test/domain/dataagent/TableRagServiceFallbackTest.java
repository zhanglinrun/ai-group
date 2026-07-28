package com.linrun.agent.test.domain.dataagent;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.reactor.data.dto.NL2SQLReq;
import com.linrun.agent.domain.agent.rag.SchemaRecallService;
import com.linrun.agent.domain.agent.rag.TableRagService;

import java.util.List;

/**
 * table_rag 空召回降级回归测试。
 */
public class TableRagServiceFallbackTest {

    @Test
    public void shouldReturnEmptyListWhenTableRagRespondsWithEmptyData() throws Exception {
        SchemaRecallService schemaRecallService = Mockito.mock(SchemaRecallService.class);
        Mockito.when(schemaRecallService.vectorRecall(Mockito.any())).thenReturn(List.of());
        TableRagService tableRagService = new TableRagService(schemaRecallService);

        NL2SQLReq req = new NL2SQLReq();
        req.setTraceId("trace-1");
        req.setRequestId("req-1");

        List<?> result = tableRagService.tableRag(req);

        Assert.assertNotNull(result);
        Assert.assertTrue(result.isEmpty());
    }
}
