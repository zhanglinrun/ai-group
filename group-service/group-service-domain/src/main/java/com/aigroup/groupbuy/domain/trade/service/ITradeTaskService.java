package com.aigroup.groupbuy.domain.trade.service;

import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;

import java.util.Map;

/**
 * 交易任务服务接口：扫描 `notify_task` 并通过 Kafka 投递成团通知。
 * 
 * 2025/7/12 21:15
 */
public interface ITradeTaskService {

    /**
     * 执行结算通知任务
     *
     * @return 结算数量
     * @throws Exception 异常
     */
    Map<String, Integer> execNotifyJob() throws Exception;

    /**
     * 执行结算通知任务
     *
     * @param notifyTaskEntity 通知任务对象
     * @return 结算数量
     * @throws Exception 异常
     */
    Map<String, Integer> execNotifyJob(NotifyTaskEntity notifyTaskEntity) throws Exception;

}
