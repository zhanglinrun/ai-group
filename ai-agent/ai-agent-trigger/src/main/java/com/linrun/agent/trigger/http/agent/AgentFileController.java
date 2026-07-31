package com.linrun.agent.trigger.http.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.linrun.agent.api.response.Response;
import com.linrun.agent.domain.agent.service.session.ConversationSessionOwnershipService;
import com.linrun.agent.domain.agent.service.session.ConversationAttachmentRegistry;
import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;
import com.linrun.agent.infrastructure.gateway.ReactorFileGateway;
import com.linrun.agent.infrastructure.gateway.dto.ConversationUploadFileDTO;
import com.linrun.agent.trigger.http.agent.vo.AgentFileUploadRespVO;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.enums.ResponseCode;

import jakarta.annotation.Resource;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * 对话附件上传 Controller。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/file")
public class AgentFileController {

    private static final String DEFAULT_TENANT = ConversationAttachmentRegistry.DEFAULT_TENANT;
    private static final long ACCESS_URL_TTL_MILLIS = 10L * 60L * 1_000L;

    @Resource
    private ReactorFileGateway reactorFileGateway;

    @Resource
    private ConversationSessionOwnershipService conversationSessionOwnershipService;

    @Resource
    private ConversationAttachmentRegistry conversationAttachmentRegistry;

    @Resource
    private AttachmentUploadPolicy attachmentUploadPolicy;

    @Resource
    private AttachmentAccessSigner attachmentAccessSigner;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response<AgentFileUploadRespVO> upload(@RequestParam("sessionId") String sessionId,
                                                  @RequestParam("file") MultipartFile file) {
        if (!StringUtils.hasText(sessionId)) {
            return Response.<AgentFileUploadRespVO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("sessionId不能为空")
                    .build();
        }
        if (file == null || file.isEmpty()) {
            return Response.<AgentFileUploadRespVO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info("上传文件不能为空")
                    .build();
        }

        try {
            attachmentUploadPolicy.validate(file);
            String ownerId = OwnerRequestContext.requireOwnerIdAsString();
            conversationSessionOwnershipService.ensureSessionAccessible(
                    ownerId,
                    sessionId,
                    null
            );
            ConversationUploadFileDTO fileDTO = reactorFileGateway.uploadConversationFile(sessionId, file);
            long expiresAt = Instant.now().plusMillis(7L * 24L * 60L * 60L * 1_000L).toEpochMilli();
            conversationAttachmentRegistry.register(DEFAULT_TENANT, ownerId, sessionId,
                    toFileInformation(fileDTO), expiresAt);
            AgentFileUploadRespVO respVO = new AgentFileUploadRespVO();
            BeanUtils.copyProperties(fileDTO, respVO);
            respVO.setExpiresAtEpochMillis(expiresAt);
            String accessUrl = accessUrl(ownerId, sessionId, fileDTO.getResourceKey());
            respVO.setAccessUrl(accessUrl);
            respVO.setUrl(accessUrl);
            respVO.setPreviewUrl(accessUrl);
            respVO.setDownloadUrl(accessUrl);
            return Response.<AgentFileUploadRespVO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(respVO)
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.<AgentFileUploadRespVO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                    .info(e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("上传对话附件失败 fileSize={} errorType={}", file == null ? 0 : file.getSize(),
                    e.getClass().getSimpleName());
            return Response.<AgentFileUploadRespVO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/sessions/{sessionId}")
    public Response<List<AgentFileUploadRespVO>> list(@PathVariable String sessionId) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        conversationSessionOwnershipService.ensureExistingSessionAccessible(ownerId, sessionId);
        List<AgentFileUploadRespVO> entries = conversationAttachmentRegistry
                .listAccessible(DEFAULT_TENANT, ownerId, sessionId).stream()
                .map(file -> toView(ownerId, sessionId, file))
                .toList();
        return Response.<List<AgentFileUploadRespVO>>builder()
                .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(entries).build();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Response<Boolean> delete(@PathVariable String sessionId, @RequestParam String resourceKey) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        conversationSessionOwnershipService.ensureExistingSessionAccessible(ownerId, sessionId);
        boolean deleted = conversationAttachmentRegistry.delete(DEFAULT_TENANT, ownerId, sessionId, resourceKey);
        return Response.<Boolean>builder()
                .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo()).data(deleted).build();
    }

    @GetMapping("/sessions/{sessionId}/access")
    public ResponseEntity<Void> access(@PathVariable String sessionId,
                                        @RequestParam String resourceKey,
                                        @RequestParam long expiresAt,
                                        @RequestParam String signature) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        conversationSessionOwnershipService.ensureExistingSessionAccessible(ownerId, sessionId);
        if (!attachmentAccessSigner.verifies(ownerId, sessionId, resourceKey, expiresAt, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        FileInformation attachment = conversationAttachmentRegistry
                .resolveAccessible(DEFAULT_TENANT, ownerId, sessionId,
                        List.of(FileInformation.builder().resourceKey(resourceKey).build()))
                .getFirst();
        String target = StringUtils.hasText(attachment.getDomainUrl())
                ? attachment.getDomainUrl() : attachment.getOssUrl();
        if (!StringUtils.hasText(target)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(target).toString()).build();
    }

    private FileInformation toFileInformation(ConversationUploadFileDTO upload) {
        return FileInformation.builder()
                .fileName(upload.getName())
                .fileDesc("用户上传附件")
                .ossUrl(upload.getDownloadUrl())
                .domainUrl(upload.getPreviewUrl())
                .fileSize(upload.getSize() == null ? null : Math.toIntExact(upload.getSize()))
                .fileType(upload.getType())
                .resourceKey(upload.getResourceKey())
                .mimeType(upload.getMimeType())
                .originFileName(upload.getOriginFileName())
                .artifactHash(upload.getArtifactHash())
                .tenantId(DEFAULT_TENANT)
                .build();
    }

    private AgentFileUploadRespVO toView(String ownerId, String sessionId, FileInformation file) {
        AgentFileUploadRespVO view = new AgentFileUploadRespVO();
        view.setName(file.getFileName());
        view.setUrl(file.getDomainUrl());
        view.setType(file.getFileType());
        view.setSize(file.getFileSize() == null ? null : file.getFileSize().longValue());
        view.setPreviewUrl(file.getDomainUrl());
        view.setDownloadUrl(file.getOssUrl());
        view.setResourceKey(file.getResourceKey());
        view.setMimeType(file.getMimeType());
        view.setOriginFileName(file.getOriginFileName());
        view.setArtifactHash(file.getArtifactHash());
        view.setExpiresAtEpochMillis(file.getExpiresAtEpochMillis());
        view.setAccessUrl(accessUrl(ownerId, sessionId, file.getResourceKey()));
        return view;
    }

    private String accessUrl(String ownerId, String sessionId, String resourceKey) {
        long expiresAt = Instant.now().plusMillis(ACCESS_URL_TTL_MILLIS).toEpochMilli();
        String signature = attachmentAccessSigner.sign(ownerId, sessionId, resourceKey, expiresAt);
        return "/api/agent/file/sessions/" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                + "/access?resourceKey=" + URLEncoder.encode(resourceKey, StandardCharsets.UTF_8)
                + "&expiresAt=" + expiresAt + "&signature=" + URLEncoder.encode(signature, StandardCharsets.UTF_8);
    }
}
