from __future__ import annotations

from datetime import datetime, timezone

from models.evidence import EvidenceRecord
from models.run import Run
from models.step import Step
from agents.nodes.qa import _qa_warning_items
from schemas.qa import Approval, Rejection
from service.llm.response import LLMResponse
from service.qa.engine import (
    _apply_numeric_claim_gate,
    _build_qa_fast_path_log_fields,
    _build_qa_slow_path_log_fields,
    _semantic_fail_closed_rule_result,
    _semantic_dimension_rule_results,
    _unsupported_numeric_claims,
    _target_sections_for_report,
    build_qa_outcome,
)
from service.qa.rules import (
    RuleResult,
    evaluate_fast_path_rules,
    rule_buyer_critical_sections_need_official_source,
    rule_evidence_must_be_desensitized,
    rule_evidence_balance_for_profile_competitors,
    rule_deep_report_min_char_count,
    rule_locale_mismatch,
    rule_report_must_have_at_least_one_section,
    rule_report_must_have_markdown_content,
    rule_report_language_consistency,
    rule_report_section_count_in_bounds,
    rule_source_quality_blocklist_share,
    rule_complete_coverage_has_target_evidence,
    rule_landscape_core_commercial_sections_present,
    rule_landscape_no_legacy_workbench_sections,
    rule_structured_sections_present,
    rule_report_template_id_present,
    rule_triplet_coverage_for_profile_competitors,
    rule_writer_must_cite_evidence,
    rule_writer_no_fallback_mode,
    rule_writer_no_placeholder_scaffolding,
    rule_writer_sections_must_have_content,
)


def _make_evidence(*, desensitized: bool) -> EvidenceRecord:
    return EvidenceRecord(
        id="ev_test_001",
        run_id="run_test_001",
        source_type="official_site",
        source_url="https://example.com",
        source_title="Example",
        quote="quoted text",
        sanitized_text="sanitized text",
        span={"start": 0, "end": 1},
        collected_by="step_researcher_001",
        collected_at=datetime.now(timezone.utc),
        desensitized=desensitized,
    )


def _make_evidence_with_authority(
    *, evidence_id: str, source_authority: str
) -> EvidenceRecord:
    return EvidenceRecord(
        id=evidence_id,
        run_id="run_test_authority",
        source_type="article",
        source_url="https://example.com",
        source_title="Example",
        quote="quoted text",
        sanitized_text="sanitized text",
        span={"dimension": "pricing_strategy", "source_authority": source_authority},
        collected_by="step_researcher_001",
        collected_at=datetime.now(timezone.utc),
        desensitized=True,
    )


def _make_locale_evidence(
    *,
    evidence_id: str,
    source_url: str,
    sanitized_text: str,
) -> EvidenceRecord:
    return EvidenceRecord(
        id=evidence_id,
        run_id="run_test_locale",
        source_type="article",
        source_url=source_url,
        source_title="Locale Source",
        quote=sanitized_text,
        sanitized_text=sanitized_text,
        span={},
        collected_by="step_researcher_001",
        collected_at=datetime.now(timezone.utc),
        desensitized=True,
    )


def _make_profile_evidence(
    *,
    evidence_id: str,
    competitor_id: str,
    source_url: str,
) -> EvidenceRecord:
    return EvidenceRecord(
        id=evidence_id,
        run_id="run_profile_balance",
        source_type="article",
        source_url=source_url,
        source_title="Profile Source",
        quote="profile quote",
        sanitized_text="profile quote",
        span={"competitor_id": competitor_id, "source_authority": "third_party"},
        collected_by="step_researcher_001",
        collected_at=datetime.now(timezone.utc),
        desensitized=True,
    )


def test_rule_buyer_critical_sections_need_official_source_warns_on_third_party_only() -> None:
    content_json = {
        "sections": [
            {"section_id": "pricing_strategy", "evidence_refs": ["ev_third_party"]},
        ]
    }
    evidence_items = [
        _make_evidence_with_authority(
            evidence_id="ev_third_party", source_authority="third_party"
        )
    ]

    result = rule_buyer_critical_sections_need_official_source(
        content_json=content_json,
        evidence_items=evidence_items,
    )

    assert result.passed is False
    assert result.severity == "warning"
    assert "pricing_strategy" in result.message


def test_rule_buyer_critical_sections_need_official_source_passes_with_official() -> None:
    content_json = {
        "sections": [
            {
                "section_id": "pricing_strategy",
                "evidence_refs": ["ev_official", "ev_third_party"],
            },
            # Non-critical section is ignored regardless of authority.
            {"section_id": "market_differences", "evidence_refs": ["ev_third_party"]},
        ]
    }
    evidence_items = [
        _make_evidence_with_authority(
            evidence_id="ev_official", source_authority="official"
        ),
        _make_evidence_with_authority(
            evidence_id="ev_third_party", source_authority="third_party"
        ),
    ]

    result = rule_buyer_critical_sections_need_official_source(
        content_json=content_json,
        evidence_items=evidence_items,
    )

    assert result.passed is True


def test_rule_locale_mismatch_warns_for_china_scope_with_overseas_english_sources() -> None:
    result = rule_locale_mismatch(
        market_scope="中国大陆",
        evidence_items=[
            _make_locale_evidence(
                evidence_id="ev_en_1",
                source_url="https://example.com/a",
                sanitized_text="English source about product positioning.",
            ),
            _make_locale_evidence(
                evidence_id="ev_en_2",
                source_url="https://example.org/b",
                sanitized_text="Another English source about product positioning.",
            ),
        ],
    )

    assert result.passed is False
    assert result.severity == "warning"
    assert "domestic_coverage=0.00" in result.message


def test_rule_locale_mismatch_passes_for_china_scope_with_domestic_sources() -> None:
    result = rule_locale_mismatch(
        market_scope="中国大陆",
        evidence_items=[
            _make_locale_evidence(
                evidence_id="ev_cn_1",
                source_url="https://example.cn/a",
                sanitized_text="中文来源介绍产品能力。",
            ),
            _make_locale_evidence(
                evidence_id="ev_cn_2",
                source_url="https://36kr.com/p/123",
                sanitized_text="这是一条中文市场报道。",
            ),
        ],
    )

    assert result.passed is True


def test_rule_report_language_consistency_rejects_zh_report_with_english_drift() -> None:
    content_json = {
        "executive_summary": (
            "This report compares pricing strategy, distribution channels, and product moats across "
            "multiple competitors with evidence-backed recommendations and implementation guidance."
        ),
        "sections": [
            {
                "section_id": "pricing_strategy",
                "title": "Pricing Strategy",
                "content_markdown": (
                    "Enterprise pricing is structured around annual contracts, seat bundles, "
                    "compliance add-ons, procurement workflows, and rollout governance for "
                    "cross-functional buying committees."
                ),
                "evidence_refs": ["ev_test_001"],
            }
        ],
    }

    result = rule_report_language_consistency(
        content_json=content_json,
        response_language="zh",
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert "response_language=zh" in result.message


def test_rule_report_language_consistency_allows_en_report_with_small_chinese_terms() -> None:
    content_json = {
        "executive_summary": (
            "The report evaluates product positioning, monetization, and enterprise readiness "
            "across major vendors while keeping evidence traceable for auditing."
        ),
        "sections": [
            {
                "section_id": "market_differences",
                "title": "Market Differences",
                "content_markdown": (
                    "Most evidence supports an English narrative, with only limited proper nouns "
                    "such as 阿里云 and 字节跳动 kept in original form."
                ),
                "evidence_refs": ["ev_test_001"],
            }
        ],
    }

    result = rule_report_language_consistency(
        content_json=content_json,
        response_language="en",
    )

    assert result.passed is True
    assert result.severity == "blocking"


def test_rule_report_language_consistency_rejects_en_report_with_large_chinese_blocks() -> None:
    content_json = {
        "executive_summary": "这是一段中文执行摘要，明显不符合英文报告输出要求。",
        "sections": [
            {
                "section_id": "key_players",
                "title": "关键玩家",
                "content_markdown": (
                    "该章节主要使用中文描述市场竞争格局、产品能力差异、渠道策略、定价方式以及采购流程，"
                    "没有形成英文用户可以直接消费的统一叙述。"
                ),
                "evidence_refs": ["ev_test_001"],
            }
        ],
    }

    result = rule_report_language_consistency(
        content_json=content_json,
        response_language="en",
    )

    assert result.passed is False
    assert result.severity == "blocking"
    assert "response_language=en" in result.message


def test_rule_report_must_have_markdown_content_pass_and_fail() -> None:
    assert rule_report_must_have_markdown_content("# title").passed is True
    assert rule_report_must_have_markdown_content("  ").passed is False


def test_rule_report_template_id_present_pass_and_fail() -> None:
    assert rule_report_template_id_present(content_json={"template_id": "battlecard_default"}).passed is True
    assert rule_report_template_id_present(content_json={"template_id": "   "}).passed is False


def test_rule_report_must_have_at_least_one_section_pass_and_fail() -> None:
    assert rule_report_must_have_at_least_one_section({"sections": ["feature"]}).passed is True
    assert rule_report_must_have_at_least_one_section({"sections": []}).passed is False


def test_rule_report_section_count_in_bounds() -> None:
    assert rule_report_section_count_in_bounds({"sections": [{"section_id": "feature"}]}).passed is True
    assert rule_report_section_count_in_bounds({"sections": []}).passed is False
    assert rule_report_section_count_in_bounds({"sections": [{} for _ in range(13)]}).passed is False


def test_rule_writer_sections_must_have_content_pass_and_fail() -> None:
    passing_content_json = {
        "sections": [
            {
                "section_id": "feature",
                "title": "Feature Comparison",
                "content_markdown": (
                    "This section contains enough concrete analysis details and evidence-backed "
                    "narrative to satisfy QA minimum length constraints."
                ),
                "evidence_refs": ["ev_test_001"],
            }
        ]
    }
    failing_content_json = {
        "sections": [
            {
                "section_id": "feature",
                "title": "Feature Comparison",
                "content_markdown": "too short",
                "evidence_refs": ["ev_test_001"],
            }
        ]
    }
    assert rule_writer_sections_must_have_content(passing_content_json).passed is True
    assert rule_writer_sections_must_have_content(failing_content_json).passed is False


def test_rule_writer_no_placeholder_scaffolding_blocks_scaffold_text() -> None:
    passing_content_json = {
        "sections": [
            {
                "section_id": "market_size_growth",
                "title": "Market Size and Growth",
                "content_markdown": "This trend section contains grounded observations and concrete evidence.",
                "evidence_refs": ["ev_test_001"],
            }
        ]
    }
    methodology_limits_content_json = {
        "sections": [
            {
                "section_id": "methodology_limits",
                "title": "方法论与证据边界",
                "content_markdown": (
                    "本轮公开证据足以支撑主要竞争格局判断，但部分私有部署价格暂缺足够证据。"
                    "报告因此只保留方向性判断，不把未核验价格写成确定事实。"
                ),
                "evidence_refs": ["ev_test_001"],
            }
        ]
    }
    failing_content_json = {
        "sections": [
            {
                "section_id": "market_size_growth",
                "title": "Market Size and Growth",
                "content_markdown": "Section market_size_growth lacks enough grounded evidence; trigger follow-up research.",
                "evidence_refs": ["ev_test_001"],
            }
        ]
    }
    assert rule_writer_no_placeholder_scaffolding(passing_content_json).passed is True
    assert rule_writer_no_placeholder_scaffolding(methodology_limits_content_json).passed is True
    assert rule_writer_no_placeholder_scaffolding(failing_content_json).passed is False


def test_rule_writer_must_cite_evidence_pass_and_fail() -> None:
    passing_content_json = {
        "sections": [
            {
                "section_id": "feature",
                "title": "Feature Comparison",
                "content_markdown": "x" * 80,
                "evidence_refs": ["ev_test_001"],
            }
        ]
    }
    failing_content_json = {
        "sections": [
            {
                "section_id": "feature",
                "title": "Feature Comparison",
                "content_markdown": "x" * 80,
                "evidence_refs": ["ev_not_exists"],
            }
        ]
    }
    assert (
        rule_writer_must_cite_evidence(
            content_json=passing_content_json,
            allowed_evidence_ids={"ev_test_001"},
        ).passed
        is True
    )
    assert (
        rule_writer_must_cite_evidence(
            content_json=failing_content_json,
            allowed_evidence_ids={"ev_test_001"},
        ).passed
        is False
    )


def test_rule_writer_must_cite_evidence_requires_valid_ref_per_section() -> None:
    partially_cited_content_json = {
        "sections": [
            {
                "section_id": "feature",
                "title": "Feature Comparison",
                "content_markdown": "x" * 80,
                "evidence_refs": ["ev_test_001"],
            },
            {
                "section_id": "pricing",
                "title": "Pricing Comparison",
                "content_markdown": "x" * 80,
                "evidence_refs": [],
            },
        ]
    }
    result = rule_writer_must_cite_evidence(
        content_json=partially_cited_content_json,
        allowed_evidence_ids={"ev_test_001"},
    )
    assert result.passed is False
    assert "pricing" in result.message


def test_rule_evidence_must_be_desensitized_pass_and_fail() -> None:
    assert rule_evidence_must_be_desensitized([_make_evidence(desensitized=True)]).passed is True
    assert rule_evidence_must_be_desensitized([_make_evidence(desensitized=False)]).passed is False


def test_rule_writer_no_fallback_mode_pass_and_fail() -> None:
    assert rule_writer_no_fallback_mode({"risk_callouts": []}).passed is True
    assert rule_writer_no_fallback_mode({"risk_callouts": ["writer_fallback_mode"]}).passed is False
    assert rule_writer_no_fallback_mode({"risk_callouts": ["pricing volatility"]}).passed is True


def test_rule_structured_sections_present_enforces_archetype_sections() -> None:
    thin_landscape_json = {
        "executive_summary": "summary",
        "sections": [
            {"section_id": "executive_takeaways"},
            {"section_id": "strategic_recommendations"},
        ],
    }
    landscape_json = {
        "executive_summary": "summary",
        "sections": [
            {"section_id": "executive_takeaways"},
            {"section_id": "market_definition"},
            {"section_id": "market_size_growth"},
            {"section_id": "market_segmentation"},
            {"section_id": "competitive_landscape"},
            {"section_id": "key_players"},
            {"section_id": "value_chain"},
            {"section_id": "opportunities_risks"},
            {"section_id": "strategic_recommendations"},
            {"section_id": "methodology_limits"},
        ]
    }
    comparison_json = {
        "executive_summary": "summary",
        "sections": [
            {"section_id": "competitor_profiles"},
            {"section_id": "comparison_matrix"},
            {"section_id": "positioning_map"},
            {"section_id": "self_positioning"},
            {"section_id": "strategic_recommendations"},
        ]
    }

    thin_landscape_result = rule_structured_sections_present(
        content_json=thin_landscape_json,
        analysis_archetype="landscape",
    )
    landscape_result = rule_structured_sections_present(
        content_json=landscape_json,
        analysis_archetype="landscape",
    )
    comparison_result = rule_structured_sections_present(
        content_json=comparison_json,
        analysis_archetype="comparison",
    )

    assert thin_landscape_result.passed is False
    assert "market_definition" in thin_landscape_result.message
    assert "methodology_limits" in thin_landscape_result.message
    assert landscape_result.passed is True
    assert comparison_result.passed is True


def test_rule_structured_sections_present_ignores_degraded_required_sections() -> None:
    landscape_json = {
        "executive_summary": "summary",
        "sections": [
            {"section_id": "executive_takeaways"},
            {"section_id": "market_definition"},
            {"section_id": "market_size_growth"},
            {"section_id": "market_segmentation"},
            {"section_id": "competitive_landscape"},
            {"section_id": "key_players"},
            {"section_id": "value_chain"},
            {"section_id": "opportunities_risks"},
            {"section_id": "strategic_recommendations"},
            {"section_id": "methodology_limits"},
        ],
    }
    result = rule_structured_sections_present(
        content_json=landscape_json,
        analysis_archetype="landscape",
    )

    assert result.passed is True


def test_rule_landscape_no_legacy_workbench_sections_blocks_old_output() -> None:
    result = rule_landscape_no_legacy_workbench_sections(
        content_json={"sections": [{"section_id": "market_landscape_map"}]},
        content_markdown="## 竞品分层地图\n旧输出",
        analysis_archetype="landscape",
    )
    assert result.passed is False
    assert result.reject_to == "writer"


def test_rule_landscape_no_legacy_workbench_sections_allows_business_phrase() -> None:
    result = rule_landscape_no_legacy_workbench_sections(
        content_json={"sections": [{"section_id": "key_players"}]},
        content_markdown="Tenstorrent 是主权 AI 场景下的首选替代方案之一。",
        analysis_archetype="landscape",
    )
    assert result.passed is True


def test_rule_landscape_core_commercial_sections_present_blocks_missing_core() -> None:
    result = rule_landscape_core_commercial_sections_present(
        content_json={"sections": [{"section_id": "executive_takeaways"}]},
        analysis_archetype="landscape",
    )
    assert result.passed is False
    assert "market_definition" in result.message


def test_rule_complete_coverage_has_target_evidence_blocks_fake_complete() -> None:
    result = rule_complete_coverage_has_target_evidence(
        knowledge={
            "coverage": {"Meta": {"feature": "complete"}},
            "supporting_target_evidence_ids": {"Meta": {"pricing": ["ev_price"]}},
        },
    )
    assert result.passed is False
    assert "Meta.feature" in result.message


def test_rule_triplet_coverage_for_profile_competitors_blocks_thin_coverage() -> None:
    result = rule_triplet_coverage_for_profile_competitors(
        knowledge={
            "coverage": {
                "Meta": {
                    "feature": "complete",
                    "pricing": "insufficient_data",
                    "feedback": "missing",
                }
            }
        },
        profile_competitors=["Meta"],
    )

    assert result.passed is False
    assert result.reject_to == "researcher"
    assert "Meta" in result.message


def test_rule_evidence_balance_for_profile_competitors_blocks_dominance_and_gaps() -> None:
    result = rule_evidence_balance_for_profile_competitors(
        evidence_items=[
            _make_profile_evidence(
                evidence_id="ev_meta_1",
                competitor_id="Meta",
                source_url="https://meta.com/pricing",
            ),
            _make_profile_evidence(
                evidence_id="ev_meta_2",
                competitor_id="Meta",
                source_url="https://meta.com/features",
            ),
            _make_profile_evidence(
                evidence_id="ev_meta_3",
                competitor_id="Meta",
                source_url="https://meta.com/reviews",
            ),
        ],
        profile_competitors=["Meta", "XREAL"],
    )

    assert result.passed is False
    assert "zero_competitors=['XREAL']" in result.message


def test_rule_source_quality_blocklist_share_blocks_spam_sources() -> None:
    result = rule_source_quality_blocklist_share(
        evidence_items=[
            _make_profile_evidence(
                evidence_id="ev_ok",
                competitor_id="Meta",
                source_url="https://meta.com/pricing",
            ),
            _make_profile_evidence(
                evidence_id="ev_spam_1",
                competitor_id="Meta",
                source_url="https://x.com/search?q=ai+hardware",
            ),
            _make_profile_evidence(
                evidence_id="ev_spam_2",
                competitor_id="XREAL",
                source_url="https://book118.com/webdir/ai",
            ),
        ]
    )

    assert result.passed is False
    assert result.reject_to == "researcher"
    assert "blocked_ratio" in result.message


def test_deep_report_min_char_count_blocks_short_baseline() -> None:
    result = rule_deep_report_min_char_count(content_markdown="x" * 2086)
    assert result.passed is False
    assert result.severity == "blocking"


def test_evaluate_fast_path_rules_applies_deep_only_gates() -> None:
    content_json = {
        "template_id": "default",
        "sections": [
            {
                "section_id": "pricing",
                "title": "Pricing",
                "content_markdown": "x" * 240,
                "evidence_refs": ["ev_test_001"],
            }
        ],
    }
    evidence = _make_evidence(desensitized=True)

    quick_results = evaluate_fast_path_rules(
        content_markdown="x" * 500,
        content_json=content_json,
        evidence_items=[evidence],
        allowed_evidence_ids={"ev_test_001"},
        report_depth="quick",
        target_sections=["pricing", "security"],
    )
    deep_results = evaluate_fast_path_rules(
        content_markdown="x" * 500,
        content_json=content_json,
        evidence_items=[evidence],
        allowed_evidence_ids={"ev_test_001"},
        report_depth="deep",
        target_sections=["pricing", "security"],
    )

    assert all(not item.rule_id.startswith("rule_deep_") for item in quick_results)
    failed_deep_rule_ids = {item.rule_id for item in deep_results if not item.passed}
    assert "rule_deep_report_min_char_count" in failed_deep_rule_ids
    assert "rule_deep_report_covers_target_sections" in failed_deep_rule_ids


def test_evaluate_fast_path_rules_blocks_report_language_drift() -> None:
    content_json = {
        "template_id": "default",
        "sections": [
            {
                "section_id": "pricing",
                "title": "Pricing",
                "content_markdown": (
                    "This section describes annual contracts, onboarding playbooks, and procurement "
                    "workflows with explicit buying-stage recommendations for enterprise teams."
                ),
                "evidence_refs": ["ev_test_001"],
            }
        ],
    }
    evidence = _make_evidence(desensitized=True)

    results = evaluate_fast_path_rules(
        content_markdown="x" * 500,
        content_json=content_json,
        evidence_items=[evidence],
        allowed_evidence_ids={"ev_test_001"},
        report_depth="quick",
        response_language="zh",
    )

    failed_rule_ids = {item.rule_id for item in results if not item.passed}
    assert "rule_report_language_consistency" in failed_rule_ids


def test_deep_report_section_coverage_counts_top_level_executive_summary() -> None:
    content_json = {
        "template_id": "default",
        "executive_summary": "Executive summary with enough substance to count as present.",
        "sections": [
            {
                "section_id": "pricing",
                "title": "Pricing",
                "content_markdown": "x" * 240,
                "evidence_refs": ["ev_test_001"],
            }
        ],
    }
    evidence = _make_evidence(desensitized=True)

    results = evaluate_fast_path_rules(
        content_markdown="x" * 3200,
        content_json=content_json,
        evidence_items=[evidence],
        allowed_evidence_ids={"ev_test_001"},
        report_depth="deep",
        target_sections=["executive_summary", "pricing"],
    )

    failed_rule_ids = {item.rule_id for item in results if not item.passed}
    assert "rule_deep_report_covers_target_sections" not in failed_rule_ids


def test_target_sections_prefers_writer_resolved_targets_over_plan_and_intake() -> None:
    run = Run(
        run_id="run_qa_targets",
        user_query="qa targets",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_a"],
        intake_draft={"focus_dimensions": ["phantom_6", "phantom_7", "phantom_8", "phantom_9"]},
        plan_tree={
            "tasks": [
                {
                    "stage": "research",
                    "focus_dimensions": ["feature", "pricing", "security", "support"],
                }
            ]
        },
    )
    writer_step = Step(
        step_id="step_writer_targets",
        run_id=run.run_id,
        agent_name="writer",
        status="completed",
        retry_count=0,
        payload={
            "sections": [],
            "target_sections": ["feature", "pricing", "security", "support", "implementation"],
        },
    )

    assert _target_sections_for_report(run=run, writer_step=writer_step) == [
        "feature",
        "pricing",
        "security",
        "support",
        "implementation",
    ]


def test_target_sections_prefers_writer_renderable_sections_when_available() -> None:
    run = Run(
        run_id="run_qa_targets_renderable",
        user_query="qa targets",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_a"],
        intake_draft={"focus_dimensions": ["phantom_1"]},
        plan_tree=None,
    )
    writer_step = Step(
        step_id="step_writer_targets_renderable",
        run_id=run.run_id,
        agent_name="writer",
        status="completed",
        retry_count=0,
        payload={
            "renderable_sections": ["executive_summary", "strategic_recommendations"],
            "target_sections": ["executive_summary", "strategic_recommendations", "comparison_matrix"],
        },
    )

    assert _target_sections_for_report(run=run, writer_step=writer_step) == [
        "executive_summary",
        "strategic_recommendations",
    ]


def test_target_sections_falls_back_to_plan_and_intake_without_writer_targets() -> None:
    run = Run(
        run_id="run_qa_targets_fallback",
        user_query="qa targets",
        status="completed",
        target_roles=["pm"],
        competitors=["comp_a"],
        intake_draft={"focus_dimensions": ["pricing"]},
        plan_tree={
            "tasks": [
                {
                    "stage": "research",
                    "focus_dimensions": ["feature"],
                }
            ]
        },
    )
    writer_step = Step(
        step_id="step_writer_targets_fallback",
        run_id=run.run_id,
        agent_name="writer",
        status="completed",
        retry_count=0,
        payload={"sections": ["security"]},
    )

    assert _target_sections_for_report(run=run, writer_step=writer_step) == [
        "security",
        "feature",
        "pricing",
    ]


def test_numeric_claim_gate_blocks_unsupported_numbers_until_removed() -> None:
    semantic_output = {
        "semantic_audit_passed": True,
        "reject_to": "writer",
        "severity": "warning",
        "finding": "Looks fine.",
        "required_fields": [],
        "unsupported_numeric_claims": [
            {
                "claim": "效率提升 28%",
                "section_id": "efficiency",
                "reason": "Evidence does not mention 28%.",
            }
        ],
    }

    first_round = _apply_numeric_claim_gate(
        semantic_output=semantic_output,
        qa_rejection_count=0,
        has_blocking_failures_pre_semantic=False,
    )
    retry_round = _apply_numeric_claim_gate(
        semantic_output=semantic_output,
        qa_rejection_count=1,
        has_blocking_failures_pre_semantic=False,
    )

    assert first_round["semantic_audit_passed"] is False
    assert first_round["severity"] == "blocking"
    assert first_round["reject_to"] == "writer"
    assert "reports.content_json.sections[].evidence_refs" in first_round["required_fields"]
    assert retry_round["semantic_audit_passed"] is False
    assert retry_round["severity"] == "blocking"


def test_numeric_claim_gate_ignores_positioning_map_claims() -> None:
    semantic_output = {
        "semantic_audit_passed": True,
        "reject_to": "writer",
        "severity": "warning",
        "finding": "Looks fine.",
        "required_fields": [],
        "unsupported_numeric_claims": [
            {
                "claim": "Q1 high capability / high maturity: none",
                "section_id": "positioning_map",
                "reason": "Quadrant label detected as numeric claim.",
            }
        ],
    }

    gated = _apply_numeric_claim_gate(
        semantic_output=semantic_output,
        qa_rejection_count=0,
        has_blocking_failures_pre_semantic=False,
    )

    assert gated["semantic_audit_passed"] is True
    assert gated["severity"] == "warning"
    assert _unsupported_numeric_claims(gated) == []


def test_engine_aggregation_rejects_when_blocking_failed() -> None:
    rule_results = [
        RuleResult(
            rule_id="rule_report_must_have_markdown_content",
            passed=False,
            severity="blocking",
            reject_to="writer",
            message="markdown missing",
        )
    ]
    result = build_qa_outcome(
        target_step_id="step_writer_001",
        reviewer_step_id="step_qa_001",
        rule_results=rule_results,
        qa_rejection_count=0,
    )
    assert isinstance(result, Rejection)
    assert result.reject_to == "writer"
    assert "rule_report_must_have_markdown_content" in result.failed_rule_ids


def test_build_qa_fast_path_log_fields_surfaces_rule_ids_and_promoted_counts() -> None:
    rule_results = [
        RuleResult(
            rule_id="rule_writer_must_cite_evidence",
            passed=False,
            severity="blocking",
            reject_to="writer",
            message="missing evidence",
        ),
        RuleResult(
            rule_id="rule_writer_no_fallback_mode",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="ok",
        ),
    ]

    fields = _build_qa_fast_path_log_fields(
        mode="applied",
        rule_results=rule_results,
        promoted_qa_rule_ids=["rule_pricing"],
        promoted_rule_metadata={
            "promoted_qa_enforced_count": 1,
            "promoted_qa_parse_error_count": 0,
            "promoted_qa_blocked_rule_ids": ["rule_promoted_rule_pricing"],
        },
    )

    assert fields["failed_rule_ids"] == ["rule_writer_must_cite_evidence"]
    assert fields["blocking_failed_rule_ids"] == ["rule_writer_must_cite_evidence"]
    assert fields["promoted_qa_rule_ids"] == ["rule_pricing"]
    assert fields["promoted_qa_blocked_rule_ids"] == ["rule_promoted_rule_pricing"]
    assert fields["promoted_qa_enforced_count"] == 1
    assert fields["promoted_qa_parse_error_count"] == 0


def test_build_qa_slow_path_log_fields_surfaces_semantic_preview() -> None:
    rule_results = [
        RuleResult(
            rule_id="rule_qa_semantic_audit",
            passed=False,
            severity="blocking",
            reject_to="writer",
            message="semantic issue",
        )
    ]
    response = LLMResponse(
        model_slot="qa",
        provider="fake",
        model_name="fake-qa",
        prompt_preview="preview",
        prompt_hash="hash",
        content={},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error=None,
        fallback_used=False,
    )

    fields = _build_qa_slow_path_log_fields(
        mode="applied",
        rule_results=rule_results,
        semantic_output={
            "semantic_audit_passed": False,
            "finding": "x" * 350,
            "reject_to": "writer",
            "severity": "blocking",
        },
        semantic_response=response,
        schema_error="finding is required",
    )

    assert fields["failed_rule_ids"] == ["rule_qa_semantic_audit"]
    assert fields["semantic_finding_preview"] == "x" * 300
    assert fields["semantic_reject_to"] == "writer"
    assert fields["semantic_severity"] == "blocking"
    assert fields["schema_error"] == "finding is required"


def test_semantic_dimension_rule_results_block_on_false_dimension() -> None:
    semantic_output = {
        "semantic_audit_passed": True,
        "reject_to": "writer",
        "severity": "warning",
        "finding": "Looks acceptable.",
        "dimension_results": {
            "depth": True,
            "citation_coverage": False,
            "faithfulness": True,
            "instruction_following": True,
        },
        "unsupported_numeric_claims": [],
    }
    rules = _semantic_dimension_rule_results(semantic_output)
    assert len(rules) == 1
    assert rules[0].rule_id == "rule_qa_semantic_citation_coverage"
    assert rules[0].passed is False
    assert rules[0].severity == "blocking"
    assert rules[0].reject_to == "writer"
    assert "Actionable finding: Looks acceptable." in rules[0].message


def test_semantic_dimension_rule_results_can_downgrade_to_warning() -> None:
    semantic_output = {
        "semantic_audit_passed": True,
        "reject_to": "analyst",
        "severity": "warning",
        "finding": "Executive summary contradicts positioning signal.",
        "dimension_results": {
            "depth": False,
            "citation_coverage": True,
            "faithfulness": True,
            "instruction_following": True,
        },
    }

    rules = _semantic_dimension_rule_results(
        semantic_output,
        severity="warning",
    )
    assert len(rules) == 1
    assert rules[0].rule_id == "rule_qa_semantic_depth"
    assert rules[0].severity == "warning"
    assert rules[0].reject_to == "analyst"


def test_semantic_fail_closed_rule_result_from_llm_error() -> None:
    semantic_response = LLMResponse(
        model_slot="qa",
        provider="fake",
        model_name="fake-qa",
        prompt_preview="preview",
        prompt_hash="hash",
        content={},
        prompt_tokens=1,
        completion_tokens=1,
        latency_ms=1,
        error="provider timeout",
        fallback_used=False,
    )
    rule = _semantic_fail_closed_rule_result(
        semantic_response=semantic_response,
        schema_error=None,
    )
    assert rule.rule_id == "rule_qa_semantic_audit"
    assert rule.passed is False
    assert rule.severity == "blocking"
    assert "semantic_llm_error=provider timeout" in rule.message


def test_engine_aggregation_approves_when_all_rules_pass() -> None:
    rule_results = [
        RuleResult(
            rule_id="rule_report_must_have_markdown_content",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="ok",
        ),
        RuleResult(
            rule_id="rule_report_template_id_present",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="ok",
        ),
        RuleResult(
            rule_id="rule_report_must_have_at_least_one_section",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="ok",
        ),
        RuleResult(
            rule_id="rule_evidence_must_be_desensitized",
            passed=True,
            severity="blocking",
            reject_to="researcher",
            message="ok",
        ),
    ]
    result = build_qa_outcome(
        target_step_id="step_writer_001",
        reviewer_step_id="step_qa_001",
        rule_results=rule_results,
        qa_rejection_count=0,
    )
    assert isinstance(result, Approval)
    assert result.semantic_audit_passed is True
    assert len(result.passed_rule_ids) == 4


def test_engine_aggregation_keeps_warning_rule_ids_on_approval() -> None:
    rule_results = [
        RuleResult(
            rule_id="rule_report_must_have_markdown_content",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="ok",
        ),
        RuleResult(
            rule_id="rule_locale_mismatch",
            passed=False,
            severity="warning",
            reject_to="researcher",
            message="low locale match",
        ),
    ]

    result = build_qa_outcome(
        target_step_id="step_writer_001",
        reviewer_step_id="step_qa_001",
        rule_results=rule_results,
        qa_rejection_count=0,
    )

    assert isinstance(result, Approval)
    assert result.warning_rule_ids == ["rule_locale_mismatch"]


def test_qa_warning_items_classifies_visible_warning_metadata() -> None:
    warnings = _qa_warning_items(
        qa_payload={
            "warning_rule_ids": [
                "rule_buyer_critical_sections_need_official_source",
                "rule_locale_mismatch",
            ]
        },
        semantic_metadata={
            "qa_unsupported_numeric_claims": [
                {"claim": "提升 28%", "reason": "Evidence does not support it."}
            ]
        },
    )

    assert warnings == [
        {
            "category": "missing_official_source",
            "rule_id": "rule_buyer_critical_sections_need_official_source",
            "message": "QA warning: rule_buyer_critical_sections_need_official_source",
        },
        {
            "category": "locale_risk",
            "rule_id": "rule_locale_mismatch",
            "message": "QA warning: rule_locale_mismatch",
        },
        {
            "category": "numeric_claim_unsupported",
            "rule_id": "qa_unsupported_numeric_claims",
            "message": "报告包含未被引用证据支持的数字结论。",
            "count": 1,
        },
    ]
