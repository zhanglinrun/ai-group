"""add run_create_requests idempotency table

Revision ID: 0015_add_run_create_requests
Revises: 0014_add_runs_title_column
Create Date: 2026-06-02 18:38:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0015_add_run_create_requests"
down_revision: str | None = "0014_add_runs_title_column"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "run_create_requests",
        sa.Column("idempotency_key", sa.String(length=128), nullable=False),
        sa.Column("run_id", sa.String(length=64), nullable=False),
        sa.Column("request_hash", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("error_code", sa.String(length=64), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.ForeignKeyConstraint(["run_id"], ["runs.run_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("idempotency_key"),
    )
    op.create_index(
        "ix_run_create_requests_run_id",
        "run_create_requests",
        ["run_id"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index("ix_run_create_requests_run_id", table_name="run_create_requests")
    op.drop_table("run_create_requests")
