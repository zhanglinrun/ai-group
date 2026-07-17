package com.linrun.agent.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;
import com.linrun.agent.config.AiAgentSkillAutoConfiguration;
import com.linrun.agent.config.AiAgentSkillProperties;
import com.linrun.agent.config.SkillDirectoryResolver;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRuntimeOptions;

import java.nio.file.Path;
import java.util.List;

/**
 * Skill 自动装配测试。
 */
public class AiAgentSkillAutoConfigurationTest {

    @Test
    public void shouldResolveDirectoriesBeforeBuildingRuntimeOptions() {
        AiAgentSkillProperties properties = new AiAgentSkillProperties();
        properties.setEnabled(true);
        properties.setDirectories(List.of("D:/invalid/project/runtime/skills"));

        SkillDirectoryResolver resolver = new SkillDirectoryResolver(Path.of("D:/repo/ai-agent/ai-agent-app"));
        AiAgentSkillAutoConfiguration autoConfiguration = new AiAgentSkillAutoConfiguration(resolver);

        SkillRuntimeOptions options = autoConfiguration.skillRuntimeOptions(properties);

        Assert.assertTrue(options.isEnabled());
        Assert.assertTrue(options.getDirectories().isEmpty());
    }

    @Test
    public void shouldBindCanonicalSkillPrefix() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("autobots.autoagent.skill.enabled", "true")
                .withProperty("autobots.autoagent.skill.directories[0]", "runtime/skills")
                .withProperty("autobots.autoagent.skill.max-read-chars", "4096")
                .withProperty("skills.directory", "must-not-bind");

        AiAgentSkillProperties properties = Binder.get(environment)
                .bind("autobots.autoagent.skill", AiAgentSkillProperties.class)
                .orElseThrow(() -> new IllegalStateException("canonical skill properties did not bind"));

        Assert.assertTrue(properties.isEnabled());
        Assert.assertEquals(List.of("runtime/skills"), properties.getDirectories());
        Assert.assertEquals(4096, properties.getMaxReadChars());
    }
}
