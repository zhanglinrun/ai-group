package com.linrun.agent.test.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.agent.domain.agent.runtime.stream.AgentStreamEvent;
import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class AgentStreamEventContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void javaEventsMatchSharedFrontendContractFixtures() throws Exception {
        List<AgentStreamEvent> events = List.of(
                new AgentStreamEvent.AgentStart("run-1", "owner-1", "conversation-1", "AgentLoop", "qwen-plus"),
                new AgentStreamEvent.Thinking("run-1", "分析问题"),
                new AgentStreamEvent.Text("run-1", "结论"),
                new AgentStreamEvent.ToolStart("run-1", "call-1", "deep_search", Map.of("query", "AI Agent")),
                new AgentStreamEvent.ToolEnd("run-1", "call-1", "deep_search", Map.of("hits", 3), true, 1200),
                new AgentStreamEvent.TodoProgress("run-1", List.of(Map.of("title", "检索", "status", "completed"))),
                new AgentStreamEvent.Paused("run-1", "approval-1", "call-2", "image_generation",
                        Map.of("prompt", "diagram"), 200000, "2026-07-27T12:00:00Z"),
                new AgentStreamEvent.ResumeStart("run-1", "approval-1", "call-2", "APPROVED"),
                new AgentStreamEvent.StageOutput("run-1", "call-3", "markdown",
                        Map.of("content", "# Report"),
                        List.of(Map.of("artifactId", "artifact-1", "fileName", "report.md")), true),
                new AgentStreamEvent.Error("run-1", "TOOL_FAILED", "tool failed"),
                new AgentStreamEvent.Complete("run-1", "done", 2500, 200000));
        JsonNode expected = objectMapper.readTree(Files.readString(findFixture()));
        JsonNode actual = objectMapper.readTree(objectMapper.writeValueAsString(events));

        Assert.assertEquals(11, events.size());
        Assert.assertEquals(expected, actual);
    }

    private Path findFixture() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("contracts/agent-stream-events.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("contracts/agent-stream-events.json not found");
    }
}
