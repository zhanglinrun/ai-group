package com.linrun.agent.test.domain;

import com.linrun.agent.domain.agent.reactor.config.ReactorConfig;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.agent.ToolInvocationContract;
import com.linrun.agent.domain.agent.runtime.completion.ToolExecutionEvidence;
import com.linrun.agent.domain.agent.runtime.context.ContextBudget;
import com.linrun.agent.domain.agent.runtime.context.ContextManager;
import com.linrun.agent.domain.agent.runtime.context.ManagedContext;
import com.linrun.agent.domain.agent.runtime.dto.Memory;
import com.linrun.agent.domain.agent.runtime.dto.Message;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolChoice;
import com.linrun.agent.domain.agent.runtime.enums.AgentExecutionProfile;
import com.linrun.agent.domain.agent.runtime.enums.RoleType;
import com.linrun.agent.domain.agent.runtime.llm.TokenCounter;
import com.linrun.agent.domain.agent.runtime.loop.ContextPipeline;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.common.TodoWriteTool;
import com.linrun.agent.domain.agent.runtime.work.TodoStepEvidenceScope;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ContextPipelineTodoStateTest {

    @Test
    public void shouldExposeNoToolsForModelOnlyCompositionTurn() {
        AgentContext context = AgentContext.builder()
                .query("根据已获取的证据撰写研究结论")
                .executionProfile(AgentExecutionProfile.STANDARD)
                .toolInvocationContract(ToolInvocationContract.modelOnly())
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .build();
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(context);
        ToolCollection catalog = catalog(context, todoWriteTool);

        ContextPipeline.PreparedModelTurn turn = new ContextPipeline().prepareTurn(
                context,
                new ReactorConfig(),
                new ContextPipeline.PromptState("stable-system", "compose-answer"),
                new Memory(),
                catalog,
                1);

        Assert.assertEquals(ToolChoice.NONE, turn.toolChoice());
        Assert.assertTrue(turn.exposedTools().getToolMap().isEmpty());
        Assert.assertNull(turn.exposedTools().getTool("business_probe"));
        Assert.assertNull(turn.exposedTools().getTool(TodoWriteTool.NAME));
    }

    @Test
    public void shouldExposeOnlyTodoWriteUntilDeepTodoIsCreated() {
        AgentContext context = AgentContext.builder()
                .query("执行深度配额核验")
                .executionProfile(AgentExecutionProfile.DEEP)
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .build();
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(context);
        ToolCollection catalog = catalog(context, todoWriteTool);

        ContextPipeline.PreparedModelTurn turn = new ContextPipeline().prepareTurn(
                context,
                new ReactorConfig(),
                new ContextPipeline.PromptState("stable-system", "continue-current-work"),
                new Memory(),
                catalog,
                1);

        Assert.assertEquals(ToolChoice.REQUIRED, turn.toolChoice());
        Assert.assertEquals(List.of(TodoWriteTool.NAME),
                new ArrayList<>(turn.exposedTools().getToolMap().keySet()));
        Assert.assertNull(turn.exposedTools().getTool("business_probe"));
        Assert.assertTrue(turn.systemPrompt().contains("state: none"));
        Assert.assertEquals(2, context.getAgentRunState().getLatestToolCatalogCountValue());
        Assert.assertEquals(1, context.getAgentRunState().getLatestExposedToolCountValue());
        Assert.assertEquals(turn.exposedTools().estimateSchemaChars(),
                context.getAgentRunState().getLatestToolSchemaCharsValue());
    }

    @Test
    public void shouldKeepTodoReconciliationGateClosedUntilSuccessfulTodoMutation() {
        AgentContext context = AgentContext.builder()
                .query("执行深度配额核验")
                .executionProfile(AgentExecutionProfile.DEEP)
                .toolExecutionEvidence(new ArrayList<>())
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .build();
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(context);
        ToolCollection catalog = catalog(context, todoWriteTool);
        todoWriteTool.execute(Map.of(
                "command", "create",
                "title", "配额核验",
                "steps", List.of("核对参数", "调用配额工具"),
                "evidence_policies", List.of("TOOL", "TOOL")
        ));
        context.recordToolExecutionEvidence(evidence(
                "todo-create", TodoWriteTool.NAME, true));
        context.recordToolExecutionEvidence(evidenceForCurrentStep(
                todoWriteTool, "business-call-1", "business_probe", true));

        ContextPipeline pipeline = new ContextPipeline();
        ContextPipeline.PromptState promptState = new ContextPipeline.PromptState(
                "stable-system", "continue-current-work");
        Memory memory = new Memory();
        ContextPipeline.PreparedModelTurn pending = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, memory, catalog, 2);

        Assert.assertEquals(ToolChoice.REQUIRED, pending.toolChoice());
        Assert.assertEquals(List.of(TodoWriteTool.NAME),
                new ArrayList<>(pending.exposedTools().getToolMap().keySet()));
        Assert.assertTrue(pending.systemPrompt().contains(
                "evidence_reconciliation_required: true"));
        Assert.assertTrue(pending.systemPrompt().contains(
                "pending_successful_evidence:\n- tool_call_id=business-call-1 | tool=business_probe"));
        Assert.assertTrue(pending.systemPrompt().contains(
                "tool_call_id=business-call-1 | tool=business_probe"));

        context.recordToolExecutionEvidence(evidence(
                "todo-mark-invalid", TodoWriteTool.NAME, false));
        ContextPipeline.PreparedModelTurn afterFailedTodo = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, memory, catalog, 3);
        Assert.assertEquals(List.of(TodoWriteTool.NAME),
                new ArrayList<>(afterFailedTodo.exposedTools().getToolMap().keySet()));

        try {
            todoWriteTool.execute(Map.of(
                    "command", "update",
                    "steps", List.of("绕过 evidence 的计划更新")
            ));
            Assert.fail("update must not clear a pending evidence reconciliation gate");
        } catch (IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("pending reconciliation"));
        }
        try {
            todoWriteTool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "in_progress",
                    "step_notes", "未显式消费 evidence"
            ));
            Assert.fail("an empty in_progress mark must not clear reconciliation");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("pending business evidence"));
        }

        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "in_progress",
                "step_notes", "参数核验仍需补充，但已保存当前工具结果",
                "evidence_refs", List.of("business-call-1")
        ));
        ContextPipeline.PreparedModelTurn reconciled = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, memory, catalog, 4);

        Assert.assertNotNull(reconciled.exposedTools().getTool("business_probe"));
        Assert.assertFalse(reconciled.systemPrompt().contains(
                "evidence_reconciliation_required: true"));
        Assert.assertTrue(reconciled.systemPrompt().contains(
                "pending_successful_evidence:\n- none"));
        Assert.assertEquals("in_progress",
                todoWriteTool.getTodoListSnapshot().getStepStatus().get(0));
        Assert.assertEquals(List.of("business-call-1"),
                todoWriteTool.getTodoListSnapshot().getEvidenceRefs().get(0));

        context.recordToolExecutionEvidence(evidenceForCurrentStep(
                todoWriteTool, "business-call-2", "business_probe", true));
        ContextPipeline.PreparedModelTurn repeatedBusiness = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, memory, catalog, 5);
        Assert.assertEquals(List.of(TodoWriteTool.NAME),
                new ArrayList<>(repeatedBusiness.exposedTools().getToolMap().keySet()));
        Assert.assertTrue(repeatedBusiness.systemPrompt().contains(
                "tool_call_id=business-call-2 | tool=business_probe"));

        try {
            todoWriteTool.execute(Map.of(
                    "command", "mark_step",
                    "step_index", 0,
                    "step_status", "in_progress",
                    "evidence_refs", List.of("business-call-1")
            ));
            Assert.fail("an already acknowledged ref must not clear a new reconciliation gate");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("pending business evidence"));
        }
        Assert.assertTrue(todoWriteTool.requiresEvidenceReconciliation());

        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "in_progress",
                "evidence_refs", List.of("business-call-2")
        ));
        ContextPipeline.PreparedModelTurn secondReconciled = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, memory, catalog, 6);
        Assert.assertNotNull(secondReconciled.exposedTools().getTool("business_probe"));
    }

    @Test
    public void shouldExposeOnlyTodoWriteForNoneStepEvenUnderExclusiveToolContract() {
        AgentContext context = AgentContext.builder()
                .query("必须调用 business_probe 完成配额核验")
                .executionProfile(AgentExecutionProfile.DEEP)
                .toolInvocationContract(new ToolInvocationContract(
                        Set.of("business_probe"),
                        Set.of("business_probe"),
                        Set.of(),
                        true))
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .build();
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(context);
        ToolCollection catalog = catalog(context, todoWriteTool);
        todoWriteTool.execute(Map.of(
                "command", "create",
                "title", "分步配额核验",
                "steps", List.of("核对参数", "调用配额工具"),
                "evidence_policies", List.of("NONE", "TOOL")
        ));

        ContextPipeline pipeline = new ContextPipeline();
        ContextPipeline.PromptState promptState = new ContextPipeline.PromptState(
                "stable-system", "continue-current-work");
        ContextPipeline.PreparedModelTurn cognitiveTurn = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, new Memory(), catalog, 2);

        Assert.assertEquals(ToolChoice.REQUIRED, cognitiveTurn.toolChoice());
        Assert.assertEquals(List.of(TodoWriteTool.NAME),
                new ArrayList<>(cognitiveTurn.exposedTools().getToolMap().keySet()));
        Assert.assertNull(cognitiveTurn.exposedTools().getTool("business_probe"));
        Assert.assertTrue(cognitiveTurn.systemPrompt().contains("evidence_policy=NONE"));

        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed",
                "step_notes", "已从用户输入核对"
        ));
        ContextPipeline.PreparedModelTurn toolTurn = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, new Memory(), catalog, 3);

        Assert.assertNotNull(toolTurn.exposedTools().getTool("business_probe"));
        Assert.assertNotNull(toolTurn.exposedTools().getTool(TodoWriteTool.NAME));
        Assert.assertTrue(toolTurn.systemPrompt().contains("evidence_policy=TOOL"));
    }

    @Test
    public void shouldInjectFreshTodoSnapshotAfterEarlierToolMessagesWereCompactedAway() {
        AgentContext context = AgentContext.builder()
                .query("完成三步调研")
                .task("")
                .productFiles(new ArrayList<>())
                .taskProductFiles(new ArrayList<>())
                .toolExecutionEvidence(List.of(ToolExecutionEvidence.builder()
                        .toolCallId("search-call-001")
                        .toolName("deep_search")
                        .success(true)
                        .build()))
                .build();
        TodoWriteTool todoWriteTool = new TodoWriteTool();
        todoWriteTool.setAgentContext(context);
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(todoWriteTool);
        catalog.setAgentContext(context);

        todoWriteTool.execute(Map.of(
                "command", "create",
                "title", "产品调研",
                "steps", List.of("核验价格", "核验能力", "形成比较")
        ));
        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 0,
                "step_status", "completed",
                "step_notes", "价格来源已核验",
                "evidence_refs", List.of("search-call-001")
        ));

        Memory compactedMemory = memoryWithEarlyTodoCallAndLongTail();
        int memorySizeBeforeTurn = compactedMemory.size();
        ContextPipeline pipeline = new ContextPipeline();
        ContextPipeline.PromptState promptState = new ContextPipeline.PromptState(
                "stable-system", "continue-current-work");

        ContextPipeline.PreparedModelTurn firstTurn = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, compactedMemory, catalog, 2);
        Assert.assertEquals(ToolChoice.REQUIRED, firstTurn.toolChoice());
        Assert.assertTrue(firstTurn.systemPrompt().contains(
                "Do not produce a final answer while this Todo is incomplete."));
        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 1,
                "step_status", "completed",
                "step_notes", "能力来源已核验",
                "evidence_refs", List.of("search-call-001")
        ));
        ContextPipeline.PreparedModelTurn secondTurn = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, compactedMemory, catalog, 3);

        String firstSystemPrompt = firstTurn.systemPrompt();
        Assert.assertTrue(firstSystemPrompt.endsWith("</current_todo_state>"));
        Assert.assertTrue(firstSystemPrompt.contains("completed_prefix:\n- #0 [completed] 核验价格"));
        Assert.assertTrue(firstSystemPrompt.contains("evidence=search-call-001"));
        Assert.assertTrue(firstSystemPrompt.contains("in_progress:\n- #1 [in_progress] 核验能力"));
        Assert.assertTrue(firstSystemPrompt.contains("pending_suffix:\n- #2 [not_started] 形成比较"));
        Assert.assertEquals(1, occurrences(firstSystemPrompt, "<current_todo_state>"));

        String secondSystemPrompt = secondTurn.systemPrompt();
        Assert.assertNotEquals(firstSystemPrompt, secondSystemPrompt);
        Assert.assertTrue(secondSystemPrompt.contains("- #1 [completed] 核验能力"));
        Assert.assertTrue(secondSystemPrompt.contains("in_progress:\n- #2 [in_progress] 形成比较"));
        Assert.assertTrue(secondSystemPrompt.contains("pending_suffix:\n- none"));
        Assert.assertEquals(1, occurrences(secondSystemPrompt, "<current_todo_state>"));

        Assert.assertEquals(memorySizeBeforeTurn, compactedMemory.size());
        Assert.assertTrue(compactedMemory.getMessages().stream()
                .noneMatch(message -> message.getContent() != null
                        && message.getContent().contains("<current_todo_state>")));

        List<Message> requestMessages = new ArrayList<>();
        requestMessages.add(Message.systemMessage(secondSystemPrompt, null));
        requestMessages.addAll(compactedMemory.getMessages());
        ManagedContext managed = new ContextManager(new TokenCounter()).prepare(
                requestMessages, ContextBudget.forModel(320, 0));

        Assert.assertTrue(managed.compacted());
        Assert.assertTrue(managed.messages().stream()
                .noneMatch(message -> message.getContent() != null
                        && message.getContent().contains("EARLY_TODO_TOOL_OBSERVATION")));
        Message managedSystem = managed.messages().stream()
                .filter(message -> message.getRole() == RoleType.SYSTEM)
                .findFirst()
                .orElseThrow();
        Assert.assertTrue(managedSystem.getContent().contains("completed_prefix:\n- #0 [completed] 核验价格"));
        Assert.assertTrue(managedSystem.getContent().contains("- #1 [completed] 核验能力"));
        Assert.assertTrue(managedSystem.getContent().contains("in_progress:\n- #2 [in_progress] 形成比较"));
        Assert.assertTrue(managedSystem.getContent().endsWith("</current_todo_state>"));

        todoWriteTool.execute(Map.of(
                "command", "mark_step",
                "step_index", 2,
                "step_status", "completed",
                "step_notes", "比较结论已形成",
                "evidence_refs", List.of("search-call-001")
        ));
        ContextPipeline.PreparedModelTurn completedTurn = pipeline.prepareTurn(
                context, new ReactorConfig(), promptState, compactedMemory, catalog, 4);
        Assert.assertEquals(ToolChoice.AUTO, completedTurn.toolChoice());
        Assert.assertFalse(completedTurn.systemPrompt().contains(
                "Do not produce a final answer while this Todo is incomplete."));
    }

    private Memory memoryWithEarlyTodoCallAndLongTail() {
        ToolCall todoCall = ToolCall.builder()
                .id("todo-call-early")
                .type("function")
                .function(ToolCall.Function.builder()
                        .name(TodoWriteTool.NAME)
                        .arguments("{\"command\":\"create\"}")
                        .build())
                .build();
        Memory memory = new Memory();
        memory.addMessage(Message.fromToolCalls("create the todo list", List.of(todoCall)));
        memory.addMessage(Message.toolMessage(
                "EARLY_TODO_TOOL_OBSERVATION", "todo-call-early", null));
        for (int index = 0; index < 16; index++) {
            memory.addMessage(Message.assistantMessage(
                    "historical-assistant-" + index + " " + "detail ".repeat(40), null));
            memory.addMessage(Message.userMessage(
                    "historical-user-" + index + " " + "context ".repeat(40), null));
        }
        return memory;
    }

    private ToolCollection catalog(AgentContext context, TodoWriteTool todoWriteTool) {
        ToolCollection catalog = new ToolCollection();
        catalog.addTool(todoWriteTool);
        catalog.addTool(new BaseTool() {
            @Override
            public String getName() {
                return "business_probe";
            }

            @Override
            public String getDescription() {
                return "Test-only business capability";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                return "ok";
            }
        });
        catalog.setAgentContext(context);
        return catalog;
    }

    private ToolExecutionEvidence evidence(String toolCallId,
                                            String toolName,
                                            boolean success) {
        return ToolExecutionEvidence.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .success(success)
                .build();
    }

    private ToolExecutionEvidence evidenceForCurrentStep(TodoWriteTool tool,
                                                         String toolCallId,
                                                         String toolName,
                                                         boolean success) {
        TodoStepEvidenceScope scope = tool.getCurrentStepEvidenceScope();
        Assert.assertNotNull(scope);
        return ToolExecutionEvidence.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .success(success)
                .todoStepIndex(scope.stepIndex())
                .todoStepActivationId(scope.activationId())
                .build();
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
