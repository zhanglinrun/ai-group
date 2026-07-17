package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.reactor.util.ChateiUtils;

public class ChateiUtilsRequestIdTest {

    @Test
    public void shouldKeepShortRequestIdReadable() {
        Assert.assertEquals("usersession-1:req-1", ChateiUtils.getRequestId("User", "session-1", "req-1"));
    }

    @Test
    public void shouldNotPrefixShortRequestIdWithNullUser() {
        Assert.assertEquals("session-1:req-1", ChateiUtils.getRequestId(null, "session-1", "req-1"));
    }

    @Test
    public void shouldHashLongRequestIdToStableDatabaseKey() {
        String sessionId = "session-" + "a".repeat(48);
        String requestId = "request-" + "b".repeat(48);

        String first = ChateiUtils.getRequestId(null, sessionId, requestId);
        String retry = ChateiUtils.getRequestId(null, sessionId, requestId);
        String different = ChateiUtils.getRequestId(null, sessionId, requestId + "-different");

        Assert.assertEquals(64, first.length());
        Assert.assertTrue(first.matches("[0-9a-f]{64}"));
        Assert.assertEquals(first, retry);
        Assert.assertNotEquals(first, different);
    }
}
