package com.aigroup.paymall.config;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import feign.Capability;
import feign.InvocationHandlerFactory;
import feign.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * Wraps Feign invocations with Sentinel using {@code serviceId#methodName}.
 * Enabled with Spring Cloud Sentinel, independent of {@code feign.sentinel.enabled}
 * so hardcoded {@code url=} does not rewrite the resource name.
 */
@Component
@ConditionalOnProperty(name = "spring.cloud.sentinel.enabled", havingValue = "true")
public class SentinelStableNameCapability implements Capability {

    @Override
    public InvocationHandlerFactory enrich(InvocationHandlerFactory factory) {
        InvocationHandlerFactory delegateFactory = factory == null
                ? new InvocationHandlerFactory.Default()
                : factory;
        return (target, dispatch) -> {
            InvocationHandler delegate = delegateFactory.create(target, dispatch);
            return new StableNameHandler(target, delegate);
        };
    }

    static final class StableNameHandler implements InvocationHandler {
        private final Target<?> target;
        private final InvocationHandler delegate;

        StableNameHandler(Target<?> target, InvocationHandler delegate) {
            this.target = target;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return delegate.invoke(proxy, method, args);
            }
            String resource = PayFeignSentinelResources.resource(target.name(), method.getName());
            Entry entry = null;
            try {
                entry = SphU.entry(resource, EntryType.OUT);
                return delegate.invoke(proxy, method, args);
            } catch (BlockException blocked) {
                throw blocked;
            } catch (Throwable error) {
                Tracer.trace(error);
                throw error;
            } finally {
                if (entry != null) {
                    entry.exit();
                }
            }
        }
    }
}
