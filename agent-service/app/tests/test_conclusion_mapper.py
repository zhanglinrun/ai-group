from __future__ import annotations

from service.conclusion.mapper import comparisons_to_conclusions, insights_to_conclusions


class _EvidenceStub:
    def __init__(self, competitor_id: str) -> None:
        self.span: dict[str, object] = {"competitor_id": competitor_id}


def test_insights_to_conclusions_preserves_evidence_order_and_competitors() -> None:
    insights = [
        {
            "dimension": "feature",
            "finding": "Cursor has stronger repo context continuity.",
            "confidence": "high",
            "evidence_ids": ["ev_feature_1", "ev_feature_2", "ev_feature_1"],
        },
        {
            "dimension": "pricing",
            "finding": "Windsurf pricing has lower entry tier.",
            "confidence": "medium",
            "evidence_ids": ["ev_pricing_1"],
        },
        {
            "dimension": "user_feedback",
            "finding": "Users report better responsiveness for Cursor.",
            "confidence": "low",
            "evidence_ids": ["ev_feedback_1"],
        },
    ]
    evidence_lookup: dict[str, object] = {
        "ev_feature_1": {"span": {"competitor_id": "comp_cursor"}},
        "ev_feature_2": {"span": {"competitor_id": "comp_windsurf"}},
        "ev_pricing_1": _EvidenceStub("comp_windsurf"),
        "ev_feedback_1": {"competitor_id": "comp_cursor"},
    }
    result = insights_to_conclusions(
        run_id="run_mapper_001",
        step_id="step_mapper_001",
        insights=insights,
        evidence_lookup=evidence_lookup,
        risk_flags=[
            "feature_gap",
            "feature_volatility",
            "pricing_uncertainty",
            "uncovered_dimension:user_feedback",
        ],
    )

    assert len(result) == 3
    assert result[0]["section"] == "feature"
    assert result[0]["evidence_ids"] == ["ev_feature_1", "ev_feature_2"]
    assert result[0]["competitor_ids"] == ["comp_cursor", "comp_windsurf"]
    assert result[0]["risk_flags"] == ["feature_gap", "feature_volatility"]

    assert result[1]["section"] == "pricing"
    assert result[1]["competitor_ids"] == ["comp_windsurf"]
    assert result[1]["risk_flags"] == ["pricing_uncertainty"]

    assert result[2]["section"] == "user_feedback"
    assert result[2]["competitor_ids"] == ["comp_cursor"]
    assert result[2]["risk_flags"] == ["uncovered_dimension:user_feedback"]


def test_insights_to_conclusions_skips_invalid_items() -> None:
    result = insights_to_conclusions(
        run_id="run_mapper_002",
        step_id="step_mapper_002",
        insights=[
            {
                "dimension": "feature",
                "finding": "   ",
                "confidence": "high",
                "evidence_ids": ["ev_1"],
            },
            {
                "dimension": "swot",
                "finding": "Valid shape but evidence missing in lookup.",
                "confidence": "high",
                "evidence_ids": ["ev_unknown"],
            },
        ],
        evidence_lookup={"ev_1": {"span": {"competitor_id": "comp_cursor"}}},
        risk_flags=[],
    )

    assert result == []


def test_insights_to_conclusions_accepts_dynamic_section_ids() -> None:
    result = insights_to_conclusions(
        run_id="run_mapper_003",
        step_id="step_mapper_003",
        insights=[
            {
                "dimension": "go_to_market",
                "finding": "Competitor has stronger organic acquisition channels.",
                "confidence": "medium",
                "evidence_ids": ["ev_1"],
            },
        ],
        evidence_lookup={"ev_1": {"span": {"competitor_id": "comp_cursor"}}},
        risk_flags=["go_to_market_channel_gap"],
    )

    assert len(result) == 1
    assert result[0]["section"] == "go_to_market"


def test_comparisons_to_conclusions_backfills_grounded_dimensions() -> None:
    result = comparisons_to_conclusions(
        run_id="run_mapper_004",
        step_id="step_mapper_004",
        comparisons=[
            {
                "dimension": "pricing",
                "cells": [
                    {
                        "competitor_id": "Cursor",
                        "stance": "leader",
                        "summary": "Cursor has stronger enterprise packaging.",
                        "evidence_ids": ["ev_cursor_pricing"],
                    },
                    {
                        "competitor_id": "Windsurf",
                        "stance": "laggard",
                        "summary": "Windsurf has less mature enterprise pricing evidence.",
                        "evidence_ids": ["ev_windsurf_pricing"],
                    },
                ],
            },
            {
                "dimension": "security",
                "cells": [
                    {
                        "competitor_id": "Cursor",
                        "stance": "competitive",
                        "summary": "Cursor publishes security controls.",
                        "evidence_ids": ["ev_cursor_security"],
                    },
                    {
                        "competitor_id": "Windsurf",
                        "stance": "unknown",
                        "summary": "No grounded security evidence.",
                        "evidence_ids": [],
                    },
                ],
            },
        ],
        evidence_lookup={
            "ev_cursor_pricing": _EvidenceStub("Cursor"),
            "ev_windsurf_pricing": _EvidenceStub("Windsurf"),
            "ev_cursor_security": _EvidenceStub("Cursor"),
        },
        competitors=["Cursor", "Windsurf"],
        covered_sections=set(),
        risk_flags=["pricing_uncertainty"],
    )

    assert [item["section"] for item in result] == ["pricing", "security"]
    assert result[0]["evidence_ids"] == ["ev_cursor_pricing", "ev_windsurf_pricing"]
    assert result[0]["competitor_ids"] == ["Cursor", "Windsurf"]
    assert result[0]["risk_flags"] == ["pricing_uncertainty"]
    assert "Cursor is leader" in result[0]["claim"]
    assert result[1]["evidence_ids"] == ["ev_cursor_security"]


def test_comparisons_to_conclusions_skips_unknown_or_covered_dimensions() -> None:
    result = comparisons_to_conclusions(
        run_id="run_mapper_005",
        step_id="step_mapper_005",
        comparisons=[
            {
                "dimension": "pricing",
                "cells": [
                    {
                        "competitor_id": "Cursor",
                        "stance": "leader",
                        "summary": "Covered by an insight already.",
                        "evidence_ids": ["ev_cursor_pricing"],
                    },
                    {
                        "competitor_id": "Windsurf",
                        "stance": "competitive",
                        "summary": "Covered by an insight already.",
                        "evidence_ids": ["ev_windsurf_pricing"],
                    },
                ],
            },
            {
                "dimension": "security",
                "cells": [
                    {
                        "competitor_id": "Cursor",
                        "stance": "unknown",
                        "summary": "No evidence.",
                        "evidence_ids": [],
                    },
                    {
                        "competitor_id": "Windsurf",
                        "stance": "unknown",
                        "summary": "No evidence.",
                        "evidence_ids": [],
                    },
                ],
            },
        ],
        evidence_lookup={
            "ev_cursor_pricing": _EvidenceStub("Cursor"),
            "ev_windsurf_pricing": _EvidenceStub("Windsurf"),
        },
        competitors=["Cursor", "Windsurf"],
        covered_sections={"pricing"},
        risk_flags=[],
    )

    assert result == []
