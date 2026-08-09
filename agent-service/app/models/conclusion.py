from __future__ import annotations

from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Integer, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from models.base import Base


class ConclusionRecord(Base):
    __tablename__ = "conclusions"

    conclusion_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    run_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("runs.run_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    step_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("steps.step_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    section: Mapped[str] = mapped_column(String(32), nullable=False)
    claim: Mapped[str] = mapped_column(Text, nullable=False)
    confidence: Mapped[str] = mapped_column(String(16), nullable=False)
    competitor_ids: Mapped[list[str]] = mapped_column(JSONB, nullable=False, default=list)
    risk_flags: Mapped[list[str]] = mapped_column(JSONB, nullable=False, default=list)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

    evidence_links: Mapped[list[ConclusionEvidenceLink]] = relationship(
        "ConclusionEvidenceLink",
        back_populates="conclusion",
        cascade="all, delete-orphan",
    )


class ConclusionEvidenceLink(Base):
    __tablename__ = "conclusion_evidence"

    conclusion_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("conclusions.conclusion_id", ondelete="CASCADE"),
        primary_key=True,
    )
    evidence_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("evidence.id", ondelete="CASCADE"),
        primary_key=True,
    )
    relevance_rank: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
    )

    conclusion: Mapped[ConclusionRecord] = relationship(
        "ConclusionRecord",
        back_populates="evidence_links",
    )
