#!/usr/bin/env python3
"""Summarize the parent transaction samples from the login/group-order JTL."""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from datetime import datetime, timezone
from pathlib import Path


TRANSACTION_LABEL = "Register + login + group order"


def percentile(values: list[float], percentage: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round((percentage / 100) * (len(ordered) - 1)))))
    return ordered[index]


def load_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jtl", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--threads", type=int, required=True)
    parser.add_argument("--loops", type=int, required=True)
    parser.add_argument("--rampup", type=int, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()

    rows = load_rows(Path(args.jtl))
    transactions = [row for row in rows if row.get("label") == TRANSACTION_LABEL]
    latencies = [float(row["elapsed"]) for row in transactions]
    errors = sum(1 for row in transactions if str(row.get("success", "")).lower() != "true")
    started = [int(row["timeStamp"]) for row in transactions if row.get("timeStamp", "").isdigit()]
    completed = [start + latency for start, latency in zip(started, latencies)]
    elapsed_sec = max(0.001, (max(completed) - min(started)) / 1000) if started else 0.001

    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "benchmarkType": "jmeter-gateway-login-group-order",
        "target": args.base_url.rstrip("/") + "/api/v1/alipay/create_pay_order",
        "scenario": {
            "tool": "Apache JMeter",
            "transaction": "register test account -> login -> create group order (includes Group lock)",
            "threads": args.threads,
            "loopsPerThread": args.loops,
            "rampUpSec": args.rampup,
            "paymentProvider": "disabled; no QR-code, redirect, callback, or sync-settlement request",
        },
        "results": {
            "transactions": len(transactions),
            "errors": errors,
            "errorRatePct": round(100.0 * errors / max(1, len(transactions)), 2),
            "throughputTps": round(len(transactions) / elapsed_sec, 1),
            "latencyAvgMs": round(statistics.mean(latencies), 1) if latencies else 0,
            "latencyP95Ms": round(percentile(latencies, 95), 1),
            "latencyP99Ms": round(percentile(latencies, 99), 1),
        },
        "successDefinition": "HTTP and business assertions pass for register, login, and group order creation.",
    }
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"LOGIN_GROUP_ORDER_REPORT={output}")
    return 0 if transactions and errors == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
