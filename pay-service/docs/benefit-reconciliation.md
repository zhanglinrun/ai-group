# Published benefit reconciliation (Pay)

Fresh full-Compose MySQL volumes apply V8 then V9 through numbered init mounts.
Existing volumes do not rerun init SQL: apply
`docs/dev-ops/mysql/sql/V8_benefit_event_publish_claim.sql` followed by
`docs/dev-ops/mysql/sql/V9_benefit_reconciliation.sql` to
`s_pay_mall_ddd_market` before enabling the job. The application does not
auto-migrate. Member's internal status endpoint and `ai-group.internal.token`
must be reachable/configured.

Fresh XXL-JOB volumes register `benefitReconciliationJob` every minute. On an
existing XXL volume, run `dev-ops/xxl-job/migrations/2026-09-20-benefit-reconciliation.sql`
once (safe to rerun). Alternatively enable
`pay.benefit-reconciliation.local-scheduler-enabled=true` when XXL Admin is
unavailable (default false; local fixed delay 60s). Do not intentionally
enable both schedulers; the DB claim still excludes duplicate work.
DLT listeners acknowledge only after successful processing. A failed DLT record
stops its dedicated container for operator diagnosis and restart; it is not
proof of Member grant, and this Pay reconciliation still verifies Member state.
Alert on stopped DLT containers and inspect the failing topic/partition/offset
before replaying the original idempotency identity. No automatic DLT-to-DLT loop.

The shared recoverer now explicitly publishes to `{sourceTopic}.DLT` and waits
for the broker send result. Before this fix Spring Kafka's default destination
was `{sourceTopic}-dlt`, which the project listeners did not subscribe to.
On an existing broker, inspect any historical `-dlt` topics separately and
replay only after verifying the original business identity and current state;
new topic configuration does not migrate old records.
Each run fetches at most 50 COMPLETED rows with `SENT` state aged at least 10
minutes. The table stores `event_id`, Member and order statuses, reason,
`check_count`, `replay_attempts`, lease and next-check time. Unchecked rows and
due retries are ordered by their due time; confirmed GRANTED/REVOKED outcomes
are terminal so old successful grants cannot starve missing grants. An atomic
five-minute claim fences concurrent instances; a replay intent is recorded before send.
The publisher resends the original outbox ID, type, user, order, SKU and quota,
not a new event; Member deduplicates by order ID and type. Only MARKET or
DEAL_DONE with Member PENDING and no revoke outbox row can replay. Current
order/revoke and the immutable user, SKU and positive quota snapshot are checked
again immediately before send. Ambiguous Kafka ack or crash can produce a
duplicate original event, never a new grant identity. Refund may still race after
the final check: Member's revoke tombstone blocks a later grant, while a grant
already applied with spent quota becomes `REJECTED_GRANTED` for manual review.
Replay is limited to three attempts with 5/15/45-minute rechecks; after the
last recheck still PENDING, the row becomes MANUAL. Member transport/application
5xx failures retry after five minutes without consuming replay attempts. A
GRANTED confirmation requires matching user, SKU, microcredit amount and no
manualReview flag; normal refunded REVOKED is confirmed, conflicts stay MANUAL.
The read-only `dev-ops/reconciliation` audit detects later cross-service drift.

Operational triage:

```sql
SELECT r.event_id, e.order_id, r.outcome, r.order_status, r.member_status,
       r.detail, r.check_count, r.replay_attempts, r.update_time
FROM benefit_reconciliation r JOIN benefit_event e ON e.event_id = r.event_id
WHERE r.outcome IN ('MANUAL', 'UNAVAILABLE', 'REPLAYED', 'PROCESSING')
ORDER BY r.update_time DESC LIMIT 100;
```

Investigate MANUAL rows against the Pay order, revoke outbox, Member status and
DLT. Do not manually adjust quota from Pay. After manual investigation and a
confirmed safe resolution, an operator may reset a specific row's outcome to
`READY`, `next_check_at=NOW()`, and `replay_attempts=0` if replay is authorized;
otherwise leave it MANUAL for audit. `PROCESSING` with an expired lease is
retried. Run rate is at most 50 per invocation; continuous Member outages are
rechecked every five minutes but never auto-credit. The five-minute lease
requires Feign/Kafka timeouts below five minutes; an exceptionally slow call
may overlap after lease expiry, but uses the same Member idempotency key.
