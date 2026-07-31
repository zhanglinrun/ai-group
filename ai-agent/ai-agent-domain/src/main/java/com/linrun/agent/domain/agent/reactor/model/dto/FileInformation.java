package com.linrun.agent.domain.agent.reactor.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInformation {
    private String fileName;
    private String fileDesc;
    private String ossUrl;
    private String domainUrl;
    private Integer fileSize;
    private String fileType;
    private String resourceKey;
    private String mimeType;
    private String originFileName;
    private String originFileUrl;
    private String originOssUrl;
    private String originDomainUrl;
    /** SHA-256 of the original upload; used as an immutable artifact identity. */
    private String artifactHash;
    /** Tenant identity stays explicit even while the current platform uses the default tenant. */
    private String tenantId;
    /** Server-side registration expiry. Expired attachments are never admitted to a new Run. */
    private Long expiresAtEpochMillis;
}
