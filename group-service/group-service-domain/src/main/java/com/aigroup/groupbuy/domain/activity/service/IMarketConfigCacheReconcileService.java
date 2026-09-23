package com.aigroup.groupbuy.domain.activity.service;

/**
 * Samples marketing-config cache against MySQL and evicts mismatches.
 */
public interface IMarketConfigCacheReconcileService {

    /**
     * @return number of mismatched keys evicted
     */
    int reconcile();
}
