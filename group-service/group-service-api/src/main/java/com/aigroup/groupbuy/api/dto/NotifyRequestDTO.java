package com.aigroup.groupbuy.api.dto;

import lombok.Data;

import java.util.List;

/**
 * @description 回调请求对象
 * @create 2025-01-31 10:08
 */
@Data
public class NotifyRequestDTO {

    /** 组队ID */
    private String teamId;
    private Long activityId;
    private List<Member> members;

    @Data
    public static class Member {
        private String userId;
        private String source;
        private String channel;
        private String outTradeNo;
    }

}
