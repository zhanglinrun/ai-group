package com.aigroup.groupbuy.test.mapper;

import com.aigroup.groupbuy.infrastructure.dao.IGroupBuyOrderListDao;
import com.aigroup.groupbuy.infrastructure.dao.po.GroupBuyOrderList;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class GroupBuyOrderBusinessKeyMapperTest {

    @Test
    public void statusUpdatesAndReplayReadsRequireFullBusinessKey() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mybatis/mapper/group_buy_order_list_mapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        GroupBuyOrderList scoped = GroupBuyOrderList.builder().userId("u").source("s")
                .channel("c2").outTradeNo("no").orderId("order2").build();
        for (String name : new String[]{"queryGroupBuyOrderRecordByBusinessKey", "updateOrderStatus2COMPLETE",
                "unpaid2Refund", "paid2Refund", "paidTeam2Refund"}) {
            MappedStatement statement = configuration.getMappedStatement(IGroupBuyOrderListDao.class.getName() + "." + name);
            BoundSql bound = statement.getBoundSql(scoped);
            List<String> parameters = bound.getParameterMappings().stream()
                    .map(mapping -> mapping.getProperty()).collect(Collectors.toList());
            for (String field : new String[]{"userId", "source", "channel", "outTradeNo"}) {
                assertTrue(name + " missing " + field, parameters.contains(field));
            }
            if (name.endsWith("Refund")) assertTrue(name + " missing orderId", parameters.contains("orderId"));
            assertTrue(name + " missing source predicate", bound.getSql().contains("source = ?"));
            assertTrue(name + " missing channel predicate", bound.getSql().contains("channel = ?"));
        }
        BoundSql legacy = configuration.getMappedStatement(IGroupBuyOrderListDao.class.getName()
                + ".queryGroupBuyOrderRecordByOutTradeNo").getBoundSql(scoped);
        assertTrue("legacy lookup must reject ambiguous matches", legacy.getSql().contains("count(*)"));
        assertTrue(legacy.getSql().contains("= 1"));
    }
}
