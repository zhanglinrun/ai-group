package com.linrun.agent.domain.agent.service.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Server-side fact store for uploaded conversation attachments. */
@Service
public class ConversationAttachmentRegistry {

    public static final String DEFAULT_TENANT = "default";
    private static final long DEFAULT_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<FileInformation>> FILE_LIST = new TypeReference<>() {
    };

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Value("${autobots.autoagent.conversation-attachments.directory:runtime/conversation-attachments}")
    private String attachmentsDirectory;

    /** Client supplied attachment URLs are never admitted to an Agent request. */
    public void register(String ownerId, String sessionId, FileInformation upload) {
        register(DEFAULT_TENANT, ownerId, sessionId, upload, null);
    }

    /** Registers one immutable attachment fact with explicit tenant/owner/session scope. */
    public void register(String tenantId, String ownerId, String sessionId, FileInformation upload,
                         Long expiresAtEpochMillis) {
        requireScope(tenantId, ownerId, sessionId);
        if (upload == null || StringUtils.isAnyBlank(upload.getResourceKey(), upload.getFileName())) {
            throw new IllegalArgumentException("上传附件缺少稳定标识");
        }
        if (!upload.getResourceKey().startsWith(sessionId + ":")) {
            throw new IllegalArgumentException("附件 resourceKey 与会话不匹配");
        }
        FileInformation canonical = copy(upload);
        canonical.setTenantId(normalizeTenant(tenantId));
        canonical.setExpiresAtEpochMillis(resolveExpiry(expiresAtEpochMillis, canonical.getExpiresAtEpochMillis()));
        String key = normalizeTenant(tenantId) + ":" + ownerId + ":" + sessionId;
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            Map<String, FileInformation> byResourceKey = new LinkedHashMap<>();
            for (FileInformation existing : read(tenantId, ownerId, sessionId)) {
                if (existing != null && StringUtils.isNotBlank(existing.getResourceKey())) {
                    byResourceKey.put(existing.getResourceKey(), existing);
                }
            }
            byResourceKey.put(canonical.getResourceKey(), canonical);
            write(tenantId, ownerId, sessionId, new ArrayList<>(byResourceKey.values()));
        }
    }

    public List<FileInformation> resolveAccessible(String ownerId,
                                                    String sessionId,
                                                    List<FileInformation> requestedFiles) {
        return resolveAccessible(DEFAULT_TENANT, ownerId, sessionId, requestedFiles);
    }

    public List<FileInformation> resolveAccessible(String tenantId,
                                                    String ownerId,
                                                    String sessionId,
                                                    List<FileInformation> requestedFiles) {
        requireScope(tenantId, ownerId, sessionId);
        if (requestedFiles == null || requestedFiles.isEmpty()) {
            return List.of();
        }
        Map<String, FileInformation> stored = new LinkedHashMap<>();
        for (FileInformation existing : active(read(tenantId, ownerId, sessionId), tenantId)) {
            if (existing != null && StringUtils.isNotBlank(existing.getResourceKey())) {
                stored.put(existing.getResourceKey(), existing);
            }
        }
        List<FileInformation> result = new ArrayList<>(requestedFiles.size());
        for (FileInformation requested : requestedFiles) {
            String resourceKey = requested == null ? null : StringUtils.trimToNull(requested.getResourceKey());
            FileInformation canonical = stored.get(resourceKey);
            if (canonical == null) {
                throw new SessionOwnershipDeniedException("当前用户无权访问该附件");
            }
            result.add(copy(canonical));
        }
        return List.copyOf(result);
    }

    public List<FileInformation> listAccessible(String ownerId, String sessionId) {
        return listAccessible(DEFAULT_TENANT, ownerId, sessionId);
    }

    public List<FileInformation> listAccessible(String tenantId, String ownerId, String sessionId) {
        requireScope(tenantId, ownerId, sessionId);
        return active(read(tenantId, ownerId, sessionId), tenantId).stream().map(this::copy).toList();
    }

    /** Removes the attachment from all future Runs. The remote blob is never fetched or exposed again. */
    public boolean delete(String tenantId, String ownerId, String sessionId, String resourceKey) {
        requireScope(tenantId, ownerId, sessionId);
        if (StringUtils.isBlank(resourceKey)) {
            return false;
        }
        String key = normalizeTenant(tenantId) + ":" + ownerId + ":" + sessionId;
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            List<FileInformation> existing = read(tenantId, ownerId, sessionId);
            List<FileInformation> retained = existing.stream()
                    .filter(file -> !StringUtils.equals(resourceKey, file == null ? null : file.getResourceKey()))
                    .toList();
            if (retained.size() == existing.size()) {
                return false;
            }
            write(tenantId, ownerId, sessionId, retained);
            return true;
        }
    }

    private void requireScope(String tenantId, String ownerId, String sessionId) {
        if (StringUtils.isBlank(tenantId) || tenantId.contains("/") || tenantId.contains("\\")) {
            throw new IllegalArgumentException("tenantId 非法");
        }
        if (StringUtils.isBlank(ownerId) || !ownerId.matches("\\d+")) {
            throw new IllegalArgumentException("ownerId 非法");
        }
        if (StringUtils.isBlank(sessionId) || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("sessionId 非法");
        }
    }

    private List<FileInformation> read(String tenantId, String ownerId, String sessionId) {
        Path path = attachmentPath(tenantId, ownerId, sessionId);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(path.toFile(), FILE_LIST);
        } catch (IOException error) {
            throw new IllegalStateException("读取会话附件登记失败", error);
        }
    }

    private void write(String tenantId, String ownerId, String sessionId, List<FileInformation> files) {
        Path target = attachmentPath(tenantId, ownerId, sessionId);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), files);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("保存会话附件登记失败", error);
        }
    }

    private Path attachmentPath(String tenantId, String ownerId, String sessionId) {
        Path base = Path.of(attachmentsDirectory).toAbsolutePath().normalize();
        Path tenantBase = DEFAULT_TENANT.equals(normalizeTenant(tenantId)) ? base : base.resolve(normalizeTenant(tenantId));
        Path path = tenantBase.resolve(ownerId).resolve(sessionId + ".json").normalize();
        if (!path.startsWith(base)) {
            throw new IllegalArgumentException("附件登记路径非法");
        }
        return path;
    }

    private FileInformation copy(FileInformation source) {
        return FileInformation.builder()
                .fileName(source.getFileName())
                .fileDesc(source.getFileDesc())
                .ossUrl(source.getOssUrl())
                .domainUrl(source.getDomainUrl())
                .fileSize(source.getFileSize())
                .fileType(source.getFileType())
                .resourceKey(source.getResourceKey())
                .mimeType(source.getMimeType())
                .originFileName(source.getOriginFileName())
                .originFileUrl(source.getOriginFileUrl())
                .originOssUrl(source.getOriginOssUrl())
                .originDomainUrl(source.getOriginDomainUrl())
                .artifactHash(source.getArtifactHash())
                .tenantId(source.getTenantId())
                .expiresAtEpochMillis(source.getExpiresAtEpochMillis())
                .build();
    }

    private List<FileInformation> active(List<FileInformation> files, String tenantId) {
        long now = System.currentTimeMillis();
        String normalizedTenant = normalizeTenant(tenantId);
        return files.stream()
                .filter(file -> file != null)
                .filter(file -> StringUtils.equals(normalizedTenant,
                        StringUtils.defaultIfBlank(file.getTenantId(), DEFAULT_TENANT)))
                .filter(file -> file.getExpiresAtEpochMillis() == null || file.getExpiresAtEpochMillis() > now)
                .collect(Collectors.toList());
    }

    private String normalizeTenant(String tenantId) {
        return StringUtils.defaultIfBlank(tenantId, DEFAULT_TENANT).trim();
    }

    private Long resolveExpiry(Long requestedExpiry, Long existingExpiry) {
        long now = System.currentTimeMillis();
        Long candidate = requestedExpiry == null ? existingExpiry : requestedExpiry;
        if (candidate == null) {
            return now + DEFAULT_TTL_MILLIS;
        }
        if (candidate <= now) {
            throw new IllegalArgumentException("附件过期时间必须在未来");
        }
        return candidate;
    }
}
