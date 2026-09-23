#!/usr/bin/env python3
"""Bench settlement idempotency + throughput on ai-group-bench.

Preferred path: run inside the Compose network (see run-settlement-bench.ps1):
  kafka-python → kafka:19092, PyMySQL → mysql:3306, 64 worker threads.

Formulas match campus-dash SettleConcurrencyIT: round(N * 1000 / elapsed_ms).
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

TOPIC = "group.team_success"
PROJECT = "ai-group-bench"
MYSQL_CONTAINER = "ai-group-bench-mysql-1"
ACTIVITY_ID = 100201
PRODUCT_ID = "9890002"
PRODUCT_CODE = "QUOTA_LIGHT"
PRODUCT_NAME = "QUOTA_LIGHT"
BASE_QUOTA = 60
SOURCE = "s01"
CHANNEL = "c01"
EXPECTED_MICRO = BASE_QUOTA * 1_000_000


class BenchError(Exception):
    pass


def env(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    return default if value is None or value == "" else value


IN_NETWORK = env("BENCH_IN_NETWORK", "0") in ("1", "true", "TRUE", "yes")
KAFKA_BOOTSTRAP = env("KAFKA_BOOTSTRAP", "kafka:19092" if IN_NETWORK else "localhost:9092")
MYSQL_HOST = env("MYSQL_HOST", "mysql" if IN_NETWORK else "127.0.0.1")
MYSQL_PORT = int(env("MYSQL_PORT", "3306" if IN_NETWORK else "13306"))
MYSQL_USER = env("MYSQL_USER", "root")
MYSQL_PASSWORD = env("MYSQL_ROOT_PASSWORD", env("MYSQL_PASSWORD", ""))


_local = threading.local()


def _pymysql_conn():
    import pymysql  # type: ignore
    if not MYSQL_PASSWORD:
        raise BenchError("MYSQL_ROOT_PASSWORD required for PyMySQL")
    conn = getattr(_local, "conn", None)
    if conn is None or not conn.open:
        conn = pymysql.connect(
            host=MYSQL_HOST,
            port=MYSQL_PORT,
            user=MYSQL_USER,
            password=MYSQL_PASSWORD,
            charset="utf8mb4",
            autocommit=True,
        )
        _local.conn = conn
    return conn


def mysql_execute(sql: str) -> str:
    """Run SQL via PyMySQL (in-network / password present) or docker exec fallback."""
    if IN_NETWORK or MYSQL_PASSWORD:
        try:
            import pymysql  # noqa: F401
        except ImportError:
            if IN_NETWORK:
                raise BenchError("pymysql required in-network") from None
        else:
            if MYSQL_PASSWORD:
                out_lines: list[str] = []
                conn = _pymysql_conn()
                with conn.cursor() as cur:
                    for statement in sql.split(";"):
                        stmt = statement.strip()
                        if not stmt:
                            continue
                        cur.execute(stmt)
                        if cur.description:
                            for row in cur.fetchall():
                                out_lines.append(
                                    "\t".join("" if v is None else str(v) for v in row)
                                )
                return "\n".join(out_lines) + ("\n" if out_lines else "")

    result = subprocess.run(
        [
            "docker", "exec", "-i", MYSQL_CONTAINER, "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot '
            "--batch --raw --skip-column-names --default-character-set=utf8mb4",
        ],
        input=sql,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=120,
        check=False,
    )
    if result.returncode:
        raise BenchError(f"mysql failed: {result.stderr.strip()[:400]}")
    return result.stdout


def check_bench() -> None:
    if IN_NETWORK:
        mysql_execute("SELECT 1")
        return
    try:
        labels = json.loads(subprocess.check_output(
            ["docker", "inspect", "--format", "{{json .Config.Labels}}", MYSQL_CONTAINER],
            text=True, encoding="utf-8"))
        running = json.loads(subprocess.check_output(
            ["docker", "inspect", "--format", "{{json .State.Running}}", MYSQL_CONTAINER],
            text=True, encoding="utf-8"))
    except (subprocess.CalledProcessError, ValueError, TypeError) as exc:
        raise BenchError("bench mysql inspect failed") from exc
    if labels.get("com.docker.compose.project") != PROJECT or running is not True:
        raise BenchError("target is not running ai-group-bench mysql")


def team_payload(team_id: str, user_id: str, order_id: str) -> str:
    return json.dumps({
        "teamId": team_id,
        "activityId": ACTIVITY_ID,
        "members": [{
            "userId": user_id,
            "source": SOURCE,
            "channel": CHANNEL,
            "outTradeNo": order_id,
        }],
    }, separators=(",", ":"))


def make_producer():
    from kafka import KafkaProducer
    return KafkaProducer(
        bootstrap_servers=KAFKA_BOOTSTRAP,
        acks="all",
        retries=3,
        linger_ms=5,
        value_serializer=lambda v: v.encode("utf-8"),
        key_serializer=lambda v: None if v is None else v.encode("utf-8"),
    )


def seed_order(user_id: str, order_id: str, team_id: str) -> None:
    mysql_execute(f"""
INSERT INTO member_db.quota_account(user_id, free_quota_balance, paid_quota_balance, frozen_balance)
VALUES ({user_id}, 0, 0, 0)
ON DUPLICATE KEY UPDATE update_time = NOW();
INSERT INTO s_pay_mall_ddd_market.pay_order(
  client_request_id, request_fingerprint, create_stage,
  user_id, product_id, product_code, product_name, base_quota_snapshot,
  order_id, order_time, total_amount, status, pay_time, market_type,
  group_activity_id, group_team_id, group_source, group_channel,
  market_deduction_amount, pay_amount, settlement_notified
) VALUES (
  'req-{order_id}', SHA2('{order_id}', 256), 'PREPAY_READY',
  '{user_id}', '{PRODUCT_ID}', '{PRODUCT_CODE}', '{PRODUCT_NAME}', {BASE_QUOTA},
  '{order_id}', NOW(), 10.80, 'PAY_SUCCESS', NOW(), 1,
  {ACTIVITY_ID}, '{team_id}', '{SOURCE}', '{CHANNEL}',
  1.20, 10.80, 0
)
""")


def parse_kv(out: str) -> dict[str, str]:
    data: dict[str, str] = {}
    for line in out.splitlines():
        line = line.strip()
        if not line or "\t" not in line:
            continue
        key, value = line.split("\t", 1)
        data[key] = value
    return data


def counts(order_id: str, user_id: str) -> dict[str, str]:
    return parse_kv(mysql_execute(f"""
SELECT 'pay_status', IFNULL(status,'MISSING') FROM s_pay_mall_ddd_market.pay_order WHERE order_id='{order_id}';
SELECT 'outbox', COUNT(*) FROM s_pay_mall_ddd_market.benefit_event WHERE order_id='{order_id}';
SELECT 'outbox_sent', COUNT(*) FROM s_pay_mall_ddd_market.benefit_event
  WHERE order_id='{order_id}' AND publish_status='SENT';
SELECT 'grants', COUNT(*) FROM member_db.benefit_grant_event WHERE order_id='{order_id}';
SELECT 'grant_status', IFNULL(GROUP_CONCAT(status),'NONE')
  FROM member_db.benefit_grant_event WHERE order_id='{order_id}';
SELECT 'paid_balance', IFNULL(paid_quota_balance,0) FROM member_db.quota_account WHERE user_id={user_id}
"""))


def wait_settled(order_id: str, user_id: str, timeout_s: float = 90.0) -> dict[str, str]:
    deadline = time.time() + timeout_s
    last: dict[str, str] = {}
    while time.time() < deadline:
        last = counts(order_id, user_id)
        if last.get("pay_status") == "MARKET" and last.get("grants") == "1" and last.get("outbox_sent") == "1":
            return last
        time.sleep(0.1)
    raise BenchError(f"settle timeout order={order_id} last={last}")


def wait_count(sql: str, want: int, timeout_s: float) -> float:
    deadline = time.time() + timeout_s
    t0 = time.perf_counter()
    got = "0"
    while time.time() < deadline:
        out = mysql_execute(sql).strip()
        got = out.splitlines()[-1] if out else "0"
        if got == str(want):
            return time.perf_counter() - t0
        time.sleep(0.1)
    raise BenchError(f"wait_count timeout got={got} want={want} sql={sql[:160]}")


def publish_pool(payloads: list[tuple[str, str]], workers: int) -> float:
    """Campus-dash style: fixed pool drains N tasks; no latch-aligned spike."""
    prod = make_producer()
    errors: list[str] = []
    lock = threading.Lock()

    def one(item: tuple[str, str]) -> None:
        key, value = item
        try:
            prod.send(TOPIC, key=key, value=value).get(timeout=15)
        except Exception as exc:  # noqa: BLE001
            with lock:
                errors.append(str(exc))

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=max(1, min(workers, len(payloads) or 1))) as pool:
        futs = [pool.submit(one, item) for item in payloads]
        for fut in as_completed(futs):
            fut.result()
    prod.flush(15)
    prod.close()
    elapsed = time.perf_counter() - t0
    if errors:
        raise BenchError(f"publish errors ({len(errors)}): {errors[:3]}")
    return elapsed


def tps(count: int, elapsed_s: float) -> int:
    elapsed_ms = max(int(elapsed_s * 1000), 1)
    return int(round(count * 1000.0 / elapsed_ms))


def run_idempotency(concurrency: int, workers: int, report: dict) -> None:
    prefix = f"bench-set-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
    user_id = str(9000001000000 + (int(time.time()) % 100000))
    order_id = f"{prefix}-order-idem"
    team_id = f"{prefix}-team-idem"
    seed_order(user_id, order_id, team_id)
    payload = team_payload(team_id, user_id, order_id)
    publish_pool([(team_id, payload)], workers=1)
    first = wait_settled(order_id, user_id)
    elapsed = publish_pool([(team_id, payload) for _ in range(concurrency)], workers=workers)
    time.sleep(2)
    final = counts(order_id, user_id)
    ok = (
        final.get("pay_status") == "MARKET"
        and final.get("grants") == "1"
        and final.get("outbox") == "1"
        and final.get("outbox_sent") == "1"
        and final.get("paid_balance") == str(EXPECTED_MICRO)
    )
    report["idempotency"] = {
        "prefix": prefix,
        "userId": user_id,
        "concurrency": concurrency,
        "workers": workers,
        "publishElapsedSec": round(elapsed, 3),
        "first": first,
        "final": final,
        "expectedMicro": EXPECTED_MICRO,
        "passed": ok,
        "zeroDuplicateGrant": final.get("grants") == "1",
    }
    if not ok:
        raise BenchError(f"idempotency failed: {final}")


def run_throughput(orders: int, workers: int, report: dict) -> None:
    prefix = f"bench-tps-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
    user_base = 9000003000000 + (int(time.time()) % 100000) * 1000
    rows = []
    for i in range(orders):
        user_id = str(user_base + i)
        order_id = f"{prefix}-o{i:04d}"
        team_id = f"{prefix}-t{i:04d}"
        seed_order(user_id, order_id, team_id)
        rows.append((user_id, order_id, team_id))
    payloads = [(team_id, team_payload(team_id, user_id, order_id)) for user_id, order_id, team_id in rows]

    t0 = time.perf_counter()
    produce_s = publish_pool(payloads, workers=workers)
    pay_s = wait_count(
        f"SELECT COUNT(*) FROM s_pay_mall_ddd_market.pay_order "
        f"WHERE order_id LIKE '{prefix}-o%' AND status='MARKET'",
        orders,
        timeout_s=180,
    )
    member_s = wait_count(
        f"SELECT COUNT(*) FROM member_db.benefit_grant_event WHERE order_id LIKE '{prefix}-o%'",
        orders,
        timeout_s=180,
    )
    total_s = time.perf_counter() - t0

    out = mysql_execute(
        "SELECT COUNT(*) FROM member_db.benefit_grant_event WHERE order_id LIKE "
        f"'{prefix}-o%';\n"
        "SELECT COUNT(*) FROM s_pay_mall_ddd_market.pay_order WHERE order_id LIKE "
        f"'{prefix}-o%' AND status='MARKET';\n"
        "SELECT COUNT(*) FROM s_pay_mall_ddd_market.benefit_event WHERE order_id LIKE "
        f"'{prefix}-o%' AND publish_status='SENT';\n"
        "SELECT COUNT(*) FROM member_db.quota_ledger l "
        "JOIN member_db.benefit_grant_event g ON g.user_id = l.user_id "
        f"AND g.order_id LIKE '{prefix}-o%' WHERE l.type='GRANT'"
    ).splitlines()
    grants, market, sent, ledgers = (out + ["0"] * 4)[:4]
    ok = grants == market == sent == ledgers == str(orders)
    report["throughput"] = {
        "prefix": prefix,
        "orders": orders,
        "workers": workers,
        "produceMs": int(produce_s * 1000),
        "payMarketMs": int(pay_s * 1000),
        "memberGrantMs": int(member_s * 1000),
        "totalElapsedMs": int(total_s * 1000),
        "e2eTps": tps(orders, total_s),
        "grants": int(grants),
        "marketOrders": int(market),
        "sentOutbox": int(sent),
        "grantLedgers": int(ledgers),
        "zeroDuplicate": ok,
        "passed": ok,
    }
    if not ok:
        raise BenchError(f"throughput failed counts={out}")


def run_pay_market_slice(orders: int, workers: int, report: dict) -> None:
    """MySQL CAS PAY_SUCCESS→MARKET + outbox insert (campus-dash-like money path)."""
    # Cap DB workers: concurrent PyMySQL + service pools can exhaust MySQL max_connections.
    db_workers = max(1, min(workers, 20, orders))
    prefix = f"bench-slice-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}"
    user_base = 9000004000000 + (int(time.time()) % 100000) * 1000
    rows = []
    for i in range(orders):
        user_id = str(user_base + i)
        order_id = f"{prefix}-o{i:04d}"
        team_id = f"{prefix}-t{i:04d}"
        seed_order(user_id, order_id, team_id)
        rows.append((user_id, order_id, team_id))

    errors: list[str] = []
    lock = threading.Lock()
    settled = 0

    def one(row: tuple[str, str, str]) -> None:
        nonlocal settled
        user_id, order_id, team_id = row
        event_id = str(uuid.uuid4())
        try:
            mysql_execute(f"""
UPDATE s_pay_mall_ddd_market.pay_order
SET status='MARKET', update_time=NOW()
WHERE order_id='{order_id}' AND user_id='{user_id}'
  AND group_team_id='{team_id}' AND group_activity_id={ACTIVITY_ID}
  AND group_source='{SOURCE}' AND group_channel='{CHANNEL}'
  AND market_type=1 AND status='PAY_SUCCESS';
INSERT INTO s_pay_mall_ddd_market.benefit_event(
  event_id, event_type, user_id, order_id, product_code, event_published, publish_status, base_quota, create_time, update_time
) VALUES (
  '{event_id}', 'GROUP_BUY_COMPLETED', {user_id}, '{order_id}', '{PRODUCT_CODE}', 0, 'PENDING', {BASE_QUOTA}, NOW(), NOW()
)
""")
            with lock:
                settled += 1
        except Exception as exc:  # noqa: BLE001
            with lock:
                errors.append(str(exc))

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=db_workers) as pool:
        futs = [pool.submit(one, row) for row in rows]
        for fut in as_completed(futs):
            fut.result()
    elapsed = time.perf_counter() - t0
    market = mysql_execute(
        f"SELECT COUNT(*) FROM s_pay_mall_ddd_market.pay_order "
        f"WHERE order_id LIKE '{prefix}-o%' AND status='MARKET'"
    ).strip().splitlines()[-1]
    outbox = mysql_execute(
        f"SELECT COUNT(*) FROM s_pay_mall_ddd_market.benefit_event "
        f"WHERE order_id LIKE '{prefix}-o%'"
    ).strip().splitlines()[-1]
    ok = market == outbox == str(orders) and not errors
    report["payMarketSlice"] = {
        "prefix": prefix,
        "orders": orders,
        "workers": db_workers,
        "elapsedMs": int(elapsed * 1000),
        "payMarketTps": tps(orders, elapsed),
        "marketOrders": int(market),
        "outboxRows": int(outbox),
        "workerSettled": settled,
        "errors": errors[:3],
        "passed": ok,
        "note": "MySQL CAS+outbox only; no Kafka/Member. Closest to campus-dash SettleConcurrencyIT money path.",
    }
    if not ok:
        raise BenchError(
            f"pay market slice failed market={market} outbox={outbox} errors={errors[:3]}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--concurrency", type=int, default=500)
    parser.add_argument("--workers", type=int, default=64)
    parser.add_argument("--orders", type=int, default=500)
    parser.add_argument("--skip-e2e", action="store_true")
    parser.add_argument("--skip-slice", action="store_true")
    parser.add_argument("--skip-idempotency", action="store_true")
    args = parser.parse_args()

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ-") + uuid.uuid4().hex[:8]
    report_root = Path(env("SETTLEMENT_BENCH_REPORT_ROOT", ""))
    if not str(report_root):
        # Host layout: .../jmeter/plans/this.py → .../jmeter/reports/settlement-bench
        report_root = Path(__file__).resolve().parents[1] / "reports" / "settlement-bench"
    report_dir = report_root / run_id
    report_dir.mkdir(parents=True, exist_ok=False)
    report: dict = {
        "runId": run_id,
        "project": PROJECT,
        "topic": TOPIC,
        "inNetwork": IN_NETWORK,
        "kafkaBootstrap": KAFKA_BOOTSTRAP,
        "passed": False,
        "note": "E2E is team_success→Pay→Member; payMarketSlice is MySQL CAS+outbox only (campus-dash-like).",
    }
    try:
        check_bench()
        if not args.skip_idempotency:
            run_idempotency(args.concurrency, args.workers, report)
        if not args.skip_e2e:
            run_throughput(args.orders, args.workers, report)
        if not args.skip_slice:
            run_pay_market_slice(args.orders, args.workers, report)
        report["passed"] = all(
            report.get(section, {}).get("passed", True)
            for section in ("idempotency", "throughput", "payMarketSlice")
            if section in report
        )
    except BenchError as exc:
        report["error"] = str(exc)

    (report_dir / "summary.json").write_text(
        json.dumps(report, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(f"SETTLEMENT_BENCH_REPORT={report_dir / 'summary.json'}")
    print(f"SETTLEMENT_BENCH_EXIT={0 if report.get('passed') else 1}")
    if report.get("idempotency"):
        idem = report["idempotency"]
        print(
            f"IDEMPOTENCY concurrency={idem['concurrency']} grants={idem['final'].get('grants')} "
            f"passed={idem['passed']}"
        )
    if report.get("throughput"):
        thr = report["throughput"]
        print(
            f"E2E orders={thr['orders']} e2eTps={thr['e2eTps']} elapsedMs={thr['totalElapsedMs']} "
            f"produceMs={thr['produceMs']} payMarketMs={thr['payMarketMs']} "
            f"memberGrantMs={thr['memberGrantMs']} passed={thr['passed']}"
        )
    if report.get("payMarketSlice"):
        slice_ = report["payMarketSlice"]
        print(
            f"PAY_SLICE orders={slice_['orders']} payMarketTps={slice_['payMarketTps']} "
            f"elapsedMs={slice_['elapsedMs']} passed={slice_['passed']}"
        )
    if report.get("error"):
        print(f"ERROR={report['error']}", file=sys.stderr)
    return 0 if report.get("passed") else 1


if __name__ == "__main__":
    sys.exit(main())
