package com.linrun.agent.domain.agent.quota;

import com.linrun.agent.domain.agent.adapter.port.QuotaBillingPort;

/** Raw member-service calls. Only the durable coordinator may depend on this port. */
public interface QuotaSettlementRemotePort {

    RemoteReservationStatus reserveRemote(Long userId,
                                          long requestedMicrocredits,
                                          long minimumMicrocredits,
                                          String abilityCode,
                                          String billingRequestId);

    RemoteReservationStatus confirmRemote(String freezeId, long actualMicrocredits);

    RemoteReservationStatus releaseRemote(String freezeId);

    /** Successful HTTP + absent row is represented explicitly as NOT_FOUND, never null. */
    RemoteReservationStatus findByRequestRemote(Long userId, String billingRequestId);

    /** Successful HTTP + absent row is represented explicitly as NOT_FOUND, never null. */
    RemoteReservationStatus findByFreezeIdRemote(String freezeId);

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
