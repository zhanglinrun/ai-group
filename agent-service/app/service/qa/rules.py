from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Literal

from core.defaults import (
    DEEP_REPORT_MIN_CHAR_COUNT,
    DEEP_REPORT_MIN_EVIDENCE_REFS_PER_SECTION,
    DEEP_REPORT_MIN_SECTION_CHAR_COUNT,
    DEEP_REPORT_MIN_SECTION_COVERAGE_RATE,
)
from models.evidence import EvidenceRecord
from schemas.contracts import validate_section_id
from schemas.report_sections import required_sections_for_archetype
from service.collector.source_quality import source_blocklist_reason
from service.locale import normalize_report_language, source_locale, target_country_from_scope

RuleSeverity = Literal["blocking", "warning"]
RuleRejectTarget = Literal["supervisor", "researcher", "analyst", "writer"]
_HONEST_INCOMPLETE_COVERAGE = {"partial", "insufficient_data", "missing"}
# Coverage floor, NOT a dominance target: an explicitly China-scoped analysis should have
# at least some domestic firsthand grounding. Foreign sources stay valid for breadth.
_MIN_DOMESTIC_COVERAGE_RATE = 0.20
_TRIPLET_FIELDS: tuple[str, ...] = ("feature", "pricing", "feedback")
_TRIPLET_MIN_SUPPORTED_DIMENSIONS = 2
_MAX_SINGLE_COMPETITOR_EVIDENCE_SHARE = 0.60
_MAX_BLOCKLIST_EVIDENCE_SHARE = 0.20
_PLACEHOLDER_SECTION_MARKERS: tuple[str, ...] = (
    "暂缺足够证据",
    "lacks enough grounded evidence",
    "no grounded evidence matched this section",
)
_PLACEHOLDER_SCAFFOLDING_MARKERS: tuple[str, ...] = (
    "trigger follow-up research",
    "todo",
    "待补充",
)
_LEGACY_WORKBENCH_SECTION_IDS: frozenset[str] = frozenset(
    {
        "market_landscape_map",
        "competitor_profiles",
        "comparison_matrix",
        "positioning_map",
        "representative_benchmarks",
        "trend_summary",
    }
)
_LEGACY_WORKBENCH_TITLE_MARKERS: tuple[str, ...] = (
    "竞品分层地图",
    "逐竞品画像",
    "代表标杆",
    "直接竞品",
    "替代方案",
    "2x2",
)
_LANDSCAPE_CORE_SECTION_IDS: frozenset[str] = frozenset(
    {
        "executive_takeaways",
        "market_definition",
        "competitive_landscape",
        "key_players",
        "methodology_limits",
    }
)
_REPORT_LANGUAGE_ZH_MIN_CJK_CHARS = 20
_REPORT_LANGUAGE_ZH_MIN_CJK_RATIO = 0.28
_REPORT_LANGUAGE_ZH_LATIN_GRACE_CHARS = 24
_REPORT_LANGUAGE_EN_MAX_CJK_CHARS = 24
_REPORT_LANGUAGE_EN_MAX_CJK_RATIO = 0.20
_EVIDENCE_CITATION_TOKEN_PATTERN = re.compile(r"\[ev_[^\]]+\]", re.IGNORECASE)
_URL_TOKEN_PATTERN = re.compile(r"https?://\S+", re.IGNORECASE)
_CODE_SPAN_PATTERN = re.compile(r"`[^`]+`")


@dataclass(frozen=True)
class RuleResult:
    rule_id: str
    passed: bool
    severity: RuleSeverity
    reject_to: RuleRejectTarget
    message: str


def rule_report_must_have_markdown_content(content_markdown: str) -> RuleResult:
    passed = bool(content_markdown.strip())
    return RuleResult(
        rule_id="rule_report_must_have_markdown_content",
        passed=passed,
        severity="blocking",
        reject_to="writer",
        message="Report markdown must be non-empty.",
    )


def rule_report_template_id_present(content_json: dict[str, object]) -> RuleResult:
    template_id_raw = content_json.get("template_id")
    passed = isinstance(template_id_raw, str) and bool(template_id_raw.strip())
    return RuleResult(
        rule_id="rule_report_template_id_present",
        passed=passed,
        severity="blocking",
        reject_to="writer",
        message="template_id must be a non-empty string.",
    )


def rule_report_must_have_at_least_one_section(content_json: dict[str, object]) -> RuleResult:
    sections_raw = content_json.get("sections")
    passed = isinstance(sections_raw, list) and len(sections_raw) >= 1
    return RuleResult(
        rule_id="rule_report_must_have_at_least_one_section",
        passed=passed,
        severity="blocking",
        reject_to="writer",
        message="Report must contain at least one section.",
    )


def rule_writer_sections_must_have_content(content_json: dict[str, object]) -> RuleResult:
    sections_raw = content_json.get("sections")
    passed = False
    if isinstance(sections_raw, list) and sections_raw:
        passed = True
        for section in sections_raw:
            if not isinstance(section, dict):
                passed = False
                break
            section_id_raw = section.get("section_id")
            if not isinstance(section_id_raw, str):
                passed = False
                break
            try:
                validate_section_id(section_id_raw)
            except ValueError:
                passed = False
                break
            content_markdown_raw = section.get("content_markdown")
            if (
                not isinstance(content_markdown_raw, str)
                or len(content_markdown_raw.strip()) < 60
            ):
                passed = False
                break
    return RuleResult(
        rule_id="rule_writer_sections_must_have_content",
        passed=passed,
        severity="blocking",
        reject_to="writer",
        message="Every section must include substantial content_markdown.",
    )


def rule_writer_no_placeholder_scaffolding(content_json: dict[str, object]) -> RuleResult:
    scaffold_sections: list[str] = []
    for section in _iter_report_sections(content_json):
        section_id = _section_id(section) or "unknown"
        markdown = _section_markdown(section)
        if _markdown_is_placeholder_scaffolding(markdown):
            scaffold_sections.append(section_id)
    return RuleResult(
        rule_id="rule_writer_no_placeholder_scaffolding",
        passed=not scaffold_sections,
        severity="blocking",
        reject_to="writer",
        message=(
            "Report body must not contain placeholder scaffolding "
            f"(sections={scaffold_sections})."
        ),
    )


def _markdown_is_placeholder_scaffolding(content_markdown: str) -> bool:
    normalized = re.sub(r"\s+", " ", content_markdown).strip().casefold()
    if not normalized:
        return False
    has_placeholder_marker = any(marker in normalized for marker in _PLACEHOLDER_SECTION_MARKERS)
    if not has_placeholder_marker:
        return False
    if any(marker in normalized for marker in _PLACEHOLDER_SCAFFOLDING_MARKERS):
        return True
    return any(normalized.startswith(marker) for marker in _PLACEHOLDER_SECTION_MARKERS)


def rule_writer_must_cite_evidence(
    *,
    content_json: dict[str, object],
    allowed_evidence_ids: set[str],
) -> RuleResult:
    sections_raw = content_json.get("sections")
    invalid_ref_detected = False
    sections_missing_valid_refs: list[str] = []
    seen_missing_sections: set[str] = set()

    def _record_missing_section(section_label: str) -> None:
        if section_label in seen_missing_sections:
            return
        seen_missing_sections.add(section_label)
        sections_missing_valid_refs.append(section_label)

    if isinstance(sections_raw, list):
        for index, section in enumerate(sections_raw):
            section_label = f"index:{index}"
            if not isinstance(section, dict):
                invalid_ref_detected = True
                _record_missing_section(section_label)
                continue
            section_id_raw = section.get("section_id")
            if isinstance(section_id_raw, str) and section_id_raw.strip():
                section_label = section_id_raw.strip()
            evidence_refs_raw = section.get("evidence_refs")
            if not isinstance(evidence_refs_raw, list):
                _record_missing_section(section_label)
                continue
            has_valid_ref = False
            for evidence_id in evidence_refs_raw:
                if not isinstance(evidence_id, str):
                    invalid_ref_detected = True
                    continue
                if evidence_id not in allowed_evidence_ids:
                    invalid_ref_detected = True
                    continue
                has_valid_ref = True
            if not has_valid_ref:
                _record_missing_section(section_label)
    else:
        _record_missing_section("sections")
    passed = (
        isinstance(sections_raw, list)
        and bool(sections_raw)
        and not invalid_ref_detected
        and not sections_missing_valid_refs
    )
    return RuleResult(
        rule_id="rule_writer_must_cite_evidence",
        passed=passed,
        severity="blocking",
        reject_to="writer",
        message=(
            "Each writer section must cite at least one valid collected evidence_id; "
            f"sections_missing_valid_refs={sections_missing_valid_refs}."
        ),
    )


def rule_report_section_count_in_bounds(content_json: dict[str, object]) -> RuleResult:
    sections_raw = content_json.get("sections")
    section_count = len(sections_raw) if isinstance(sections_raw, list) else 0
    passed = 1 <= section_count <= 12
    return RuleResult(
        rule_id="rule_report_section_count_in_bounds",
        passed=passed,
        severity="blocking",
        reject_to="writer",
        message="Report section count must be between 1 and 12.",
    )


def rule_evidence_must_be_desensitized(evidence_items: list[EvidenceRecord]) -> RuleResult:
    passed = all(item.desensitized for item in evidence_items)
    return RuleResult(
        rule_id="rule_evidence_must_be_desensitized",
        passed=passed,
        severity="blocking",
        reject_to="researcher",
        message="All evidence rows must be desensitized before downstream reporting.",
    )


def rule_writer_no_fallback_mode(content_json: dict[str, object]) -> RuleResult:
    risk_callouts_raw = content_json.get("risk_callouts")
    has_fallback_flag = (
        isinstance(risk_callouts_raw, list)
        and "writer_fallback_mode" in risk_callouts_raw
    )
    return RuleResult(
        rule_id="rule_writer_no_fallback_mode",
        passed=not has_fallback_flag,
        severity="blocking",
        reject_to="writer",
        message="Report must not be generated in deterministic writer fallback mode.",
    )


def rule_report_language_consistency(
    *,
    content_json: dict[str, object],
    response_language: str | None,
) -> RuleResult:
    normalized_response_language = normalize_report_language(response_language)
    if normalized_response_language is None:
        return RuleResult(
            rule_id="rule_report_language_consistency",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="Report language consistency skipped: response_language is not explicitly set.",
        )
    report_text = _language_check_text(content_json)
    if not report_text.strip():
        return RuleResult(
            rule_id="rule_report_language_consistency",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="Report language consistency skipped: no report text available.",
        )
    sanitized = _sanitize_language_check_text(report_text)
    cjk_chars, latin_chars = _script_char_counts(sanitized)
    script_total = cjk_chars + latin_chars
    cjk_ratio = (cjk_chars / script_total) if script_total > 0 else 0.0
    if normalized_response_language == "zh":
        passed = cjk_chars >= _REPORT_LANGUAGE_ZH_MIN_CJK_CHARS and (
            cjk_ratio >= _REPORT_LANGUAGE_ZH_MIN_CJK_RATIO
            or latin_chars <= _REPORT_LANGUAGE_ZH_LATIN_GRACE_CHARS
        )
    else:
        passed = (
            cjk_chars <= _REPORT_LANGUAGE_EN_MAX_CJK_CHARS
            or cjk_ratio <= _REPORT_LANGUAGE_EN_MAX_CJK_RATIO
        )
    return RuleResult(
        rule_id="rule_report_language_consistency",
        passed=passed,
        severity="blocking",
        reject_to="writer",
        message=(
            "Report body and section titles should stay in response_language "
            f"(response_language={normalized_response_language}, cjk_chars={cjk_chars}, "
            f"latin_chars={latin_chars}, cjk_ratio={cjk_ratio:.2f})."
        ),
    )


def rule_landscape_no_legacy_workbench_sections(
    *,
    content_json: dict[str, object],
    content_markdown: str,
    analysis_archetype: str,
) -> RuleResult:
    if analysis_archetype != "landscape":
        return RuleResult(
            rule_id="rule_landscape_no_legacy_workbench_sections",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="Legacy workbench section check skipped for non-landscape report.",
        )
    section_ids = [
        section_id
        for section in _iter_report_sections(content_json)
        for section_id in [_section_id(section)]
        if section_id is not None
    ]
    legacy_ids = [section_id for section_id in section_ids if section_id in _LEGACY_WORKBENCH_SECTION_IDS]
    legacy_titles = [
        marker
        for marker in _LEGACY_WORKBENCH_TITLE_MARKERS
        if _markdown_has_legacy_title_marker(content_markdown, marker)
    ]
    return RuleResult(
        rule_id="rule_landscape_no_legacy_workbench_sections",
        passed=not legacy_ids and not legacy_titles,
        severity="blocking",
        reject_to="writer",
        message=(
            "Landscape report must not expose legacy workbench sections or headings "
            f"(legacy_ids={legacy_ids}, legacy_titles={legacy_titles})."
        ),
    )


def _markdown_has_legacy_title_marker(content_markdown: str, marker: str) -> bool:
    for line in content_markdown.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        normalized = stripped.lstrip("#").strip().strip("*").strip()
        if normalized == marker:
            return True
        if normalized.startswith(f"{marker}：") or normalized.startswith(f"{marker}:"):
            return True
    return False


def rule_landscape_core_commercial_sections_present(
    *,
    content_json: dict[str, object],
    analysis_archetype: str,
) -> RuleResult:
    if analysis_archetype != "landscape":
        return RuleResult(
            rule_id="rule_landscape_core_commercial_sections_present",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="Commercial landscape core section check skipped for non-landscape report.",
        )
    present = _covered_report_section_ids(content_json)
    missing = sorted(_LANDSCAPE_CORE_SECTION_IDS - present)
    return RuleResult(
        rule_id="rule_landscape_core_commercial_sections_present",
        passed=not missing,
        severity="blocking",
        reject_to="writer",
        message=f"Landscape report is missing commercial core sections (missing={missing}).",
    )


def rule_complete_coverage_has_target_evidence(
    *,
    knowledge: dict[str, object],
) -> RuleResult:
    coverage = knowledge.get("coverage")
    supporting_raw = knowledge.get("supporting_target_evidence_ids")
    supporting = supporting_raw if isinstance(supporting_raw, dict) else {}
    failures: list[str] = []
    if isinstance(coverage, dict):
        for competitor, dimensions in coverage.items():
            if not isinstance(competitor, str) or not isinstance(dimensions, dict):
                continue
            support_for_competitor = supporting.get(competitor)
            support_map = support_for_competitor if isinstance(support_for_competitor, dict) else {}
            for dimension, status in dimensions.items():
                if status != "complete" or not isinstance(dimension, str):
                    continue
                target_ids = support_map.get(dimension)
                if not isinstance(target_ids, list) or not any(isinstance(item, str) for item in target_ids):
                    failures.append(f"{competitor}.{dimension}")
    return RuleResult(
        rule_id="rule_complete_coverage_has_target_evidence",
        passed=not failures,
        severity="blocking",
        reject_to="researcher",
        message=(
            "Complete knowledge coverage must be supported by target-category evidence "
            f"(failures={failures})."
        ),
    )


def _iter_report_sections(content_json: dict[str, object]) -> list[dict[str, object]]:
    sections_raw = content_json.get("sections")
    if not isinstance(sections_raw, list):
        return []
    return [item for item in sections_raw if isinstance(item, dict)]


def _section_id(section: dict[str, object]) -> str | None:
    value = section.get("section_id")
    return value if isinstance(value, str) and value else None


def _section_markdown(section: dict[str, object]) -> str:
    value = section.get("content_markdown")
    return value.strip() if isinstance(value, str) else ""


def _section_evidence_refs(section: dict[str, object]) -> list[str]:
    refs_raw = section.get("evidence_refs")
    if not isinstance(refs_raw, list):
        return []
    return [item for item in refs_raw if isinstance(item, str) and item]


def _language_check_text(content_json: dict[str, object]) -> str:
    fragments: list[str] = []
    executive_summary_raw = content_json.get("executive_summary")
    if isinstance(executive_summary_raw, str) and executive_summary_raw.strip():
        fragments.append(executive_summary_raw)
    for section in _iter_report_sections(content_json):
        title_raw = section.get("title")
        if isinstance(title_raw, str) and title_raw.strip():
            fragments.append(title_raw)
        markdown_raw = section.get("content_markdown")
        if isinstance(markdown_raw, str) and markdown_raw.strip():
            fragments.append(markdown_raw)
    return "\n".join(fragments)


def _sanitize_language_check_text(text: str) -> str:
    without_citations = _EVIDENCE_CITATION_TOKEN_PATTERN.sub(" ", text)
    without_urls = _URL_TOKEN_PATTERN.sub(" ", without_citations)
    without_code_spans = _CODE_SPAN_PATTERN.sub(" ", without_urls)
    return without_code_spans


def _script_char_counts(text: str) -> tuple[int, int]:
    cjk_chars = sum(1 for char in text if "\u4e00" <= char <= "\u9fff")
    latin_chars = sum(1 for char in text if ("a" <= char <= "z") or ("A" <= char <= "Z"))
    return cjk_chars, latin_chars


def _executive_summary_is_present(content_json: dict[str, object]) -> bool:
    summary_raw = content_json.get("executive_summary")
    return isinstance(summary_raw, str) and bool(summary_raw.strip())


def _covered_report_section_ids(content_json: dict[str, object]) -> set[str]:
    section_ids = {
        section_id
        for section in _iter_report_sections(content_json)
        for section_id in [_section_id(section)]
        if section_id is not None
    }
    if _executive_summary_is_present(content_json):
        section_ids.add("executive_summary")
    return section_ids


def _normalized_target_sections(target_sections: list[str] | None) -> list[str]:
    if not target_sections:
        return []
    normalized: list[str] = []
    seen: set[str] = set()
    for item in target_sections:
        if item in seen:
            continue
        try:
            validate_section_id(item)
        except ValueError:
            continue
        seen.add(item)
        normalized.append(item)
    return normalized


def rule_deep_report_min_char_count(
    *,
    content_markdown: str,
    min_chars: int = DEEP_REPORT_MIN_CHAR_COUNT,
) -> RuleResult:
    char_count = len(content_markdown.strip())
    return RuleResult(
        rule_id="rule_deep_report_min_char_count",
        passed=char_count >= min_chars,
        severity="blocking",
        reject_to="writer",
        message=f"Deep report markdown must be at least {min_chars} chars (actual={char_count}).",
    )


def rule_deep_report_covers_target_sections(
    *,
    content_json: dict[str, object],
    target_sections: list[str] | None,
    min_coverage_rate: float = DEEP_REPORT_MIN_SECTION_COVERAGE_RATE,
) -> RuleResult:
    targets = _normalized_target_sections(target_sections)
    if not targets:
        return RuleResult(
            rule_id="rule_deep_report_covers_target_sections",
            passed=True,
            severity="blocking",
            reject_to="writer",
            message="Deep report section coverage skipped because no target sections were resolved.",
        )
    actual_sections = _covered_report_section_ids(content_json)
    covered_count = sum(1 for target in targets if target in actual_sections)
    coverage_rate = covered_count / len(targets)
    missing = [target for target in targets if target not in actual_sections]
    return RuleResult(
        rule_id="rule_deep_report_covers_target_sections",
        passed=coverage_rate >= min_coverage_rate,
        severity="blocking",
        reject_to="writer",
        message=(
            "Deep report must cover target sections "
            f"(coverage={coverage_rate:.2f}, min={min_coverage_rate:.2f}, missing={missing})."
        ),
    )


def rule_deep_sections_min_chars(
    *,
    content_json: dict[str, object],
    min_chars: int = DEEP_REPORT_MIN_SECTION_CHAR_COUNT,
) -> RuleResult:
    sections = _iter_report_sections(content_json)
    short_sections = [
        _section_id(section) or "unknown"
        for section in sections
        if len(_section_markdown(section)) < min_chars
    ]
    return RuleResult(
        rule_id="rule_deep_sections_min_chars",
        passed=bool(sections) and not short_sections,
        severity="blocking",
        reject_to="writer",
        message=(
            f"Every deep report section must be at least {min_chars} chars "
            f"(short_sections={short_sections})."
        ),
    )


def rule_deep_sections_cite_evidence(
    *,
    content_json: dict[str, object],
    min_refs_per_section: int = DEEP_REPORT_MIN_EVIDENCE_REFS_PER_SECTION,
) -> RuleResult:
    sections = _iter_report_sections(content_json)
    under_cited_sections = [
        _section_id(section) or "unknown"
        for section in sections
        if len(_section_evidence_refs(section)) < min_refs_per_section
    ]
    return RuleResult(
        rule_id="rule_deep_sections_cite_evidence",
        passed=bool(sections) and not under_cited_sections,
        severity="blocking",
        reject_to="writer",
        message=(
            "Every deep report section must cite collected evidence "
            f"(min_refs_per_section={min_refs_per_section}, under_cited={under_cited_sections})."
        ),
    )


# Sections where a buyer cannot trust third-party summaries alone — at least one
# cited source must come from the vendor itself (R10).
_OFFICIAL_REQUIRED_SECTION_KEYWORDS: tuple[str, ...] = (
    "pricing",
    "enterprise",
    "compliance",
    "security",
)


def _evidence_authority_by_id(evidence_items: list[EvidenceRecord]) -> dict[str, str]:
    authority_by_id: dict[str, str] = {}
    for item in evidence_items:
        span = item.span if isinstance(item.span, dict) else {}
        authority_raw = span.get("source_authority")
        authority_by_id[item.id] = (
            authority_raw if isinstance(authority_raw, str) else "third_party"
        )
    return authority_by_id


def rule_buyer_critical_sections_need_official_source(
    *,
    content_json: dict[str, object],
    evidence_items: list[EvidenceRecord],
) -> RuleResult:
    authority_by_id = _evidence_authority_by_id(evidence_items)
    flagged: list[str] = []
    for section in _iter_report_sections(content_json):
        section_id = _section_id(section)
        if section_id is None:
            continue
        lowered = section_id.lower()
        if not any(keyword in lowered for keyword in _OFFICIAL_REQUIRED_SECTION_KEYWORDS):
            continue
        refs = _section_evidence_refs(section)
        if not refs:
            # Missing citations are covered by the citation rules; this gate only
            # judges the authority of sources that ARE cited.
            continue
        if not any(authority_by_id.get(ref) == "official" for ref in refs):
            flagged.append(section_id)
    return RuleResult(
        rule_id="rule_buyer_critical_sections_need_official_source",
        passed=not flagged,
        severity="warning",
        reject_to="researcher",
        message=(
            "Buyer-critical sections should cite at least one official (vendor) source; "
            f"sections relying only on third-party evidence: {flagged}."
        ),
    )


def _is_domestic_source(*, item: EvidenceRecord) -> bool:
    locale = source_locale(
        source_url=item.source_url,
        span=item.span if isinstance(item.span, dict) else None,
        sanitized_text=item.sanitized_text,
    )
    return locale["country"] == "china" or locale["language"] == "zh"


def rule_locale_mismatch(
    *,
    market_scope: str | None,
    evidence_items: list[EvidenceRecord],
    min_domestic_rate: float = _MIN_DOMESTIC_COVERAGE_RATE,
) -> RuleResult:
    # Region is derived ONLY from an explicit market scope; output language never gates it.
    target_country = target_country_from_scope(market_scope=market_scope)
    if target_country != "china" or not evidence_items:
        return RuleResult(
            rule_id="rule_locale_mismatch",
            passed=True,
            severity="warning",
            reject_to="researcher",
            message="Locale coverage check skipped: no explicit regional market scope (language does not constrain market).",
        )
    domestic_count = sum(1 for item in evidence_items if _is_domestic_source(item=item))
    domestic_rate = domestic_count / len(evidence_items)
    return RuleResult(
        rule_id="rule_locale_mismatch",
        passed=domestic_rate >= min_domestic_rate,
        severity="warning",
        reject_to="researcher",
        message=(
            "Explicit China-scope analysis has thin domestic firsthand coverage; "
            "add home-market sources (foreign sources remain valid for breadth) "
            f"(domestic_coverage={domestic_rate:.2f}, floor={min_domestic_rate:.2f}, "
            f"domestic={domestic_count}, total={len(evidence_items)})."
        ),
    )


def rule_structured_sections_present(
    *,
    content_json: dict[str, object],
    analysis_archetype: str,
) -> RuleResult:
    required_sections = list(required_sections_for_archetype(analysis_archetype))
    degraded_required_raw = content_json.get("report_degraded_required_sections")
    degraded_required = (
        {item for item in degraded_required_raw if isinstance(item, str)}
        if isinstance(degraded_required_raw, list)
        else set()
    )
    required_sections = [
        section_id for section_id in required_sections if section_id not in degraded_required
    ]
    present = _covered_report_section_ids(content_json)
    missing = [section_id for section_id in required_sections if section_id not in present]
    return RuleResult(
        rule_id="rule_structured_sections_present",
        passed=not missing,
        severity="blocking",
        reject_to="writer",
        message=(
            "Commercial report skeleton must include required structured sections "
            f"(missing={missing})."
        ),
    )


def _normalized_profile_competitors(profile_competitors: list[str] | None) -> list[str]:
    if not profile_competitors:
        return []
    normalized: list[str] = []
    for competitor in profile_competitors:
        if not isinstance(competitor, str):
            continue
        item = competitor.strip()
        if not item or item in normalized:
            continue
        normalized.append(item)
    return normalized


def rule_triplet_coverage_for_profile_competitors(
    *,
    knowledge: dict[str, object],
    profile_competitors: list[str] | None,
    min_supported_dimensions: int = _TRIPLET_MIN_SUPPORTED_DIMENSIONS,
) -> RuleResult:
    competitors = _normalized_profile_competitors(profile_competitors)
    coverage = knowledge.get("coverage")
    if not competitors:
        return RuleResult(
            rule_id="rule_triplet_coverage_for_profile_competitors",
            passed=True,
            severity="blocking",
            reject_to="researcher",
            message="Triplet coverage check skipped because no profile competitors were resolved.",
        )
    failures: list[str] = []
    for competitor in competitors:
        statuses: dict[str, str] = {}
        supported_count = 0
        for field in _TRIPLET_FIELDS:
            status = _coverage_status(
                coverage=coverage,
                competitor_id=competitor,
                field_name=field,
            ) or "missing"
            statuses[field] = status
            if status in {"complete", "partial"}:
                supported_count += 1
        if supported_count < min_supported_dimensions:
            failures.append(f"{competitor}:{statuses}")
    return RuleResult(
        rule_id="rule_triplet_coverage_for_profile_competitors",
        passed=not failures,
        severity="blocking",
        reject_to="researcher",
        message=(
            "Profile competitors need usable triplet coverage "
            f"(min_supported_dimensions={min_supported_dimensions}, failures={failures})."
        ),
    )


def rule_evidence_balance_for_profile_competitors(
    *,
    evidence_items: list[EvidenceRecord],
    profile_competitors: list[str] | None,
    max_single_competitor_share: float = _MAX_SINGLE_COMPETITOR_EVIDENCE_SHARE,
) -> RuleResult:
    competitors = _normalized_profile_competitors(profile_competitors)
    if not competitors:
        return RuleResult(
            rule_id="rule_evidence_balance_for_profile_competitors",
            passed=True,
            severity="blocking",
            reject_to="researcher",
            message="Evidence balance check skipped because no profile competitors were resolved.",
        )
    counts = {competitor: 0 for competitor in competitors}
    total = 0
    for item in evidence_items:
        span = item.span if isinstance(item.span, dict) else {}
        competitor_raw = span.get("competitor_id")
        if not isinstance(competitor_raw, str):
            continue
        competitor = competitor_raw.strip()
        if competitor not in counts:
            continue
        counts[competitor] += 1
        total += 1
    zero_competitors = [competitor for competitor, count in counts.items() if count == 0]
    max_share = (max(counts.values()) / total) if total > 0 else 1.0
    passed = total > 0 and not zero_competitors and max_share <= max_single_competitor_share
    return RuleResult(
        rule_id="rule_evidence_balance_for_profile_competitors",
        passed=passed,
        severity="blocking",
        reject_to="researcher",
        message=(
            "Evidence should be balanced across profile competitors "
            f"(counts={counts}, max_share={max_share:.2f}, limit={max_single_competitor_share:.2f}, "
            f"zero_competitors={zero_competitors})."
        ),
    )


def rule_source_quality_blocklist_share(
    *,
    evidence_items: list[EvidenceRecord],
    max_blocklist_share: float = _MAX_BLOCKLIST_EVIDENCE_SHARE,
) -> RuleResult:
    if not evidence_items:
        return RuleResult(
            rule_id="rule_source_quality_blocklist_share",
            passed=True,
            severity="blocking",
            reject_to="researcher",
            message="Source-quality blocklist check skipped because evidence is empty.",
        )
    blocked_ids: list[str] = []
    for item in evidence_items:
        if source_blocklist_reason(item.source_url) is not None:
            blocked_ids.append(item.id)
    blocked_ratio = len(blocked_ids) / len(evidence_items)
    return RuleResult(
        rule_id="rule_source_quality_blocklist_share",
        passed=blocked_ratio <= max_blocklist_share,
        severity="blocking",
        reject_to="researcher",
        message=(
            "Blocked/spam source share is too high "
            f"(blocked_ratio={blocked_ratio:.2f}, limit={max_blocklist_share:.2f}, blocked_ids={blocked_ids})."
        ),
    )


def _knowledge_items_by_competitor(
    items: object,
) -> dict[str, list[dict[str, object]]]:
    by_competitor: dict[str, list[dict[str, object]]] = {}
    if not isinstance(items, list):
        return by_competitor
    for item in items:
        if not isinstance(item, dict):
            continue
        competitor_id = item.get("competitor_id")
        if not isinstance(competitor_id, str) or not competitor_id.strip():
            continue
        by_competitor.setdefault(competitor_id.strip(), []).append(item)
    return by_competitor


def _coverage_status(
    *,
    coverage: object,
    competitor_id: str,
    field_name: str,
) -> str | None:
    if not isinstance(coverage, dict):
        return None
    competitor_coverage = coverage.get(competitor_id)
    if not isinstance(competitor_coverage, dict):
        return None
    status = competitor_coverage.get(field_name)
    return status if isinstance(status, str) and status else None


def _has_non_empty_evidence_ids(item: dict[str, object]) -> bool:
    evidence_ids = item.get("evidence_ids")
    return isinstance(evidence_ids, list) and any(
        isinstance(evidence_id, str) and evidence_id.strip()
        for evidence_id in evidence_ids
    )


def _required_string(item: dict[str, object], key: str) -> bool:
    value = item.get(key)
    return isinstance(value, str) and bool(value.strip())


def _expected_knowledge_competitors(
    *,
    knowledge: dict[str, object],
    expected_competitors: list[str] | None,
) -> list[str]:
    competitors: list[str] = []
    for competitor_id in expected_competitors or []:
        normalized = competitor_id.strip()
        if normalized and normalized not in competitors:
            competitors.append(normalized)
    if competitors:
        return competitors
    coverage = knowledge.get("coverage")
    if isinstance(coverage, dict):
        for competitor_id in coverage:
            if isinstance(competitor_id, str) and competitor_id.strip() and competitor_id not in competitors:
                competitors.append(competitor_id)
    for group_name in ("features", "pricings"):
        for competitor_id in _knowledge_items_by_competitor(knowledge.get(group_name)):
            if competitor_id not in competitors:
                competitors.append(competitor_id)
    return competitors


def rule_knowledge_schema_conformance(
    *,
    knowledge: dict[str, object],
    expected_competitors: list[str] | None = None,
    evidence_item_count: int = 0,
    min_evidence_for_schema_floor: int = 12,
    qa_rejection_count: int = 0,
    require_competitor_schema: bool = True,
) -> RuleResult:
    # `require_competitor_schema` is the archetype gate: landscape runs should not
    # be forced into a per-competitor schema floor. For comparison runs we classify
    # failures into researcher-side evidence gaps vs analyst-side extraction issues.
    malformed_failures: list[str] = []
    coverage_failures: list[str] = []
    schema_floor_failure_type: str | None = None
    schema_floor_failures: list[str] = []

    def _typed_message(*, failure_type: str, failures: list[str]) -> str:
        return f"[{failure_type}] " + "; ".join(sorted(set(failures)))

    schema_version = knowledge.get("schema_version")
    if not isinstance(schema_version, str) or not schema_version.strip():
        malformed_failures.append("schema_version missing")

    features_by_competitor = _knowledge_items_by_competitor(knowledge.get("features"))
    pricings_by_competitor = _knowledge_items_by_competitor(knowledge.get("pricings"))
    feedback_by_competitor = _knowledge_items_by_competitor(knowledge.get("feedback"))
    coverage = knowledge.get("coverage")
    competitors = _expected_knowledge_competitors(
        knowledge=knowledge,
        expected_competitors=expected_competitors,
    )
    personas = knowledge.get("personas")
    persona_count = len(personas) if isinstance(personas, list) else 0
    has_schema_items = (
        any(features_by_competitor.values())
        or any(pricings_by_competitor.values())
        or any(feedback_by_competitor.values())
        or persona_count > 0
    )
    if (
        require_competitor_schema
        and competitors
        and not has_schema_items
    ):
        if evidence_item_count <= 0:
            schema_floor_failure_type = "no_evidence"
            schema_floor_failures.append(
                "knowledge schema empty because researcher collected no evidence"
            )
        elif evidence_item_count < min_evidence_for_schema_floor:
            schema_floor_failure_type = "insufficient_evidence"
            schema_floor_failures.append(
                "knowledge schema empty with thin evidence; re-research required"
            )
        else:
            schema_floor_failure_type = "extraction_empty"
            schema_floor_failures.append(
                "knowledge schema empty despite sufficient evidence; extraction failed"
            )

    for item in [item for items in features_by_competitor.values() for item in items]:
        if not _required_string(item, "id") or not _required_string(item, "name"):
            malformed_failures.append("feature missing id/name")
        if not _has_non_empty_evidence_ids(item):
            malformed_failures.append("feature missing evidence_ids")

    for item in [item for items in pricings_by_competitor.values() for item in items]:
        if not _required_string(item, "id") or not _required_string(item, "model"):
            malformed_failures.append("pricing missing id/model")
        if not _has_non_empty_evidence_ids(item):
            malformed_failures.append("pricing missing evidence_ids")

    for item in [item for items in feedback_by_competitor.values() for item in items]:
        if (
            not _required_string(item, "id")
            or not _required_string(item, "topic")
            or not _required_string(item, "summary")
            or not _required_string(item, "sentiment")
        ):
            malformed_failures.append("feedback missing required fields")
        if not _has_non_empty_evidence_ids(item):
            malformed_failures.append("feedback missing evidence_ids")

    if isinstance(personas, list):
        for item in personas:
            if not isinstance(item, dict):
                malformed_failures.append("persona must be object")
                continue
            pain_points = item.get("pain_points")
            jobs_to_be_done = item.get("jobs_to_be_done")
            has_context = (
                isinstance(pain_points, list)
                and any(isinstance(value, str) and value.strip() for value in pain_points)
            ) or (
                isinstance(jobs_to_be_done, list)
                and any(isinstance(value, str) and value.strip() for value in jobs_to_be_done)
            )
            if not _required_string(item, "role") or not has_context:
                malformed_failures.append("persona missing role or buyer context")

    if require_competitor_schema and has_schema_items:
        for competitor_id in competitors:
            feature_count = len(features_by_competitor.get(competitor_id, []))
            feature_status = _coverage_status(
                coverage=coverage,
                competitor_id=competitor_id,
                field_name="feature",
            )
            if feature_count < 3 and feature_status not in _HONEST_INCOMPLETE_COVERAGE:
                coverage_failures.append(
                    f"{competitor_id} feature coverage incomplete without honest coverage status"
                )

            pricings = pricings_by_competitor.get(competitor_id, [])
            pricing_status = _coverage_status(
                coverage=coverage,
                competitor_id=competitor_id,
                field_name="pricing",
            )
            if not pricings and pricing_status not in _HONEST_INCOMPLETE_COVERAGE:
                coverage_failures.append(
                    f"{competitor_id} pricing missing without honest coverage status"
                )
            feedbacks = feedback_by_competitor.get(competitor_id, [])
            feedback_status = _coverage_status(
                coverage=coverage,
                competitor_id=competitor_id,
                field_name="feedback",
            )
            if feedback_status is None:
                continue
            if not feedbacks and feedback_status not in _HONEST_INCOMPLETE_COVERAGE:
                coverage_failures.append(
                    f"{competitor_id} feedback missing without honest coverage status"
                )

    if malformed_failures:
        message = "Knowledge schema is malformed: " + _typed_message(
            failure_type="malformed_fields",
            failures=malformed_failures,
        )
        if coverage_failures:
            message += "; " + _typed_message(
                failure_type="dishonest_coverage",
                failures=coverage_failures,
            )
        return RuleResult(
            rule_id="rule_knowledge_schema_conformance",
            passed=False,
            severity="blocking",
            reject_to="analyst",
            message=message,
        )
    if coverage_failures:
        return RuleResult(
            rule_id="rule_knowledge_schema_conformance",
            passed=False,
            severity="blocking",
            reject_to="analyst",
            message="Knowledge schema has dishonest coverage: "
            + _typed_message(
                failure_type="dishonest_coverage",
                failures=coverage_failures,
            ),
        )

    if schema_floor_failure_type in {"no_evidence", "insufficient_evidence"}:
        return RuleResult(
            rule_id="rule_knowledge_schema_conformance",
            passed=False,
            severity="blocking",
            reject_to="researcher",
            message="Knowledge schema lacks evidence: "
            + _typed_message(
                failure_type=schema_floor_failure_type,
                failures=schema_floor_failures,
            ),
        )

    if schema_floor_failure_type == "extraction_empty" and qa_rejection_count == 0:
        return RuleResult(
            rule_id="rule_knowledge_schema_conformance",
            passed=False,
            severity="blocking",
            reject_to="analyst",
            message="Knowledge schema extraction failed: "
            + _typed_message(
                failure_type="extraction_empty",
                failures=schema_floor_failures,
            ),
        )

    if schema_floor_failure_type == "extraction_empty":
        return RuleResult(
            rule_id="rule_knowledge_schema_conformance",
            passed=False,
            severity="warning",
            reject_to="analyst",
            message="Knowledge schema still empty after analyst retry; accepted with warning: "
            + _typed_message(
                failure_type="extraction_empty_retry",
                failures=schema_floor_failures,
            ),
        )

    return RuleResult(
        rule_id="rule_knowledge_schema_conformance",
        passed=True,
        severity="blocking",
        reject_to="analyst",
        message="Knowledge schema conforms to feature/pricing/persona minimums.",
    )


def evaluate_fast_path_rules(
    *,
    content_markdown: str,
    content_json: dict[str, object],
    evidence_items: list[EvidenceRecord],
    allowed_evidence_ids: set[str],
    report_depth: Literal["quick", "deep"] = "quick",
    target_sections: list[str] | None = None,
    market_scope: str | None = None,
    response_language: str | None = None,
    knowledge: dict[str, object] | None = None,
    analysis_archetype: str = "comparison",
    profile_competitors: list[str] | None = None,
) -> list[RuleResult]:
    effective_knowledge = knowledge if isinstance(knowledge, dict) else {}
    rule_results = [
        rule_report_must_have_markdown_content(content_markdown),
        rule_report_template_id_present(content_json),
        rule_report_must_have_at_least_one_section(content_json),
        rule_report_section_count_in_bounds(content_json),
        rule_writer_sections_must_have_content(content_json),
        rule_writer_no_placeholder_scaffolding(content_json),
        rule_writer_must_cite_evidence(
            content_json=content_json,
            allowed_evidence_ids=allowed_evidence_ids,
        ),
        rule_writer_no_fallback_mode(content_json),
        rule_report_language_consistency(
            content_json=content_json,
            response_language=response_language,
        ),
        rule_landscape_no_legacy_workbench_sections(
            content_json=content_json,
            content_markdown=content_markdown,
            analysis_archetype=analysis_archetype,
        ),
        rule_landscape_core_commercial_sections_present(
            content_json=content_json,
            analysis_archetype=analysis_archetype,
        ),
        rule_evidence_must_be_desensitized(evidence_items),
        rule_buyer_critical_sections_need_official_source(
            content_json=content_json,
            evidence_items=evidence_items,
        ),
        rule_locale_mismatch(
            market_scope=market_scope,
            evidence_items=evidence_items,
        ),
        rule_structured_sections_present(
            content_json=content_json,
            analysis_archetype=analysis_archetype,
        ),
        rule_triplet_coverage_for_profile_competitors(
            knowledge=effective_knowledge,
            profile_competitors=profile_competitors,
        ),
        rule_evidence_balance_for_profile_competitors(
            evidence_items=evidence_items,
            profile_competitors=profile_competitors,
        ),
        rule_source_quality_blocklist_share(
            evidence_items=evidence_items,
        ),
        rule_complete_coverage_has_target_evidence(
            knowledge=effective_knowledge,
        ),
    ]
    if report_depth == "deep":
        rule_results.extend(
            [
                rule_deep_report_min_char_count(content_markdown=content_markdown),
                rule_deep_report_covers_target_sections(
                    content_json=content_json,
                    target_sections=target_sections,
                ),
                rule_deep_sections_min_chars(content_json=content_json),
                rule_deep_sections_cite_evidence(content_json=content_json),
            ]
        )
    return rule_results
