from __future__ import annotations

from schemas.agent_outputs import resolve_writer_target_sections


def test_academic_outline_rejects_market_sections() -> None:
    sections = resolve_writer_target_sections(
        requested_sections=["market_definition", "methods"],
        recommended_sections=["pricing", "benchmarks"],
        analysis_archetype="landscape",
        research_mode="academic",
    )

    assert sections == [
        "problem",
        "methods",
        "datasets",
        "benchmarks",
        "experimental_results",
        "research_gaps",
        "limitations",
        "methodology_limits",
    ]
    assert "market_definition" not in sections
    assert "pricing" not in sections


def test_general_outline_is_neutral() -> None:
    sections = resolve_writer_target_sections(
        requested_sections=None,
        recommended_sections=[],
        analysis_archetype="landscape",
        research_mode="general",
    )

    assert sections[:3] == ["overview", "key_concepts", "current_state"]
    assert "market_size_growth" not in sections
