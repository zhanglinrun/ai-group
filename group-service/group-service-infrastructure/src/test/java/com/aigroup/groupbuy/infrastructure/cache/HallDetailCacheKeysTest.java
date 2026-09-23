package com.aigroup.groupbuy.infrastructure.cache;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HallDetailCacheKeysTest {

    @Test
    public void keysAreStableAndActivityScoped() {
        assertEquals("group_buy_hall_team_stat_100201", HallDetailCacheKeys.teamStatistic(100201L));
        assertEquals("group_buy_hall_progress_pool_100201", HallDetailCacheKeys.progressPool(100201L));
        assertEquals("group_buy_hall_owner_teams_100201_u1", HallDetailCacheKeys.ownerTeams(100201L, "u1"));
        assertTrue(HallDetailCacheKeys.ownerTeams(1L, "a").contains("a"));
    }
}
