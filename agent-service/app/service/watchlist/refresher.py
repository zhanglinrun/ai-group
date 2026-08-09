from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from datetime import datetime, timedelta, timezone
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from core.defaults import DEFAULT_FOCUS_DIMENSIONS
from models.run import Run
from models.watchlist import WatchlistItem
from schemas.ids import make_id
from schemas.intake import RunIntakeDraft
from service.locale import detect_language
from utils.logger import get_logger

log = get_logger("service.watchlist.refresher")

_MAX_CONCURRENT_REFRESHES = 3


class WatchlistRefresher:
    """Polls the watchlist for overdue refresh entries and launches background Runs.

    The caller provides a `run_launcher` coroutine that handles graph execution
    for a given run_id and initial_state. After the run completes (success or
    failure), last_refreshed_at / last_run_id / next_refresh_at are updated.
    """

    def __init__(
        self,
        *,
        session_factory: Callable[[], AsyncSession],
        run_launcher: Callable[[str, dict[str, object]], Awaitable[None]],
        background_tasks: set[asyncio.Task[Any]],
    ) -> None:
        self._session_factory = session_factory
        self._run_launcher = run_launcher
        self._semaphore = asyncio.Semaphore(_MAX_CONCURRENT_REFRESHES)
        self._background_tasks = background_tasks

    async def trigger_single(self, watch_id: str) -> str:
        """Immediately trigger a refresh run for one watchlist item. Returns run_id."""
        async with self._session_factory() as session:
            item = await session.get(WatchlistItem, watch_id)
            if item is None:
                raise ValueError(f"watch_id={watch_id} not found in watchlist")
            run_id = await self._create_run_record(item, session)

        task = asyncio.create_task(
            self._execute_and_finalize(watch_id=watch_id, run_id=run_id, competitor_id=item.competitor_id),
            name=f"watchlist_finalize_{run_id}",
        )
        self._background_tasks.add(task)
        task.add_done_callback(self._background_tasks.discard)
        return run_id

    async def run_once(self) -> None:
        """Scan for overdue watchlist items and launch a refresh run for each."""
        now = datetime.now(timezone.utc)
        async with self._session_factory() as session:
            result = await session.execute(
                select(WatchlistItem).where(
                    WatchlistItem.refresh_interval_hours.is_not(None),
                    WatchlistItem.next_refresh_at <= now,
                )
            )
            items = list(result.scalars().all())

        if not items:
            return

        log.info("watchlist.refresher.run_once.found", count=len(items))
        for item in items:
            task = asyncio.create_task(
                self._trigger_item(item.watch_id),
                name=f"watchlist_refresh_trigger_{item.watch_id}",
            )
            self._background_tasks.add(task)
            task.add_done_callback(self._background_tasks.discard)

    async def start_loop(self, interval_seconds: int = 300) -> None:
        """Background polling loop. Call once from app lifespan."""
        log.info("watchlist.refresher.loop.start", interval_seconds=interval_seconds)
        while True:
            try:
                await self.run_once()
            except Exception as exc:
                log.warning("watchlist.refresher.run_once.error", error=str(exc))
            await asyncio.sleep(interval_seconds)

    async def _trigger_item(self, watch_id: str) -> None:
        async with self._semaphore:
            try:
                await self.trigger_single(watch_id)
            except Exception as exc:
                log.warning("watchlist.refresher.trigger.error", watch_id=watch_id, error=str(exc))

    async def _create_run_record(self, item: WatchlistItem, session: AsyncSession) -> str:
        run_id = make_id("run_")
        parent_run_id = item.last_run_id
        user_query = f"[竞品追踪] 自动刷新: {item.competitor_id}"
        intake_draft = RunIntakeDraft(
            user_query=user_query,
            user_role="pm",
            analysis_intent=f"追踪竞品 {item.competitor_id} 的最新动态",
            competitors_explicit=[item.competitor_id],
            competitors_discovery_mode=False,
            focus_dimensions=list(DEFAULT_FOCUS_DIMENSIONS),
            report_depth="quick",
            response_language=detect_language(item.competitor_id),
        )
        run = Run(
            run_id=run_id,
            user_query=user_query,
            domain_hint=None,
            reference_urls=[],
            status="running",
            target_roles=["pm"],
            competitors=[item.competitor_id],
            intake_draft=intake_draft.model_dump(exclude={"is_complete"}),
            parent_run_id=parent_run_id,
            seed_competitor_ids=[item.competitor_id],
        )
        session.add(run)
        item.last_run_id = run_id
        # Claim the slot now: a refresh Run can outlast the 5-min poll interval, and
        # next_refresh_at is otherwise only advanced in _finalize_watchlist (after the
        # Run finishes). Without this the same item stays `next_refresh_at <= now` and
        # the next poll fires a duplicate Run. _finalize_watchlist re-stamps it on done.
        if item.refresh_interval_hours:
            item.next_refresh_at = datetime.now(timezone.utc) + timedelta(
                hours=item.refresh_interval_hours
            )
        await session.commit()
        log.info("watchlist.refresher.run_created", watch_id=item.watch_id, run_id=run_id)
        return run_id

    async def _execute_and_finalize(
        self, *, watch_id: str, run_id: str, competitor_id: str
    ) -> None:
        initial_state: dict[str, object] = {
            "run_id": run_id,
            "domain_hint": None,
            "market_scope": None,
            "response_language": detect_language(competitor_id),
            "reference_urls": [],
            "competitors": [competitor_id],
            "discovered_competitors": [],
            "discovered_competitor_sources": {},
            "user_query": f"[竞品追踪] 自动刷新: {competitor_id}",
            "researched_competitors": [],
            "analysis_done": False,
            "report_draft_done": False,
            "replan_count": 0,
            "current_iteration": 0,
            "pending_tool_args": {},
            "qa_outcome": None,
            "qa_reject_to": None,
            "qa_rejection_count": 0,
            "pending_review_target_step_id": None,
            "qa_reasons": [],
            "status": "running",
        }
        try:
            await self._run_launcher(run_id, initial_state)
        except Exception as exc:
            log.warning(
                "watchlist.refresher.execute.error",
                watch_id=watch_id,
                run_id=run_id,
                error=str(exc),
            )
        finally:
            await self._finalize_watchlist(watch_id=watch_id, run_id=run_id)

    async def _finalize_watchlist(self, *, watch_id: str, run_id: str) -> None:
        now = datetime.now(timezone.utc)
        try:
            async with self._session_factory() as session:
                item = await session.get(WatchlistItem, watch_id)
                if item is None:
                    return
                item.last_refreshed_at = now
                item.last_run_id = run_id
                if item.refresh_interval_hours:
                    item.next_refresh_at = now + timedelta(hours=item.refresh_interval_hours)
                else:
                    item.next_refresh_at = None
                await session.commit()
            log.info(
                "watchlist.refresher.finalized",
                watch_id=watch_id,
                run_id=run_id,
            )
        except Exception as exc:
            log.warning(
                "watchlist.refresher.finalize.error",
                watch_id=watch_id,
                run_id=run_id,
                error=str(exc),
            )
