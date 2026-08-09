"""add conclusions and conclusion_evidence tables

Revision ID: 0004_add_conclusions
Revises: 0003_llm_calls_provider
Create Date: 2026-05-27 16:05:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0004_add_conclusions"
down_revision: str | None = "0003_llm_calls_provider"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "conclusions",
        sa.Column("conclusion_id", sa.String(length=64), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("step_id", sa.String(length=64), nullable=False),
        sa.Column("section", sa.String(length=32), nullable=False),
        sa.Column("claim", sa.Text(), nullable=False),
        sa.Column("confidence", sa.String(length=16), nullable=False),
        sa.Column(
            "competitor_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "risk_flags",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["run_id"],
            ["runs.run_id"],
            name=op.f("fk_conclusions_run_id_runs"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["step_id"],
            ["steps.step_id"],
            name=op.f("fk_conclusions_step_id_steps"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("conclusion_id", name=op.f("pk_conclusions")),
    )
    op.create_index(op.f("ix_conclusions_run_id"), "conclusions", ["run_id"], unique=False)
    op.create_index(op.f("ix_conclusions_step_id"), "conclusions", ["step_id"], unique=False)

    op.create_table(
        "conclusion_evidence",
        sa.Column("conclusion_id", sa.String(length=64), nullable=False),
        sa.Column("evidence_id", sa.String(length=64), nullable=False),
        sa.Column("relevance_rank", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["conclusion_id"],
            ["conclusions.conclusion_id"],
            name=op.f("fk_conclusion_evidence_conclusion_id_conclusions"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["evidence_id"],
            ["evidence.id"],
            name=op.f("fk_conclusion_evidence_evidence_id_evidence"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("conclusion_id", "evidence_id", name=op.f("pk_conclusion_evidence")),
    )
    op.create_index(
        op.f("ix_conclusion_evidence_evidence_id"),
        "conclusion_evidence",
        ["evidence_id"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index(op.f("ix_conclusion_evidence_evidence_id"), table_name="conclusion_evidence")
    op.drop_table("conclusion_evidence")
    op.drop_index(op.f("ix_conclusions_step_id"), table_name="conclusions")
    op.drop_index(op.f("ix_conclusions_run_id"), table_name="conclusions")
    op.drop_table("conclusions")
