from __future__ import annotations

from types import SimpleNamespace

import pytest

from service import billing_settlement
from service.billing_settlement import (
    is_unsettled_billing,
    settle_if_needed_for_delete,
    settle_run_billing,
    settle_run_ids,
    sweep_unsettled_runs,
)


class _ScalarResult:
    def __init__(self, rows: list[object]) -> None:
        self._rows = rows

    def scalars(self) -> _ScalarResult:
        return self

    def all(self) -> list[object]:
        return list(self._rows)


class _FakeSession:
    def __init__(self, run: object | None, llm_rows: list[object] | None = None) -> None:
        self.run = run
        self.llm_rows = llm_rows or []
        self.committed = False

    async def get(self, _model: object, run_id: str) -> object | None:
        if self.run is None:
            return None
        if getattr(self.run, "run_id", None) != run_id:
            return None
        return self.run

    async def execute(self, _stmt: object) -> _ScalarResult:
        return _ScalarResult(self.llm_rows)

    async def commit(self) -> None:
        self.committed = True

    async def __aenter__(self) -> _FakeSession:
        return self

    async def __aexit__(self, *_exc: object) -> bool:
        return False


def _session_factory(session: _FakeSession):
    return lambda: session


def _run(**overrides: object) -> SimpleNamespace:
    values: dict[str, object] = {
        "run_id": "run_1",
        "status": "failed",
        "billing_status": "RESERVED",
        "reservation_id": "frz_1",
        "reserved_micro_points": 5000,
        "consumed_micro_points": 0,
        "owner_user_id": 42,
        "billing_error": None,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _llm_row(**overrides: object) -> SimpleNamespace:
    values: dict[str, object] = {
        "prompt_hash": "real",
        "charged_micro_points": 35,
        "prompt_tokens": 1,
        "completion_tokens": 1,
        "error": None,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


@pytest.mark.asyncio
async def test_settle_skips_already_settled(monkeypatch: pytest.MonkeyPatch) -> None:
    run = _run(billing_status="SETTLED")
    session = _FakeSession(run)
    monkeypatch.setattr(billing_settlement, "get_session_factory", lambda: _session_factory(session))
    confirms: list[int] = []

    async def _confirm(_reservation: object, *, actual_micro_points: int, trace_id: str) -> None:
        confirms.append(actual_micro_points)

    monkeypatch.setattr(billing_settlement.quota_client, "confirm", _confirm)
    assert await settle_run_billing(run_id="run_1", terminal_status="failed") == "SETTLED"
    assert confirms == []
    assert session.committed is False


@pytest.mark.asyncio
async def test_missing_usage_stays_pending_without_confirm(monkeypatch: pytest.MonkeyPatch) -> None:
    run = _run()
    session = _FakeSession(run, [_llm_row(prompt_tokens=None, completion_tokens=None)])
    monkeypatch.setattr(billing_settlement, "get_session_factory", lambda: _session_factory(session))
    confirms: list[int] = []

    async def _confirm(_reservation: object, *, actual_micro_points: int, trace_id: str) -> None:
        confirms.append(actual_micro_points)

    monkeypatch.setattr(billing_settlement.quota_client, "confirm", _confirm)
    assert await settle_run_billing(run_id="run_1", terminal_status="failed") == "PENDING_RECONCILIATION"
    assert run.billing_status == "PENDING_RECONCILIATION"
    assert confirms == []
    assert session.committed is True


@pytest.mark.asyncio
async def test_confirm_success_marks_settled(monkeypatch: pytest.MonkeyPatch) -> None:
    run = _run()
    session = _FakeSession(run, [_llm_row()])
    monkeypatch.setattr(billing_settlement, "get_session_factory", lambda: _session_factory(session))

    async def _confirm(_reservation: object, *, actual_micro_points: int, trace_id: str) -> None:
        assert actual_micro_points == 35
        assert trace_id == "run_1"

    monkeypatch.setattr(billing_settlement.quota_client, "confirm", _confirm)
    assert await settle_run_billing(run_id="run_1", terminal_status="failed") == "SETTLED"
    assert run.billing_status == "SETTLED"
    assert run.consumed_micro_points == 35


@pytest.mark.asyncio
async def test_confirm_failure_stays_pending(monkeypatch: pytest.MonkeyPatch) -> None:
    run = _run()
    session = _FakeSession(run, [_llm_row()])
    monkeypatch.setattr(billing_settlement, "get_session_factory", lambda: _session_factory(session))

    async def _confirm(_reservation: object, *, actual_micro_points: int, trace_id: str) -> None:
        raise RuntimeError("member down")

    monkeypatch.setattr(billing_settlement.quota_client, "confirm", _confirm)
    assert await settle_run_billing(run_id="run_1", terminal_status="failed") == "PENDING_RECONCILIATION"
    assert "member down" in (run.billing_error or "")


@pytest.mark.asyncio
async def test_sweep_settles_unsettled_terminal_and_skips_running(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    settled: list[tuple[str, str]] = []

    async def _list(*, limit: int) -> list[tuple[str, str]]:
        assert limit == 50
        return [("run_a", "failed"), ("run_b", "completed")]

    async def _settle(*, run_id: str, terminal_status: str) -> str:
        settled.append((run_id, terminal_status))
        return "SETTLED"

    monkeypatch.setattr(billing_settlement, "list_unsettled_terminal_runs", _list)
    monkeypatch.setattr(billing_settlement, "settle_run_billing", _settle)
    assert await sweep_unsettled_runs() == 2
    assert settled == [("run_a", "failed"), ("run_b", "completed")]


@pytest.mark.asyncio
async def test_running_unsettled_is_not_settled_on_delete(monkeypatch: pytest.MonkeyPatch) -> None:
    called = False

    async def _settle(*, run_id: str, terminal_status: str) -> str:
        nonlocal called
        called = True
        return "SETTLED"

    monkeypatch.setattr(billing_settlement, "settle_run_billing", _settle)
    status = await settle_if_needed_for_delete(
        run_id="run_1",
        status="running",
        billing_status="RESERVED",
    )
    assert status == "RESERVED"
    assert called is False
    assert is_unsettled_billing(status) is True


@pytest.mark.asyncio
async def test_failed_unsettled_is_settled_before_delete(monkeypatch: pytest.MonkeyPatch) -> None:
    async def _settle(*, run_id: str, terminal_status: str) -> str:
        assert run_id == "run_1"
        assert terminal_status == "failed"
        return "SETTLED"

    monkeypatch.setattr(billing_settlement, "settle_run_billing", _settle)
    status = await settle_if_needed_for_delete(
        run_id="run_1",
        status="failed",
        billing_status="PENDING_RECONCILIATION",
    )
    assert status == "SETTLED"
    assert is_unsettled_billing(status) is False


@pytest.mark.asyncio
async def test_orphan_ids_are_settled_as_failed(monkeypatch: pytest.MonkeyPatch) -> None:
    settled: list[tuple[str, str]] = []

    async def _settle(*, run_id: str, terminal_status: str) -> str:
        settled.append((run_id, terminal_status))
        return "SETTLED"

    monkeypatch.setattr(billing_settlement, "settle_run_billing", _settle)
    await settle_run_ids(["orphan_1", "orphan_2"], terminal_status="failed")
    assert settled == [("orphan_1", "failed"), ("orphan_2", "failed")]
