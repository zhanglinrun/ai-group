package com.aigroup.paymall.test.config;

import com.aigroup.paymall.config.PayFeignSentinelResources;
import com.aigroup.paymall.config.PaySentinelDefaultRules;
import com.aigroup.paymall.config.SentinelStableNameCapability;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import feign.InvocationHandlerFactory;
import feign.Target;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SentinelStableNameCapabilityTest {

    public interface GroupApi {
        String lockMarketPayOrder();
    }

    @After
    public void clearRules() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    public void blocksOnStableServiceMethodNameIndependentOfUrl() throws Throwable {
        FlowRule tight = new FlowRule(PayFeignSentinelResources.LOCK);
        tight.setGrade(1);
        tight.setCount(1);
        FlowRuleManager.loadRules(List.of(tight));
        DegradeRuleManager.loadRules(List.of());

        AtomicInteger calls = new AtomicInteger();
        Target<GroupApi> target = new Target.HardCodedTarget<>(
                GroupApi.class, "group-service", "http://127.0.0.1:8091");
        InvocationHandlerFactory inner = (ignoredTarget, ignoredDispatch) ->
                (proxy, method, args) -> {
                    calls.incrementAndGet();
                    return "ok";
                };
        InvocationHandlerFactory factory = new SentinelStableNameCapability().enrich(inner);
        InvocationHandler invocation = factory.create(target, Map.of());
        Method method = GroupApi.class.getMethod("lockMarketPayOrder");

        assertEquals("ok", invocation.invoke(null, method, new Object[0]));
        try {
            invocation.invoke(null, method, new Object[0]);
            fail("second call should be blocked");
        } catch (BlockException blocked) {
            assertEquals(PayFeignSentinelResources.LOCK, blocked.getRule().getResource());
        }
        assertEquals(1, calls.get());
    }

    @Test
    public void defaultRulesCoverCapabilityResourceNames() {
        PaySentinelDefaultRules.load();
        assertTrue(FlowRuleManager.getRules().stream().anyMatch(
                rule -> PayFeignSentinelResources.LOCK.equals(rule.getResource())));
    }
}
