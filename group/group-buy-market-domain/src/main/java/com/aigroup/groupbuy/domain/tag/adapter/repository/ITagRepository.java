package com.aigroup.groupbuy.domain.tag.adapter.repository;

import com.aigroup.groupbuy.domain.tag.model.entity.CrowdTagsJobEntity;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 人群标签仓储接口
 * @create 2024-12-28 11:26
 */
public interface ITagRepository {

    CrowdTagsJobEntity queryCrowdTagsJobEntity(String tagId, String batchId);

    void addCrowdTagsUserId(String tagId, String userId);

    void updateCrowdTagsStatistics(String tagId, int count);

}
