#!/usr/bin/env python3
"""Read-only Docker/MySQL pre/post correctness gate for an isolated fixed-team spike."""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
from collections import Counter
from pathlib import Path

from summarize_group_lock_jtl import LABEL

MYSQL = "ai-group-bench-mysql-1"
GROUP = "ai-group-bench-group-service-1"
PREFIX = "jmeter-spike-"


class GateError(Exception):
    pass


def validate_ids(team_id: str, activity_id: str, goods_id: str, run_id: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", team_id):
        raise GateError("Invalid team ID (ASCII letters/digits/_/- only, max 64)")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,16}", goods_id):
        raise GateError("Invalid goods ID (ASCII letters/digits/_/- only, max 16)")
    if not re.fullmatch(r"[1-9][0-9]{0,18}", activity_id) or int(activity_id) > 9223372036854775807:
        raise GateError("Invalid positive bigint activity ID")
    if not re.fullmatch(r"[0-9]{17}", run_id):
        raise GateError("Invalid 17-digit run ID")


def command(*argv: str, input_text: str | None = None) -> str:
    try:
        result = subprocess.run(argv, input=input_text, text=True, capture_output=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise GateError("Docker/MySQL unavailable") from exc
    if result.returncode:
        # Never expose Docker/MySQL stderr: it may contain credentials or SQL.
        raise GateError("Docker/MySQL command failed; check bench containers and DB credentials privately")
    return result.stdout


def inspect(container: str, field: str) -> dict:
    try:
        return json.loads(command("docker", "inspect", "--format", field, container))
    except (ValueError, TypeError) as exc:
        raise GateError("Invalid Docker inspection result") from exc


def check_stack(port: int) -> None:
    for container, service in ((MYSQL, "mysql"), (GROUP, "group-service")):
        labels = inspect(container, "{{json .Config.Labels}}")
        if (labels.get("com.docker.compose.project") != "ai-group-bench"
                or labels.get("com.docker.compose.service") != service):
            raise GateError("Container is not from the ai-group-bench Compose project")
    ports = inspect(GROUP, "{{json .NetworkSettings.Ports}}")
    bindings = ports.get("8091/tcp") or []
    if not any(str(binding.get("HostPort")) == str(port) for binding in bindings):
        raise GateError("Group target port is not published by the bench Group container")
    env = inspect(GROUP, "{{json .Config.Env}}")
    if ("MYSQL_HOST=mysql" not in env or not any(
            item.startswith("SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/group_buy_market?") for item in env)):
        raise GateError("Bench Group is not configured for the expected MySQL database")


def capture(team_id: str, activity_id: str, goods_id: str, run_id: str, port: int) -> dict:
    validate_ids(team_id, activity_id, goods_id, run_id)
    check_stack(port)
    sql = (f"SET @team_id = '{team_id}';\nSET @goods_id = '{goods_id}';\n"
           f"SET @run_id = '{run_id}';\n"
           + Path(__file__).with_name("verify_group_lock_fixed_team.sql").read_text(encoding="utf-8"))
    output = command("docker", "exec", "-i", MYSQL, "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --database=group_buy_market --batch --raw --skip-column-names',
                     input_text=sql)
    snapshot = {"identity": [], "team": [], "mapping": [], "detail": [], "outbox": []}
    try:
        for line in output.splitlines():
            row = json.loads(line)
            snapshot[row["kind"]].append(row)
    except (ValueError, KeyError, TypeError) as exc:
        raise GateError("Malformed or incomplete MySQL snapshot") from exc
    if len(snapshot["identity"]) != 1 or snapshot["identity"][0]["database"] != "group_buy_market":
        raise GateError("MySQL database mismatch")
    return snapshot


def check_snapshot(snapshot: dict, team_id: str, activity_id: str, goods_id: str, run_id: str,
                   expected_slots: int | None = None) -> list[str]:
    issues = []
    if len(snapshot["identity"]) != 1 or snapshot["identity"][0].get("database") != "group_buy_market" or not snapshot["identity"][0].get("serverUuid"):
        return ["Snapshot database or server identity mismatch"]
    teams, mappings = snapshot["team"], snapshot["mapping"]
    if len(teams) != 1 or len(mappings) != 1:
        return ["Expected exactly one team and one goods mapping"]
    team, mapping = teams[0], mappings[0]
    if (team["teamId"] != team_id or team["activityId"] != int(activity_id)
            or team["source"] != "s01" or team["channel"] != "c01"
            or mapping["activityId"] != int(activity_id) or mapping["goodsId"] != goods_id
            or mapping["source"] != "s01" or mapping["channel"] != "c01"):
        issues.append("Team/activity/goods/source/channel mismatch")
    if team["status"] != 0 or team["unexpired"] != 1:
        issues.append("Team is not unexpired and in progress")
    target, locked, complete = (team[key] for key in ("targetCount", "lockCount", "completeCount"))
    if not (0 <= complete <= locked <= target) or (expected_slots is not None and target - locked != expected_slots):
        issues.append("Team counters or expected free slots mismatch")
    details = [row for row in snapshot["detail"] if row["teamId"] == team_id]
    if locked != sum(row["status"] in (0, 1) for row in details) or complete != sum(row["status"] == 1 for row in details):
        issues.append("Team counters disagree with active/settled details")
    if any(row["activityId"] != int(activity_id) for row in details):
        issues.append("Team detail belongs to another activity")
    for key in ("id", "outTradeNo", "orderId", "bizId"):
        if len({row[key] for row in details}) != len(details):
            issues.append("Duplicate team detail " + key)
    run_details = [row for row in snapshot["detail"] if row["outTradeNo"].startswith(PREFIX + run_id + "-")]
    for row in run_details:
        if (row["teamId"] != team_id or row["activityId"] != int(activity_id)
                or row["goodsId"] != goods_id or row["source"] != "s01" or row["channel"] != "c01"
                or row["status"] != 0 or not re.fullmatch(re.escape(PREFIX + run_id + "-") + r"[0-9a-f]{32}", row["outTradeNo"])):
            issues.append("Misplaced or invalid run-scoped detail")
    if len({row["outTradeNo"] for row in run_details}) != len(run_details):
        issues.append("Duplicate run-scoped key")
    outbox = snapshot["outbox"]
    if (len({row["uuid"] for row in outbox}) != len(outbox)
            or any(row["activityId"] != int(activity_id) for row in outbox)):
        issues.append("Outbox duplicate UUID or wrong activity")
    return issues


def verify(pre: dict, post: dict, jtl: Path, team_id: str, activity_id: str,
           goods_id: str, run_id: str, expected_slots: int) -> list[str]:
    issues = check_snapshot(pre, team_id, activity_id, goods_id, run_id, expected_slots)
    issues += check_snapshot(post, team_id, activity_id, goods_id, run_id)
    if issues:
        return issues
    before, after = pre["team"][0], post["team"][0]
    if before["completeCount"] != after["completeCount"] or after["lockCount"] - before["lockCount"] != expected_slots:
        issues.append("Settled/lock counter drift")
    static_fields = ("teamId", "activityId", "source", "channel", "status", "targetCount",
                     "originalPrice", "deductionPrice", "payPrice", "validStartTime", "validEndTime",
                     "notifyType", "notifyUrl")
    if any(before.get(key) != after.get(key) for key in static_fields):
        issues.append("Team price, window or notification config changed during run")
    if pre["mapping"] != post["mapping"]:
        issues.append("Goods mapping changed during run")
    if pre["identity"][0]["serverUuid"] != post["identity"][0]["serverUuid"]:
        issues.append("MySQL server changed between snapshots")
    if pre["outbox"] != post["outbox"]:
        issues.append("Outbox drift during lock-only run")
    old = {row["id"]: row for row in pre["detail"]}
    new = {row["id"]: row for row in post["detail"]}
    if any(new.get(key) != row for key, row in old.items()):
        issues.append("Existing detail changed or disappeared")
    prefix = PREFIX + run_id + "-"
    if any(row["outTradeNo"].startswith(prefix) for row in pre["detail"]):
        issues.append("Run prefix already present before spike")
    added = [row for key, row in new.items() if key not in old]
    run_details = [row for row in post["detail"] if row["outTradeNo"].startswith(prefix)]
    if len(added) != expected_slots or {row["id"] for row in added} != {row["id"] for row in run_details}:
        issues.append("New detail count or run scope mismatch")
    with jtl.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames or not {"label", "businessCode", "outTradeNo", "success", "responseCode"}.issubset(reader.fieldnames):
            raise GateError("JTL missing sample identifiers/business codes")
        rows = [row for row in reader if row["label"] == LABEL]
    keys = [row["outTradeNo"] for row in rows]
    if len(keys) != len(set(keys)) or any(not re.fullmatch(re.escape(prefix) + r"[0-9a-f]{32}", key or "") for key in keys):
        issues.append("JTL has duplicate, missing or wrong run-scoped key")
    accepted = [row["outTradeNo"] for row in rows if row["businessCode"] == "0000"
                and row["responseCode"] == "200" and row["success"].lower() == "true"]
    rejected = [row["outTradeNo"] for row in rows if row["businessCode"] in ("E0005", "E0006", "E0008")
                and row["responseCode"] == "200" and row["success"].lower() == "true"]
    db_keys = [row["outTradeNo"] for row in run_details]
    if len(accepted) != expected_slots or Counter(accepted) != Counter(db_keys):
        issues.append("Successful JTL keys differ from run-scoped DB details")
    if set(rejected) & set(db_keys):
        issues.append("Rejected JTL key persisted as a detail")
    return issues


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phase", choices=("pre", "post"), required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--team-id", required=True)
    parser.add_argument("--activity-id", required=True)
    parser.add_argument("--goods-id", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--expected-free-slots", type=int, required=True)
    parser.add_argument("--port", type=int, default=8091)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    result = {"passed": False, "phase": args.phase, "issues": []}
    try:
        validate_ids(args.team_id, args.activity_id, args.goods_id, args.run_id)
        if args.expected_free_slots < 1 or not 1 <= args.port <= 65535:
            raise GateError("Invalid expected free slots or Group port")
        if args.phase == "post":
            pre = json.loads((args.output_dir / "db-pre.json").read_text(encoding="utf-8"))
        snapshot = capture(args.team_id, args.activity_id, args.goods_id, args.run_id, args.port)
        (args.output_dir / f"db-{args.phase}.json").write_text(json.dumps(snapshot, indent=2), encoding="utf-8")
        if args.phase == "pre":
            result["issues"] = check_snapshot(snapshot, args.team_id, args.activity_id, args.goods_id,
                                               args.run_id, args.expected_free_slots)
            if any(row["outTradeNo"].startswith(PREFIX + args.run_id + "-") for row in snapshot["detail"]):
                result["issues"].append("Run prefix already exists")
        else:
            result["issues"] = verify(pre, snapshot, args.output_dir / "results.jtl", args.team_id,
                                      args.activity_id, args.goods_id, args.run_id, args.expected_free_slots)
        result["passed"] = not result["issues"]
    except (GateError, OSError, ValueError, KeyError, TypeError, AttributeError, IndexError) as exc:
        result["issues"] = [str(exc) if isinstance(exc, GateError) else "Snapshot/JTL unavailable or malformed"]
    (args.output_dir / "verification.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
