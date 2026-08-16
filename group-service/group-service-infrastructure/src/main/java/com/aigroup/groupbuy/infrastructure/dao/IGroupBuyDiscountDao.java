package com.aigroup.groupbuy.infrastructure.dao;

import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyDiscount;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description 折扣配置Dao
 * @create 2024-12-07 10:10
 */
@Mapper
public interface IGroupBuyDiscountDao {

    List<GroupBuyDiscount> queryGroupBuyDiscountList();

    GroupBuyDiscount queryGroupBuyActivityDiscountByDiscountId(String discountId);

    /** 运营端：更新折扣表达式（如 ZJ 直减金额），直接决定拼团价 */
    int updateGroupBuyDiscountExpr(GroupBuyDiscount groupBuyDiscountReq);

    int insertGroupBuyDiscount(GroupBuyDiscount groupBuyDiscountReq);

}
