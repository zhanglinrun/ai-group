"""bind runs to platform users and persist token billing fields"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "0024_xiongdoctor_billing"
down_revision: str | None = "0023_watchlist_refresh_and_diffs"
branch_labels: Sequence[str] | None = None
depends_on: Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("runs", sa.Column("owner_user_id", sa.BigInteger(), nullable=False, server_default="0"))
    op.add_column("runs", sa.Column("reservation_id", sa.String(length=64), nullable=True))
    op.add_column("runs", sa.Column("reserved_micro_points", sa.BigInteger(), nullable=False, server_default="0"))
    op.add_column("runs", sa.Column("consumed_micro_points", sa.BigInteger(), nullable=False, server_default="0"))
    op.add_column("runs", sa.Column("billing_status", sa.String(length=32), nullable=False, server_default="NOT_STARTED"))
    op.add_column("runs", sa.Column("billing_error", sa.Text(), nullable=True))
    op.create_index("ix_runs_owner_user_id", "runs", ["owner_user_id"])
    op.create_index("ix_runs_reservation_id", "runs", ["reservation_id"])
    op.add_column("llm_calls", sa.Column("charged_micro_points", sa.BigInteger(), nullable=False, server_default="0"))
    op.add_column("llm_calls", sa.Column("price_version", sa.String(length=64), nullable=True))


def downgrade() -> None:
    op.drop_column("llm_calls", "price_version")
    op.drop_column("llm_calls", "charged_micro_points")
    op.drop_index("ix_runs_reservation_id", table_name="runs")
    op.drop_index("ix_runs_owner_user_id", table_name="runs")
    op.drop_column("runs", "billing_error")
    op.drop_column("runs", "billing_status")
    op.drop_column("runs", "consumed_micro_points")
    op.drop_column("runs", "reserved_micro_points")
    op.drop_column("runs", "reservation_id")
    op.drop_column("runs", "owner_user_id")
