package com.aigroup.groupbuy.infrastructure.adapter.repository;

import com.aigroup.groupbuy.domain.activity.model.valobj.TeamStatisticVO;
import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyOrderDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyTeamStatistic;
import com.aigroup.groupbuy.infrastructure.dcc.DCCService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class ActivityRepositoryStatisticTest {

    private ActivityRepository repository;
    private IGroupBuyOrderDao groupBuyOrderDao;
    private DCCService dccService;

    @Before
    public void setUp() {
        repository = new ActivityRepository();
        groupBuyOrderDao = Mockito.mock(IGroupBuyOrderDao.class);
        dccService = Mockito.mock(DCCService.class);
        when(dccService.isCacheOpenSwitch()).thenReturn(false);
        ReflectionTestUtils.setField(repository, "groupBuyOrderDao", groupBuyOrderDao);
        ReflectionTestUtils.setField(repository, "dccService", dccService);
    }

    @Test
    public void shouldReadActivityStatisticsFromSingleAggregateQuery() {
        when(groupBuyOrderDao.queryTeamStatisticByActivityId(100201L))
                .thenReturn(new GroupBuyTeamStatistic(399009L, 2L, 399014L));

        TeamStatisticVO result = repository.queryTeamStatisticByActivityId(100201L);

        assertEquals(Integer.valueOf(399009), result.getAllTeamCount());
        assertEquals(Integer.valueOf(2), result.getAllTeamCompleteCount());
        assertEquals(Integer.valueOf(399014), result.getAllTeamUserCount());
        Mockito.verify(groupBuyOrderDao).queryTeamStatisticByActivityId(100201L);
    }

    @Test
    public void shouldReturnZerosWhenAggregateIsUnavailable() {
        when(groupBuyOrderDao.queryTeamStatisticByActivityId(100203L)).thenReturn(null);

        TeamStatisticVO result = repository.queryTeamStatisticByActivityId(100203L);

        assertEquals(Integer.valueOf(0), result.getAllTeamCount());
        assertEquals(Integer.valueOf(0), result.getAllTeamCompleteCount());
        assertEquals(Integer.valueOf(0), result.getAllTeamUserCount());
    }
}
