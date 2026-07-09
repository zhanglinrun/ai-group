package com.aigroup.groupbuy.domain.trade.service.task;

import com.aigroup.groupbuy.domain.trade.adapter.port.ITradePort;
import com.aigroup.groupbuy.domain.trade.adapter.repository.ITradeRepository;
import com.aigroup.groupbuy.domain.trade.model.entity.NotifyTaskEntity;
import com.aigroup.groupbuy.domain.trade.service.ITradeTaskService;
import com.aigroup.groupbuy.types.enums.NotifyTaskHTTPEnumVO;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 浜ゆ槗浠诲姟锛圡T/HTTP锛夋湇鍔?
 * 
 * @author xiaofuge bugstack.cn @灏忓倕鍝?
 * 2025/7/12 21:15
 */
@Slf4j
@Service
public class TradeTaskService implements ITradeTaskService {

    @Resource
    private ITradeRepository repository;
    @Resource
    private ITradePort port;
    @Resource
    private ThreadPoolExecutor threadPoolExecutor;
    
    @Override
    public Map<String, Integer> execNotifyJob() throws Exception {
        log.info("鎷煎洟浜ゆ槗-鎵ц鍥炶皟閫氱煡浠诲姟");

        // 鏌ヨ鏈墽琛屼换鍔?
        List<NotifyTaskEntity> notifyTaskEntityList = repository.queryUnExecutedNotifyTaskList();

        return execNotifyJob(notifyTaskEntityList);
    }

    @Override
    public Map<String, Integer> execNotifyJob(String teamId) throws Exception {
        log.info("鎷煎洟浜ゆ槗-鎵ц鍥炶皟閫氱煡鍥炶皟锛屾寚瀹?teamId:{}", teamId);
        List<NotifyTaskEntity> notifyTaskEntityList = repository.queryUnExecutedNotifyTaskList(teamId);
        return execNotifyJob(notifyTaskEntityList);
    }

    @Override
    public Map<String, Integer> execNotifyJob(NotifyTaskEntity notifyTaskEntity) throws Exception {
        log.info("鎷煎洟浜ゆ槗-鎵ц鍥炶皟閫氱煡鍥炶皟锛屾寚瀹?teamId:{} notifyTaskEntity:{}", notifyTaskEntity.getTeamId(), JSON.toJSONString(notifyTaskEntity));
        return execNotifyJob(Collections.singletonList(notifyTaskEntity));
    }

    private Map<String, Integer> execNotifyJob(List<NotifyTaskEntity> notifyTaskEntityList) throws Exception {
        int successCount = 0, errorCount = 0, retryCount = 0;
        for (NotifyTaskEntity notifyTask : notifyTaskEntityList) {
            // 鍥炶皟澶勭悊 success 鎴愬姛锛宔rror 澶辫触
            String response = port.groupBuyNotify(notifyTask);

            // 鏇存柊鐘舵?佸垽鏂?鍙樻洿鏁版嵁搴撹〃鍥炶皟浠诲姟鐘舵??
            if (NotifyTaskHTTPEnumVO.SUCCESS.getCode().equals(response)) {
                int updateCount = repository.updateNotifyTaskStatusSuccess(notifyTask);
                if (1 == updateCount) {
                    successCount += 1;
                }
            } else if (NotifyTaskHTTPEnumVO.ERROR.getCode().equals(response)) {
                if (notifyTask.getNotifyCount() > 4) {
                    int updateCount = repository.updateNotifyTaskStatusError(notifyTask);
                    if (1 == updateCount) {
                        errorCount += 1;
                    }
                } else {
                    int updateCount = repository.updateNotifyTaskStatusRetry(notifyTask);
                    if (1 == updateCount) {
                        retryCount += 1;
                    }
                }
            }
        }

        Map<String, Integer> resultMap = new HashMap<>();
        resultMap.put("waitCount", notifyTaskEntityList.size());
        resultMap.put("successCount", successCount);
        resultMap.put("errorCount", errorCount);
        resultMap.put("retryCount", retryCount);

        return resultMap;
    }
    
}
