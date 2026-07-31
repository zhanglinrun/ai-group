package com.linrun.agent.domain.agent.runtime.observability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opaque handle for one trace span. Domain code intentionally only sees the
 * stable correlation ids; the concrete tracer stays in the application layer.
 */
public final class AgentTraceScope {

    private final String traceId;
    private final String spanId;
    private final String operation;
    private final Object delegate;
    private final AtomicBoolean ended = new AtomicBoolean(false);

    public AgentTraceScope(String traceId, String spanId, String operation, Object delegate) {
        this.traceId = Objects.requireNonNullElse(traceId, "");
        this.spanId = Objects.requireNonNullElse(spanId, "");
        this.operation = Objects.requireNonNullElse(operation, "");
        this.delegate = delegate;
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String operation() {
        return operation;
    }

    /** Framework adapter use only. It must never be exposed through an API payload. */
    public Object delegate() {
        return delegate;
    }

    public boolean markEnded() {
        return ended.compareAndSet(false, true);
    }
}
