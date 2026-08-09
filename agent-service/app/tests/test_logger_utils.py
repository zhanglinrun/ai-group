from __future__ import annotations

import logging

import pytest
import structlog

from utils.logger import (
    _SuppressNoisyAccessLog,
    configure_logging,
    format_exception_for_log,
)


def test_format_exception_for_log_strips_sql_dump() -> None:
    class _FakeOrig(Exception):
        pass

    class _FakeDbError(Exception):
        def __init__(self) -> None:
            self.orig = _FakeOrig('invalid byte sequence for encoding "UTF8": 0x00')

        def __str__(self) -> str:
            return (
                '(_FakeDbError) invalid byte sequence [SQL: INSERT INTO evidence VALUES (...)] '
                "[parameters: ('ev_1', 'run_x', ...)]"
            )

    message = format_exception_for_log(_FakeDbError())
    assert "INSERT INTO evidence" not in message
    assert "parameters:" not in message
    assert "0x00" in message


def test_suppress_noisy_access_log_filters_run_polling() -> None:
    filt = _SuppressNoisyAccessLog()
    suppressed = [
        'INFO: 127.0.0.1:1 - "GET /api/runs/run_abc123 HTTP/1.1" 200 OK',
        'INFO: 127.0.0.1:1 - "GET /api/runs/run_abc123/trace HTTP/1.1" 200 OK',
        'INFO: 127.0.0.1:1 - "GET /api/runs/run_abc123/metrics HTTP/1.1" 200 OK',
        'INFO: 127.0.0.1:1 - "GET /api/runs/run_abc123/events HTTP/1.1" 200 OK',
        'INFO: 127.0.0.1:1 - "GET /health HTTP/1.1" 200 OK',
    ]
    for line in suppressed:
        record = logging.LogRecord(
            name="uvicorn.access",
            level=logging.INFO,
            pathname=__file__,
            lineno=1,
            msg=line,
            args=(),
            exc_info=None,
        )
        assert filt.filter(record) is False


def test_suppress_noisy_access_log_keeps_mutating_routes() -> None:
    filt = _SuppressNoisyAccessLog()
    kept = [
        'INFO: 127.0.0.1:1 - "POST /api/runs/intake HTTP/1.1" 202 Accepted',
        'INFO: 127.0.0.1:1 - "POST /api/runs/run_abc123/intake/reply HTTP/1.1" 200 OK',
        'INFO: 127.0.0.1:1 - "POST /api/runs/run_abc123/plan/confirm HTTP/1.1" 200 OK',
    ]
    for line in kept:
        record = logging.LogRecord(
            name="uvicorn.access",
            level=logging.INFO,
            pathname=__file__,
            lineno=1,
            msg=line,
            args=(),
            exc_info=None,
        )
        assert filt.filter(record) is True


def test_configure_logging_suppresses_noisy_third_party_loggers() -> None:
    configure_logging()

    assert logging.getLogger("urllib3").getEffectiveLevel() >= logging.WARNING
    assert logging.getLogger("urllib3.connectionpool").getEffectiveLevel() >= logging.WARNING
    assert logging.getLogger("readability").getEffectiveLevel() >= logging.WARNING
    assert logging.getLogger("readability.readability").getEffectiveLevel() >= logging.WARNING


@pytest.mark.asyncio
async def test_json_mode_fallback_logs_once_per_run_and_model(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    from service.llm import providers as providers_module
    from service.llm.providers import _log_json_mode_fallback
    from utils.logger import configure_logging

    configure_logging()
    providers_module._json_mode_fallback_keys.clear()
    structlog.contextvars.clear_contextvars()
    structlog.contextvars.bind_contextvars(run_id="run_dedupe_test")

    _log_json_mode_fallback(
        provider="doubao",
        model="ep-test",
        http_status=400,
        error_preview="json_object not supported",
    )
    _log_json_mode_fallback(
        provider="doubao",
        model="ep-test",
        http_status=400,
        error_preview="json_object not supported",
    )

    logged = capsys.readouterr().out
    info_fallback_lines = [
        line
        for line in logged.splitlines()
        if "llm.call.json_mode_fallback" in line and '"level": "info"' in line
    ]
    assert len(info_fallback_lines) == 1
