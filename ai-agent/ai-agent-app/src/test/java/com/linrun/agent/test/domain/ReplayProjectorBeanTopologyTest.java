package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.linrun.agent.config.reactor.ReplayProjectorAutoConfiguration;
import com.linrun.agent.config.reactor.DataAgentInitRunner;
import com.linrun.agent.config.reactor.data.Es7HighLevelClientConfig;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerReadRepository;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.ledger.ExecutionLedgerQueryService;
import com.linrun.agent.domain.agent.ledger.impl.ExecutionLedgerQueryServiceImpl;
import com.linrun.agent.domain.agent.ledger.replay.ConversationHistoryReplayService;
import com.linrun.agent.domain.agent.ledger.tooloutput.ToolOutputReader;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.infrastructure.adapter.repository.ExecutionLedgerReadRepository;
import com.linrun.agent.infrastructure.dao.reactor.IArtifactLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueRunLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IDialogueSessionLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.ILlmInvocationLedgerDao;
import com.linrun.agent.infrastructure.dao.reactor.IToolInvocationLedgerDao;
import com.linrun.agent.trigger.http.agent.AgentConversationHistoryController;

import java.lang.reflect.Field;

/**
 * 验证 Phase 1 迁出的 Reactor 装配仍能在 app 层稳定提供 Bean。
 */
public class ReplayProjectorBeanTopologyTest {

    @Test
    public void shouldWireHistoryBeansFromAppOwnedConfiguration() {
        Assert.assertTrue(ReplayProjectorAutoConfiguration.class.getPackageName().startsWith("com.linrun.agent.config"));
        Assert.assertTrue(Es7HighLevelClientConfig.class.getPackageName().startsWith("com.linrun.agent.config"));
        Assert.assertTrue(DataAgentInitRunner.class.getPackageName().startsWith("com.linrun.agent.config"));
        Assert.assertTrue(ReactorConfig.class.getPackageName().startsWith("com.linrun.agent.domain.agent.reactor.config"));

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(ReplayProjectorAutoConfiguration.class);
        context.register(ExecutionLedgerQueryServiceImpl.class);
        context.register(ExecutionLedgerReadRepository.class);
        context.register(AgentConversationHistoryController.class);
        context.register(TestDependencyConfiguration.class);

        try {
            context.refresh();

            Assert.assertNotNull(context.getBean(ExecutionLedgerQueryService.class));
            Assert.assertNotNull(context.getBean(IExecutionLedgerReadRepository.class));
            Assert.assertNotNull(context.getBean(ConversationHistoryReplayService.class));
            Assert.assertNotNull(context.getBean(AgentConversationHistoryController.class));
        } finally {
            context.close();
        }
    }

    @Test
    public void shouldLimitAppOwnedDeferredLegacyContractsToDocumentedConfigAndMetadataServices() {
        assertDeclaredFieldTypes(DataAgentInitRunner.class,
                "com.linrun.agent.domain.agent.reactor.config.data.DataAgentConfig",
                "com.linrun.agent.domain.agent.reactor.service.ChatModelInfoService",
                "com.linrun.agent.domain.agent.reactor.service.ColumnValueSyncService");
        assertDeclaredFieldTypes(Es7HighLevelClientConfig.class,
                "com.linrun.agent.domain.agent.reactor.config.data.DataAgentConfig");
    }

    private void assertDeclaredFieldTypes(Class<?> type, String... expectedTypes) {
        java.util.List<String> fieldTypes = java.util.Arrays.stream(type.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .toList();
        for (String expectedType : expectedTypes) {
            Assert.assertTrue(type.getSimpleName() + " 应显式登记延期 legacy 契约: " + expectedType,
                    fieldTypes.contains(expectedType));
        }
    }

    @Configuration
    static class TestDependencyConfiguration {

        @Bean
        public IDialogueRunLedgerDao dialogueRunLedgerDao() {
            return Mockito.mock(IDialogueRunLedgerDao.class);
        }

        @Bean
        public IDialogueSessionLedgerDao dialogueSessionLedgerDao() {
            return Mockito.mock(IDialogueSessionLedgerDao.class);
        }

        @Bean
        public ILlmInvocationLedgerDao llmInvocationLedgerDao() {
            return Mockito.mock(ILlmInvocationLedgerDao.class);
        }

        @Bean
        public IToolInvocationLedgerDao toolInvocationLedgerDao() {
            return Mockito.mock(IToolInvocationLedgerDao.class);
        }

        @Bean
        public IArtifactLedgerDao artifactLedgerDao() {
            return Mockito.mock(IArtifactLedgerDao.class);
        }

        @Bean
        public ToolOutputReader toolOutputReader() {
            return Mockito.mock(ToolOutputReader.class);
        }

        @Bean
        public ConversationSessionOwnershipService conversationSessionOwnershipService() {
            return Mockito.mock(ConversationSessionOwnershipService.class);
        }
    }
}
