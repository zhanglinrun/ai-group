package com.aigroup.paymall.test.infrastructure;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;

import java.io.InputStream;
import java.util.Date;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BenefitReconciliationMapperTest {
    @Test
    public void dueScanAndClaimStatementsParseAndFenceConcurrentReplays() throws Exception {
        Configuration config = new Configuration();
        parse(config, "mybatis/mapper/benefit_event_mapper.xml");
        parse(config, "mybatis/mapper/benefit_reconciliation_mapper.xml");

        String scan = sql(config, "com.aigroup.paymall.infrastructure.dao.IBenefitEventDao.queryDuePublishedGrants",
                Map.of("cutoff", new Date(), "limit", 50));
        assertTrue(scan.contains("e.event_published = 1"));
        assertTrue(scan.contains("e.publish_status = 'SENT'"));
        assertTrue(scan.contains("r.next_check_at <= now()"));
        assertTrue(scan.contains("r.lease_until < now()"));
        assertTrue(scan.contains("order by coalesce(r.next_check_at, e.update_time), e.id asc"));
        assertFalse(scan.contains("'GRANTED', 'REVOKED'"));
        assertTrue(scan.contains("limit ?"));

        String claim = sql(config, "com.aigroup.paymall.infrastructure.dao.IBenefitReconciliationDao.claim",
                Map.of("eventId", "e-1", "token", "owner"));
        assertTrue(claim.contains("check_count = check_count + 1"));
        assertTrue(claim.contains("lease_until = date_add(now(), interval 5 minute)"));
        assertTrue(claim.contains("next_check_at <= now()"));
        assertTrue(claim.contains("lease_until < now()"));
        assertFalse(claim.contains("'GRANTED', 'REVOKED'"));

        String intent = sql(config, "com.aigroup.paymall.infrastructure.dao.IBenefitReconciliationDao.recordReplayIntent",
                Map.of("eventId", "e-1", "token", "owner", "maxAttempts", 3));
        assertTrue(intent.contains("replay_attempts = replay_attempts + 1"));
        assertTrue(intent.contains("claim_token = ?"));
        assertTrue(intent.contains("replay_attempts < ?"));
    }

    private void parse(Configuration config, String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
    }

    private String sql(Configuration config, String statement, Object params) {
        BoundSql bound = config.getMappedStatement(statement).getBoundSql(params);
        return bound.getSql().replaceAll("\\s+", " ").trim();
    }
}
