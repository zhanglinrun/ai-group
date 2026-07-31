package com.linrun.agent.domain.agent.runtime.deepresearch;

import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DeepResearchState extends AgentState {

    static final String QUERY = "query";
    static final String OWNER_ID = "ownerId";
    static final String REQUEST_ID = "requestId";
    static final String SESSION_ID = "sessionId";
    static final String CHECKPOINT_THREAD_ID = "checkpointThreadId";
    static final String PLAN = "plan";
    static final String BRANCH_RESULTS = "branchResults";
    static final String COMPLETED_SECTIONS = "completedSections";
    static final String EVIDENCE = "evidence";
    static final String MARKDOWN = "markdown";
    static final String QUALITY = "quality";
    static final String PLAN_REVISION = "planRevision";
    static final String REPAIR_COUNT = "repairCount";
    static final String SUMMARY = "summary";
    static final String REPORT_ARTIFACT_ID = "reportArtifactId";
    static final String ARTIFACT_REFS = "artifactRefs";

    public DeepResearchState(Map<String, Object> initData) {
        super(initData);
    }

    public String text(String key) {
        Object value = data().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    public int integer(String key) {
        Object value = data().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public ResearchPlan plan() {
        return ResearchPlan.from(data().get(PLAN), text(QUERY));
    }

    public List<ResearchBranchResult> branchResults() {
        return list(data().get(BRANCH_RESULTS)).stream()
                .map(ResearchBranchResult::from)
                .toList();
    }

    public List<String> completedSections() {
        return list(data().get(COMPLETED_SECTIONS)).stream()
                .map(String::valueOf)
                .toList();
    }

    public List<ResearchEvidencePacket> evidence() {
        return list(data().get(EVIDENCE)).stream()
                .map(ResearchEvidencePacket::from)
                .toList();
    }

    public ReportQualityResult quality() {
        return ReportQualityResult.from(data().get(QUALITY));
    }

    public ResearchPlanRevision planRevision() {
        return ResearchPlanRevision.from(data().get(PLAN_REVISION));
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (value instanceof List<?> values) {
            return new ArrayList<>((List<Object>) values);
        }
        return List.of();
    }
}
