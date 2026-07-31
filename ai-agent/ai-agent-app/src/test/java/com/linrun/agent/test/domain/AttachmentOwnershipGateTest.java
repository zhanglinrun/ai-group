package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;
import com.linrun.agent.domain.agent.service.session.ConversationAttachmentRegistry;
import com.linrun.agent.domain.agent.service.session.SessionOwnershipDeniedException;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ensures Agent requests use server-registered attachment metadata only. */
public class AttachmentOwnershipGateTest {

    @Test
    public void shouldResolveOnlyTheCanonicalAttachmentForItsOwnerAndSession() throws Exception {
        ConversationAttachmentRegistry registry = newRegistry();
        registry.register("1001", "session-1", FileInformation.builder()
                .fileName("source.pdf")
                .resourceKey("session-1:source.pdf:sha256")
                .ossUrl("https://files.example/source.pdf")
                .domainUrl("https://files.example/source.pdf")
                .fileSize(123)
                .build());

        FileInformation spoofedRequest = FileInformation.builder()
                .resourceKey("session-1:source.pdf:sha256")
                .ossUrl("http://127.0.0.1/private.pdf")
                .domainUrl("http://127.0.0.1/private.pdf")
                .build();

        List<FileInformation> resolved = registry.resolveAccessible("1001", "session-1", List.of(spoofedRequest));

        Assert.assertEquals(1, resolved.size());
        Assert.assertEquals("https://files.example/source.pdf", resolved.getFirst().getOssUrl());
        Assert.assertEquals("source.pdf", resolved.getFirst().getFileName());
    }

    @Test(expected = SessionOwnershipDeniedException.class)
    public void shouldRejectCrossOwnerAttachmentReference() throws Exception {
        ConversationAttachmentRegistry registry = newRegistry();
        registry.register("1001", "session-1", FileInformation.builder()
                .fileName("source.pdf")
                .resourceKey("session-1:source.pdf:sha256")
                .build());

        registry.resolveAccessible("2002", "session-1", List.of(FileInformation.builder()
                .resourceKey("session-1:source.pdf:sha256")
                .build()));
    }

    @Test(expected = SessionOwnershipDeniedException.class)
    public void shouldRejectUnknownResourceKeyForTheCurrentSession() throws Exception {
        ConversationAttachmentRegistry registry = newRegistry();
        registry.register("1001", "session-1", FileInformation.builder()
                .fileName("source.pdf")
                .resourceKey("session-1:source.pdf:sha256")
                .build());

        registry.resolveAccessible("1001", "session-1", List.of(FileInformation.builder()
                .resourceKey("session-1:other.pdf:sha256")
                .build()));
    }

    @Test
    public void shouldExcludeExpiredAttachmentsAndAllowOwnerScopedDeletion() throws Exception {
        ConversationAttachmentRegistry registry = newRegistry();
        registry.register("tenant-a", "1001", "session-1", FileInformation.builder()
                        .fileName("active.md").resourceKey("session-1:active:sha256").build(),
                System.currentTimeMillis() + 60_000L);
        registry.register("tenant-a", "1001", "session-1", FileInformation.builder()
                        .fileName("expired.md").resourceKey("session-1:expired:sha256").build(),
                System.currentTimeMillis() + 1_000L);
        // The registry correctly rejects a timestamp that is already expired.
        // Keep enough scheduling headroom for a loaded CI host before testing
        // expiration, instead of constructing an invalid +1ms registration.
        Thread.sleep(1_100L);

        Assert.assertEquals(1, registry.listAccessible("tenant-a", "1001", "session-1").size());
        Assert.assertFalse(registry.delete("tenant-a", "2002", "session-1", "session-1:active:sha256"));
        Assert.assertTrue(registry.delete("tenant-a", "1001", "session-1", "session-1:active:sha256"));
        Assert.assertTrue(registry.listAccessible("tenant-a", "1001", "session-1").isEmpty());
    }

    private ConversationAttachmentRegistry newRegistry() throws Exception {
        Path root = Files.createTempDirectory("conversation-attachment-gate");
        ConversationAttachmentRegistry registry = new ConversationAttachmentRegistry();
        ReflectionTestUtils.setField(registry, "attachmentsDirectory", root.toString());
        return registry;
    }
}
