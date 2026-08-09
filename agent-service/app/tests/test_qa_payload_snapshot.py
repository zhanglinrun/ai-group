from __future__ import annotations

from datetime import datetime, timezone

from agents.nodes.qa import _make_qa_payload
from schemas.qa import Approval, Rejection, RetryPolicy


def test_qa_payload_snapshot_records_failed_rule_count_for_rejection() -> None:
    rejection = Rejection(
        rejection_id="rejection_test",
        step_id="step_writer",
        reject_to="analyst",
        failed_rule_ids=["rule_knowledge_schema_conformance", "rule_writer_must_cite_evidence"],
        semantic_findings=["knowledge incomplete"],
        required_fields=["run_knowledge.features"],
        retry_policy=RetryPolicy(max_retry=3, current_retry=1),
        severity="blocking",
        reviewer_step_id="step_qa",
        created_at=datetime.now(timezone.utc).isoformat(),
    )

    payload = _make_qa_payload(
        target_step_id="step_writer",
        report_id="report_test",
        review_result=rejection,
    )

    assert payload["failed_rule_count"] == 2
    assert payload["failed_rule_ids"] == [
        "rule_knowledge_schema_conformance",
        "rule_writer_must_cite_evidence",
    ]


def test_qa_payload_snapshot_records_zero_failed_rules_for_approval() -> None:
    approval = Approval(
        approval_id="approval_test",
        step_id="step_writer",
        passed_rule_ids=["rule_report_must_have_markdown_content"],
        warning_rule_ids=["rule_locale_mismatch"],
        semantic_audit_passed=True,
        reviewer_step_id="step_qa",
        created_at=datetime.now(timezone.utc).isoformat(),
    )

    payload = _make_qa_payload(
        target_step_id="step_writer",
        report_id="report_test",
        review_result=approval,
    )

    assert payload["failed_rule_count"] == 0
    assert payload["failed_rule_ids"] == []
    assert payload["warning_rule_ids"] == ["rule_locale_mismatch"]
