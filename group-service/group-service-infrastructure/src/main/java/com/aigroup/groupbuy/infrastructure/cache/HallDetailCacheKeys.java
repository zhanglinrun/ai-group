package com.aigroup.groupbuy.infrastructure.cache;

/**
 * Redis keys for hall/detail hot reads (activity stats + in-progress team cards).
 * Seat stock remains on Lua + MySQL and is never stored here.
 */
public final class HallDetailCacheKeys {

    private HallDetailCacheKeys() {
    }

    public static String teamStatistic(Long activityId) {
        return "group_buy_hall_team_stat_" + activityId;
    }

    /** Shared in-progress team pool for an activity (random hall slice is drawn from this). */
    public static String progressPool(Long activityId) {
        return "group_buy_hall_progress_pool_" + activityId;
    }

    public static String ownerTeams(Long activityId, String userId) {
        return "group_buy_hall_owner_teams_" + activityId + "_" + userId;
    }
}
