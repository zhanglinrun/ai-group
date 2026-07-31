package com.linrun.agent.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Reproducible page-fetch result. Content stays run-local so the deterministic
 * extractor can create verbatim citations; reports use only the hash and
 * excerpts, never this raw field.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FetchedPageToolOutput implements ToolStructuredOutput {

    private String sourceId;
    private String requestedUrl;
    private String finalUrl;
    private String title;
    private String content;
    private String contentHash;
    private String contentTrust;
    private List<String> riskSignals;
    private long fetchedAtEpochMillis;
    private String artifactId;
    private boolean offlineFixture;

    @Override
    public String getToolName() {
        return "fetch_page";
    }
}
