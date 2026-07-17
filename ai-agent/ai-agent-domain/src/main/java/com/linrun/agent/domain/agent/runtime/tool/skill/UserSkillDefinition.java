package com.linrun.agent.domain.agent.runtime.tool.skill;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserSkillDefinition {
    String name;
    String description;
    String content;
    boolean enabled;
}
