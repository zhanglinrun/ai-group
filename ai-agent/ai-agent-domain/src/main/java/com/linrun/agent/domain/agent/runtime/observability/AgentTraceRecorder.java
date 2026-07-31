package com.linrun.agent.domain.agent.runtime.observability;

import java.util.Map;
import java.util.UUID;

/**
 * Runtime tracing port. Implementations may export through OpenTelemetry, but
 * callers are deliberately unable to attach arbitrary payloads.
 */
public interface AgentTraceRecorder {

    AgentTraceScope start(String operation, AgentTraceScope parent, Map<String, ?> attributes);

    void annotate(AgentTraceScope scope, Map<String, ?> attributes);

    void end(AgentTraceScope scope, Throwable error);

    static AgentTraceRecorder noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final AgentTraceRecorder INSTANCE = new AgentTraceRecorder() {
            @Override
            public AgentTraceScope start(String operation, AgentTraceScope parent, Map<String, ?> attributes) {
                String traceId = parent == null || parent.traceId().isBlank()
                        ? UUID.randomUUID().toString().replace("-", "")
                        : parent.traceId();
                return new AgentTraceScope(traceId,
                        UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                        operation, null);
            }

            @Override
            public void annotate(AgentTraceScope scope, Map<String, ?> attributes) {
                // A correlation id is still produced for offline evidence when exporting is disabled.
            }

            @Override
            public void end(AgentTraceScope scope, Throwable error) {
                if (scope != null) {
                    scope.markEnded();
                }
            }
        };

        private NoopHolder() {
        }
    }
}
