#!/usr/bin/env python3
"""Turn a JMeter JTL into the same resume JSON shape as quota-throughput.py."""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from datetime import datetime, timezone
from pathlib import Path


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round((pct / 100) * (len(ordered) - 1)))))
    return ordered[index]


def load_rows(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        sample = handle.read(2048)
        handle.seek(0)
        if "timeStamp" in sample and "elapsed" in sample:
            return list(csv.DictReader(handle))
        rows = []
        for raw in handle:
            parts = raw.strip().split(",")
            if len(parts) < 8:
                continue
            rows.append(
                {
                    "elapsed": parts[1],
                    "label": parts[2],
                    "success": parts[7],
                }
            )
        return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jtl", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--label", default="Freeze unique requestId")
    parser.add_argument("--threads", type=int, default=50)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--base-url", default="http://127.0.0.1:18082")
    args = parser.parse_args()

    all_rows = load_rows(Path(args.jtl))
    rows = [row for row in all_rows if row.get("label") == args.label]
    latencies = [float(row["elapsed"]) for row in rows]
    errors = sum(1 for row in rows if str(row.get("success", "")).lower() != "true")
    stamps = [int(row["timeStamp"]) for row in rows if row.get("timeStamp", "").isdigit()]
    elapsed_sec = max(0.001, (max(stamps) - min(stamps)) / 1000 if len(stamps) >= 2 else args.duration)
    idem = [row for row in all_rows if row.get("label") == "Freeze same requestId"]
    idem_ok = sum(1 for row in idem if str(row.get("success", "")).lower() == "true")

    report = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "benchmarkType": "jmeter-http-freeze-throughput",
        "target": f"{args.base_url.rstrip('/')}/internal/member/quota/reservations",
        "scenario": {
            "tool": "Apache JMeter",
            "threads": args.threads,
            "durationSec": args.duration,
            "requestId": "unique-per-request",
            "ownerService": "legacy",
        },
        "idempotency": {
            "requests": len(idem),
            "successes": idem_ok,
            "allSucceeded": bool(idem) and idem_ok == len(idem),
        },
        "results": {
            "requests": len(latencies),
            "errors": errors,
            "errorRatePct": round(100.0 * errors / max(1, len(latencies)), 2),
            "throughputTps": round(len(latencies) / elapsed_sec, 1),
            "latencyAvgMs": round(statistics.mean(latencies), 1) if latencies else 0,
            "latencyP95Ms": round(percentile(latencies, 95), 1),
            "latencyP99Ms": round(percentile(latencies, 99), 1),
        },
        "methodology": (
            "JMeter non-GUI against member-service /internal freeze. "
            "Not Gateway, not Agent. Success = HTTP 200 and body contains code 200. "
            "Ledger invariants are still proven by QuotaConcurrencyBenchmarkIT, not by this JTL."
        ),
    }
    out = Path(args.report)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"RESUME_THROUGHPUT_REPORT={out}")
    if not latencies:
        return 1
    return 0 if (errors / max(1, len(latencies))) < 0.05 else 1


if __name__ == "__main__":
    raise SystemExit(main())
