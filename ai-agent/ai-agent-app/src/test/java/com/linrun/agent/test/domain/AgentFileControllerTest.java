package com.linrun.agent.test.domain;

import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.service.session.ConversationAttachmentRegistry;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.infrastructure.gateway.ReactorFileGateway;
import com.linrun.agent.infrastructure.gateway.dto.ConversationUploadFileDTO;
import com.linrun.agent.trigger.http.agent.AgentFileController;
import com.linrun.agent.trigger.http.agent.AttachmentAccessSigner;
import com.linrun.agent.trigger.http.agent.AttachmentUploadPolicy;
import com.linrun.agent.trigger.http.agent.vo.AgentFileUploadRespVO;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;

public class AgentFileControllerTest {

    @After
    public void clearOwnerContext() {
        OwnerRequestContext.clear();
    }

    @Test
    public void shouldRejectMaliciousTypeBeforeCallingRemoteFileGateway() throws Exception {
        ReactorFileGateway gateway = Mockito.mock(ReactorFileGateway.class);
        AgentFileController controller = controller(gateway, newRegistry());
        OwnerRequestContext.bind(1001L);

        Response<AgentFileUploadRespVO> response = controller.upload("session-file-1",
                new MockMultipartFile("file", "payload.pdf", "application/pdf", "MZ not a PDF".getBytes()));

        Assert.assertNull(response.getData());
        Assert.assertNotNull(response.getInfo());
        Mockito.verifyNoInteractions(gateway);
    }

    @Test
    public void shouldRegisterHashReturnSignedAccessAndEnforceOwnerDeleteBoundary() throws Exception {
        ReactorFileGateway gateway = Mockito.mock(ReactorFileGateway.class);
        Mockito.when(gateway.uploadConversationFile(Mockito.eq("session-file-1"), Mockito.any())).thenReturn(
                ConversationUploadFileDTO.builder()
                        .name("research.pdf").originFileName("research.pdf").type("pdf").size(12L)
                        .mimeType("application/pdf").resourceKey("session-file-1:research.pdf:abc123")
                        .artifactHash("abc123").previewUrl("https://files.example/preview/research.pdf")
                        .downloadUrl("https://files.example/download/research.pdf").build());
        AgentFileController controller = controller(gateway, newRegistry());
        OwnerRequestContext.bind(1001L);

        Response<AgentFileUploadRespVO> upload = controller.upload("session-file-1",
                new MockMultipartFile("file", "research.pdf", "application/pdf", "%PDF-1.7\nbody".getBytes()));

        Assert.assertEquals("abc123", upload.getData().getArtifactHash());
        Assert.assertTrue(upload.getData().getAccessUrl().contains("signature="));
        Assert.assertNotNull(upload.getData().getExpiresAtEpochMillis());

        OwnerRequestContext.bind(2002L);
        Assert.assertTrue(controller.list("session-file-1").getData().isEmpty());
        Assert.assertFalse(controller.delete("session-file-1", "session-file-1:research.pdf:abc123").getData());

        OwnerRequestContext.bind(1001L);
        Assert.assertEquals(1, controller.list("session-file-1").getData().size());
        AttachmentAccessSigner signer = new AttachmentAccessSigner(new ReactorConfig());
        long expiry = System.currentTimeMillis() + 30_000L;
        Assert.assertEquals(HttpStatus.FOUND, controller.access("session-file-1", "session-file-1:research.pdf:abc123",
                expiry, signer.sign("1001", "session-file-1", "session-file-1:research.pdf:abc123", expiry)).getStatusCode());
        Assert.assertTrue(controller.delete("session-file-1", "session-file-1:research.pdf:abc123").getData());
    }

    private AgentFileController controller(ReactorFileGateway gateway, ConversationAttachmentRegistry registry) {
        AgentFileController controller = new AgentFileController();
        ReflectionTestUtils.setField(controller, "reactorFileGateway", gateway);
        ReflectionTestUtils.setField(controller, "conversationSessionOwnershipService",
                Mockito.mock(ConversationSessionOwnershipService.class));
        ReflectionTestUtils.setField(controller, "conversationAttachmentRegistry", registry);
        ReflectionTestUtils.setField(controller, "attachmentUploadPolicy", new AttachmentUploadPolicy());
        ReflectionTestUtils.setField(controller, "attachmentAccessSigner", new AttachmentAccessSigner(new ReactorConfig()));
        return controller;
    }

    private ConversationAttachmentRegistry newRegistry() throws Exception {
        ConversationAttachmentRegistry registry = new ConversationAttachmentRegistry();
        ReflectionTestUtils.setField(registry, "attachmentsDirectory",
                Files.createTempDirectory("agent-file-controller").toString());
        return registry;
    }
}
