package com.linrun.agent.domain.agent.runtime.tool.durable;

/** Reconcile summary suitable for traces and operational metrics. */
public record DurableToolReconcileResult(int published, int markedUnknown, int manualReconciliationRequired) {
}
