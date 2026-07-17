package com.linrun.agent.domain.agent.ledger.tooloutput;

import com.linrun.agent.domain.agent.ledger.model.tooloutput.ToolOutputPersistCommand;

/**
 * rich tool 输出写入契约。
 */
public interface ToolOutputWriter {

    void write(ToolOutputPersistCommand command);

    void writeOrThrow(ToolOutputPersistCommand command);
}
