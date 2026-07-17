package com.linrun.agent.test.spring.ai;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import com.linrun.agent.domain.agent.runtime.tool.skill.DefaultSkillRegistry;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillDefinition;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillLoadException;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillMarkdownParser;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillPathGuard;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillScriptDiscoverer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Skill 注册中心相关测试。
 */
public class SkillRegistryTest {

    @Test
    public void shouldLoadValidSkillFromFixtureDirectory() throws Exception {
        DefaultSkillRegistry skillRegistry = createRegistry(true, fixtureSkillsRoot().toString());

        skillRegistry.refresh();

        Assert.assertEquals(1, skillRegistry.listSkills().size());
        SkillDefinition skillDefinition = skillRegistry.getRequiredSkill("sql-analysis");
        Assert.assertEquals("sql-analysis", skillDefinition.getName());
        Assert.assertTrue(skillDefinition.getBasePath().isAbsolute());
        Assert.assertTrue(skillDefinition.getScripts().containsKey("summarize"));
        Assert.assertEquals("python", skillDefinition.getScripts().get("summarize").getRuntime());
    }

    @Test
    public void shouldSkipSkillWithoutFrontMatter() throws Exception {
        Path rootDirectory = Files.createTempDirectory("skill-registry-missing-frontmatter");
        Path brokenSkillDirectory = rootDirectory.resolve("broken-skill");
        Files.createDirectories(brokenSkillDirectory);
        Files.writeString(brokenSkillDirectory.resolve("SKILL.md"), "# Broken Skill", StandardCharsets.UTF_8);

        DefaultSkillRegistry skillRegistry = createRegistry(true, rootDirectory.toString());
        skillRegistry.refresh();

        Assert.assertTrue(skillRegistry.listSkills().isEmpty());
    }

    @Test
    public void shouldHandleEmptyDirectoryGracefully() throws Exception {
        Path rootDirectory = Files.createTempDirectory("skill-registry-empty");
        DefaultSkillRegistry skillRegistry = createRegistry(true, rootDirectory.toString());

        skillRegistry.refresh();

        Assert.assertTrue(skillRegistry.listSkills().isEmpty());
        Assert.assertTrue(skillRegistry.isEnabled());
    }

    @Test(expected = SkillLoadException.class)
    public void shouldFailWhenSkillNamesDuplicate() throws Exception {
        Path rootDirectory = Files.createTempDirectory("skill-registry-duplicate");
        createSkillDirectory(rootDirectory.resolve("skill-a"), "duplicate-skill", "说明 A");
        createSkillDirectory(rootDirectory.resolve("skill-b"), "duplicate-skill", "说明 B");

        DefaultSkillRegistry skillRegistry = createRegistry(true, rootDirectory.toString());
        skillRegistry.refresh();
    }

    @Test
    public void shouldLoadNineRepositorySkillsWithProgressiveDescriptions() {
        Path repositorySkills = findRepositorySkills();
        DefaultSkillRegistry skillRegistry = createRegistry(true, repositorySkills.toString());

        skillRegistry.refresh();

        Assert.assertEquals(9, skillRegistry.listSkills().size());
        String lightweightDescription = skillRegistry.buildSkillDescription();
        Assert.assertTrue(lightweightDescription.contains("github-deep-research"));
        Assert.assertFalse(lightweightDescription.contains("## Workflow"));
        Assert.assertTrue(skillRegistry.getRequiredSkill("github-deep-research").getContent().length() > 100);
    }

    private DefaultSkillRegistry createRegistry(boolean enabled, String... directories) {
        SkillPathGuard skillPathGuard = new SkillPathGuard();
        return new DefaultSkillRegistry(
                SkillRuntimeOptions.builder()
                        .enabled(enabled)
                        .directories(List.of(directories))
                        .build(),
                new SkillMarkdownParser(),
                new SkillScriptDiscoverer(skillPathGuard),
                skillPathGuard
        );
    }

    private Path fixtureSkillsRoot() throws Exception {
        return new ClassPathResource("skills").getFile().toPath();
    }

    private Path findRepositorySkills() {
        Path current = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve("runtime").resolve("skills");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            Path nested = current.resolve("ai-agent").resolve("runtime").resolve("skills");
            if (Files.isDirectory(nested)) {
                return nested;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository runtime/skills directory not found");
    }

    private void createSkillDirectory(Path skillDirectory, String skillName, String description) throws Exception {
        Files.createDirectories(skillDirectory);
        String markdown = """
                ---
                name: %s
                description: %s
                ---

                # Demo Skill
                """.formatted(skillName, description);
        Files.writeString(skillDirectory.resolve("SKILL.md"), markdown, StandardCharsets.UTF_8);
    }
}
