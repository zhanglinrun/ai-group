package org.wwz.ai.test.domain;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.wwz.ai.application.agent.execute.planexecute.PlanSolveAgentExecuteStrategy;
import org.wwz.ai.domain.agent.reactor.config.ReactorConfig;
import org.wwz.ai.domain.agent.reactor.model.req.AgentRequest;

public class PlanSolveOutputStylePolicyTest {

    @Test
    public void shouldRespectChineseNoExternalToolsDirective() {
        AgentRequest request = request("不要调用外部工具。请用三点解释 checkpoint。", "html");

        applyOutputStyle(request);

        Assert.assertEquals("不要调用外部工具。请用三点解释 checkpoint。", request.getQuery());
    }

    @Test
    public void shouldRespectEnglishNoToolsDirective() {
        AgentRequest request = request("No tools. Explain checkpoint in three bullets.", "html");

        applyOutputStyle(request);

        Assert.assertEquals("No tools. Explain checkpoint in three bullets.", request.getQuery());
    }

    @Test
    public void shouldAppendConfiguredOutputInstructionWhenToolsAreAllowed() {
        AgentRequest request = request("生成 checkpoint 演示报告", "html");

        applyOutputStyle(request);

        Assert.assertEquals("生成 checkpoint 演示报告\n[FORMAT] call report_tool", request.getQuery());
    }

    private void applyOutputStyle(AgentRequest request) {
        ReactorConfig config = new ReactorConfig();
        config.setOutputStylePrompts("{\"html\":\"\\n[FORMAT] call report_tool\"}");
        PlanSolveAgentExecuteStrategy strategy = new PlanSolveAgentExecuteStrategy();
        ReflectionTestUtils.setField(strategy, "reactorConfig", config);
        ReflectionTestUtils.invokeMethod(strategy, "applyOutputStyle", request);
    }

    private AgentRequest request(String query, String outputStyle) {
        return AgentRequest.builder()
                .requestId("request-output-style")
                .query(query)
                .originalQuery(query)
                .outputStyle(outputStyle)
                .build();
    }
}
