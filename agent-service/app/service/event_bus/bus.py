from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from enum import StrEnum
import json
from typing import Final

import psycopg
from psycopg import AsyncConnection, sql
from pydantic import BaseModel, Field, ValidationError

from utils.logger import get_logger

log = get_logger("service.event_bus")

EVENT_CHANNEL: Final[str] = "xiongdoctor_run_events"


class RunEventType(StrEnum):
    STEP_START = "step.start"
    STEP_FINISH = "step.finish"
    SUPERVISOR_DECISION = "supervisor.decision"
    QA_OUTCOME = "qa.outcome"
    CURATOR_START = "curator.start"
    CURATOR_SKIPPED = "curator.skipped"
    CURATOR_FINISH = "curator.finish"
    RUN_FINISH = "run.finish"
    # --- Phase 1+ Agent-native intake + plan-then-execute + live run (emitters TBD) ---
    INTAKE_CLARIFY_REQUEST = "intake.clarify_request"
    INTAKE_USER_REPLY = "intake.user_reply"
    INTAKE_COMPLETE = "intake.complete"
    PLAN_PUBLISHED = "plan.published"
    PLAN_CONFIRMED = "plan.confirmed"
    PLAN_RECONCILED = "plan.reconciled"
    PLAN_REVISED = "plan.revised"
    PLAN_TASK_START = "plan.task.start"
    PLAN_TASK_FINISH = "plan.task.finish"
    TOOL_START = "tool.start"
    TOOL_FINISH = "tool.finish"
    EVIDENCE_COLLECTED = "evidence.collected"
    FOLLOWUP_RECEIVED = "followup.received"
    HEARTBEAT = "heartbeat"


class RunEvent(BaseModel):
    run_id: str
    event_type: RunEventType
    step_id: str | None = None
    payload: dict[str, object] = Field(default_factory=dict)
    emitted_at: str = Field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


def normalize_postgres_dsn(raw_dsn: str) -> str:
    if raw_dsn.startswith("postgresql://"):
        return raw_dsn
    return (
        raw_dsn.replace("postgresql+psycopg://", "postgresql://")
        .replace("postgresql+psycopg2://", "postgresql://")
        .replace("postgresql+asyncpg://", "postgresql://")
    )


class EventBus:
    def __init__(
        self,
        *,
        dsn: str,
        channel: str = EVENT_CHANNEL,
        max_queue_size: int = 256,
    ) -> None:
        self._dsn = normalize_postgres_dsn(dsn)
        self._channel = channel
        self._max_queue_size = max_queue_size
        self._subscriber_lock = asyncio.Lock()
        self._subscribers: dict[str, set[asyncio.Queue[RunEvent]]] = {}
        self._listener_conn: AsyncConnection[tuple[object, ...]] | None = None
        self._listener_task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()

    async def start(self) -> None:
        if self._listener_task is not None and not self._listener_task.done():
            return
        self._stop_event.clear()
        try:
            conn = await AsyncConnection.connect(self._dsn, autocommit=True)
            await conn.execute(
                sql.SQL("LISTEN {}").format(sql.Identifier(self._channel))
            )
        except psycopg.Error as exc:
            raise RuntimeError(f"event bus startup failed: {exc}") from exc
        self._listener_conn = conn
        self._listener_task = asyncio.create_task(
            self._listen_loop(),
            name=f"event_bus_listener_{self._channel}",
        )

    async def stop(self) -> None:
        self._stop_event.set()
        listener_task = self._listener_task
        self._listener_task = None

        listener_conn = self._listener_conn
        self._listener_conn = None
        if listener_task is not None:
            listener_task.cancel()

        if listener_task is not None:
            try:
                await asyncio.wait_for(listener_task, timeout=1.0)
            except (asyncio.CancelledError, TimeoutError):
                pass

        if listener_conn is not None:
            cancel = getattr(listener_conn, "cancel", None)
            if callable(cancel):
                try:
                    cancel()
                except psycopg.Error:
                    pass
            try:
                await asyncio.wait_for(listener_conn.close(), timeout=1.0)
            except (psycopg.Error, TimeoutError):
                pass

    async def publish(self, event: RunEvent) -> None:
        payload_text = json.dumps(event.model_dump(mode="json"), ensure_ascii=False)
        try:
            async with await AsyncConnection.connect(self._dsn, autocommit=True) as conn:
                await conn.execute(
                    "SELECT pg_notify(%s, %s)",
                    (self._channel, payload_text),
                )
        except psycopg.Error as exc:
            raise RuntimeError(f"event bus publish failed: {exc}") from exc

    @asynccontextmanager
    async def subscribe(self, run_id: str) -> AsyncIterator[asyncio.Queue[RunEvent]]:
        queue: asyncio.Queue[RunEvent] = asyncio.Queue(maxsize=self._max_queue_size)
        async with self._subscriber_lock:
            self._subscribers.setdefault(run_id, set()).add(queue)
        try:
            yield queue
        finally:
            async with self._subscriber_lock:
                queues = self._subscribers.get(run_id)
                if queues is not None:
                    queues.discard(queue)
                    if not queues:
                        self._subscribers.pop(run_id, None)

    async def _listen_loop(self) -> None:
        conn = self._listener_conn
        if conn is None:
            return
        try:
            while not self._stop_event.is_set():
                try:
                    async for notify in conn.notifies(timeout=1.0, stop_after=1):
                        event = self._parse_notify_payload(notify.payload)
                        if event is None:
                            continue
                        await self._fan_out(event)
                except psycopg.Error as exc:
                    if self._stop_event.is_set():
                        return
                    log.info(
                        "event_bus.listener.error",
                        channel=self._channel,
                        error=str(exc)[:500],
                    )
                    return
        except asyncio.CancelledError:
            return

    def _parse_notify_payload(self, payload: str) -> RunEvent | None:
        try:
            loaded = json.loads(payload)
        except json.JSONDecodeError:
            log.info(
                "event_bus.payload.invalid_json",
                channel=self._channel,
            )
            return None

        try:
            return RunEvent.model_validate(loaded)
        except ValidationError:
            log.info(
                "event_bus.payload.invalid_schema",
                channel=self._channel,
            )
            return None

    async def _fan_out(self, event: RunEvent) -> None:
        async with self._subscriber_lock:
            queues = list(self._subscribers.get(event.run_id, set()))
        for queue in queues:
            if queue.full():
                try:
                    queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            try:
                queue.put_nowait(event)
            except asyncio.QueueFull:
                continue


_global_event_bus: EventBus | None = None


def set_event_bus(event_bus: EventBus | None) -> None:
    global _global_event_bus
    _global_event_bus = event_bus


def get_event_bus() -> EventBus | None:
    return _global_event_bus


async def emit_run_event(
    *,
    run_id: str,
    event_type: RunEventType,
    step_id: str | None = None,
    payload: dict[str, object] | None = None,
) -> None:
    event_bus = get_event_bus()
    if event_bus is None:
        return
    try:
        await event_bus.publish(
            RunEvent(
                run_id=run_id,
                event_type=event_type,
                step_id=step_id,
                payload=payload or {},
            )
        )
    except RuntimeError as exc:
        log.info(
            "event_bus.publish.failed",
            run_id=run_id,
            event_type=event_type.value,
            error=str(exc)[:500],
        )
