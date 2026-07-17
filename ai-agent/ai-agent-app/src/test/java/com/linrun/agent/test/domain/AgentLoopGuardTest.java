package com.linrun.agent.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import com.linrun.agent.domain.agent.ledger.AgentExecutionRecorder;
import com.linrun.agent.domain.agent.ledger.model.ExecutionLedgerConstants;
import com.linrun.agent.domain.agent.ledger.model.ToolInvocationFinishRecord;
import com.linrun.agent.domain.agent.runtime.agent.BaseAgent;
import com.linrun.agent.domain.agent.runtime.agent.AgentContext;
import com.linrun.agent.domain.agent.runtime.enums.AgentState;
import com.linrun.agent.domain.agent.runtime.enums.AgentStopReason;
import com.linrun.agent.domain.agent.runtime.harness.AgentRunBudget;
import com.linrun.agent.domain.agent.runtime.harness.StopGate;
import com.linrun.agent.domain.agent.runtime.printer.Printer;
import com.linrun.agent.domain.agent.runtime.dto.tool.McpToolInfo;
import com.linrun.agent.domain.agent.runtime.dto.tool.ToolCall;
import com.linrun.agent.domain.agent.runtime.tool.BaseTool;
import com.linrun.agent.domain.agent.runtime.tool.ToolCollection;
import com.linrun.agent.domain.agent.runtime.tool.ToolResultPayload;
import com.linrun.agent.domain.agent.runtime.tool.mcp.runtime.McpToolExecutor;
import com.linrun.agent.domain.agent.ledger.model.AgentRunState;
import com.linrun.agent.domain.agent.runtime.ReactorRuntimeDependencies;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 主循环死循环兜底与断开中止测试（无需 LLM/中间件，离线可跑）。
 */
public class AgentLoopGuardTest {

    /**
     * 每步返回固定结果的最小 Agent，用于验证 run() 的通用终止逻辑。
     */
    static class ConstantStepAgent extends BaseAgent {
        private final String stepResult;
        private final AtomicInteger stepCalls = new AtomicInteger();

        ConstantStepAgent(String stepResult, int maxSteps, int duplicateThreshold) {
            this.stepResult = stepResult;
            setName("test-agent");
            setMaxSteps(maxSteps);
            setDuplicateThreshold(duplicateThreshold);
        }

        @Override
        public String step() {
            stepCalls.incrementAndGet();
            return stepResult;
        }

        int stepCalls() {
            return stepCalls.get();
        }
    }

    static class ToolTurnAgent extends BaseAgent {
        private final List<List<ToolCall>> toolTurns;
        private final List<String> observations;
        private int turnIndex;
        private List<ToolCall> currentToolCalls = List.of();

        ToolTurnAgent(List<List<ToolCall>> toolTurns,
                      List<String> observations,
                      int duplicateThreshold) {
            this.toolTurns = toolTurns;
            this.observations = observations;
            setName("tool-turn-agent");
            setMaxSteps(toolTurns.size());
            setDuplicateThreshold(duplicateThreshold);
        }

        @Override
        public String step() {
            currentToolCalls = toolTurns.get(turnIndex);
            return observations.get(turnIndex++);
        }

        @Override
        protected String repetitionSignature(String stepResult) {
            return StopGate.toolCallsSignature(currentToolCalls);
        }

        int turnCalls() {
            return turnIndex;
        }
    }

    @Test
    public void shouldBreakOnRepeatedStepsBeforeMaxSteps() {
        ConstantStepAgent agent = new ConstantStepAgent("same-observation", 40, 2);

        String last = agent.run("请开始");

        // 连续重复达到阈值即以可识别的死循环终止标识收尾，而不是空转到 40 步
        Assert.assertEquals(BaseAgent.TERMINATION_STUCK, last);
        Assert.assertTrue("stuck should stop well before maxSteps", agent.stepCalls() <= 4);
        Assert.assertEquals(AgentStopReason.REPEATED_TURN, agent.getStopReason());
    }

    @Test
    public void shouldRunToMaxStepsWhenDuplicateDetectionDisabled() {
        ConstantStepAgent agent = new ConstantStepAgent("obs", 5, 0);

        String last = agent.run("请开始");

        Assert.assertTrue(last.startsWith(BaseAgent.TERMINATION_MAX_TURNS));
        Assert.assertEquals(5, agent.stepCalls());
        Assert.assertEquals(AgentStopReason.MAX_TURNS, agent.getStopReason());
    }

    @Test
    public void shouldNotTreatEqualObservationsFromDifferentToolActionsAsRepeated() {
        ToolTurnAgent agent = new ToolTurnAgent(
                List.of(
                        List.of(toolCall("call-1", "search", "{\"query\":\"alpha\"}")),
                        List.of(toolCall("call-2", "search", "{\"query\":\"beta\"}")),
                        List.of(toolCall("call-3", "read_file", "{\"path\":\"alpha\"}"))
                ),
                List.of("same-observation", "same-observation", "same-observation"),
                1
        );

        String last = agent.run("start");

        Assert.assertTrue(last.startsWith(BaseAgent.TERMINATION_MAX_TURNS));
        Assert.assertEquals(3, agent.turnCalls());
        Assert.assertEquals(AgentStopReason.MAX_TURNS, agent.getStopReason());
    }

    @Test
    public void shouldDetectSameToolActionDespiteNewCallIdsAndVolatileResults() {
        ToolTurnAgent agent = new ToolTurnAgent(
                List.of(
                        List.of(toolCall("call-1", "search", "{\"query\":\"alpha\",\"limit\":10}")),
                        List.of(toolCall("call-2", "search", "{ \"limit\" : 10, \"query\" : \"alpha\" }")),
                        List.of(toolCall("call-3", "search", "{\"query\":\"alpha\",\"limit\":10}"))
                ),
                List.of(
                        "completedAt=2026-07-17T10:00:00Z",
                        "completedAt=2026-07-17T10:00:01Z",
                        "completedAt=2026-07-17T10:00:02Z"
                ),
                2
        );

        String last = agent.run("start");

        Assert.assertEquals(BaseAgent.TERMINATION_STUCK, last);
        Assert.assertEquals(3, agent.turnCalls());
        Assert.assertEquals(AgentStopReason.REPEATED_TURN, agent.getStopReason());
    }

    @Test
    public void shouldGiveEachRunAnIndependentStepBudget() {
        List<Integer> observedSteps = new ArrayList<>();
        BaseAgent agent = new BaseAgent() {
            @Override
            public String step() {
                observedSteps.add(getCurrentStep());
                if (getCurrentStep() == 2) {
                    setState(AgentState.FINISHED);
                }
                return "step-" + getCurrentStep();
            }
        };
        agent.setMaxSteps(3);
        agent.setDuplicateThreshold(0);

        Assert.assertEquals("step-2", agent.run("first"));
        Assert.assertEquals("step-2", agent.run("second"));
        Assert.assertEquals(List.of(1, 2, 1, 2), observedSteps);
    }

    @Test
    public void shouldStopImmediatelyWhenDownstreamAborted() {
        ConstantStepAgent agent = new ConstantStepAgent("obs", 40, 0);
        Printer abortedPrinter = Mockito.mock(Printer.class);
        Mockito.when(abortedPrinter.isAborted()).thenReturn(true);
        agent.setPrinter(abortedPrinter);

        String last = agent.run("请开始");

        // 断开后不应执行任何步骤
        Assert.assertEquals(0, agent.stepCalls());
        Assert.assertEquals("No steps executed", last);
        Assert.assertEquals(AgentStopReason.DOWNSTREAM_ABORTED, agent.getStopReason());
    }

    @Test
    public void shouldReturnFinalStepResultWhenAgentFinishesAtMaxStepBoundary() {
        BaseAgent agent = new BaseAgent() {
            @Override
            public String step() {
                setState(AgentState.FINISHED);
                return "final-answer";
            }
        };
        agent.setMaxSteps(1);
        agent.setDuplicateThreshold(0);

        String result = agent.run("start");

        Assert.assertEquals("final-answer", result);
        Assert.assertEquals(AgentState.FINISHED, agent.getState());
        Assert.assertEquals(AgentStopReason.COMPLETED, agent.getStopReason());
    }

    @Test
    public void shouldStopBeforeNextTurnWhenTokenBudgetIsExhausted() {
        AgentRunState runState = AgentRunState.builder().build();
        runState.recordLlmUsage(5, 0);
        ConstantStepAgent agent = new ConstantStepAgent("unused", 4, 0);
        agent.setRunBudget(new AgentRunBudget(4, 8, 3, 60_000, 5, 1_000));
        agent.setContext(AgentContext.builder().agentRunState(runState).build());

        String result = agent.run("start");

        Assert.assertEquals("Terminated: TOKEN_BUDGET", result);
        Assert.assertEquals(0, agent.stepCalls());
        Assert.assertEquals(AgentStopReason.TOKEN_BUDGET, agent.getStopReason());
    }

    @Test
    public void shouldRejectBatchThatExceedsToolCallBudget() {
        BaseAgent agent = new BaseAgent() {
            @Override
            public String step() {
                return executeTools(List.of(toolCall("one"), toolCall("two"))).values().iterator().next();
            }
        };
        agent.setRunBudget(new AgentRunBudget(2, 1, 3, 60_000, 10_000, 1_000));

        String result = agent.run("start");

        Assert.assertTrue(result.contains("Tool call budget exceeded"));
        Assert.assertEquals(AgentStopReason.TOOL_CALL_BUDGET, agent.getStopReason());
    }

    @Test
    public void shouldEnforceExplicitSingleUseToolConstraint() {
        AtomicInteger executions = new AtomicInteger();
        ToolCollection tools = new ToolCollection();
        tools.addTool(new BaseTool() {
            @Override
            public String getName() {
                return "once_tool";
            }

            @Override
            public String getDescription() {
                return "test";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                executions.incrementAndGet();
                return "ok";
            }
        });
        BaseAgent agent = new BaseAgent() {
            @Override
            public String step() {
                return "unused";
            }
        };
        agent.setAvailableTools(tools);
        agent.setSingleUseToolName("once_tool");
        agent.setContext(AgentContext.builder().agentRunState(AgentRunState.builder().build()).build());

        Assert.assertEquals("ok", agent.executeTool(toolCall("first", "once_tool", "{\"value\":1}")));
        Assert.assertTrue(agent.executeTool(toolCall(
                "second", "once_tool", "{ \"value\" : 1 }"))
                .contains("Reused prior successful result"));
        Assert.assertTrue(agent.executeTool(toolCall(
                "third", "once_tool", "{\"value\":2}"))
                .contains("limited by the user"));
        Assert.assertEquals(1, executions.get());
    }

    @Test
    public void shouldNotRetryToolUnlessItExplicitlyOptsIn() {
        AtomicInteger executions = new AtomicInteger();
        ToolCollection tools = new ToolCollection();
        tools.addTool(new BaseTool() {
            @Override
            public String getName() {
                return "side_effect_tool";
            }

            @Override
            public String getDescription() {
                return "may mutate remote state";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                executions.incrementAndGet();
                throw new IllegalStateException("remote write failed");
            }
        });
        BaseAgent agent = toolAgent(tools);
        agent.setToolMaxAttempts(3);

        String result = agent.executeTool(toolCall("side-effect-call", "side_effect_tool"));

        Assert.assertTrue(result.contains("Error"));
        Assert.assertEquals(1, executions.get());
    }

    @Test
    public void shouldRetryOnlyOptedInToolWithinConfiguredBound() {
        AtomicInteger executions = new AtomicInteger();
        ToolCollection tools = new ToolCollection();
        tools.addTool(new BaseTool() {
            @Override
            public String getName() {
                return "retryable_read_tool";
            }

            @Override
            public String getDescription() {
                return "idempotent read";
            }

            @Override
            public Map<String, Object> toParams() {
                return Map.of("type", "object");
            }

            @Override
            public Object execute(Object input) {
                if (executions.incrementAndGet() < 2) {
                    throw new IllegalStateException("transient read failure");
                }
                return "ok";
            }

            @Override
            public boolean isRetryable() {
                return true;
            }
        });
        BaseAgent agent = toolAgent(tools);
        agent.setToolMaxAttempts(3);

        Assert.assertEquals("ok", agent.executeTool(toolCall("retryable-call", "retryable_read_tool")));
        Assert.assertEquals(2, executions.get());
    }

    private BaseAgent toolAgent(ToolCollection tools) {
        BaseAgent agent = new BaseAgent() {
            @Override
            public String step() {
                return "unused";
            }
        };
        AgentContext context = AgentContext.builder()
                .requestId("retry-policy")
                .sessionId("retry-policy-session")
                .agentRunState(AgentRunState.builder().build())
                .build();
        tools.setAgentContext(context);
        agent.setContext(context);
        agent.setAvailableTools(tools);
        return agent;
    }

    @Test(timeout = 3000L)
    public void shouldStopHungToolBatchAtRunDeadlineAndInterruptWorker() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch toolInterrupted = new CountDownLatch(1);
        try {
            ToolCollection tools = new ToolCollection();
            tools.addTool(new BaseTool() {
                @Override
                public String getName() {
                    return "hung_tool";
                }

                @Override
                public String getDescription() {
                    return "waits until cancelled";
                }

                @Override
                public Map<String, Object> toParams() {
                    return Map.of("type", "object");
                }

                @Override
                public Object execute(Object input) {
                    toolStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                        return "unexpected";
                    } catch (InterruptedException interruptedException) {
                        toolInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        return "interrupted";
                    }
                }
            });

            AgentContext context = AgentContext.builder()
                    .requestId("run-deadline")
                    .sessionId("session-deadline")
                    .agentRunState(AgentRunState.builder().build())
                    .runtimeDependencies(ReactorRuntimeDependencies.builder()
                            .toolExecutor(executor)
                            .build())
                    .build();
            tools.setAgentContext(context);

            BaseAgent agent = new BaseAgent() {
                @Override
                public String step() {
                    return executeTools(List.of(toolCall("hung-call", "hung_tool")))
                            .get("hung-call");
                }
            };
            agent.setName("deadline-agent");
            agent.setContext(context);
            agent.setAvailableTools(tools);
            agent.setDuplicateThreshold(0);
            agent.setRunBudget(new AgentRunBudget(3, 3, 1, 150L, 10_000L, 1_000L));

            long startedAt = System.nanoTime();
            String result = agent.run("start");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            Assert.assertTrue(toolStarted.await(1, TimeUnit.SECONDS));
            Assert.assertEquals(AgentStopReason.TIME_BUDGET, agent.getStopReason());
            Assert.assertTrue(result.contains("time budget"));
            Assert.assertTrue("run should return near its deadline", elapsedMillis < 1500L);
            Assert.assertTrue("cancellation should interrupt the worker",
                    toolInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldPropagateTypedMcpFailureToLedgerEvidenceAndToolCallEvent() {
        String exposedToolName = "mcp__search__remote_search";
        McpToolInfo toolInfo = McpToolInfo.builder()
                .mcpId("search")
                .name("remote_search")
                .exposedName(exposedToolName)
                .desc("search")
                .parameters("{}")
                .build();
        McpToolExecutor executor = Mockito.mock(McpToolExecutor.class);
        Mockito.when(executor.executeTool(Mockito.eq(toolInfo), Mockito.any()))
                .thenReturn(ToolResultPayload.failure(
                        "Toolremote_search Error.",
                        "Toolremote_search Error.",
                        null,
                        "permission denied"
                ));

        ToolCollection tools = new ToolCollection();
        tools.setMcpToolExecutor(executor);
        tools.addMcpTool(toolInfo);

        AgentExecutionRecorder recorder = Mockito.mock(AgentExecutionRecorder.class);
        Mockito.when(recorder.createToolInvocations(Mockito.any()))
                .thenReturn(Map.of("call-mcp-failed", 903L));
        AgentRunState runState = AgentRunState.builder().runId(701L).build();
        runState.bindCurrentLlmInvocationId(801L);
        Printer printer = Mockito.mock(Printer.class);
        AgentContext context = AgentContext.builder()
                .requestId("req-mcp-failed")
                .sessionId("session-mcp-failed")
                .executionRecorder(recorder)
                .agentRunState(runState)
                .printer(printer)
                .build();
        tools.setAgentContext(context);

        BaseAgent agent = new BaseAgent() {
            @Override
            public String step() {
                return "unused";
            }
        };
        agent.setName("test-agent");
        agent.setContext(context);
        agent.setPrinter(printer);
        agent.setAvailableTools(tools);

        String observation = agent.executeTool(toolCall("call-mcp-failed", exposedToolName));

        Assert.assertEquals("Toolremote_search Error.", observation);
        Assert.assertEquals(1, context.snapshotToolExecutionEvidence().size());
        Assert.assertFalse(context.snapshotToolExecutionEvidence().get(0).isSuccess());
        Assert.assertEquals("permission denied",
                context.snapshotToolExecutionEvidence().get(0).getErrorMessage());

        ArgumentCaptor<ToolInvocationFinishRecord> ledgerCaptor =
                ArgumentCaptor.forClass(ToolInvocationFinishRecord.class);
        Mockito.verify(recorder).finishToolInvocation(ledgerCaptor.capture());
        Assert.assertEquals(Integer.valueOf(ExecutionLedgerConstants.STATUS_FAILED),
                ledgerCaptor.getValue().getStatus());
        Assert.assertEquals("permission denied", ledgerCaptor.getValue().getErrorMsg());

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Boolean> finalCaptor = ArgumentCaptor.forClass(Boolean.class);
        Mockito.verify(printer, Mockito.times(2)).send(
                Mockito.eq("call-mcp-failed"),
                Mockito.eq("tool_call"),
                eventCaptor.capture(),
                finalCaptor.capture()
        );
        int finalIndex = finalCaptor.getAllValues().indexOf(Boolean.TRUE);
        Assert.assertTrue(finalIndex >= 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> finalEvent = (Map<String, Object>) eventCaptor.getAllValues().get(finalIndex);
        Assert.assertEquals("failed", finalEvent.get("status"));
        Assert.assertEquals("permission denied", finalEvent.get("errorMsg"));
    }

    private static ToolCall toolCall(String id) {
        return toolCall(id, "noop");
    }

    private static ToolCall toolCall(String id, String name) {
        return toolCall(id, name, "{}");
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return ToolCall.builder()
                .id(id)
                .function(ToolCall.Function.builder()
                        .name(name)
                        .arguments(arguments)
                        .build())
                .build();
    }
}
