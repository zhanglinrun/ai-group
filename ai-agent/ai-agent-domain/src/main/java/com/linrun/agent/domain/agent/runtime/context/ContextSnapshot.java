package com.linrun.agent.domain.agent.runtime.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Recoverable L2 context summary. It deliberately stores structured facts and
 * references rather than raw prompts, tool payloads, or hidden reasoning.
 */
public record ContextSnapshot(ContextSnapshotKey key,
                              long revision,
                              String researchGoal,
                              List<String> confirmedFacts,
                              List<String> unconfirmedAssumptions,
                              List<String> keyEvidenceIds,
                              List<String> conflicts,
                              List<String> nextSteps,
                              List<String> protectedTodos,
                              String summaryModel,
                              String summaryVersion,
                              String sourceHash,
                              boolean summaryDegraded,
                              long createdAtEpochMillis,
                              long updatedAtEpochMillis) {

    public ContextSnapshot {
        if (key == null) {
            throw new IllegalArgumentException("snapshot key is required");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("snapshot revision must not be negative");
        }
        researchGoal = safeText(researchGoal);
        confirmedFacts = normalize(confirmedFacts);
        unconfirmedAssumptions = normalize(unconfirmedAssumptions);
        keyEvidenceIds = normalize(keyEvidenceIds);
        conflicts = normalize(conflicts);
        nextSteps = normalize(nextSteps);
        protectedTodos = normalize(protectedTodos);
        summaryModel = safeText(summaryModel);
        summaryVersion = safeText(summaryVersion);
        sourceHash = safeText(sourceHash);
        long now = Instant.now().toEpochMilli();
        createdAtEpochMillis = createdAtEpochMillis <= 0 ? now : createdAtEpochMillis;
        updatedAtEpochMillis = updatedAtEpochMillis <= 0 ? createdAtEpochMillis : updatedAtEpochMillis;
    }

    public static ContextSnapshot draft(ContextSnapshotKey key, String researchGoal) {
        return new ContextSnapshot(key, 0, researchGoal, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), "deterministic", "p70", "", false, 0, 0);
    }

    public ContextSnapshot nextRevision(long nextRevision) {
        return new ContextSnapshot(key, nextRevision, researchGoal, confirmedFacts, unconfirmedAssumptions,
                keyEvidenceIds, conflicts, nextSteps, protectedTodos, summaryModel, summaryVersion, sourceHash,
                summaryDegraded, createdAtEpochMillis, Instant.now().toEpochMilli());
    }

    public String snapshotHash() {
        return "sha256:" + sha256(String.join("|", List.of(
                key.tenantId(), key.ownerId(), key.sessionId(), String.valueOf(key.runId()),
                String.valueOf(revision), researchGoal,
                String.join("\u001f", confirmedFacts), String.join("\u001f", unconfirmedAssumptions),
                String.join("\u001f", keyEvidenceIds), String.join("\u001f", conflicts),
                String.join("\u001f", nextSteps), String.join("\u001f", protectedTodos),
                summaryModel, summaryVersion, sourceHash, String.valueOf(summaryDegraded))));
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String normalized = safeText(value);
            if (!normalized.isEmpty() && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
