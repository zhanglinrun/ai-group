from __future__ import annotations

import pytest
from pydantic import ValidationError

from schemas.agent_outputs import (
    AnalystOutput,
    DiscoveryExtractOutput,
    IntakeTurnOutput,
    KnowledgeExtractionOutput,
    PlannerOutput,
    QASemanticOutput,
    ResearcherDecisionOutput,
    SupervisorToolCallOutput,
    WriterExecutionContext,
    WriterReportOutput,
    resolve_writer_target_sections,
)
from schemas.agent_outputs_pipeline import INTAKE_PATCHABLE_FIELDS, IntakeTurnOutput as PipelineIntakeTurnOutput
from schemas.contracts import validate_dimension
from schemas.intake import RunIntakeDraft
from schemas.report_sections import default_outline_for_archetype


def test_analyst_output_canonicalizes_recommended_sections_from_insights() -> None:
    output = AnalystOutput.model_validate(
        {
            "summary": "Summary with enough analyst context.",
            "insights": [
                {
                    "dimension": "competitive_edge",
                    "finding": "Product A leads on context depth.",
                    "evidence_ids": ["ev_001"],
                    "confidence": "high",
                }
            ],
            "recommended_sections": ["Competitive positioning gap analysis report"],
        }
    )

    assert output.recommended_sections == ["competitive_edge"]


def test_analyst_output_normalizes_report_outline_items() -> None:
    output = AnalystOutput.model_validate(
        {
            "summary": "Summary with enough analyst context.",
            "insights": [
                {
                    "dimension": "feature",
                    "finding": "Product A offers broader capability coverage.",
                    "evidence_ids": ["ev_001"],
                }
            ],
            "report_outline": [
                {"section_id": "executive_summary", "directive": "focus on outcomes"},
                "comparison_matrix",
                {"section_id": "unknown_outline_item"},
                {"section_id": "comparison_matrix", "directive": "duplicate should be removed"},
            ],
        }
    )

    assert [item.section_id for item in output.report_outline] == [
        "executive_summary",
        "comparison_matrix",
    ]
    assert output.report_outline[0].directive == "focus on outcomes"
    assert output.report_outline[1].directive is None


def test_analyst_fallback_sets_default_report_outline_for_archetype() -> None:
    analyst = AnalystOutput.build_fallback(
        focus_dimensions=["feature", "pricing"],
        evidence_briefs=[],
        analysis_archetype="landscape",
    )

    assert [item.section_id for item in analyst.report_outline] == list(
        default_outline_for_archetype("landscape")
    )


def test_resolve_writer_target_sections_prefers_analyst_dimensions() -> None:
    sections = resolve_writer_target_sections(
        requested_sections=None,
        recommended_sections=["competitive_edge", "monetization_model"],
    )

    assert sections == [
        "executive_summary",
        "competitor_profiles",
        "comparison_matrix",
        "positioning_map",
        "self_positioning",
        "strategic_recommendations",
    ]


def test_resolve_writer_target_sections_adds_landscape_required_sections() -> None:
    sections = resolve_writer_target_sections(
        requested_sections=None,
        recommended_sections=["opportunities_risks"],
        analysis_archetype="landscape",
    )

    assert sections == [
        "executive_takeaways",
        "market_definition",
        "market_size_growth",
        "market_segmentation",
        "competitive_landscape",
        "key_players",
        "value_chain",
        "opportunities_risks",
        "strategic_recommendations",
        "methodology_limits",
    ]


def test_resolve_writer_target_sections_accepts_outline_sections_not_dimensions() -> None:
    analyst = AnalystOutput.model_validate(
        {
            "summary": "Summary with enough analyst context.",
            "insights": [
                {
                    "dimension": "competitive_edge",
                    "finding": "Product A leads on context depth.",
                    "evidence_ids": ["ev_001"],
                    "confidence": "high",
                }
            ],
            "risk_flags": [],
            "recommended_sections": ["competitive_edge", "opportunities_risks"],
            "report_outline": [
                {"section_id": "market_definition"},
                {"section_id": "pricing"},
            ],
        }
    )

    sections = resolve_writer_target_sections(
        requested_sections=["feature", "pricing"],
        recommended_sections=analyst.recommended_sections,
        report_outline=analyst.report_outline,
        analysis_archetype="comparison",
    )

    assert sections == [
        "executive_summary",
        "competitor_profiles",
        "comparison_matrix",
        "positioning_map",
        "self_positioning",
        "strategic_recommendations",
    ]
    assert "feature" not in sections
    assert "pricing" not in sections
    assert "competitive_edge" not in sections


def test_writer_execution_context_aligns_with_analyst_output() -> None:
    analyst = AnalystOutput.model_validate(
        {
            "summary": "Summary with enough analyst context.",
            "insights": [
                {
                    "dimension": "feature",
                    "finding": "Feature depth varies across competitors.",
                    "evidence_ids": ["ev_001"],
                    "confidence": "medium",
                }
            ],
            "recommended_sections": [],
        }
    )
    context = WriterExecutionContext.resolve(
        template_id="battlecard_default",
        requested_sections=None,
        analyst_output=analyst,
        allowed_evidence_ids={"ev_001"},
        allowed_insight_ids={"insight_1"},
    )

    assert context.target_sections == [
        "executive_summary",
        "competitor_profiles",
        "comparison_matrix",
        "positioning_map",
        "self_positioning",
        "strategic_recommendations",
    ]
    assert context.renderable_sections == context.target_sections
    assert "feature" not in context.target_sections


def test_analyst_fallback_marks_uncovered_dimensions() -> None:
    analyst = AnalystOutput.build_fallback(
        focus_dimensions=["feature", "pricing"],
        evidence_briefs=[
            {
                "evidence_id": "ev_001",
                "dimension": "pricing",
                "competitor_id": "Cursor",
                "quote_preview": "Cursor pricing starts at a public monthly plan.",
                "source_title": "Cursor Pricing",
                "source_url": "https://cursor.com/pricing",
            }
        ],
    )

    assert analyst.recommended_sections == ["pricing"]
    assert analyst.risk_flags == ["analyst_fallback_mode", "uncovered_dimension:feature"]


def test_analyst_output_slugifies_dimension_before_allowed_membership() -> None:
    output = AnalystOutput.parse_llm_content(
        {
            "summary": "Summary with enough analyst context.",
            "insights": [
                {
                    "dimension": "User Feedback",
                    "finding": "Users report onboarding friction.",
                    "evidence_ids": ["ev_001"],
                    "confidence": "medium",
                }
            ],
        },
        allowed_evidence_ids={"ev_001"},
        allowed_dimensions={"user_feedback"},
    )

    assert output.insights[0].dimension == "user_feedback"


def test_analyst_output_filters_structured_comparisons() -> None:
    output = AnalystOutput.parse_llm_content(
        {
            "summary": "Summary with enough analyst context.",
            "insights": [
                {
                    "dimension": "Feature",
                    "finding": "Cursor and Windsurf differ on repository context.",
                    "evidence_ids": ["ev_cursor"],
                    "confidence": "medium",
                }
            ],
            "comparisons": [
                {
                    "dimension": "Feature",
                    "cells": [
                        {
                            "competitor_id": "Cursor ",
                            "stance": "leader",
                            "summary": "Cursor has stronger repo context.",
                            "evidence_ids": ["ev_cursor", "ev_unknown"],
                        },
                        {
                            "competitor_id": "Windsurf",
                            "stance": "not_valid",
                            "summary": "Windsurf is competitive but less grounded here.",
                            "evidence_ids": ["ev_windsurf"],
                        },
                        {
                            "competitor_id": "UnknownCompetitor",
                            "stance": "leader",
                            "summary": "Should be filtered.",
                            "evidence_ids": ["ev_unknown"],
                        },
                    ],
                },
                {
                    "dimension": "Pricing",
                    "cells": [
                        {
                            "competitor_id": "Cursor",
                            "stance": "leader",
                            "summary": "Single-cell comparisons are not useful.",
                            "evidence_ids": ["ev_cursor"],
                        }
                    ],
                },
            ],
        },
        allowed_evidence_ids={"ev_cursor", "ev_windsurf"},
        allowed_dimensions={"feature", "pricing"},
        competitors={"Cursor", "Windsurf"},
    )

    assert len(output.comparisons) == 1
    comparison = output.comparisons[0]
    assert comparison.dimension == "feature"
    assert [cell.competitor_id for cell in comparison.cells] == ["Cursor", "Windsurf"]
    assert comparison.cells[0].evidence_ids == ["ev_cursor"]
    assert comparison.cells[1].stance == "unknown"


def test_analyst_output_downgrades_qualified_comparison_without_evidence() -> None:
    output = AnalystOutput.parse_llm_content(
        {
            "summary": "Summary with enough analyst context.",
            "insights": [
                {
                    "dimension": "Feature",
                    "finding": "Cursor and Windsurf differ on repository context.",
                    "evidence_ids": ["ev_cursor"],
                    "confidence": "medium",
                }
            ],
            "comparisons": [
                {
                    "dimension": "Feature",
                    "cells": [
                        {
                            "competitor_id": "Cursor",
                            "stance": "leader",
                            "summary": "Cursor supposedly leads, but the evidence was filtered.",
                            "evidence_ids": ["ev_missing"],
                        },
                        {
                            "competitor_id": "Windsurf",
                            "stance": "competitive",
                            "summary": "Windsurf has grounded competing evidence.",
                            "evidence_ids": ["ev_windsurf"],
                        },
                    ],
                }
            ],
        },
        allowed_evidence_ids={"ev_cursor", "ev_windsurf"},
        allowed_dimensions={"feature"},
        competitors={"Cursor", "Windsurf"},
    )

    cells = output.comparisons[0].cells
    assert cells[0].competitor_id == "Cursor"
    assert cells[0].stance == "unknown"
    assert cells[0].evidence_ids == []
    assert cells[1].stance == "competitive"


def test_analyst_output_skips_out_of_focus_insight_and_audits_reason() -> None:
    dropped: dict[str, int] = {}

    with pytest.raises(ValidationError):
        AnalystOutput.parse_llm_content(
            {
                "summary": "Summary with enough analyst context.",
                "insights": [
                    {
                        "dimension": "User Feedback",
                        "finding": "Users report onboarding friction.",
                        "evidence_ids": ["ev_001"],
                        "confidence": "medium",
                    }
                ],
            },
            allowed_evidence_ids={"ev_001"},
            allowed_dimensions={"pricing"},
            dropped_dimensions=dropped,
        )

    assert dropped == {"out_of_focus": 1}


def test_writer_report_output_marks_uncovered_target_sections() -> None:
    context = WriterExecutionContext(
        template_id="battlecard_default",
        target_sections=["feature", "pricing"],
        renderable_sections=["feature", "pricing"],
        allowed_evidence_ids=frozenset({"ev_001"}),
        allowed_insight_ids=frozenset(),
    )
    output = WriterReportOutput.parse_llm_content(
        {
            "template_id": "battlecard_default",
            "title": "Battlecard",
            "executive_summary": "Executive summary grounded in collected evidence.",
            "sections": [
                {
                    "section_id": "feature",
                    "title": "Feature",
                    "content_markdown": (
                        "Feature comparison with enough detail to satisfy writer schema validation."
                    ),
                    "evidence_refs": ["ev_001"],
                    "insight_refs": [],
                }
            ],
            "risk_callouts": [],
        },
        execution_context=context,
    )

    assert [section.section_id for section in output.sections] == ["feature"]
    assert output.risk_callouts == ["uncovered_section:pricing"]


def test_intake_turn_output_requires_clarify_for_ask() -> None:
    with pytest.raises(ValidationError):
        IntakeTurnOutput.model_validate(
            {
                "action": "ask",
                "draft_patch": {},
                "clarify_request": None,
                "reasoning_summary": "",
            }
        )


def test_intake_patchable_fields_include_optional_scope_contract() -> None:
    assert {
        "self_product",
        "market_scope",
        "time_context",
        "response_language",
        "analysis_archetype",
    }.issubset(INTAKE_PATCHABLE_FIELDS)


def test_intake_turn_parser_preserves_analysis_archetype_patch() -> None:
    parsed = PipelineIntakeTurnOutput.parse_llm_content(
        {
            "action": "complete",
            "draft_patch": {"analysis_archetype": "landscape"},
            "clarify_request": None,
            "reasoning_summary": "done",
        }
    )

    assert parsed.draft_patch == {"analysis_archetype": "landscape"}


def test_intake_turn_parser_preserves_optional_scope_patch_fields() -> None:
    parsed = PipelineIntakeTurnOutput.parse_llm_content(
        {
            "action": "complete",
            "draft_patch": {
                "self_product": "某大厂 AI 工具团队",
                "market_scope": "中国市场",
                "time_context": "只看近一年",
                "response_language": "zh",
                "unknown_field": "drop me",
            },
            "clarify_request": None,
            "reasoning_summary": "done",
        }
    )

    assert parsed.draft_patch == {
        "self_product": "某大厂 AI 工具团队",
        "market_scope": "中国市场",
        "time_context": "只看近一年",
        "response_language": "zh",
    }


def test_planner_output_parses_research_tasks() -> None:
    draft = RunIntakeDraft(user_query="compare AI coding tools", competitors_explicit=["Cursor"])
    output = PlannerOutput.parse_llm_content(
        {
            "rationale": "Research explicit competitors first.",
            "tasks": [
                {
                    "stage": "research",
                    "title": "Research Cursor",
                    "description": "Collect evidence",
                    "competitor_id": "Cursor",
                    "focus_dimensions": ["feature"],
                }
            ],
        },
        draft=draft,
    )
    tasks = output.to_plan_tasks()
    assert len(tasks) == 1
    assert tasks[0].competitor_id == "Cursor"


def test_planner_output_normalizes_or_falls_back_non_contract_dimensions() -> None:
    draft = RunIntakeDraft(
        user_query="对比 AI 编程工具",
        competitors_explicit=["Cursor"],
        focus_dimensions=["产品定位", "pricing_strategy"],
    )
    output = PlannerOutput.parse_llm_content(
        {
            "rationale": "Research explicit competitors first.",
            "tasks": [
                {
                    "stage": "research",
                    "title": "Research Cursor",
                    "description": "Collect evidence",
                    "competitor_id": "Cursor",
                    "focus_dimensions": ["产品定位", "enterprise capabilities"],
                }
            ],
        },
        draft=draft,
    )

    task = output.to_plan_tasks()[0]
    assert task.focus_dimensions == [
        "enterprise_capabilities",
        "feature",
        "pricing",
        "user_feedback",
    ]


def test_dimension_aliases_share_canonical_namespace() -> None:
    assert validate_dimension("china_vs_global") == "market_differences"
    assert validate_dimension("china_vs_global_market_dynamics") == "market_differences"
    assert validate_dimension("enterprise_features") == "enterprise_capabilities"
    assert validate_dimension("enterprise_capabilities_assessme") == "enterprise_capabilities"
    assert validate_dimension("product_positioning_analysis") == "product_positioning"
    assert validate_dimension("pricing_strategy_comparison") == "pricing_strategy"
    assert validate_dimension("investment_recommendation") == "strategic_recommendations"
    assert validate_dimension("strategic_investment_recommendat") == "strategic_recommendations"


def test_supervisor_tool_call_output_validates_batch_topics() -> None:
    with pytest.raises(ValueError):
        SupervisorToolCallOutput.parse_llm_content(
            {
                "chosen_tool": "ConductResearchBatch",
                "tool_args": {
                    "topics": [
                        {
                            "research_topic": "t1",
                            "competitor_id": "A",
                            "focus_dimensions": ["feature"],
                            "max_iterations": 3,
                            "fallback_to_offline": True,
                        },
                        {
                            "research_topic": "t2",
                            "competitor_id": "A",
                            "focus_dimensions": ["feature"],
                            "max_iterations": 3,
                            "fallback_to_offline": True,
                        },
                    ],
                    "parallelism_rationale": "dup",
                },
                "reasoning_summary": "batch",
            }
        )


def test_discovery_extract_output_dedupes_competitors() -> None:
    output = DiscoveryExtractOutput.parse_llm_content(
        {"competitors": ["Cursor", "Cursor", "Windsurf"]}
    )
    assert output.competitors == ["Cursor", "Windsurf"]


def test_discovery_extract_output_parses_grounded_candidates() -> None:
    output = DiscoveryExtractOutput.parse_llm_content(
        {
            "candidates": [
                {
                    "name": "Cursor",
                    "is_competitor": True,
                    "relevance_reason": "AI coding product in the target market.",
                    "evidence_quote": "Cursor is an AI code editor.",
                },
                {
                    "name": "TechCrunch",
                    "is_competitor": False,
                    "relevance_reason": "Publisher, not a product competitor.",
                    "evidence_quote": "TechCrunch reported on AI coding tools.",
                },
            ]
        }
    )

    assert output.competitors == ["Cursor"]
    assert output.candidates[0].evidence_quote == "Cursor is an AI code editor."
    assert output.candidates[1].is_competitor is False


def test_researcher_decision_to_action_tuple_search_web() -> None:
    decision = ResearcherDecisionOutput.parse_llm_content(
        {
            "action": "search_web",
            "action_args": {"query": "Cursor pricing", "dimension": "pricing"},
            "reasoning_summary": "Need pricing evidence",
        }
    )
    action_tuple = decision.to_action_tuple(competitor_id="Cursor")
    assert action_tuple is not None
    action, args = action_tuple
    assert action == "search_web"
    assert args["query"] == "Cursor pricing"


def test_researcher_decision_dimension_falls_back_to_pending_when_out_of_focus() -> None:
    decision = ResearcherDecisionOutput.parse_llm_content(
        {
            "action": "search_web",
            "action_args": {
                "query": "Cursor product positioning pricing strategy",
                "dimension": "product_positioning_pricing_strategy",
            },
            "reasoning_summary": "Need pricing evidence",
        }
    )

    action_tuple = decision.to_action_tuple(
        competitor_id="Cursor",
        focus_dimensions=["pricing"],
        pending_dimensions=["pricing"],
    )

    assert action_tuple is not None
    action, args = action_tuple
    assert action == "search_web"
    assert args["dimension"] == "pricing"


def test_researcher_decision_fetch_url_without_dimension_does_not_use_next_pending() -> None:
    decision = ResearcherDecisionOutput.parse_llm_content(
        {
            "action": "fetch_url",
            "action_args": {"url": "https://cursor.com/pricing"},
            "reasoning_summary": "Follow a pricing result URL",
        }
    )

    action_tuple = decision.to_action_tuple(
        competitor_id="Cursor",
        focus_dimensions=["pricing", "security"],
        pending_dimensions=["security"],
    )

    assert action_tuple is not None
    action, args = action_tuple
    assert action == "fetch_url"
    assert "dimension" not in args


def test_researcher_decision_extract_structured_without_dimension_does_not_use_next_pending() -> None:
    decision = ResearcherDecisionOutput.parse_llm_content(
        {
            "action": "extract_structured",
            "action_args": {"text": "Cursor pricing includes public team plan evidence."},
            "reasoning_summary": "Extract details from the last fetched page",
        }
    )

    action_tuple = decision.to_action_tuple(
        competitor_id="Cursor",
        focus_dimensions=["pricing", "security"],
        pending_dimensions=["security"],
    )

    assert action_tuple is not None
    action, args = action_tuple
    assert action == "extract_structured"
    assert "dimension" not in args


def test_qa_semantic_output_normalizes_dict() -> None:
    output = QASemanticOutput.parse_llm_content(
        {
            "semantic_audit_passed": False,
            "reject_to": "writer",
            "severity": "blocking",
            "finding": "Missing pricing evidence",
            "required_fields": ["reports.content_json.sections"],
            "unsupported_numeric_claims": [
                {
                    "claim": "效率提升 28%",
                    "section_id": "efficiency",
                    "reason": "Cited evidence does not mention 28%.",
                },
                {
                    "claim": "Q1 high capability / high maturity: none",
                    "section_id": "positioning_map",
                    "reason": "Deterministic quadrant label flagged by auditor.",
                },
            ],
            "dimension_results": {
                "depth": False,
                "citation_coverage": False,
                "faithfulness": True,
                "instruction_following": True,
            },
        }
    )
    normalized = output.to_normalized_dict()
    assert normalized["reject_to"] == "writer"
    assert normalized["semantic_audit_passed"] is False
    assert normalized["unsupported_numeric_claims"] == [
        {
            "claim": "效率提升 28%",
            "section_id": "efficiency",
            "reason": "Cited evidence does not mention 28%.",
        },
        {
            "claim": "Q1 high capability / high maturity: none",
            "section_id": "positioning_map",
            "reason": "Deterministic quadrant label flagged by auditor.",
        },
    ]
    assert normalized["dimension_results"] == {
        "depth": False,
        "citation_coverage": False,
        "faithfulness": True,
        "instruction_following": True,
    }


def test_qa_semantic_output_rejects_malformed_numeric_claim_item() -> None:
    with pytest.raises(ValueError):
        QASemanticOutput.parse_llm_content(
            {
                "semantic_audit_passed": False,
                "reject_to": "writer",
                "severity": "blocking",
                "finding": "Missing pricing evidence",
                "required_fields": ["reports.content_json.sections"],
                "unsupported_numeric_claims": [
                    {
                        "claim": "",
                        "section_id": "pricing",
                        "reason": "invalid item should fail-closed",
                    }
                ],
                "dimension_results": {
                    "depth": False,
                    "citation_coverage": False,
                    "faithfulness": True,
                    "instruction_following": True,
                },
            }
        )


def test_knowledge_extraction_output_filters_non_grounded_rows() -> None:
    output = KnowledgeExtractionOutput.parse_llm_content(
        {
            "schema_version": "schema_v0.2",
            "features": [
                {
                    "id": "feat_input_1",
                    "competitor_id": "Cursor",
                    "name": "Repo context",
                    "evidence_ids": ["ev_1"],
                },
                {
                    "id": "feat_input_2",
                    "competitor_id": "Unknown",
                    "name": "Should be dropped",
                    "evidence_ids": ["ev_2"],
                },
            ],
            "pricings": [
                {
                    "id": "price_input_1",
                    "competitor_id": "Cursor",
                    "model": "subscription",
                    "tiers": [{"name": "Pro", "price": "$20"}],
                    "evidence_ids": ["ev_1"],
                }
            ],
            "personas": [
                {
                    "id": "persona_input_1",
                        "competitor_id": "Cursor",
                    "name": "Engineering manager",
                    "role": "engineering_manager",
                    "pain_points": ["Review load"],
                    "jobs_to_be_done": ["Faster release"],
                    "evidence_ids": ["ev_1"],
                }
            ],
            "feedback": [
                {
                    "id": "fb_input_1",
                    "competitor_id": "Cursor",
                    "sentiment": "positive",
                    "topic": "onboarding",
                    "summary": "Onboarding is improving.",
                    "evidence_ids": ["ev_1"],
                },
                {
                    "id": "fb_input_2",
                    "competitor_id": "Cursor",
                    "sentiment": "positive",
                    "topic": "invalid",
                    "summary": "Should be dropped due to invalid evidence id.",
                    "evidence_ids": ["ev_not_allowed"],
                },
            ],
        },
        allowed_evidence_ids={"ev_1"},
        competitors={"Cursor"},
    )

    assert output.schema_version == "schema_v0.2"
    assert len(output.features) == 1
    assert output.features[0]["competitor_id"] == "Cursor"
    assert len(output.pricings) == 1
    assert len(output.personas) == 1
    assert len(output.feedback) == 1
    assert output.feedback[0]["topic"] == "onboarding"
