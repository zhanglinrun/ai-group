package com.linrun.agent.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Typed, verbatim evidence emitted only from a fetched source. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractedEvidenceToolOutput implements ToolStructuredOutput {

    private String sourceId;
    private String sourceUrl;
    private String title;
    private String claim;
    private String contentHash;
    private long fetchedAtEpochMillis;
    private String sourceType;
    private String retrievalTraceId;
    private String extractorVersion;
    private boolean offlineFixture;
    private boolean fetchedSource;
    private List<Excerpt> excerpts = new ArrayList<>();

    @Override
    public String getToolName() {
        return "extract_evidence";
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Excerpt {
        private String quote;
        private int startOffset;
        private int endOffset;
    }
}
