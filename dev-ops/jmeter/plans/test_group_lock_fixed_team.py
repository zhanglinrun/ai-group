"""Local fixed-team plan/report/DB-gate checks; no live Group or MySQL."""

import copy
import csv
import json
import os
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

PLANS = Path(__file__).resolve().parent
sys.path.insert(0, str(PLANS))
from generate_group_lock_jmx import build  # noqa: E402
from verify_group_lock_fixed_team import (  # noqa: E402
    GateError, capture, check_snapshot, check_stack, main as gate_main, validate_ids, verify,
)

RUN = "20260907235835766"
PREFIX = "jmeter-spike-" + RUN + "-"
KEY1, KEY2, KEY3 = (PREFIX + char * 32 for char in "abc")
LABEL = "POST Group lock_market_pay_order"


def sample(key, code="0000", **fields):
    return dict(timeStamp="1000", elapsed="20", label=LABEL, responseCode="200",
                success="true", businessCode=code, outTradeNo=key) | fields


def write_jtl(path, rows):
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=["timeStamp", "elapsed", "label", "responseCode",
                                                   "success", "businessCode", "outTradeNo"])
        writer.writeheader()
        writer.writerows(rows)


def detail(number, key, **fields):
    return dict(kind="detail", id=number, teamId="team-1", activityId=100201, goodsId="9890002",
                source="s01", channel="c01", status=0, outTradeNo=key,
                orderId=f"order-{number}", bizId=f"biz-{number}") | fields

def snapshot(locked=1, details=None):
    return {
        "identity": [dict(kind="identity", database="group_buy_market", serverUuid="bench-server", serverTime="2026-09-20 00:00:00")],
        "team": [dict(kind="team", teamId="team-1", activityId=100201, source="s01", channel="c01",
                      status=0, unexpired=1, targetCount=3, lockCount=locked, completeCount=0,
                      originalPrice=12, deductionPrice=0, payPrice=12,
                      validStartTime="2026-09-20 00:00:00", validEndTime="2026-09-21 00:00:00",
                      notifyType="MQ", notifyUrl=None)],
        "mapping": [dict(kind="mapping", goodsId="9890002", activityId=100201, source="s01", channel="c01")],
        "detail": details if details is not None else [detail(1, "original")],
        "outbox": [],
    }


class FixedTeamPlanTests(unittest.TestCase):
    def test_default_new_team_regression(self):
        root = ET.fromstring(build())
        self.assertIn('"teamId":null', root.find(".//HTTPSamplerProxy//stringProp[@name='Argument.value']").text)
        self.assertIn('${__P(activityId,100201)}', build())
        self.assertIn('${__P(goodsId,9890002)}', build())
        self.assertEqual("-1", root.find(".//stringProp[@name='LoopController.loops']").text)
        self.assertIsNone(root.find(".//SyncTimer"))
        script = root.find(".//JSR223PreProcessor/stringProp[@name='script']").text
        self.assertIn("'jmeter-lock-' + UUID.randomUUID()", script)
        self.assertNotIn("props.getProperty('runId')", script)

    def test_fixed_plan_and_runner(self):
        root = ET.fromstring(build(True))
        body = root.find(".//HTTPSamplerProxy//stringProp[@name='Argument.value']").text
        self.assertIn('"teamId":"${__P(teamId,)}"', body)
        self.assertEqual("1", root.find(".//stringProp[@name='LoopController.loops']").text)
        self.assertEqual("${__P(threads,2000)}", root.find(".//SyncTimer/stringProp[@name='groupSize']").text)
        self.assertEqual("120000", root.find(".//SyncTimer/stringProp[@name='timeoutInMs']").text)
        script = root.find(".//JSR223PreProcessor/stringProp[@name='script']").text
        self.assertIn("vars.put('outTradeNo', 'jmeter-spike-' + runId", script)
        self.assertIn("vars.put('businessCode'", root.find(".//JSR223Assertion/stringProp[@name='script']").text)
        runner = (PLANS.parent / "run-group-lock-fixed-team.ps1").read_text(encoding="utf-8")
        self.assertIn("[int]$ExpectedFreeSlots", runner)
        self.assertIn('"-Jsample_variables=businessCode,outTradeNo"', runner)
        self.assertLess(runner.index("--phase pre"), runner.index("& $jmeter -n"))
        self.assertGreater(runner.index("--phase post"), runner.index("& $jmeter -n"))
        self.assertIn("READ ONLY, WITH CONSISTENT SNAPSHOT", (PLANS / "verify_group_lock_fixed_team.sql").read_text())


class FixedTeamSummaryTests(unittest.TestCase):
    def run_summary(self, rows, threads, slots=1):
        with tempfile.TemporaryDirectory(dir=os.environ.get("PI_SCRATCH_DIR")) as directory:
            jtl, report = Path(directory) / "results.jtl", Path(directory) / "summary.json"
            write_jtl(jtl, rows)
            result = subprocess.run(
                [sys.executable, str(PLANS / "summarize_group_lock_fixed_team_jtl.py"),
                 "--jtl", str(jtl), "--report", str(report), "--threads", str(threads),
                 "--rampup", "0", "--team-id", "team-1", "--activity-id", "100201",
                 "--goods-id", "9890002", "--run-id", RUN, "--expected-free-slots", str(slots)],
                capture_output=True, text=True, check=False)
            return result.returncode, json.loads(report.read_text(encoding="utf-8"))

    def test_separate_latency_and_expected_rejections(self):
        status, report = self.run_summary([sample(KEY1, elapsed="10"), sample(KEY2, "E0006", elapsed="40")], 2)
        self.assertEqual(0, status)
        self.assertEqual(10, report["results"]["acceptedLatencyP99Ms"])
        self.assertEqual(40, report["results"]["rejectedLatencyP99Ms"])
        self.assertEqual(1, report["results"]["expectedFullTeamRejects"])

    def test_missing_slot_with_two_preflight_slots_fails(self):
        status, report = self.run_summary([sample(KEY1), sample(KEY2, "E0006")], 2, slots=2)
        self.assertEqual(1, status)
        self.assertEqual(1, report["results"]["acceptedLocks"])
        self.assertEqual(2, report["scenario"]["expectedFreeSlots"])

    def test_timeout_rejected_or_wrong_key_cannot_pass(self):
        for rows in ([sample(KEY1, responseCode="Non HTTP response code: java.net.SocketTimeoutException", success="false")],
                     [sample(KEY1, "E0008")], [sample("jmeter-spike-00000000000000000-" + "a" * 32)],
                     [sample(KEY1), sample(KEY1, "E0006")]):
            status, _ = self.run_summary(rows, len(rows))
            self.assertEqual(1, status)

    def test_missing_samples_or_unsynchronized_spike(self):
        self.assertEqual(1, self.run_summary([sample(KEY1)], 2)[0])
        self.assertEqual(1, self.run_summary([sample(KEY1), sample(KEY2, "E0006", timeStamp="121000")], 2)[0])
        self.assertEqual(1, self.run_summary([sample(KEY1), sample(KEY2, "E0006", label="unexpected")], 1)[0])


class DatabaseGateTests(unittest.TestCase):
    def test_identifiers_reject_sql_metacharacters(self):
        for identifiers in (("team';DROP TABLE x", "100201", "9890002", RUN),
                            ("team-1", "100201", "goods%", RUN),
                            ("team-1", "1 OR 1=1", "9890002", RUN),
                            ("team-1", "100201", "9890002", "1'")):
            with self.assertRaises(GateError):
                validate_ids(*identifiers)

    @patch("verify_group_lock_fixed_team.inspect")
    def test_compose_label_mismatch_fails_closed(self, mocked):
        mocked.return_value = {"com.docker.compose.project": "wrong", "com.docker.compose.service": "mysql"}
        with self.assertRaisesRegex(GateError, "not from the ai-group-bench"):
            check_stack(8091)

    @patch("verify_group_lock_fixed_team.command")
    @patch("verify_group_lock_fixed_team.check_stack")
    def test_database_identity_mismatch_fails_closed(self, _stack, cmd):
        cmd.return_value = json.dumps({"kind": "identity", "database": "production"})
        with self.assertRaisesRegex(GateError, "database mismatch"):
            capture("team-1", "100201", "9890002", RUN, 8091)
        self.assertIn("START TRANSACTION READ ONLY", cmd.call_args.kwargs["input_text"])
        self.assertNotIn("-p", " ".join(cmd.call_args.args))

    @patch("verify_group_lock_fixed_team.command", side_effect=GateError("Docker/MySQL unavailable"))
    @patch("verify_group_lock_fixed_team.check_stack")
    def test_db_unavailable_fails_closed(self, _stack, _cmd):
        with self.assertRaises(GateError):
            capture("team-1", "100201", "9890002", RUN, 8091)

    def test_preflight_unavailable_persists_failed_verification(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("PI_SCRATCH_DIR")) as directory:
            argv = ["verify_group_lock_fixed_team.py", "--phase", "pre", "--output-dir", directory,
                    "--team-id", "team-1", "--activity-id", "100201", "--goods-id", "9890002",
                    "--run-id", RUN, "--expected-free-slots", "2"]
            with patch.object(sys, "argv", argv), patch("verify_group_lock_fixed_team.capture",
                                                       side_effect=GateError("Docker/MySQL unavailable")):
                self.assertEqual(1, gate_main())
            result = json.loads((Path(directory) / "verification.json").read_text(encoding="utf-8"))
            self.assertFalse(result["passed"])
            self.assertFalse((Path(directory) / "db-pre.json").exists())

    def test_preflight_creates_new_output_directory(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("PI_SCRATCH_DIR")) as directory:
            output = Path(directory) / "new" / "run"
            argv = ["verify_group_lock_fixed_team.py", "--phase", "pre", "--output-dir", str(output),
                    "--team-id", "team-1", "--activity-id", "100201", "--goods-id", "9890002",
                    "--run-id", RUN, "--expected-free-slots", "2"]
            with patch.object(sys, "argv", argv), patch("verify_group_lock_fixed_team.capture", return_value=snapshot()):
                self.assertEqual(0, gate_main())
            self.assertTrue((output / "db-pre.json").exists())
            self.assertTrue(json.loads((output / "verification.json").read_text(encoding="utf-8"))["passed"])

    def test_preflight_rejects_missing_slot_or_expired_team(self):
        pre = snapshot()
        self.assertIn("Team counters or expected free slots mismatch",
                      check_snapshot(pre, "team-1", "100201", "9890002", RUN, 1 + 1 + 1))
        pre["team"][0]["unexpired"] = 0
        self.assertIn("Team is not unexpired and in progress",
                      check_snapshot(pre, "team-1", "100201", "9890002", RUN, 2))

    def test_post_keys_counters_and_outbox(self):
        pre = snapshot()
        post = snapshot(locked=3, details=pre["detail"] + [detail(2, KEY1), detail(3, KEY2)])
        with tempfile.TemporaryDirectory(dir=os.environ.get("PI_SCRATCH_DIR")) as directory:
            jtl = Path(directory) / "results.jtl"
            write_jtl(jtl, [sample(KEY1), sample(KEY2)])
            self.assertEqual([], verify(pre, post, jtl, "team-1", "100201", "9890002", RUN, 2))
            changed_server = copy.deepcopy(post)
            changed_server["identity"][0]["serverUuid"] = "other-server"
            self.assertIn("MySQL server changed between snapshots",
                          verify(pre, changed_server, jtl, "team-1", "100201", "9890002", RUN, 2))
            changed_price = copy.deepcopy(post)
            changed_price["team"][0]["payPrice"] = 11
            self.assertIn("Team price, window or notification config changed during run",
                          verify(pre, changed_price, jtl, "team-1", "100201", "9890002", RUN, 2))
            write_jtl(jtl, [sample(KEY1), sample(KEY3)])
            self.assertIn("Successful JTL keys differ from run-scoped DB details",
                          verify(pre, post, jtl, "team-1", "100201", "9890002", RUN, 2))
            write_jtl(jtl, [sample(KEY1), sample(KEY2, "E0006")])
            issues = verify(pre, post, jtl, "team-1", "100201", "9890002", RUN, 2)
            self.assertIn("Rejected JTL key persisted as a detail", issues)
            write_jtl(jtl, [sample(KEY1), sample(KEY2)])
            wrong_team = copy.deepcopy(post)
            wrong_team["detail"][2]["teamId"] = "other-team"
            self.assertIn("Misplaced or invalid run-scoped detail",
                          verify(pre, wrong_team, jtl, "team-1", "100201", "9890002", RUN, 2))
            post["outbox"].append(dict(kind="outbox", id=1, uuid="x", activityId=100201,
                                       notifyStatus=0, notifyCount=0))
            self.assertIn("Outbox drift during lock-only run",
                          verify(pre, post, jtl, "team-1", "100201", "9890002", RUN, 2))
            baseline = copy.deepcopy(pre)
            baseline["outbox"] = [dict(kind="outbox", id=1, uuid="x", activityId=100201,
                                       notifyStatus=0, notifyCount=0, rowDigest="before")]
            after = copy.deepcopy(post)
            after["outbox"] = [dict(kind="outbox", id=1, uuid="x", activityId=100201,
                                    notifyStatus=0, notifyCount=0, rowDigest="after")]
            self.assertIn("Outbox drift during lock-only run",
                          verify(baseline, after, jtl, "team-1", "100201", "9890002", RUN, 2))


if __name__ == "__main__":
    unittest.main()
