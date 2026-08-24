package com.aigroup.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
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
    void jsonHasFlowAndDegradeWhileSseIsNotDegraded() {
        GatewayRuleManager.loadRules(Set.of());
        DegradeRuleManager.loadRules(List.of());
        GatewaySentinelDefaultRules.load();

        assertTrue(GatewayRuleManager.getRules().stream().anyMatch(rule -> "agent-json".equals(rule.getResource())));
        assertTrue(GatewayRuleManager.getRules().stream().anyMatch(rule -> "agent-sse".equals(rule.getResource())));
        assertTrue(DegradeRuleManager.getRules().stream().anyMatch(rule -> "agent-json".equals(rule.getResource())));
        assertTrue(DegradeRuleManager.getRules().stream().anyMatch(rule -> "group".equals(rule.getResource())));
        assertFalse(DegradeRuleManager.getRules().stream().anyMatch(rule -> "agent-sse".equals(rule.getResource())));
    }
}
