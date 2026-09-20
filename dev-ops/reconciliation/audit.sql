-- Local bench only. The runner substitutes the two validated integer tokens.
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;
SET @as_of = NOW(6);
SET @cutoff = DATE_SUB(@as_of, INTERVAL 10 MINUTE);
SET @window_start = DATE_SUB(@as_of, INTERVAL __WINDOW_HOURS__ HOUR);

SELECT JSON_OBJECT('kind', 'meta', 'serverUuid', @@server_uuid,
                   'asOf', DATE_FORMAT(@as_of, '%Y-%m-%dT%H:%i:%s.%f'),
                   'windowStart', DATE_FORMAT(@window_start, '%Y-%m-%dT%H:%i:%s.%f'),
                   'cutoff', DATE_FORMAT(@cutoff, '%Y-%m-%dT%H:%i:%s.%f'));

WITH linked AS (
    SELECT p.order_id, p.user_id, p.status AS pay_status, p.settlement_notified,
           p.group_source, p.group_channel, d.source AS detail_source, d.channel AS detail_channel,
           COUNT(*) OVER (PARTITION BY p.order_id) AS matching_detail_count,
           g.team_id, g.status AS group_status, g.lock_count, g.complete_count, g.target_count,
           d.status AS detail_status, e.event_id, e.event_published, e.publish_status,
           e.update_time AS event_update_time, rev.event_id AS revoke_event_id,
           mc.status AS member_completed_status, mr.status AS member_revoked_status
    FROM s_pay_mall_ddd_market.pay_order p
    JOIN group_buy_market.group_buy_order_list d
      ON d.out_trade_no COLLATE utf8mb4_unicode_ci = p.order_id COLLATE utf8mb4_unicode_ci
     AND d.user_id COLLATE utf8mb4_unicode_ci = p.user_id COLLATE utf8mb4_unicode_ci
     AND d.team_id COLLATE utf8mb4_unicode_ci = p.group_team_id COLLATE utf8mb4_unicode_ci
     AND d.activity_id = p.group_activity_id
     AND d.status = 1 AND d.update_time < @cutoff
    JOIN group_buy_market.group_buy_order g
      ON g.team_id = d.team_id AND g.status IN (1, 3) AND g.update_time < @cutoff
    LEFT JOIN s_pay_mall_ddd_market.benefit_event e
      ON e.order_id = p.order_id AND e.event_type = 'GROUP_BUY_COMPLETED'
    LEFT JOIN s_pay_mall_ddd_market.benefit_event rev
      ON rev.order_id = p.order_id AND rev.event_type = 'GROUP_BUY_REVOKED'
    LEFT JOIN member_db.benefit_grant_event mc
      ON mc.idempotency_key = CONCAT(p.order_id, ':GROUP_BUY_COMPLETED')
    LEFT JOIN member_db.benefit_grant_event mr
      ON mr.idempotency_key = CONCAT(p.order_id, ':GROUP_BUY_REVOKED')
    WHERE p.market_type = 1 AND p.update_time >= @window_start AND p.update_time < @cutoff
),
candidates AS (
    SELECT 'FORMED_GROUP_PAY_STUCK' AS anomaly, order_id,
           JSON_OBJECT('kind', 'anomaly', 'name', 'FORMED_GROUP_PAY_STUCK',
                       'orderId', order_id, 'userId', user_id, 'teamId', team_id,
                       'payStatus', pay_status, 'groupStatus', group_status,
                       'detailStatus', detail_status, 'lockCount', lock_count,
                       'completeCount', complete_count, 'targetCount', target_count,
                       'settlementNotified', settlement_notified,
                       'completedEventId', event_id, 'publishStatus', publish_status,
                       'memberCompletedStatus', member_completed_status,
                       'memberRevokedStatus', member_revoked_status) AS evidence
    FROM linked
    WHERE pay_status = 'PAY_SUCCESS' AND revoke_event_id IS NULL
      AND member_revoked_status IS NULL

    UNION ALL
    SELECT 'LEGACY_GROUP_IDENTITY_MISSING', order_id,
           JSON_OBJECT('kind', 'anomaly', 'name', 'LEGACY_GROUP_IDENTITY_MISSING',
                       'orderId', order_id, 'userId', user_id, 'teamId', team_id,
                       'payStatus', pay_status, 'groupStatus', group_status,
                       'detailStatus', detail_status, 'matchingDetails', matching_detail_count,
                       'groupSource', group_source, 'groupChannel', group_channel,
                       'detailSource', IF(matching_detail_count = 1, detail_source, NULL),
                       'detailChannel', IF(matching_detail_count = 1, detail_channel, NULL)) AS evidence
    FROM linked
    WHERE pay_status IN ('PAY_SUCCESS', 'MARKET', 'DEAL_DONE', 'WAIT_REFUND')
      AND (group_source IS NULL OR group_channel IS NULL)

    UNION ALL
    SELECT 'ELIGIBLE_PAY_MISSING_COMPLETED_OUTBOX', order_id,
           JSON_OBJECT('kind', 'anomaly', 'name', 'ELIGIBLE_PAY_MISSING_COMPLETED_OUTBOX',
                       'orderId', order_id, 'userId', user_id, 'teamId', team_id,
                       'payStatus', pay_status, 'groupStatus', group_status,
                       'detailStatus', detail_status, 'lockCount', lock_count,
                       'completeCount', complete_count, 'targetCount', target_count,
                       'settlementNotified', settlement_notified,
                       'completedEventId', event_id, 'publishStatus', publish_status,
                       'memberCompletedStatus', member_completed_status,
                       'memberRevokedStatus', member_revoked_status)
    FROM linked
    WHERE pay_status IN ('MARKET', 'DEAL_DONE') AND event_id IS NULL
      AND revoke_event_id IS NULL AND member_revoked_status IS NULL

    UNION ALL
    SELECT 'SENT_COMPLETED_WITHOUT_MEMBER_RECORD', order_id,
           JSON_OBJECT('kind', 'anomaly', 'name', 'SENT_COMPLETED_WITHOUT_MEMBER_RECORD',
                       'orderId', order_id, 'userId', user_id, 'teamId', team_id,
                       'payStatus', pay_status, 'groupStatus', group_status,
                       'detailStatus', detail_status, 'lockCount', lock_count,
                       'completeCount', complete_count, 'targetCount', target_count,
                       'settlementNotified', settlement_notified,
                       'completedEventId', event_id, 'publishStatus', publish_status,
                       'eventPublished', event_published,
                       'memberCompletedStatus', member_completed_status,
                       'memberRevokedStatus', member_revoked_status)
    FROM linked
    WHERE pay_status IN ('MARKET', 'DEAL_DONE') AND event_published = 1
      AND publish_status = 'SENT' AND event_update_time < @cutoff
      AND event_id IS NOT NULL AND member_completed_status IS NULL
      AND revoke_event_id IS NULL AND member_revoked_status IS NULL

    UNION ALL
    SELECT 'REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT', p.order_id,
           JSON_OBJECT('kind', 'anomaly', 'name', 'REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT',
                       'orderId', p.order_id, 'userId', p.user_id,
                       'payStatus', p.status, 'settlementNotified', p.settlement_notified,
                       'memberCompletedStatus', mc.status, 'memberCompletedQuota', mc.granted_quota,
                       'memberRevokedStatus', mr.status)
    FROM s_pay_mall_ddd_market.pay_order p
    LEFT JOIN member_db.benefit_grant_event mc
      ON mc.idempotency_key = CONCAT(p.order_id, ':GROUP_BUY_COMPLETED')
    LEFT JOIN member_db.benefit_grant_event mr
      ON mr.idempotency_key = CONCAT(p.order_id, ':GROUP_BUY_REVOKED')
    WHERE p.market_type = 1 AND p.status IN ('CLOSE', 'WAIT_REFUND')
      AND p.update_time >= @window_start AND p.update_time < @cutoff
      AND (mr.status IS NULL OR mr.status NOT IN ('REVOKED', 'SKIPPED_REVOKED'))
      AND ((mc.status IN ('GRANTED', 'REJECTED_GRANTED') AND mc.created_at < @cutoff)
           OR (mr.status = 'REJECTED_GRANTED' AND mr.created_at < @cutoff))

    UNION ALL
    SELECT 'PAY_BENEFIT_RECONCILIATION_MANUAL', COALESCE(e.order_id, ''),
           JSON_OBJECT('kind', 'anomaly', 'name', 'PAY_BENEFIT_RECONCILIATION_MANUAL',
                       'orderId', e.order_id, 'eventId', r.event_id,
                       'eventType', e.event_type, 'publishStatus', e.publish_status,
                       'eventPublished', e.event_published, 'payStatus', p.status,
                       'memberStatus', r.member_status, 'reconciliationOutcome', r.outcome,
                       'reconciliationDetail', r.detail)
    FROM s_pay_mall_ddd_market.benefit_reconciliation r
    LEFT JOIN s_pay_mall_ddd_market.benefit_event e ON e.event_id = r.event_id
    LEFT JOIN s_pay_mall_ddd_market.pay_order p ON p.order_id = e.order_id
    WHERE r.outcome = 'MANUAL' AND r.update_time >= @window_start AND r.update_time < @cutoff
)
SELECT evidence FROM candidates ORDER BY anomaly, order_id LIMIT __LIMIT_PLUS_ONE__;
COMMIT;
