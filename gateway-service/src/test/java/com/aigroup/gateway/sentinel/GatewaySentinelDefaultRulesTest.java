package com.aigroup.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewaySentinelDefaultRulesTest {

    @AfterEach
    void clearRules() {
        GatewayRuleManager.loadRules(Set.of());
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void javaRoutesHaveQpsAndDegradeWhileSseIsNotDegraded() {
        GatewayRuleManager.loadRules(Set.of());
        DegradeRuleManager.loadRules(List.of());
        GatewaySentinelDefaultRules.load();

        assertTrue(hasFlow("agent-json"));
        assertTrue(hasFlow("agent-sse"));
        assertTrue(hasFlow("auth"));
        assertTrue(hasFlow("member"));
        assertTrue(hasFlow("group"));
        assertTrue(hasFlow("pay"));
        assertTrue(hasFlow("pay-v1"));

        assertTrue(hasDegrade("agent-json", CircuitBreakerStrategy.ERROR_RATIO));
        assertTrue(hasDegrade("group", CircuitBreakerStrategy.ERROR_RATIO));
        assertTrue(hasDegrade("group", CircuitBreakerStrategy.SLOW_REQUEST_RATIO));
        assertTrue(hasDegrade("pay", CircuitBreakerStrategy.SLOW_REQUEST_RATIO));
        assertFalse(hasDegrade("agent-json", CircuitBreakerStrategy.SLOW_REQUEST_RATIO));
        assertFalse(DegradeRuleManager.getRules().stream().anyMatch(rule -> "agent-sse".equals(rule.getResource())));
    }

    @Test
    void partialNacosFlowDoesNotDropJavaRouteQps() {
        GatewayRuleManager.loadRules(Set.of(GatewaySentinelDefaultRules.routeFlow("agent-json", 50)));
        DegradeRuleManager.loadRules(List.of());
        GatewaySentinelDefaultRules.load();

        assertTrue(hasFlow("agent-json"));
        assertTrue(hasFlow("group"));
        assertTrue(hasFlow("pay"));
    }

    private static boolean hasFlow(String resource) {
        return GatewayRuleManager.getRules().stream().anyMatch(rule -> resource.equals(rule.getResource()));
    }

    private static boolean hasDegrade(String resource, CircuitBreakerStrategy strategy) {
        return DegradeRuleManager.getRules().stream().anyMatch(rule ->
                resource.equals(rule.getResource()) && rule.getGrade() == strategy.getType());
    }
}
