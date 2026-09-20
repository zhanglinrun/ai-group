#!/usr/bin/env python3
"""Classify one-shot fixed-team lock responses from a JMeter CSV JTL."""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from summarize_group_lock_jtl import LABEL, percentile

EXPECTED_REJECTS = {"E0005", "E0006", "E0008"}
ALLOWED = EXPECTED_REJECTS | {"0000"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jtl", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--threads", type=int, required=True)
    parser.add_argument("--rampup", type=int, required=True)
    parser.add_argument("--team-id", required=True)
    parser.add_argument("--activity-id", required=True)
    parser.add_argument("--goods-id", required=True)
    parser.add_argument("--expected-free-slots", type=int, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8091")
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    if (args.threads < 1 or args.rampup < 0 or args.expected_free_slots < 1
            or args.expected_free_slots > args.threads or not all((args.team_id, args.activity_id, args.goods_id))):
        parser.error("threads and expected-free-slots must be positive, slots <= threads, and all target IDs nonempty")

    if not re.fullmatch(r"[0-9]{17}", args.run_id):
        parser.error("run-id must be 17 digits (yyyyMMddHHmmssfff)")
    with Path(args.jtl).open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames or not {"label", "businessCode", "outTradeNo", "responseCode", "success", "elapsed", "timeStamp"}.issubset(reader.fieldnames):
            parser.error("JTL needs CSV columns label,businessCode,outTradeNo,responseCode,success,elapsed,timeStamp; run with -Jsample_variables=businessCode,outTradeNo")
        all_rows = list(reader)
        rows = [row for row in all_rows if row["label"] == LABEL]

    codes = Counter(row["businessCode"] or "<missing>" for row in rows)
    http_errors = sum(row["responseCode"] != "200" for row in rows)
    unexpected_business = sum(row["businessCode"] not in ALLOWED for row in rows)
    failed_assertions = sum(row["success"].lower() != "true" for row in rows)
    errors = sum(row["responseCode"] != "200" or row["businessCode"] not in ALLOWED
                 or row["success"].lower() != "true" for row in rows)
    accepted = sum(row["businessCode"] == "0000" and row["responseCode"] == "200"
                   and row["success"].lower() == "true" for row in rows)
    rejects = sum(row["businessCode"] in EXPECTED_REJECTS and row["responseCode"] == "200"
                  and row["success"].lower() == "true" for row in rows)
    prefix = f"jmeter-spike-{args.run_id}-"
    keys = [row["outTradeNo"] for row in rows]
    valid_keys = (len(set(keys)) == len(keys) and all(re.fullmatch(re.escape(prefix) + r"[0-9a-f]{32}", key or "") for key in keys))
    accepted_rows = [row for row in rows if row["businessCode"] == "0000" and row["responseCode"] == "200" and row["success"].lower() == "true"]
    rejected_rows = [row for row in rows if row["businessCode"] in EXPECTED_REJECTS and row["responseCode"] == "200" and row["success"].lower() == "true"]
    accepted_latencies = [float(row["elapsed"]) for row in accepted_rows]
    rejected_latencies = [float(row["elapsed"]) for row in rejected_rows]
    latencies = [float(row["elapsed"]) for row in rows]
    started = [int(row["timeStamp"]) for row in rows]
    start_spread_ms = max(started) - min(started) if started else 0
    synchronized = args.rampup == 0 and start_spread_ms <= 5000
    elapsed_sec = max(0.001, (max(start + latency for start, latency in zip(started, latencies))
                              - min(started)) / 1000) if rows else 0.001
    report = {
        "schemaVersion": 2,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "benchmarkType": "jmeter-group-lock-fixed-team-spike",
        "target": args.base_url.rstrip("/") + "/api/v1/gbm/trade/lock_market_pay_order",
        "scenario": {
            "tool": "Apache JMeter", "teamId": args.team_id,
            "activityId": args.activity_id, "goodsId": args.goods_id, "runId": args.run_id,
            "threads": args.threads, "rampUpSec": args.rampup, "expectedFreeSlots": args.expected_free_slots,
            "source": "s01", "channel": "c01", "orderPrice": "12.00",
            "barrierTimeoutMs": 120000,
            "requestsPerThread": 1, "warmupRequests": 0,
            "excluded": "registration, login, Gateway, Pay service, Alipay and payment callback",
        },
        "results": {
            "requests": len(rows), "requestCountMatchesThreads": len(rows) == args.threads and len(all_rows) == len(rows),
            "startSpreadMs": start_spread_ms, "synchronizedWithin5Sec": synchronized,
            "acceptedLocks": accepted, "expectedFullTeamRejects": rejects,
            "uniqueValidOutTradeNos": valid_keys,
            "businessCodes": dict(sorted(codes.items())),
            "httpErrors": http_errors, "unexpectedBusinessCodes": unexpected_business,
            "failedAssertions": failed_assertions, "errors": errors,
            "errorRatePct": round(100.0 * errors / max(1, len(rows)), 2),
            "throughputTps": round(len(rows) / elapsed_sec, 1),
            "latencyAvgMs": round(statistics.mean(latencies), 1) if rows else 0,
            "latencyP95Ms": round(percentile(latencies, 95), 1),
            "latencyP99Ms": round(percentile(latencies, 99), 1),
            "acceptedLatencyAvgMs": round(statistics.mean(accepted_latencies), 1) if accepted_latencies else 0,
            "acceptedLatencyP95Ms": round(percentile(accepted_latencies, 95), 1),
            "acceptedLatencyP99Ms": round(percentile(accepted_latencies, 99), 1),
            "rejectedLatencyAvgMs": round(statistics.mean(rejected_latencies), 1) if rejected_latencies else 0,
            "rejectedLatencyP95Ms": round(percentile(rejected_latencies, 95), 1),
            "rejectedLatencyP99Ms": round(percentile(rejected_latencies, 99), 1),
        },
        "successDefinition": "One response per thread, exactly expectedFreeSlots accepted locks, unique run-scoped keys, zero transport/assertion errors, request starts within 5 seconds and independent DB verification. Full-team rejections are not locks.",
    }
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"GROUP_LOCK_FIXED_TEAM_REPORT={output}")
    return 0 if rows and len(rows) == len(all_rows) == args.threads and errors == 0 and accepted == args.expected_free_slots and valid_keys and synchronized else 1


if __name__ == "__main__":
    raise SystemExit(main())
