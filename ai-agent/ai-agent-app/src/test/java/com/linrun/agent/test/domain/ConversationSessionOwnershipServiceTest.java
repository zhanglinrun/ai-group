package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.domain.agent.service.session.SessionOwnershipDeniedException;
import com.linrun.agent.domain.agent.ledger.entity.DialogueSession;

/**
 * 会话归属应用服务测试。
 */
public class ConversationSessionOwnershipServiceTest {

    @Test
    public void shouldBindSessionToFirstOwner() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionOwnershipService service = new ConversationSessionOwnershipService(
                ctx.readRepository,
                ctx.writeRepository
        );

        DialogueSession session = service.ensureSessionAccessible("1001", "session-001", "帮我总结项目结构");

        Assert.assertNotNull(session);
        Assert.assertEquals("1001", session.getOwnerId());
        Assert.assertEquals("session-001", session.getSessionId());
        Assert.assertEquals("帮我总结项目结构", session.getTitle());
        Assert.assertEquals("1001", ctx.readRepository.querySessionEntity("session-001").getOwnerId());
    }

    @Test
    public void shouldAllowRepeatedAccessForSameOwner() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionOwnershipService service = new ConversationSessionOwnershipService(
                ctx.readRepository,
                ctx.writeRepository
        );
        service.ensureSessionAccessible("1001", "session-001", "第一次进入");

        DialogueSession session = service.ensureSessionAccessible("1001", "session-001", "再次进入");

        Assert.assertNotNull(session);
        Assert.assertEquals("1001", session.getOwnerId());
        Assert.assertEquals("session-001", session.getSessionId());
    }

    @Test(expected = SessionOwnershipDeniedException.class)
    public void shouldRejectCrossOwnerAccess() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionOwnershipService service = new ConversationSessionOwnershipService(
                ctx.readRepository,
                ctx.writeRepository
        );
        service.ensureSessionAccessible("1001", "session-001", "第一次进入");

        service.ensureSessionAccessible("1002", "session-001", "尝试越权访问");
    }

    @Test
    public void shouldRejectMissingSessionWithExplicitMessage() {
        ExecutionLedgerFixtureFactory.LedgerTestContext ctx = ExecutionLedgerFixtureFactory.newLedgerTestContext();
        ConversationSessionOwnershipService service = new ConversationSessionOwnershipService(
                ctx.readRepository,
                ctx.writeRepository
        );

        try {
            service.ensureExistingSessionAccessible("1001", "session-missing-001");
            Assert.fail("缺失会话应被拒绝");
        } catch (SessionOwnershipDeniedException exception) {
            Assert.assertEquals("当前会话不存在", exception.getMessage());
        }
    }
}
