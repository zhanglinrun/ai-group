# Group-to-Pay notification identity migration

Apply `V10_group_order_identity.sql` before deploying the new Pay listener and mapper. Fresh full-Compose MySQL volumes run it after V9; existing volumes do not rerun entrypoint scripts. `group_source` and `group_channel` are immutable snapshots written when Pay locks the Group order. Settlement requires an exact `order_id`, user, team, activity, source, channel and group-market match; refund checks the same tuple before any Alipay call. Old Group Kafka messages without these fields fail closed and remain retryable/DLT-visible rather than settling an unrelated order.

Legacy `pay_order` rows keep both new fields NULL. Do **not** fill them from current `app.config.group-buy-market.source/channel`: configuration may have changed since purchase. On an isolated MySQL containing both schemas, this read-only query lists the eligible, uniquely matched legacy rows for review:

```sql
SELECT p.order_id, p.user_id, p.group_team_id, p.group_activity_id,
       MIN(d.source) AS source, MIN(d.channel) AS channel,
       COUNT(*) AS matching_details
FROM s_pay_mall_ddd_market.pay_order p
JOIN group_buy_market.group_buy_order_list d
  ON d.out_trade_no COLLATE utf8mb4_unicode_ci = p.order_id COLLATE utf8mb4_unicode_ci
 AND d.user_id COLLATE utf8mb4_unicode_ci = p.user_id COLLATE utf8mb4_unicode_ci
 AND d.team_id COLLATE utf8mb4_unicode_ci = p.group_team_id COLLATE utf8mb4_unicode_ci
 AND d.activity_id = p.group_activity_id
WHERE p.market_type = 1 AND p.group_source IS NULL AND p.group_channel IS NULL
GROUP BY p.order_id, p.user_id, p.group_team_id, p.group_activity_id
HAVING COUNT(*) = 1;
```

For each reviewed row, a controlled migration may set the two snapshots from the **same** Group detail after confirming the user and business intent. A zero- or multiple-detail match, a partially populated tuple, or physically separate production databases requires manual investigation and a migration-specific evidence trail. Never fall back to an unscoped callback. Coordinate deployment with the Group producer so new messages contain `teamId`, `activityId`, and member identities; monitor DLT and the read-only `dev-ops/reconciliation` audit. Its `LEGACY_GROUP_IDENTITY_MISSING` finding identifies aged matching Group orders with a NULL Pay snapshot (and can coexist with `FORMED_GROUP_PAY_STUCK`); `matchingDetails > 1` hides the ambiguous Group source/channel, so never pick one arbitrarily. The audit does not backfill or replay. The 24-hour default lookback and 10-minute age threshold are not complete coverage of all legacy rows. This migration does not mutate any order status or grant quota.

Group may publish a formed team containing orders from other integrations. Pay settles only members with a local Pay order; an absent local row is logged and skipped, while any existing row with mismatched identity or an unpaid state fails the callback. A paid `CLOSE` (non-null `pay_time`) is an idempotent refund replay, but an unpaid cancellation is not. The cross-schema audit starts from Pay rows and therefore cannot prove that every Group detail belongs to this Pay service or detect a missing Pay row; investigate unexpected skip logs against the producer's source/channel ownership. These are code-level checks, not a mixed-integration live bench result.
