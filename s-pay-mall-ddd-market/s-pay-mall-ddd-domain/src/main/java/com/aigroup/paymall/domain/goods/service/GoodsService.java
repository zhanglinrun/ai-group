package com.aigroup.paymall.domain.goods.service;

import com.aigroup.paymall.domain.goods.adapter.repository.IGoodsRepository;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 缁撶畻鏈嶅姟
 * @create 2025-02-15 09:11
 */
@Service
public class GoodsService implements IGoodsService {

    @Resource
    private IGoodsRepository repository;


    @Override
    public void changeOrderDealDone(String orderId) {
        repository.changeOrderDealDone(orderId);
    }

}
