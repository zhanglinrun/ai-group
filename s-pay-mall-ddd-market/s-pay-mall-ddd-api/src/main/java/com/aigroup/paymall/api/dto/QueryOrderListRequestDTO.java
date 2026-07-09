package com.aigroup.paymall.api.dto;

import lombok.Data;

@Data
public class QueryOrderListRequestDTO {

    /** 用户ID */
    private String userId;
    /** 分页参数：大于此ID的记录 */
    private Long lastId;
    /** 每页数量 */
    private Integer pageSize = 10;

}