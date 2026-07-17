package com.linrun.agent.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.linrun.agent.domain.agent.adapter.port.PlatformContextPort;

import java.util.List;

/** Structured, read-only platform context returned to the Agent Loop. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformContextToolOutput implements ToolStructuredOutput {

    public static final String TOOL_NAME = "platform_context";

    private String operation;

    /** COMPLETE, DEGRADED, or FAILED. */
    private String status;

    /** True only when BFF explicitly reported a complete response. */
    private Boolean complete;

    private Boolean degraded;

    /** Distinguishes an authoritative empty result from an empty degraded fallback. */
    private Boolean authoritativeEmpty;

    @Builder.Default
    private List<PlatformContextPort.Degradation> degradationErrors = List.of();

    private Object data;

    /** Navigation information only; never an order/payment mutation command. */
    private NavigationCta cta;

    private String message;

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    public record NavigationCta(String label, String path) {
    }
}
