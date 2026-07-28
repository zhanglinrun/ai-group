package com.linrun.agent.domain.agent.ledger.model;

import java.util.concurrent.TimeoutException;

/**
 * 执行账本常量。
 */
public final class ExecutionLedgerConstants {

    private ExecutionLedgerConstants() {
    }

    public static final int STATUS_RUNNING = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_TIMEOUT = 3;
    public static final int STATUS_STOPPED = 4;

    public static final String CALL_KIND_ASK = "ask";
    public static final String CALL_KIND_ASK_TOOL = "askTool";
    public static final String CALL_KIND_INTERNAL_DIGITAL_EMPLOYEE = "internalDigitalEmployee";

    public static final String AGENT_NAME_AGENT_LOOP = "agent_loop";
    public static final String AGENT_NAME_DEEP_RESEARCH_GRAPH = "deep_research_graph";
    public static final String ENTRY_AGENT_LOOP_PREFIX = "agent_loop:";
    public static final String ENTRY_AGENT_LOOP_STANDARD = "agent_loop:standard";
    public static final String ENTRY_AGENT_LOOP_AUTO = "agent_loop:auto";
    public static final String ENTRY_AGENT_LOOP_DEEP = "agent_loop:deep";

    public static final String ARTIFACT_ROLE_INPUT = "input";
    public static final String ARTIFACT_ROLE_OUTPUT = "output";

    public static final String VISIBILITY_VISIBLE = "visible";
    public static final String VISIBILITY_INTERNAL = "internal";

    public static final String SOURCE_TYPE_USER_UPLOAD = "user_upload";
    public static final String SOURCE_TYPE_TOOL_OUTPUT = "tool_output";

    public static final String REQUEST_SOURCE_AGENT = "agent";
    public static final String REQUEST_SOURCE_WORKSPACE = "workspace";

    public static final String TOOL_PROVIDER_LOCAL = "local";
    public static final String TOOL_PROVIDER_MCP = "mcp";

    /**
     * 根据异常推导失败状态。
     */
    public static int resolveFailureStatus(Throwable throwable) {
        return throwable instanceof TimeoutException ? STATUS_TIMEOUT : STATUS_FAILED;
    }
}
