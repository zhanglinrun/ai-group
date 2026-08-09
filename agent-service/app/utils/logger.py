from __future__ import annotations

from contextlib import contextmanager
import logging
import re
import sys
from typing import Iterator

import structlog

from core.config import settings
from utils.request_id import request_id_ctx

_HTTP_CLIENT_LOGGER_NAMES = (
    "openai",
    "httpx",
    "httpcore",
    "httpcore.http11",
    "httpcore.connection",
    "urllib3",
)

_NOISY_THIRD_PARTY_LOGGER_NAMES = (
    "readability",
)


class _SuppressNoisyAccessLog(logging.Filter):
    """Drop high-frequency access lines that drown out structlog JSON events."""

    _HEALTH_PATTERN = re.compile(r"\b(GET|HEAD)\s+/health\b")
    _EVENTS_PATTERN = re.compile(r"\bGET\s+/api/runs/[^/\s]+/events\b")
    _RUN_POLL_PATTERN = re.compile(
        r"\bGET\s+/api/runs/[^/\s]+(?:/(?:trace|metrics|report|conclusions|evidence))?\b"
    )
    _OPTIONS_PATTERN = re.compile(r"\bOPTIONS\s+")

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        if self._HEALTH_PATTERN.search(message):
            return False
        if self._EVENTS_PATTERN.search(message):
            return False
        if self._RUN_POLL_PATTERN.search(message):
            return False
        if self._OPTIONS_PATTERN.search(message):
            return False
        return True


def format_exception_for_log(exc: BaseException, *, max_len: int = 240) -> str:
    """Compact exception text for structured logs — strip SQL/parameter dumps."""
    orig = getattr(exc, "orig", None)
    if orig is not None:
        message = f"{type(exc).__name__}: {orig}"
    else:
        message = str(exc)
    for marker in ("[SQL:", "[parameters:", "(Background on this error"):
        if marker in message:
            message = message.split(marker, maxsplit=1)[0].strip()
    message = " ".join(message.split())
    if len(message) > max_len:
        return message[: max_len - 3] + "..."
    return message


def _configure_third_party_loggers() -> None:
    http_level = getattr(
        logging,
        settings.HTTP_CLIENT_LOG_LEVEL.upper(),
        logging.WARNING,
    )
    for logger_name in _HTTP_CLIENT_LOGGER_NAMES:
        logging.getLogger(logger_name).setLevel(http_level)
    for logger_name in _NOISY_THIRD_PARTY_LOGGER_NAMES:
        logging.getLogger(logger_name).setLevel(logging.WARNING)


def configure_logging() -> None:
    log_level = getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO)
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=log_level,
    )

    logging.getLogger("uvicorn.access").addFilter(_SuppressNoisyAccessLog())
    _configure_third_party_loggers()

    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.add_log_level,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(log_level),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)


def bind_request_id() -> None:
    structlog.contextvars.bind_contextvars(request_id=request_id_ctx.get())


def clear_request_id() -> None:
    structlog.contextvars.clear_contextvars()


@contextmanager
def bind_run(
    run_id: str,
    *,
    node: str | None = None,
    competitor_id: str | None = None,
) -> Iterator[None]:
    values: dict[str, str] = {"run_id": run_id}
    if node is not None:
        values["node"] = node
    if competitor_id is not None:
        values["competitor_id"] = competitor_id
    with structlog.contextvars.bound_contextvars(**values):
        yield


@contextmanager
def bind_step(step_id: str) -> Iterator[None]:
    with structlog.contextvars.bound_contextvars(step_id=step_id):
        yield
