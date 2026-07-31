package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.memory.LongTermMemoryPreference;
import com.linrun.agent.domain.agent.memory.LongTermMemoryServiceImpl;
import com.linrun.agent.domain.agent.memory.MemoryTurn;
import com.linrun.agent.domain.agent.memory.UserMemoryPreferenceService;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetriever;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

public class MemoryRetentionIT {

    @Test
    public void shouldRequireOwnerOptInForWritesButAllowOwnerDeletionAfterOptOut() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        UserMemoryPreferenceService preferences = Mockito.mock(UserMemoryPreferenceService.class);
        Mockito.when(preferences.current("1001"))
                .thenReturn(LongTermMemoryPreference.disabled("1001"));
        LongTermMemoryServiceImpl service = service(repository);
        ReflectionTestUtils.setField(service, "userMemoryPreferenceService", preferences);

        service.save(new MemoryTurn("1001", "session-1", "request-1", "普通问题", "普通回答"));
        Mockito.verifyNoInteractions(repository);

        Mockito.when(repository.deleteMemory("1001", "memory-1")).thenReturn(true);
        Assert.assertTrue(service.delete("1001", "memory-1"));
        Mockito.verify(repository).deleteMemory("1001", "memory-1");
    }

    @Test
    public void shouldPersistOptedInMemoryWithPreferenceRetention() {
        PgVectorMemoryRepository repository = Mockito.mock(PgVectorMemoryRepository.class);
        UserMemoryPreferenceService preferences = Mockito.mock(UserMemoryPreferenceService.class);
        Mockito.when(preferences.current("1001")).thenReturn(
                new LongTermMemoryPreference("1001", true, 30, Instant.now()));
        LongTermMemoryServiceImpl service = service(repository);
        ReflectionTestUtils.setField(service, "userMemoryPreferenceService", preferences);

        service.save(new MemoryTurn("1001", "session-1", "request-1", "普通问题", "普通回答"));

        Mockito.verify(repository).saveMemory(Mockito.anyString(), Mockito.eq("1001"), Mockito.eq("qa_pair"),
                Mockito.anyString(), Mockito.argThat(metadata -> metadata.containsKey("expiresAt")),
                Mockito.eq("session-1"), Mockito.any());
    }

    private LongTermMemoryServiceImpl service(PgVectorMemoryRepository repository) {
        ReactorConfig config = new ReactorConfig();
        ReflectionTestUtils.setField(config, "memoryEnabled", true);
        ReflectionTestUtils.setField(config, "longTermMemoryEnabled", true);
        return new LongTermMemoryServiceImpl(provider(repository), provider(Mockito.mock(HybridRetriever.class)), config);
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
