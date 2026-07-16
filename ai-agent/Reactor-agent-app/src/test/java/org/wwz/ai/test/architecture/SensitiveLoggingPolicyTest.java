package org.wwz.ai.test.architecture;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Guards high-risk Agent logging surfaces against accidentally restoring raw prompts,
 * tool arguments, request/response bodies, credentials, or signed URLs.
 */
public class SensitiveLoggingPolicyTest {

    private static final Pattern RAW_SENSITIVE_GETTER = Pattern.compile(
            "\\.get(?:Query|OriginalQuery|BasePrompt|SopPrompt|HistoryDialogue|Arguments|Content|ApiKey|Token|Body|Headers|ServerUrl|BaseUri|Endpoint)\\s*\\(\\)\\s*[,)]"
    );
    private static final Pattern RAW_SENSITIVE_IDENTIFIER = Pattern.compile(
            "\\b(?:request|req|params|input|args|body|headers|data|responseBody|requestBody|resultObject|message|"
                    + "toolInput|task|query|prompt|formattedPrompt|content|text|result|llmResponse|jsonObject|jsonString|"
                    + "fullUrl|baseUrl|serverUrl|sopRecallUrl)\\b\\s*[,)]"
    );
    private static final Pattern RAW_PLACEHOLDER = Pattern.compile(
            "(?i)\\b(?:headers?|body|args?|arguments?|input|query|prompt|script|response|data|url|uri)\\s*[:=]\\s*\\{\\}"
    );
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    @Test
    public void shouldLogOnlyMetadataOnHighRiskAgentSurfaces() throws Exception {
        Path root = findAiAgentRoot();
        List<Path> guardedFiles = guardedFiles(root);
        List<String> violations = new ArrayList<>();

        for (Path file : guardedFiles) {
            Assert.assertTrue("Missing guarded source file: " + file, Files.isRegularFile(file));
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String logCall : extractLogCalls(source)) {
                String violation = violation(logCall);
                if (violation != null) {
                    violations.add(root.relativize(file) + ": " + violation);
                }
            }
        }

        Assert.assertTrue("Sensitive logging policy violations:\n" + String.join("\n", violations),
                violations.isEmpty());
    }

    private String violation(String logCall) {
        if (logCall.contains("toJSONString(")) {
            return "serializes an object directly inside a log call";
        }
        if (RAW_SENSITIVE_GETTER.matcher(logCall).find()) {
            return "logs a sensitive getter directly";
        }
        String withoutLiterals = STRING_LITERAL.matcher(logCall).replaceAll("\"\"");
        if (RAW_SENSITIVE_IDENTIFIER.matcher(withoutLiterals).find()) {
            return "logs a raw payload variable directly";
        }
        if (RAW_PLACEHOLDER.matcher(logCall).find()) {
            return "uses a raw sensitive-value placeholder";
        }
        String normalized = logCall.toLowerCase(Locale.ROOT);
        if (normalized.contains("recv data") || normalized.contains("raw response")) {
            return "logs raw stream or model response content";
        }
        return null;
    }

    private List<String> extractLogCalls(String source) {
        List<String> calls = new ArrayList<>();
        StringBuilder current = null;
        for (String line : source.split("\\R")) {
            if (current == null) {
                int start = line.indexOf("log.");
                if (start < 0) {
                    continue;
                }
                current = new StringBuilder(line.substring(start));
            } else {
                current.append('\n').append(line);
            }
            if (line.contains(");")) {
                calls.add(current.toString());
                current = null;
            }
        }
        return calls;
    }

    private List<Path> guardedFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String relative : List.of(
                "Reactor-agent-infrastructure/src/main/java/org/wwz/ai/infrastructure/gateway/HttpUtils.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/AiAgentController.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/reactor/ReactorController.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/AgentQueryServiceImpl.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/agent/ReActAgent.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/agent/ReactImplAgent.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/agent/ExecutorAgent.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/agent/BaseAgent.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/llm/BaseToolCallbackAdapter.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/llm/LlmChatModelResolver.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/skill/ScriptRunnerTool.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/skill/SkillScriptRunnerClient.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/CodeInterpreterTool.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/DataAnalysisTool.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/DeepSearchTool.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/ReportTool.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/rag/SopRecallService.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/reactor/service/VectorService.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/mcp/runtime/McpClientRuntimeFactory.java",
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/service/armory/util/McpConnectionDiagnostic.java",
                "Reactor-agent-app/src/main/java/org/wwz/ai/config/AiAgentAutoConfiguration.java",
                "Reactor-agent-case/src/main/java/org/wwz/ai/application/agent/stream/AgentSessionPrinter.java",
                "Reactor-agent-case/src/main/java/org/wwz/ai/application/agent/execute/react/ReactAgentExecuteStrategy.java",
                "Reactor-agent-case/src/main/java/org/wwz/ai/application/agent/execute/planexecute/PlanSolveAgentExecuteStrategy.java",
                "Reactor-agent-case/src/main/java/org/wwz/ai/application/agent/execute/workflow/FlowAgentExecuteStrategy.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/admin/AiClientApiAdminController.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/admin/AiClientModelAdminController.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/admin/AiClientAdminController.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/admin/AiClientToolMcpAdminController.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/admin/AiClientSystemPromptAdminController.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/admin/AiClientAdvisorAdminController.java",
                "Reactor-agent-trigger/src/main/java/org/wwz/ai/trigger/http/admin/AiClientRagOrderAdminController.java"
        )) {
            files.add(root.resolve(relative));
        }

        Path armoryNodes = root.resolve(
                "Reactor-agent-domain/src/main/java/org/wwz/ai/domain/agent/service/armory/node");
        try (Stream<Path> stream = Files.walk(armoryNodes)) {
            stream.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("PlanningAgent.java"))
                    .forEach(files::add);
        }
        return files.stream().distinct().toList();
    }

    private Path findAiAgentRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("Reactor-agent-domain/src/main/java"))) {
                return cursor;
            }
            Path nested = cursor.resolve("ai-agent");
            if (Files.isDirectory(nested.resolve("Reactor-agent-domain/src/main/java"))) {
                return nested;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate ai-agent source root");
    }
}
