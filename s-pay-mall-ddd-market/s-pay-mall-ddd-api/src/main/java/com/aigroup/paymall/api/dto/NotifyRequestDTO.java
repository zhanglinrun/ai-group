package com.aigroup.paymall.api.dto;

import lombok.Data;

import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 回调请求对象
 * @create 2025-01-31 10:08
 */
@Data
public class NotifyRequestDTO {

    /** 组队ID */
    private String teamId;
    /** 外部单号 */
    private List<String> outTradeNoList;

}
