package com.linrun.agent.domain.agent.runtime.tool.mcp.runtime;

/**
 * MCP 配置来源。来源与 transport 共同决定离线请求是否可以暴露工具。
 */
public enum McpToolOrigin {

    /** 由管理员维护、随应用配置加载的受信 MCP。 */
    CONFIGURED,

    /** 由终端用户在运行时添加的远程 MCP。 */
    USER_EXTENSION,

    /** 缺少来源元数据时保持 fail-closed。 */
    UNKNOWN
}
