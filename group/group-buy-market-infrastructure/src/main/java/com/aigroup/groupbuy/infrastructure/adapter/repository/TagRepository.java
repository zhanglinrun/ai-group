package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.domain.tag.adapter.repository.ITagRepository;
import com.aigroup.groupbuy.domain.tag.model.entity.CrowdTagsJobEntity;
import com.aigroup.groupbuy.infrastructure.dao.ICrowdTagsDao;
import com.aigroup.groupbuy.infrastructure.dao.ICrowdTagsDetailDao;
import com.aigroup.groupbuy.infrastructure.dao.ICrowdTagsJobDao;
import com.aigroup.groupbuy.infrastructure.dao.po.CrowdTags;
import com.aigroup.groupbuy.infrastructure.dao.po.CrowdTagsDetail;
import com.aigroup.groupbuy.infrastructure.dao.po.CrowdTagsJob;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import org.redisson.api.RBitSet;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 浜虹兢鏍囩浠撳偍
 * @create 2024-12-28 13:12
 */
@Repository
public class TagRepository implements ITagRepository {

    @Resource
    private ICrowdTagsDao crowdTagsDao;
    @Resource
    private ICrowdTagsDetailDao crowdTagsDetailDao;
    @Resource
    private ICrowdTagsJobDao crowdTagsJobDao;

    @Resource
    private IRedisService redisService;

    @Override
    public CrowdTagsJobEntity queryCrowdTagsJobEntity(String tagId, String batchId) {
        CrowdTagsJob crowdTagsJobReq = new CrowdTagsJob();
        crowdTagsJobReq.setTagId(tagId);
        crowdTagsJobReq.setBatchId(batchId);

        CrowdTagsJob crowdTagsJobRes = crowdTagsJobDao.queryCrowdTagsJob(crowdTagsJobReq);
        if (null == crowdTagsJobRes) return null;

        return CrowdTagsJobEntity.builder()
                .tagType(crowdTagsJobRes.getTagType())
                .tagRule(crowdTagsJobRes.getTagRule())
                .statStartTime(crowdTagsJobRes.getStatStartTime())
                .statEndTime(crowdTagsJobRes.getStatEndTime())
                .build();
    }

    @Override
    public void addCrowdTagsUserId(String tagId, String userId) {
        CrowdTagsDetail crowdTagsDetailReq = new CrowdTagsDetail();
        crowdTagsDetailReq.setTagId(tagId);
        crowdTagsDetailReq.setUserId(userId);

        try {
            crowdTagsDetailDao.addCrowdTagsUserId(crowdTagsDetailReq);
        } catch (DuplicateKeyException ignore) {
            // 蹇界暐鍞竴绱㈠紩鍐茬獊
        }

        // 鑾峰彇BitSet
        RBitSet bitSet = redisService.getBitSet(tagId);
        bitSet.set(redisService.getIndexFromUserId(userId), true);
    }

    @Override
    public void updateCrowdTagsStatistics(String tagId, int count) {
        CrowdTags crowdTagsReq = new CrowdTags();
        crowdTagsReq.setTagId(tagId);
        crowdTagsReq.setStatistics(count);

        crowdTagsDao.updateCrowdTagsStatistics(crowdTagsReq);
    }

}
