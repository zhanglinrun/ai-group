package com.linrun.agent.domain.agent.runtime.tool.skill;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class UserSkillExtensionService {

    private static final long MAX_ARCHIVE_BYTES = 2 * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 50;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Set<String> ALLOWED_SUFFIXES =
            Set.of(".md", ".txt", ".json", ".yaml", ".yml", ".csv");
    private static final String DISABLED_MARKER = ".disabled";

    private final SkillMarkdownParser skillMarkdownParser;

    @Value("${autobots.autoagent.user-extensions.directory:runtime/user-extensions}")
    private String extensionsDirectory;

    public List<UserSkillDefinition> list(String ownerId) {
        Path root = skillRoot(ownerId);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isDirectory)
                    .map(this::readDefinition)
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(UserSkillDefinition::getName))
                    .toList();
        } catch (IOException e) {
            throw new SkillLoadException("读取用户 Skills 失败", e);
        }
    }

    public List<UserSkillDefinition> listEnabled(String ownerId) {
        return list(ownerId).stream().filter(UserSkillDefinition::isEnabled).toList();
    }

    public UserSkillDefinition getRequired(String ownerId, String skillName) {
        String safeName = requireSafeName(skillName);
        UserSkillDefinition definition = readDefinition(skillRoot(ownerId).resolve(safeName));
        if (definition == null) {
            throw new SkillLoadException("用户 Skill 不存在: " + safeName);
        }
        return definition;
    }

    public synchronized UserSkillDefinition install(String ownerId, InputStream archiveStream) {
        Path root = skillRoot(ownerId);
        Path temp = root.resolve(".upload-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(temp);
            extractArchive(archiveStream, temp);
            Path skillDirectory = findSkillDirectory(temp);
            SkillDefinition parsed = skillMarkdownParser.parse(skillDirectory);
            String safeName = requireSafeName(parsed.getName());
            Path target = root.resolve(safeName).normalize();
            if (Files.exists(target)) {
                throw new SkillLoadException("用户 Skill 已存在: " + safeName);
            }
            moveDirectory(skillDirectory, target);
            return readDefinition(target);
        } catch (IOException e) {
            throw new SkillLoadException("安装用户 Skill 失败", e);
        } finally {
            deleteTree(temp);
        }
    }

    public synchronized UserSkillDefinition setEnabled(String ownerId, String skillName, boolean enabled) {
        Path directory = skillRoot(ownerId).resolve(requireSafeName(skillName));
        if (!Files.isDirectory(directory)) {
            throw new SkillLoadException("用户 Skill 不存在: " + skillName);
        }
        Path marker = directory.resolve(DISABLED_MARKER);
        try {
            if (enabled) {
                Files.deleteIfExists(marker);
            } else if (!Files.exists(marker)) {
                Files.createFile(marker);
            }
            return readDefinition(directory);
        } catch (IOException e) {
            throw new SkillLoadException("更新用户 Skill 状态失败", e);
        }
    }

    public synchronized void delete(String ownerId, String skillName) {
        Path directory = skillRoot(ownerId).resolve(requireSafeName(skillName));
        if (!Files.isDirectory(directory)) {
            throw new SkillLoadException("用户 Skill 不存在: " + skillName);
        }
        deleteTree(directory);
    }

    public String buildEnabledDescription(String ownerId) {
        List<UserSkillDefinition> skills = listEnabled(ownerId);
        if (skills.isEmpty()) {
            return "当前用户没有已启用的 Skill。";
        }
        return skills.stream()
                .map(skill -> "- " + skill.getName() + ": " + skill.getDescription())
                .collect(java.util.stream.Collectors.joining("\n", "当前用户已启用 Skills：\n", ""));
    }

    private UserSkillDefinition readDefinition(Path directory) {
        if (!Files.isDirectory(directory) || !Files.isRegularFile(directory.resolve("SKILL.md"))) {
            return null;
        }
        try {
            SkillDefinition parsed = skillMarkdownParser.parse(directory);
            return UserSkillDefinition.builder()
                    .name(parsed.getName())
                    .description(parsed.getDescription())
                    .content(parsed.getContent())
                    .enabled(!Files.exists(directory.resolve(DISABLED_MARKER)))
                    .build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void extractArchive(InputStream input, Path targetRoot) throws IOException {
        long totalBytes = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new SkillLoadException("Skill 压缩包文件数量超过限制");
                }
                String entryName = entry.getName().replace('\\', '/');
                Path target = targetRoot.resolve(entryName).normalize();
                if (!target.startsWith(targetRoot) || entryName.startsWith("/")) {
                    throw new SkillLoadException("Skill 压缩包包含非法路径");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                requirePassiveFile(target);
                Files.createDirectories(target.getParent());
                long entryBytes = copyEntry(zip, target);
                if (entryBytes > MAX_ENTRY_BYTES || (totalBytes += entryBytes) > MAX_ARCHIVE_BYTES) {
                    throw new SkillLoadException("Skill 压缩包大小超过限制");
                }
            }
        }
    }

    private long copyEntry(ZipInputStream zip, Path target) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(target)) {
            int read;
            while ((read = zip.read(buffer)) >= 0) {
                copied += read;
                if (copied > MAX_ENTRY_BYTES) {
                    throw new SkillLoadException("Skill 文件大小超过限制");
                }
                output.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private Path findSkillDirectory(Path tempRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(tempRoot)) {
            List<Path> matches = paths.filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .map(Path::getParent)
                    .toList();
            if (matches.size() != 1) {
                throw new SkillLoadException("Skill 压缩包必须且只能包含一个 SKILL.md");
            }
            return matches.get(0);
        }
    }

    private void requirePassiveFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (ALLOWED_SUFFIXES.stream().noneMatch(name::endsWith)) {
            throw new SkillLoadException("用户 Skill 仅允许声明式文本资源: " + name);
        }
    }

    private String requireSafeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!SAFE_NAME.matcher(normalized).matches()) {
            throw new SkillLoadException("Skill 名称仅允许字母、数字、下划线和短横线");
        }
        return normalized;
    }

    private Path skillRoot(String ownerId) {
        if (ownerId == null || !ownerId.matches("\\d+")) {
            throw new SkillLoadException("ownerId 非法");
        }
        Path base = Path.of(extensionsDirectory).toAbsolutePath().normalize();
        Path root = base.resolve(ownerId).resolve("skills").normalize();
        if (!root.startsWith(base)) {
            throw new SkillLoadException("用户 Skill 路径非法");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new SkillLoadException("创建用户 Skill 目录失败", e);
        }
        return root;
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup for failed uploads.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup for failed uploads.
        }
    }
}
