package com.linrun.agent.trigger.http.agent;

import com.linrun.agent.domain.agent.ledger.AgentStreamEventStore;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.IExecutionLedgerReadRepository;
import com.linrun.agent.domain.agent.ledger.entity.DialogueRun;
import com.linrun.agent.domain.agent.ledger.model.DialogueRunCancelResult;
import com.linrun.agent.domain.agent.service.session.SessionOwnershipDeniedException;
import com.linrun.agent.types.agent.owner.OwnerRequestContext;
import com.linrun.agent.types.common.JsonUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owner-scoped canonical SSE replay using the durable event sequence as cursor. */
@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
public class AgentRunEventController {

    private static final long SSE_TIMEOUT_MILLIS = 30_000L;
    private static final long LIVE_TAIL_INTERVAL_MILLIS = 250L;
    private static final ExecutorService LIVE_TAIL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final IExecutionLedgerReadRepository executionLedgerReadRepository;
    private final AgentStreamEventStore streamEventStore;
    private final AgentExecutionRecorder executionRecorder;

    /** Preferred P30 endpoint: a durable numeric run id, with owner isolation. */
    @GetMapping(value = "/{runId:\\d+}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter replayAfterRunId(@PathVariable Long runId,
                                       @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                       @RequestParam(value = "cursor", required = false) Long cursor) throws Exception {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        DialogueRun run = executionLedgerReadRepository.queryRunById(runId);
        if (run == null || !StringUtils.equals(ownerId, run.getOwnerId())) {
            throw new SessionOwnershipDeniedException("当前用户无权访问该运行记录");
        }
        return streamFromCursor(run, lastEventId, cursor);
    }

    /** Explicit request-id route kept for existing clients while runs move to numeric identifiers. */
    @GetMapping(value = {"/by-request/{requestId}/events", "/{requestId}/events"},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter replayAfterRequestId(@PathVariable String requestId,
                                           @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                           @RequestParam(value = "cursor", required = false) Long cursor) throws Exception {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        DialogueRun run = executionLedgerReadRepository.queryRunByRequestId(requestId);
        if (run == null || !StringUtils.equals(ownerId, run.getOwnerId())) {
            throw new SessionOwnershipDeniedException("当前用户无权访问该运行记录");
        }
        return streamFromCursor(run, lastEventId, cursor);
    }

    private SseEmitter streamFromCursor(DialogueRun run, String lastEventId, Long cursor) throws IOException {
        SseCursor requested = resolveCursor(lastEventId, cursor);
        String requestId = run.getRequestId();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean open = new AtomicBoolean(true);
        emitter.onCompletion(() -> open.set(false));
        emitter.onTimeout(() -> open.set(false));
        emitter.onError(error -> open.set(false));
        try {
            long earliest = streamEventStore.earliestSequence(requestId);
            if (isCursorGap(requested.sequence(), requested.supplied(), earliest)) {
                emitter.send(SseEmitter.event()
                        .name("gap")
                        .data(Map.of(
                                "type", "gap",
                                "requestedAfter", requested.sequence(),
                                "earliestRetained", earliest,
                                "reloadDurableState", true)));
                emitter.complete();
                return emitter;
            }
            // Capture a durable watermark before catch-up. Events appended just after it are
            // consumed by the tailer, so no event is skipped at the handoff boundary.
            long watermark = streamEventStore.latestSequence(requestId);
            Delivery delivery = deliverThrough(requestId, requested.sequence(), watermark, emitter);
            if (delivery.terminal()) {
                emitter.complete();
                return emitter;
            }
            tailFromWatermark(requestId, delivery.sequence(), emitter, open);
        } catch (Exception error) {
            emitter.completeWithError(error);
            if (error instanceof IOException ioException) {
                throw ioException;
            }
            throw new IllegalStateException("failed to deliver durable run events", error);
        }
        return emitter;
    }

    private void tailFromWatermark(String requestId, long deliveredSequence,
                                   SseEmitter emitter, AtomicBoolean open) {
        LIVE_TAIL_EXECUTOR.execute(() -> {
            long lastDelivered = deliveredSequence;
            try {
                while (open.get()) {
                    Thread.sleep(LIVE_TAIL_INTERVAL_MILLIS);
                    long watermark = streamEventStore.latestSequence(requestId);
                    Delivery delivery = deliverThrough(requestId, lastDelivered, watermark, emitter);
                    lastDelivered = delivery.sequence();
                    if (delivery.terminal()) {
                        open.set(false);
                        emitter.complete();
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                if (open.getAndSet(false)) {
                    emitter.completeWithError(error);
                }
            }
        });
    }

    private Delivery deliverThrough(String requestId, long afterSequence, long watermark,
                                    SseEmitter emitter) throws IOException {
        long delivered = afterSequence;
        for (AgentStreamEventStore.StoredStreamEvent event : streamEventStore
                .findByRequestIdAfter(requestId, afterSequence)) {
            if (event.sequence() > watermark) {
                break;
            }
            if (event.sequence() <= delivered) {
                continue;
            }
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.sequence()))
                    .name(event.eventType())
                    .data(JsonUtils.parseTree(event.eventJson())));
            delivered = event.sequence();
            if (isTerminal(event.eventType())) {
                return new Delivery(delivered, true);
            }
        }
        return new Delivery(delivered, false);
    }

    /** Explicit cancellation is durable and owner-scoped; it does not roll back completed external effects. */
    @PostMapping("/{runId:\\d+}/cancel")
    public ResponseEntity<CancelRunResponse> cancel(@PathVariable Long runId) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        DialogueRunCancelResult result = executionRecorder.requestRunCancellation(runId, ownerId, null);
        return cancelResponse(result);
    }

    /** Request-id cancellation keeps browser clients independent from the internal numeric ledger id. */
    @PostMapping("/by-request/{requestId}/cancel")
    public ResponseEntity<CancelRunResponse> cancelByRequest(@PathVariable String requestId) {
        String ownerId = OwnerRequestContext.requireOwnerIdAsString();
        DialogueRun run = executionLedgerReadRepository.queryRunByRequestId(requestId);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new CancelRunResponse(requestId, "NOT_FOUND", false, "run not found"));
        }
        if (!StringUtils.equals(ownerId, run.getOwnerId())) {
            throw new SessionOwnershipDeniedException("当前用户无权取消该运行记录");
        }
        return cancelResponse(executionRecorder.requestRunCancellation(run.getId(), ownerId, null));
    }

    private ResponseEntity<CancelRunResponse> cancelResponse(DialogueRunCancelResult result) {
        return switch (result.status()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(CancelRunResponse.of(result, "run not found"));
            case OWNER_MISMATCH -> throw new SessionOwnershipDeniedException("当前用户无权取消该运行记录");
            case ACCEPTED -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(CancelRunResponse.of(result,
                            "cancel intent accepted; completed external side effects are not rolled back"));
            case ALREADY_REQUESTED -> ResponseEntity.ok(CancelRunResponse.of(result,
                    "cancel intent was already recorded"));
            case TERMINAL -> ResponseEntity.ok(CancelRunResponse.of(result,
                    "run is already terminal; no cancel intent was added"));
        };
    }

    public record CancelRunResponse(String requestId, String status, boolean accepted, String message) {
        private static CancelRunResponse of(DialogueRunCancelResult result, String message) {
            return new CancelRunResponse(result.requestId(), result.status().name(), result.isAccepted(), message);
        }
    }

    static boolean isCursorGap(long afterSequence, boolean cursorSupplied, long earliestRetained) {
        return cursorSupplied && earliestRetained > 0L && afterSequence + 1L < earliestRetained;
    }

    private SseCursor resolveCursor(String lastEventId, Long cursor) {
        String value = StringUtils.defaultIfBlank(lastEventId,
                cursor == null ? null : String.valueOf(cursor));
        if (StringUtils.isBlank(value)) {
            return new SseCursor(0L, false);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0L) {
                throw new NumberFormatException("negative cursor");
            }
            return new SseCursor(parsed, true);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("SSE cursor must be a non-negative event sequence", error);
        }
    }

    private boolean isTerminal(String eventType) {
        return "complete".equals(eventType) || "error".equals(eventType);
    }

    private record Delivery(long sequence, boolean terminal) {
    }

    private record SseCursor(long sequence, boolean supplied) {
    }
}
