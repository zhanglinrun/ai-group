package com.linrun.agent.test.domain;

import io.qdrant.client.grpc.Points;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import com.linrun.agent.domain.agent.memory.LongTermMemoryEntry;
import com.linrun.agent.domain.agent.memory.LongTermMemoryServiceImpl;
import com.linrun.agent.domain.agent.memory.LongTermMemoryType;
import com.linrun.agent.domain.agent.memory.MemoryTurn;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.reactor.data.dto.VectorSaveReq;
import com.linrun.agent.domain.agent.reactor.service.VectorService;

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
    public void shouldSaveExplicitPreferenceWithOwnerScopedPayload() {
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
        Assert.assertFalse(data.getEmbeddingText().contains("已记录你喜欢 Java"));
        Assert.assertEquals("user-1", data.getPayloads().get("ownerId"));
        Assert.assertEquals("session-1", data.getPayloads().get("sessionId"));
        Assert.assertNotNull(data.getPayloads().get("ts"));
        Assert.assertTrue(data.getPayloads().get("text").toString().contains("我喜欢 Java"));
        Assert.assertEquals("PREFERENCE", data.getPayloads().get("memoryType"));
        Assert.assertEquals("explicit-user-memory", data.getPayloads().get("source"));
        Assert.assertTrue(Long.parseLong(data.getPayloads().get("version").toString()) > 1L);
        Assert.assertNotNull(data.getPayloads().get("expiresAt"));
        Assert.assertNotNull(data.getUuid());
        Assert.assertEquals(
                List.of("ownerId", "memoryId", "memoryKey", "memoryType", "sessionId"),
                req.getKeywordIndexFields());
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

    @Test
    public void shouldRejectOrdinaryConversationTurnFromLongTermMemory() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn(
                "user-1", "session-1", "req-ordinary", "解释一下 Java 虚拟线程", "这里是回答摘要"));

        Mockito.verifyNoInteractions(vectorService);
    }

    @Test
    public void shouldRejectThirdPersonPreferenceQuestion() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn(
                "user-1", "session-1", "req-question", "为什么大家喜欢 Java？", "因为生态成熟"));

        Mockito.verifyNoInteractions(vectorService);
    }

    @Test
    public void shouldRejectFirstPersonPreferenceQuestion() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn(
                "user-1", "session-1", "req-question", "我为什么喜欢 Java？", "可能因为类型系统"));

        Mockito.verifyNoInteractions(vectorService);
    }

    @Test
    public void shouldRejectFirstPersonPreferenceQuestionWithoutPunctuation() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn(
                "user-1", "session-1", "req-question-particle", "我喜欢 Java 吗", "这是一个问题"));
        service.save(new MemoryTurn(
                "user-1", "session-1", "req-question-whether", "我是否喜欢 Java", "这是一个问题"));

        Mockito.verifyNoInteractions(vectorService);
    }

    @Test
    public void shouldUseIndependentStableSlotsForNameAndSchool() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        Mockito.when(vectorService.saveVector(Mockito.any())).thenReturn(true);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn(
                "user-1", "session-1", "req-name", "请记住：我叫小王", "已记录"));
        service.save(new MemoryTurn(
                "user-1", "session-1", "req-school", "请记住：我就读于浙江大学", "已记录"));

        ArgumentCaptor<VectorSaveReq> captor = ArgumentCaptor.forClass(VectorSaveReq.class);
        Mockito.verify(vectorService, Mockito.times(2)).saveVector(captor.capture());
        VectorSaveReq.VectorData name = captor.getAllValues().get(0).getDataList().get(0);
        VectorSaveReq.VectorData school = captor.getAllValues().get(1).getDataList().get(0);
        Assert.assertEquals("fact:user-name", name.getPayloads().get("memoryKey"));
        Assert.assertEquals("fact:user-school", school.getPayloads().get("memoryKey"));
        Assert.assertNotEquals(name.getUuid(), school.getUuid());
    }

    @Test
    public void shouldUpsertSameProfileSlotWithStableVectorId() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        Mockito.when(vectorService.saveVector(Mockito.any())).thenReturn(true);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn(
                "user-1", "session-1", "req-name-1", "请记住：我叫小王", "已记录"));
        service.save(new MemoryTurn(
                "user-1", "session-2", "req-name-2", "请记住：我的名字是王明", "已更新"));

        ArgumentCaptor<VectorSaveReq> captor = ArgumentCaptor.forClass(VectorSaveReq.class);
        Mockito.verify(vectorService, Mockito.times(2)).saveVector(captor.capture());
        VectorSaveReq.VectorData first = captor.getAllValues().get(0).getDataList().get(0);
        VectorSaveReq.VectorData second = captor.getAllValues().get(1).getDataList().get(0);
        Assert.assertEquals("fact:user-name", first.getPayloads().get("memoryKey"));
        Assert.assertEquals(first.getPayloads().get("memoryKey"), second.getPayloads().get("memoryKey"));
        Assert.assertEquals(first.getUuid(), second.getUuid());
    }

    @Test
    public void shouldUpsertResponsePreferenceByStableSemanticSlot() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        Mockito.when(vectorService.saveVector(Mockito.any())).thenReturn(true);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        service.save(new MemoryTurn(
                "user-1", "session-1", "req-style-1", "请记住：回答优先使用英文", "已记录"));
        service.save(new MemoryTurn(
                "user-1", "session-2", "req-style-2", "更新偏好：以后请使用中文，并且先给结论", "已更新"));

        ArgumentCaptor<VectorSaveReq> captor = ArgumentCaptor.forClass(VectorSaveReq.class);
        Mockito.verify(vectorService, Mockito.times(2)).saveVector(captor.capture());
        VectorSaveReq.VectorData first = captor.getAllValues().get(0).getDataList().get(0);
        VectorSaveReq.VectorData second = captor.getAllValues().get(1).getDataList().get(0);
        Assert.assertEquals("preference:response-style", first.getPayloads().get("memoryKey"));
        Assert.assertEquals(first.getPayloads().get("memoryKey"), second.getPayloads().get("memoryKey"));
        Assert.assertEquals(first.getUuid(), second.getUuid());
        Assert.assertFalse(second.getEmbeddingText().contains("已更新"));
    }

    @Test
    public void shouldUseStableVectorIdForIdempotentRetry() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        Mockito.when(vectorService.saveVector(Mockito.any())).thenReturn(true);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());
        MemoryTurn sameTurn = new MemoryTurn(
                "user-1", "session-1", "request-idempotent", "我偏好简洁回答", "已记录");

        service.save(sameTurn);
        service.save(sameTurn);

        ArgumentCaptor<VectorSaveReq> captor = ArgumentCaptor.forClass(VectorSaveReq.class);
        Mockito.verify(vectorService, Mockito.times(2)).saveVector(captor.capture());
        Assert.assertEquals(
                captor.getAllValues().get(0).getDataList().get(0).getUuid(),
                captor.getAllValues().get(1).getDataList().get(0).getUuid());
    }

    @Test
    public void shouldResolveVersionConflictAndDiscardExpiredMemory() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        long now = System.currentTimeMillis();
        List<Map<String, Object>> hits = List.of(
                structuredHit("pref-v1", "answer-style", "旧偏好：详细回答", "PREFERENCE",
                        1, now - 10_000L, now + 86_400_000L, 0.95d),
                structuredHit("pref-v2", "answer-style", "新偏好：简洁回答", "PREFERENCE",
                        2, now - 1_000L, now + 86_400_000L, 0.80d),
                structuredHit("expired", "old-fact", "已经过期的事实", "FACT",
                        1, now - 20_000L, now - 1L, 0.99d),
                structuredHit("fact-1", "project-language", "项目使用 Java 21", "FACT",
                        1, now - 2_000L, now + 86_400_000L, 0.70d)
        );
        Mockito.when(vectorService.vectorRecall(Mockito.any())).thenReturn(hits);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        List<LongTermMemoryEntry> entries = service.recallEntries("user-1", "current-session", "回答偏好");

        Assert.assertEquals(2, entries.size());
        Assert.assertTrue(entries.stream().noneMatch(entry -> entry.getContent().contains("过期")));
        LongTermMemoryEntry preference = entries.stream()
                .filter(entry -> entry.getType() == LongTermMemoryType.PREFERENCE)
                .findFirst()
                .orElseThrow();
        Assert.assertEquals("新偏好：简洁回答", preference.getContent());
        Assert.assertEquals(2L, preference.getVersion());
    }

    @Test
    public void shouldDeleteOnlyThroughOwnerScopedFilter() {
        VectorService vectorService = Mockito.mock(VectorService.class);
        Mockito.when(vectorService.deleteVector(Mockito.eq("agent_conversation_memory"),
                Mockito.any(Points.Filter.class))).thenReturn(true);
        LongTermMemoryServiceImpl service = new LongTermMemoryServiceImpl(vectorService, enabledConfig());

        Assert.assertTrue(service.delete("user-1", "memory-1"));

        Mockito.verify(vectorService).deleteVector(Mockito.eq("agent_conversation_memory"),
                Mockito.any(Points.Filter.class));
    }

    private Map<String, Object> hit(String sessionId, String text, double score, long ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sessionId", sessionId);
        m.put("text", text);
        m.put("score", (float) score);
        m.put("ts", String.valueOf(ts));
        return m;
    }

    private Map<String, Object> structuredHit(String memoryId,
                                              String memoryKey,
                                              String text,
                                              String type,
                                              long version,
                                              long createdAt,
                                              long expiresAt,
                                              double score) {
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("memoryId", memoryId);
        hit.put("memoryKey", memoryKey);
        hit.put("sessionId", "another-session");
        hit.put("text", text);
        hit.put("memoryType", type);
        hit.put("source", "test-fixture");
        hit.put("confidence", "0.8");
        hit.put("version", String.valueOf(version));
        hit.put("createdAt", String.valueOf(createdAt));
        hit.put("expiresAt", String.valueOf(expiresAt));
        hit.put("score", (float) score);
        return hit;
    }
}
