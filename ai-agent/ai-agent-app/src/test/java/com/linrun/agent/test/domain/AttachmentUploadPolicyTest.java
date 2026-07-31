package com.linrun.agent.test.domain;

import com.linrun.agent.trigger.http.agent.AttachmentUploadPolicy;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.mock.web.MockMultipartFile;

public class AttachmentUploadPolicyTest {

    @Test
    public void shouldAcceptSupportedPdfAndRejectMagicTypeMismatch() {
        AttachmentUploadPolicy policy = new AttachmentUploadPolicy();
        policy.validate(new MockMultipartFile("file", "research.pdf", "application/pdf",
                "%PDF-1.7\nbody".getBytes()));

        try {
            policy.validate(new MockMultipartFile("file", "research.pdf", "application/pdf",
                    "MZ executable payload".getBytes()));
            Assert.fail("renamed executable must not be accepted as PDF");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("内容"));
        }
    }

    @Test
    public void shouldRejectUnsupportedOrOversizedAttachmentsBeforeRemoteUpload() {
        AttachmentUploadPolicy policy = new AttachmentUploadPolicy();
        try {
            policy.validate(new MockMultipartFile("file", "payload.exe", "application/octet-stream", new byte[] {1}));
            Assert.fail("unsupported extension must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("不支持"));
        }
        try {
            policy.validate(new MockMultipartFile("file", "large.txt", "text/plain",
                    new byte[(int) AttachmentUploadPolicy.MAX_FILE_BYTES + 1]));
            Assert.fail("oversized attachment must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("25 MiB"));
        }
    }
}
