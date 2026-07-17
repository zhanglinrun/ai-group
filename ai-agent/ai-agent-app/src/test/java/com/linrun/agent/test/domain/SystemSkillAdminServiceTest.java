package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillMarkdownParser;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRegistry;
import com.linrun.agent.domain.agent.runtime.tool.skill.SkillRuntimeOptions;
import com.linrun.agent.domain.agent.runtime.tool.skill.SystemSkillAdminService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SystemSkillAdminServiceTest {

    @Test
    public void shouldInstallDisableAndDeleteSystemSkill() throws Exception {
        Path root = Files.createTempDirectory("system-skills");
        SkillRegistry registry = Mockito.mock(SkillRegistry.class);
        SystemSkillAdminService service = new SystemSkillAdminService(
                SkillRuntimeOptions.builder().directories(List.of(root.toString())).build(),
                new SkillMarkdownParser(),
                registry
        );

        service.install(archive("""
                ---
                name: admin-demo
                description: system skill
                ---
                # Demo
                """));
        Assert.assertTrue(service.getRequired("admin-demo").isEnabled());

        service.setEnabled("admin-demo", false);
        Assert.assertFalse(service.getRequired("admin-demo").isEnabled());

        service.delete("admin-demo");
        Assert.assertTrue(service.list().isEmpty());
        Mockito.verify(registry, Mockito.times(3)).refresh();
    }

    private ByteArrayInputStream archive(String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
