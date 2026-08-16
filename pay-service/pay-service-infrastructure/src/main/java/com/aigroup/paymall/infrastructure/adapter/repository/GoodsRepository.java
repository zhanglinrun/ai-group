package com.aigroup.paymall.infrastructure.adapter.repository;

import com.aigroup.paymall.domain.goods.adapter.repository.IGoodsRepository;
import com.aigroup.paymall.infrastructure.dao.IOrderDao;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

/**
 * @description 缁撶畻浠撳偍鏈嶅姟
 * @create 2025-02-15 09:13
 */
@Repository
public class GoodsRepository implements IGoodsRepository {

    @Resource
    private IOrderDao orderDao;

    @Override
    public void changeOrderDealDone(String orderId) {
        orderDao.changeOrderDealDone(orderId);
    }

}
