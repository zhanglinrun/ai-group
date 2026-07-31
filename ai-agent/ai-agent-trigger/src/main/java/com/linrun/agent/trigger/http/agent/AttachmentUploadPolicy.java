package com.linrun.agent.trigger.http.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/** Admission policy for user attachments before bytes leave the API boundary. */
@Component
public class AttachmentUploadPolicy {

    public static final long MAX_FILE_BYTES = 25L * 1024L * 1024L;
    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("yaml", "application/x-yaml"),
            Map.entry("yml", "application/x-yaml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif")
    );

    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("附件超过 25 MiB 限制");
        }
        String fileName = StringUtils.trimWhitespace(file.getOriginalFilename());
        if (!StringUtils.hasText(fileName) || fileName.contains("/") || fileName.contains("\\")
                || fileName.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("附件文件名非法");
        }
        String extension = extension(fileName);
        String expected = CONTENT_TYPES.get(extension);
        if (expected == null) {
            throw new IllegalArgumentException("不支持的附件类型: ." + extension);
        }
        String declared = normalizeContentType(file.getContentType(), expected);
        if (!isCompatible(extension, declared)) {
            throw new IllegalArgumentException("附件 content type 与扩展名不匹配");
        }
        verifyMagicBytes(file, extension);
    }

    private String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index <= 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeContentType(String provided, String fallback) {
        return StringUtils.hasText(provided) ? provided.trim().toLowerCase(Locale.ROOT) : fallback;
    }

    private boolean isCompatible(String extension, String contentType) {
        if ("md".equals(extension) || "markdown".equals(extension) || "txt".equals(extension)) {
            return "text/plain".equals(contentType) || "text/markdown".equals(contentType);
        }
        if ("yaml".equals(extension) || "yml".equals(extension)) {
            return "application/x-yaml".equals(contentType) || "text/yaml".equals(contentType)
                    || "text/plain".equals(contentType);
        }
        return CONTENT_TYPES.get(extension).equals(contentType);
    }

    private void verifyMagicBytes(MultipartFile file, String extension) {
        if (!requiresMagicCheck(extension)) {
            return;
        }
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(16);
            if (!matchesMagic(extension, header)) {
                throw new IllegalArgumentException("附件内容与声明类型不匹配");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取附件内容", exception);
        }
    }

    private boolean requiresMagicCheck(String extension) {
        return "pdf".equals(extension) || "png".equals(extension) || "jpg".equals(extension)
                || "jpeg".equals(extension) || "webp".equals(extension) || "gif".equals(extension);
    }

    private boolean matchesMagic(String extension, byte[] value) {
        return switch (extension) {
            case "pdf" -> startsWith(value, 0x25, 0x50, 0x44, 0x46);
            case "png" -> startsWith(value, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "jpg", "jpeg" -> startsWith(value, 0xff, 0xd8, 0xff);
            case "gif" -> startsWith(value, 0x47, 0x49, 0x46, 0x38);
            case "webp" -> startsWith(value, 0x52, 0x49, 0x46, 0x46)
                    && value.length >= 12 && value[8] == 'W' && value[9] == 'E'
                    && value[10] == 'B' && value[11] == 'P';
            default -> false;
        };
    }

    private boolean startsWith(byte[] value, int... expected) {
        if (value.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((value[i] & 0xff) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
