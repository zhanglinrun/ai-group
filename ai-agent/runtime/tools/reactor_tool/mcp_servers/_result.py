"""Small, deterministic result helpers shared by the local MCP servers."""

from __future__ import annotations

import json
from typing import Any


MAX_RESULT_BYTES = 8 * 1024


def bounded_json(payload: Any) -> str:
    """Serialize a tool result without ever emitting more than 8 KiB."""

    serialized = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )
    if len(serialized.encode("utf-8")) <= MAX_RESULT_BYTES:
        return serialized
    return json.dumps(
        {
            "error": {
                "code": "result_too_large",
                "message": "工具结果超过 8 KB 安全上限，请缩小查询范围。",
            },
            "ok": False,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def error_json(code: str, message: str) -> str:
    """Build a bounded, machine-readable tool error."""

    return bounded_json({"ok": False, "error": {"code": code, "message": message}})
