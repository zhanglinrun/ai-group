package com.aigroup.member.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaReconciliationMapperSqlContractTest {
    @Test
    void pageReadsOnlyEconomicLedgerAndPendingFreezeInKeysetOrder() throws Exception {
        String sql = String.join(" ", QuotaReconciliationMapper.class
                .getMethod("readPage", long.class, long.class, int.class).getAnnotation(Select.class).value());
        assertTrue(sql.contains("l.type IN ('GRANT', 'DEBIT', 'CONFIRM', 'MONTHLY_GRANT', 'ADMIN_ADJUST')"));
        assertFalse(sql.contains("'REVOKE'"));
        assertTrue(sql.contains("f.status = 'PENDING'"));
        assertTrue(sql.contains("a.user_id > #{afterUserId} AND a.user_id <= #{upperUserId}"));
        assertTrue(sql.contains("ORDER BY a.user_id"));
        assertTrue(sql.contains("LIMIT #{limit}"));
        assertFalse(sql.contains("FOR UPDATE"));
        assertFalse(sql.contains("OFFSET"));
    }

    @Test
    void onlyMismatchTableIsUpserted() throws Exception {
        String sql = String.join(" ", QuotaReconciliationMapper.class
                .getMethod("upsertMismatch", java.time.LocalDate.class, long.class, String.class, long.class, long.class)
                .getAnnotation(Insert.class).value());
        assertTrue(sql.contains("INSERT INTO quota_reconciliation_mismatch"));
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertFalse(sql.contains("UPDATE quota_account"));
        assertFalse(sql.contains("UPDATE quota_freeze"));
    }
}
