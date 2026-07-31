package com.linrun.agent.domain.agent.runtime.deepresearch.report;

import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchPlan;
import org.apache.commons.lang3.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only report-writing product of a DEEP Writer node.  It contains claim
 * and provenance references, never a renderer-specific binary or a raw model
 * transcript.  Renderers receive this immutable projection and may not add
 * facts or citations.
 */
public record ReportSpec(String title,
                         String executiveSummary,
                         String methodology,
                         List<Section> sections,
                         List<Claim> claims,
                         List<Citation> citations,
                         List<Conflict> conflicts,
                         List<String> limitations,
                         String generatedAt,
                         String rendererVersion) implements ToolStructuredOutput {

    public static final String RENDERER_VERSION = "researchpilot-deterministic-v1";

    public ReportSpec {
        title = StringUtils.defaultIfBlank(title, "ResearchPilot report");
        executiveSummary = StringUtils.defaultString(executiveSummary);
        methodology = StringUtils.defaultString(methodology);
        sections = sections == null ? List.of() : List.copyOf(sections);
        claims = claims == null ? List.of() : List.copyOf(claims);
        citations = citations == null ? List.of() : List.copyOf(citations);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        generatedAt = StringUtils.defaultIfBlank(generatedAt, Instant.now().toString());
        rendererVersion = StringUtils.defaultIfBlank(rendererVersion, RENDERER_VERSION);
    }

    @Override
    public String getToolName() {
        return "write_report_spec";
    }

    public static ReportSpec fromResearch(ResearchPlan plan, List<ResearchEvidencePacket> input) {
        List<ResearchEvidencePacket> evidence = input == null ? List.of() : input.stream()
                .filter(ResearchEvidencePacket::isFinalReportEvidence).toList();
        Map<String, List<ResearchEvidencePacket>> byClaim = new LinkedHashMap<>();
        Map<String, ResearchEvidencePacket> byEvidenceId = new LinkedHashMap<>();
        for (ResearchEvidencePacket packet : evidence) {
            byClaim.computeIfAbsent(packet.claimId(), ignored -> new ArrayList<>()).add(packet);
            byEvidenceId.putIfAbsent(packet.evidenceId(), packet);
        }

        List<Claim> claims = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<ResearchEvidencePacket>> entry : byClaim.entrySet()) {
            String claimId = entry.getKey();
            List<ResearchEvidencePacket> claimEvidence = entry.getValue();
            Set<String> relations = new LinkedHashSet<>();
            boolean allStale = !claimEvidence.isEmpty();
            for (ResearchEvidencePacket packet : claimEvidence) {
                relations.add(StringUtils.upperCase(packet.relation()));
                allStale &= "STALE".equalsIgnoreCase(packet.freshness());
            }
            boolean conflicted = relations.contains("SUPPORTS") && relations.contains("CONTRADICTS");
            String uncertainty = conflicted ? "CONFLICTED" : allStale ? "OUTDATED" : "NONE";
            List<String> evidenceIds = claimEvidence.stream().map(ResearchEvidencePacket::evidenceId).distinct().toList();
            String statement = claimEvidence.stream().map(ResearchEvidencePacket::claimStatement)
                    .filter(StringUtils::isNotBlank).findFirst().orElse(claimId);
            claims.add(new Claim(claimId, statement, evidenceIds, uncertainty));
            if (conflicted) {
                conflicts.add(new Conflict(claimId, "SUPPORTS and CONTRADICTS evidence are both retained.", evidenceIds));
            }
        }

        List<Citation> citations = byEvidenceId.values().stream().map(packet -> new Citation(
                packet.evidenceId(), packet.claimId(), packet.url(), packet.contentHash(), packet.snippet(),
                packet.excerptStartOffset(), packet.excerptEndOffset(), packet.fetchedAtEpochMillis(),
                packet.offlineFixture())).toList();
        List<String> limitations = new ArrayList<>();
        limitations.add("Only fetched, hash-bound evidence from this run is rendered.");
        if (evidence.isEmpty()) {
            limitations.add("No verified evidence was available for this run.");
        }
        if (evidence.stream().anyMatch(ResearchEvidencePacket::offlineFixture)) {
            limitations.add("One or more sources are explicitly marked offline_fixture=true.");
        }
        if (claims.stream().anyMatch(claim -> !"NONE".equals(claim.uncertainty()))) {
            limitations.add("Claims with stale or conflicting evidence are explicitly labelled.");
        }
        String planTitle = plan == null ? "Research" : StringUtils.defaultIfBlank(plan.title(), "Research");
        List<String> claimIds = claims.stream().map(Claim::id).toList();
        List<Section> sections = List.of(
                new Section("findings", "Research findings", claimIds),
                new Section("sources", "Evidence and citations", claimIds));
        return new ReportSpec("ResearchPilot — " + planTitle,
                "This report contains " + citations.size() + " verified source(s) across " + claims.size() + " claim(s).",
                "Search candidates are fetched, hash-bound and extracted before entering the evidence ledger; rendering is deterministic.",
                sections, claims, citations, conflicts, limitations, Instant.now().toString(), RENDERER_VERSION);
    }

    /** Deterministic review projection; final user artifacts are rendered by Python. */
    public String reviewMarkdown() {
        Map<String, List<Citation>> citationsByClaim = new LinkedHashMap<>();
        for (Citation citation : citations) {
            citationsByClaim.computeIfAbsent(citation.claimId(), ignored -> new ArrayList<>()).add(citation);
        }
        Map<String, Integer> citationNumbers = new LinkedHashMap<>();
        for (int index = 0; index < citations.size(); index++) {
            citationNumbers.put(citations.get(index).evidenceId(), index + 1);
        }
        StringBuilder markdown = new StringBuilder("# ").append(title).append("\n\n")
                .append("## Executive summary\n\n").append(executiveSummary).append("\n\n")
                .append("## Methodology\n\n").append(methodology).append("\n\n")
                .append("## Research findings\n\n");
        for (Claim claim : claims) {
            markdown.append("- ").append(claim.statement());
            List<Integer> numbers = citationsByClaim.getOrDefault(claim.id(), List.of()).stream()
                    .map(citation -> citationNumbers.get(citation.evidenceId())).filter(number -> number != null).toList();
            for (Integer number : numbers) {
                markdown.append(" [S").append(number).append("]");
            }
            if (!"NONE".equals(claim.uncertainty())) {
                markdown.append(" (uncertainty: ").append(claim.uncertainty()).append(")");
            }
            markdown.append('\n');
        }
        if (!conflicts.isEmpty()) {
            markdown.append("\n## Conflicts\n\n");
            conflicts.forEach(conflict -> markdown.append("- ").append(conflict.claimId()).append(": ")
                    .append(conflict.description()).append('\n'));
        }
        markdown.append("\n## Limitations\n\n");
        limitations.forEach(limitation -> markdown.append("- ").append(limitation).append('\n'));
        markdown.append("\n## 证据与来源\n\n");
        for (int index = 0; index < citations.size(); index++) {
            Citation citation = citations.get(index);
            markdown.append("[S").append(index + 1).append("] ").append(citation.sourceUrl())
                    .append("\n> ").append(citation.exactQuote()).append("\n\n");
        }
        return markdown.toString().trim();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("executiveSummary", executiveSummary);
        map.put("methodology", methodology);
        map.put("sections", sections.stream().map(Section::toMap).toList());
        map.put("claims", claims.stream().map(Claim::toMap).toList());
        map.put("citations", citations.stream().map(Citation::toMap).toList());
        map.put("conflicts", conflicts.stream().map(Conflict::toMap).toList());
        map.put("limitations", limitations);
        map.put("generatedAt", generatedAt);
        map.put("rendererVersion", rendererVersion);
        return Map.copyOf(map);
    }

    public static ReportSpec from(Object value) {
        if (value instanceof ReportSpec spec) {
            return spec;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            return new ReportSpec("ResearchPilot report", "", "", List.of(), List.of(), List.of(), List.of(), List.of(),
                    Instant.now().toString(), RENDERER_VERSION);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> map.put(String.valueOf(key), item));
        return new ReportSpec(text(map.get("title")), text(map.get("executiveSummary")), text(map.get("methodology")),
                maps(map.get("sections")).stream().map(Section::from).toList(),
                maps(map.get("claims")).stream().map(Claim::from).toList(),
                maps(map.get("citations")).stream().map(Citation::from).toList(),
                maps(map.get("conflicts")).stream().map(Conflict::from).toList(), strings(map.get("limitations")),
                text(map.get("generatedAt")), text(map.get("rendererVersion")));
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> map = new LinkedHashMap<>();
                raw.forEach((key, nested) -> map.put(String.valueOf(key), nested));
                result.add(Map.copyOf(map));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(ReportSpec::text).filter(StringUtils::isNotBlank).toList();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record Section(String id, String heading, List<String> claimIds) {
        public Section {
            id = StringUtils.defaultIfBlank(id, "section");
            heading = StringUtils.defaultIfBlank(heading, id);
            claimIds = claimIds == null ? List.of() : List.copyOf(claimIds);
        }
        Map<String, Object> toMap() { return Map.of("id", id, "heading", heading, "claimIds", claimIds); }
        static Section from(Map<String, Object> map) { return new Section(text(map.get("id")), text(map.get("heading")), strings(map.get("claimIds"))); }
    }

    public record Claim(String id, String statement, List<String> evidenceIds, String uncertainty) {
        public Claim {
            id = StringUtils.defaultIfBlank(id, "claim");
            statement = StringUtils.defaultIfBlank(statement, id);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            uncertainty = StringUtils.defaultIfBlank(uncertainty, "NONE");
        }
        Map<String, Object> toMap() { return Map.of("id", id, "statement", statement, "evidenceIds", evidenceIds, "uncertainty", uncertainty); }
        static Claim from(Map<String, Object> map) { return new Claim(text(map.get("id")), text(map.get("statement")), strings(map.get("evidenceIds")), text(map.get("uncertainty"))); }
    }

    public record Citation(String evidenceId, String claimId, String sourceUrl, String contentHash, String exactQuote,
                           int startOffset, int endOffset, long fetchedAtEpochMillis, boolean offlineFixture) {
        public Citation {
            evidenceId = StringUtils.defaultString(evidenceId);
            claimId = StringUtils.defaultString(claimId);
            sourceUrl = StringUtils.defaultString(sourceUrl);
            contentHash = StringUtils.defaultString(contentHash);
            exactQuote = StringUtils.defaultString(exactQuote);
        }
        Map<String, Object> toMap() { return Map.of("evidenceId", evidenceId, "claimId", claimId, "sourceUrl", sourceUrl,
                "contentHash", contentHash, "exactQuote", exactQuote, "startOffset", startOffset, "endOffset", endOffset,
                "fetchedAtEpochMillis", fetchedAtEpochMillis, "offlineFixture", offlineFixture); }
        static Citation from(Map<String, Object> map) { return new Citation(text(map.get("evidenceId")), text(map.get("claimId")),
                text(map.get("sourceUrl")), text(map.get("contentHash")), text(map.get("exactQuote")), integer(map.get("startOffset")),
                integer(map.get("endOffset")), number(map.get("fetchedAtEpochMillis")), bool(map.get("offlineFixture"))); }
    }

    public record Conflict(String claimId, String description, List<String> evidenceIds) {
        public Conflict {
            claimId = StringUtils.defaultString(claimId);
            description = StringUtils.defaultString(description);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
        Map<String, Object> toMap() { return Map.of("claimId", claimId, "description", description, "evidenceIds", evidenceIds); }
        static Conflict from(Map<String, Object> map) { return new Conflict(text(map.get("claimId")), text(map.get("description")), strings(map.get("evidenceIds"))); }
    }

    private static int integer(Object value) { return (int) number(value); }
    private static long number(Object value) {
        if (value instanceof Number number) { return number.longValue(); }
        try { return Long.parseLong(text(value)); } catch (NumberFormatException ignored) { return 0L; }
    }
    private static boolean bool(Object value) { return value instanceof Boolean valueBool ? valueBool : Boolean.parseBoolean(text(value)); }
}
