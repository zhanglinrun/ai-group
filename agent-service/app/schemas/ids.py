from __future__ import annotations

from uuid import uuid4

ID_PREFIXES: tuple[str, ...] = (
    "run_",
    "step_",
    "ev_",
    "concl_",
    "cmp_",
    "comp_",
    "feat_",
    "price_",
    "persona_",
    "feedback_",
    "knowledge_",
    "msg_",
    "decision_",
    "rejection_",
    "skill_",
    "artifact_",
    "ptask_",
    "plan_",
    "fu_",
    "watch_",
    "diff_",
)


def make_id(prefix: str) -> str:
    if prefix not in ID_PREFIXES:
        raise ValueError(f"Unsupported ID prefix: {prefix}")
    return f"{prefix}{uuid4().hex[:12]}"
