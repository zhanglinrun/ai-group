package com.linrun.agent.types.design.tree;

/**
 * 多线程策略路由器抽象基类：策略树节点的模板方法实现。
 *
 * <p>采用模板方法 + 责任链模式：{@link #apply(Object, Object)} 作为模板入口，先调用
 * {@link #multiThread(Object, Object)} 完成节点所需的数据装载（钩子，缺省空实现），再调用
 * {@link #doApply(Object, Object)} 执行节点核心逻辑；节点在 {@code doApply} 末尾调用
 * {@link #router(Object, Object)} 委托给 {@link #get(Object, Object)} 返回的下一跳节点，
 * 从而串起整条装配链。
 *
 * <p>链路终点由 {@link #defaultStrategyHandler} 表示：其 {@code apply} 返回 {@link #defaultR}，
 * 子类可覆写 {@link #defaultR} 自定义终点返回值。
 *
 * @param <T> 请求参数类型
 * @param <D> 动态上下文类型
 * @param <R> 返回值类型
 */
public abstract class AbstractMultiThreadStrategyRouter<T, D, R> implements StrategyHandler<T, D, R> {

    /**
     * 终端策略处理器：链路终点占位，{@code apply} 直接返回 {@link #defaultR}。
     * 子类可在 {@link #get(Object, Object)} 中返回该字段以表示"无下一跳"。
     */
    protected final StrategyHandler<T, D, R> defaultStrategyHandler =
            (requestParameter, dynamicContext) -> defaultR(requestParameter, dynamicContext);

    /**
     * 模板入口：先执行数据装载钩子，再执行节点核心逻辑。
     */
    @Override
    public R apply(T requestParameter, D dynamicContext) throws Exception {
        multiThread(requestParameter, dynamicContext);
        return doApply(requestParameter, dynamicContext);
    }

    /**
     * 节点核心逻辑，由子类实现。
     */
    protected abstract R doApply(T requestParameter, D dynamicContext) throws Exception;

    /**
     * 数据装载钩子：在 {@link #doApply} 之前同步执行，用于把节点所需数据写入动态上下文。
     * 缺省为空操作，子类按需覆写。
     */
    protected void multiThread(T requestParameter, D dynamicContext) throws Exception {
        // 缺省空实现
    }

    /**
     * 委托给下一跳节点：取 {@link #get(Object, Object)} 返回的节点并调用其 {@code apply}；
     * 若无下一跳则返回 {@link #defaultR}。
     */
    protected R router(T requestParameter, D dynamicContext) throws Exception {
        StrategyHandler<T, D, R> nextStrategyHandler = get(requestParameter, dynamicContext);
        if (null == nextStrategyHandler) {
            return defaultR(requestParameter, dynamicContext);
        }
        return nextStrategyHandler.apply(requestParameter, dynamicContext);
    }

    /**
     * 下一跳解析：缺省返回 {@link #defaultStrategyHandler}（即终点）。
     * 子类覆写以接入具体下一节点。
     */
    @Override
    public StrategyHandler<T, D, R> get(T requestParameter, D dynamicContext) throws Exception {
        return defaultStrategyHandler;
    }

    /**
     * 链路终点返回值，缺省 {@code null}，子类可覆写。
     */
    protected R defaultR(T requestParameter, D dynamicContext) throws Exception {
        return null;
    }
}
