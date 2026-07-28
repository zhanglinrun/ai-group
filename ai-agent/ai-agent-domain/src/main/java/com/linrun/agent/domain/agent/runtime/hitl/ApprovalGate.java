package com.linrun.agent.domain.agent.runtime.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linrun.agent.types.common.JsonUtils;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Online-only, fail-closed approval gate for tool execution. */
@Slf4j
@Service
public class ApprovalGate {

    private static final Set<String> SECRET_KEYS = Set.of(
            "authorization", "password", "passwd", "secret", "token", "apikey", "credential");

    private final ToolApprovalRepository repository;
    private final Duration timeout;
    private final ConcurrentMap<Long, CompletableFuture<DecisionCommand>> waiters = new ConcurrentHashMap<>();
    private final Set<String> approvedAllCache = ConcurrentHashMap.newKeySet();

    public ApprovalGate(ToolApprovalRepository repository,
                        @Value("${agent.hitl.timeout-ms:300000}") long timeoutMs) {
        this.repository = repository;
        this.timeout = Duration.ofMillis(Math.max(1L, timeoutMs));
    }

    public ApprovalResult awaitApproval(ApprovalRequest request,
                                        Consumer<ToolApproval> pausedCallback,
                                        BooleanSupplier aborted) {
        if (request == null || !request.isApprovalRequired()) {
            return ApprovalResult.approved(null, ApprovalDecision.APPROVED, "approval not required");
        }
        String cacheKey = cacheKey(request.getRunId(), request.getToolName());
        if (approvedAllCache.contains(cacheKey)) {
            return ApprovalResult.approved(null, ApprovalDecision.APPROVED_ALL, "approved_all cached");
        }

        ToolApproval approval;
        try {
            Instant now = Instant.now();
            approval = repository.create(ToolApproval.builder()
                    .ownerId(request.getOwnerId())
                    .runId(request.getRunId())
                    .toolCallId(request.getToolCallId())
                    .toolName(request.getToolName())
                    .argumentsPreview(redact(request.getArgumentsJson()))
                    .estimatedMicrocredits(request.getEstimatedMicrocredits())
                    .status(ApprovalDecision.PENDING)
                    .expiresAt(now.plus(timeout))
                    .createdAt(now)
                    .build());
        } catch (RuntimeException error) {
            log.error("approval persist failed runId={} tool={} errorType={}",
                    request.getRunId(), request.getToolName(), error.getClass().getSimpleName());
            return ApprovalResult.rejected(null, ApprovalDecision.REJECTED, "approval persistence failed");
        }

        CompletableFuture<DecisionCommand> waiter = new CompletableFuture<>();
        waiters.put(approval.getId(), waiter);
        try {
            if (pausedCallback != null) {
                pausedCallback.accept(approval);
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (aborted != null && aborted.getAsBoolean()) {
                    markTimeout(approval.getId());
                    return ApprovalResult.rejected(
                            approval.getId(), ApprovalDecision.TIMEOUT, "downstream disconnected");
                }
                long remainingMillis = Math.max(1L,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                try {
                    DecisionCommand command = waiter.get(Math.min(1000L, remainingMillis), TimeUnit.MILLISECONDS);
                    return resolve(approval, command);
                } catch (TimeoutException ignored) {
                    // Wake periodically so an SSE disconnect fails closed without waiting for the full timeout.
                }
            }
            markTimeout(approval.getId());
            return ApprovalResult.rejected(approval.getId(), ApprovalDecision.TIMEOUT, "approval timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            markTimeout(approval.getId());
            return ApprovalResult.rejected(approval.getId(), ApprovalDecision.TIMEOUT, "approval interrupted");
        } catch (Exception error) {
            markTimeout(approval.getId());
            log.error("approval wait failed id={} errorType={}", approval.getId(), error.getClass().getSimpleName());
            return ApprovalResult.rejected(approval.getId(), ApprovalDecision.REJECTED, "approval wait failed");
        } finally {
            waiters.remove(approval.getId(), waiter);
        }
    }

    public List<ToolApproval> findPending(String ownerId, String runId) {
        return repository.findPending(ownerId, runId).stream()
                .filter(approval -> waiters.containsKey(approval.getId()))
                .toList();
    }

    public boolean decide(long approvalId,
                          String ownerId,
                          ApprovalDecision decision,
                          String decisionPayload) {
        if (decision == null || decision == ApprovalDecision.PENDING || decision == ApprovalDecision.TIMEOUT) {
            return false;
        }
        if (decision == ApprovalDecision.MODIFIED && StringUtils.isBlank(decisionPayload)) {
            return false;
        }
        CompletableFuture<DecisionCommand> waiter = waiters.get(approvalId);
        if (waiter == null) {
            return false;
        }
        boolean updated;
        try {
            String persistedPayload = decision == ApprovalDecision.MODIFIED
                    ? redact(decisionPayload)
                    : StringUtils.isBlank(decisionPayload) ? null : JsonUtils.toJson(decisionPayload);
            updated = repository.decide(approvalId, ownerId, decision, persistedPayload);
        } catch (RuntimeException error) {
            log.error("approval decision persist failed id={} errorType={}",
                    approvalId, error.getClass().getSimpleName());
            return false;
        }
        return updated && waiter.complete(new DecisionCommand(decision, decisionPayload));
    }

    public void clearRunCache(String runId) {
        approvedAllCache.removeIf(key -> key.startsWith(runId + "|"));
    }

    private ApprovalResult resolve(ToolApproval approval, DecisionCommand command) {
        return switch (command.decision()) {
            case APPROVED -> ApprovalResult.approved(
                    approval.getId(), command.decision(), "user approved");
            case APPROVED_ALL -> {
                approvedAllCache.add(cacheKey(approval.getRunId(), approval.getToolName()));
                yield ApprovalResult.approved(
                        approval.getId(), command.decision(), "user approved all");
            }
            case MODIFIED -> ApprovalResult.modified(approval.getId(), command.payload());
            case SKIPPED -> ApprovalResult.skipped(approval.getId(), "user skipped");
            case REJECTED, TIMEOUT -> ApprovalResult.rejected(
                    approval.getId(), command.decision(), "user rejected: " + command.decision());
            case PENDING -> ApprovalResult.rejected(
                    approval.getId(), ApprovalDecision.REJECTED, "invalid pending decision");
        };
    }

    private void markTimeout(long approvalId) {
        try {
            repository.timeout(approvalId);
        } catch (RuntimeException error) {
            log.error("approval timeout persist failed id={} errorType={}",
                    approvalId, error.getClass().getSimpleName());
        }
    }

    private String redact(String json) {
        if (StringUtils.isBlank(json)) {
            return "{}";
        }
        try {
            JsonNode root = JsonUtils.mapper().readTree(json);
            redactNode(root);
            return JsonUtils.toJson(root);
        } catch (Exception ignored) {
            return "{\"redacted\":true}";
        }
    }

    private void redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                String key = entry.getKey().replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if (SECRET_KEYS.stream().anyMatch(key::contains)) {
                    object.put(entry.getKey(), "***");
                } else {
                    redactNode(entry.getValue());
                }
            });
        } else if (node != null && node.isArray()) {
            node.forEach(this::redactNode);
        }
    }

    private String cacheKey(String runId, String toolName) {
        return runId + "|" + toolName;
    }

    private record DecisionCommand(ApprovalDecision decision, String payload) {
    }

    @lombok.Value
    @Builder
    public static class ApprovalRequest {
        String runId;
        String ownerId;
        String toolCallId;
        String toolName;
        String argumentsJson;
        long estimatedMicrocredits;
        boolean approvalRequired;
    }

    @lombok.Value
    @Builder
    public static class ApprovalResult {
        boolean approved;
        boolean modified;
        boolean skipped;
        boolean rejected;
        Long approvalId;
        ApprovalDecision decision;
        String modifiedArguments;
        String reason;

        static ApprovalResult approved(Long id, ApprovalDecision decision, String reason) {
            return ApprovalResult.builder().approved(true).approvalId(id).decision(decision).reason(reason).build();
        }

        static ApprovalResult modified(Long id, String arguments) {
            return ApprovalResult.builder().modified(true).approvalId(id)
                    .decision(ApprovalDecision.MODIFIED).modifiedArguments(arguments).build();
        }

        static ApprovalResult skipped(Long id, String reason) {
            return ApprovalResult.builder().skipped(true).approvalId(id)
                    .decision(ApprovalDecision.SKIPPED).reason(reason).build();
        }

        static ApprovalResult rejected(Long id, ApprovalDecision decision, String reason) {
            return ApprovalResult.builder().rejected(true).approvalId(id).decision(decision).reason(reason).build();
        }
    }
}
