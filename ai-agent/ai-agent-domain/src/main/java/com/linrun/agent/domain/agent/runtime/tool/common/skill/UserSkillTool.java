package com.linrun.agent.domain.agent.runtime.tool.common.skill;

import lombok.RequiredArgsConstructor;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillDefinition;
import com.linrun.agent.domain.agent.runtime.tool.skill.UserSkillExtensionService;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class UserSkillTool implements BaseTool {

    private final UserSkillExtensionService userSkillExtensionService;
    private AgentContext agentContext;

    @Override
    public String getName() {
        return "user_skill";
    }

    @Override
    public String getDescription() {
        return "列出或加载当前用户自己上传并启用的 Skill。\n"
                + userSkillExtensionService.buildEnabledDescription(ownerId());
    }

    @Override
    public Map<String, Object> toParams() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of("type", "string", "enum", List.of("list", "load")),
                        "name", Map.of("type", "string", "description", "action=load 时填写 Skill 名称")
                ),
                "required", List.of("action")
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Object input) {
        Map<String, Object> params = input instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        String action = String.valueOf(params.getOrDefault("action", "list"));
        if ("list".equals(action)) {
            return userSkillExtensionService.buildEnabledDescription(ownerId());
        }
        UserSkillDefinition skill =
                userSkillExtensionService.getRequired(ownerId(), String.valueOf(params.getOrDefault("name", "")));
        if (!skill.isEnabled()) {
            return "用户 Skill 未启用: " + skill.getName();
        }
        return skill.getContent();
    }

    public void setAgentContext(AgentContext agentContext) {
        this.agentContext = agentContext;
    }

    private String ownerId() {
        if (agentContext == null || agentContext.getOwnerId() == null) {
            throw new IllegalStateException("user_skill 缺少 ownerId");
        }
        return String.valueOf(agentContext.getOwnerId());
    }
}
