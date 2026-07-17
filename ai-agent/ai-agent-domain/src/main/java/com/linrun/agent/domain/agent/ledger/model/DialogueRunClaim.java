package com.linrun.agent.domain.agent.ledger.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable claim result for one request id.
 *
 * <p>The {@code dialogue_run.request_id} unique key is the source of truth:
 * only {@link Disposition#NEW} is allowed to enter the model/tool loop.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DialogueRunClaim {

    public enum Disposition {
        NEW,
        RUNNING,
        FINISHED,
        OWNER_MISMATCH,
        REQUEST_MISMATCH
    }

    private Disposition disposition;
    private Long runId;
    private String runUid;
    private String requestId;
    private String ownerId;
    private String sessionId;
    private Integer runStatus;
    private String finalSummaryText;
    private String errorCode;
    private String errorMsg;

    public boolean isNew() {
        return disposition == Disposition.NEW;
    }
}
