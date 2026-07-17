package com.linrun.agent.domain.agent.runtime.harness;

import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;

/** Parameter-aware execution permission evaluated after tool selection. */
public interface PermissionPolicy {

    PermissionDecision evaluate(String toolName,
                                Object input,
                                ToolCollection activeTools,
                                AgentContext context);

    enum Decision {
        ALLOW,
        DENY,
        ASK
    }

    record PermissionDecision(Decision decision,
                              String reason,
                              ToolPermissionMetadata metadata) {
        public PermissionDecision {
            decision = decision == null ? Decision.DENY : decision;
            metadata = metadata == null ? ToolPermissionMetadata.readOnly() : metadata;
        }

        public boolean allowed() {
            return decision == Decision.ALLOW;
        }

        public boolean requiresApproval() {
            return decision == Decision.ASK;
        }

        public static PermissionDecision allow() {
            return allow(ToolPermissionMetadata.readOnly());
        }

        public static PermissionDecision allow(ToolPermissionMetadata metadata) {
            return new PermissionDecision(Decision.ALLOW, null, metadata);
        }

        public static PermissionDecision deny(String reason) {
            return new PermissionDecision(Decision.DENY, reason, ToolPermissionMetadata.readOnly());
        }

        public static PermissionDecision deny(String reason, ToolPermissionMetadata metadata) {
            return new PermissionDecision(Decision.DENY, reason, metadata);
        }

        public static PermissionDecision ask(String reason, ToolPermissionMetadata metadata) {
            return new PermissionDecision(Decision.ASK, reason, metadata);
        }
    }
}
