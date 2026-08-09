from __future__ import annotations

from datetime import datetime, timezone
from types import SimpleNamespace

import pytest

from agents.nodes.researcher import _build_evidence_rows, _build_initial_substate, researcher_node
from agents.subgraphs.researcher import _append_evidence_drafts, _effective_action_dimension
from models.step import Step
from service.collector.source_resolver import SourceResolutionResult
from service.event_bus import RunEventType
from schemas.supervisor import ConductResearch


def test_build_evidence_rows_strips_null_bytes_from_text_fields() -> None:
    rows, ids, dropped_dimensions = _build_evidence_rows(
        run_id="run_nul_test",
        step_id="step_nul_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["product_market_positioning"],
        evidence_drafts=[
            {
                "dimension": "product_market_positioning",
                "competitor_id": "通义灵码",
                "quote": "通义灵码\x00官方介绍覆盖代码补全、企业权限、审计日志和知识库检索等能力，适合研发团队在私有化环境部署，并支持跨语言协作流程。官方文档还说明了模型接入策略、团队空间治理、代码评审协同、合规模块配置、审批链路和故障追踪流程。",
                "sanitized_text": "通义灵码\x00官方介绍覆盖代码补全、企业权限、审计日志和知识库检索等能力，适合研发团队在私有化环境部署，并支持跨语言协作流程。官方文档还说明了模型接入策略、团队空间治理、代码评审协同、合规模块配置、审批链路和故障追踪流程。",
                "source_type": "article",
                "source_url": "https://example.com/\x00page",
                "source_title": "通义灵码\x00产品说明",
                "desensitized": True,
                "metadata": {},
            }
        ],
        observations_log=[],
        default_competitor_id="通义灵码",
    )
    assert len(rows) == 1
    assert len(ids) == 1
    assert dropped_dimensions["count"] == 1
    assert dropped_dimensions["reasons"]["low_semantic"] == 1
    row = rows[0]
    assert "\x00" not in row.quote
    assert "\x00" not in row.sanitized_text
    assert row.source_url is not None and "\x00" not in row.source_url
    assert row.source_title is not None and "\x00" not in row.source_title


def test_initial_researcher_substate_raises_turn_budget_to_cover_focus_dimensions() -> None:
    substate = _build_initial_substate(
        run_id="run_turn_budget_test",
        step_id="step_turn_budget_test",
        request=ConductResearch(
            research_topic="Cursor dimensions",
            competitor_id="Cursor",
            focus_dimensions=["core_features", "pricing", "security", "integrations"],
            max_iterations=2,
            fallback_to_offline=True,
        ),
        focus_dimensions=["core_features", "pricing", "security", "integrations"],
        domain_hint=None,
        market_scope="中国市场",
        response_language="zh",
        reference_urls=[],
        resolved_official_urls=[],
        resolved_official_hosts=[],
        resolved_source_pages=[],
        search_attempts_per_dim=2,
        target_category=None,
        category_aliases=[],
        excluded_categories=[],
        market_segments=[],
        scope_policy=None,
    )

    # 4 dimensions x (2 searches + 1 fetch) = 12 turns, well above max_iterations.
    assert substate["max_turns"] == 12
    assert substate["search_attempts_per_dim"] == 2
    assert substate["market_scope"] == "中国市场"
    assert substate["response_language"] == "zh"


def test_build_evidence_rows_keeps_out_of_focus_dimension_as_unclassified() -> None:
    rows, ids, dropped_dimensions = _build_evidence_rows(
        run_id="run_dimension_test",
        step_id="step_dimension_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "User Feedback",
                "competitor_id": "Cursor",
                "quote": "Cursor users discuss onboarding friction, enterprise SSO setup steps, repository permission audits, and procurement review workflow in long-form forum feedback for team administrators.",
                "sanitized_text": "Cursor users discuss onboarding friction, enterprise SSO setup steps, repository permission audits, and procurement review workflow in long-form forum feedback for team administrators.",
                "source_type": "article",
                "source_url": "https://example.com/review",
                "source_title": "Cursor Review",
                "desensitized": True,
                "metadata": {},
            }
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert len(ids) == 1
    assert rows[0].span["dimension"] is None
    assert rows[0].span["dimension_drop_reason"] == "out_of_focus"
    assert dropped_dimensions == {"count": 1, "reasons": {"out_of_focus": 1}}


def test_build_evidence_rows_keeps_missing_dimension_as_unclassified() -> None:
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_missing_dimension_test",
        step_id="step_missing_dimension_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "competitor_id": "Cursor",
                "quote": "Cursor publishes a public price point, outlines annual discounts, clarifies enterprise seat minimums, and explains invoicing controls for legal and finance teams.",
                "sanitized_text": "Cursor publishes a public price point, outlines annual discounts, clarifies enterprise seat minimums, and explains invoicing controls for legal and finance teams.",
                "source_type": "article",
                "source_url": "https://example.com/pricing",
                "source_title": "Cursor Pricing",
                "desensitized": True,
                "metadata": {},
            }
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert rows[0].span["dimension"] is None
    assert dropped_dimensions == {"count": 1, "reasons": {"missing": 1}}


def test_build_evidence_rows_inherits_dimension_from_observation_args() -> None:
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_observation_dimension_test",
        step_id="step_observation_dimension_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[],
        observations_log=[
            {
                "tool": "fetch_url",
                "args": {
                    "url": "https://cursor.com/pricing",
                    "competitor_id": "Cursor",
                    "dimension": "pricing",
                },
                "result": {
                    "snippets": [
                        {
                                "quote": "Cursor publishes pricing details for team buyers, including annual billing discounts, admin seat controls, and procurement checkpoints used by enterprise finance teams.",
                                "sanitized_text": "Cursor publishes pricing details for team buyers, including annual billing discounts, admin seat controls, and procurement checkpoints used by enterprise finance teams.",
                            "source_url": "https://cursor.com/pricing",
                            "source_title": "Cursor Pricing",
                            "source_type": "pricing_page",
                            "metadata": {},
                        }
                    ]
                },
            }
        ],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert rows[0].span["dimension"] == "pricing"
    assert rows[0].span["competitor_id"] == "Cursor"
    assert dropped_dimensions == {"count": 0, "reasons": {}}


def test_build_evidence_rows_uses_sanitized_text_when_quote_missing() -> None:
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_observation_sanitized_fallback_test",
        step_id="step_observation_sanitized_fallback_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[],
        observations_log=[
            {
                "tool": "search_web",
                "args": {
                    "query": "Cursor pricing",
                    "competitor_id": "Cursor",
                    "dimension": "pricing",
                },
                "result": {
                    "snippets": [
                        {
                            "quote": None,
                            "sanitized_text": (
                                "Cursor pricing page lists team and enterprise plans for procurement reviews in detail, "
                                "including annual billing policy, seat governance checkpoints, finance approval flow, "
                                "and contract terms used by enterprise buyers evaluating production rollout."
                            ),
                            "source_url": "https://cursor.com/pricing",
                            "source_title": "Cursor Pricing",
                            "source_type": "pricing_page",
                            "metadata": {},
                        }
                    ]
                },
            }
        ],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert rows[0].quote.startswith("Cursor pricing page")
    assert rows[0].sanitized_text.startswith("Cursor pricing page")
    assert dropped_dimensions == {"count": 0, "reasons": {}}


def test_build_evidence_rows_persists_multilingual_source_language() -> None:
    english_quote = (
        "Cursor pricing page explains annual billing, enterprise controls, seat governance, "
        "security review expectations, procurement checkpoints, and finance approval workflows "
        "for software teams comparing rollout readiness across paid tiers."
    )
    japanese_quote = (
        "Cursorの価格ページでは、年間契約、エンタープライズ管理、調達フロー、監査対応、"
        "運用ガバナンス、導入計画の詳細が説明されており、企業チームが比較検討するための"
        "具体的な判断材料が継続的に更新されています。"
        "Cursorの価格ページでは、年間契約、エンタープライズ管理、調達フロー、監査対応、"
        "運用ガバナンス、導入計画の詳細が説明されており、企業チームが比較検討するための"
        "具体的な判断材料が継続的に更新されています。"
    )
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_source_language_test",
        step_id="step_source_language_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": english_quote,
                "sanitized_text": english_quote,
                "source_type": "article",
                "source_url": "https://example.com/en-pricing",
                "source_title": "Cursor Pricing EN",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": japanese_quote,
                "sanitized_text": japanese_quote,
                "source_type": "article",
                "source_url": "https://example.com/ja-pricing",
                "source_title": "Cursor Pricing JA",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 2
    language_by_url = {
        row.source_url: row.span.get("source_language")
        for row in rows
        if isinstance(row.source_url, str) and isinstance(row.span, dict)
    }
    assert language_by_url["https://example.com/en-pricing"] == "en"
    assert language_by_url["https://example.com/ja-pricing"] == "ja"
    assert dropped_dimensions == {"count": 0, "reasons": {}}


def test_build_evidence_rows_dedupes_draft_and_observation_path() -> None:
    quote = (
        "Cursor publishes pricing details for team buyers, including annual billing discounts, "
        "admin seat controls, and procurement checkpoints used by enterprise finance teams."
    )
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_dedupe_test",
        step_id="step_dedupe_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": quote,
                "sanitized_text": quote,
                "source_type": "pricing_page",
                "source_url": "https://cursor.com/pricing",
                "source_title": "Cursor Pricing",
                "desensitized": True,
                "metadata": {},
            }
        ],
        observations_log=[
            {
                "tool": "fetch_url",
                "args": {"competitor_id": "Cursor", "dimension": "pricing"},
                "result": {
                    "snippets": [
                        {
                            "quote": quote,
                            "sanitized_text": quote,
                            "source_url": "https://cursor.com/pricing",
                            "source_title": "Cursor Pricing",
                            "source_type": "pricing_page",
                            "metadata": {},
                        }
                    ]
                },
            }
        ],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert rows[0].quote == quote
    assert dropped_dimensions == {"count": 0, "reasons": {}}


def test_build_evidence_rows_keeps_same_url_for_different_dimensions() -> None:
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_same_url_dimensions_test",
        step_id="step_same_url_dimensions_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing", "security"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Cursor pricing includes a public team plan, annual discounts, billing contact workflows, and procurement requirements that help finance teams evaluate predictable spend for software organizations.",
                "sanitized_text": "Cursor pricing includes a public team plan, annual discounts, billing contact workflows, and procurement requirements that help finance teams evaluate predictable spend for software organizations.",
                "source_type": "pricing_page",
                "source_url": "https://cursor.com/pricing",
                "source_title": "Cursor Pricing",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "security",
                "competitor_id": "Cursor",
                "quote": "Cursor security controls are described for enterprise buyers, including workspace policy management, audit logs, SSO integration guidance, and administrator review procedures for regulated teams.",
                "sanitized_text": "Cursor security controls are described for enterprise buyers, including workspace policy management, audit logs, SSO integration guidance, and administrator review procedures for regulated teams.",
                "source_type": "pricing_page",
                "source_url": "https://cursor.com/pricing",
                "source_title": "Cursor Pricing",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 2
    assert {row.span["dimension"] for row in rows} == {"pricing", "security"}
    assert dropped_dimensions == {"count": 0, "reasons": {}}


def test_append_evidence_drafts_keeps_same_url_for_different_dimensions() -> None:
    drafts = _append_evidence_drafts(
        evidence_drafts=[],
        observation={
            "competitor_id": "Cursor",
            "snippets": [
                {
                    "quote": "Cursor pricing includes a public team plan.",
                    "sanitized_text": "Cursor pricing includes a public team plan.",
                    "source_url": "https://cursor.com/pricing",
                    "source_title": "Cursor Pricing",
                    "source_type": "pricing_page",
                    "metadata": {"dimension": "pricing"},
                },
                {
                    "quote": "Cursor security controls are described for enterprise buyers.",
                    "sanitized_text": "Cursor security controls are described for enterprise buyers.",
                    "source_url": "https://cursor.com/pricing",
                    "source_title": "Cursor Pricing",
                    "source_type": "pricing_page",
                    "metadata": {"dimension": "security"},
                },
            ],
        },
        focus_dimensions=["pricing", "security"],
    )

    assert len(drafts) == 2
    assert {draft["dimension"] for draft in drafts} == {"pricing", "security"}


def test_append_evidence_drafts_dedupes_same_identity_only() -> None:
    drafts = _append_evidence_drafts(
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Cursor pricing includes a public team plan.",
                "source_url": "https://cursor.com/pricing",
            }
        ],
        observation={
            "competitor_id": "Cursor",
            "dimension": "pricing",
            "snippets": [
                {
                    "quote": "Cursor pricing includes a public team plan.",
                    "sanitized_text": "Cursor pricing includes a public team plan.",
                    "source_url": "https://cursor.com/pricing",
                    "source_title": "Cursor Pricing",
                    "source_type": "pricing_page",
                    "metadata": {},
                },
                {
                    "quote": "Cursor pricing also documents enterprise billing controls.",
                    "sanitized_text": "Cursor pricing also documents enterprise billing controls.",
                    "source_url": "https://cursor.com/pricing",
                    "source_title": "Cursor Pricing",
                    "source_type": "pricing_page",
                    "metadata": {},
                },
            ],
        },
        focus_dimensions=["pricing"],
    )

    assert len(drafts) == 2
    assert drafts[1]["quote"] == "Cursor pricing also documents enterprise billing controls."


def test_build_evidence_rows_applies_source_quality_gate() -> None:
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_source_quality_test",
        step_id="step_source_quality_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Welcome back. Continue with Google. Sign in to continue.",
                "sanitized_text": "Welcome back. Continue with Google. Sign in to continue.",
                "source_type": "article",
                "source_url": "https://example.com/login",
                "source_title": "Login",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "--- | --- | ---",
                "sanitized_text": "--- | --- | ---",
                "source_type": "article",
                "source_url": "https://example.com/table",
                "source_title": "Table",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "LinkedIn login wall content for a competitor page.",
                "sanitized_text": "LinkedIn login wall content for a competitor page.",
                "source_type": "article",
                "source_url": "https://www.linkedin.com/login",
                "source_title": "LinkedIn",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Cursor publishes paid team plan details, annual contract options, enterprise controls, legal terms, and billing responsibilities for procurement and finance stakeholders evaluating large deployments.",
                "sanitized_text": "Cursor publishes paid team plan details, annual contract options, enterprise controls, legal terms, and billing responsibilities for procurement and finance stakeholders evaluating large deployments.",
                "source_type": "pricing_page",
                "source_url": "https://cursor.com/pricing",
                "source_title": "Cursor Pricing",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert rows[0].source_url == "https://cursor.com/pricing"
    assert dropped_dimensions["reasons"]["source_blocklist"] == 2
    assert dropped_dimensions["reasons"]["low_semantic"] == 1


def test_build_evidence_rows_marks_source_authority_and_competitor_match() -> None:
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_source_authority_test",
        step_id="step_source_authority_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Cursor pricing page describes team plans for buyers, annual billing policy, enterprise procurement guardrails, and account administration expectations for larger software teams.",
                "sanitized_text": "Cursor pricing page describes team plans for buyers, annual billing policy, enterprise procurement guardrails, and account administration expectations for larger software teams.",
                "source_type": "article",
                "source_url": "https://cursor.com/pricing",
                "source_title": "Cursor Pricing",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "BillingPlatform compares Cursor with other B2B vendors and discusses how Cursor pricing automation, invoice governance, and enterprise controls differ across procurement scenarios.",
                "sanitized_text": "BillingPlatform compares Cursor with other B2B vendors and discusses how Cursor pricing automation, invoice governance, and enterprise controls differ across procurement scenarios.",
                "source_type": "pricing_page",
                "source_url": "https://billingplatform.com/pricing",
                "source_title": "Cursor and BillingPlatform Pricing",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 2
    official_row = next(row for row in rows if row.source_url == "https://cursor.com/pricing")
    mismatch_row = next(row for row in rows if row.source_url == "https://billingplatform.com/pricing")
    assert official_row.source_type == "pricing_page"
    assert official_row.span["source_authority"] == "official"
    assert official_row.span["competitor_source_match"] is True
    assert mismatch_row.span["source_authority"] == "third_party"
    assert mismatch_row.span["competitor_source_match"] is False
    assert dropped_dimensions == {"count": 0, "reasons": {}}


def test_build_evidence_rows_downgrades_cross_vendor_official_source_type() -> None:
    rows, _, _ = _build_evidence_rows(
        run_id="run_cross_vendor_test",
        step_id="step_cross_vendor_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "A GitHub docs page that upstream tools labeled official by host union.",
                "sanitized_text": "A GitHub docs page that upstream tools labeled official by host union.",
                "source_type": "official_site",
                "source_url": "https://github.com/features/copilot",
                "source_title": "GitHub Copilot",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    row = rows[0]
    assert row.source_type == "article"
    assert row.span["competitor_source_match"] is False
    assert row.span["source_authority"] == "third_party"


def test_build_evidence_rows_uses_runtime_resolved_official_hosts_for_match() -> None:
    rows, _, _ = _build_evidence_rows(
        run_id="run_runtime_official_hosts",
        step_id="step_runtime_official_hosts",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "NewVendor",
                "quote": "NewVendor pricing page shows paid plans.",
                "sanitized_text": "NewVendor pricing page shows paid plans.",
                "source_type": "article",
                "source_url": "https://newvendor.ai/pricing",
                "source_title": "NewVendor Pricing",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="NewVendor",
        resolved_official_hosts={"newvendor.ai"},
    )

    assert len(rows) == 1
    row = rows[0]
    assert row.source_type == "pricing_page"
    assert row.span["competitor_source_match"] is True
    assert row.span["source_authority"] == "official"


def test_build_evidence_rows_grounds_official_host_page_without_name_mention() -> None:
    rows, _, dropped = _build_evidence_rows(
        run_id="run_official_grounding",
        step_id="step_official_grounding",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "NewVendor",
                # Body never repeats the vendor name; only the official host attributes it.
                "quote": "Team plans start at $20 per seat.",
                "sanitized_text": (
                    "Team plans start at $20 per seat with enterprise controls, SSO, "
                    "audit logs, and dedicated onboarding included for procurement and "
                    "administration teams evaluating a company-wide rollout this quarter."
                ),
                "source_type": "pricing_page",
                "source_url": "https://newvendor.ai/pricing",
                "source_title": "Pricing",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "NewVendor",
                # Third-party page with no name mention stays an ungrounded miss.
                "quote": "Generic market commentary.",
                "sanitized_text": (
                    "An independent blog discusses developer tooling budgets, seat-based "
                    "billing trends, and enterprise procurement cycles without ever naming "
                    "the specific vendor under review in this lengthy editorial overview."
                ),
                "source_type": "article",
                "source_url": "https://news.example.com/tooling-budgets",
                "source_title": "Tooling budgets",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="NewVendor",
        resolved_official_hosts={"newvendor.ai"},
    )

    assert len(rows) == 1
    assert rows[0].source_url == "https://newvendor.ai/pricing"
    assert rows[0].span["source_authority"] == "official"
    assert rows[0].span.get("evidence_floor") is not True
    assert dropped["reasons"]["competitor_grounding_miss"] == 1


def test_build_evidence_rows_marks_market_report_as_authoritative() -> None:
    rows, _, _ = _build_evidence_rows(
        run_id="run_market_report_authority",
        step_id="step_market_report_authority",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["market_size"],
        evidence_drafts=[
            {
                "dimension": "market_size",
                "competitor_id": "AI Glasses",
                "quote": "CAICT published a market report on AI hardware adoption.",
                "sanitized_text": "CAICT published a market report on AI hardware adoption.",
                "source_type": "article",
                "source_url": "https://www.caict.ac.cn/reports/ai-hardware",
                "source_title": "AI Hardware Market Report",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="AI Glasses",
    )

    assert len(rows) == 1
    row = rows[0]
    assert row.source_type == "market_report"
    assert row.span["source_authority"] == "authoritative_report"
    assert row.span["source_authority_reason"] == "market_report_source_type"


def test_build_evidence_rows_drops_homepage_and_ungrounded_candidates() -> None:
    rows, _, dropped_dimensions = _build_evidence_rows(
        run_id="run_grounding_gate_test",
        step_id="step_grounding_gate_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Cursor official website homepage.",
                "sanitized_text": "Cursor official website homepage.",
                "source_type": "article",
                "source_url": "https://cursor.com/",
                "source_title": "Cursor",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Windsurf announces updated monthly plans, enterprise bundle discounts, account administration workflows, and customer support commitments in a long-form launch article for procurement teams.",
                "sanitized_text": "Windsurf announces updated monthly plans, enterprise bundle discounts, account administration workflows, and customer support commitments in a long-form launch article for procurement teams.",
                "source_type": "article",
                "source_url": "https://news.example.com/tools-pricing",
                "source_title": "AI tools pricing update",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Cursor team plan starts at $20 and includes enterprise controls.",
                "sanitized_text": "Cursor team plan starts at $20 and includes enterprise controls.",
                "source_type": "pricing_page",
                "source_url": "https://cursor.com/pricing",
                "source_title": "Cursor pricing",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert rows[0].source_url == "https://cursor.com/pricing"
    assert dropped_dimensions["reasons"]["source_blocklist"] == 1
    assert dropped_dimensions["reasons"]["competitor_grounding_miss"] == 1


@pytest.mark.asyncio
async def test_researcher_node_enriches_candidate_urls_with_official_search(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    added_rows: list[object] = []
    resolved_candidate_urls: list[str] = []

    class _FakeSession:
        async def __aenter__(self) -> "_FakeSession":
            return self

        async def __aexit__(self, *_: object) -> None:
            return None

        def add(self, row: object) -> None:
            added_rows.append(row)

        async def flush(self) -> None:
            return None

        async def commit(self) -> None:
            return None

    class _FakeSubgraph:
        async def ainvoke(self, _: object, *, config: object | None = None) -> dict[str, object]:
            return {
                "evidence_drafts": [],
                "observations_log": [],
                "llm_calls": [],
                "turn_count": 1,
                "compression_count": 0,
                "queried_dimensions": ["pricing"],
                "search_call_count": 1,
                "official_fetch_count": 0,
                "coverage_matrix": {},
                "final_summary": "",
            }

    class _FakeRegistry:
        def __init__(self) -> None:
            self.calls: list[tuple[str, dict[str, object]]] = []

        async def invoke(self, action: str, *, args: dict[str, object]) -> SimpleNamespace:
            self.calls.append((action, dict(args)))
            return SimpleNamespace(
                result=SimpleNamespace(
                    snippets=[
                        SimpleNamespace(source_url="https://newvendor.ai"),
                        SimpleNamespace(source_url="https://newvendor.ai/pricing"),
                    ]
                )
            )

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    async def _fake_resolve_official_sources(
        *,
        competitor_id: str,
        competitor_name: str,
        candidate_urls: list[str],
        candidate_url_budget: int = 5,
        key_page_budget: int = 5,
        http_client: object | None = None,
    ) -> SourceResolutionResult:
        del competitor_id, competitor_name, candidate_url_budget, key_page_budget, http_client
        resolved_candidate_urls.extend(candidate_urls)
        return SourceResolutionResult(
            official_urls=[],
            official_hosts=[],
            key_pages=[],
            attempted_candidate_count=len(candidate_urls),
            validated_candidate_count=0,
        )

    fake_registry = _FakeRegistry()
    monkeypatch.setattr("agents.nodes.researcher.get_session_factory", lambda: _FakeSession)
    monkeypatch.setattr("agents.nodes.researcher.get_researcher_subgraph", lambda: _FakeSubgraph())
    monkeypatch.setattr("agents.nodes.researcher.get_channel_registry", lambda: fake_registry)
    monkeypatch.setattr("agents.nodes.researcher.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.researcher.resolve_official_sources", _fake_resolve_official_sources)

    await researcher_node(
        {
            "run_id": "run_official_url_candidates",
            "market_scope": "中国市场",
            "response_language": "zh",
            "pending_tool_args": {
                "research_topic": "NewVendor pricing",
                "competitor_id": "NewVendor",
                "focus_dimensions": ["pricing"],
                "max_iterations": 1,
                "fallback_to_offline": True,
            },
            "researched_competitors": [],
            "reference_urls": ["https://docs.newvendor.ai/pricing"],
        }
    )

    step_rows = [row for row in added_rows if isinstance(row, Step)]
    assert len(step_rows) == 1
    source_resolution = step_rows[0].payload["source_resolution"]
    assert source_resolution["candidate_url_count"] == 3
    assert source_resolution["official_url_search_candidate_count"] == 2
    assert resolved_candidate_urls == [
        "https://docs.newvendor.ai/pricing",
        "https://newvendor.ai",
        "https://newvendor.ai/pricing",
    ]
    assert fake_registry.calls
    first_call_args = fake_registry.calls[0][1]
    assert first_call_args["response_language"] == "zh"
    assert first_call_args["market_scope"] == "中国市场"


@pytest.mark.asyncio
async def test_researcher_node_falls_back_to_intake_locale_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    added_rows: list[object] = []

    class _FakeSession:
        async def __aenter__(self) -> "_FakeSession":
            return self

        async def __aexit__(self, *_: object) -> None:
            return None

        def add(self, row: object) -> None:
            added_rows.append(row)

        async def flush(self) -> None:
            return None

        async def commit(self) -> None:
            return None

    class _FakeSubgraph:
        async def ainvoke(self, _: object, *, config: object | None = None) -> dict[str, object]:
            return {
                "evidence_drafts": [],
                "observations_log": [],
                "llm_calls": [],
                "turn_count": 1,
                "compression_count": 0,
                "queried_dimensions": ["pricing"],
                "search_call_count": 1,
                "official_fetch_count": 0,
                "coverage_matrix": {},
                "final_summary": "",
            }

    class _FakeRegistry:
        def __init__(self) -> None:
            self.calls: list[tuple[str, dict[str, object]]] = []

        async def invoke(self, action: str, *, args: dict[str, object]) -> SimpleNamespace:
            self.calls.append((action, dict(args)))
            return SimpleNamespace(
                result=SimpleNamespace(
                    snippets=[SimpleNamespace(source_url="https://example.cn/official")]
                )
            )

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    async def _fake_resolve_official_sources(
        *,
        competitor_id: str,
        competitor_name: str,
        candidate_urls: list[str],
        candidate_url_budget: int = 5,
        key_page_budget: int = 5,
        http_client: object | None = None,
    ) -> SourceResolutionResult:
        del competitor_id, competitor_name, candidate_urls, candidate_url_budget, key_page_budget, http_client
        return SourceResolutionResult(
            official_urls=[],
            official_hosts=[],
            key_pages=[],
            attempted_candidate_count=0,
            validated_candidate_count=0,
        )

    fake_registry = _FakeRegistry()
    monkeypatch.setattr("agents.nodes.researcher.get_session_factory", lambda: _FakeSession)
    monkeypatch.setattr("agents.nodes.researcher.get_researcher_subgraph", lambda: _FakeSubgraph())
    monkeypatch.setattr("agents.nodes.researcher.get_channel_registry", lambda: fake_registry)
    monkeypatch.setattr("agents.nodes.researcher.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.researcher.resolve_official_sources", _fake_resolve_official_sources)

    await researcher_node(
        {
            "run_id": "run_intake_locale_fallback",
            "pending_tool_args": {
                "research_topic": "测试竞品定价",
                "competitor_id": "测试竞品",
                "focus_dimensions": ["pricing"],
                "max_iterations": 1,
                "fallback_to_offline": True,
            },
            "researched_competitors": [],
            "intake_draft": {
                "domain_hint": "AI硬件",
                "market_scope": "中国市场",
                "response_language": "zh",
                "reference_urls": ["https://example.cn/pricing"],
            },
        }
    )

    assert fake_registry.calls
    first_call_args = fake_registry.calls[0][1]
    assert first_call_args["response_language"] == "zh"
    assert first_call_args["market_scope"] == "中国市场"
    step_rows = [row for row in added_rows if isinstance(row, Step)]
    assert len(step_rows) == 1
    assert step_rows[0].payload["domain_hint"] == "AI硬件"
    assert step_rows[0].payload["market_scope"] == "中国市场"
    assert step_rows[0].payload["response_language"] == "zh"
    assert step_rows[0].payload["reference_urls"] == ["https://example.cn/pricing"]


@pytest.mark.asyncio
async def test_researcher_node_loads_intake_locale_context_from_run_row(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    added_rows: list[object] = []

    class _FakeSession:
        async def __aenter__(self) -> "_FakeSession":
            return self

        async def __aexit__(self, *_: object) -> None:
            return None

        async def get(self, _: object, run_id: str) -> object | None:
            if run_id != "run_persisted_intake_locale":
                return None
            return SimpleNamespace(
                intake_draft={
                    "domain_hint": "AI硬件",
                    "market_scope": "中国市场",
                    "response_language": "zh",
                    "reference_urls": ["https://example.cn/pricing"],
                }
            )

        def add(self, row: object) -> None:
            added_rows.append(row)

        async def flush(self) -> None:
            return None

        async def commit(self) -> None:
            return None

    class _FakeSubgraph:
        async def ainvoke(self, _: object, *, config: object | None = None) -> dict[str, object]:
            return {
                "evidence_drafts": [],
                "observations_log": [],
                "llm_calls": [],
                "turn_count": 1,
                "compression_count": 0,
                "queried_dimensions": ["pricing"],
                "search_call_count": 1,
                "official_fetch_count": 0,
                "coverage_matrix": {},
                "final_summary": "",
            }

    class _FakeRegistry:
        def __init__(self) -> None:
            self.calls: list[tuple[str, dict[str, object]]] = []

        async def invoke(self, action: str, *, args: dict[str, object]) -> SimpleNamespace:
            self.calls.append((action, dict(args)))
            return SimpleNamespace(
                result=SimpleNamespace(
                    snippets=[SimpleNamespace(source_url="https://example.cn/official")]
                )
            )

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    async def _fake_resolve_official_sources(
        *,
        competitor_id: str,
        competitor_name: str,
        candidate_urls: list[str],
        candidate_url_budget: int = 5,
        key_page_budget: int = 5,
        http_client: object | None = None,
    ) -> SourceResolutionResult:
        del competitor_id, competitor_name, candidate_urls, candidate_url_budget, key_page_budget, http_client
        return SourceResolutionResult(
            official_urls=[],
            official_hosts=[],
            key_pages=[],
            attempted_candidate_count=0,
            validated_candidate_count=0,
        )

    fake_registry = _FakeRegistry()
    monkeypatch.setattr("agents.nodes.researcher.get_session_factory", lambda: _FakeSession)
    monkeypatch.setattr("agents.nodes.researcher.get_researcher_subgraph", lambda: _FakeSubgraph())
    monkeypatch.setattr("agents.nodes.researcher.get_channel_registry", lambda: fake_registry)
    monkeypatch.setattr("agents.nodes.researcher.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.researcher.resolve_official_sources", _fake_resolve_official_sources)

    await researcher_node(
        {
            "run_id": "run_persisted_intake_locale",
            "domain_hint": "AI眼镜",
            "pending_tool_args": {
                "research_topic": "测试竞品定价",
                "competitor_id": "测试竞品",
                "focus_dimensions": ["pricing"],
                "max_iterations": 1,
                "fallback_to_offline": True,
            },
            "researched_competitors": [],
        }
    )

    assert fake_registry.calls
    first_call_args = fake_registry.calls[0][1]
    assert first_call_args["response_language"] == "zh"
    assert first_call_args["market_scope"] == "中国市场"
    step_rows = [row for row in added_rows if isinstance(row, Step)]
    assert len(step_rows) == 1
    assert step_rows[0].payload["domain_hint"] == "AI眼镜"
    assert step_rows[0].payload["market_scope"] == "中国市场"
    assert step_rows[0].payload["response_language"] == "zh"
    assert step_rows[0].payload["reference_urls"] == ["https://example.cn/pricing"]


@pytest.mark.asyncio
async def test_researcher_node_merges_partial_state_intake_with_persisted_draft(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    added_rows: list[object] = []

    class _FakeSession:
        async def __aenter__(self) -> "_FakeSession":
            return self

        async def __aexit__(self, *_: object) -> None:
            return None

        async def get(self, _: object, run_id: str) -> object | None:
            if run_id != "run_partial_intake_locale":
                return None
            return SimpleNamespace(
                intake_draft={
                    "domain_hint": "AI硬件",
                    "market_scope": "中国市场",
                    "response_language": "zh",
                    "reference_urls": ["https://example.cn/pricing"],
                }
            )

        def add(self, row: object) -> None:
            added_rows.append(row)

        async def flush(self) -> None:
            return None

        async def commit(self) -> None:
            return None

    class _FakeSubgraph:
        async def ainvoke(self, _: object, *, config: object | None = None) -> dict[str, object]:
            return {
                "evidence_drafts": [],
                "observations_log": [],
                "llm_calls": [],
                "turn_count": 1,
                "compression_count": 0,
                "queried_dimensions": ["pricing"],
                "search_call_count": 1,
                "official_fetch_count": 0,
                "coverage_matrix": {},
                "final_summary": "",
            }

    class _FakeRegistry:
        def __init__(self) -> None:
            self.calls: list[tuple[str, dict[str, object]]] = []

        async def invoke(self, action: str, *, args: dict[str, object]) -> SimpleNamespace:
            self.calls.append((action, dict(args)))
            return SimpleNamespace(
                result=SimpleNamespace(
                    snippets=[SimpleNamespace(source_url="https://example.cn/official")]
                )
            )

    async def _fake_emit_run_event(**_: object) -> None:
        return None

    async def _fake_resolve_official_sources(
        *,
        competitor_id: str,
        competitor_name: str,
        candidate_urls: list[str],
        candidate_url_budget: int = 5,
        key_page_budget: int = 5,
        http_client: object | None = None,
    ) -> SourceResolutionResult:
        del competitor_id, competitor_name, candidate_urls, candidate_url_budget, key_page_budget, http_client
        return SourceResolutionResult(
            official_urls=[],
            official_hosts=[],
            key_pages=[],
            attempted_candidate_count=0,
            validated_candidate_count=0,
        )

    fake_registry = _FakeRegistry()
    monkeypatch.setattr("agents.nodes.researcher.get_session_factory", lambda: _FakeSession)
    monkeypatch.setattr("agents.nodes.researcher.get_researcher_subgraph", lambda: _FakeSubgraph())
    monkeypatch.setattr("agents.nodes.researcher.get_channel_registry", lambda: fake_registry)
    monkeypatch.setattr("agents.nodes.researcher.emit_run_event", _fake_emit_run_event)
    monkeypatch.setattr("agents.nodes.researcher.resolve_official_sources", _fake_resolve_official_sources)

    await researcher_node(
        {
            "run_id": "run_partial_intake_locale",
            "intake_draft": {
                "domain_hint": "AI眼镜",
                "reference_urls": [],
            },
            "pending_tool_args": {
                "research_topic": "测试竞品定价",
                "competitor_id": "测试竞品",
                "focus_dimensions": ["pricing"],
                "max_iterations": 1,
                "fallback_to_offline": True,
            },
            "researched_competitors": [],
        }
    )

    assert fake_registry.calls
    first_call_args = fake_registry.calls[0][1]
    assert first_call_args["response_language"] == "zh"
    assert first_call_args["market_scope"] == "中国市场"
    step_rows = [row for row in added_rows if isinstance(row, Step)]
    assert len(step_rows) == 1
    assert step_rows[0].payload["domain_hint"] == "AI眼镜"
    assert step_rows[0].payload["market_scope"] == "中国市场"
    assert step_rows[0].payload["response_language"] == "zh"
    assert step_rows[0].payload["reference_urls"] == ["https://example.cn/pricing"]


def test_build_evidence_rows_restores_quality_floor_when_gate_filters_all_candidates() -> None:
    rows, ids, dropped_dimensions = _build_evidence_rows(
        run_id="run_source_quality_floor_test",
        step_id="step_source_quality_floor_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Welcome back. Continue with Google. Sign in to continue.",
                "sanitized_text": "Welcome back. Continue with Google. Sign in to continue.",
                "source_type": "article",
                "source_url": "https://example.com/login",
                "source_title": "Login",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "--- | --- | ---",
                "sanitized_text": "--- | --- | ---",
                "source_type": "article",
                "source_url": "https://example.com/table",
                "source_title": "Table",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert len(ids) == 1
    assert rows[0].span["source_quality_floor"] is True
    assert rows[0].span["source_quality_drop_reason"] == "low_semantic"
    assert dropped_dimensions["reasons"] == {"source_blocklist": 1, "low_semantic": 1}


def test_build_evidence_rows_restores_floor_for_blocklisted_only_candidates() -> None:
    rows, ids, dropped_dimensions = _build_evidence_rows(
        run_id="run_source_quality_floor_blocklist_only",
        step_id="step_source_quality_floor_blocklist_only",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "Cursor official homepage.",
                "sanitized_text": "Cursor official homepage.",
                "source_type": "article",
                "source_url": "https://cursor.com/",
                "source_title": "Cursor",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": "LinkedIn login wall content for a competitor page.",
                "sanitized_text": "LinkedIn login wall content for a competitor page.",
                "source_type": "article",
                "source_url": "https://www.linkedin.com/login",
                "source_title": "LinkedIn",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert len(ids) == 1
    assert rows[0].span["source_quality_floor"] is True
    assert rows[0].span["evidence_floor"] is True
    assert rows[0].span["evidence_floor_reason"] == "source_blocklist"
    assert dropped_dimensions["reasons"] == {"source_blocklist": 2}


def test_build_evidence_rows_restores_floor_for_grounding_miss_only_candidates() -> None:
    rows, ids, dropped_dimensions = _build_evidence_rows(
        run_id="run_grounding_floor_only",
        step_id="step_grounding_floor_only",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote": (
                    "Windsurf introduced team pricing updates for enterprise procurement workflows and annual billing, "
                    "with release notes describing contract approvals, admin seat governance, invoice operations, "
                    "and long-form support commitments for larger software organizations."
                ),
                "sanitized_text": (
                    "Windsurf introduced team pricing updates for enterprise procurement workflows and annual billing, "
                    "with release notes describing contract approvals, admin seat governance, invoice operations, "
                    "and long-form support commitments for larger software organizations."
                ),
                "source_type": "article",
                "source_url": "https://example.com/pricing-update",
                "source_title": "AI pricing update",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="Cursor",
    )

    assert len(rows) == 1
    assert len(ids) == 1
    assert rows[0].span["grounding_floor"] is True
    assert rows[0].span["evidence_floor"] is True
    assert rows[0].span["evidence_floor_reason"] == "competitor_grounding_miss"
    assert dropped_dimensions["reasons"] == {"competitor_grounding_miss": 1}


@pytest.mark.asyncio
async def test_researcher_node_degrades_zero_evidence_without_requeue(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    added_rows: list[object] = []
    captured_events: list[tuple[RunEventType, str | None, dict[str, object]]] = []

    class _FakeSession:
        async def __aenter__(self) -> "_FakeSession":
            return self

        async def __aexit__(self, *_: object) -> None:
            return None

        def add(self, row: object) -> None:
            added_rows.append(row)

        async def flush(self) -> None:
            return None

        async def commit(self) -> None:
            return None

    class _FakeSubgraph:
        async def ainvoke(self, _: object, *, config: object | None = None) -> dict[str, object]:
            return {
                "evidence_drafts": [],
                "observations_log": [],
                "llm_calls": [],
                "turn_count": 1,
                "compression_count": 0,
                "queried_dimensions": ["pricing"],
                "search_call_count": 1,
                "official_fetch_count": 2,
                "coverage_matrix": {
                    "pricing": {
                        "covered": False,
                        "evidence_count": 0,
                        "official_evidence_count": 0,
                        "requires_official": True,
                    }
                },
                "final_summary": "No grounded evidence found.",
            }

    async def _fake_emit_run_event(
        *,
        run_id: str,
        event_type: RunEventType,
        step_id: str | None = None,
        payload: dict[str, object] | None = None,
    ) -> None:
        del run_id
        captured_events.append((event_type, step_id, dict(payload or {})))

    monkeypatch.setattr("agents.nodes.researcher.get_session_factory", lambda: _FakeSession)
    monkeypatch.setattr("agents.nodes.researcher.get_researcher_subgraph", lambda: _FakeSubgraph())
    monkeypatch.setattr("agents.nodes.researcher.emit_run_event", _fake_emit_run_event)

    result = await researcher_node(
        {
            "run_id": "run_zero_evidence",
            "pending_tool_args": {
                "research_topic": "Cursor pricing",
                "competitor_id": "Cursor",
                "focus_dimensions": ["pricing"],
                "max_iterations": 1,
                "fallback_to_offline": True,
            },
            "researched_competitors": [],
        }
    )

    step_rows = [row for row in added_rows if isinstance(row, Step)]
    assert len(step_rows) == 1
    step = step_rows[0]
    assert step.status == "degraded"
    assert step.payload["uncovered"] is True
    assert step.payload["degraded_reason"] == "researcher_zero_evidence"
    assert step.payload["evidence_ids"] == []
    assert step.payload["search_call_count"] == 1
    assert step.payload["official_fetch_count"] == 2
    assert step.payload["coverage_summary"]["total_dimension_count"] == 1
    assert step.payload["coverage_summary"]["uncovered_dimensions"] == ["pricing"]
    evidence_funnel = step.payload["evidence_funnel"]
    assert evidence_funnel["overall"]["search_results"] == 0
    assert evidence_funnel["overall"]["drafts"] == 0
    assert evidence_funnel["overall"]["persisted"] == 0
    assert result["researched_competitors"] == ["Cursor"]
    assert result["researcher_degraded_competitors"] == ["Cursor"]
    assert captured_events[-1][0] == RunEventType.STEP_FINISH
    assert captured_events[-1][2]["status"] == "degraded"
    assert captured_events[-1][2]["evidence_count"] == 0


def test_effective_action_dimension_followup_inherits_recent_search() -> None:
    state = {
        "focus_dimensions": ["core_features", "pricing", "security"],
        "pending_dimensions": ["pricing", "security"],
        "observations_log": [
            {"tool": "search_web", "args": {"dimension": "core_features"}},
        ],
    }
    assert (
        _effective_action_dimension(state=state, action_args={}, action="fetch_url")
        == "core_features"
    )
    assert (
        _effective_action_dimension(
            state=state, action_args={}, action="extract_structured"
        )
        == "core_features"
    )


def test_effective_action_dimension_search_uses_pending_head() -> None:
    state = {
        "focus_dimensions": ["core_features", "pricing", "security"],
        "pending_dimensions": ["pricing", "security"],
        "observations_log": [
            {"tool": "search_web", "args": {"dimension": "core_features"}},
        ],
    }
    assert (
        _effective_action_dimension(state=state, action_args={}, action="search_web")
        == "pricing"
    )


def test_build_evidence_rows_drops_off_topic_category_before_floor_restore() -> None:
    rows, ids, dropped_dimensions = _build_evidence_rows(
        run_id="run_category_gate_test",
        step_id="step_category_gate_test",
        collected_at=datetime.now(timezone.utc),
        focus_dimensions=["pricing", "feature"],
        evidence_drafts=[
            {
                "dimension": "pricing",
                "competitor_id": "vivo",
                "quote": (
                    "vivo smartphone price details describe handset launch pricing, storage tiers, "
                    "carrier retail bundles, and mobile phone channel discounts for consumer buyers, "
                    "but do not mention AI hardware endpoint products or AI glasses."
                ),
                "sanitized_text": (
                    "vivo smartphone price details describe handset launch pricing, storage tiers, "
                    "carrier retail bundles, and mobile phone channel discounts for consumer buyers, "
                    "but do not mention AI hardware endpoint products or AI glasses."
                ),
                "source_type": "article",
                "source_url": "https://example.com/vivo-phone-price",
                "source_title": "vivo smartphone price",
                "desensitized": True,
                "metadata": {},
            },
            {
                "dimension": "feature",
                "competitor_id": "Huawei",
                "quote": (
                    "Huawei OceanStor storage feature documentation describes enterprise storage arrays, "
                    "data protection, snapshot management, and SAN deployment for infrastructure buyers, "
                    "without covering AI hardware endpoint products or AI glasses."
                ),
                "sanitized_text": (
                    "Huawei OceanStor storage feature documentation describes enterprise storage arrays, "
                    "data protection, snapshot management, and SAN deployment for infrastructure buyers, "
                    "without covering AI hardware endpoint products or AI glasses."
                ),
                "source_type": "article",
                "source_url": "https://example.com/huawei-oceanstor",
                "source_title": "Huawei OceanStor storage",
                "desensitized": True,
                "metadata": {},
            },
        ],
        observations_log=[],
        default_competitor_id="vivo",
        target_category="AI硬件",
        category_aliases=["AI硬件", "AI hardware"],
        excluded_categories=["smartphone", "手机", "OceanStor", "storage"],
        market_segments=["AI眼镜"],
        competitor_admissions={"vivo": "watchlist", "Huawei": "watchlist"},
    )

    assert rows == []
    assert ids == []
    assert dropped_dimensions["reasons"]["category:matched_excluded_category"] == 2
