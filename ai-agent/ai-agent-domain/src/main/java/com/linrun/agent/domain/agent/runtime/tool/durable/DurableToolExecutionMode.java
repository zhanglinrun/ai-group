package com.linrun.agent.domain.agent.runtime.tool.durable;

/** Whether a tool call executed remotely or reused a completed canonical operation. */
public enum DurableToolExecutionMode {
    EXECUTED,
    REUSED
}
