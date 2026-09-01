#!/usr/bin/env python3
"""Summarize the single Group lock-order sampler from a JMeter JTL."""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from datetime import datetime, timezone
from pathlib import Path


LABEL = "POST Group lock_market_pay_order"


def percentile(values: list[float], percentage: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round((percentage / 100) * (len(ordered) - 1)))))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jtl", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--threads", type=int, required=True)
    parser.add_argument("--duration", type=int, required=True)
    parser.add_argument("--rampup", type=int, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8091")
    args = parser.parse_args()

    with Path(args.jtl).open(encoding="utf-8", newline="") as handle:
        rows = [row for row in csv.DictReader(handle) if row.get("label") == LABEL]
    latencies = [float(row["elapsed"]) for row in rows]
    errors = sum(1 for row in rows if str(row.get("success", "")).lower() != "true")
    started = [int(row["timeStamp"]) for row in rows if row.get("timeStamp", "").isdigit()]
    completed = [start + latency for start, latency in zip(started, latencies)]
    elapsed_sec = max(0.001, (max(completed) - min(started)) / 1000) if started else 0.001
    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "benchmarkType": "jmeter-group-lock-only",
        "target": args.base_url.rstrip("/") + "/api/v1/gbm/trade/lock_market_pay_order",
        "scenario": {
            "tool": "Apache JMeter",
            "transaction": "authenticated Group lock endpoint only; every request opens a new team",
            "threads": args.threads,
            "durationSec": args.duration,
            "rampUpSec": args.rampup,
            "excluded": "registration, login, Gateway, Pay service, Alipay and payment callback",
        },
        "results": {
            "requests": len(rows),
            "errors": errors,
            "errorRatePct": round(100.0 * errors / max(1, len(rows)), 2),
            "throughputTps": round(len(rows) / elapsed_sec, 1),
            "latencyAvgMs": round(statistics.mean(latencies), 1) if latencies else 0,
            "latencyP95Ms": round(percentile(latencies, 95), 1),
            "latencyP99Ms": round(percentile(latencies, 99), 1),
        },
        "successDefinition": "HTTP 200 and business code 0000.",
    }
    output = Path(args.report)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"GROUP_LOCK_ONLY_REPORT={output}")
    return 0 if rows and errors == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
