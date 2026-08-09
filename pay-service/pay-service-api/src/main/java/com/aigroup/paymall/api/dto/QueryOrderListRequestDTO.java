package com.aigroup.paymall.api.dto;

import lombok.Data;

@Data
public class QueryOrderListRequestDTO {

    /** 用户ID */
    private String userId;
    /** 倒序 keyset 游标：查询 id 小于此值的更早记录；首屏为空 */
    private Long lastId;
    /** 每页数量 */
    private Integer pageSize = 10;

}
