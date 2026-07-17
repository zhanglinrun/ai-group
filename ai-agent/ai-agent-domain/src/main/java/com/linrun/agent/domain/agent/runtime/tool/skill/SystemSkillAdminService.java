package com.linrun.agent.domain.agent.runtime.tool.skill;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class SystemSkillAdminService {

    private static final long MAX_ARCHIVE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 10L * 1024 * 1024;
    private static final int MAX_ENTRIES = 500;
    private static final String DISABLED_MARKER = ".disabled";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private final SkillRuntimeOptions options;
    private final SkillMarkdownParser parser;
    private final SkillRegistry registry;

    public List<UserSkillDefinition> list() {
        return roots().stream()
                .flatMap(this::skillDirectories)
                .map(this::read)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(UserSkillDefinition::getName))
                .toList();
    }

    public UserSkillDefinition getRequired(String name) {
        Path directory = findDirectory(name);
        UserSkillDefinition definition = read(directory);
        if (definition == null) {
            throw new SkillLoadException("系统 Skill 不存在: " + name);
        }
        return definition;
    }

    public synchronized UserSkillDefinition install(InputStream input) {
        Path root = writableRoot();
        Path temp = root.resolve(".upload-" + UUID.randomUUID()).normalize();
        try {
            Files.createDirectories(temp);
            extract(input, temp);
            Path source = singleSkillDirectory(temp);
            SkillDefinition parsed = parser.parse(source);
            String name = safeName(parsed.getName());
            if (list().stream().anyMatch(skill -> name.equals(skill.getName()))) {
                throw new SkillLoadException("系统 Skill 已存在: " + name);
            }
            Path target = root.resolve(name).normalize();
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
            registry.refresh();
            return getRequired(name);
        } catch (IOException e) {
            throw new SkillLoadException("安装系统 Skill 失败", e);
        } finally {
            deleteTree(temp);
        }
    }

    public synchronized UserSkillDefinition setEnabled(String name, boolean enabled) {
        Path directory = findDirectory(name);
        try {
            Path marker = directory.resolve(DISABLED_MARKER);
            if (enabled) {
                Files.deleteIfExists(marker);
            } else if (!Files.exists(marker)) {
                Files.createFile(marker);
            }
            registry.refresh();
            return getRequired(name);
        } catch (IOException e) {
            throw new SkillLoadException("更新系统 Skill 状态失败", e);
        }
    }

    public synchronized void delete(String name) {
        deleteTree(findDirectory(name));
        registry.refresh();
    }

    private UserSkillDefinition read(Path directory) {
        try {
            SkillDefinition definition = parser.parse(directory);
            return UserSkillDefinition.builder()
                    .name(definition.getName())
                    .description(definition.getDescription())
                    .content(definition.getContent())
                    .enabled(!Files.exists(directory.resolve(DISABLED_MARKER)))
                    .build();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Path findDirectory(String name) {
        String safeName = safeName(name);
        return roots().stream()
                .flatMap(this::skillDirectories)
                .filter(path -> {
                    UserSkillDefinition definition = read(path);
                    return definition != null && safeName.equals(definition.getName());
                })
                .findFirst()
                .orElseThrow(() -> new SkillLoadException("系统 Skill 不存在: " + safeName));
    }

    private Stream<Path> skillDirectories(Path root) {
        if (!Files.isDirectory(root)) {
            return Stream.empty();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> "SKILL.md".equals(path.getFileName().toString()))
                    .map(Path::getParent)
                    .filter(path -> StreamSupport.stream(path.spliterator(), false)
                            .noneMatch(part -> part.toString().startsWith(".upload-")))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new SkillLoadException("读取系统 Skills 失败", e);
        }
    }

    private List<Path> roots() {
        if (options.getDirectories() == null) {
            return List.of();
        }
        return options.getDirectories().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> Path.of(value).toAbsolutePath().normalize())
                .toList();
    }

    private Path writableRoot() {
        Path root = roots().stream().findFirst()
                .orElseThrow(() -> new SkillLoadException("未配置系统 Skill 目录"));
        try {
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new SkillLoadException("创建系统 Skill 目录失败", e);
        }
    }

    private void extract(InputStream input, Path root) throws IOException {
        long total = 0;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw new SkillLoadException("Skill 压缩包文件数量超过限制");
                }
                String name = entry.getName().replace('\\', '/');
                Path target = root.resolve(name).normalize();
                if (!target.startsWith(root) || name.startsWith("/")) {
                    throw new SkillLoadException("Skill 压缩包包含非法路径");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                long copied = copy(zip, target);
                total += copied;
                if (total > MAX_ARCHIVE_BYTES) {
                    throw new SkillLoadException("Skill 压缩包大小超过限制");
                }
            }
        }
    }

    private long copy(ZipInputStream input, Path target) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[8192];
        try (var output = Files.newOutputStream(target)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied += read;
                if (copied > MAX_ENTRY_BYTES) {
                    throw new SkillLoadException("Skill 文件大小超过限制");
                }
                output.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private Path singleSkillDirectory(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
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

    private String safeName(String value) {
        String name = value == null ? "" : value.trim();
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new SkillLoadException("Skill 名称仅允许字母、数字、下划线和短横线");
        }
        return name;
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new SkillLoadException("删除系统 Skill 失败", e);
                }
            });
        } catch (IOException e) {
            throw new SkillLoadException("删除系统 Skill 失败", e);
        }
    }
}
