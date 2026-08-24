package com.aigroup.paymall.config;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class PaySentinelDefaultRules {

    private static final Logger log = LoggerFactory.getLogger(PaySentinelDefaultRules.class);

    static final List<String> FEIGN_RESOURCES = List.of(
            "GET:http://member-service/internal/skus/by-goods/{goodsId}",
            "POST:http://group-service/api/v1/gbm/trade/lock_market_pay_order",
            "POST:http://group-service/api/v1/gbm/trade/query_market_pay_order",
            "POST:http://group-service/api/v1/gbm/trade/settlement_market_pay_order",
            "POST:http://group-service/api/v1/gbm/trade/refund_market_pay_order");

    @PostConstruct
    public void loadDefaults() {
        load();
    }

    public static void load() {
        List<DegradeRule> existing = DegradeRuleManager.getRules();
        if (existing != null && !existing.isEmpty()) {
            return;
        }
        List<DegradeRule> rules = new ArrayList<>();
        for (String resource : FEIGN_RESOURCES) {
            DegradeRule rule = new DegradeRule(resource);
            rule.setGrade(CircuitBreakerStrategy.ERROR_RATIO.getType());
            rule.setCount(0.5d);
            rule.setTimeWindow(10);
            rule.setMinRequestAmount(5);
            rule.setStatIntervalMs(10_000);
            rules.add(rule);
        }
        DegradeRuleManager.loadRules(rules);
        log.warn("sentinel.pay.feign.defaults-loaded");
    }
}
