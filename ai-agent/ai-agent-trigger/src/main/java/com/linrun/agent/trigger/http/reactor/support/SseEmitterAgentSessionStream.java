package com.linrun.agent.trigger.http.reactor.support;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.linrun.agent.domain.agent.adapter.port.AgentMessageStream;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 SSE 的会话输出适配器。
 * 触发层负责把 HTTP 协议细节封装为应用层可消费的流端口。
 */
public class SseEmitterAgentSessionStream implements AgentMessageStream {

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean detached = new AtomicBoolean(false);
    private final AtomicBoolean localTermination = new AtomicBoolean(false);

    public SseEmitterAgentSessionStream(SseEmitter emitter) {
        this.emitter = emitter;
        this.emitter.onCompletion(this::handleCompletion);
        this.emitter.onTimeout(this::handleTimeout);
        this.emitter.onError(this::handleError);
    }

    @Override
    public void send(Object payload) throws Exception {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(payload);
        } catch (Exception ex) {
            if (SseClientDisconnectDetector.isClientDisconnected(ex)) {
                markAborted();
                return;
            }
            throw ex;
        }
    }

    @Override
    public void send(String eventName, Object payload) throws Exception {
        send(eventName, null, payload);
    }

    @Override
    public void send(String eventName, String eventId, Object payload) throws Exception {
        if (closed.get()) {
            return;
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(eventName).data(payload);
            if (eventId != null && !eventId.isBlank()) {
                event.id(eventId);
            }
            emitter.send(event);
        } catch (Exception ex) {
            if (SseClientDisconnectDetector.isClientDisconnected(ex)) {
                markAborted();
                return;
            }
            throw ex;
        }
    }

    @Override
    public void complete() {
        localTermination.set(true);
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    @Override
    public void completeWithError(Throwable throwable) {
        localTermination.set(true);
        if (closed.compareAndSet(false, true)) {
            emitter.completeWithError(throwable);
        }
    }

    @Override
    public void onAbort(Runnable abortHandler) {
        // Browser transport loss is not a run cancellation. P30 makes cancel a
        // durable owner-scoped intent handled by the Run API, so this legacy
        // callback intentionally never fires for an SSE disconnect.
    }

    @Override
    public boolean isAborted() {
        return false;
    }

    /** True when this browser connection detached while the durable run continues. */
    public boolean isDetached() {
        return detached.get();
    }

    private void handleCompletion() {
        if (localTermination.get()) {
            closed.set(true);
            return;
        }
        markAborted();
    }

    private void handleTimeout() {
        if (localTermination.get()) {
            closed.set(true);
            return;
        }
        markAborted();
    }

    private void handleError(Throwable throwable) {
        if (localTermination.get()) {
            closed.set(true);
            return;
        }
        if (SseClientDisconnectDetector.isClientDisconnected(throwable)) {
            markAborted();
            return;
        }
        markAborted();
    }

    /** Detach this transport only; model/tool work continues from durable state. */
    private void markAborted() {
        detached.set(true);
        closed.set(true);
    }
}
