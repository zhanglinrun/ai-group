from __future__ import annotations

import asyncio
from collections import deque
from collections.abc import AsyncIterator, Awaitable
import json
from typing import Any

import pytest

from service.event_bus import EventBus, RunEvent, RunEventType


class _FakeNotify:
    def __init__(self, payload: str) -> None:
        self.payload = payload


class _FakeNotifies:
    def __init__(self, events: list[str], *, stop_event: asyncio.Event) -> None:
        self._events = deque(events)
        self._stop_event = stop_event

    async def __aiter__(self) -> AsyncIterator[_FakeNotify]:
        while self._events:
            yield _FakeNotify(self._events.popleft())
        if not self._stop_event.is_set():
            await asyncio.sleep(0.02)
        return


class _FakeConn:
    def __init__(
        self,
        *,
        notifies_events: list[str] | None = None,
        stop_event: asyncio.Event | None = None,
    ) -> None:
        self.executed: list[tuple[str, tuple[Any, ...] | None]] = []
        self.closed = False
        self._notifies_events = notifies_events or []
        self._stop_event = stop_event or asyncio.Event()

    async def execute(self, query: str, params: tuple[Any, ...] | None = None) -> None:
        self.executed.append((query, params))

    def notifies(
        self,
        *,
        timeout: float | None = None,
        stop_after: int | None = None,
    ) -> _FakeNotifies:
        del timeout, stop_after
        return _FakeNotifies(self._notifies_events, stop_event=self._stop_event)

    async def close(self) -> None:
        self.closed = True
        self._stop_event.set()

    def cancel(self) -> None:
        self._stop_event.set()


class _FakeConnectCtx:
    def __init__(self, conn: _FakeConn) -> None:
        self._conn = conn

    async def __aenter__(self) -> _FakeConn:
        return self._conn

    async def __aexit__(self, exc_type, exc, tb) -> None:
        del exc_type, exc, tb
        return None


@pytest.mark.asyncio
async def test_event_bus_publish_calls_pg_notify(monkeypatch: pytest.MonkeyPatch) -> None:
    publish_conn = _FakeConn()

    async def _fake_connect(*args: Any, **kwargs: Any) -> _FakeConnectCtx:
        del args, kwargs
        return _FakeConnectCtx(publish_conn)

    monkeypatch.setattr(
        "service.event_bus.bus.AsyncConnection.connect",
        _fake_connect,
    )
    bus = EventBus(dsn="postgresql://fake")
    await bus.publish(
        RunEvent(
            run_id="run_publish",
            event_type=RunEventType.RUN_FINISH,
            payload={"status": "completed"},
        )
    )

    assert publish_conn.executed
    query, params = publish_conn.executed[0]
    assert query == "SELECT pg_notify(%s, %s)"
    assert isinstance(params, tuple)
    assert params[0] == "xiongdoctor_run_events"
    assert isinstance(params[1], str)
    decoded = json.loads(params[1])
    assert decoded["run_id"] == "run_publish"
    assert decoded["event_type"] == "run.finish"


@pytest.mark.asyncio
async def test_event_bus_listener_fan_out_by_run_id(monkeypatch: pytest.MonkeyPatch) -> None:
    stop_event = asyncio.Event()
    listener_conn = _FakeConn(
        notifies_events=[
            json.dumps(
                {
                    "run_id": "run_a",
                    "event_type": "step.start",
                    "step_id": "step_1",
                    "payload": {"agent_name": "supervisor"},
                    "emitted_at": "2026-05-29T00:00:00+00:00",
                }
            ),
            json.dumps(
                {
                    "run_id": "run_b",
                    "event_type": "qa.outcome",
                    "step_id": "step_2",
                    "payload": {"qa_outcome": "approved"},
                    "emitted_at": "2026-05-29T00:00:01+00:00",
                }
            ),
        ],
        stop_event=stop_event,
    )

    async def _fake_connect(*args: Any, **kwargs: Any) -> _FakeConn:
        del args, kwargs
        return listener_conn

    monkeypatch.setattr(
        "service.event_bus.bus.AsyncConnection.connect",
        _fake_connect,
    )

    bus = EventBus(dsn="postgresql://fake")
    await bus.start()
    try:
        async with bus.subscribe("run_a") as queue_a, bus.subscribe("run_b") as queue_b:
            event_a = await asyncio.wait_for(queue_a.get(), timeout=1.0)
            event_b = await asyncio.wait_for(queue_b.get(), timeout=1.0)
            assert event_a.run_id == "run_a"
            assert event_a.event_type == RunEventType.STEP_START
            assert event_b.run_id == "run_b"
            assert event_b.event_type == RunEventType.QA_OUTCOME
    finally:
        await bus.stop()

    assert listener_conn.closed is True


@pytest.mark.asyncio
async def test_event_bus_queue_drops_oldest_when_full() -> None:
    bus = EventBus(dsn="postgresql://fake", max_queue_size=1)
    queue: asyncio.Queue[RunEvent] = asyncio.Queue(maxsize=1)
    async with bus._subscriber_lock:
        bus._subscribers.setdefault("run_drop", set()).add(queue)

    try:
        await bus._fan_out(
            RunEvent(
                run_id="run_drop",
                event_type=RunEventType.STEP_START,
                step_id="step_old",
            )
        )
        await bus._fan_out(
            RunEvent(
                run_id="run_drop",
                event_type=RunEventType.STEP_FINISH,
                step_id="step_new",
            )
        )
        assert queue.qsize() == 1
        event = queue.get_nowait()
        assert event.step_id == "step_new"
    finally:
        async with bus._subscriber_lock:
            bus._subscribers.clear()


@pytest.mark.asyncio
async def test_subscribe_receives_event_payload_fields() -> None:
    bus = EventBus(dsn="postgresql://fake")
    async with bus.subscribe("run_payload") as queue:
        await bus._fan_out(
            RunEvent(
                run_id="run_payload",
                event_type=RunEventType.SUPERVISOR_DECISION,
                step_id="step_decision_1",
                payload={"chosen_tool": "Analyze", "iteration": 2},
            )
        )
        event = await asyncio.wait_for(queue.get(), timeout=0.2)
    assert event.run_id == "run_payload"
    assert event.step_id == "step_decision_1"
    assert event.event_type == RunEventType.SUPERVISOR_DECISION
    assert event.payload["chosen_tool"] == "Analyze"
