package com.linrun.agent.domain.agent.memory;

import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Keeps the user consent record separate from the runtime-wide feature switch. */
@Slf4j
@Service
public class UserMemoryPreferenceService {

    private final PgVectorMemoryRepository memoryRepository;

    public UserMemoryPreferenceService(ObjectProvider<PgVectorMemoryRepository> memoryRepository) {
        this.memoryRepository = memoryRepository.getIfAvailable();
    }

    public LongTermMemoryPreference current(String ownerId) {
        if (memoryRepository == null) {
            return LongTermMemoryPreference.disabled(ownerId);
        }
        return memoryRepository.getMemoryPreference(ownerId);
    }

    public LongTermMemoryPreference update(LongTermMemoryPreference preference) {
        LongTermMemoryPreference normalized = preference.normalized();
        if (memoryRepository == null) {
            throw new IllegalStateException("long-term memory storage is unavailable");
        }
        memoryRepository.upsertMemoryPreference(normalized);
        memoryRepository.applyRetention(normalized.ownerId(), normalized.retentionDays());
        memoryRepository.purgeExpired(normalized.ownerId());
        return memoryRepository.getMemoryPreference(normalized.ownerId());
    }
}
