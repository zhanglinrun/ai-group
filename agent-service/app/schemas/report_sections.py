from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Literal

# Single source of truth for report section building blocks: which sections exist,
# how they render (kind), which intents must contain them (required_for), the data
# predicate that gates a deterministic block (requires), and user-facing titles.
#
# This module is intentionally writer-free: `resolve_writer_target_sections` (schemas) and the
# writer node both import it, so it must not import either to stay acyclic. The builder
# dispatch (section_id -> render function) lives in the writer node, keyed by these ids.

SectionKind = Literal["top_level", "deterministic", "narrative"]
ReportArchetype = Literal["comparison", "landscape", "mixed"]

# Coverage statuses that count as real, citable evidence for a (competitor, dimension).
SUBSTANTIVE_STATUSES: frozenset[str] = frozenset({"complete", "partial"})
# Discovery roles whose competitors belong to the deep, comparable "core" subset.
CORE_DISCOVERY_ROLES: frozenset[str] = frozenset(
    {"direct_competitor", "adjacent_competitor", "substitute"}
)


@dataclass(frozen=True)
class SectionEvidenceContext:
    """Inputs for a section `requires` predicate, computed from real coverage.

    `coverage` maps competitor -> dimension -> status. `competitors` is the ordered
    report set; `core_competitors` is the role-filtered deep subset (already resolved
    by the caller using `CORE_DISCOVERY_ROLES`).
    """

    coverage: Mapping[str, Mapping[str, str]]
    competitors: tuple[str, ...]
    core_competitors: tuple[str, ...]


def competitor_has_substantive_dimension(
    *,
    coverage: Mapping[str, Mapping[str, str]],
    competitor: str,
) -> bool:
    dimensions = coverage.get(competitor)
    if not isinstance(dimensions, Mapping):
        return False
    return any(status in SUBSTANTIVE_STATUSES for status in dimensions.values())


def _count_substantive(
    *,
    coverage: Mapping[str, Mapping[str, str]],
    competitors: tuple[str, ...],
) -> int:
    return sum(
        1
        for competitor in competitors
        if competitor_has_substantive_dimension(coverage=coverage, competitor=competitor)
    )


def _requires_always(ctx: SectionEvidenceContext) -> bool:
    return True


def _requires_at_least_one_substantive(ctx: SectionEvidenceContext) -> bool:
    return _count_substantive(coverage=ctx.coverage, competitors=ctx.competitors) >= 1


def _requires_at_least_one_core_substantive(ctx: SectionEvidenceContext) -> bool:
    return _count_substantive(coverage=ctx.coverage, competitors=ctx.core_competitors) >= 1


def _requires_at_least_two_core_substantive(ctx: SectionEvidenceContext) -> bool:
    return _count_substantive(coverage=ctx.coverage, competitors=ctx.core_competitors) >= 2


@dataclass(frozen=True)
class SectionSpec:
    section_id: str
    kind: SectionKind
    required_for: frozenset[str]
    requires: Callable[[SectionEvidenceContext], bool]
    title_zh: str
    title_en: str

    def is_required_for(self, archetype: str) -> bool:
        return archetype in self.required_for

    def title(self, *, response_language: str | None) -> str:
        return self.title_zh if response_language == "zh" else self.title_en


_ALL_ARCHETYPES: frozenset[str] = frozenset({"comparison", "landscape", "mixed"})
_COMPARISON_AND_MIXED: frozenset[str] = frozenset({"comparison", "mixed"})
_LANDSCAPE_AND_MIXED: frozenset[str] = frozenset({"landscape", "mixed"})


_SECTION_SPECS: tuple[SectionSpec, ...] = (
    SectionSpec(
        section_id="executive_summary",
        kind="top_level",
        required_for=_ALL_ARCHETYPES,
        requires=_requires_always,
        title_zh="执行摘要",
        title_en="Executive Summary",
    ),
    SectionSpec(
        section_id="competitor_profiles",
        kind="deterministic",
        required_for=_COMPARISON_AND_MIXED,
        requires=_requires_at_least_one_core_substantive,
        title_zh="竞品画像",
        title_en="Competitor Profiles",
    ),
    SectionSpec(
        section_id="comparison_matrix",
        kind="deterministic",
        required_for=_COMPARISON_AND_MIXED,
        requires=_requires_at_least_two_core_substantive,
        title_zh="功能、定价与反馈对比",
        title_en="Comparison Matrix (Feature/Pricing/User Feedback)",
    ),
    SectionSpec(
        section_id="positioning_map",
        kind="deterministic",
        required_for=_COMPARISON_AND_MIXED,
        requires=_requires_at_least_one_core_substantive,
        title_zh="定位分析",
        title_en="Positioning Analysis",
    ),
    SectionSpec(
        section_id="self_positioning",
        kind="deterministic",
        required_for=_COMPARISON_AND_MIXED,
        requires=_requires_always,
        title_zh="我方位置与差异化",
        title_en="Self Positioning and Differentiation",
    ),
    SectionSpec(
        section_id="executive_takeaways",
        kind="top_level",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="核心判断",
        title_en="Executive Takeaways",
    ),
    SectionSpec(
        section_id="market_definition",
        kind="deterministic",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="市场定义与范围",
        title_en="Market Definition and Scope",
    ),
    SectionSpec(
        section_id="market_size_growth",
        kind="narrative",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="市场规模与增长驱动",
        title_en="Market Size and Growth Drivers",
    ),
    SectionSpec(
        section_id="market_segmentation",
        kind="deterministic",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="细分赛道",
        title_en="Market Segmentation",
    ),
    SectionSpec(
        section_id="competitive_landscape",
        kind="deterministic",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="竞争格局",
        title_en="Competitive Landscape",
    ),
    SectionSpec(
        section_id="key_players",
        kind="deterministic",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="关键玩家分析",
        title_en="Key Players",
    ),
    SectionSpec(
        section_id="value_chain",
        kind="deterministic",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="产业链与生态",
        title_en="Value Chain and Ecosystem",
    ),
    SectionSpec(
        section_id="opportunities_risks",
        kind="narrative",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="机会与风险",
        title_en="Opportunities and Risks",
    ),
    SectionSpec(
        section_id="strategic_recommendations",
        kind="narrative",
        required_for=_ALL_ARCHETYPES,
        requires=_requires_always,
        title_zh="战略建议",
        title_en="Strategic Recommendations",
    ),
    SectionSpec(
        section_id="methodology_limits",
        kind="deterministic",
        required_for=_LANDSCAPE_AND_MIXED,
        requires=_requires_always,
        title_zh="方法论与证据边界",
        title_en="Methodology and Evidence Limits",
    ),
)

SECTION_REGISTRY: dict[str, SectionSpec] = {spec.section_id: spec for spec in _SECTION_SPECS}

# Default, intent-driven outline ordering. `executive_summary` is always first.
# `mixed` is the reserved union (architecture present, not activated this round).
_DEFAULT_OUTLINES: dict[str, tuple[str, ...]] = {
    "comparison": (
        "executive_summary",
        "competitor_profiles",
        "comparison_matrix",
        "positioning_map",
        "self_positioning",
        "strategic_recommendations",
    ),
    "landscape": (
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
    ),
    "mixed": (
        "executive_summary",
        "executive_takeaways",
        "market_definition",
        "market_size_growth",
        "market_segmentation",
        "competitive_landscape",
        "key_players",
        "value_chain",
        "opportunities_risks",
        "competitor_profiles",
        "comparison_matrix",
        "positioning_map",
        "self_positioning",
        "strategic_recommendations",
        "methodology_limits",
    ),
}


def get_section_spec(section_id: str) -> SectionSpec | None:
    return SECTION_REGISTRY.get(section_id)


def is_known_section(section_id: str) -> bool:
    return section_id in SECTION_REGISTRY


def default_outline_for_archetype(archetype: str) -> tuple[str, ...]:
    return _DEFAULT_OUTLINES.get(archetype, _DEFAULT_OUTLINES["comparison"])


def required_sections_for_archetype(archetype: str) -> tuple[str, ...]:
    return tuple(
        spec.section_id
        for spec in _SECTION_SPECS
        if spec.is_required_for(archetype)
    )


def section_title(section_id: str, *, response_language: str | None) -> str:
    spec = SECTION_REGISTRY.get(section_id)
    if spec is not None:
        return spec.title(response_language=response_language)
    return section_id.replace("_", " ").title()


@dataclass(frozen=True)
class OutlineTriageResult:
    """Outcome of evidence-sufficiency triage over a resolved outline.

    `renderable`: sections to assemble (registry block satisfied, or extra dimension
    sections the LLM writes). `omitted`: non-required registry blocks dropped because
    their data predicate failed (silent omission, no scaffold). `degraded_required`:
    required-for-intent blocks whose data predicate failed — a data-level gap that must
    drive an honest degrade terminal state instead of rendering an empty skeleton.
    """

    renderable: tuple[str, ...]
    omitted: tuple[str, ...]
    degraded_required: tuple[str, ...]


def triage_outline_sections(
    *,
    target_sections: Sequence[str],
    archetype: str,
    ctx: SectionEvidenceContext,
) -> OutlineTriageResult:
    renderable: list[str] = []
    omitted: list[str] = []
    degraded_required: list[str] = []
    for section_id in target_sections:
        spec = SECTION_REGISTRY.get(section_id)
        if spec is None:
            # Extra dimension sections (feature, pricing, ...) are LLM-written narrative
            # blocks, not registry deterministic blocks; the writer/LLM owns their content.
            renderable.append(section_id)
            continue
        if spec.requires(ctx):
            renderable.append(section_id)
        elif spec.is_required_for(archetype):
            degraded_required.append(section_id)
        else:
            omitted.append(section_id)
    return OutlineTriageResult(
        renderable=tuple(renderable),
        omitted=tuple(omitted),
        degraded_required=tuple(degraded_required),
    )
