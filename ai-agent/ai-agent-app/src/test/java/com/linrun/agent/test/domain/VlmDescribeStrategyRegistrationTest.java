package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.rag.ingest.VlmDescribeStrategy;
import com.linrun.agent.domain.agent.rag.storage.PgVectorMemoryRepository;
import com.linrun.agent.domain.agent.runtime.llm.BillableModelInvocationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class VlmDescribeStrategyRegistrationTest {

    @Test
    void registersWhenPgvectorRepositoryAndChatModelExist() {
        new ApplicationContextRunner()
                .withBean(PgVectorMemoryRepository.class, () -> mock(PgVectorMemoryRepository.class))
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withBean(BillableModelInvocationService.class, () -> mock(BillableModelInvocationService.class))
                .withPropertyValues("spring.datasource.postgres.url=jdbc:postgresql://localhost/test")
                .withUserConfiguration(VlmDescribeStrategy.class)
                .run(context -> assertEquals(1, context.getBeansOfType(VlmDescribeStrategy.class).size()));
    }
}
