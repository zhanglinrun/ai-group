package com.aigroup.groupbuy.infrastructure.rate.limiter;

import com.aigroup.common.context.RequestUserContext;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import com.aigroup.groupbuy.infrastructure.redis.RedisRateLimitScript;
import com.aigroup.groupbuy.types.annotations.DCCValue;
import com.aigroup.groupbuy.types.annotations.RateLimiterAccessInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Method-level limiter backed by Redis so every Group instance shares one bucket.
 * {@code key=userId} prefers the Gateway JWT binding over a request-body field.
 */
@Aspect
public class RateLimiterAOP {

    private static final long WINDOW_MS = 1000L;
    private static final long BLACKLIST_TTL_SECONDS = TimeUnit.HOURS.toSeconds(24);

    private final Logger log = LoggerFactory.getLogger(RateLimiterAOP.class);
    private final IRedisService redisService;

    @DCCValue("rateLimiterSwitch:open")
    private String rateLimiterSwitch;

    public RateLimiterAOP(IRedisService redisService) {
        this.redisService = redisService;
    }

    @Pointcut("@annotation(com.aigroup.groupbuy.types.annotations.RateLimiterAccessInterceptor)")
    public void aopPoint() {
    }

    @Around("aopPoint() && @annotation(rateLimiterAccessInterceptor)")
    public Object doRouter(ProceedingJoinPoint jp, RateLimiterAccessInterceptor rateLimiterAccessInterceptor) throws Throwable {
        if (StringUtils.isBlank(rateLimiterSwitch) || "close".equals(rateLimiterSwitch)) {
            return jp.proceed();
        }

        String key = rateLimiterAccessInterceptor.key();
        if (StringUtils.isBlank(key)) {
            throw new RuntimeException("annotation RateLimiter key is null!");
        }

        String keyAttr = resolveKey(key, jp.getArgs());
        log.info("aop attr {}", keyAttr);

        try {
            if (!"all".equals(keyAttr)
                    && rateLimiterAccessInterceptor.blacklistCount() != 0
                    && RedisRateLimitScript.blacklistCount(redisService, blacklistKey(keyAttr))
                    > rateLimiterAccessInterceptor.blacklistCount()) {
                log.info("限流-黑名单拦截(24h)：{}", keyAttr);
                return fallbackMethodResult(jp, rateLimiterAccessInterceptor.fallbackMethod());
            }

            int limit = Math.max(1, (int) Math.ceil(rateLimiterAccessInterceptor.permitsPerSecond()));
            if (!RedisRateLimitScript.tryAcquire(redisService, windowKey(keyAttr), limit, WINDOW_MS)) {
                if (rateLimiterAccessInterceptor.blacklistCount() != 0 && !"all".equals(keyAttr)) {
                    RedisRateLimitScript.bumpBlacklist(redisService, blacklistKey(keyAttr), BLACKLIST_TTL_SECONDS);
                }
                log.info("限流-超频次拦截：{}", keyAttr);
                return fallbackMethodResult(jp, rateLimiterAccessInterceptor.fallbackMethod());
            }
        } catch (RuntimeException ex) {
            log.error("rate limiter redis failed, fail-open key={}", keyAttr, ex);
            return jp.proceed();
        }

        return jp.proceed();
    }

    String resolveKey(String attr, Object[] args) {
        if ("userId".equals(attr)) {
            Long bound = RequestUserContext.getUserId();
            if (bound != null) {
                return String.valueOf(bound);
            }
        }
        return getAttrValue(attr, args);
    }

    private String windowKey(String keyAttr) {
        return "group:rate:" + keyAttr;
    }

    private String blacklistKey(String keyAttr) {
        return "group:rate:blacklist:" + keyAttr;
    }

    private Object fallbackMethodResult(JoinPoint jp, String fallbackMethod) throws NoSuchMethodException,
            InvocationTargetException, IllegalAccessException {
        Signature sig = jp.getSignature();
        MethodSignature methodSignature = (MethodSignature) sig;
        Method method = jp.getTarget().getClass().getMethod(fallbackMethod, methodSignature.getParameterTypes());
        return method.invoke(jp.getThis(), jp.getArgs());
    }

    public String getAttrValue(String attr, Object[] args) {
        if (args[0] instanceof String) {
            return args[0].toString();
        }
        String filedValue = null;
        for (Object arg : args) {
            try {
                if (StringUtils.isNotBlank(filedValue)) {
                    break;
                }
                filedValue = String.valueOf(this.getValueByName(arg, attr));
            } catch (Exception e) {
                log.error("获取路由属性值失败 attr：{}", attr, e);
            }
        }
        return filedValue;
    }

    private Object getValueByName(Object item, String name) {
        try {
            Field field = getFieldByName(item, name);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object o = field.get(item);
            field.setAccessible(false);
            return o;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private Field getFieldByName(Object item, String name) {
        try {
            Field field;
            try {
                field = item.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                field = item.getClass().getSuperclass().getDeclaredField(name);
            }
            return field;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
