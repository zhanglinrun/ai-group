package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.model.valobj.AiClientAdvisorVO;
import com.linrun.agent.domain.agent.rag.retrieval.HybridRetrievalRequest;
import com.linrun.agent.domain.agent.service.armory.node.factory.element.RagAnswerAdvisor;

import java.util.Map;

/**
 * RagAnswerAdvisor 行为测试。
 * 锁定知识库 owner 隔离和过滤表达式规则。
 */
public class RagAnswerAdvisorTest {

    @Test
    public void shouldTranslateKnowledgeFilterIntoOwnerScopedRetrieval() {
        TestableRagAnswerAdvisor advisor = new TestableRagAnswerAdvisor(AiClientAdvisorVO.RagAnswer.builder()
                .topK(6)
                .filterExpression("knowledge == 'article-prompt-words'")
                .build());

        HybridRetrievalRequest req = advisor.buildReq("什么是 JMM", Map.of());

        Assert.assertEquals("article-prompt-words", req.getOwnerId());
        Assert.assertEquals("什么是 JMM", req.getQuery());
        Assert.assertEquals(6, req.getTopK());
        Assert.assertEquals(3, req.getDocTypes().size());
    }

    @Test
    public void shouldPreferContextFilterExpressionWhenPresent() {
        TestableRagAnswerAdvisor advisor = new TestableRagAnswerAdvisor(AiClientAdvisorVO.RagAnswer.builder()
                .topK(4)
                .filterExpression("knowledge == 'default-knowledge'")
                .build());

        HybridRetrievalRequest req = advisor.buildReq("什么是 Agent", Map.of(
                "qa_filter_expression", "knowledge == 'override-knowledge'"
        ));

        Assert.assertEquals("override-knowledge", req.getOwnerId());
    }

    @Test
    public void shouldRejectUnsupportedFilterExpression() {
        TestableRagAnswerAdvisor advisor = new TestableRagAnswerAdvisor(AiClientAdvisorVO.RagAnswer.builder()
                .filterExpression("source == 'book'")
                .build());

        IllegalArgumentException error = Assert.assertThrows(
                IllegalArgumentException.class,
                () -> advisor.buildReq("什么是 RAG", Map.of())
        );

        Assert.assertTrue(error.getMessage().contains("knowledge == 'xxx'"));
    }

    @Test
    public void shouldNotRetrieveWithoutKnowledgeOwner() {
        TestableRagAnswerAdvisor advisor = new TestableRagAnswerAdvisor(AiClientAdvisorVO.RagAnswer.builder().build());
        Assert.assertNull(advisor.buildReq("什么是 RAG", Map.of()));
    }

    private static class TestableRagAnswerAdvisor extends RagAnswerAdvisor {

        private TestableRagAnswerAdvisor(AiClientAdvisorVO.RagAnswer ragAnswer) {
            super(null, ragAnswer);
        }

        private HybridRetrievalRequest buildReq(String userText, Map<String, Object> context) {
            return super.buildRetrievalRequest(userText, context);
        }
    }
}
