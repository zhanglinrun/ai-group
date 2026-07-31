package com.linrun.agent.domain.agent.runtime.deepresearch.graph;

import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchBranchResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchEvidencePacket;
import com.linrun.agent.domain.agent.runtime.deepresearch.ResearchPlan;
import com.linrun.agent.domain.agent.runtime.deepresearch.ReportQualityResult;
import com.linrun.agent.domain.agent.runtime.deepresearch.report.ReportSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializable, framework-neutral DEEP recovery projection.
 *
 * <p>The projection deliberately contains user request data, plan/subtask
 * contracts, verified evidence, review findings and bounded execution status.
 * It never copies an {@code AgentContext}, expanded prompt, hidden reasoning or
 * a raw model response. Branch markdown is not persisted; a resumed Writer
 * deterministically rebuilds report prose from the retained evidence ledger.</p>
 */
public record DeepResearchCheckpointState(Map<String, Object> values) {

    public static final String QUERY = "query";
    public static final String REQUEST_ID = "requestId";
    public static final String OWNER_ID = "ownerId";
    public static final String SESSION_ID = "sessionId";
    public static final String PLAN = "plan";
    public static final String SUBTASKS = "subtasks";
    public static final String BRANCH_RESULTS = "branchResults";
    public static final String BRANCH_EXECUTION = "branchExecution";
    public static final String EVIDENCE = "evidence";
    public static final String CLAIM_GRAPH = "claimGraph";
    public static final String MARKDOWN = "markdown";
    public static final String QUALITY = "quality";
    public static final String REVIEW_FINDINGS = "reviewFindings";
    public static final String REPAIR_TARGETS = "repairTargets";
    public static final String REPAIR_COUNT = "repairCount";
    public static final String CONTEXT_SNAPSHOT = "contextSnapshot";
    public static final String CHECKPOINT_METADATA = "checkpointMetadata";
    public static final String QUOTA_USAGE = "quotaUsage";
    public static final String RESEARCH_METRICS = "researchMetrics";
    public static final String TERMINAL_REASON = "terminalReason";
    public static final String DELIVERY_STATE = "deliveryState";
    public static final String REPORT_ARTIFACT_ID = "reportArtifactId";
    public static final String ARTIFACT_REFS = "artifactRefs";
    public static final String REPORT_SPEC = "reportSpec";
    public static final String CITATION_GATE = "citationGate";

    public DeepResearchCheckpointState {
        values = values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
    }

    /** Builds the permitted recovery subset from live SAA state. */
    public static DeepResearchCheckpointState project(Map<String, Object> state,
                                                       List<ResearchBranchResult> branchResults,
                                                       Map<Integer, String> branchExecution,
                                                       String phase,
                                                       String terminalReason) {
        Map<String, Object> source = state == null ? Map.of() : state;
        Map<String, Object> projected = new LinkedHashMap<>();
        copy(source, projected, QUERY, REQUEST_ID, OWNER_ID, SESSION_ID, PLAN, SUBTASKS, MARKDOWN,
                QUALITY, REPAIR_TARGETS, REPAIR_COUNT, CONTEXT_SNAPSHOT, QUOTA_USAGE,
                RESEARCH_METRICS, DELIVERY_STATE, REPORT_ARTIFACT_ID, ARTIFACT_REFS, REPORT_SPEC, CITATION_GATE);

        ResearchPlan plan = ResearchPlan.from(source.get(PLAN), text(source.get(QUERY)));
        projected.put(PLAN, plan.toMap());
        projected.put(SUBTASKS, plan.subtasks().stream().map(task -> task.toMap()).toList());

        List<Map<String, Object>> safeBranches = new ArrayList<>();
        for (ResearchBranchResult branch : branchResults == null ? List.<ResearchBranchResult>of() : branchResults) {
            Map<String, Object> branchMap = new LinkedHashMap<>(branch.toMap());
            // Branch prose is a raw model output surface. Persist evidence/contracts only.
            branchMap.put("markdown", "");
            safeBranches.add(branchMap);
        }
        projected.put(BRANCH_RESULTS, List.copyOf(safeBranches));

        Map<String, Object> statuses = new LinkedHashMap<>();
        if (branchExecution != null) {
            branchExecution.forEach((index, status) -> statuses.put(String.valueOf(index), status));
        }
        projected.put(BRANCH_EXECUTION, Map.copyOf(statuses));

        List<ResearchEvidencePacket> evidence = packets(source.get(EVIDENCE), branchResults);
        projected.put(EVIDENCE, evidence.stream().map(ResearchEvidencePacket::toMap).toList());
        projected.put(CLAIM_GRAPH, evidence.stream().map(packet -> Map.<String, Object>of(
                "claimId", packet.claimId(), "sourceUrl", packet.url(), "sourceTitle", packet.title())).toList());

        ReportQualityResult quality = ReportQualityResult.from(source.get(QUALITY));
        projected.put(QUALITY, quality.toMap());
        projected.put(REVIEW_FINDINGS, List.copyOf(quality.issues()));
        ReportSpec reportSpec = ReportSpec.from(source.get(REPORT_SPEC));
        if (!reportSpec.claims().isEmpty() || !reportSpec.citations().isEmpty()) {
            projected.put(REPORT_SPEC, reportSpec.toMap());
        }
        projected.put(CHECKPOINT_METADATA, Map.of(
                "phase", phase == null ? "UNKNOWN" : phase,
                "recoveryPolicy", "SAFE_NODES_ONLY",
                "version", 1));
        projected.put(TERMINAL_REASON, terminalReason == null ? "" : terminalReason);
        return new DeepResearchCheckpointState(projected);
    }

    private static List<ResearchEvidencePacket> packets(Object stored,
                                                          List<ResearchBranchResult> branches) {
        List<ResearchEvidencePacket> packets = new ArrayList<>();
        if (stored instanceof List<?> values) {
            values.stream().map(ResearchEvidencePacket::from)
                    .filter(ResearchEvidencePacket::isFinalReportEvidence).forEach(packets::add);
        }
        if (packets.isEmpty() && branches != null) {
            branches.stream().flatMap(branch -> branch.evidence().stream())
                    .filter(ResearchEvidencePacket::isFinalReportEvidence).forEach(packets::add);
        }
        Map<String, ResearchEvidencePacket> deduped = new LinkedHashMap<>();
        packets.forEach(packet -> deduped.putIfAbsent(packet.contentHash() + "|" + packet.url(), packet));
        return List.copyOf(deduped.values());
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                target.put(key, source.get(key));
            }
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
