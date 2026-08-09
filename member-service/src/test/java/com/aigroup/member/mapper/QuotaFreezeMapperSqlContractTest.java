package com.aigroup.member.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotaFreezeMapperSqlContractTest {

    @Test
    void annotationSqlUsesARealNotEqualOperatorAndSeparatesManagedRows() throws Exception {
        Method legacyScan = QuotaFreezeMapper.class.getMethod(
                "selectExpiredPendingFreezeIds", java.time.LocalDateTime.class, int.class);
        String legacySql = String.join(" ", legacyScan.getAnnotation(Select.class).value());

        assertTrue(legacySql.contains("owner_service <> 'agent-service'"));
        assertFalse(legacySql.contains("&lt;&gt;"),
                "Java annotation SQL is not XML and must not HTML-escape the <> operator");

        Method managedScan = QuotaFreezeMapper.class.getMethod(
                "selectExpiredManagedPendingFreezeIds", java.time.LocalDateTime.class, int.class);
        String managedSql = String.join(" ", managedScan.getAnnotation(Select.class).value());
        assertTrue(managedSql.contains("owner_service = 'agent-service'"));
    }
}
