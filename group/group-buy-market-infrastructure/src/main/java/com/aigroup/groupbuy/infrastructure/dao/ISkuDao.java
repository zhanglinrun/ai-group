package com.aigroup.groupbuy.infrastructure.dao;

import com.aigroup.groupbuy.infrastructure.dao.po.Sku;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 商品查询
 * @create 2024-12-21 10:48
 */
@Mapper
public interface ISkuDao {

    Sku querySkuByGoodsId(String goodsId);

    /** 运营端：商品列表 */
    java.util.List<Sku> querySkuList();

    /** 运营端：更新商品名称/原价（拼团价 = 原价 - 折扣） */
    int updateSkuGoods(Sku skuReq);

    int insertSku(Sku skuReq);

}
