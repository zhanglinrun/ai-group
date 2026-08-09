from __future__ import annotations

from contextvars import ContextVar
from uuid import uuid4

request_id_ctx: ContextVar[str] = ContextVar("request_id", default="unknown")


def new_request_id() -> str:
    return f"req_{uuid4().hex}"
