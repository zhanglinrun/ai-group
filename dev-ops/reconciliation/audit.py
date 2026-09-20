#!/usr/bin/env python3
"""Read-only cross-schema audit for the isolated ai-group-bench MySQL container."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

MYSQL = "ai-group-bench-mysql-1"
PROJECT = "ai-group-bench"
NAMES = frozenset({
    "FORMED_GROUP_PAY_STUCK",
    "LEGACY_GROUP_IDENTITY_MISSING",
    "ELIGIBLE_PAY_MISSING_COMPLETED_OUTBOX",
    "SENT_COMPLETED_WITHOUT_MEMBER_RECORD",
    "REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT",
    "PAY_BENEFIT_RECONCILIATION_MANUAL",
})


class AuditError(Exception):
    pass


def command(*argv: str, input_text: str | None = None) -> str:
    try:
        result = subprocess.run(argv, input=input_text, capture_output=True, text=True,
                                encoding="utf-8", timeout=120, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise AuditError("Docker/MySQL command unavailable or timed out") from exc
    if result.returncode:
        # mysql and Docker stderr can contain credentials, SQL, or other local details.
        raise AuditError("Docker/MySQL query failed; inspect the bench container privately")
    return result.stdout


def check_bench() -> None:
    try:
        labels = json.loads(command("docker", "inspect", "--format", "{{json .Config.Labels}}", MYSQL))
        running = json.loads(command("docker", "inspect", "--format", "{{json .State.Running}}", MYSQL))
    except (ValueError, TypeError) as exc:
        raise AuditError("Malformed Docker inspection response") from exc
    if not isinstance(labels, dict) or labels.get("com.docker.compose.project") != PROJECT \
            or labels.get("com.docker.compose.service") != "mysql" or running is not True:
        raise AuditError("Target is not the running ai-group-bench Compose MySQL container")


def make_sql(window_hours: int, limit: int) -> str:
    if not 1 <= window_hours <= 168 or not 1 <= limit <= 500:
        raise AuditError("window-hours must be 1..168 and limit must be 1..500")
    try:
        template = Path(__file__).with_name("audit.sql").read_text(encoding="utf-8")
    except OSError as exc:
        raise AuditError("Audit SQL template is unavailable") from exc
    return (template.replace("__WINDOW_HOURS__", str(window_hours))
                    .replace("__LIMIT_PLUS_ONE__", str(limit + 1)))


def eligible_group(row: dict) -> bool:
    return (row.get("groupStatus") in (1, 3) and row.get("detailStatus") == 1
            and isinstance(row.get("teamId"), str) and bool(row["teamId"])
            and row.get("memberRevokedStatus") is None)


def valid_anomaly(row: dict) -> bool:
    name = row.get("name")
    pay_status = row.get("payStatus")
    if name == "FORMED_GROUP_PAY_STUCK":
        return eligible_group(row) and pay_status == "PAY_SUCCESS"
    if name == "LEGACY_GROUP_IDENTITY_MISSING":
        return (row.get("groupStatus") in (1, 3) and row.get("detailStatus") == 1
                and isinstance(row.get("teamId"), str) and bool(row["teamId"])
                and pay_status in ("PAY_SUCCESS", "MARKET", "DEAL_DONE", "WAIT_REFUND")
                and "groupSource" in row and "groupChannel" in row
                and (row["groupSource"] is None or row["groupChannel"] is None)
                and isinstance(row.get("matchingDetails"), int) and row["matchingDetails"] >= 1
                and ((row["matchingDetails"] == 1
                      and bool(row.get("detailSource")) and bool(row.get("detailChannel")))
                     or (row["matchingDetails"] > 1
                         and row.get("detailSource") is None and row.get("detailChannel") is None)))
    if name == "ELIGIBLE_PAY_MISSING_COMPLETED_OUTBOX":
        return (eligible_group(row) and pay_status in ("MARKET", "DEAL_DONE")
                and row.get("completedEventId") is None)
    if name == "SENT_COMPLETED_WITHOUT_MEMBER_RECORD":
        return (eligible_group(row) and pay_status in ("MARKET", "DEAL_DONE")
                and row.get("eventPublished") == 1 and row.get("publishStatus") == "SENT"
                and isinstance(row.get("completedEventId"), str)
                and row.get("memberCompletedStatus") is None)
    if name == "REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT":
        return (pay_status in ("CLOSE", "WAIT_REFUND")
                and row.get("memberRevokedStatus") not in ("REVOKED", "SKIPPED_REVOKED")
                and (row.get("memberCompletedStatus") in ("GRANTED", "REJECTED_GRANTED")
                     or row.get("memberRevokedStatus") == "REJECTED_GRANTED"))
    return name == "PAY_BENEFIT_RECONCILIATION_MANUAL" \
        and row.get("reconciliationOutcome") == "MANUAL" and bool(row.get("eventId"))


def parse_snapshot(output: str, limit: int) -> tuple[dict, list[dict], bool]:
    try:
        rows = [json.loads(line) for line in output.splitlines()]
        if not rows or not isinstance(rows[0], dict) or rows[0].get("kind") != "meta":
            raise ValueError("missing snapshot metadata")
        meta = rows.pop(0)
        if not all(isinstance(meta.get(key), str) and meta[key]
                   for key in ("serverUuid", "asOf", "windowStart", "cutoff")):
            raise ValueError("incomplete snapshot metadata")
        for row in rows:
            if (not isinstance(row, dict) or row.get("kind") != "anomaly"
                    or row.get("name") not in NAMES
                    or (not isinstance(row.get("orderId"), str) or not row["orderId"])
                    and not (row.get("name") == "PAY_BENEFIT_RECONCILIATION_MANUAL"
                             and row.get("orderId") is None)
                    or not valid_anomaly(row)):
                raise ValueError("invalid anomaly row")
        if len(rows) > limit + 1:
            raise ValueError("MySQL returned more than the bounded result set")
    except (ValueError, TypeError, KeyError) as exc:
        raise AuditError("Malformed or incomplete MySQL audit snapshot") from exc
    return meta, rows[:limit], len(rows) > limit


def capture(window_hours: int, limit: int) -> tuple[dict, list[dict], bool]:
    sql = make_sql(window_hours, limit)
    check_bench()
    output = command("docker", "exec", "-i", MYSQL, "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot '
                     '--batch --raw --skip-column-names --default-character-set=utf8mb4',
                     input_text=sql)
    return parse_snapshot(output, limit)


def run(window_hours: int, limit: int, report_root: Path) -> tuple[int, Path]:
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ-") + uuid.uuid4().hex[:8]
    report_dir = report_root / run_id
    report_dir.mkdir(parents=True, exist_ok=False)
    report = {"runId": run_id, "project": PROJECT, "container": MYSQL,
              "ageMinutes": 10, "windowHours": window_hours, "limit": limit,
              "passed": False, "truncated": False, "anomalyCount": 0, "counts": {},
              "anomalies": []}
    try:
        meta, anomalies, truncated = capture(window_hours, limit)
        report["snapshot"] = meta
        report["anomalies"] = anomalies
        report["truncated"] = truncated
        report["anomalyCount"] = len(anomalies)
        report["counts"] = dict(Counter(row["name"] for row in anomalies))
        report["passed"] = not anomalies and not truncated
    except AuditError as exc:
        report["error"] = str(exc)
    (report_dir / "audit.json").write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n",
                                           encoding="utf-8")
    return (0 if report["passed"] else 1), report_dir / "audit.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--window-hours", type=int, default=24)
    parser.add_argument("--limit", type=int, default=100)
    args = parser.parse_args()
    status, report_path = run(args.window_hours, args.limit, Path(__file__).with_name("reports"))
    print(f"AUDIT_REPORT={report_path}")
    print(f"AUDIT_EXIT={status}")
    return status

if __name__ == "__main__":
    sys.exit(main())
