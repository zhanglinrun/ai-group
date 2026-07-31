package com.linrun.agent.eval.report;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Reproducibility metadata only; never reads or emits environment secrets. */
public record RuntimeFingerprint(String gitCommit, boolean dirtyWorktree, String configHash) {
    public static RuntimeFingerprint capture(Path workspace, String configMaterial) {
        String status = command(workspace, "status", "--porcelain");
        return new RuntimeFingerprint(command(workspace, "rev-parse", "HEAD"),
                !status.equals("unknown") && !status.isBlank(), sha256(configMaterial));
    }

    private static String command(Path workspace, String... arguments) {
        try {
            String[] command = new String[arguments.length + 1];
            command[0] = "git";
            System.arraycopy(arguments, 0, command, 1, arguments.length);
            Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                // Consume the complete stream before waiting: `git status --porcelain` can exceed the pipe buffer.
                String value = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
                process.waitFor();
                return process.exitValue() == 0 ? value.trim() : "unknown";
            }
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
