package com.aigroup.groupbuy.test.domain.tag;

import com.aigroup.groupbuy.domain.tag.service.TagService;
import com.aigroup.groupbuy.infrastructure.redis.IRedisService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RBitSet;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @灏忓倕鍝?
 * @description 浜虹兢鏍囩鏈嶅姟娴嬭瘯
 * @create 2024-12-28 14:33
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ITagServiceTest {

    @Resource
    private TagService tagService;
    @Resource
    private IRedisService redisService;

    @Test
    public void test_tag_job() {
        tagService.execTagBatchJob("RQ_KJHKL98UU78H66554GFDV", "10001");
    }

    @Test
    public void test_get_tag_bitmap() {
        RBitSet bitSet = redisService.getBitSet("RQ_KJHKL98UU78H66554GFDV");
        // 鏄惁瀛樺湪
        log.info("xiaofuge 瀛樺湪锛岄鏈熺粨鏋滀负 true锛屾祴璇曠粨鏋?{}", bitSet.get(redisService.getIndexFromUserId("xiaofuge")));
        log.info("gudebai 涓嶅瓨鍦紝棰勬湡缁撴灉涓?false锛屾祴璇曠粨鏋?{}", bitSet.get(redisService.getIndexFromUserId("gudebai")));
    }

    @Test
    public void test_null_tag_bitmap() {
        RBitSet bitSet = redisService.getBitSet("null");
        log.info("娴嬭瘯缁撴灉:{}", bitSet.isExists());
    }

}
