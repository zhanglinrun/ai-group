from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
import inspect
from typing import Any

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from sqlalchemy import select, update

from agents.graph import compile_graph
from core.config import settings
from core.nacos_discovery import NacosRegistration
from core.tiers import resolve_tier_profile
from db.engine import dispose_engine, get_session_factory, init_engine
from exceptions.base import APIException
from models.run import Run
from router import health_rt, run_rt, skill_rt
from service.billing_settlement import settle_run_ids, start_loop as start_billing_settlement_loop
from service.event_bus import EventBus, RunEventType, emit_run_event, set_event_bus
from service.skill_store import get_skill_store
from service.watchlist.refresher import WatchlistRefresher
from utils.logger import bind_request_id, clear_request_id, configure_logging, get_logger
from utils.request_id import new_request_id, request_id_ctx

configure_logging()
log = get_logger("app_main")


class UTF8JSONResponse(JSONResponse):
    media_type = "application/json; charset=utf-8"


async def _sweep_orphan_running_runs() -> list[str]:
    """Reconcile runs left as `running` after a previous service crash.

    Without this, a server restart silently abandons every in-flight task —
    asyncio is gone, the LangGraph checkpoint may be mid-step, but `runs.status`
    still says "running" and the UI polls forever. We mark them all as failed
    so the user sees an actionable terminal state and can re-submit.
    """
    session_factory = get_session_factory()
    grace_seconds = settings.ORPHAN_RUN_SWEEP_GRACE_SECONDS
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=grace_seconds)
    async with session_factory() as session:
        result = await session.execute(
            select(Run.run_id).where(Run.status == "running", Run.started_at < cutoff)
        )
        orphan_ids = [row[0] for row in result.all()]
        if not orphan_ids:
            return []
        await session.execute(
            update(Run)
            .where(Run.run_id.in_(orphan_ids))
            .values(status="failed", finished_at=datetime.now(timezone.utc))
        )
        await session.commit()
    log.warning(
        "startup.orphan_runs.swept",
        run_count=len(orphan_ids),
        grace_seconds=grace_seconds,
        run_ids=orphan_ids[:10],
    )
    # Emit RUN_FINISH so any client that reconnects after restart immediately
    # sees the row flip terminal without needing a hard refresh.
    for run_id in orphan_ids:
        await emit_run_event(
            run_id=run_id,
            event_type=RunEventType.RUN_FINISH,
            payload={
                "run_id": run_id,
                "status": "failed",
                "error_type": "ServerRestart",
                "error_message": "服务重启时此任务正在执行，已标记为失败。请重新发起分析。",
            },
        )
    return orphan_ids


@asynccontextmanager
async def lifespan(app: FastAPI):
    init_engine()
    get_skill_store().scan()
    background_tasks: set[asyncio.Task[Any]] = set()
    app.state.background_tasks = background_tasks
    event_bus = EventBus(dsn=settings.DATABASE_URL_SYNC)
    await event_bus.start()
    app.state.event_bus = event_bus
    set_event_bus(event_bus)
    orphan_ids = await _sweep_orphan_running_runs()
    if orphan_ids:
        await settle_run_ids(orphan_ids, terminal_status="failed")
    checkpoint_dsn = settings.LANGGRAPH_CHECKPOINT_DSN
    if checkpoint_dsn is None:
        raise RuntimeError("LANGGRAPH_CHECKPOINT_DSN must be configured before service startup.")
    try:
        async with AsyncPostgresSaver.from_conn_string(checkpoint_dsn) as checkpointer:
            setup_result = checkpointer.setup()
            if inspect.isawaitable(setup_result):
                await setup_result

            app.state.checkpointer = checkpointer
            app.state.compiled_graph = compile_graph(checkpointer=checkpointer)

            def _make_watchlist_run_launcher(compiled_graph: Any, app_state: Any) -> Any:
                async def _launcher(run_id: str, initial_state: dict[str, object]) -> None:
                    bt: set[asyncio.Task[Any]] = getattr(app_state, "background_tasks", set())
                    profile = resolve_tier_profile("quick")
                    from router.run_rt import _execute_run_graph  # noqa: PLC0415
                    task = asyncio.create_task(
                        _execute_run_graph(
                            run_id=run_id,
                            graph=compiled_graph,
                            initial_state=initial_state,
                            domain_hint=None,
                            recursion_limit=profile.recursion_limit,
                            background_tasks=bt,
                        ),
                        name=f"run_graph_{run_id}",
                    )
                    bt.add(task)
                    task.add_done_callback(bt.discard)
                    await task
                return _launcher

            refresher = WatchlistRefresher(
                session_factory=get_session_factory(),
                run_launcher=_make_watchlist_run_launcher(app.state.compiled_graph, app.state),
                background_tasks=background_tasks,
            )
            app.state.watchlist_refresher = refresher
            refresher_task = asyncio.create_task(refresher.start_loop(), name="watchlist_refresher")
            background_tasks.add(refresher_task)
            refresher_task.add_done_callback(background_tasks.discard)
            settlement_task = asyncio.create_task(
                start_billing_settlement_loop(),
                name="billing_settlement",
            )
            background_tasks.add(settlement_task)
            settlement_task.add_done_callback(background_tasks.discard)

            log.info("service_start", service=settings.SERVICE_NAME, environment=settings.ENVIRONMENT)
            nacos_registration = NacosRegistration(settings)
            nacos_registration.start()
            app.state.nacos_registration = nacos_registration
            yield
    finally:
        log.info("service_stop.cleanup.start")
        nacos_registration = getattr(app.state, "nacos_registration", None)
        if nacos_registration is not None:
            nacos_registration.stop()
        pending_tasks = list(background_tasks)
        for task in pending_tasks:
            task.cancel()
        if pending_tasks:
            log.info("service_stop.cleanup.background_tasks_wait", task_count=len(pending_tasks))
            await asyncio.wait(pending_tasks, timeout=1)
        log.info("service_stop.cleanup.event_bus_stop.start")
        await event_bus.stop()
        log.info("service_stop.cleanup.event_bus_stop.finish")
        set_event_bus(None)
        log.info("service_stop.cleanup.dispose_engine.start")
        await dispose_engine()
        log.info("service_stop.cleanup.dispose_engine.finish")
        log.info("service_stop", service=settings.SERVICE_NAME)


app = FastAPI(
    title="ai-group Agent API",
    description="ai-group LangGraph research and token-metering service.",
    version="1.0.0",
    lifespan=lifespan,
    default_response_class=UTF8JSONResponse,
)

cors_allow_origins = [origin.strip() for origin in settings.CORS_ALLOW_ORIGINS.split(",") if origin.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=cors_allow_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def request_context_middleware(request: Request, call_next):
    request_id = new_request_id()
    token = request_id_ctx.set(request_id)
    bind_request_id()
    try:
        response = await call_next(request)
    finally:
        clear_request_id()
        request_id_ctx.reset(token)

    response.headers["X-Request-ID"] = request_id
    return response


@app.exception_handler(APIException)
async def api_exception_handler(_: Request, exc: APIException) -> JSONResponse:
    return UTF8JSONResponse(status_code=exc.status_code, content=exc.to_dict())


@app.exception_handler(Exception)
async def unhandled_exception_handler(_: Request, exc: Exception) -> JSONResponse:
    log.exception("unhandled_exception", error=str(exc))
    return UTF8JSONResponse(
        status_code=500,
        content={
            "error_code": "INTERNAL_SERVER_ERROR",
            "message": "Unexpected error",
            "timestamp": datetime.now(timezone.utc).isoformat(),
        },
    )


app.include_router(health_rt.router)
app.include_router(run_rt.router)
app.include_router(skill_rt.router)
