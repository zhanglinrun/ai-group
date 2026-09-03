"""add a fencing lease for multi-instance Agent Run execution"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0030_run_execution_lease"
down_revision: str | None = "0029_run_events"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "runs",
        sa.Column("execution_owner_token", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "runs",
        sa.Column("execution_lease_until", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_runs_execution_owner_token", "runs", ["execution_owner_token"])
    op.create_index("ix_runs_execution_lease_until", "runs", ["execution_lease_until"])


def downgrade() -> None:
    op.drop_index("ix_runs_execution_lease_until", table_name="runs")
    op.drop_index("ix_runs_execution_owner_token", table_name="runs")
    op.drop_column("runs", "execution_lease_until")
    op.drop_column("runs", "execution_owner_token")
