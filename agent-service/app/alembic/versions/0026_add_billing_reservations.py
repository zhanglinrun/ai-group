"""optional JSON column on runs for leftover billing slices

New runs charge each LLM call in billing_meter and leave this null.

Revision ID: 0026_billing_reservations
Revises: 0025_run_status_reason
Create Date: 2026-08-20 22:30:00
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
from sqlalchemy.dialects.postgresql import JSONB
import sqlalchemy as sa

revision: str = "0026_billing_reservations"
down_revision: str | None = "0025_run_status_reason"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("runs", sa.Column("billing_reservations", JSONB(none_as_null=True), nullable=True))


def downgrade() -> None:
    op.drop_column("runs", "billing_reservations")
