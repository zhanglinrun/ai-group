package com.linrun.agent.domain.agent.quota;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;

/** Raw member-service calls. Only the durable coordinator may depend on this port. */
public interface QuotaSettlementRemotePort {

    RemoteReservationStatus reserveRemote(Long userId,
                                          long requestedMicrocredits,
                                          long minimumMicrocredits,
                                          String abilityCode,
                                          String billingRequestId);

    /** Remote reserve with the immutable run trace retained for recovery. */
    default RemoteReservationStatus reserveRemote(Long userId,
                                                  long requestedMicrocredits,
                                                  long minimumMicrocredits,
                                                  String abilityCode,
                                                  String billingRequestId,
                                                  String traceId) {
        return reserveRemote(userId, requestedMicrocredits, minimumMicrocredits, abilityCode, billingRequestId);
    }

    RemoteReservationStatus confirmRemote(String freezeId, long actualMicrocredits);

    /** Remote confirmation carrying the originating billing request and trace. */
    default RemoteReservationStatus confirmRemote(String freezeId,
                                                  long actualMicrocredits,
                                                  String billingRequestId,
                                                  String traceId) {
        return confirmRemote(freezeId, actualMicrocredits);
    }

    RemoteReservationStatus releaseRemote(String freezeId);

    /** Remote release carrying the originating billing request and trace. */
    default RemoteReservationStatus releaseRemote(String freezeId,
                                                  String billingRequestId,
                                                  String traceId) {
        return releaseRemote(freezeId);
    }

    /** Successful HTTP + absent row is represented explicitly as NOT_FOUND, never null. */
    RemoteReservationStatus findByRequestRemote(Long userId, String billingRequestId);

    /** Recovery lookup with the trace fixed at reserve time. */
    default RemoteReservationStatus findByRequestRemote(Long userId,
                                                        String billingRequestId,
                                                        String traceId) {
        return findByRequestRemote(userId, billingRequestId);
    }

    /** Successful HTTP + absent row is represented explicitly as NOT_FOUND, never null. */
    RemoteReservationStatus findByFreezeIdRemote(String freezeId);

    /** Recovery lookup with the request/trace pair fixed at reserve time. */
    default RemoteReservationStatus findByFreezeIdRemote(String freezeId,
                                                         String billingRequestId,
                                                         String traceId) {
        return findByFreezeIdRemote(freezeId);
    }

    record RemoteReservationStatus(String freezeId,
                                   Long userId,
                                   long reservedMicrocredits,
                                   long settledMicrocredits,
                                   Long requestedMicrocredits,
                                   Long minimumMicrocredits,
                                   String abilityCode,
                                   QuotaBillingPort.ReservationState state,
                                   String billingRequestId,
                                   String requestFingerprint,
                                   String ownerService) {
    }
}
