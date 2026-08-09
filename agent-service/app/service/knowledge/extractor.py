from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from pydantic import ValidationError

from schemas.business import Feature, Persona, Pricing, UserFeedback
from schemas.contracts import validate_dimension
from schemas.ids import make_id

CoverageStatus = Literal[
    "complete",
    "partial",
    "insufficient_data",
    "missing",
    "not_applicable_for_archetype",
    "not_requested",
]
KnowledgeCoverage = dict[str, dict[str, CoverageStatus]]
SchemaBucket = Literal["feature", "pricing", "persona"]

SCHEMA_VERSION = "schema_v0.2"
_LANDSCAPE_CORE_ROLES = frozenset({"direct_competitor", "adjacent_competitor", "substitute"})


@dataclass(frozen=True)
class KnowledgeExtractionResult:
    schema_version: str
    features: list[dict[str, object]]
    pricings: list[dict[str, object]]
    personas: list[dict[str, object]]
    feedback: list[dict[str, object]]
    coverage: KnowledgeCoverage
    extraction_mode: Literal["comparison", "landscape"]
    missing_reasons: dict[str, list[str]]
    supporting_target_evidence_ids: dict[str, dict[str, list[str]]]


@dataclass(frozen=True)
class _EvidenceBrief:
    evidence_id: str
    competitor_id: str
    dimension: str | None
    quote_preview: str
    source_title: str
    category_relevance: str
    category_relevance_reason: str


def _safe_string(value: object) -> str:
    return value.strip() if isinstance(value, str) else ""


def _normalize_dimension(value: object) -> str | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return validate_dimension(value)
    except ValueError:
        return None


def _normalize_competitors(values: list[str]) -> list[str]:
    ordered: list[str] = []
    seen: set[str] = set()
    for value in values:
        normalized = value.strip()
        if not normalized or normalized in seen:
            continue
        seen.add(normalized)
        ordered.append(normalized)
    return ordered


def _normalize_evidence_briefs(
    *,
    evidence_briefs: list[dict[str, object]],
    competitors: set[str],
) -> list[_EvidenceBrief]:
    normalized: list[_EvidenceBrief] = []
    for item in evidence_briefs:
        evidence_id = _safe_string(item.get("evidence_id"))
        competitor_id = _safe_string(item.get("competitor_id"))
        if not evidence_id or not competitor_id or competitor_id == "unknown":
            continue
        if competitors and competitor_id not in competitors:
            continue
        category_relevance = _safe_string(item.get("category_relevance")) or "target"
        if category_relevance == "off_topic":
            continue
        normalized.append(
            _EvidenceBrief(
                evidence_id=evidence_id,
                competitor_id=competitor_id,
                dimension=_normalize_dimension(item.get("dimension")),
                quote_preview=_safe_string(item.get("quote_preview")),
                source_title=_safe_string(item.get("source_title")),
                category_relevance=category_relevance,
                category_relevance_reason=_safe_string(item.get("category_relevance_reason")),
            )
        )
    return normalized


def _schema_bucket_for_dimension(dimension: str | None) -> SchemaBucket:
    if dimension is None:
        return "feature"
    if "pricing" in dimension or "price" in dimension or "cost" in dimension:
        return "pricing"
    if (
        "feedback" in dimension
        or "persona" in dimension
        or "buyer" in dimension
    ):
        return "persona"
    return "feature"


def _schema_bucket_for_brief(brief: _EvidenceBrief) -> SchemaBucket:
    bucket = _schema_bucket_for_dimension(brief.dimension)
    text = f"{brief.source_title} {brief.quote_preview}".lower()
    pricing_terms = (
        "pricing",
        "price",
        "subscription",
        "monthly",
        "annual",
        "per user",
        "seat",
        "tier",
        "free plan",
        "enterprise plan",
        "quota",
        "token",
        "credit",
        "定价",
        "价格",
        "收费",
        "套餐",
        "订阅",
        "每用户",
        "席位",
    )
    if any(term in text for term in pricing_terms):
        return "pricing"
    persona_terms = (
        "persona",
        "target user",
        "user segment",
        "target segment",
        "buyer persona",
        "buyer",
        "feedback",
        "review",
        "目标用户",
        "用户画像",
        "用户群体",
        "用户反馈",
        "买方",
        "画像",
        "评价",
        "口碑",
    )
    if bucket != "pricing" and any(term in text for term in persona_terms):
        return "persona"
    return bucket


def _feature_name(*, brief: _EvidenceBrief, index: int) -> str:
    if brief.source_title:
        return brief.source_title[:80]
    if brief.dimension:
        return f"{brief.dimension} signal {index}"
    return f"capability signal {index}"


def _pricing_model_hint(evidence: list[_EvidenceBrief]) -> str:
    if not evidence:
        return "unknown"
    joined = " ".join([item.quote_preview for item in evidence]).lower()
    if "seat" in joined:
        return "seat"
    if "usage" in joined or "token" in joined:
        return "usage"
    if "subscription" in joined or "monthly" in joined or "annual" in joined:
        return "subscription"
    return "unknown"


def _persona_role(competitor_id: str) -> str:
    normalized = "".join(
        character.lower() if character.isalnum() else "_"
        for character in competitor_id
    ).strip("_")
    return f"{normalized or 'unknown'}_buyer"


def _feedback_sentiment_hint(text: str) -> Literal["positive", "neutral", "negative", "mixed"]:
    lowered = text.lower()
    positive_terms = (
        "good",
        "great",
        "fast",
        "helpful",
        "love",
        "improve",
        "稳定",
        "满意",
        "高效",
        "好用",
    )
    negative_terms = (
        "bad",
        "slow",
        "issue",
        "bug",
        "expensive",
        "hard",
        "difficult",
        "差",
        "贵",
        "问题",
        "不稳定",
    )
    has_positive = any(term in lowered for term in positive_terms)
    has_negative = any(term in lowered for term in negative_terms)
    if has_positive and has_negative:
        return "mixed"
    if has_positive:
        return "positive"
    if has_negative:
        return "negative"
    return "neutral"


def _coverage_for_counts(
    *,
    feature_count: int,
    pricing_count: int,
    pricing_tier_count: int,
    feedback_count: int,
    persona_count: int,
    pricing_applicable: bool,
) -> dict[str, CoverageStatus]:
    feature_status: CoverageStatus
    if feature_count >= 3:
        feature_status = "complete"
    elif feature_count > 0:
        feature_status = "partial"
    else:
        feature_status = "insufficient_data"
    if not pricing_applicable:
        pricing_status = "not_applicable_for_archetype"
    elif pricing_count <= 0:
        pricing_status: CoverageStatus = "insufficient_data"
    elif pricing_tier_count >= pricing_count:
        pricing_status = "complete"
    else:
        pricing_status = "partial"
    if feedback_count >= 2:
        feedback_status: CoverageStatus = "complete"
    elif feedback_count > 0:
        feedback_status = "partial"
    else:
        feedback_status = "insufficient_data"
    if persona_count >= 2:
        persona_status: CoverageStatus = "complete"
    elif persona_count > 0:
        persona_status = "partial"
    else:
        persona_status = "insufficient_data"
    return {
        "feature": feature_status,
        "pricing": pricing_status,
        "feedback": feedback_status,
        "persona": persona_status,
    }


def _empty_coverage(competitors: list[str]) -> KnowledgeCoverage:
    return {
        competitor_id: {
            "feature": "insufficient_data",
            "pricing": "insufficient_data",
            "feedback": "insufficient_data",
            "persona": "insufficient_data",
        }
        for competitor_id in competitors
    }


def _pricing_requested(*, focus_dimensions: list[str] | None) -> bool:
    if not focus_dimensions:
        return False
    normalized_dimensions = [_normalize_dimension(item) for item in focus_dimensions]
    return any(
        dimension is not None and ("pricing" in dimension or "cost" in dimension)
        for dimension in normalized_dimensions
    )


def _coerce_competitor_id(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = value.strip()
    return normalized if normalized else None


def _persona_matches_competitor(
    *,
    persona_item: dict[str, object],
    competitor_id: str,
    evidence_owner_by_id: dict[str, str],
) -> bool:
    direct_competitor = _coerce_competitor_id(persona_item.get("competitor_id"))
    if direct_competitor == competitor_id:
        return True
    evidence_ids_raw = persona_item.get("evidence_ids")
    if isinstance(evidence_ids_raw, list):
        for evidence_id in evidence_ids_raw:
            if (
                isinstance(evidence_id, str)
                and evidence_owner_by_id.get(evidence_id) == competitor_id
            ):
                return True
    return False


def _normalize_competitor_roles(
    competitor_roles: dict[str, str] | None,
) -> dict[str, str]:
    normalized: dict[str, str] = {}
    if not isinstance(competitor_roles, dict):
        return normalized
    for competitor_id_raw, role_raw in competitor_roles.items():
        competitor_id = _coerce_competitor_id(competitor_id_raw)
        role = _coerce_competitor_id(role_raw)
        if competitor_id is None or role is None:
            continue
        normalized[competitor_id] = role
    return normalized


def _target_evidence_ids_for_item(
    item: dict[str, object],
    *,
    evidence_category_by_id: dict[str, str],
) -> list[str]:
    evidence_ids_raw = item.get("evidence_ids")
    if not isinstance(evidence_ids_raw, list):
        return []
    return [
        evidence_id
        for evidence_id in evidence_ids_raw
        if isinstance(evidence_id, str) and evidence_category_by_id.get(evidence_id, "target") == "target"
    ]


def _has_any_evidence(item: dict[str, object]) -> bool:
    evidence_ids_raw = item.get("evidence_ids")
    return isinstance(evidence_ids_raw, list) and any(isinstance(evidence_id, str) for evidence_id in evidence_ids_raw)


def build_knowledge_schema_result(
    *,
    schema_version: str,
    features: list[dict[str, object]],
    pricings: list[dict[str, object]],
    personas: list[dict[str, object]],
    feedback: list[dict[str, object]],
    competitors: list[str],
    analysis_archetype: str,
    focus_dimensions: list[str] | None = None,
    competitor_roles: dict[str, str] | None = None,
    evidence_category_by_id: dict[str, str] | None = None,
) -> KnowledgeExtractionResult:
    ordered_competitors = _normalize_competitors(competitors)
    if not ordered_competitors:
        ordered_competitors = _normalize_competitors(
            [
                competitor_id
                for competitor_id in (
                    _coerce_competitor_id(item.get("competitor_id"))
                    for item in [*features, *pricings, *feedback]
                )
                if competitor_id is not None
            ]
        )
    coverage: KnowledgeCoverage = _empty_coverage(ordered_competitors)
    missing_reasons: dict[str, list[str]] = {}
    normalized_competitor_roles = _normalize_competitor_roles(competitor_roles)
    evidence_categories = evidence_category_by_id or {}
    supporting_target_evidence_ids: dict[str, dict[str, list[str]]] = {}
    evidence_owner_by_id: dict[str, str] = {}
    for item in [*features, *pricings, *feedback]:
        competitor_id = _coerce_competitor_id(item.get("competitor_id"))
        if competitor_id is None:
            continue
        evidence_ids_raw = item.get("evidence_ids")
        if not isinstance(evidence_ids_raw, list):
            continue
        for evidence_id in evidence_ids_raw:
            if isinstance(evidence_id, str) and evidence_id not in evidence_owner_by_id:
                evidence_owner_by_id[evidence_id] = competitor_id
    for competitor_id in ordered_competitors:
        competitor_role = normalized_competitor_roles.get(competitor_id)
        landscape_peripheral_competitor = (
            analysis_archetype == "landscape"
            and competitor_role is not None
            and competitor_role not in _LANDSCAPE_CORE_ROLES
        )
        competitor_features = [
            item
            for item in features
            if _coerce_competitor_id(item.get("competitor_id")) == competitor_id
        ]
        feature_count = sum(
            1
            for item in competitor_features
            if _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
        )
        feature_any_count = sum(1 for item in competitor_features if _has_any_evidence(item))
        competitor_pricings = [
            item
            for item in pricings
            if _coerce_competitor_id(item.get("competitor_id")) == competitor_id
        ]
        pricing_count = sum(
            1
            for item in competitor_pricings
            if _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
        )
        pricing_any_count = sum(1 for item in competitor_pricings if _has_any_evidence(item))
        pricing_tier_count = sum(
            1
            for item in competitor_pricings
            if isinstance(item.get("tiers"), list) and bool(item["tiers"])
            and _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
        )
        competitor_feedback = [
            item
            for item in feedback
            if _coerce_competitor_id(item.get("competitor_id")) == competitor_id
        ]
        feedback_count = sum(
            1
            for item in competitor_feedback
            if _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
        )
        feedback_any_count = sum(1 for item in competitor_feedback if _has_any_evidence(item))
        persona_count = sum(
            1
            for item in personas
            if _persona_matches_competitor(
                persona_item=item,
                competitor_id=competitor_id,
                evidence_owner_by_id=evidence_owner_by_id,
            )
            and _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
        )
        persona_any_count = sum(
            1
            for item in personas
            if _persona_matches_competitor(
                persona_item=item,
                competitor_id=competitor_id,
                evidence_owner_by_id=evidence_owner_by_id,
            )
            and _has_any_evidence(item)
        )
        pricing_applicable = (
            analysis_archetype != "landscape"
            or not landscape_peripheral_competitor
            or pricing_count > 0
            or _pricing_requested(focus_dimensions=focus_dimensions)
        )
        landscape_schema_not_applicable = (
            landscape_peripheral_competitor
            and feature_count == 0
            and persona_count == 0
            and feedback_count == 0
        )
        coverage[competitor_id] = _coverage_for_counts(
            feature_count=feature_count,
            pricing_count=pricing_count,
            pricing_tier_count=pricing_tier_count,
            feedback_count=feedback_count,
            persona_count=persona_count,
            pricing_applicable=pricing_applicable,
        )
        for dimension, target_ids in {
            "feature": [
                evidence_id
                for item in competitor_features
                for evidence_id in _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
            ],
            "pricing": [
                evidence_id
                for item in competitor_pricings
                for evidence_id in _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
            ],
            "feedback": [
                evidence_id
                for item in competitor_feedback
                for evidence_id in _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
            ],
            "persona": [
                evidence_id
                for item in personas
                if _persona_matches_competitor(
                    persona_item=item,
                    competitor_id=competitor_id,
                    evidence_owner_by_id=evidence_owner_by_id,
                )
                for evidence_id in _target_evidence_ids_for_item(item, evidence_category_by_id=evidence_categories)
            ],
        }.items():
            if target_ids:
                supporting_target_evidence_ids.setdefault(competitor_id, {})[dimension] = sorted(set(target_ids))
        for dimension, any_count in {
            "feature": feature_any_count,
            "pricing": pricing_any_count,
            "feedback": feedback_any_count,
            "persona": persona_any_count,
        }.items():
            if coverage[competitor_id].get(dimension) == "insufficient_data" and any_count > 0:
                coverage[competitor_id][dimension] = "partial"
        if landscape_schema_not_applicable:
            coverage[competitor_id]["feature"] = "not_applicable_for_archetype"
            coverage[competitor_id]["feedback"] = "not_applicable_for_archetype"
            coverage[competitor_id]["persona"] = "not_applicable_for_archetype"
        reasons: list[str] = []
        if landscape_schema_not_applicable:
            reasons.append("feature:not_applicable_for_archetype")
        elif feature_count == 0:
            reasons.append(
                "feature:category_mismatch"
                if feature_any_count > 0
                else "feature:no_grounded_evidence"
            )
        elif feature_count < 3:
            reasons.append("feature:coverage_partial")
        if not pricing_applicable:
            reasons.append("pricing:not_applicable_for_archetype")
        elif pricing_count == 0:
            reasons.append(
                "pricing:category_mismatch"
                if pricing_any_count > 0
                else "pricing:no_grounded_evidence"
            )
        elif pricing_tier_count == 0:
            reasons.append("pricing:tier_details_missing")
        if landscape_schema_not_applicable:
            reasons.append("feedback:not_applicable_for_archetype")
        elif feedback_count == 0:
            reasons.append(
                "feedback:category_mismatch"
                if feedback_any_count > 0
                else "feedback:no_grounded_evidence"
            )
        elif feedback_count < 2:
            reasons.append("feedback:coverage_partial")
        if landscape_schema_not_applicable:
            reasons.append("persona:not_applicable_for_archetype")
        elif persona_count == 0:
            reasons.append(
                "persona:category_mismatch"
                if persona_any_count > 0
                else "persona:no_grounded_evidence"
            )
        elif persona_count < 2:
            reasons.append("persona:coverage_partial")
        if reasons:
            missing_reasons[competitor_id] = reasons
    return KnowledgeExtractionResult(
        schema_version=schema_version,
        features=features,
        pricings=pricings,
        personas=personas,
        feedback=feedback,
        coverage=coverage,
        extraction_mode="landscape" if analysis_archetype == "landscape" else "comparison",
        missing_reasons=missing_reasons,
        supporting_target_evidence_ids=supporting_target_evidence_ids,
    )


def extract_knowledge_schema(
    *,
    evidence_briefs: list[dict[str, object]],
    competitors: list[str],
    focus_dimensions: list[str],
    analysis_archetype: str,
    competitor_roles: dict[str, str] | None = None,
) -> KnowledgeExtractionResult:
    ordered_competitors = _normalize_competitors(competitors)
    competitor_set = set(ordered_competitors)
    normalized_evidence = _normalize_evidence_briefs(
        evidence_briefs=evidence_briefs,
        competitors=competitor_set,
    )
    evidence_category_by_id = {
        item.evidence_id: item.category_relevance for item in normalized_evidence
    }
    if not ordered_competitors:
        ordered_competitors = _normalize_competitors(
            [item.competitor_id for item in normalized_evidence]
        )
        competitor_set = set(ordered_competitors)

    feature_by_competitor: dict[str, list[_EvidenceBrief]] = {
        competitor_id: [] for competitor_id in ordered_competitors
    }
    pricing_by_competitor: dict[str, list[_EvidenceBrief]] = {
        competitor_id: [] for competitor_id in ordered_competitors
    }
    persona_by_competitor: dict[str, list[_EvidenceBrief]] = {
        competitor_id: [] for competitor_id in ordered_competitors
    }
    for brief in normalized_evidence:
        if competitor_set and brief.competitor_id not in competitor_set:
            continue
        bucket = _schema_bucket_for_brief(brief)
        if bucket == "pricing":
            pricing_by_competitor.setdefault(brief.competitor_id, []).append(brief)
        elif bucket == "persona":
            persona_by_competitor.setdefault(brief.competitor_id, []).append(brief)
        else:
            feature_by_competitor.setdefault(brief.competitor_id, []).append(brief)

    features: list[dict[str, object]] = []
    for competitor_id in ordered_competitors:
        seen_names: set[str] = set()
        for index, brief in enumerate(feature_by_competitor.get(competitor_id, [])[:8], start=1):
            feature_name = _feature_name(brief=brief, index=index)
            if feature_name in seen_names:
                continue
            seen_names.add(feature_name)
            try:
                features.append(
                    Feature.model_validate(
                        {
                            "id": make_id("feat_"),
                            "competitor_id": competitor_id,
                            "name": feature_name,
                            "parent_id": None,
                            "description": brief.quote_preview[:240] if brief.quote_preview else None,
                            "maturity": "unknown",
                            "evidence_ids": [brief.evidence_id],
                        }
                    ).model_dump(mode="python")
                )
            except ValidationError:
                continue

    pricings: list[dict[str, object]] = []
    for competitor_id in ordered_competitors:
        pricing_evidence = pricing_by_competitor.get(competitor_id, [])
        if not pricing_evidence:
            continue
        evidence_ids = list({item.evidence_id for item in pricing_evidence[:4]})
        try:
            pricings.append(
                Pricing.model_validate(
                    {
                        "id": make_id("price_"),
                        "competitor_id": competitor_id,
                        "model": _pricing_model_hint(pricing_evidence),
                        "tiers": [],
                        "free_plan": None,
                        "enterprise_plan": None,
                        "evidence_ids": evidence_ids,
                    }
                ).model_dump(mode="python")
            )
        except ValidationError:
            continue

    personas: list[dict[str, object]] = []
    feedback: list[dict[str, object]] = []
    for competitor_id in ordered_competitors:
        persona_evidence = persona_by_competitor.get(competitor_id, [])
        if not persona_evidence:
            continue
        first = persona_evidence[0]
        pain_point = first.quote_preview[:160] if first.quote_preview else ""
        try:
            personas.append(
                Persona.model_validate(
                    {
                        "id": make_id("persona_"),
                        "competitor_id": competitor_id,
                        "name": f"{competitor_id} buyer persona",
                        "role": _persona_role(competitor_id),
                        "pain_points": [pain_point] if pain_point else [],
                        "jobs_to_be_done": [],
                        "evidence_ids": [first.evidence_id],
                    }
                ).model_dump(mode="python")
            )
        except ValidationError:
            continue
        for index, brief in enumerate(persona_evidence[:3], start=1):
            topic_hint = brief.dimension or "user_feedback"
            summary = brief.quote_preview[:240] if brief.quote_preview else ""
            if not summary:
                continue
            try:
                feedback.append(
                    UserFeedback.model_validate(
                        {
                            "id": make_id("feedback_"),
                            "competitor_id": competitor_id,
                            "sentiment": _feedback_sentiment_hint(summary),
                            "topic": f"{topic_hint}_{index}",
                            "summary": summary,
                            "evidence_ids": [brief.evidence_id],
                        }
                    ).model_dump(mode="python")
                )
            except ValidationError:
                continue

    return build_knowledge_schema_result(
        schema_version=SCHEMA_VERSION,
        features=features,
        pricings=pricings,
        personas=personas,
        feedback=feedback,
        competitors=ordered_competitors,
        analysis_archetype=analysis_archetype,
        focus_dimensions=focus_dimensions,
        competitor_roles=competitor_roles,
        evidence_category_by_id=evidence_category_by_id,
    )


__all__ = [
    "KnowledgeCoverage",
    "KnowledgeExtractionResult",
    "build_knowledge_schema_result",
    "extract_knowledge_schema",
]
