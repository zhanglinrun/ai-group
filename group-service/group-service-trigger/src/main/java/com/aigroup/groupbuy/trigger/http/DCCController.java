package com.aigroup.groupbuy.trigger.http;

import com.aigroup.groupbuy.api.IDCCService;
import com.aigroup.groupbuy.api.response.Response;
import com.aigroup.groupbuy.types.dcc.DccAttributeVO;
import com.aigroup.groupbuy.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * @author Fuzhengwei bugstack.cn @小傅哥
 * @description 动态配置管理
 * @create 2025-01-03 19:16
 */
@Slf4j
@RestController()
@RequestMapping("/api/v1/gbm/dcc/")
public class DCCController implements IDCCService {

    @Resource(name = "dynamicConfigCenterRedisTopic")
    private RTopic dccTopic;

    /**
     * 动态值变更
     * <p>
     * curl -X POST "http://127.0.0.1:8091/api/v1/gbm/dcc/update_config?key=downgradeSwitch&value=1"
     * curl -X POST "http://127.0.0.1:8091/api/v1/gbm/dcc/update_config?key=cutRange&value=0"
     * curl -X POST "http://127.0.0.1:8091/api/v1/gbm/dcc/update_config?key=rateLimiterSwitch&value=close"
     */
    @RequestMapping(value = "update_config", method = RequestMethod.POST)
    @Override
    public Response<Boolean> updateConfig(@RequestParam String key, @RequestParam String value) {
        try {
            log.info("DCC 动态配置值变更 key:{} value:{}", key, value);
            dccTopic.publish(new DccAttributeVO(key, value));
            return Response.<Boolean>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("DCC 动态配置值变更失败 key:{} value:{}", key, value, e);
            return Response.<Boolean>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

}
