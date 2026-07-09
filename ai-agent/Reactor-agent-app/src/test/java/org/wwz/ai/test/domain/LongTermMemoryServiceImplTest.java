package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.domain.agent.memory.LongTermMemoryServiceImpl;
import org.wwz.ai.domain.agent.memory.MemoryTurn;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.data.dto.VectorSaveReq;
import org.wwz.ai.domain.agent.reactor.service.VectorService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期跨会话向量记忆测试（mock VectorService，离线可跑）。
 */
public class LongTermMemoryServiceImplTest {

    private ReactorConfig enabledConfig() {
        ReactorConfig cfg = new ReactorConfig();
        ReflectionTestUtils.setField(cfg, "memoryEnabled", Boolean.TRUE);
        ReflectionTestUtils.setField(cfg, "longTermMemoryEnabled", Boolean.TRUE);
        ReflectionTestUtils.setField(cfg, "longTermMemoryCollection", "agent_conversation_memory");
        ReflectionTestUtils.setField(cfg, "longTermMemoryTopK", 5);
        ReflectionTestUtils.setField(cfg, "longTermMemoryScoreThreshold", 0.6f);
        ReflectionTestUtils.setField(cfg, "longTermMemoryDecayHalfLifeDays", 30);
        return cfg;
    }

    @Test
    public void shouldSaveTurnWithOwnerScopedPayload() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        Mockito.when(vectorService.saveVector(Mockito.any())).thenReturn(true);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn("user-1", "session-1", "req-1", "我喜欢 Java", "已记录你喜欢 Java"));

        ArgumentCaptor<VectorSaveReq> captor = ArgumentCaptor.forClass(VectorSaveReq.class);
        Mockito.verify(vectorService).saveVector(captor.capture());
        VectorSaveReq req = captor.getValue();
        Assert.assertEquals("agent_conversation_memory", req.getCollectionName());
        Assert.assertEquals(1, req.getDataList().size());
        VectorSaveReq.VectorData data = req.getDataList().get(0);
        Assert.assertTrue(data.getEmbeddingText().contains("我喜欢 Java"));
        Assert.assertTrue(data.getEmbeddingText().contains("已记录你喜欢 Java"));
        Assert.assertEquals("user-1", data.getPayloads().get("ownerId"));
        Assert.assertEquals("session-1", data.getPayloads().get("sessionId"));
        Assert.assertNotNull(data.getPayloads().get("ts"));
        Assert.assertTrue(data.getPayloads().get("text").toString().contains("我喜欢 Java"));
    }

    @Test
    public void shouldNoOpWhenLongTermDisabled() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        ReactorConfig cfg = enabledConfig();
        ReflectionTestUtils.setField(cfg, "longTermMemoryEnabled", Boolean.FALSE);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, cfg);

        service.save(new MemoryTurn("user-1", "session-1", "req-1", "q", "a"));
        Assert.assertTrue(service.recall("user-1", "session-1", "q").isEmpty());
        Mockito.verifyNoInteractions(vectorService);
    }

    @Test
    public void shouldRecallExcludingCurrentSessionAndRankByTimeDecay() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        long now = System.currentTimeMillis();
        long oneDayAgo = now - 86_400_000L;
        long sixtyDaysAgo = now - 60L * 86_400_000L;

        List<Map<String, Object>> hits = new ArrayList<>();
        hits.add(hit("session-current", "当前会话片段（应被排除）", 0.99d, now));
        hits.add(hit("session-old-a", "较久远但原始分高的片段", 0.90d, sixtyDaysAgo));
        hits.add(hit("session-old-b", "较新的片段", 0.80d, oneDayAgo));
        Mockito.when(vectorService.vectorRecall(Mockito.any())).thenReturn(hits);

        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());
        List<String> recalled = service.recall("user-1", "session-current", "问题");

        Assert.assertFalse(recalled.contains("当前会话片段（应被排除）"));
        Assert.assertEquals(2, recalled.size());
        // 60 天前的片段经半衰期(30天)衰减后应排在 1 天前片段之后
        Assert.assertEquals("较新的片段", recalled.get(0));
        Assert.assertEquals("较久远但原始分高的片段", recalled.get(1));
    }

    private Map<String, Object> hit(String sessionId, String text, double score, long ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("text", text);
        m.put("score", (float) score);
        m.put("ts", String.valueOf(ts));
        return m;
    }
}
