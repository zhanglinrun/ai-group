from service.run_status_reason import (
    DEFAULT_DEGRADED_REASON,
    DEFAULT_FAILED_REASON,
    build_degraded_reason,
    derive_degraded_reason_from_records,
    humanize_failure_message,
)


def test_build_degraded_reason_explains_single_competitor_matrix() -> None:
    reason = build_degraded_reason(
        forced_degraded_by_qa=True,
        qa_degrade_reason="report_degraded_required_sections",
        degraded_required_sections=["comparison_matrix"],
        competitor_count=1,
    )
    assert reason == "当前只调研了 1 个对象，对比矩阵需要至少 2 个对照对象。"


def test_writer_fallback_is_the_only_reason_even_if_sections_also_failed() -> None:
    reason = build_degraded_reason(
        forced_degraded_by_qa=True,
        qa_degrade_reason="report_degraded_required_sections",
        degraded_required_sections=["competitor_profiles", "comparison_matrix"],
        writer_fallback=True,
        writer_fallback_reason="Error code: 401 - Invalid token (request id: abc)",
        competitor_count=1,
    )
    assert reason == "写作模型未能连上，已改用模板报告。"


def test_derive_writer_fallback_ignores_downstream_section_gaps() -> None:
    reason = derive_degraded_reason_from_records(
        competitor_ids=["cursor"],
        qa_payload={
            "qa_outcome": "force_degraded",
            "qa_degrade_reason": "report_degraded_required_sections",
            "qa_degraded_required_sections": ["competitor_profiles", "comparison_matrix"],
        },
        writer_payload={
            "writer_mode": "fallback",
            "fallback_reason": "Error code: 401 - Invalid token",
            "report_degraded_required_sections": ["competitor_profiles", "comparison_matrix"],
        },
        finalize_tool_args={"completion_reason": "fallback_path"},
    )
    assert reason == "写作模型未能连上，已改用模板报告。"


def test_build_degraded_reason_researcher_gap() -> None:
    reason = build_degraded_reason(
        researcher_degraded_competitors=["cursor"],
        report_draft_done=True,
        completion_reason="all_dimensions_covered",
        competitor_count=1,
    )
    assert "cursor" in reason
    assert "有效证据" in reason


def test_humanize_failure_message_redacts_token_errors() -> None:
    raw = (
        "LLMRequestError: openai request failed for model=gpt-5.5: "
        "Error code: 401 - {'error': {'message': 'Invalid token (request id: 123)'}}"
    )
    assert humanize_failure_message(raw) == "写作模型未能连上，请稍后重试。"
    assert "密钥" not in humanize_failure_message(raw)
    assert humanize_failure_message("") == DEFAULT_FAILED_REASON


def test_derive_from_records_matches_watchlist_refresh_gap() -> None:
    reason = derive_degraded_reason_from_records(
        competitor_ids=["cursor"],
        qa_payload={
            "qa_outcome": "force_degraded",
            "qa_degrade_reason": "report_degraded_required_sections",
            "qa_degraded_required_sections": ["comparison_matrix"],
        },
        writer_payload={"writer_mode": "llm", "report_degraded_required_sections": ["comparison_matrix"]},
        finalize_tool_args={"completion_reason": "fallback_path"},
    )
    assert "对比矩阵" in reason
    assert reason != DEFAULT_DEGRADED_REASON
