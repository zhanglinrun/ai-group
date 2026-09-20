"""Offline tests for the bench reconciliation audit."""

import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import audit

META = {"kind": "meta", "serverUuid": "mysql-uuid", "asOf": "2026-09-20T12:00:00.000000",
        "windowStart": "2026-09-19T12:00:00.000000", "cutoff": "2026-09-20T11:50:00.000000"}
GROUP = {"kind": "anomaly", "orderId": "o-1", "teamId": "t-1", "groupStatus": 1,
         "detailStatus": 1, "payStatus": "PAY_SUCCESS", "memberRevokedStatus": None}

IDENTITY = {"matchingDetails": 1, "detailSource": "s01", "detailChannel": "c01"}

def snapshot(*rows):
    return "\n".join(json.dumps(row) for row in (META, *rows)) + "\n"


class AuditTests(unittest.TestCase):
    def test_classification(self):
        cases = [
            ("FORMED_GROUP_PAY_STUCK", {**GROUP}, True),
            ("FORMED_GROUP_PAY_STUCK", {**GROUP, "groupStatus": 0}, False),
            ("FORMED_GROUP_PAY_STUCK", {**GROUP, "detailStatus": 0}, False),
            ("FORMED_GROUP_PAY_STUCK", {**GROUP, "teamId": None}, False),
            ("FORMED_GROUP_PAY_STUCK", {**GROUP, "memberRevokedStatus": "SKIPPED_REVOKED"}, False),
            ("LEGACY_GROUP_IDENTITY_MISSING", {**GROUP, **IDENTITY, "groupSource": None,
                                               "groupChannel": "default"}, True),
            ("LEGACY_GROUP_IDENTITY_MISSING", {**GROUP, **IDENTITY, "payStatus": "WAIT_REFUND",
                                               "groupSource": "app", "groupChannel": None}, True),
            ("LEGACY_GROUP_IDENTITY_MISSING", {**GROUP, **IDENTITY, "groupSource": None,
                                               "groupChannel": None, "matchingDetails": 2,
                                               "detailSource": None, "detailChannel": None}, True),
            ("LEGACY_GROUP_IDENTITY_MISSING", {**GROUP, **IDENTITY, "groupSource": None,
                                               "groupChannel": None, "matchingDetails": 2}, False),
            ("LEGACY_GROUP_IDENTITY_MISSING", {**GROUP, **IDENTITY, "groupSource": "app",
                                               "groupChannel": "default"}, False),
            ("LEGACY_GROUP_IDENTITY_MISSING", {**GROUP, **IDENTITY, "groupSource": None,
                                               "groupChannel": "default", "detailStatus": 0}, False),
            ("LEGACY_GROUP_IDENTITY_MISSING", {**GROUP, **IDENTITY, "groupSource": None}, False),
            ("ELIGIBLE_PAY_MISSING_COMPLETED_OUTBOX", {**GROUP, "payStatus": "MARKET",
                                                       "completedEventId": None}, True),
            ("ELIGIBLE_PAY_MISSING_COMPLETED_OUTBOX", {**GROUP, "payStatus": "MARKET",
                                                       "completedEventId": "e-1"}, False),
            ("SENT_COMPLETED_WITHOUT_MEMBER_RECORD", {**GROUP, "payStatus": "DEAL_DONE",
                "completedEventId": "e-1", "publishStatus": "SENT", "eventPublished": 1}, True),
            ("SENT_COMPLETED_WITHOUT_MEMBER_RECORD", {**GROUP, "payStatus": "DEAL_DONE",
                "completedEventId": "e-1", "publishStatus": "PENDING", "eventPublished": 1}, False),
            ("REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT", {"payStatus": "CLOSE",
                "memberCompletedStatus": "GRANTED", "memberRevokedStatus": None}, True),
            ("REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT", {"payStatus": "WAIT_REFUND",
                "memberCompletedStatus": "GRANTED", "memberRevokedStatus": "REVOKED"}, False),
            ("REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT", {"payStatus": "CLOSE",
                "memberCompletedStatus": "GRANTED", "memberRevokedStatus": "SKIPPED_REVOKED"}, False),
            ("REFUNDED_PAY_WITH_ACTIVE_MEMBER_GRANT", {"payStatus": "CLOSE",
                "memberCompletedStatus": "GRANTED", "memberRevokedStatus": "REJECTED_GRANTED"}, True),
            ("PAY_BENEFIT_RECONCILIATION_MANUAL", {"eventId": "e-1",
                "reconciliationOutcome": "MANUAL"}, True),
        ]
        for name, data, expected in cases:
            with self.subTest(name=name, data=data):
                self.assertEqual(audit.valid_anomaly({"name": name, **data}), expected)

    def test_parse_and_bound(self):
        row = {**GROUP, "name": "FORMED_GROUP_PAY_STUCK"}
        meta, items, truncated = audit.parse_snapshot(snapshot(row, row), 1)
        self.assertEqual(meta["serverUuid"], "mysql-uuid")
        self.assertEqual(len(items), 1)
        self.assertTrue(truncated)
        self.assertEqual(audit.parse_snapshot(snapshot(), 1)[1:], ([], False))

    def test_malformed_snapshot_fails_closed(self):
        row = {**GROUP, "name": "FORMED_GROUP_PAY_STUCK"}
        for output in ("", "not-json\n", json.dumps(row), snapshot({**row, "name": "UNKNOWN"}),
                       snapshot({**row, "orderId": None}), snapshot(row, row, row)):
            with self.subTest(output=output), self.assertRaises(audit.AuditError):
                audit.parse_snapshot(output, 1)
        orphan = {"kind": "anomaly", "name": "PAY_BENEFIT_RECONCILIATION_MANUAL",
                  "eventId": "e-1", "orderId": None, "reconciliationOutcome": "MANUAL"}
        self.assertEqual(audit.parse_snapshot(snapshot(orphan), 1)[1], [orphan])

    def test_sql_scoping_and_read_only(self):
        sql = audit.make_sql(24, 2)
        self.assertIn("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY", sql)
        self.assertIn("LIMIT 3;", sql)
        self.assertIn("INTERVAL 24 HOUR", sql)
        self.assertIn("p.market_type = 1", sql)
        self.assertIn("d.status = 1 AND d.update_time", sql)
        self.assertIn("g.status IN (1, 3)", sql)
        self.assertIn("group_source IS NULL OR group_channel IS NULL", sql)
        self.assertIn("d.team_id COLLATE utf8mb4_unicode_ci = p.group_team_id", sql)
        self.assertIn("d.activity_id = p.group_activity_id", sql)
        self.assertIn("COUNT(*) OVER (PARTITION BY p.order_id)", sql)
        self.assertIn("revoke_event_id IS NULL", sql)
        self.assertIn("member_revoked_status IS NULL", sql)
        for value in (0, 169):
            with self.assertRaises(audit.AuditError):
                audit.make_sql(value, 1)

    @patch.object(audit, "command")
    def test_label_rejection_stops_before_query(self, command):
        command.side_effect = [json.dumps({"com.docker.compose.project": "wrong",
                                            "com.docker.compose.service": "mysql"}), "true"]
        with self.assertRaises(audit.AuditError):
            audit.capture(24, 100)
        self.assertEqual(command.call_count, 2)

    @patch.object(audit, "command")
    def test_query_failure_report_and_exit(self, command):
        command.side_effect = [json.dumps({"com.docker.compose.project": "ai-group-bench",
                                            "com.docker.compose.service": "mysql"}), "true",
                               audit.AuditError("Docker/MySQL query failed")]
        with tempfile.TemporaryDirectory(dir=os.environ.get("PI_SCRATCH_DIR")) as root:
            code, report_path = audit.run(24, 100, Path(root))
            report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(code, 1)
        self.assertFalse(report["passed"])
        self.assertIn("query failed", report["error"])
        self.assertEqual(report["anomalies"], [])

    @patch.object(audit, "capture")
    def test_anomaly_report_exits_nonzero(self, capture):
        row = {**GROUP, "name": "FORMED_GROUP_PAY_STUCK"}
        capture.return_value = META, [row], False
        with tempfile.TemporaryDirectory(dir=os.environ.get("PI_SCRATCH_DIR")) as root:
            code, report_path = audit.run(24, 1, Path(root))
            report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(code, 1)
        self.assertEqual(report["anomalyCount"], 1)
        self.assertEqual(report["counts"], {"FORMED_GROUP_PAY_STUCK": 1})

    @patch.object(audit.subprocess, "run")
    def test_subprocess_error_is_sanitized(self, run):
        run.return_value.returncode = 1
        run.return_value.stderr = "secret password"
        with self.assertRaises(audit.AuditError) as caught:
            audit.command("docker", "exec")
        self.assertNotIn("secret", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
