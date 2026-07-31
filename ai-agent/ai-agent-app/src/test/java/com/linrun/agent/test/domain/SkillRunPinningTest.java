package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.tool.common.skill.SkillTool;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillDefinition;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillLoadException;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRegistry;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillScriptDefinition;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** P60: descriptor-first Skill bodies are pinned and bounded per run. */
public class SkillRunPinningTest {

    @Test
    public void shouldPinOneOnDemandSkillAndFailClosedForAdditionalOrChangedDefinitions() {
        MutableRegistry registry = new MutableRegistry();
        registry.put(definition("alpha", "sha256:alpha-v1"));
        registry.put(definition("beta", "sha256:beta-v1"));
        SkillTool tool = new SkillTool(registry);
        tool.setAgentContext(AgentContext.builder()
                .requestId("skill-run")
                .agentRunState(AgentRunState.builder().build())
                .build());

        String alpha = String.valueOf(tool.execute(Map.of("skill_name", "alpha")));
        String beta = String.valueOf(tool.execute(Map.of("skill_name", "beta")));
        registry.put(definition("alpha", "sha256:alpha-v2"));
        String changed = String.valueOf(tool.execute(Map.of("skill_name", "alpha")));

        Assert.assertTrue(alpha.contains("技能名称：alpha"));
        Assert.assertTrue(beta.contains("only one additional skill body"));
        Assert.assertTrue(changed.contains("definition changed during this run"));
    }

    private SkillDefinition definition(String name, String hash) {
        return SkillDefinition.builder()
                .name(name)
                .description("descriptor for " + name)
                .version("v1")
                .definitionHash(hash)
                .triggers(List.of(name))
                .basePath(Path.of("C:/skills/" + name))
                .content("# " + name)
                .build();
    }

    private static final class MutableRegistry implements SkillRegistry {
        private final Map<String, SkillDefinition> definitions = new java.util.LinkedHashMap<>();
        void put(SkillDefinition definition) { definitions.put(definition.getName(), definition); }
        @Override public void refresh() { }
        @Override public boolean isEnabled() { return true; }
        @Override public Collection<SkillDefinition> listSkills() { return definitions.values(); }
        @Override public Optional<SkillDefinition> findSkill(String name) { return Optional.ofNullable(definitions.get(name)); }
        @Override public SkillDefinition getRequiredSkill(String name) {
            return findSkill(name).orElseThrow(() -> new SkillLoadException("missing " + name));
        }
        @Override public SkillScriptDefinition getRequiredScript(String skillName, String scriptName) {
            throw new SkillLoadException("missing script");
        }
        @Override public Path assertPathAllowed(Path candidatePath) { return candidatePath; }
        @Override public String buildSkillDescription() { return "descriptors only"; }
    }
}
