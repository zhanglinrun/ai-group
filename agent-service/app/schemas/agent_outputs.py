from __future__ import annotations

from typing import Literal, Self

from pydantic import BaseModel, ConfigDict, Field, ValidationError, ValidationInfo, field_validator, model_validator

from schemas.business import Feature, Persona, Pricing, UserFeedback
from schemas.contracts import normalize_dimension_or_none, validate_dimension, validate_section_id, validate_template_id
from schemas.ids import make_id
from schemas.report_sections import default_outline_for_archetype, get_section_spec, is_known_section

ConfidenceLevel = Literal["high", "medium", "low"]
ComparisonStance = Literal["leader", "competitive", "laggard", "unknown"]
DEFAULT_WRITER_SECTIONS: tuple[str, ...] = default_outline_for_archetype("comparison")
MIN_WRITER_SECTION_CHARS = 60
CoverageStatus = Literal["complete", "partial", "insufficient_data", "missing"]
KnowledgeCoverage = dict[str, dict[str, CoverageStatus]]


def stable_unique(values: list[str]) -> list[str]:
    seen: set[str] = set()
    ordered: list[str] = []
    for value in values:
        if value in seen:
            continue
        seen.add(value)
        ordered.append(value)
    return ordered


def _section_allowed_for_archetype(section_id: str, archetype: str) -> bool:
    spec = get_section_spec(section_id)
    return spec is not None and spec.is_required_for(archetype)


def resolve_writer_target_sections(
    *,
    requested_sections: list[str] | None,
    recommended_sections: list[str],
    report_outline: list[OutlineItem] | None = None,
    analysis_archetype: str = "comparison",
) -> list[str]:
    """Single source of truth for writer section targets across analyst → writer."""
    targets: list[str] = list(default_outline_for_archetype(analysis_archetype))
    for item in report_outline or []:
        if _section_allowed_for_archetype(item.section_id, analysis_archetype):
            targets.append(item.section_id)
    for section_id in requested_sections or []:
        try:
            canonical = validate_section_id(section_id)
        except ValueError:
            continue
        if _section_allowed_for_archetype(canonical, analysis_archetype):
            targets.append(canonical)
    for section_id in recommended_sections:
        try:
            canonical = validate_section_id(section_id)
        except ValueError:
            continue
        if _section_allowed_for_archetype(canonical, analysis_archetype):
            targets.append(canonical)
    normalized_targets = stable_unique(
        [item for item in targets if _section_allowed_for_archetype(item, analysis_archetype)]
    )
    if not normalized_targets:
        normalized_targets = list(DEFAULT_WRITER_SECTIONS)
    if analysis_archetype == "landscape":
        return [item for item in normalized_targets if item != "executive_summary"]
    if "executive_summary" not in normalized_targets:
        normalized_targets.insert(0, "executive_summary")
    without_summary = [item for item in normalized_targets if item != "executive_summary"]
    return ["executive_summary", *without_summary]


def _filter_valid_section_ids(values: list[str]) -> list[str]:
    normalized: list[str] = []
    for value in values:
        raw_value = value.strip()
        try:
            canonical = validate_section_id(raw_value)
        except ValueError:
            continue
        # Analyst-recommended sections must already be canonical section IDs.
        # Free-form titles should fall back to insight dimensions.
        if canonical != raw_value:
            continue
        normalized.append(canonical)
    return stable_unique(normalized)


def _normalize_outline_items(value: object) -> list[dict[str, str | None]]:
    if not isinstance(value, list):
        return []
    normalized: list[dict[str, str | None]] = []
    seen: set[str] = set()
    for item in value:
        section_raw: object
        directive_raw: object = None
        if isinstance(item, str):
            section_raw = item
        elif isinstance(item, dict):
            section_raw = item.get("section_id")
            directive_raw = item.get("directive")
        else:
            continue
        if not isinstance(section_raw, str):
            continue
        section_candidate = section_raw.strip()
        if not section_candidate:
            continue
        try:
            section_id = validate_section_id(section_candidate)
        except ValueError:
            continue
        if not is_known_section(section_id) or section_id in seen:
            continue
        seen.add(section_id)
        directive = (
            directive_raw.strip()
            if isinstance(directive_raw, str) and directive_raw.strip()
            else None
        )
        normalized.append({"section_id": section_id, "directive": directive})
    return normalized


def _normalize_allowed_competitors(competitors: set[str] | None) -> set[str]:
    return {item.strip() for item in competitors or set() if item.strip()}


def _filter_allowed_evidence_ids(value: object, allowed_evidence_ids: set[str]) -> list[str]:
    if not isinstance(value, list):
        return []
    return stable_unique(
        [
            evidence_id
            for evidence_id in value
            if isinstance(evidence_id, str) and evidence_id in allowed_evidence_ids
        ]
    )


def _string_value(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized if normalized else None


def _bool_or_none(value: object) -> bool | None:
    return value if isinstance(value, bool) else None


def _list_of_strings(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return stable_unique([item.strip() for item in value if isinstance(item, str) and item.strip()])


def _list_of_dicts(value: object) -> list[dict[object, object]]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, dict)]


def _normalize_coverage(
    value: object,
    *,
    competitors: set[str],
) -> KnowledgeCoverage:
    allowed_statuses: set[CoverageStatus] = {
        "complete",
        "partial",
        "insufficient_data",
        "missing",
    }
    allowed_keys = {"feature", "pricing", "feedback", "persona"}
    if not isinstance(value, dict):
        return {}
    normalized: KnowledgeCoverage = {}
    for competitor_raw, coverage_raw in value.items():
        competitor_id = _string_value(competitor_raw)
        if competitor_id is None:
            continue
        if competitors and competitor_id not in competitors:
            continue
        if not isinstance(coverage_raw, dict):
            continue
        coverage_item: dict[str, CoverageStatus] = {}
        for key_raw, status_raw in coverage_raw.items():
            key = _string_value(key_raw)
            status = _string_value(status_raw)
            if key not in allowed_keys or status not in allowed_statuses:
                continue
            coverage_item[key] = status  # type: ignore[assignment]
        if coverage_item:
            normalized[competitor_id] = coverage_item
    return normalized


def _filter_features(
    value: object,
    *,
    allowed_evidence_ids: set[str],
    competitors: set[str],
) -> list[dict[str, object]]:
    raw_features = _list_of_dicts(value)
    candidates: list[dict[str, object]] = []
    raw_id_to_new_id: dict[str, str] = {}
    raw_id_to_competitor: dict[str, str] = {}
    for item in raw_features:
        competitor_id = _string_value(item.get("competitor_id"))
        name = _string_value(item.get("name"))
        evidence_ids = _filter_allowed_evidence_ids(item.get("evidence_ids"), allowed_evidence_ids)
        if (
            competitor_id is None
            or (competitors and competitor_id not in competitors)
            or name is None
            or not evidence_ids
        ):
            continue
        feature_id = make_id("feat_")
        raw_id = _string_value(item.get("id"))
        if raw_id is not None:
            raw_id_to_new_id[raw_id] = feature_id
            raw_id_to_competitor[raw_id] = competitor_id
        candidates.append(
            {
                "id": feature_id,
                "competitor_id": competitor_id,
                "name": name,
                "parent_id": _string_value(item.get("parent_id")),
                "description": _string_value(item.get("description")),
                "maturity": item.get("maturity"),
                "evidence_ids": evidence_ids,
            }
        )
    filtered: list[dict[str, object]] = []
    for item in candidates:
        parent_id = item["parent_id"]
        competitor_id = item["competitor_id"]
        if (
            isinstance(parent_id, str)
            and raw_id_to_new_id.get(parent_id) is not None
            and raw_id_to_competitor.get(parent_id) == competitor_id
        ):
            item["parent_id"] = raw_id_to_new_id[parent_id]
        else:
            item["parent_id"] = None
        try:
            filtered.append(Feature.model_validate(item).model_dump(mode="python"))
        except ValidationError:
            continue
    return filtered


def _filter_pricings(
    value: object,
    *,
    allowed_evidence_ids: set[str],
    competitors: set[str],
) -> list[dict[str, object]]:
    filtered: list[dict[str, object]] = []
    for item in _list_of_dicts(value):
        competitor_id = _string_value(item.get("competitor_id"))
        model = _string_value(item.get("model")) or "unknown"
        evidence_ids = _filter_allowed_evidence_ids(item.get("evidence_ids"), allowed_evidence_ids)
        if (
            competitor_id is None
            or (competitors and competitor_id not in competitors)
            or not evidence_ids
        ):
            continue
        payload = {
            "id": make_id("price_"),
            "competitor_id": competitor_id,
            "model": model,
            "tiers": _list_of_dicts(item.get("tiers")),
            "free_plan": _bool_or_none(item.get("free_plan")),
            "enterprise_plan": _bool_or_none(item.get("enterprise_plan")),
            "evidence_ids": evidence_ids,
        }
        try:
            filtered.append(Pricing.model_validate(payload).model_dump(mode="python"))
        except ValidationError:
            continue
    return filtered


def _filter_personas(
    value: object,
    *,
    allowed_evidence_ids: set[str],
    competitors: set[str],
) -> list[dict[str, object]]:
    filtered: list[dict[str, object]] = []
    for item in _list_of_dicts(value):
        competitor_id = _string_value(item.get("competitor_id"))
        name = _string_value(item.get("name"))
        role = _string_value(item.get("role"))
        if (
            competitor_id is None
            or (competitors and competitor_id not in competitors)
            or name is None
            or role is None
        ):
            continue
        payload = {
            "id": make_id("persona_"),
            "competitor_id": competitor_id,
            "name": name,
            "role": role,
            "pain_points": _list_of_strings(item.get("pain_points")),
            "jobs_to_be_done": _list_of_strings(item.get("jobs_to_be_done")),
            "evidence_ids": _filter_allowed_evidence_ids(item.get("evidence_ids"), allowed_evidence_ids),
        }
        try:
            filtered.append(Persona.model_validate(payload).model_dump(mode="python"))
        except ValidationError:
            continue
    return filtered


def _filter_feedback(
    value: object,
    *,
    allowed_evidence_ids: set[str],
    competitors: set[str],
) -> list[dict[str, object]]:
    filtered: list[dict[str, object]] = []
    for item in _list_of_dicts(value):
        competitor_id = _string_value(item.get("competitor_id"))
        sentiment = _string_value(item.get("sentiment"))
        topic = _string_value(item.get("topic"))
        summary = _string_value(item.get("summary"))
        evidence_ids = _filter_allowed_evidence_ids(item.get("evidence_ids"), allowed_evidence_ids)
        if (
            competitor_id is None
            or (competitors and competitor_id not in competitors)
            or sentiment not in {"positive", "neutral", "negative", "mixed"}
            or topic is None
            or summary is None
            or not evidence_ids
        ):
            continue
        payload = {
            "id": make_id("feedback_"),
            "competitor_id": competitor_id,
            "sentiment": sentiment,
            "topic": topic,
            "summary": summary,
            "evidence_ids": evidence_ids,
        }
        try:
            filtered.append(UserFeedback.model_validate(payload).model_dump(mode="python"))
        except ValidationError:
            continue
    return filtered


class AnalystInsight(BaseModel):
    dimension: str
    finding: str = Field(min_length=1)
    evidence_ids: list[str] = Field(min_length=1)
    confidence: ConfidenceLevel = "medium"

    @field_validator("dimension")
    @classmethod
    def _validate_dimension(cls, value: str) -> str:
        return validate_dimension(value)

    @field_validator("confidence", mode="before")
    @classmethod
    def _normalize_confidence(cls, value: object) -> ConfidenceLevel:
        if isinstance(value, str) and value in {"high", "medium", "low"}:
            return value  # type: ignore[return-value]
        return "medium"


class ComparisonCell(BaseModel):
    competitor_id: str = Field(min_length=1)
    stance: ComparisonStance = "unknown"
    summary: str = Field(min_length=1)
    evidence_ids: list[str] = Field(default_factory=list)

    @field_validator("stance", mode="before")
    @classmethod
    def _normalize_stance(cls, value: object) -> ComparisonStance:
        if isinstance(value, str) and value in {"leader", "competitive", "laggard", "unknown"}:
            return value  # type: ignore[return-value]
        return "unknown"

    @model_validator(mode="after")
    def _require_evidence_for_qualified_stance(self) -> Self:
        if self.stance != "unknown" and not self.evidence_ids:
            self.stance = "unknown"
        return self


class DimensionComparison(BaseModel):
    dimension: str
    cells: list[ComparisonCell] = Field(min_length=2)

    @field_validator("dimension")
    @classmethod
    def _validate_dimension(cls, value: str) -> str:
        return validate_dimension(value)


class OutlineItem(BaseModel):
    section_id: str
    directive: str | None = None

    @field_validator("section_id")
    @classmethod
    def _validate_known_section_id(cls, value: str) -> str:
        section_id = validate_section_id(value)
        if not is_known_section(section_id):
            raise ValueError(f"Unsupported outline section_id: {section_id}")
        return section_id

    @field_validator("directive", mode="before")
    @classmethod
    def _normalize_directive(cls, value: object) -> str | None:
        if not isinstance(value, str):
            return None
        normalized = value.strip()
        return normalized or None


class AnalystOutput(BaseModel):
    """Canonical analyst artifact consumed by writer and QA."""

    schema_version: str = "schema_v0.2"
    summary: str = Field(min_length=1)
    insights: list[AnalystInsight] = Field(min_length=1)
    comparisons: list[DimensionComparison] = Field(default_factory=list)
    risk_flags: list[str] = Field(default_factory=list)
    recommended_sections: list[str] = Field(default_factory=list)
    report_outline: list[OutlineItem] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def _normalize_outline_payload(cls, value: object) -> object:
        if not isinstance(value, dict):
            return value
        if "report_outline" not in value:
            return value
        return {
            **value,
            "report_outline": _normalize_outline_items(value.get("report_outline")),
        }

    @model_validator(mode="after")
    def _canonicalize_recommended_sections(self) -> Self:
        from_insights = stable_unique([insight.dimension for insight in self.insights])
        from_llm = _filter_valid_section_ids(self.recommended_sections)
        self.recommended_sections = from_llm or from_insights
        if self.report_outline:
            deduped_outline: list[OutlineItem] = []
            seen_sections: set[str] = set()
            for item in self.report_outline:
                if item.section_id in seen_sections:
                    continue
                seen_sections.add(item.section_id)
                deduped_outline.append(item)
            self.report_outline = deduped_outline
        return self

    @classmethod
    def parse_llm_content(
        cls,
        content: dict[str, object],
        *,
        allowed_evidence_ids: set[str],
        allowed_dimensions: set[str],
        competitors: set[str] | None = None,
        dropped_dimensions: dict[str, int] | None = None,
    ) -> AnalystOutput:
        insights_raw = content.get("insights")
        filtered_insights: list[dict[str, object]] = []
        if isinstance(insights_raw, list):
            for item in insights_raw:
                if not isinstance(item, dict):
                    continue
                dimension_raw = item.get("dimension")
                finding_raw = item.get("finding")
                evidence_ids_raw = item.get("evidence_ids")
                dimension, drop_reason = normalize_dimension_or_none(
                    dimension_raw,
                    allowed=allowed_dimensions,
                )
                if drop_reason is not None and dropped_dimensions is not None:
                    dropped_dimensions[drop_reason] = dropped_dimensions.get(drop_reason, 0) + 1
                if (
                    dimension is None
                    or not isinstance(finding_raw, str)
                    or not finding_raw.strip()
                    or not isinstance(evidence_ids_raw, list)
                ):
                    continue
                evidence_ids = [
                    evidence_id
                    for evidence_id in evidence_ids_raw
                    if isinstance(evidence_id, str) and evidence_id in allowed_evidence_ids
                ]
                if not evidence_ids:
                    continue
                filtered_insights.append(
                    {
                        "dimension": dimension,
                        "finding": finding_raw.strip(),
                        "evidence_ids": evidence_ids,
                        "confidence": item.get("confidence", "medium"),
                    }
                )
        comparisons_raw = content.get("comparisons")
        filtered_comparisons: list[dict[str, object]] = []
        allowed_competitors = {item.strip() for item in competitors or set() if item.strip()}
        if isinstance(comparisons_raw, list):
            for item in comparisons_raw:
                if not isinstance(item, dict):
                    continue
                dimension, drop_reason = normalize_dimension_or_none(
                    item.get("dimension"),
                    allowed=allowed_dimensions,
                )
                if drop_reason is not None and dropped_dimensions is not None:
                    dropped_dimensions[drop_reason] = dropped_dimensions.get(drop_reason, 0) + 1
                cells_raw = item.get("cells")
                if dimension is None or not isinstance(cells_raw, list):
                    continue
                filtered_cells: list[dict[str, object]] = []
                seen_competitors: set[str] = set()
                for cell in cells_raw:
                    if not isinstance(cell, dict):
                        continue
                    competitor_raw = cell.get("competitor_id")
                    summary_raw = cell.get("summary")
                    evidence_ids_raw = cell.get("evidence_ids")
                    if not isinstance(competitor_raw, str):
                        continue
                    competitor_id = competitor_raw.strip()
                    if (
                        not competitor_id
                        or competitor_id in seen_competitors
                        or (allowed_competitors and competitor_id not in allowed_competitors)
                        or not isinstance(summary_raw, str)
                        or not summary_raw.strip()
                    ):
                        continue
                    evidence_ids = (
                        [
                            evidence_id
                            for evidence_id in evidence_ids_raw
                            if isinstance(evidence_id, str) and evidence_id in allowed_evidence_ids
                        ]
                        if isinstance(evidence_ids_raw, list)
                        else []
                    )
                    seen_competitors.add(competitor_id)
                    filtered_cells.append(
                        {
                            "competitor_id": competitor_id,
                            "stance": cell.get("stance", "unknown"),
                            "summary": summary_raw.strip(),
                            "evidence_ids": stable_unique(evidence_ids),
                        }
                    )
                if len(filtered_cells) >= 2:
                    filtered_comparisons.append(
                        {
                            "dimension": dimension,
                            "cells": filtered_cells,
                        }
                    )
        allowed_competitors = _normalize_allowed_competitors(competitors)
        payload = {
            "schema_version": _string_value(content.get("schema_version")) or "schema_v0.2",
            "summary": content.get("summary"),
            "insights": filtered_insights,
            "comparisons": filtered_comparisons,
            "risk_flags": content.get("risk_flags") if isinstance(content.get("risk_flags"), list) else [],
            "recommended_sections": content.get("recommended_sections")
            if isinstance(content.get("recommended_sections"), list)
            else [],
            "report_outline": _normalize_outline_items(content.get("report_outline")),
        }
        return cls.model_validate(payload)

    @classmethod
    def parse_persisted(cls, payload: object) -> AnalystOutput | None:
        if not isinstance(payload, dict):
            return None
        insights_raw = payload.get("insights")
        if not isinstance(insights_raw, list) or not insights_raw:
            return None
        normalized_payload = {
            **payload,
            "report_outline": _normalize_outline_items(payload.get("report_outline")),
        }
        try:
            return cls.model_validate(normalized_payload)
        except ValidationError:
            return None

    def to_persisted_dict(self) -> dict[str, object]:
        return self.model_dump(mode="python")

    @classmethod
    def build_fallback(
        cls,
        *,
        focus_dimensions: list[str],
        evidence_briefs: list[dict[str, object]],
        competitors: list[str] | None = None,
        analysis_archetype: str = "comparison",
    ) -> AnalystOutput:
        covered_dimensions = stable_unique(
            [
                item["dimension"]
                for item in evidence_briefs
                if isinstance(item.get("dimension"), str) and item["dimension"]
            ]
        )
        uncovered_dimensions = [
            dimension
            for dimension in focus_dimensions
            if dimension not in covered_dimensions
        ]
        risk_flags = stable_unique(
            [
                "analyst_fallback_mode",
                *(f"uncovered_dimension:{dimension}" for dimension in uncovered_dimensions),
            ]
        )
        if evidence_briefs:
            first = next(
                (
                    item
                    for item in evidence_briefs
                    if isinstance(item.get("dimension"), str) and item["dimension"]
                ),
                evidence_briefs[0],
            )
            summary = (
                f"Fallback analysis generated from {len(evidence_briefs)} evidence snippets "
                f"across {len(focus_dimensions)} dimensions."
            )
            dimension_raw = first.get("dimension")
            dimension = dimension_raw if isinstance(dimension_raw, str) and dimension_raw else "general"
            competitor_raw = first.get("competitor_id")
            competitor_id = competitor_raw if isinstance(competitor_raw, str) else "unknown"
            evidence_id_raw = first.get("evidence_id")
            evidence_id = evidence_id_raw if isinstance(evidence_id_raw, str) else "ev_missing"
            insight = AnalystInsight(
                dimension=dimension,
                finding=(
                    f"Preliminary signal from {competitor_id} on {dimension} "
                    "requires deeper analyst iteration."
                ),
                evidence_ids=[evidence_id],
                confidence="low",
            )
        else:
            summary = "Fallback analysis generated without evidence; analyst should re-run after research recovers."
            dimension = focus_dimensions[0] if focus_dimensions else "general"
            insight = AnalystInsight(
                dimension=dimension,
                finding="No evidence available for analyst pass.",
                evidence_ids=["ev_missing"],
                confidence="low",
            )
        coverage_competitors = stable_unique(
            [
                item.strip()
                for item in competitors or []
                if isinstance(item, str) and item.strip()
            ]
        )
        if not coverage_competitors:
            coverage_competitors = stable_unique(
                [
                    item["competitor_id"]
                    for item in evidence_briefs
                    if isinstance(item.get("competitor_id"), str) and item["competitor_id"]
                ]
            )
        if not coverage_competitors:
            coverage_competitors = ["unknown"]
        return cls(
            summary=summary,
            insights=[insight],
            comparisons=[],
            risk_flags=risk_flags,
            recommended_sections=covered_dimensions or focus_dimensions or [dimension],
            report_outline=[
                {"section_id": section_id}
                for section_id in default_outline_for_archetype(analysis_archetype)
            ],
        )


class KnowledgeExtractionOutput(BaseModel):
    """Grounded structured knowledge extracted from analyst evidence briefs."""

    schema_version: str = "schema_v0.2"
    features: list[dict[str, object]] = Field(default_factory=list)
    pricings: list[dict[str, object]] = Field(default_factory=list)
    personas: list[dict[str, object]] = Field(default_factory=list)
    feedback: list[dict[str, object]] = Field(default_factory=list)

    @classmethod
    def parse_llm_content(
        cls,
        content: dict[str, object],
        *,
        allowed_evidence_ids: set[str],
        competitors: set[str] | None = None,
    ) -> KnowledgeExtractionOutput:
        allowed_competitors = _normalize_allowed_competitors(competitors)
        payload = {
            "schema_version": _string_value(content.get("schema_version")) or "schema_v0.2",
            "features": _filter_features(
                content.get("features"),
                allowed_evidence_ids=allowed_evidence_ids,
                competitors=allowed_competitors,
            ),
            "pricings": _filter_pricings(
                content.get("pricings"),
                allowed_evidence_ids=allowed_evidence_ids,
                competitors=allowed_competitors,
            ),
            "personas": _filter_personas(
                content.get("personas"),
                allowed_evidence_ids=allowed_evidence_ids,
                competitors=allowed_competitors,
            ),
            "feedback": _filter_feedback(
                content.get("feedback"),
                allowed_evidence_ids=allowed_evidence_ids,
                competitors=allowed_competitors,
            ),
        }
        return cls.model_validate(payload)

    @classmethod
    def parse_persisted(cls, payload: object) -> KnowledgeExtractionOutput | None:
        if not isinstance(payload, dict):
            return None
        try:
            return cls.model_validate(payload)
        except ValidationError:
            return None

    def to_persisted_dict(self) -> dict[str, object]:
        return self.model_dump(mode="python")


class WriterSectionOutput(BaseModel):
    section_id: str
    title: str = Field(min_length=1)
    content_markdown: str = Field(min_length=MIN_WRITER_SECTION_CHARS)
    evidence_refs: list[str] = Field(min_length=1)
    insight_refs: list[str] = Field(default_factory=list)

    @field_validator("section_id")
    @classmethod
    def _validate_section_id(cls, value: str) -> str:
        return validate_section_id(value)


class WriterExecutionContext(BaseModel):
    """Resolved writer contract: section targets + grounding sets."""

    model_config = ConfigDict(frozen=True)

    template_id: str | None
    target_sections: list[str]
    renderable_sections: list[str]
    allowed_evidence_ids: frozenset[str]
    allowed_insight_ids: frozenset[str]
    default_risk_callouts: tuple[str, ...] = Field(default_factory=tuple)

    @classmethod
    def resolve(
        cls,
        *,
        template_id: str | None,
        requested_sections: list[str] | None,
        analyst_output: AnalystOutput,
        allowed_evidence_ids: set[str],
        allowed_insight_ids: set[str],
        analysis_archetype: str = "comparison",
        default_risk_callouts: list[str] | None = None,
    ) -> WriterExecutionContext:
        target_sections = resolve_writer_target_sections(
            requested_sections=requested_sections,
            recommended_sections=analyst_output.recommended_sections,
            report_outline=analyst_output.report_outline,
            analysis_archetype=analysis_archetype,
        )
        return cls(
            template_id=template_id,
            target_sections=target_sections,
            renderable_sections=target_sections,
            allowed_evidence_ids=frozenset(allowed_evidence_ids),
            allowed_insight_ids=frozenset(allowed_insight_ids),
            default_risk_callouts=tuple(default_risk_callouts or analyst_output.risk_flags),
        )


class WriterReportOutput(BaseModel):
    template_id: str
    title: str = Field(min_length=1)
    executive_summary: str = Field(min_length=1)
    sections: list[WriterSectionOutput] = Field(min_length=1)
    risk_callouts: list[str] = Field(default_factory=list)

    @field_validator("template_id")
    @classmethod
    def _validate_template_id(cls, value: str) -> str:
        return validate_template_id(value)

    @model_validator(mode="after")
    def _validate_against_execution_context(self, info: ValidationInfo) -> Self:
        context = info.context if info.context else {}
        allowed_evidence_ids: set[str] = context.get("allowed_evidence_ids", set())
        allowed_insight_ids: set[str] = context.get("allowed_insight_ids", set())
        target_sections: list[str] = context.get("target_sections", [])
        renderable_sections: list[str] = context.get("renderable_sections", target_sections)
        expected_template_id: str | None = context.get("template_id")

        if expected_template_id is not None and self.template_id != expected_template_id:
            raise ValueError(
                f"template_id mismatch: expected {expected_template_id!r}, got {self.template_id!r}"
            )

        normalized_sections: list[WriterSectionOutput] = []
        for section in self.sections:
            evidence_refs = [
                evidence_id
                for evidence_id in section.evidence_refs
                if evidence_id in allowed_evidence_ids
            ]
            if not evidence_refs:
                continue
            insight_refs = [
                insight_id
                for insight_id in section.insight_refs
                if insight_id in allowed_insight_ids
            ]
            normalized_sections.append(
                section.model_copy(
                    update={
                        "evidence_refs": stable_unique(evidence_refs),
                        "insight_refs": stable_unique(insight_refs),
                    }
                )
            )
        if not normalized_sections:
            raise ValueError("No sections remain after evidence grounding.")

        if renderable_sections:
            present = {section.section_id for section in normalized_sections}
            if self.executive_summary.strip():
                present.add("executive_summary")
            missing = [section_id for section_id in renderable_sections if section_id not in present]
            if missing:
                self.risk_callouts = stable_unique(
                    [
                        *self.risk_callouts,
                        *(f"uncovered_section:{section_id}" for section_id in missing),
                    ]
                )

        self.sections = normalized_sections
        return self

    @classmethod
    def parse_llm_content(
        cls,
        content: dict[str, object],
        *,
        execution_context: WriterExecutionContext,
    ) -> WriterReportOutput:
        template_id_raw = content.get("template_id")
        if execution_context.template_id is not None:
            template_id = execution_context.template_id
        elif isinstance(template_id_raw, str) and template_id_raw.strip():
            template_id = template_id_raw.strip()
        else:
            template_id = "default"

        payload = {
            **content,
            "template_id": template_id,
        }
        risk_callouts_raw = payload.get("risk_callouts")
        if not isinstance(risk_callouts_raw, list):
            payload["risk_callouts"] = list(execution_context.default_risk_callouts)
        return cls.model_validate(
            payload,
            context={
                "allowed_evidence_ids": set(execution_context.allowed_evidence_ids),
                "allowed_insight_ids": set(execution_context.allowed_insight_ids),
                "target_sections": execution_context.target_sections,
                "renderable_sections": execution_context.renderable_sections,
                "template_id": execution_context.template_id,
            },
        )

    def to_report_content(self) -> dict[str, object]:
        return self.model_dump(mode="python")


from schemas.agent_outputs_pipeline import (  # noqa: E402
    DiscoveryExtractOutput,
    ExtractStructuredOutput,
    IntakeTurnOutput,
    PlannerOutput,
    QASemanticOutput,
    ReplannerOutput,
    ResearcherCompressionOutput,
    ResearcherDecisionOutput,
    SkillCuratorHarnessOutput,
    SupervisorToolCallOutput,
)

__all__ = [
    "AnalystOutput",
    "ComparisonCell",
    "ComparisonStance",
    "ConfidenceLevel",
    "DEFAULT_WRITER_SECTIONS",
    "DimensionComparison",
    "DiscoveryExtractOutput",
    "ExtractStructuredOutput",
    "IntakeTurnOutput",
    "KnowledgeExtractionOutput",
    "MIN_WRITER_SECTION_CHARS",
    "OutlineItem",
    "PlannerOutput",
    "QASemanticOutput",
    "ReplannerOutput",
    "ResearcherCompressionOutput",
    "ResearcherDecisionOutput",
    "SkillCuratorHarnessOutput",
    "SupervisorToolCallOutput",
    "WriterExecutionContext",
    "WriterReportOutput",
    "resolve_writer_target_sections",
    "stable_unique",
]
