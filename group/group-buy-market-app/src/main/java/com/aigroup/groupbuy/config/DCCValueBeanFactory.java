package com.aigroup.groupbuy.config;

import com.aigroup.groupbuy.infrastructure.dcc.DynamicConfigHolder;
import com.aigroup.groupbuy.types.annotations.DCCValue;
import com.aigroup.groupbuy.types.common.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 {@link DynamicConfigHolder} 实现的声明式动态配置注入。
 * <p>
 * 启动时扫描所有 Bean 中标注了 {@link DCCValue} 的字段，从 {@link DynamicConfigHolder}
 * 读取配置值（不存在则用注解中的默认值）并通过反射注入。运行时注册为
 * {@link DynamicConfigHolder} 的变更监听器，当配置通过 Redis topic 广播变更后，
 * 同步通过反射更新对应字段值，无需重启应用。
 */
@Slf4j
@Component
public class DCCValueBeanFactory implements BeanPostProcessor {

    /** key = DCC 配置键名, value = {bean 实例, 对应 Field} */
    private final Map<String, BeanFieldRef> dccFieldGroup = new HashMap<>();

    private final DynamicConfigHolder dynamicConfigHolder;

    public DCCValueBeanFactory(DynamicConfigHolder dynamicConfigHolder) {
        this.dynamicConfigHolder = dynamicConfigHolder;
        this.dynamicConfigHolder.addListener(this::onConfigChange);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // AOP 代理后需要获取目标类才能读取自定义注解
        Class<?> targetClass = bean.getClass();
        Object targetBean = bean;
        if (AopUtils.isAopProxy(bean)) {
            targetClass = AopUtils.getTargetClass(bean);
            targetBean = AopProxyUtils.getSingletonTarget(bean);
            if (targetBean == null) {
                targetBean = bean;
            }
        }

        Field[] fields = targetClass.getDeclaredFields();
        for (Field field : fields) {
            if (!field.isAnnotationPresent(DCCValue.class)) {
                continue;
            }

            DCCValue dccValue = field.getAnnotation(DCCValue.class);
            String config = dccValue.value();
            if (StringUtils.isBlank(config)) {
                throw new RuntimeException(field.getName() + " @DCCValue is not config value, " +
                        "expected format「key:defaultValue」e.g.「rateLimiterSwitch:open」");
            }

            String[] splits = config.split(Constants.COLON);
            String key = splits[0].trim();
            String defaultValue = splits.length == 2 ? splits[1] : null;

            if (StringUtils.isBlank(defaultValue)) {
                throw new RuntimeException("dcc config error: " + key + " must have a default value");
            }

            String setValue = dynamicConfigHolder.get(key, defaultValue);

            try {
                field.setAccessible(true);
                field.set(targetBean, setValue);
                field.setAccessible(false);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("DCC inject failed for field: " + field.getName(), e);
            }

            dccFieldGroup.put(key, new BeanFieldRef(targetBean, field));
            log.info("DCC 注入配置 key:{} value:{}", key, setValue);
        }

        return bean;
    }

    private void onConfigChange(String key, String value) {
        BeanFieldRef ref = dccFieldGroup.get(key);
        if (ref == null) return;

        try {
            ref.field.setAccessible(true);
            ref.field.set(ref.bean, value);
            ref.field.setAccessible(false);
            log.info("DCC 动态更新 key:{} value:{}", key, value);
        } catch (IllegalAccessException e) {
            log.error("DCC 动态更新失败 key:{}", key, e);
        }
    }

    private record BeanFieldRef(Object bean, Field field) {}
}
