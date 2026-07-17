package com.linrun.agent.domain.agent.ledger;

import com.linrun.agent.domain.agent.reactor.model.dto.FileInformation;
import com.linrun.agent.domain.agent.reactor.model.req.AgentRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Builds the stable client-payload identity used by durable request-id claiming. */
public final class DialogueRunRequestFingerprint {

    private DialogueRunRequestFingerprint() {
    }

    public static String from(AgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("AgentRequest must not be null when building a request fingerprint");
        }
        StringBuilder canonical = new StringBuilder();
        append(canonical, "query", canonicalQuery(request));
        append(canonical, "executionMode", upper(request.getExecutionMode()));
        append(canonical, "outputStyle", lower(request.getOutputStyle()));
        append(canonical, "modelId", trimmed(request.getModelId()));
        append(canonical, "online", String.valueOf(!Boolean.FALSE.equals(request.getOnline())));
        append(canonical, "aiAgentId", trimmed(request.getAiAgentId()));

        List<String> fileIdentities = new ArrayList<>();
        if (request.getSessionFiles() != null) {
            for (FileInformation file : request.getSessionFiles()) {
                if (file != null) {
                    fileIdentities.add(fileIdentity(file));
                }
            }
        }
        fileIdentities.sort(String::compareTo);
        for (String fileIdentity : fileIdentities) {
            append(canonical, "file", fileIdentity);
        }
        return sha256(canonical.toString());
    }

    private static String canonicalQuery(AgentRequest request) {
        String query = request.getOriginalQuery() != null
                ? request.getOriginalQuery()
                : request.getQuery();
        return query == null ? "" : query.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String fileIdentity(FileInformation file) {
        StringBuilder identity = new StringBuilder();
        append(identity, "storage", firstNonBlank(
                file.getResourceKey(),
                file.getOriginOssUrl(),
                file.getOssUrl(),
                file.getOriginDomainUrl(),
                file.getDomainUrl(),
                file.getOriginFileUrl(),
                file.getOriginFileName(),
                file.getFileName()));
        append(identity, "fileName", trimmed(file.getFileName()));
        append(identity, "originFileName", trimmed(file.getOriginFileName()));
        append(identity, "fileSize", file.getFileSize() == null ? "" : file.getFileSize().toString());
        append(identity, "mimeType", lower(file.getMimeType()));
        append(identity, "fileType", lower(file.getFileType()));
        return identity.toString();
    }

    private static void append(StringBuilder target, String name, String value) {
        String safe = value == null ? "" : value;
        target.append(name.length()).append(':').append(name)
                .append('=').append(safe.length()).append(':').append(safe).append('\n');
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return trimmed(value).toLowerCase(Locale.ROOT);
    }

    private static String upper(String value) {
        return trimmed(value).toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
