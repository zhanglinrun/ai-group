from __future__ import annotations

from datetime import datetime

from sqlalchemy import BigInteger, DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column

from models.base import Base


class LLMBillingAttempt(Base):
    """Durable state for one provider round-trip's quota settlement."""

    __tablename__ = "llm_billing_attempts"

    attempt_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    run_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("runs.run_id", ondelete="CASCADE"), nullable=False, index=True
    )
    reservation_id: Mapped[str | None] = mapped_column(String(64), nullable=True, unique=True, index=True)
    request_id: Mapped[str] = mapped_column(String(128), nullable=False, unique=True, index=True)
    owner_user_id: Mapped[int] = mapped_column(nullable=False, default=0, index=True)
    estimated_micro_points: Mapped[int] = mapped_column(BigInteger, nullable=False)
    actual_micro_points: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False, index=True)
    retry_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )
    settled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
