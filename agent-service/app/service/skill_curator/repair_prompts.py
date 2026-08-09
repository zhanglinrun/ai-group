from __future__ import annotations

import json
from collections.abc import Sequence


def build_skill_curator_repair_user_prompt(
    *,
    validation_errors: Sequence[str],
    allowed_types: Sequence[str],
) -> str:
    return (
        "Repair skill curator JSON to satisfy schema validation.\n"
        f"- validation_errors: {json.dumps(list(validation_errors), ensure_ascii=False)}\n"
        f"- allowed candidate_type values: {json.dumps(list(allowed_types), ensure_ascii=False)}\n\n"
        "Rules:\n"
        "- candidates must be a list of objects with candidate_type, payload, rationale.\n"
        "- Return JSON object only."
    )
