package com.linrun.agent.types.design.tree;

/**
 * 策略处理器接口：装配链 / 责任链节点的统一抽象。
 *
 * <p>每个节点通过 {@link #apply(Object, Object)} 执行自身逻辑，并通过 {@link #get(Object, Object)}
 * 返回下一跳节点，从而组成一棵策略树。{@link AbstractMultiThreadStrategyRouter} 提供了
 * 模板方法实现，业务节点只需继承并覆写 {@code doApply} 即可。
 *
 * @param <T> 请求参数类型
 * @param <D> 动态上下文类型（在节点间透传）
 * @param <R> 返回值类型
 */
@FunctionalInterface
public interface StrategyHandler<T, D, R> {

    /**
     * 执行当前节点的策略逻辑。
     *
     * @param requestParameter 请求参数
     * @param dynamicContext   动态上下文
     * @return 处理结果
     * @throws Exception 执行过程中允许抛出业务异常
     */
    R apply(T requestParameter, D dynamicContext) throws Exception;

    /**
     * 返回下一跳策略节点；返回 {@code null} 表示到达链路终点。
     *
     * <p>默认实现返回 {@code null}，由 {@link AbstractMultiThreadStrategyRouter} 覆写为
     * 终端处理器，业务节点可按需覆写以接入下一节点。
     *
     * @param requestParameter 请求参数
     * @param dynamicContext   动态上下文
     * @return 下一跳节点，{@code null} 表示终点
     * @throws Exception 解析下一跳时允许抛出异常
     */
    default StrategyHandler<T, D, R> get(T requestParameter, D dynamicContext) throws Exception {
        return null;
    }
}
