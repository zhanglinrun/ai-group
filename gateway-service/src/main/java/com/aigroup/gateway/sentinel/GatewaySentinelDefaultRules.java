package com.aigroup.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conservative in-memory rules used when Nacos Sentinel data-ids are empty.
 * Agent SSE is flow-limited loosely and never degraded.
 */
@Component
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class GatewaySentinelDefaultRules {

    private static final Logger log = LoggerFactory.getLogger(GatewaySentinelDefaultRules.class);

    static final List<String> DEGRADE_ROUTE_IDS = List.of(
            "agent-json", "auth", "member", "pay", "pay-v1", "group");

    @PostConstruct
    public void loadDefaults() {
        load();
    }

    public static void load() {
        Set<GatewayFlowRule> flowRules = GatewayRuleManager.getRules();
        if (flowRules == null || flowRules.isEmpty()) {
            List<GatewayFlowRule> defaults = new ArrayList<>();
            defaults.add(routeFlow("agent-json", 50));
            defaults.add(routeFlow("agent-sse", 2000));
            GatewayRuleManager.loadRules(new HashSet<>(defaults));
            log.warn("sentinel.gateway.flow.defaults-loaded");
        }
        List<DegradeRule> degradeRules = DegradeRuleManager.getRules();
        if (degradeRules == null || degradeRules.isEmpty()) {
            List<DegradeRule> defaults = new ArrayList<>();
            for (String routeId : DEGRADE_ROUTE_IDS) {
                defaults.add(routeDegrade(routeId));
            }
            DegradeRuleManager.loadRules(defaults);
            log.warn("sentinel.gateway.degrade.defaults-loaded");
        }
    }

    static GatewayFlowRule routeFlow(String routeId, int qps) {
        GatewayFlowRule rule = new GatewayFlowRule();
        rule.setResource(routeId);
        rule.setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID);
        rule.setGrade(1);
        rule.setCount(qps);
        rule.setIntervalSec(1);
        return rule;
    }

    static DegradeRule routeDegrade(String routeId) {
        DegradeRule rule = new DegradeRule(routeId);
        rule.setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType());
        rule.setCount(0.5d);
        rule.setTimeWindow(10);
        rule.setMinRequestAmount(10);
        rule.setStatIntervalMs(10_000);
        return rule;
    }
}
