"""add user-facing terminal status_reason on runs

Revision ID: 0025_run_status_reason
Revises: 0024_xiongdoctor_billing
Create Date: 2026-08-18 23:10:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0025_run_status_reason"
down_revision: str | None = "0024_xiongdoctor_billing"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("runs", sa.Column("status_reason", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("runs", "status_reason")
