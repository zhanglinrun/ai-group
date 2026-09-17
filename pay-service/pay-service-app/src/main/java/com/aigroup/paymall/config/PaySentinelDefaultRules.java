package com.aigroup.paymall.config;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class PaySentinelDefaultRules {

    private static final Logger log = LoggerFactory.getLogger(PaySentinelDefaultRules.class);

    @PostConstruct
    public void loadDefaults() {
        load();
    }

    public static void load() {
        Map<String, FlowRule> flowByResource = new LinkedHashMap<>();
        List<FlowRule> existingFlow = FlowRuleManager.getRules();
        if (existingFlow != null) {
            for (FlowRule rule : existingFlow) {
                if (rule != null && rule.getResource() != null) {
                    flowByResource.put(rule.getResource(), rule);
                }
            }
        }
        for (FlowRule required : requiredFlowRules()) {
            flowByResource.putIfAbsent(required.getResource(), required);
        }
        FlowRuleManager.loadRules(new ArrayList<>(flowByResource.values()));
        if (existingFlow == null || existingFlow.isEmpty()) {
            log.warn("sentinel.pay.feign.flow.defaults-loaded");
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
            log.warn("sentinel.pay.feign.degrade.defaults-loaded");
        }
    }

    static List<FlowRule> requiredFlowRules() {
        return List.of(
                qps(PayFeignSentinelResources.LOCK, 30),
                qps(PayFeignSentinelResources.QUERY_LOCK, 40),
                qps(PayFeignSentinelResources.SETTLEMENT, 50),
                qps(PayFeignSentinelResources.REFUND, 20),
                qps(PayFeignSentinelResources.MEMBER_SKU, 80));
    }

    static List<DegradeRule> requiredDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();
        for (String resource : PayFeignSentinelResources.ALL) {
            rules.add(errorRatio(resource));
            rules.add(slowRatio(resource));
        }
        return rules;
    }

    static FlowRule qps(String resource, int count) {
        FlowRule rule = new FlowRule(resource);
        rule.setGrade(1);
        rule.setCount(count);
        return rule;
    }

    static DegradeRule errorRatio(String resource) {
        DegradeRule rule = new DegradeRule(resource);
        rule.setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType());
        rule.setCount(0.3d);
        rule.setTimeWindow(10);
        rule.setMinRequestAmount(5);
        rule.setStatIntervalMs(10_000);
        return rule;
    }

    static DegradeRule slowRatio(String resource) {
        DegradeRule rule = new DegradeRule(resource);
        rule.setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType());
        rule.setCount(800d);
        rule.setTimeWindow(10);
        rule.setMinRequestAmount(5);
        rule.setSlowRatioThreshold(0.5d);
        rule.setStatIntervalMs(10_000);
        return rule;
    }

    private static String degradeKey(DegradeRule rule) {
        return rule.getResource() + "|" + rule.getGrade();
    }
}
