package com.aigroup.member.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface QuotaReconciliationMapper {

    @Select("SELECT MAX(user_id) FROM quota_account")
    Long maxUserId();

    // A single consistent read keeps each account and its two aggregates on the same InnoDB snapshot.
    @Select("""
            SELECT a.user_id, a.free_quota_balance, a.paid_quota_balance, a.frozen_balance,
                   (SELECT COALESCE(SUM(l.amount), 0) FROM quota_ledger l
                    WHERE l.user_id = a.user_id
                      AND l.type IN ('GRANT', 'DEBIT', 'CONFIRM', 'MONTHLY_GRANT', 'ADMIN_ADJUST')) AS ledger_total,
                   (SELECT COALESCE(SUM(f.amount), 0) FROM quota_freeze f
                    WHERE f.user_id = a.user_id AND f.status = 'PENDING') AS pending_total
            FROM quota_account a
            WHERE a.user_id > #{afterUserId} AND a.user_id <= #{upperUserId}
            ORDER BY a.user_id
            LIMIT #{limit}
            """)
    List<QuotaSnapshot> readPage(@Param("afterUserId") long afterUserId,
                                 @Param("upperUserId") long upperUserId,
                                 @Param("limit") int limit);

    @Insert("""
            INSERT INTO quota_reconciliation_mismatch
                (check_date, user_id, check_type, snapshot_balance, source_balance, first_seen_at, last_seen_at)
            VALUES (#{checkDate}, #{userId}, #{checkType}, #{snapshotBalance}, #{sourceBalance}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                snapshot_balance = VALUES(snapshot_balance), source_balance = VALUES(source_balance),
                last_seen_at = NOW()
            """)
    int upsertMismatch(@Param("checkDate") LocalDate checkDate,
                       @Param("userId") long userId,
                       @Param("checkType") String checkType,
                       @Param("snapshotBalance") long snapshotBalance,
                       @Param("sourceBalance") long sourceBalance);

    @Data
    class QuotaSnapshot {
        private Long userId;
        private Long freeQuotaBalance;
        private Long paidQuotaBalance;
        private Long frozenBalance;
        private Long ledgerTotal;
        private Long pendingTotal;
    }
}
