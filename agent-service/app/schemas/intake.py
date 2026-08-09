from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel, Field, computed_field, field_validator, model_validator

UserRole = Literal["pm", "founder", "sales", "investor"]
FocusDimension = str
# Output archetype (intent classification → adaptive output form). `comparison`:
# head-to-head over a comparable competitor set (per-competitor feature/pricing/
# persona schema applies). `landscape`: opportunity / trend / whitespace scan with
# no fixed comparable set (per-competitor schema is optional, framing is a map).
AnalysisArchetype = Literal["comparison", "landscape"]
ScopePolicy = Literal["explicit_category", "broad_market"]

_UNKNOWN_OPTIONAL_VALUES: frozenset[str] = frozenset(
    {
        "不知道",
        "不清楚",
        "不确定",
        "不了解",
        "未知",
        "无",
        "没有",
        "暂无",
        "none",
        "n/a",
        "na",
        "unknown",
        "not sure",
        "no",
        "全景扫描 · 覆盖整个赛道，不收窄 (whole-landscape)",
        "whole landscape — scan the full track, do not narrow",
        "whole landscape - scan the full track, do not narrow",
        "whole-landscape",
    }
)
_BROAD_MARKET_TERMS: tuple[str, ...] = (
    "硬件",
    "hardware",
    "market",
    "市场",
    "赛道",
    "行业",
    "全景",
    "landscape",
)
_CATEGORY_STOPWORDS: tuple[str, ...] = (
    "全景",
    "趋势",
    "分析",
    "报告",
    "机会",
    "市场",
    "中国",
    "全球",
)
_AI_GLASSES_ALIASES: tuple[str, ...] = (
    "AI眼镜",
    "智能眼镜",
    "AR眼镜",
    "AI glasses",
    "AI smart glasses",
    "smart glasses",
    "AR glasses",
)
_AI_HARDWARE_ALIASES: tuple[str, ...] = (
    "AI硬件",
    "AI hardware",
    "wearable AI hardware",
    "可穿戴AI硬件",
)


def normalize_optional_text(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    if not normalized:
        return None
    if normalized.casefold() in _UNKNOWN_OPTIONAL_VALUES:
        return None
    return normalized


def stable_unique_text(values: list[str]) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for value in values:
        normalized = normalize_optional_text(value)
        if normalized is None:
            continue
        key = normalized.casefold()
        if key in seen:
            continue
        seen.add(key)
        ordered.append(normalized)
    return ordered


def infer_target_category(
    *,
    user_query: str,
    domain_hint: str | None = None,
    analysis_intent: str | None = None,
) -> str | None:
    source = normalize_optional_text(user_query)
    if source is None:
        source = normalize_optional_text(analysis_intent)
    if source is None:
        source = normalize_optional_text(domain_hint)
    if source is None:
        return None
    candidate = source
    for separator in ("：", ":", "\n", "，", ","):
        candidate = candidate.split(separator, 1)[0]
    for stopword in _CATEGORY_STOPWORDS:
        candidate = candidate.replace(stopword, "")
    candidate = re.sub(r"(与|和|及|and)$", "", candidate.strip(), flags=re.IGNORECASE)
    candidate = re.sub(r"\s+", " ", candidate).strip(" -_/·")
    return candidate or source


def infer_scope_policy(target_category: str | None) -> ScopePolicy:
    if target_category is None:
        return "explicit_category"
    lowered = target_category.casefold()
    if any(term in lowered for term in _BROAD_MARKET_TERMS):
        return "broad_market"
    return "explicit_category"


def category_aliases_for_target(target_category: str | None) -> list[str]:
    target = normalize_optional_text(target_category)
    if target is None:
        return []
    aliases = [target]
    lowered = target.casefold()
    if "眼镜" in target or "glasses" in lowered:
        aliases.extend(_AI_GLASSES_ALIASES)
    if "硬件" in target or "hardware" in lowered:
        aliases.extend(_AI_HARDWARE_ALIASES)
    return stable_unique_text(aliases)


def text_mentions_any_term(text: str, terms: list[str]) -> bool:
    normalized_text = text.casefold()
    for term in terms:
        normalized_term = term.casefold().strip()
        if normalized_term and normalized_term in normalized_text:
            return True
    return False


class RunIntakeDraft(BaseModel):
    """Structured intent accumulated across Agent-native intake turns.

    The IntakeAgent keeps clarifying until `is_complete` is True, at which point the
    run advances from `intake` to `planning`. `is_complete` is computed (not stored)
    so it can never drift from the underlying fields.
    """

    user_query: str
    user_role: UserRole | None = None
    analysis_intent: str | None = None
    competitors_explicit: list[str] = Field(default_factory=list)
    competitors_discovery_mode: bool = False
    domain_hint: str | None = None
    target_category: str | None = None
    category_aliases: list[str] = Field(default_factory=list)
    excluded_categories: list[str] = Field(default_factory=list)
    market_segments: list[str] = Field(default_factory=list)
    scope_policy: ScopePolicy = "explicit_category"
    focus_dimensions: list[FocusDimension] = Field(default_factory=list)
    report_depth: Literal["debug", "quick", "deep"] = "quick"
    reference_urls: list[str] = Field(default_factory=list)
    # Quality-enriching context (optional; never gate completion). These let the
    # Planner/Analyst frame competitors RELATIVE to the requester and scope the
    # research, which is what separates a neutral listing from actionable CI.
    # `self_product`: requester's own product/positioning anchor.
    # `market_scope`: target market / geography / segment (e.g. China vs overseas).
    # `time_context`: decision timing or data-recency requirement.
    self_product: str | None = None
    market_scope: str | None = None
    time_context: str | None = None
    # Backward-compatible field name: response_language == report output language.
    response_language: Literal["zh", "en"] | None = None
    # Defaults to comparison to preserve legacy behavior; never gates completion.
    analysis_archetype: AnalysisArchetype = "comparison"

    @field_validator(
        "analysis_intent",
        "domain_hint",
        "target_category",
        "self_product",
        "market_scope",
        "time_context",
        mode="before",
    )
    @classmethod
    def _normalize_optional_text_fields(cls, value: object) -> str | None:
        return normalize_optional_text(value)

    @field_validator(
        "category_aliases",
        "excluded_categories",
        "market_segments",
        "competitors_explicit",
        "focus_dimensions",
        "reference_urls",
        mode="before",
    )
    @classmethod
    def _normalize_text_lists(cls, value: object) -> list[str]:
        if value is None:
            return []
        if not isinstance(value, list):
            return []
        return stable_unique_text([item for item in value if isinstance(item, str)])

    @model_validator(mode="after")
    def _derive_category_scope(self) -> "RunIntakeDraft":
        target_category = self.target_category or infer_target_category(
            user_query=self.user_query,
            domain_hint=self.domain_hint,
            analysis_intent=self.analysis_intent,
        )
        category_aliases = stable_unique_text(
            [
                *(self.category_aliases or []),
                *category_aliases_for_target(target_category),
            ]
        )
        scope_policy = self.scope_policy or infer_scope_policy(target_category)
        inferred_scope_policy = infer_scope_policy(target_category)
        if inferred_scope_policy == "broad_market":
            scope_policy = "broad_market"
        self.target_category = target_category
        self.category_aliases = category_aliases
        self.scope_policy = scope_policy
        return self

    @computed_field
    @property
    def is_complete(self) -> bool:
        # Completion gate (product decision): know who the user is, what they want,
        # and either an explicit competitor set or an opt-in to Agent discovery.
        has_identity = self.user_role is not None
        has_intent = bool(self.analysis_intent and self.analysis_intent.strip())
        has_competitor_path = bool(self.competitors_explicit) or self.competitors_discovery_mode
        return has_identity and has_intent and has_competitor_path


class IntakeClarifyRequest(BaseModel):
    """A single clarifying question the Agent asks to fill specific draft fields."""

    question: str
    field_targets: list[str] = Field(default_factory=list)
    suggested_options: list[str] | None = None
    suggested_answer: str | None = None


class IntakeUserReply(BaseModel):
    """User answer to an IntakeClarifyRequest (resume payload for the intake interrupt).

    At least one of `text` / `selected_options` must be non-empty — empty replies would
    feed the IntakeAgent an empty observation and trigger a re-ask loop on the same field.
    """

    text: str = ""
    selected_options: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def _require_nonempty_signal(self) -> "IntakeUserReply":
        if not self.text.strip() and not self.selected_options:
            raise ValueError("IntakeUserReply requires non-empty text or selected_options")
        return self


class IntakeExchange(BaseModel):
    """One completed clarify+reply round, appended to AgentState.intake_history.

    Modeled (not a raw tuple) so it survives checkpoint JSON round-trips intact.
    Only fully-resolved rounds belong in history; an in-flight clarify lives in
    `AgentState.pending_clarify`, not here.
    """

    clarify: IntakeClarifyRequest
    reply: IntakeUserReply
