from __future__ import annotations

import pytest

from schemas.contracts import (
    ensure_comparison_schema_dimensions,
    is_derived_dimension,
    normalize_dimension_or_none,
    research_focus_dimensions,
    validate_dimension,
    validate_section_id,
    validate_token_list,
)


def test_validate_dimension_slugifies_human_readable_value() -> None:
    assert validate_dimension("User Feedback") == "user_feedback"


def test_validate_section_id_slugifies_symbols() -> None:
    assert validate_section_id("Pricing & Cost") == "pricing_cost"


def test_validate_dimension_raises_when_slug_becomes_empty() -> None:
    with pytest.raises(ValueError, match="dimension must match"):
        validate_dimension("!!!")


def test_validate_token_list_skips_items_that_cannot_be_normalized() -> None:
    values = ["Feature", "!!!", "User Feedback", "feature"]
    normalized = validate_token_list(
        values=values,
        field_name="focus_dimensions",
        item_validator=validate_dimension,
    )
    assert normalized == ["feature", "user_feedback"]


def test_normalize_dimension_or_none_accepts_slugified_allowed_value() -> None:
    assert normalize_dimension_or_none("User Feedback", allowed=["user_feedback"]) == (
        "user_feedback",
        None,
    )


def test_normalize_dimension_or_none_reports_missing_invalid_and_out_of_focus() -> None:
    assert normalize_dimension_or_none(None, allowed=["pricing"]) == (None, "missing")
    assert normalize_dimension_or_none("!!!", allowed=["pricing"]) == (None, "invalid")
    assert normalize_dimension_or_none("User Feedback", allowed=["pricing"]) == (
        None,
        "out_of_focus",
    )


def test_is_derived_dimension_matches_aliases() -> None:
    assert is_derived_dimension("strategic_recommendations") is True
    # Aliases normalize to the canonical derived dimension.
    assert is_derived_dimension("Investment Recommendation") is True
    assert is_derived_dimension("pricing_strategy") is False
    assert is_derived_dimension("!!!") is False


def test_research_focus_dimensions_drops_derived_keeps_spelling() -> None:
    focus = ["pricing_strategy", "strategic_recommendations", "enterprise_capabilities"]
    assert research_focus_dimensions(focus) == [
        "pricing_strategy",
        "enterprise_capabilities",
        "feature",
        "pricing",
        "user_feedback",
    ]


def test_research_focus_dimensions_falls_back_when_all_derived() -> None:
    focus = ["strategic_recommendations", "Investment Recommendation"]
    # A research task must keep at least one target rather than become a no-op.
    assert research_focus_dimensions(focus) == ["feature", "pricing", "user_feedback"]


def test_ensure_comparison_schema_dimensions_skips_landscape_injection() -> None:
    assert ensure_comparison_schema_dimensions(
        ["pricing_strategy"],
        analysis_archetype="landscape",
    ) == ["pricing_strategy"]


def test_ensure_comparison_schema_dimensions_can_force_landscape_injection() -> None:
    assert ensure_comparison_schema_dimensions(
        ["pricing_strategy"],
        analysis_archetype="landscape",
        force_schema_dimensions=True,
    ) == ["pricing_strategy", "feature", "pricing", "user_feedback"]
