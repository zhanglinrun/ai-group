package com.linrun.agent.domain.agent.runtime.tool.mcp.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMcpConfig {
    private String id;
    private String name;
    private String serverUrl;
    private String transportType;
    private boolean enabled;
    private Integer toolCount;
}
