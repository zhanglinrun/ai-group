from __future__ import annotations

from service.comparison.mapper import comparisons_to_cells


def test_comparisons_to_cells_filters_invalid_cells_and_preserves_evidence_order() -> None:
    result = comparisons_to_cells(
        run_id="run_comparison_mapper",
        step_id="step_comparison_mapper",
        comparisons=[
            {
                "dimension": "Feature",
                "cells": [
                    {
                        "competitor_id": "Cursor",
                        "stance": "leader",
                        "summary": "Cursor leads on repository context.",
                        "evidence_ids": ["ev_1", "ev_2", "ev_1"],
                    },
                    {
                        "competitor_id": "Windsurf",
                        "stance": "invalid",
                        "summary": "Windsurf is competitive.",
                        "evidence_ids": ["ev_3", "ev_missing"],
                    },
                    {
                        "competitor_id": "Other",
                        "stance": "leader",
                        "summary": "Unknown competitor should be dropped.",
                        "evidence_ids": ["ev_4"],
                    },
                ],
            },
            {
                "dimension": "Pricing",
                "cells": [
                    {
                        "competitor_id": "Cursor",
                        "stance": "leader",
                        "summary": "Single cell should drop the whole dimension.",
                        "evidence_ids": ["ev_1"],
                    }
                ],
            },
        ],
        evidence_lookup={"ev_1": object(), "ev_2": object(), "ev_3": object()},
        competitors=["Cursor", "Windsurf"],
    )

    assert len(result) == 2
    assert result[0]["dimension"] == "feature"
    assert result[0]["evidence_ids"] == ["ev_1", "ev_2"]
    assert result[1]["competitor_id"] == "Windsurf"
    assert result[1]["stance"] == "unknown"
    assert result[1]["evidence_ids"] == ["ev_3"]


def test_comparisons_to_cells_downgrades_qualified_stance_without_evidence() -> None:
    result = comparisons_to_cells(
        run_id="run_comparison_mapper",
        step_id="step_comparison_mapper",
        comparisons=[
            {
                "dimension": "Feature",
                "cells": [
                    {
                        "competitor_id": " Cursor ",
                        "stance": "leader",
                        "summary": "Cursor should not keep leader without grounded evidence.",
                        "evidence_ids": ["ev_missing"],
                    },
                    {
                        "competitor_id": "Windsurf",
                        "stance": "competitive",
                        "summary": "Windsurf has grounded evidence.",
                        "evidence_ids": ["ev_2"],
                    },
                ],
            }
        ],
        evidence_lookup={"ev_2": object()},
        competitors=["Cursor", "Windsurf"],
    )

    assert len(result) == 2
    assert result[0]["competitor_id"] == "Cursor"
    assert result[0]["stance"] == "unknown"
    assert result[0]["evidence_ids"] == []
    assert result[1]["stance"] == "competitive"


def test_comparisons_to_cells_yield_gate_drops_zero_evidence_competitors() -> None:
    comparisons = [
        {
            "dimension": "Feature",
            "cells": [
                {
                    "competitor_id": "Cursor",
                    "stance": "leader",
                    "summary": "Cursor has grounded evidence.",
                    "evidence_ids": ["ev_1"],
                },
                {
                    "competitor_id": "Windsurf",
                    "stance": "competitive",
                    "summary": "Windsurf has grounded evidence.",
                    "evidence_ids": ["ev_2"],
                },
                {
                    "competitor_id": "GhostTool",
                    "stance": "unknown",
                    "summary": "Discovery anecdote with no researched evidence.",
                    "evidence_ids": [],
                },
            ],
        }
    ]
    evidence_lookup = {"ev_1": object(), "ev_2": object()}
    competitors = ["Cursor", "Windsurf", "GhostTool"]

    gated = comparisons_to_cells(
        run_id="run_yield_gate",
        step_id="step_yield_gate",
        comparisons=comparisons,
        evidence_lookup=evidence_lookup,
        competitors=competitors,
        competitors_with_evidence={"Cursor", "Windsurf"},
    )
    assert {cell["competitor_id"] for cell in gated} == {"Cursor", "Windsurf"}

    ungated = comparisons_to_cells(
        run_id="run_yield_gate",
        step_id="step_yield_gate",
        comparisons=comparisons,
        evidence_lookup=evidence_lookup,
        competitors=competitors,
    )
    assert "GhostTool" in {cell["competitor_id"] for cell in ungated}


def test_comparisons_to_cells_yield_gate_drops_dimension_below_two_cells() -> None:
    comparisons = [
        {
            "dimension": "Feature",
            "cells": [
                {
                    "competitor_id": "Cursor",
                    "stance": "leader",
                    "summary": "Only grounded competitor left after the yield gate.",
                    "evidence_ids": ["ev_1"],
                },
                {
                    "competitor_id": "GhostTool",
                    "stance": "unknown",
                    "summary": "Zero-evidence discovery anecdote.",
                    "evidence_ids": [],
                },
            ],
        }
    ]
    result = comparisons_to_cells(
        run_id="run_yield_gate",
        step_id="step_yield_gate",
        comparisons=comparisons,
        evidence_lookup={"ev_1": object()},
        competitors=["Cursor", "GhostTool"],
        competitors_with_evidence={"Cursor"},
    )
    assert result == []
