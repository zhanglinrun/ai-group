package com.linrun.agent.domain.agent.runtime.context;

import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * P70 L1 deterministic compaction and role projection. It uses token counts,
 * never character counts, and gives current request, protected Todo and
 * evidence references priority over redundant history.
 */
@Service
public class ContextProjectionService {

    private final TokenCounter tokenCounter;

    public ContextProjectionService() {
        this(new TokenCounter());
    }

    ContextProjectionService(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter == null ? new TokenCounter() : tokenCounter;
    }

    public ContextProjection project(ContextProjectionRequest request) {
        List<Candidate> candidates = candidates(request);
        String opening = "<context_projection role=\"" + request.role().name().toLowerCase()
                + "\" snapshot_revision=\"" + request.snapshot().revision() + "\" snapshot_hash=\""
                + request.snapshot().snapshotHash() + "\">\n";
        String closing = "</context_projection>";
        int fixedTokens = tokenCounter.countText(opening + closing);
        if (fixedTokens >= request.tokenBudget()) {
            throw new ContextBudgetExceededException("context projection budget cannot hold its protocol envelope");
        }

        StringBuilder body = new StringBuilder();
        List<String> retained = new ArrayList<>();
        boolean compacted = false;
        for (Candidate candidate : candidates) {
            if (candidate.content().isBlank()) {
                continue;
            }
            String line = "[" + candidate.label() + "]\n" + candidate.content().strip() + "\n";
            int available = request.tokenBudget() - fixedTokens - tokenCounter.countText(body.toString());
            int lineTokens = tokenCounter.countText(line);
            if (lineTokens <= available) {
                body.append(line);
                retained.add(candidate.label());
                continue;
            }
            if (!candidate.protectedValue() || available < 12) {
                compacted = true;
                continue;
            }
            String header = "[" + candidate.label() + "]\n";
            int contentBudget = available - tokenCounter.countText(header) - 1;
            if (contentBudget <= 0) {
                compacted = true;
                continue;
            }
            String truncated = tokenCounter.truncateTextToTokens(candidate.content(), contentBudget).strip();
            if (!truncated.isBlank()) {
                body.append(header).append(truncated).append("\n");
                retained.add(candidate.label());
            }
            compacted = true;
        }
        String rendered = opening + body + closing;
        int tokens = tokenCounter.countText(rendered);
        if (tokens > request.tokenBudget()) {
            throw new ContextBudgetExceededException("context projection exceeded token budget after deterministic compaction");
        }
        return new ContextProjection(request.role(), rendered, tokens, compacted,
                request.snapshot().revision(), request.snapshot().snapshotHash(), retained);
    }

    private List<Candidate> candidates(ContextProjectionRequest request) {
        ContextSnapshot snapshot = request.snapshot();
        List<Candidate> values = new ArrayList<>();
        values.add(protectedCandidate("CURRENT_USER_REQUEST", request.currentRequest()));
        values.add(new Candidate("RESEARCH_GOAL", snapshot.researchGoal(), true));
        values.add(protectedCandidate("UNFINISHED_TODO", bullets(snapshot.protectedTodos())));
        switch (request.role()) {
            case PLANNER -> {
                values.add(new Candidate("CONSTRAINTS", bullets(request.constraints()), false));
                values.add(new Candidate("CONFIRMED_FACTS", bullets(snapshot.confirmedFacts()), false));
                values.add(new Candidate("NEXT_STEPS", bullets(snapshot.nextSteps()), false));
            }
            case RESEARCHER -> {
                values.add(new Candidate("SUBTASK", request.subtask(), true));
                values.add(protectedCandidate("KEY_EVIDENCE", bullets(snapshot.keyEvidenceIds())));
                values.add(new Candidate("SEARCH_HISTORY", bullets(request.searchHistory()), false));
                values.add(new Candidate("OPEN_CONFLICTS", bullets(snapshot.conflicts()), false));
            }
            case WRITER -> {
                values.add(protectedCandidate("CLAIM_EVIDENCE", bullets(request.claimEvidence())));
                values.add(protectedCandidate("KEY_EVIDENCE", bullets(snapshot.keyEvidenceIds())));
                values.add(new Candidate("REPORT_OUTLINE", bullets(request.reportOutline()), false));
                values.add(new Candidate("OPEN_CONFLICTS", bullets(snapshot.conflicts()), false));
            }
            case REVIEWER -> {
                values.add(protectedCandidate("REPORT_SPEC", request.reportSpec()));
                values.add(protectedCandidate("CLAIM_EVIDENCE", bullets(request.claimEvidence())));
                values.add(new Candidate("OPEN_CONFLICTS", bullets(snapshot.conflicts()), false));
                values.add(new Candidate("UNCONFIRMED_ASSUMPTIONS", bullets(snapshot.unconfirmedAssumptions()), false));
            }
            case TOOL -> {
                values.add(new Candidate("MINIMAL_IDENTITY", "tenant=" + snapshot.key().tenantId()
                        + ", owner=" + snapshot.key().ownerId() + ", run=" + snapshot.key().runId(), true));
                values.add(new Candidate("SUBTASK", request.subtask(), true));
                values.add(new Candidate("TOOL_PARAMETERS", bullets(request.toolParameters()), true));
            }
            case STANDARD -> {
                values.add(new Candidate("CONFIRMED_FACTS", bullets(snapshot.confirmedFacts()), false));
                values.add(protectedCandidate("KEY_EVIDENCE", bullets(snapshot.keyEvidenceIds())));
                values.add(new Candidate("OPEN_CONFLICTS", bullets(snapshot.conflicts()), false));
                values.add(new Candidate("NEXT_STEPS", bullets(snapshot.nextSteps()), false));
            }
        }
        return values;
    }

    private Candidate protectedCandidate(String label, String content) {
        return new Candidate(label, content, true);
    }

    private String bullets(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value.trim());
            }
        }
        return unique.stream().map(value -> "- " + value).reduce("", (left, right) -> left + right + "\n").trim();
    }

    private record Candidate(String label, String content, boolean protectedValue) {
        private Candidate {
            content = content == null ? "" : content.trim();
        }
    }
}
