package com.linrun.agent.domain.agent.quota;

import java.util.Objects;

/** Pure transition decisions used by the coordinator before any remote side effect. */
public final class QuotaSettlementDecision {

    private QuotaSettlementDecision() {
    }

    public static ReserveAction decideReserve(QuotaSettlementCommand command, String fingerprint) {
        if (command == null) {
            return ReserveAction.INVOKE_REMOTE;
        }
        if (!Objects.equals(command.getRequestFingerprint(), fingerprint)) {
            return ReserveAction.CONFLICT;
        }
        return switch (command.getState()) {
            case RESERVE_PENDING -> ReserveAction.INVOKE_REMOTE;
            case RESERVED -> ReserveAction.RETURN_RESERVED;
            case PROVIDER_STARTED, APPLY_PENDING, CONFIRMED, RELEASED,
                    RESERVE_FAILED, MANUAL_REVIEW, CONFLICT -> ReserveAction.CONFLICT;
        };
    }

    public static ApplyAction decideApply(QuotaSettlementCommand command,
                                          QuotaSettlementIntent intent,
                                          long actualMicrocredits) {
        if (command == null || intent == null || intent == QuotaSettlementIntent.NONE) {
            return ApplyAction.CONFLICT;
        }
        return switch (command.getState()) {
            case RESERVED -> intent == QuotaSettlementIntent.RELEASE
                    ? ApplyAction.PERSIST_INTENT : ApplyAction.CONFLICT;
            case PROVIDER_STARTED -> ApplyAction.PERSIST_INTENT;
            case APPLY_PENDING -> sameIntent(command, intent, actualMicrocredits)
                    ? ApplyAction.INVOKE_REMOTE : ApplyAction.CONFLICT;
            case CONFIRMED -> intent == QuotaSettlementIntent.CONFIRM
                    && Objects.equals(command.getSettledMicrocredits(), actualMicrocredits)
                    ? ApplyAction.RETURN_TERMINAL : ApplyAction.CONFLICT;
            case RELEASED -> intent == QuotaSettlementIntent.RELEASE
                    ? ApplyAction.RETURN_TERMINAL : ApplyAction.CONFLICT;
            case RESERVE_PENDING, RESERVE_FAILED, MANUAL_REVIEW, CONFLICT -> ApplyAction.CONFLICT;
        };
    }

    private static boolean sameIntent(QuotaSettlementCommand command,
                                      QuotaSettlementIntent intent,
                                      long actualMicrocredits) {
        return command.getIntendedAction() == intent
                && (intent == QuotaSettlementIntent.RELEASE
                || Objects.equals(command.getIntendedMicrocredits(), actualMicrocredits));
    }

    public enum ReserveAction {
        INVOKE_REMOTE,
        RETURN_RESERVED,
        CONFLICT
    }

    public enum ApplyAction {
        PERSIST_INTENT,
        INVOKE_REMOTE,
        RETURN_TERMINAL,
        CONFLICT
    }
}
