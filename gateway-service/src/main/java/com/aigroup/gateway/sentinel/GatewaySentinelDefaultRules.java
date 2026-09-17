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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative in-memory rules used when Nacos Sentinel data-ids are empty,
 * and to fill route ids that a partial Nacos payload omitted.
 * <p>
 * Agent SSE is flow-limited loosely and never degraded. Agent JSON is QPS +
 * error-ratio only — it can legitimately run for 45s, so RT/slow-ratio
 * circuit breaking would false-open. Java commerce routes get QPS plus both
 * error-ratio and slow-ratio degrade.
 */
@Component
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class GatewaySentinelDefaultRules {

    private static final Logger log = LoggerFactory.getLogger(GatewaySentinelDefaultRules.class);

    static final List<String> DEGRADE_ROUTE_IDS = List.of(
            "agent-json", "auth", "member", "pay", "pay-v1", "group");

    static final List<String> SLOW_DEGRADE_ROUTE_IDS = List.of(
            "auth", "member", "pay", "pay-v1", "group");

    @PostConstruct
    public void loadDefaults() {
        load();
    }

    public static void load() {
        Map<String, GatewayFlowRule> flowByResource = new LinkedHashMap<>();
        Set<GatewayFlowRule> existingFlow = GatewayRuleManager.getRules();
        if (existingFlow != null) {
            for (GatewayFlowRule rule : existingFlow) {
                if (rule != null && rule.getResource() != null) {
                    flowByResource.put(rule.getResource(), rule);
                }
            }
        }
        for (GatewayFlowRule required : requiredFlowRules()) {
            flowByResource.putIfAbsent(required.getResource(), required);
        }
        GatewayRuleManager.loadRules(Set.copyOf(flowByResource.values()));
        if (existingFlow == null || existingFlow.isEmpty()) {
            log.warn("sentinel.gateway.flow.defaults-loaded");
        }

        Map<String, DegradeRule> degradeByKey = new LinkedHashMap<>();
        List<DegradeRule> existingDegrade = DegradeRuleManager.getRules();
        if (existingDegrade != null) {
            for (DegradeRule rule : existingDegrade) {
                if (rule != null && rule.getResource() != null) {
                    degradeByKey.put(degradeKey(rule), rule);
                }
            }
        }
        for (DegradeRule required : requiredDegradeRules()) {
            degradeByKey.putIfAbsent(degradeKey(required), required);
        }
        DegradeRuleManager.loadRules(new ArrayList<>(degradeByKey.values()));
        if (existingDegrade == null || existingDegrade.isEmpty()) {
            log.warn("sentinel.gateway.degrade.defaults-loaded");
        }
    }

    static List<GatewayFlowRule> requiredFlowRules() {
        return List.of(
                routeFlow("agent-json", 50),
                routeFlow("agent-sse", 2000),
                routeFlow("auth", 80),
                routeFlow("member", 80),
                routeFlow("group", 80),
                routeFlow("pay", 40),
                routeFlow("pay-v1", 20));
    }

    static List<DegradeRule> requiredDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();
        for (String routeId : DEGRADE_ROUTE_IDS) {
            rules.add(errorRatioDegrade(routeId));
        }
        for (String routeId : SLOW_DEGRADE_ROUTE_IDS) {
            rules.add(slowRatioDegrade(routeId));
        }
        return rules;
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

    static DegradeRule errorRatioDegrade(String routeId) {
        DegradeRule rule = new DegradeRule(routeId);
        rule.setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType());
        rule.setCount(0.3d);
        rule.setTimeWindow(10);
        rule.setMinRequestAmount(10);
        rule.setStatIntervalMs(10_000);
        return rule;
    }

    static DegradeRule slowRatioDegrade(String routeId) {
        DegradeRule rule = new DegradeRule(routeId);
        rule.setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType());
        rule.setCount(1000d);
        rule.setTimeWindow(10);
        rule.setMinRequestAmount(10);
        rule.setSlowRatioThreshold(0.5d);
        rule.setStatIntervalMs(10_000);
        return rule;
    }

    private static String degradeKey(DegradeRule rule) {
        return rule.getResource() + "|" + rule.getGrade();
    }
}
