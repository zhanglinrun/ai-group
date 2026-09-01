from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class QAPolicy:
    rule_id: str
    rule_yaml: str


_POLICIES: tuple[QAPolicy, ...] = (
    QAPolicy(
        rule_id="evidence_must_cite_source",
        rule_yaml='''id: evidence_must_cite_source
when:
  section_id_in: ["feature", "pricing", "user_feedback"]
require:
  evidence_refs_count_gte: 1
severity: blocking
reject_to: writer
message: "Each core section must reference at least one evidence id."''',
    ),
    QAPolicy(
        rule_id="pricing_must_have_tier",
        rule_yaml='''id: pricing_must_have_tier
when:
  section_id_in: ["pricing"]
require:
  evidence_refs_count_gte: 1
  section_content_min_chars: 80
severity: blocking
reject_to: writer
message: "Pricing section should include concrete tier details or plan-level evidence."''',
    ),
)


def qa_policies() -> tuple[QAPolicy, ...]:
    return _POLICIES
