package org.wwz.ai.application.agent.stream;

/**
 * 丢弃输出的会话流，供无 HTTP 客户端的定时任务等场景使用。
 */
public class HeadlessAgentSessionStream implements AgentSessionStream {

    private volatile boolean aborted;
    private volatile boolean completed;
    private volatile boolean completedWithError;

    @Override
    public void send(Object payload) {
        // discard
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public void completeWithError(Throwable throwable) {
        completedWithError = true;
    }

    @Override
    public boolean isAborted() {
        return aborted;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isCompletedWithError() {
        return completedWithError;
    }
}
