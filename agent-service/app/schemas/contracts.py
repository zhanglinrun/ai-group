from __future__ import annotations

import re
from collections.abc import Callable
from typing import Final

_CONTRACT_TOKEN_PATTERN: Final[re.Pattern[str]] = re.compile(r"^[a-z][a-z0-9_]{1,31}$")
_CONTRACT_TOKEN_SEPARATOR_PATTERN: Final[re.Pattern[str]] = re.compile(r"[^a-z0-9_]+")
_CONTRACT_TOKEN_LEADING_NON_ALPHA_PATTERN: Final[re.Pattern[str]] = re.compile(r"^[^a-z]+")
_DIMENSION_ALIASES: Final[dict[str, str]] = {
    "china_vs_global": "market_differences",
    "global_vs_china": "market_differences",
    "china_vs_global_market_dynamics": "market_differences",
    "china_vs_global_market_differenc": "market_differences",
    "market_difference": "market_differences",
    "market_differenc": "market_differences",
    "market_dynamics": "market_differences",
    "enterprise_features": "enterprise_capabilities",
    "enterprise_feature": "enterprise_capabilities",
    "enterprise_capabilities_assessme": "enterprise_capabilities",
    "enterprise_capabilities_assessment": "enterprise_capabilities",
    "investment_recommendation": "strategic_recommendations",
    "product_positioning_analysis": "product_positioning",
    "pricing_strategy_comparison": "pricing_strategy",
    "strategic_investment": "strategic_recommendations",
    "strategic_investment_recommendat": "strategic_recommendations",
    "strategic_investment_recommendation": "strategic_recommendations",
    "strategic_recommendation": "strategic_recommendations",
    # Hiring signals aliases
    "hiring": "hiring_signals",
    "jobs": "hiring_signals",
    "recruitment": "hiring_signals",
    "talent": "hiring_signals",
    "job_postings": "hiring_signals",
    # Recent news / funding aliases
    "news": "recent_news",
    "funding": "recent_news",
    "announcements": "recent_news",
    "press": "recent_news",
    "press_releases": "recent_news",
    "recent_announcements": "recent_news",
    # Product changelog aliases
    "changelog": "product_changelog",
    "releases": "product_changelog",
    "updates": "product_changelog",
    "version": "product_changelog",
    "release_notes": "product_changelog",
    "product_updates": "product_changelog",
}

COMPARISON_SCHEMA_BASE_DIMENSIONS: Final[tuple[str, str, str]] = (
    "feature",
    "pricing",
    "user_feedback",
)


def _validate_contract_token(*, value: str, field_name: str) -> str:
    normalized = value.strip().lower()
    if not normalized:
        raise ValueError(f"{field_name} cannot be empty.")
    normalized = _CONTRACT_TOKEN_SEPARATOR_PATTERN.sub("_", normalized)
    normalized = _CONTRACT_TOKEN_LEADING_NON_ALPHA_PATTERN.sub("", normalized).strip("_")
    normalized = normalized[:32]
    if _CONTRACT_TOKEN_PATTERN.fullmatch(normalized) is None:
        raise ValueError(f"{field_name} must match ^[a-z][a-z0-9_]{{1,31}}$.")
    return normalized


def validate_token_list(
    *,
    values: list[str],
    field_name: str,
    item_validator: Callable[[str], str],
    allow_empty: bool = False,
) -> list[str]:
    normalized: list[str] = []
    seen: set[str] = set()
    for value in values:
        try:
            item = item_validator(value)
        except ValueError:
            continue
        if item in seen:
            continue
        seen.add(item)
        normalized.append(item)
    if not normalized and not allow_empty:
        raise ValueError(f"{field_name} must contain at least one value.")
    return normalized


def validate_dimension(value: str) -> str:
    normalized = _validate_contract_token(value=value, field_name="dimension")
    return _DIMENSION_ALIASES.get(normalized, normalized)


def normalize_dimensions(values: list[str], *, allow_empty: bool = True) -> list[str]:
    return validate_token_list(
        values=values,
        field_name="focus_dimensions",
        item_validator=validate_dimension,
        allow_empty=allow_empty,
    )


# Dimensions the analyst synthesizes from other dimensions' evidence rather than
# the researcher gathering them as standalone facts. A research task that chases
# these wastes its turn budget and produces zero on-dimension evidence (R9).
DERIVED_DIMENSIONS: Final[frozenset[str]] = frozenset({"strategic_recommendations"})


def is_derived_dimension(value: str) -> bool:
    try:
        return validate_dimension(value) in DERIVED_DIMENSIONS
    except ValueError:
        return False


def ensure_comparison_schema_dimensions(
    focus_dimensions: list[str],
    *,
    analysis_archetype: str = "comparison",
    force_schema_dimensions: bool = False,
) -> list[str]:
    normalized = normalize_dimensions(list(focus_dimensions), allow_empty=True)
    if analysis_archetype != "comparison" and not force_schema_dimensions:
        return normalized
    existing = set(normalized)
    expanded = list(normalized)
    for dimension in COMPARISON_SCHEMA_BASE_DIMENSIONS:
        if dimension in existing:
            continue
        expanded.append(dimension)
        existing.add(dimension)
    return expanded


def research_focus_dimensions(
    focus_dimensions: list[str],
    *,
    analysis_archetype: str = "comparison",
) -> list[str]:
    """Subset of focus dimensions a research task should collect evidence for.

    Drops derived dimensions (analyst-synthesized), preserving original spelling.
    Falls back to the full list if every dimension is derived — a research task
    must keep at least one target rather than become a no-op.
    """
    expanded = ensure_comparison_schema_dimensions(
        focus_dimensions=list(focus_dimensions),
        analysis_archetype=analysis_archetype,
    )
    research = [dim for dim in expanded if not is_derived_dimension(dim)]
    return research or expanded


def normalize_dimension_or_none(
    raw: object,
    *,
    allowed: list[str] | set[str],
) -> tuple[str | None, str | None]:
    if not isinstance(raw, str):
        return None, "missing"
    try:
        normalized = validate_dimension(raw)
    except ValueError:
        return None, "invalid"
    if allowed and normalized not in set(allowed):
        return None, "out_of_focus"
    return normalized, None


def validate_section_id(value: str) -> str:
    return _validate_contract_token(value=value, field_name="section_id")


def validate_template_id(value: str) -> str:
    return _validate_contract_token(value=value, field_name="template_id")


def validate_source_type(value: str) -> str:
    return _validate_contract_token(value=value, field_name="source_type")
