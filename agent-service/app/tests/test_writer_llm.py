from __future__ import annotations

from agents.nodes.writer import (
    _apply_structured_writer_sections,
    _apply_numeric_claim_guardrail,
    _build_fallback_report,
    _render_report_markdown,
)
from schemas.agent_outputs import WriterExecutionContext, WriterReportOutput
from schemas.report_sections import default_outline_for_archetype
from service.llm.prompts import (
    WRITER_SYSTEM_PROMPT,
    build_writer_fallback_user_prompt,
    build_writer_user_prompt,
)


def test_build_writer_prompts_include_required_context() -> None:
    user_prompt = build_writer_user_prompt(
        user_query="compare cursor and windsurf",
        template_id="battlecard_default",
        target_sections=["feature", "pricing"],
        requested_sections=["feature", "pricing"],
        competitors=["comp_cursor", "comp_windsurf"],
        evidence_briefs=[
            {
                "evidence_id": "ev_001",
                "dimension": "feature",
                "competitor_id": "comp_cursor",
                "quote_preview": "repository context indexing",
                "source_title": "Cursor Docs",
                "source_url": "https://cursor.com",
            }
        ],
        allowed_evidence_ids=["ev_001"],
        analyst_summary="Cursor leads in feature depth.",
        analyst_insights=[
            {
                "insight_id": "insight_1",
                "dimension": "feature",
                "finding": "Cursor provides stronger repo-level context.",
                "confidence": "high",
                "evidence_ids": ["ev_001"],
            }
        ],
        analyst_comparisons=[
            {
                "dimension": "feature",
                "cells": [
                    {
                        "competitor_id": "comp_cursor",
                        "stance": "leader",
                        "summary": "Cursor provides stronger repo-level context.",
                        "confidence": "high",
                        "evidence_ids": ["ev_001"],
                    }
                ],
            }
        ],
        risk_flags=["pricing volatility"],
        recommended_sections=["feature", "pricing"],
        qa_reasons=["Unsupported numeric claims."],
        unsupported_numeric_claims=[{"claim": "$40/seat", "section_id": "pricing"}],
        analysis_intent="对比企业版能力和定价",
        market_scope="中国市场",
        response_language="zh",
    )
    fallback_prompt = build_writer_fallback_user_prompt(
        template_id="battlecard_default",
        requested_sections=["feature"],
        evidence_ids=["ev_001"],
        analyst_summary="Cursor leads in feature depth.",
        user_query="compare cursor and windsurf",
        response_language="zh",
        report_depth="deep",
    )

    assert "Writer context" in user_prompt
    assert "- evidence_briefs:" in user_prompt
    assert "- analyst_insights:" in user_prompt
    assert "- analyst_comparisons:" in user_prompt
    assert "- allowed_evidence_ids:" in user_prompt
    assert "- target_sections:" in user_prompt
    assert "- analysis_intent: 对比企业版能力和定价" in user_prompt
    assert "- market_scope: 中国市场" in user_prompt
    assert "- response_language: zh" in user_prompt
    assert "- report_depth: quick" in user_prompt
    assert "[ev_xxx]" in user_prompt
    assert "never output bare ev_xxx or insight_x ids in markdown" in user_prompt
    assert "unsupported_numeric_claims" in user_prompt
    assert "$40/seat" in user_prompt
    assert "Do not create a section titled Executive Summary or 执行摘要" in user_prompt
    assert "legacy workbench sections" in user_prompt
    assert "organize by stakeholder" in user_prompt
    assert "[ev_xxx]" in WRITER_SYSTEM_PROMPT
    assert "Never emit bare ev_xxx ids" in WRITER_SYSTEM_PROMPT
    assert "Write all report output in response_language" in WRITER_SYSTEM_PROMPT
    assert "Exact numbers" in WRITER_SYSTEM_PROMPT
    assert "commercial market-report outline" in WRITER_SYSTEM_PROMPT
    assert "Keep tone deterministic and factual" in WRITER_SYSTEM_PROMPT
    assert "During QA rewrites" in WRITER_SYSTEM_PROMPT
    assert "Fallback writer request" in fallback_prompt
    assert "- allowed_evidence_ids:" in fallback_prompt
    # The degraded path used to drop response_language and emit ungrounded English;
    # guard that it now carries language + grounding so a transport blip cannot
    # silently flip a zh report to English boilerplate.
    assert "- response_language: zh" in fallback_prompt
    assert "- user_query: compare cursor and windsurf" in fallback_prompt
    assert "- report_depth: deep" in fallback_prompt
    assert "Write the report in response_language" in fallback_prompt


def test_writer_report_output_accepts_valid_payload() -> None:
    context = WriterExecutionContext(
        template_id="battlecard_default",
        target_sections=["feature"],
        renderable_sections=["feature"],
        allowed_evidence_ids=frozenset({"ev_001"}),
        allowed_insight_ids=frozenset({"insight_1"}),
    )
    result = WriterReportOutput.parse_llm_content(
        {
            "template_id": "battlecard_default",
            "title": "熊博士竞品战报",
            "executive_summary": "This summary is long enough and grounded by evidence references.",
            "sections": [
                {
                    "section_id": "feature",
                    "title": "Feature Comparison",
                    "content_markdown": (
                        "Cursor delivers stronger repository-level context management while preserving "
                        "developer iteration speed and minimizing repetitive prompt overhead."
                    ),
                    "evidence_refs": ["ev_001"],
                    "insight_refs": ["insight_1"],
                }
            ],
            "risk_callouts": ["pricing volatility"],
        },
        execution_context=context,
    )

    report = result.to_report_content()
    assert report["template_id"] == "battlecard_default"
    assert len(report["sections"]) == 1
    assert report["sections"][0]["evidence_refs"] == ["ev_001"]


def test_numeric_claim_guardrail_downgrades_section_numbers() -> None:
    report_content = {
        "template_id": "default",
        "title": "测试报告",
        "executive_summary": "摘要。",
        "sections": [
            {
                "section_id": "pricing_strategy",
                "title": "定价策略",
                "content_markdown": "旗舰版定价 3799 元，入门版 1899 元，预测区间 1500-2000 元。",
                "evidence_refs": ["ev_001"],
                "insight_refs": [],
            },
            {
                "section_id": "product_positioning",
                "title": "定位",
                "content_markdown": "定位强调生态整合能力。",
                "evidence_refs": ["ev_002"],
                "insight_refs": [],
            },
        ],
        "risk_callouts": [],
    }
    updated, downgraded_sections = _apply_numeric_claim_guardrail(
        report_content=report_content,
        unsupported_numeric_claims=[
            {
                "claim": "预测区间 1500-2000 元",
                "section_id": "pricing_strategy",
                "reason": "unsupported",
            }
        ],
        response_language="zh",
    )

    assert downgraded_sections == ["pricing_strategy"]
    pricing_section = updated["sections"][0]
    assert isinstance(pricing_section, dict)
    pricing_markdown = pricing_section["content_markdown"]
    assert isinstance(pricing_markdown, str)
    assert "3799" not in pricing_markdown
    assert "1899" not in pricing_markdown
    assert "1500" not in pricing_markdown
    assert "2000" not in pricing_markdown
    assert "若干" in pricing_markdown
    assert "numeric_claims_downgraded:pricing_strategy" in updated["risk_callouts"]
    positioning_section = updated["sections"][1]
    assert isinstance(positioning_section, dict)
    assert positioning_section["content_markdown"] == "定位强调生态整合能力。"


def test_numeric_claim_guardrail_preserves_verifiable_numbers_in_deterministic_block() -> None:
    report_content = {
        "template_id": "default",
        "title": "测试报告",
        "executive_summary": "摘要。",
        "sections": [
            {
                "section_id": "comparison_matrix",
                "title": "对比矩阵",
                "content_markdown": "小米目标 2025 年 Q2 发布，预期出货量超 100 万台，预测区间 1500-2000 元。",
                "evidence_refs": ["ev_001"],
                "insight_refs": [],
            },
        ],
        "risk_callouts": [],
    }
    updated, downgraded_sections = _apply_numeric_claim_guardrail(
        report_content=report_content,
        unsupported_numeric_claims=[
            {
                "claim": "预测区间 1500-2000 元",
                "section_id": "comparison_matrix",
                "reason": "unsupported",
            }
        ],
        response_language="zh",
    )

    assert downgraded_sections == ["comparison_matrix"]
    benchmark_markdown = updated["sections"][0]["content_markdown"]
    assert isinstance(benchmark_markdown, str)
    # Only the QA-flagged claim is downgraded.
    assert "1500" not in benchmark_markdown
    assert "2000" not in benchmark_markdown
    # Verifiable structured numbers in a deterministic block survive instead of
    # being blanket-erased to placeholders.
    assert "2025" in benchmark_markdown
    assert "100" in benchmark_markdown
    assert "numeric_claims_downgraded:comparison_matrix" in updated["risk_callouts"]


def test_writer_report_output_counts_top_level_executive_summary_as_covered() -> None:
    context = WriterExecutionContext(
        template_id="battlecard_default",
        target_sections=["executive_summary", "feature"],
        renderable_sections=["executive_summary", "feature"],
        allowed_evidence_ids=frozenset({"ev_001"}),
        allowed_insight_ids=frozenset({"insight_1"}),
    )
    result = WriterReportOutput.parse_llm_content(
        {
            "template_id": "battlecard_default",
            "title": "熊博士竞品战报",
            "executive_summary": "This summary is present and should cover the executive_summary target.",
            "sections": [
                {
                    "section_id": "feature",
                    "title": "Feature Comparison",
                    "content_markdown": (
                        "Feature analysis contains enough detail and cites grounded evidence."
                    ),
                    "evidence_refs": ["ev_001"],
                    "insight_refs": ["insight_1"],
                }
            ],
            "risk_callouts": [],
        },
        execution_context=context,
    )

    assert "uncovered_section:executive_summary" not in result.risk_callouts


def test_writer_report_output_rejects_invalid_evidence_refs() -> None:
    context = WriterExecutionContext(
        template_id="battlecard_default",
        target_sections=["feature"],
        renderable_sections=["feature"],
        allowed_evidence_ids=frozenset({"ev_001"}),
        allowed_insight_ids=frozenset({"insight_1"}),
    )
    try:
        WriterReportOutput.parse_llm_content(
            {
                "template_id": "battlecard_default",
            "title": "熊博士竞品战报",
                "executive_summary": "This summary is long enough and grounded by evidence references.",
                "sections": [
                    {
                        "section_id": "feature",
                        "title": "Feature Comparison",
                        "content_markdown": (
                            "Feature analysis contains enough detail to satisfy QA validation but "
                            "uses an invalid evidence id."
                        ),
                        "evidence_refs": ["ev_missing"],
                        "insight_refs": ["insight_1"],
                    }
                ],
                "risk_callouts": ["pricing volatility"],
            },
            execution_context=context,
        )
        raised = False
    except ValueError:
        raised = True

    assert raised


def test_fallback_report_render_contains_evidence_citations() -> None:
    report_content = _build_fallback_report(
        template_id="battlecard_default",
        target_sections=["feature", "pricing"],
        evidence_ids=["ev_001", "ev_002"],
        analyst_summary="Cursor leads in feature depth.",
        insight_briefs=[
            {
                "insight_id": "insight_1",
                "dimension": "feature",
                "finding": "Cursor provides stronger repo-level context.",
                "confidence": "high",
                "evidence_ids": ["ev_001"],
            }
        ],
        evidence_briefs=[
            {
                "evidence_id": "ev_001",
                "dimension": "feature",
                "competitor_id": "comp_cursor",
                "quote_preview": "repository context indexing",
                "source_title": "Cursor Docs",
                "source_url": "https://cursor.com",
            }
        ],
        risk_flags=["pricing volatility"],
    )
    markdown = _render_report_markdown(
        report_content,
        allowed_evidence_ids={"ev_001", "ev_002"},
    )

    assert "[ev_001]" in markdown
    assert "## Feature" in markdown or "Feature" in markdown


def test_report_markdown_sanitizes_internal_ids() -> None:
    report_content = {
            "title": "熊博士竞品战报",
        "executive_summary": "Summary cites ev_001 and drops ev_missing plus insight_9.",
        "sections": [
            {
                "title": "Feature",
                "content_markdown": (
                    "Cursor leads on context ev_001 and already cites [ev_002]. "
                    "Drop hallucinated ev_fake and internal insight_1."
                ),
                "evidence_refs": ["ev_001", "ev_fake"],
                "insight_refs": ["insight_1"],
            }
        ],
        "risk_callouts": ["Risk tied to ev_002 and not insight_2."],
    }

    markdown = _render_report_markdown(
        report_content,
        allowed_evidence_ids={"ev_001", "ev_002"},
    )

    assert "[ev_001]" in markdown
    assert "[ev_002]" in markdown
    assert "Evidence: [ev_001]" in markdown
    assert "Evidence: [ev_001], [ev_fake]" not in markdown
    assert "ev_fake" not in markdown
    assert "ev_missing" not in markdown
    assert "Insights:" not in markdown
    assert "insight_" not in markdown
    assert " ev_001" not in markdown
    assert " ev_002" not in markdown


def test_report_markdown_localizes_fixed_labels_for_chinese_output() -> None:
    report_content = {
        "title": "国内销售 AI 工具对比",
        "executive_summary": "适合线下拜访团队的工具需要覆盖线索、跟进和邮件协同。",
        "sections": [
            {
                "title": "选型建议",
                "content_markdown": "优先选择能绑定销售流程证据的工具 [ev_001]。",
                "evidence_refs": ["ev_001"],
                "insight_refs": [],
            }
        ],
        "risk_callouts": ["国内可用性需要复核 [ev_001]"],
    }

    markdown = _render_report_markdown(
        report_content,
        allowed_evidence_ids={"ev_001"},
        response_language="zh",
    )

    assert "## 执行摘要" in markdown
    assert "证据: [ev_001]" in markdown
    assert "## 风险提示" in markdown
    assert "## Executive Summary" not in markdown
    assert "Evidence:" not in markdown
    assert "## Risk Callouts" not in markdown


def test_report_markdown_deduplicates_executive_summary_sections() -> None:
    report_content = {
        "title": "测试报告",
        "executive_summary": "顶层执行摘要内容。",
        "sections": [
            {
                "title": "执行摘要：赛道机会与核心结论",
                "content_markdown": "这段应该被跳过。",
                "evidence_refs": [],
                "insight_refs": [],
            },
            {
                "title": "核心发现",
                "content_markdown": "保留的正文内容。",
                "evidence_refs": ["ev_001"],
                "insight_refs": [],
            },
        ],
        "risk_callouts": [],
    }

    markdown = _render_report_markdown(
        report_content,
        allowed_evidence_ids={"ev_001"},
        response_language="zh",
    )

    assert markdown.count("## 执行摘要") == 1
    assert "执行摘要：赛道机会与核心结论" not in markdown
    assert "## 核心发现" in markdown


def test_report_markdown_appends_methodology_section() -> None:
    report_content = {
        "title": "测试报告",
        "executive_summary": "摘要。",
        "sections": [],
        "risk_callouts": [],
    }

    markdown = _render_report_markdown(
        report_content,
        allowed_evidence_ids={"ev_001", "ev_002"},
        response_language="zh",
        evidence_briefs=[
            {
                "evidence_id": "ev_001",
                "competitor_id": "厂商A",
                "source_authority": "official",
                "source_type": "pricing_page",
            },
            {
                "evidence_id": "ev_002",
                "competitor_id": "厂商B",
                "source_authority": "third_party",
                "source_type": "article",
            },
        ],
    )

    assert "## 数据来源与方法论" in markdown
    assert "覆盖竞品: 2 (厂商A, 厂商B)" in markdown
    assert "证据总数: 2" in markdown
    assert "来源等级分布: official: 1, third_party: 1" in markdown
    assert "来源类型分布: article: 1, pricing_page: 1" in markdown
    assert "数据缺口披露: 厂商B: 官方来源和定价页均未覆盖（仅第三方资料）" in markdown


def test_fallback_report_sections_follow_target_sections() -> None:
    report_content = _build_fallback_report(
        template_id="battlecard_default",
        target_sections=["feature", "pricing"],
        evidence_ids=["ev_001", "ev_002", "ev_003"],
        analyst_summary="Summary.",
        insight_briefs=[],
        evidence_briefs=[
            {
                "evidence_id": "ev_001",
                "dimension": "feature",
                "competitor_id": "comp_a",
                "quote_preview": "quote",
                "source_title": "title",
                "source_url": "https://example.com",
            }
        ],
        risk_flags=[],
    )

    section_ids = [section["section_id"] for section in report_content["sections"]]
    assert section_ids == ["feature", "pricing"]
    pricing_section = report_content["sections"][1]
    assert pricing_section["evidence_refs"] == []
    assert "uncovered_section:pricing" in report_content["risk_callouts"]


def test_fallback_report_does_not_round_robin_unmatched_insights_or_evidence() -> None:
    report_content = _build_fallback_report(
        template_id="battlecard_default",
        target_sections=["pricing"],
        evidence_ids=["ev_001"],
        analyst_summary="Summary.",
        insight_briefs=[
            {
                "insight_id": "insight_1",
                "dimension": "feature",
                "finding": "Feature depth is stronger.",
                "confidence": "high",
                "evidence_ids": ["ev_001"],
            }
        ],
        evidence_briefs=[
            {
                "evidence_id": "ev_001",
                "dimension": "feature",
                "competitor_id": "comp_a",
                "quote_preview": "feature quote",
                "source_title": "title",
                "source_url": "https://example.com",
            }
        ],
        risk_flags=[],
    )

    section = report_content["sections"][0]
    assert section["section_id"] == "pricing"
    assert section["evidence_refs"] == []
    assert section["insight_refs"] == []
    assert "uncovered_section:pricing" in report_content["risk_callouts"]


def test_fallback_report_handles_empty_target_sections_without_name_error() -> None:
    report_content = _build_fallback_report(
        template_id=None,
        target_sections=[],
        evidence_ids=["ev_001"],
        analyst_summary="Summary.",
        insight_briefs=[],
        evidence_briefs=[],
        risk_flags=[],
    )

    assert report_content["sections"][0]["section_id"] == "general"
    assert report_content["sections"][0]["evidence_refs"] == []
    assert "uncovered_section:general" in report_content["risk_callouts"]


def test_writer_report_output_allows_template_auto_mode() -> None:
    context = WriterExecutionContext(
        template_id=None,
        target_sections=["go_to_market"],
        renderable_sections=["go_to_market"],
        allowed_evidence_ids=frozenset({"ev_001"}),
        allowed_insight_ids=frozenset(),
    )
    result = WriterReportOutput.parse_llm_content(
        {
            "template_id": "default",
            "title": "Universal Report",
            "executive_summary": "Valid summary with evidence references.",
            "sections": [
                {
                    "section_id": "go_to_market",
                    "title": "Go To Market",
                    "content_markdown": (
                        "This section has enough detail and valid evidence references to pass "
                        "writer normalization under dynamic section mode."
                    ),
                    "evidence_refs": ["ev_001"],
                    "insight_refs": [],
                }
            ],
            "risk_callouts": [],
        },
        execution_context=context,
    )

    report = result.to_report_content()
    assert report["template_id"] == "default"
    assert report["sections"][0]["section_id"] == "go_to_market"


def test_apply_structured_writer_sections_landscape_preserves_llm_narrative() -> None:
    # The LLM owns landscape narrative; only methodology_limits is deterministic, and the
    # apply pass must NOT overwrite LLM prose with key:value template stubs (the old bug).
    llm_key_players = "Meta 凭借生态整合在主分析样本中处于领先，硬件与社交闭环形成壁垒 [ev_001]。"
    llm_landscape = "主分析样本由 Meta 主导，价值链样本提供供给侧解释 [ev_001]。"
    updated = _apply_structured_writer_sections(
        report_content={
            "template_id": "default",
            "title": "熊博士",
            "executive_summary": "summary",
            "sections": [
                {
                    "section_id": section_id,
                    "title": section_id,
                    "content_markdown": content,
                    "evidence_refs": ["ev_001"],
                    "insight_refs": [],
                }
                for section_id, content in (
                    ("executive_takeaways", "核心判断段落，锁定目标品类并说明样本边界 [ev_001]。"),
                    ("market_definition", "市场定义段落，界定 AI眼镜 范围与排除项 [ev_001]。"),
                    ("market_size_growth", "规模与增长驱动的定性分析段落 [ev_001]。"),
                    ("market_segmentation", "细分赛道分层逻辑与样本归属分析 [ev_001]。"),
                    ("competitive_landscape", llm_landscape),
                    ("key_players", llm_key_players),
                    ("value_chain", "产业链与生态角色分析段落 [ev_001]。"),
                    ("opportunities_risks", "机会与风险段落 [ev_001]。"),
                    ("strategic_recommendations", "面向产品与销售团队的可执行建议 [ev_001]。"),
                )
            ],
            "risk_callouts": [],
        },
        target_sections=list(default_outline_for_archetype("landscape")),
        analysis_archetype="landscape",
        response_language="zh",
        report_depth="quick",
        knowledge_payload={
            "schema_version": "schema_v0.2",
            "features": [
                {"competitor_id": "Meta", "name": "语音助手", "evidence_ids": ["ev_001"]},
                {"competitor_id": "NVIDIA", "name": "端侧芯片生态", "evidence_ids": ["ev_002"]},
            ],
            "pricings": [
                {"competitor_id": "Meta", "model": "subscription", "free_plan": False, "enterprise_plan": True, "evidence_ids": ["ev_001"]}
            ],
            "personas": [],
            "feedback": [
                {"competitor_id": "Meta", "sentiment": "positive", "topic": "续航", "summary": "续航较好", "evidence_ids": ["ev_003"]}
            ],
            "missing_reasons": {},
            "coverage": {
                "Meta": {"feature": "complete", "pricing": "complete", "feedback": "partial"},
                "NVIDIA": {"feature": "partial", "pricing": "insufficient_data", "feedback": "insufficient_data"},
            },
            "supporting_target_evidence_ids": {
                "Meta": {"feature": ["ev_001"], "pricing": ["ev_001"], "feedback": ["ev_003"]},
                "NVIDIA": {"feature": ["ev_002"]},
            },
        },
        comparison_briefs=[],
        evidence_briefs=[
            {"evidence_id": "ev_001", "competitor_id": "Meta", "category_relevance": "target"},
            {"evidence_id": "ev_002", "competitor_id": "NVIDIA", "category_relevance": "value_chain"},
            {"evidence_id": "ev_003", "competitor_id": "Meta", "category_relevance": "target"},
        ],
        allowed_evidence_ids={"ev_001", "ev_002", "ev_003"},
        state_competitors=["Meta", "NVIDIA"],
        discovered_competitor_sources={
            "Meta": {"candidate_role": "direct_competitor", "admission_status": "main_player"},
            "NVIDIA": {"candidate_role": "upstream_supplier", "admission_status": "value_chain"},
        },
        self_product=None,
        target_category="AI眼镜",
        category_aliases=["AI眼镜"],
        excluded_categories=[],
        market_segments=[],
        scope_policy="explicit_category",
        preserve_llm_executive_summary=True,
    )

    sections = [item for item in updated["sections"] if isinstance(item, dict)]
    section_ids = [item.get("section_id") for item in sections]
    assert "key_players" in section_ids
    assert "methodology_limits" in section_ids
    assert "competitor_profiles" not in section_ids
    assert "positioning_map" not in section_ids
    assert "comparison_matrix" not in section_ids
    # LLM prose is preserved verbatim, not replaced by a deterministic key:value stub.
    key_players = next(item for item in sections if item.get("section_id") == "key_players")
    assert key_players["content_markdown"] == llm_key_players
    assert "作为关键玩家纳入主分析" not in key_players["content_markdown"]
    landscape = next(item for item in sections if item.get("section_id") == "competitive_landscape")
    assert landscape["content_markdown"] == llm_landscape
    # methodology_limits stays deterministic bookkeeping.
    methodology = next(item for item in sections if item.get("section_id") == "methodology_limits")
    assert "目标品类证据数" in methodology["content_markdown"]
    assert updated["report_degraded_required_sections"] == []


def test_apply_structured_writer_sections_records_degraded_required_sections() -> None:
    updated = _apply_structured_writer_sections(
        report_content={
            "template_id": "default",
            "title": "熊博士",
            "executive_summary": "summary",
            "sections": [
                {
                    "section_id": "opportunities_risks",
                    "title": "机会与风险",
                    "content_markdown": "机会段落 [ev_001]",
                    "evidence_refs": ["ev_001"],
                    "insight_refs": [],
                },
                {
                    "section_id": "strategic_recommendations",
                    "title": "建议",
                    "content_markdown": "建议段落 [ev_001]",
                    "evidence_refs": ["ev_001"],
                    "insight_refs": [],
                },
            ],
            "risk_callouts": [],
        },
        target_sections=list(default_outline_for_archetype("landscape")),
        analysis_archetype="landscape",
        response_language="zh",
        report_depth="quick",
        knowledge_payload={
            "schema_version": "schema_v0.2",
            "features": [],
            "pricings": [],
            "personas": [],
            "feedback": [],
            "missing_reasons": {},
            "coverage": {},
            "supporting_target_evidence_ids": {},
        },
        comparison_briefs=[],
        evidence_briefs=[],
        allowed_evidence_ids=set(),
        state_competitors=["Meta"],
        discovered_competitor_sources={
            "Meta": {"candidate_role": "direct_competitor"},
        },
        self_product=None,
        target_category="AI眼镜",
        category_aliases=["AI眼镜"],
        excluded_categories=[],
        market_segments=[],
        scope_policy="explicit_category",
        preserve_llm_executive_summary=True,
    )

    assert updated["report_degraded_required_sections"] == []
    risk_callouts = updated["risk_callouts"]
    assert isinstance(risk_callouts, list)
    assert not any(item.startswith("report_degraded_required_section:") for item in risk_callouts)
    section_ids = [
        section.get("section_id")
        for section in updated["sections"]
        if isinstance(section, dict)
    ]
    assert "competitor_profiles" not in section_ids
    assert "comparison_matrix" not in section_ids
    assert "positioning_map" not in section_ids
    assert "trend_summary" not in section_ids


def test_apply_structured_writer_sections_preserves_llm_executive_summary() -> None:
    llm_summary = "Meta 在功能深度与商业化上同时领先，XREAL 仍处早期观察阶段。"
    updated = _apply_structured_writer_sections(
        report_content={
            "template_id": "default",
            "title": "熊博士",
            "executive_summary": llm_summary,
            "sections": [],
            "risk_callouts": [],
        },
        target_sections=[
            *default_outline_for_archetype("landscape"),
        ],
        analysis_archetype="landscape",
        response_language="zh",
        report_depth="quick",
        knowledge_payload={
            "schema_version": "schema_v0.2",
            "features": [
                {"competitor_id": "Meta", "name": "语音助手", "evidence_ids": ["ev_001"]},
            ],
            "pricings": [
                {"competitor_id": "Meta", "model": "subscription", "free_plan": False, "enterprise_plan": True, "evidence_ids": ["ev_001"]}
            ],
            "personas": [],
            "feedback": [
                {"competitor_id": "Meta", "sentiment": "positive", "topic": "续航", "summary": "续航较好", "evidence_ids": ["ev_003"]}
            ],
            "missing_reasons": {},
            "coverage": {
                "Meta": {"feature": "complete", "pricing": "complete", "feedback": "partial"},
            },
            "supporting_target_evidence_ids": {
                "Meta": {"feature": ["ev_001"], "pricing": ["ev_001"], "feedback": ["ev_003"]},
            },
        },
        comparison_briefs=[],
        evidence_briefs=[
            {"evidence_id": "ev_001", "competitor_id": "Meta", "category_relevance": "target"},
            {"evidence_id": "ev_003", "competitor_id": "Meta", "category_relevance": "target"},
        ],
        allowed_evidence_ids={"ev_001", "ev_003"},
        state_competitors=["Meta", "XREAL"],
        discovered_competitor_sources={
            "Meta": {"candidate_role": "direct_competitor", "admission_status": "main_player"},
            "XREAL": {"candidate_role": "adjacent_competitor", "admission_status": "watchlist"},
        },
        self_product=None,
        target_category="AI眼镜",
        category_aliases=["AI眼镜"],
        excluded_categories=[],
        market_segments=[],
        scope_policy="explicit_category",
        preserve_llm_executive_summary=True,
    )

    assert updated["executive_summary"] == llm_summary
    section_ids = [item.get("section_id") for item in updated["sections"] if isinstance(item, dict)]
    assert "positioning_map" not in section_ids
    assert "key_players" in section_ids
