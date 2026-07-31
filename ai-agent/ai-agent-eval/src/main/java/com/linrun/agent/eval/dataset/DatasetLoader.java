package com.linrun.agent.eval.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class DatasetLoader {
    private final ObjectMapper json = new ObjectMapper();

    public EvalDataset load(Path path) throws IOException {
        return parse(path.getFileName().toString(), Files.readAllBytes(path));
    }

    public EvalDataset loadClasspath(List<String> resources) throws IOException {
        List<byte[]> bodies = new ArrayList<>();
        for (String resource : resources) {
            try (InputStream input = DatasetLoader.class.getClassLoader().getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("missing eval dataset resource: " + resource);
                }
                bodies.add(input.readAllBytes());
            }
        }
        int length = bodies.stream().mapToInt(body -> body.length + 1).sum();
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] body : bodies) {
            System.arraycopy(body, 0, joined, offset, body.length);
            offset += body.length;
            joined[offset++] = '\n';
        }
        return parse("researchpilot-golden", joined);
    }

    private EvalDataset parse(String name, byte[] body) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        String[] lines = new String(body, StandardCharsets.UTF_8).split("\\R");
        for (int lineNo = 0; lineNo < lines.length; lineNo++) {
            String line = lines[lineNo].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            EvalCase evalCase;
            try {
                evalCase = EvalCase.from(json.readTree(line));
            } catch (RuntimeException error) {
                throw new IOException("invalid eval case at line " + (lineNo + 1), error);
            }
            if (!ids.add(evalCase.id())) {
                throw new IOException("duplicate eval case id: " + evalCase.id());
            }
            cases.add(evalCase);
        }
        return new EvalDataset(name, sha256(body), cases);
    }

    private static String sha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte value : digest) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
