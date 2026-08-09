"""add comparison cells

Revision ID: 0018_add_comparison_cells
Revises: 0017_add_llm_retry_count
Create Date: 2026-06-06 22:15:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0018_add_comparison_cells"
down_revision: str | None = "0017_add_llm_retry_count"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "comparison_cells",
        sa.Column("cell_id", sa.String(length=64), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("step_id", sa.String(length=64), nullable=False),
        sa.Column("dimension", sa.String(length=32), nullable=False),
        sa.Column("competitor_id", sa.String(length=128), nullable=False),
        sa.Column("stance", sa.String(length=16), nullable=False),
        sa.Column("summary", sa.Text(), nullable=False),
        sa.Column(
            "evidence_ids",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["run_id"],
            ["runs.run_id"],
            name=op.f("fk_comparison_cells_run_id_runs"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["step_id"],
            ["steps.step_id"],
            name=op.f("fk_comparison_cells_step_id_steps"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("cell_id", name=op.f("pk_comparison_cells")),
    )
    op.create_index(op.f("ix_comparison_cells_dimension"), "comparison_cells", ["dimension"], unique=False)
    op.create_index(op.f("ix_comparison_cells_run_id"), "comparison_cells", ["run_id"], unique=False)
    op.create_index(op.f("ix_comparison_cells_step_id"), "comparison_cells", ["step_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_comparison_cells_step_id"), table_name="comparison_cells")
    op.drop_index(op.f("ix_comparison_cells_run_id"), table_name="comparison_cells")
    op.drop_index(op.f("ix_comparison_cells_dimension"), table_name="comparison_cells")
    op.drop_table("comparison_cells")
