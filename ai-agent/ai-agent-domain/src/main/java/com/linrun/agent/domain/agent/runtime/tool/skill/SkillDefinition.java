package com.linrun.agent.domain.agent.runtime.tool.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Skill 定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillDefinition {

    private String name;

    private String description;

    private Path basePath;

    private String content;

    @Builder.Default
    private String version = "v1";

    @Builder.Default
    private String definitionHash = "";

    @Builder.Default
    private List<String> triggers = new ArrayList<>();

    @Builder.Default
    private Map<String, Object> frontMatter = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, SkillScriptDefinition> scripts = new LinkedHashMap<>();

    /**
     * 构建脚本摘要，便于直接拼接到 skill_tool 返回结果里。
     */
    public List<String> buildScriptSummaries() {
        if (scripts == null || scripts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> summaries = new ArrayList<>();
        for (SkillScriptDefinition scriptDefinition : scripts.values()) {
            summaries.add(scriptDefinition.toSummaryLine());
        }
        return summaries;
    }
}
