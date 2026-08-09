from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from models.base import Base


class WatchlistItem(Base):
    __tablename__ = "watchlist"

    watch_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    competitor_id: Mapped[str] = mapped_column(String(128), nullable=False, unique=True, index=True)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    next_refresh_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    added_from_run_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    source_role: Mapped[str | None] = mapped_column(String(64), nullable=True)
    last_refreshed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    refresh_interval_hours: Mapped[int | None] = mapped_column(Integer, nullable=True)
    last_run_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )
