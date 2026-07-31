package com.linrun.agent.config.observability;

import com.linrun.agent.domain.agent.runtime.observability.AgentTraceRecorder;
import com.linrun.agent.domain.agent.runtime.observability.AgentTraceScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Bridges the domain's allowlisted trace port to Spring Boot's OpenTelemetry
 * tracing bridge. When OTLP is disabled, the domain keeps a safe no-op
 * correlation projection and normal Agent execution is unaffected.
 */
@Configuration(proxyBeanMethods = false)
public class AiAgentObservabilityConfiguration {

    @Bean
    public AgentTraceRecorder agentTraceRecorder(ObjectProvider<Tracer> tracerProvider) {
        Tracer tracer = tracerProvider.getIfAvailable();
        return tracer == null ? AgentTraceRecorder.noop() : new MicrometerAgentTraceRecorder(tracer);
    }

    private static final class MicrometerAgentTraceRecorder implements AgentTraceRecorder {

        private final Tracer tracer;

        private MicrometerAgentTraceRecorder(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public AgentTraceScope start(String operation, AgentTraceScope parent, Map<String, ?> attributes) {
            Span parentSpan = parent != null && parent.delegate() instanceof Span span ? span : null;
            Span span = parentSpan == null ? tracer.nextSpan() : tracer.nextSpan(parentSpan);
            span.name(operation).start();
            annotateSpan(span, attributes);
            return new AgentTraceScope(span.context().traceId(), span.context().spanId(), operation, span);
        }

        @Override
        public void annotate(AgentTraceScope scope, Map<String, ?> attributes) {
            if (scope != null && scope.delegate() instanceof Span span) {
                annotateSpan(span, attributes);
            }
        }

        @Override
        public void end(AgentTraceScope scope, Throwable error) {
            if (scope == null || !scope.markEnded() || !(scope.delegate() instanceof Span span)) {
                return;
            }
            if (error != null) {
                span.error(error);
            }
            span.end();
        }

        private void annotateSpan(Span span, Map<String, ?> attributes) {
            if (attributes == null || attributes.isEmpty()) {
                return;
            }
            attributes.forEach((key, value) -> {
                if (key != null && value != null) {
                    span.tag(key, String.valueOf(value));
                }
            });
        }
    }
}
