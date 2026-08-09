"""add run knowledge

Revision ID: 0019_add_run_knowledge
Revises: 0018_add_comparison_cells
Create Date: 2026-06-07 11:40:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = "0019_add_run_knowledge"
down_revision: str | None = "0018_add_comparison_cells"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "run_knowledge",
        sa.Column("knowledge_id", sa.String(length=64), nullable=False),
        sa.Column("sequence_id", sa.BigInteger(), sa.Identity(), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("step_id", sa.String(length=64), nullable=False),
        sa.Column("schema_version", sa.String(length=16), nullable=False),
        sa.Column(
            "features",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "pricings",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "personas",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'[]'::jsonb"),
            nullable=False,
        ),
        sa.Column(
            "coverage",
            postgresql.JSONB(astext_type=sa.Text()),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(
            ["run_id"],
            ["runs.run_id"],
            name=op.f("fk_run_knowledge_run_id_runs"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["step_id"],
            ["steps.step_id"],
            name=op.f("fk_run_knowledge_step_id_steps"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("knowledge_id", name=op.f("pk_run_knowledge")),
    )
    op.create_index(op.f("ix_run_knowledge_run_id"), "run_knowledge", ["run_id"], unique=False)
    op.create_index(op.f("ix_run_knowledge_step_id"), "run_knowledge", ["step_id"], unique=False)


def downgrade() -> None:
    op.drop_index(op.f("ix_run_knowledge_step_id"), table_name="run_knowledge")
    op.drop_index(op.f("ix_run_knowledge_run_id"), table_name="run_knowledge")
    op.drop_table("run_knowledge")
