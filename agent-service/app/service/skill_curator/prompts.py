from __future__ import annotations

import json
from collections.abc import Sequence


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False)


SKILL_CURATOR_QA_RULE_SYSTEM_PROMPT = """You are XiongDoctor Skill Curator for QA rule candidates.
Inspect one run trace and propose only qa_rule candidates in STRICT JSON.

Output JSON schema:
{
  "candidates": [
    {
      "candidate_type": "qa_rule",
      "payload": { "any_json_object": true },
      "rationale": str,
      "confidence": "low" | "medium" | "high",
      "supporting_run_ids": list[str]
    }
  ]
}

Rules:
- Return JSON object only.
- candidates can be [].
- candidate_type must be qa_rule.
"""

SKILL_CURATOR_PROMPT_TEMPLATE_SYSTEM_PROMPT = """You are XiongDoctor Skill Curator for prompt template candidates.
Inspect one run trace and propose only prompt_template candidates in STRICT JSON.

Output JSON schema:
{
  "candidates": [
    {
      "candidate_type": "prompt_template",
      "payload": { "any_json_object": true },
      "rationale": str,
      "confidence": "low" | "medium" | "high",
      "supporting_run_ids": list[str]
    }
  ]
}

Rules:
- Return JSON object only.
- candidates can be [].
- candidate_type must be prompt_template.
"""

SKILL_CURATOR_SOURCE_ROUTING_SYSTEM_PROMPT = """You are XiongDoctor Skill Curator for source routing candidates.
Inspect one run trace and propose only source_routing candidates in STRICT JSON.

Output JSON schema:
{
  "candidates": [
    {
      "candidate_type": "source_routing",
      "payload": { "any_json_object": true },
      "rationale": str,
      "confidence": "low" | "medium" | "high",
      "supporting_run_ids": list[str]
    }
  ]
}

Rules:
- Return JSON object only.
- candidates can be [].
- candidate_type must be source_routing.
"""


def build_skill_curator_qa_rule_user_prompt(
    *,
    run_id: str,
    domain_hint: str | None,
    inferred_tags: Sequence[str],
    qa_rejection_count: int,
    qa_reasons: Sequence[str],
    supervisor_decisions: Sequence[dict[str, object]],
    evidence_source_counts: dict[str, int],
    total_evidence_count: int,
) -> str:
    return (
        "Curator context (qa_rule):\n"
        f"- run_id: {run_id}\n"
        f"- domain_hint: {domain_hint}\n"
        f"- inferred_tags: {_json(list(inferred_tags))}\n"
        f"- qa_rejection_count: {qa_rejection_count}\n"
        f"- qa_reasons: {_json(list(qa_reasons))}\n"
        f"- supervisor_decisions_tail: {_json(list(supervisor_decisions)[-8:])}\n"
        f"- evidence_source_counts: {_json(evidence_source_counts)}\n"
        f"- total_evidence_count: {total_evidence_count}\n"
    )


def build_skill_curator_qa_rule_fallback_user_prompt(
    *,
    run_id: str,
    domain_hint: str | None,
    inferred_tags: Sequence[str],
    qa_rejection_count: int,
    evidence_source_counts: dict[str, int],
    total_evidence_count: int,
) -> str:
    return (
        "Fallback curator request (qa_rule):\n"
        f"- run_id: {run_id}\n"
        f"- domain_hint: {domain_hint}\n"
        f"- inferred_tags: {_json(list(inferred_tags))}\n"
        f"- qa_rejection_count: {qa_rejection_count}\n"
        f"- evidence_source_counts: {_json(evidence_source_counts)}\n"
        f"- total_evidence_count: {total_evidence_count}\n"
        "Return minimal valid JSON with candidates list."
    )


def build_skill_curator_prompt_template_user_prompt(
    *,
    run_id: str,
    domain_hint: str | None,
    inferred_tags: Sequence[str],
    qa_rejection_count: int,
    qa_reasons: Sequence[str],
    supervisor_decisions: Sequence[dict[str, object]],
    evidence_source_counts: dict[str, int],
    total_evidence_count: int,
) -> str:
    return (
        "Curator context (prompt_template):\n"
        f"- run_id: {run_id}\n"
        f"- domain_hint: {domain_hint}\n"
        f"- inferred_tags: {_json(list(inferred_tags))}\n"
        f"- qa_rejection_count: {qa_rejection_count}\n"
        f"- qa_reasons: {_json(list(qa_reasons))}\n"
        f"- supervisor_decisions_tail: {_json(list(supervisor_decisions)[-8:])}\n"
        f"- evidence_source_counts: {_json(evidence_source_counts)}\n"
        f"- total_evidence_count: {total_evidence_count}\n"
    )


def build_skill_curator_prompt_template_fallback_user_prompt(
    *,
    run_id: str,
    domain_hint: str | None,
    inferred_tags: Sequence[str],
    qa_rejection_count: int,
    evidence_source_counts: dict[str, int],
    total_evidence_count: int,
) -> str:
    return (
        "Fallback curator request (prompt_template):\n"
        f"- run_id: {run_id}\n"
        f"- domain_hint: {domain_hint}\n"
        f"- inferred_tags: {_json(list(inferred_tags))}\n"
        f"- qa_rejection_count: {qa_rejection_count}\n"
        f"- evidence_source_counts: {_json(evidence_source_counts)}\n"
        f"- total_evidence_count: {total_evidence_count}\n"
        "Return minimal valid JSON with candidates list."
    )


def build_skill_curator_source_routing_user_prompt(
    *,
    run_id: str,
    domain_hint: str | None,
    inferred_tags: Sequence[str],
    qa_rejection_count: int,
    qa_reasons: Sequence[str],
    supervisor_decisions: Sequence[dict[str, object]],
    evidence_source_counts: dict[str, int],
    total_evidence_count: int,
) -> str:
    return (
        "Curator context (source_routing):\n"
        f"- run_id: {run_id}\n"
        f"- domain_hint: {domain_hint}\n"
        f"- inferred_tags: {_json(list(inferred_tags))}\n"
        f"- qa_rejection_count: {qa_rejection_count}\n"
        f"- qa_reasons: {_json(list(qa_reasons))}\n"
        f"- supervisor_decisions_tail: {_json(list(supervisor_decisions)[-8:])}\n"
        f"- evidence_source_counts: {_json(evidence_source_counts)}\n"
        f"- total_evidence_count: {total_evidence_count}\n"
    )


def build_skill_curator_source_routing_fallback_user_prompt(
    *,
    run_id: str,
    domain_hint: str | None,
    inferred_tags: Sequence[str],
    qa_rejection_count: int,
    evidence_source_counts: dict[str, int],
    total_evidence_count: int,
) -> str:
    return (
        "Fallback curator request (source_routing):\n"
        f"- run_id: {run_id}\n"
        f"- domain_hint: {domain_hint}\n"
        f"- inferred_tags: {_json(list(inferred_tags))}\n"
        f"- qa_rejection_count: {qa_rejection_count}\n"
        f"- evidence_source_counts: {_json(evidence_source_counts)}\n"
        f"- total_evidence_count: {total_evidence_count}\n"
        "Return minimal valid JSON with candidates list."
    )

