package com.aigroup.groupbuy.infrastructure.dao;

import com.aigroup.groupbuy.infrastructure.dao.po.SCSkuActivity;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description 渠道商品活动配置关联表Dao
 * @create 2025-01-01 09:30
 */
@Mapper
public interface ISCSkuActivityDao {

    SCSkuActivity querySCSkuActivityBySCGoodsId(SCSkuActivity scSkuActivity);

    /** 运营端：全量渠道商品-活动映射（活动列表联查商品用） */
    java.util.List<SCSkuActivity> querySCSkuActivityList();

    int insertSCSkuActivity(SCSkuActivity mapping);

}
