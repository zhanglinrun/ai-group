package com.aigroup.member.service;

import com.aigroup.member.mapper.QuotaReconciliationMapper;
import com.aigroup.member.mapper.QuotaReconciliationMapper.QuotaSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaReconciliationService {
    private static final int MAX_PAGE_SIZE = 500;
    private static final int LOG_SAMPLE_LIMIT = 5;
    private static final String LEDGER_BALANCE = "LEDGER_BALANCE";
    private static final String PENDING_FREEZE = "PENDING_FREEZE";

    private final QuotaReconciliationMapper mapper;

    public Result check(LocalDate checkDate, int pageSize) {
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("reconciliation page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long upperUserId = mapper.maxUserId();
        if (upperUserId == null) {
            return new Result(0, 0, 0);
        }
        long afterUserId = Long.MIN_VALUE;
        long checked = 0;
        long ledgerMismatches = 0;
        long freezeMismatches = 0;
        while (true) {
            List<QuotaSnapshot> page = mapper.readPage(afterUserId, upperUserId, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (QuotaSnapshot snapshot : page) {
                long userId = snapshot.getUserId();
                long balance = Math.addExact(snapshot.getFreeQuotaBalance(), snapshot.getPaidQuotaBalance());
                if (balance != snapshot.getLedgerTotal()) {
                    mapper.upsertMismatch(checkDate, userId, LEDGER_BALANCE, balance, snapshot.getLedgerTotal());
                    if (ledgerMismatches + freezeMismatches < LOG_SAMPLE_LIMIT) {
                        log.warn("quota reconciliation ledger mismatch, date={}, userId={}, snapshot={}, ledger={}",
                                checkDate, userId, balance, snapshot.getLedgerTotal());
                    }
                    ledgerMismatches++;
                }
                if (!snapshot.getFrozenBalance().equals(snapshot.getPendingTotal())) {
                    mapper.upsertMismatch(checkDate, userId, PENDING_FREEZE,
                            snapshot.getFrozenBalance(), snapshot.getPendingTotal());
                    if (ledgerMismatches + freezeMismatches < LOG_SAMPLE_LIMIT) {
                        log.warn("quota reconciliation freeze mismatch, date={}, userId={}, snapshot={}, pending={}",
                                checkDate, userId, snapshot.getFrozenBalance(), snapshot.getPendingTotal());
                    }
                    freezeMismatches++;
                }
                checked++;
                afterUserId = userId;
            }
            if (page.size() < pageSize || afterUserId == upperUserId) {
                break;
            }
        }
        return new Result(checked, ledgerMismatches, freezeMismatches);
    }

    public record Result(long checked, long ledgerMismatches, long freezeMismatches) {
    }
}
