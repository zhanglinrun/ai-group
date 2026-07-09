package com.aigroup.groupbuy.api;

import com.aigroup.groupbuy.api.response.Response;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description DCC 动态配置中心
 * @create 2025-01-03 19:16
 */
public interface IDCCService {

    Response<Boolean> updateConfig(String key, String value);

}
