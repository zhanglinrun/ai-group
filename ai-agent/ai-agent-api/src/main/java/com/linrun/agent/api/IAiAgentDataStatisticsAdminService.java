package com.linrun.agent.api;

import com.linrun.agent.api.dto.DataStatisticsResponseDTO;
import com.linrun.agent.api.response.Response;


public interface IAiAgentDataStatisticsAdminService {

    /**
     * 获取系统数据统计
     * @return 统计数据响应
     */
    Response<DataStatisticsResponseDTO> getDataStatistics();
}
