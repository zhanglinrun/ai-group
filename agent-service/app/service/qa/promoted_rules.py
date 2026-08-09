from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Literal
from collections.abc import Mapping

import yaml
from pydantic import BaseModel, Field, ValidationError, model_validator

from models.evidence import EvidenceRecord
from service.qa.rules import RuleResult

RuleSeverity = Literal["blocking", "warning"]
RuleRejectTarget = Literal["supervisor", "researcher", "analyst", "writer"]


@dataclass(frozen=True, slots=True)
class ParsedPromotedRule:
    rule_id: str
    severity: RuleSeverity
    reject_to: RuleRejectTarget
    message: str
    section_id_in: list[str]
    section_title_contains: list[str]
    source_type_in: list[str]
    collected_within_days: int | None
    evidence_refs_count_gte: int | None
    section_content_min_chars: int | None


@dataclass(frozen=True, slots=True)
class ParseError:
    detail: str


@dataclass(frozen=True, slots=True)
class PromotedRuleEvaluation:
    result: RuleResult
    parse_error: str | None
    enforced: bool


class _RuleWhen(BaseModel):
    section_id_in: list[str] = Field(default_factory=list)
    section_title_contains: list[str] = Field(default_factory=list)


class _RequireHasEvidenceWith(BaseModel):
    source_type_in: list[str] = Field(default_factory=list)
    collected_within_days: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def _validate_has_operator(self) -> "_RequireHasEvidenceWith":
        if not self.source_type_in and self.collected_within_days is None:
            raise ValueError(
                "has_evidence_with must include source_type_in or collected_within_days."
            )
        return self


class _RuleRequire(BaseModel):
    has_evidence_with: _RequireHasEvidenceWith | None = None
    evidence_refs_count_gte: int | None = Field(default=None, ge=1)
    section_content_min_chars: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def _validate_non_empty(self) -> "_RuleRequire":
        if (
            self.has_evidence_with is None
            and self.evidence_refs_count_gte is None
            and self.section_content_min_chars is None
        ):
            raise ValueError("require must define at least one operator.")
        return self


class _PromotedRuleYaml(BaseModel):
    id: str
    when: _RuleWhen | None = None
    require: _RuleRequire
    severity: RuleSeverity = "blocking"
    reject_to: RuleRejectTarget = "writer"
    message: str | None = None


def _section_title(section: dict[str, object]) -> str:
    title_raw = section.get("title")
    if isinstance(title_raw, str):
        return title_raw
    return ""


def _section_markdown(section: dict[str, object]) -> str:
    content_raw = section.get("content_markdown")
    if isinstance(content_raw, str):
        return content_raw
    return ""


def _section_evidence_refs(section: dict[str, object]) -> list[str]:
    evidence_refs_raw = section.get("evidence_refs")
    if not isinstance(evidence_refs_raw, list):
        return []
    return [item for item in evidence_refs_raw if isinstance(item, str)]


def _iter_sections(content_json: dict[str, object]) -> list[dict[str, object]]:
    sections_raw = content_json.get("sections")
    if not isinstance(sections_raw, list):
        return []
    return [item for item in sections_raw if isinstance(item, dict)]


def _ensure_timezone_aware(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def parse_promoted_rule(
    *,
    rule_yaml: str,
    fallback_rule_id: str,
) -> ParsedPromotedRule | ParseError:
    try:
        loaded = yaml.safe_load(rule_yaml)
    except yaml.YAMLError as exc:
        return ParseError(detail=f"yaml_parse_error: {exc}")
    if not isinstance(loaded, dict):
        return ParseError(detail="yaml_root_must_be_object")

    loaded_with_default = dict(loaded)
    loaded_with_default.setdefault("id", fallback_rule_id)
    try:
        parsed = _PromotedRuleYaml.model_validate(loaded_with_default)
    except ValidationError as exc:
        first_error = exc.errors()[0]["msg"]
        return ParseError(detail=f"rule_schema_invalid: {first_error}")

    has_evidence_with = parsed.require.has_evidence_with
    return ParsedPromotedRule(
        rule_id=parsed.id,
        severity=parsed.severity,
        reject_to=parsed.reject_to,
        message=(
            parsed.message.strip()
            if isinstance(parsed.message, str) and parsed.message.strip()
            else f"Promoted QA rule '{parsed.id}' check failed."
        ),
        section_id_in=list(parsed.when.section_id_in)
        if parsed.when is not None
        else [],
        section_title_contains=list(parsed.when.section_title_contains)
        if parsed.when is not None
        else [],
        source_type_in=list(has_evidence_with.source_type_in)
        if has_evidence_with is not None
        else [],
        collected_within_days=(
            has_evidence_with.collected_within_days
            if has_evidence_with is not None
            else None
        ),
        evidence_refs_count_gte=parsed.require.evidence_refs_count_gte,
        section_content_min_chars=parsed.require.section_content_min_chars,
    )


def _section_has_qualified_evidence(
    *,
    section_evidence_refs: list[str],
    evidence_by_id: Mapping[str, EvidenceRecord],
    source_type_in: list[str],
    collected_within_days: int | None,
    now: datetime,
) -> bool:
    source_type_filter = set(source_type_in)
    now_aware = _ensure_timezone_aware(now)
    for evidence_id in section_evidence_refs:
        evidence_item = evidence_by_id.get(evidence_id)
        if evidence_item is None:
            continue
        if source_type_filter and evidence_item.source_type not in source_type_filter:
            continue
        if collected_within_days is not None:
            collected_at = _ensure_timezone_aware(evidence_item.collected_at)
            if now_aware - collected_at > timedelta(days=collected_within_days):
                continue
        return True
    return False


def evaluate_promoted_rule(
    *,
    parsed_rule: ParsedPromotedRule,
    content_json: dict[str, object],
    evidence_by_id: Mapping[str, EvidenceRecord],
    now: datetime,
) -> RuleResult:
    sections = _iter_sections(content_json)
    section_ids = set(parsed_rule.section_id_in)
    title_keywords = [item.casefold() for item in parsed_rule.section_title_contains]
    if section_ids:
        scoped_sections = [
            section
            for section in sections
            if isinstance(section.get("section_id"), str)
            and section.get("section_id") in section_ids
        ]
    elif title_keywords:
        scoped_sections = [
            section
            for section in sections
            if any(
                keyword in _section_title(section).casefold()
                for keyword in title_keywords
            )
        ]
    else:
        scoped_sections = sections

    if section_ids and not scoped_sections:
        return RuleResult(
            rule_id=parsed_rule.rule_id,
            passed=True,
            severity=parsed_rule.severity,
            reject_to=parsed_rule.reject_to,
            message=(
                f"Promoted QA rule '{parsed_rule.rule_id}' not triggered "
                f"because target section_id is missing (section_id_in={sorted(section_ids)})."
            ),
        )

    if title_keywords and not scoped_sections:
        return RuleResult(
            rule_id=parsed_rule.rule_id,
            passed=True,
            severity=parsed_rule.severity,
            reject_to=parsed_rule.reject_to,
            message=(
                f"Promoted QA rule '{parsed_rule.rule_id}' not triggered "
                "because target section is missing."
            ),
        )

    if not scoped_sections:
        return RuleResult(
            rule_id=parsed_rule.rule_id,
            passed=True,
            severity=parsed_rule.severity,
            reject_to=parsed_rule.reject_to,
            message=f"Promoted QA rule '{parsed_rule.rule_id}' skipped due to empty sections.",
        )

    failures: list[str] = []
    for section in scoped_sections:
        section_title = _section_title(section) or "unknown"
        section_refs = _section_evidence_refs(section)
        section_markdown = _section_markdown(section).strip()

        if (
            parsed_rule.evidence_refs_count_gte is not None
            and len(section_refs) < parsed_rule.evidence_refs_count_gte
        ):
            failures.append(
                "section "
                f"'{section_title}' evidence_refs_count={len(section_refs)} "
                f"< {parsed_rule.evidence_refs_count_gte}"
            )

        if (
            parsed_rule.section_content_min_chars is not None
            and len(section_markdown) < parsed_rule.section_content_min_chars
        ):
            failures.append(
                "section "
                f"'{section_title}' content_len={len(section_markdown)} "
                f"< {parsed_rule.section_content_min_chars}"
            )

        if parsed_rule.source_type_in or parsed_rule.collected_within_days is not None:
            has_match = _section_has_qualified_evidence(
                section_evidence_refs=section_refs,
                evidence_by_id=evidence_by_id,
                source_type_in=parsed_rule.source_type_in,
                collected_within_days=parsed_rule.collected_within_days,
                now=now,
            )
            if not has_match:
                failures.append(
                    f"section '{section_title}' missing qualified evidence match"
                )

    if failures:
        detail = "; ".join(failures)
        return RuleResult(
            rule_id=parsed_rule.rule_id,
            passed=False,
            severity=parsed_rule.severity,
            reject_to=parsed_rule.reject_to,
            message=f"{parsed_rule.message} ({detail})",
        )

    return RuleResult(
        rule_id=parsed_rule.rule_id,
        passed=True,
        severity=parsed_rule.severity,
        reject_to=parsed_rule.reject_to,
        message=f"Promoted QA rule '{parsed_rule.rule_id}' passed.",
    )


def evaluate_promoted_rule_yaml(
    *,
    promoted_rule_id: str,
    rule_yaml: str,
    content_json: dict[str, object],
    evidence_by_id: Mapping[str, EvidenceRecord],
    now: datetime,
) -> PromotedRuleEvaluation:
    parsed_or_error = parse_promoted_rule(
        rule_yaml=rule_yaml,
        fallback_rule_id=promoted_rule_id,
    )
    if isinstance(parsed_or_error, ParseError):
        return PromotedRuleEvaluation(
            result=RuleResult(
                rule_id=promoted_rule_id,
                passed=False,
                severity="blocking",
                reject_to="writer",
                message=f"parse_error: {parsed_or_error.detail}",
            ),
            parse_error=parsed_or_error.detail,
            enforced=False,
        )
    result = evaluate_promoted_rule(
        parsed_rule=parsed_or_error,
        content_json=content_json,
        evidence_by_id=evidence_by_id,
        now=now,
    )
    if result.rule_id != promoted_rule_id:
        result = RuleResult(
            rule_id=promoted_rule_id,
            passed=result.passed,
            severity=result.severity,
            reject_to=result.reject_to,
            message=result.message,
        )
    return PromotedRuleEvaluation(
        result=result,
        parse_error=None,
        enforced=True,
    )

