package com.aigroup.groupbuy.domain.tag.service;

import com.aigroup.groupbuy.domain.tag.adapter.repository.ITagRepository;
import com.aigroup.groupbuy.domain.tag.model.entity.CrowdTagsJobEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 人群标签服务
 * @create 2024-12-28 12:51
 */
@Slf4j
@Service
public class TagService implements ITagService {

    @Resource
    private ITagRepository repository;

    @Override
    public void execTagBatchJob(String tagId, String batchId) {
        log.info("人群标签批次任务 tagId:{} batchId:{}", tagId, batchId);

        // 1. 查询批次任务
        CrowdTagsJobEntity crowdTagsJobEntity = repository.queryCrowdTagsJobEntity(tagId, batchId);

        // 2. 用户标签数据应由真实的数据源/运营任务提供；新项目不写入历史示例用户。
        List<String> userIdList = List.of();
        if (userIdList.isEmpty()) {
            log.info("当前未配置人群标签数据源，跳过标签用户写入 tagId:{} batchId:{}", tagId, batchId);
            return;
        }

        // 3. 一般人群标签由数据源批量写入，避免在业务代码中硬编码用户。
        for (String userId : userIdList) {
            repository.addCrowdTagsUserId(tagId, userId);
        }

        // 5. 更新人群标签统计量
        repository.updateCrowdTagsStatistics(tagId, userIdList.size());
    }

}
