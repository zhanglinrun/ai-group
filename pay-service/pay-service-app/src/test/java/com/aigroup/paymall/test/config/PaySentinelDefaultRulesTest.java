package com.aigroup.paymall.test.config;

import com.aigroup.paymall.config.PayFeignSentinelResources;
import com.aigroup.paymall.config.PaySentinelDefaultRules;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PaySentinelDefaultRulesTest {

    @After
    public void clearRules() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    public void stableNamesGetQpsAndErrorPlusSlowDegrade() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
        PaySentinelDefaultRules.load();

        for (String resource : PayFeignSentinelResources.ALL) {
            assertTrue(resource, hasFlow(resource));
            assertTrue(resource, hasDegrade(resource, CircuitBreakerStrategy.ERROR_RATIO));
            assertTrue(resource, hasDegrade(resource, CircuitBreakerStrategy.SLOW_REQUEST_RATIO));
        }
        assertEquals("group-service#lockMarketPayOrder", PayFeignSentinelResources.LOCK);
    }

    private static boolean hasFlow(String resource) {
        return FlowRuleManager.getRules().stream().anyMatch(rule -> resource.equals(rule.getResource()));
    }

    private static boolean hasDegrade(String resource, CircuitBreakerStrategy strategy) {
        return DegradeRuleManager.getRules().stream().anyMatch(rule ->
                resource.equals(rule.getResource()) && rule.getGrade() == strategy.getType());
    }
}
